from __future__ import annotations
 
import json
import logging
import math
import os
import random
import threading
import time
import uuid
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
import urllib.request
import ssl
 
from dotenv import load_dotenv
from flask import Flask, jsonify, redirect, render_template, request, session, send_from_directory
from flask_sock import Sock
from giftpars import (
    epicgift_placeholder_url,
    get_gifts,
    get_master_catalogue,
    gift_type_key,
    lookup_gift_floor,
    refresh_master_catalogue,
)
 
BASE_DIR = Path(__file__).resolve().parent
TEMPLATES_DIR = BASE_DIR / "templates"
ANIMATION_DIR = BASE_DIR / "animation"
STATE_DB_FILE = BASE_DIR / "database.json"
 
load_dotenv(BASE_DIR / ".env")
 
MIN_BET             = float(os.getenv("MIN_BET",              "0.1"))
MIN_NFT_PAYOUT      = float(os.getenv("MIN_NFT_PAYOUT",       "1.0"))
CRASH_GROWTH_RATE   = float(os.getenv("CRASH_GROWTH_RATE",    "0.00021"))
CRASH_RTP           = float(os.getenv("CRASH_RTP",            "0.93"))
MINES_RTP           = float(os.getenv("MINES_RTP",            "0.93"))
UPGRADE_RTP         = float(os.getenv("UPGRADE_RTP",          "0.93"))
CRASH_PAYOUT_FACTOR = 1.0
MINES_PAYOUT_FACTOR = MINES_RTP
ROUND_COOLDOWN_SEC  = float(os.getenv("ROUND_COOLDOWN_SEC",   "8.0"))
CRASH_MAX_MULTIPLIER = float(os.getenv("CRASH_MAX_MULTIPLIER", "100.0"))
LOCAL_DEV_START_BALANCE = float(os.getenv("LOCAL_DEV_START_BALANCE", "0") or 0)
NFT_WITHDRAW_ADMIN_ID = 5394422216
WITHDRAW_FEE_TON = 0.2          # комиссия за любой вывод (TON или NFT), списывается с баланса
HOUSE_WALLET_ADDRESS = os.getenv("HOUSE_WALLET_ADDRESS", "UQBp8MGK02UMez-gbOC4NPSYZ5cyE2O2tI_t1BMzUvMw5m2y")
 
# Admin TG IDs — their bets are excluded from RTP stats (test bets)
ADMIN_IDS: set[int] = {5394422216}
 
TG_BOT_TOKEN    = os.getenv("TG_BOT_TOKEN",    "")
TG_BOT_USERNAME = os.getenv("TG_BOT_USERNAME", "")
TG_WEBAPP_URL   = os.getenv("TG_WEBAPP_URL",   "")
 
 
# ── Per-player state ──────────────────────────────────────────────────────────
 
@dataclass
class PlayerState:
    tg_user_id: int | None = None
    display_name: str = ""
    username: str = ""
    avatar_url: str = ""
    balance: float = 0.0
    total_won: float = 0.0
    total_wagered: float = 0.0
    referral_bonus_locked: float = 0.0
    referral_bonus_unlocked: float = 0.0
    referral_pending_bonus: float = 0.0
    referral_wager_left: float = 0.0
    referrer_sid: str | None = None
    referred_users: list[str] = field(default_factory=list)
    inventory: list[dict[str, Any]] = field(default_factory=list)
 
 
@dataclass
class GameStats:
    crash_wagered: float = 0.0
    crash_paid: float = 0.0
    mines_wagered: float = 0.0
    mines_paid: float = 0.0
    upgrade_wagered: float = 0.0
    upgrade_paid: float = 0.0


app = Flask(__name__, template_folder=str(TEMPLATES_DIR), static_folder=None)
app.secret_key = os.getenv("FLASK_SECRET_KEY", "betgift-dev-secret-key")
sock = Sock(app)
 
p_lock: threading.Lock = threading.Lock()
stats_lock: threading.Lock = threading.Lock()
states: dict[str, PlayerState] = {}
tg_id_to_sid: dict[int, str] = {}
game_stats = GameStats()
state_dirty: bool = False
LEGACY_FAKE_GIFT_IDS = {
    "fallback-mousse-cake",
    "fallback-vice-cream",
    "fallback-homemade-cake",
    "fallback-candy-cane",
    "fallback-jelly-bunny",
    "fallback-cookie-heart",
    "fragment-vicecream-163821",
    "fragment-lunar-snake",
    "fragment-plush-pepe",
    "getgems-telegram-gift",
}
def real_catalogue_floor(name: str) -> float:
    base = str(name or "").split("#", 1)[0].strip().lower()
    if not base:
        return 0.0
    prices: list[float] = []
    for item in get_master_catalogue():
        item_base = str(item.get("name") or "").split("#", 1)[0].strip().lower()
        if item_base != base:
            continue
        try:
            price = float(item.get("price_ton") or item.get("payout_ton") or 0)
        except Exception:
            price = 0.0
        if price > 0:
            prices.append(price)
    return round(min(prices), 4) if prices else 0.0


def normalize_gift_floor(item: dict[str, Any] | None) -> dict[str, Any] | None:
    if not item:
        return item
    out = dict(item)
    key = gift_type_key(str(out.get("name") or ""))
    item_id = str(out.get("id") or "")
    needs_lookup = (
        item_id in LEGACY_FAKE_GIFT_IDS
        or item_id.startswith("fallback-")
    )
    if not needs_lookup:
        return out
    floor_item = lookup_gift_floor(str(out.get("name") or ""))
    if floor_item:
        floor = float(floor_item.get("price_ton") or 0)
        if floor > 0:
            out["price_ton"] = floor
            out["payout_ton"] = floor
            out["name"] = floor_item.get("name") or out.get("name")
            out["number"] = floor_item.get("number")
            out["id"] = floor_item.get("id") or out.get("id")
    return out


def refresh_gift_floor(item: dict[str, Any] | None, payout: float | None = None) -> dict[str, Any] | None:
    if not item:
        return item
    out = dict(item)
    floor_item = lookup_gift_floor(str(out.get("name") or ""))
    if not floor_item:
        return out
    floor = float(floor_item.get("price_ton") or 0)
    if floor <= 0:
        return out
    if payout is not None and floor > float(payout):
        return out
    out["price_ton"] = floor
    out["payout_ton"] = floor
    out["name"] = floor_item.get("name") or out.get("name")
    out["number"] = floor_item.get("number")
    out["id"] = floor_item.get("id") or out.get("id")
    return out


def with_epicgift_image(item: dict[str, Any] | None) -> dict[str, Any] | None:
    if not item:
        return item
    out = normalize_gift_floor(item) or dict(item)
    out["image_url"] = epicgift_placeholder_url(str(out.get("name") or ""))
    out["animation_url"] = ""
    out["source"] = "random"
    item_id = str(out.get("id") or "")
    if item_id in LEGACY_FAKE_GIFT_IDS or item_id.startswith("fallback-"):
        floor = real_catalogue_floor(str(out.get("name") or ""))
        if floor > 0:
            out["price_ton"] = floor
            out["payout_ton"] = floor
        else:
            out["price_ton"] = 0
            out["payout_ton"] = 0
            out["source"] = "random"
    return out


def with_epicgift_images(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [with_epicgift_image(item) or item for item in items]
 
 
# ── Global round state ────────────────────────────────────────────────────────
 
g_lock: threading.Lock    = threading.Lock()
g_phase: str              = "cooldown"
g_cooldown_until: float   = 0.0
g_round_id: str           = ""
g_round_crash_at: float   = 0.0
g_round_started_at: float = 0.0
g_history: deque          = deque(maxlen=20)
g_pending_bets: dict[str, dict[str, Any]] = {}
g_active_bets: dict[str, dict[str, Any]]   = {}
g_last_round_players: list[dict[str, Any]] = []
_last_players_broadcast: float = 0.0
g_ws_sockets: dict[str, Any]     = {}
mines_games: dict[str, dict[str, Any]] = {}
 
 
# ── Utility ───────────────────────────────────────────────────────────────────
 
def now_mono() -> float:
    return time.monotonic()
 
 
def generate_crash_point() -> float:
    """
    Pure crash distribution: P(crash > x) = 1/x.
    Payouts use the shown multiplier exactly: 3 TON at x2 pays 6 TON.
    The house edge is in the crash distribution: P(crash > x) = CRASH_RTP / x.
    """
    r   = random.random()
    raw = CRASH_RTP / max(1e-9, 1.0 - r)
    return min(CRASH_MAX_MULTIPLIER, max(1.0, math.floor(raw * 100) / 100))
 
 
def calc_multiplier(started_at: float) -> float:
    elapsed = now_mono() - started_at
    if elapsed <= 3.0:
        x = 1.0 + 0.1 * elapsed
    else:
        rate = CRASH_GROWTH_RATE * 1000.0 * 0.45
        x    = 1.3 * math.exp((elapsed - 3.0) * rate)
    return max(1.0, round(x, 2))


def calc_mines_multiplier(mines_count: int, safe_opened: int) -> float:
    if safe_opened <= 0:
        return 1.0
    total_cells = 25
    safe_cells = total_cells - mines_count
    if mines_count < 2 or mines_count > 24 or safe_opened > safe_cells:
        return 1.0
    fair = math.comb(total_cells, safe_opened) / max(1, math.comb(safe_cells, safe_opened))
    return round(fair * MINES_PAYOUT_FACTOR, 4)


def _rtp(paid: float, wagered: float) -> float:
    if wagered <= 0:
        return 0.0
    return round((paid / wagered) * 100, 2)


def public_rtp_stats() -> dict[str, Any]:
    with stats_lock:
        crash_wagered = float(game_stats.crash_wagered)
        crash_paid = float(game_stats.crash_paid)
        mines_wagered = float(game_stats.mines_wagered)
        mines_paid = float(game_stats.mines_paid)
        upgrade_wagered = float(game_stats.upgrade_wagered)
        upgrade_paid = float(game_stats.upgrade_paid)
    total_wagered = crash_wagered + mines_wagered + upgrade_wagered
    total_paid = crash_paid + mines_paid + upgrade_paid
    return {
        "crash": _rtp(crash_paid, crash_wagered),
        "mines": _rtp(mines_paid, mines_wagered),
        "upgrade": _rtp(upgrade_paid, upgrade_wagered),
        "total": _rtp(total_paid, total_wagered),
        "target_crash": round(CRASH_RTP * 100, 2),
        "target_mines": round(MINES_RTP * 100, 2),
        "target_upgrade": round(UPGRADE_RTP * 100, 2),
        "target_total": round(((CRASH_RTP + MINES_RTP + UPGRADE_RTP) / 3) * 100, 2),
        "crash_wagered": round(crash_wagered, 2),
        "crash_paid": round(crash_paid, 2),
        "mines_wagered": round(mines_wagered, 2),
        "mines_paid": round(mines_paid, 2),
        "upgrade_wagered": round(upgrade_wagered, 2),
        "upgrade_paid": round(upgrade_paid, 2),
        "total_wagered": round(total_wagered, 2),
        "total_paid": round(total_paid, 2),
    }


def record_game_wager(game: str, amount: float, is_admin: bool) -> None:
    if is_admin or amount <= 0:
        return
    with stats_lock:
        if game == "crash":
            game_stats.crash_wagered = round(game_stats.crash_wagered + amount, 4)
        elif game == "mines":
            game_stats.mines_wagered = round(game_stats.mines_wagered + amount, 4)
        elif game == "upgrade":
            game_stats.upgrade_wagered = round(game_stats.upgrade_wagered + amount, 4)
    mark_state_dirty()


def record_game_paid(game: str, amount: float, is_admin: bool) -> None:
    if is_admin or amount <= 0:
        return
    with stats_lock:
        if game == "crash":
            game_stats.crash_paid = round(game_stats.crash_paid + amount, 4)
        elif game == "mines":
            game_stats.mines_paid = round(game_stats.mines_paid + amount, 4)
        elif game == "upgrade":
            game_stats.upgrade_paid = round(game_stats.upgrade_paid + amount, 4)
    mark_state_dirty()


def public_mines_game(game: dict[str, Any], reveal_mines: bool = False) -> dict[str, Any]:
    revealed = sorted(int(x) for x in game.get("revealed", set()))
    mines = sorted(int(x) for x in game.get("mines", set())) if reveal_mines else []
    safe_opened = len(revealed)
    multiplier = calc_mines_multiplier(int(game.get("mines_count", 0)), safe_opened)
    payout = round(float(game.get("bet", 0)) * multiplier, 2) if safe_opened else 0.0
    preview = prize_preview_for_payout(payout)
    return {
        "active": bool(game.get("active")),
        "bet": round(float(game.get("bet", 0)), 2),
        "mines_count": int(game.get("mines_count", 0)),
        "revealed": revealed,
        "mines": mines,
        "safe_opened": safe_opened,
        "multiplier": multiplier,
        "payout": payout,
        "nft_preview": with_epicgift_image(preview["prize"]),
        "credited": preview["credited"],
    }
 
 
def online_count() -> int:
    with g_lock:
        return len(g_ws_sockets)
 
 
def public_player_name(player: PlayerState | None, sid: str) -> str:
    if player:
        if player.display_name:
            return player.display_name[:24]
        if player.username:
            return f"@{player.username[:24]}"
        if player.tg_user_id:
            return f"Игрок #{str(player.tg_user_id)[-4:]}"
    return f"Игрок {sid[:4].upper()}"
 
 
def build_round_players(
    phase: str,
    pending_bets: dict[str, dict[str, Any]],
    active_bets: dict[str, dict[str, Any]],
    viewer_sid: str | None = None,
    current_x: float = 1.0,
    include_nft: bool = True,
) -> list[dict[str, Any]]:
    tracked_sids = list({*pending_bets.keys(), *active_bets.keys()})
    with p_lock:
        players_snapshot = {sid: states.get(sid) for sid in tracked_sids}
 
    rows: list[dict[str, Any]] = []
    for sid in tracked_sids:
        player = players_snapshot.get(sid)
        active_info = active_bets.get(sid)
        pending_info = pending_bets.get(sid)
        bet_info = active_info or pending_info or {}
        tg_user_id = player.tg_user_id if player else None
        avatar_url = ""
        if player and player.avatar_url:
            avatar_url = player.avatar_url
        elif tg_user_id:
            avatar_url = f"/api/avatar/{tg_user_id}"
 
        status = "waiting"
        if phase == "active" and active_info:
            status = "cashed" if active_info.get("cashed_out_at") is not None else "flying"
 
        bet_value = round(float(bet_info.get("bet", 0.0)), 2)
        cashout_x = active_info.get("cashed_out_at") if active_info else None
        payout_value = round(float(active_info.get("payout", 0.0)), 2) if active_info else 0.0
        live_x = float(cashout_x or (current_x if phase == "active" and active_info else 1.0))
        live_payout = round(bet_value * live_x * CRASH_PAYOUT_FACTOR, 2) if phase == "active" and active_info and not cashout_x else payout_value
        nft_preview = None
        credited = live_payout
        if include_nft and live_payout > 0:
            if active_info and active_info.get("prize"):
                nft_preview = with_epicgift_image(active_info.get("prize"))
                credited = float(active_info.get("credited") or 0.0)
            else:
                preview = prize_preview_for_payout(live_payout)
                nft_preview = with_epicgift_image(preview["prize"])
                credited = preview["credited"]
 
        rows.append({
            "sid": sid,
            "tg_user_id": tg_user_id,
            "name": public_player_name(player, sid),
            "username": player.username if player else "",
            "avatar_url": avatar_url,
            "bet": bet_value,
            "status": status,
            "cashout_x": cashout_x,
            "auto_cashout_at": bet_info.get("auto_cashout_at"),
            "payout": payout_value,
            "current_x": round(live_x, 2),
            "live_payout": live_payout,
            "nft_preview": with_epicgift_image(active_info.get("last_prize")) if active_info and active_info.get("last_prize") else nft_preview,
            "credited": round(float(credited or 0.0), 4),
            "is_me": sid == viewer_sid,
        })
 
    status_order = {"flying": 0, "cashed": 1, "waiting": 2}
    rows.sort(key=lambda item: (0 if item["is_me"] else 1, status_order.get(item["status"], 9), -item["bet"], item["name"].lower()))
    return rows
 
 
def _build_players_for_broadcast(
    include_nft: bool = False,
    viewer_sid: str | None = None,
) -> tuple[str, list[dict]]:
    """Return (phase, players) for a realtime players broadcast.
 
    When `include_nft` is False, nft_preview is omitted (hot path — ticks).
    When True, nft_preview is computed (cold path — bet placed / cashout).
    """
    with g_lock:
        phase = g_phase
        pending_snap = dict(g_pending_bets)
        active_snap = dict(g_active_bets)
        started_at = g_round_started_at
    current_x = calc_multiplier(started_at) if phase == "active" else 1.0
 
    if phase != "active" and not pending_snap:
        if g_last_round_players:
            rows = []
            for row in g_last_round_players:
                copy = dict(row)
                copy["is_me"] = (copy.get("sid") == viewer_sid)
                rows.append(copy)
            return phase, rows
        return phase, []
 
    # Do catalogue lookups from RAM only so ticks never block on marketplace HTTP.
    rows = build_round_players(
        phase, pending_snap, active_snap,
        viewer_sid=viewer_sid,
        current_x=current_x, include_nft=include_nft,
    )
    return phase, rows
 
 
def broadcast_players(include_nft: bool = True) -> None:
    """Push the current players list to every connected socket.

    Each socket gets its own personalised copy with ``is_me`` set on the
    viewer's own row. Without that the player who just placed a bet didn't
    see themselves highlighted in the "Ставки раунда" widget — that's
    Bug-#2 ("в ставках раунда меня не показывает когда я ставлю").
    """
    try:
        with g_lock:
            sockets = dict(g_ws_sockets)
            phase = g_phase
            pending_snap = dict(g_pending_bets)
            active_snap = dict(g_active_bets)
            started_at = g_round_started_at
            last_round_snap = list(g_last_round_players)
        current_x = calc_multiplier(started_at) if phase == "active" else 1.0
        has_any = phase == "active" or bool(pending_snap)

        dead: list[str] = []
        for sid, ws in sockets.items():
            try:
                if has_any:
                    rows = build_round_players(
                        phase, pending_snap, active_snap,
                        viewer_sid=sid,
                        current_x=current_x, include_nft=include_nft,
                    )
                elif last_round_snap:
                    # Cooldown phase, no new bets yet — show the just-finished
                    # round so winners stay visible (green) and losers (red).
                    # Personalise ``is_me`` per viewer.
                    rows = []
                    for row in last_round_snap:
                        copy = dict(row)
                        copy["is_me"] = (copy.get("sid") == sid)
                        rows.append(copy)
                else:
                    rows = []
                ws.send(json.dumps({"event": "players", "phase": phase, "players": rows}))
            except Exception:
                dead.append(sid)
        if dead:
            with g_lock:
                for sid in dead:
                    g_ws_sockets.pop(sid, None)
    except Exception:
        logging.exception("broadcast_players failed")
 
 
def broadcast_all(msg: dict) -> None:
    with g_lock:
        sockets = dict(g_ws_sockets)
    dead    = []
    payload = json.dumps(msg)
    for sid, ws in sockets.items():
        try:
            ws.send(payload)
        except Exception:
            dead.append(sid)
    if dead:
        with g_lock:
            for sid in dead:
                g_ws_sockets.pop(sid, None)
 
 
def send_to(sid: str, msg: dict) -> None:
    with g_lock:
        ws = g_ws_sockets.get(sid)
    if ws:
        try:
            ws.send(json.dumps(msg))
        except Exception:
            with g_lock:
                g_ws_sockets.pop(sid, None)
 
 
def mark_state_dirty() -> None:
    global state_dirty
    state_dirty = True
 
 
def save_state_to_disk(force: bool = False) -> None:
    global state_dirty
    with p_lock:
        if not force and not state_dirty:
            return
        payload = {
            "states": {
                sid: {
                    "tg_user_id": p.tg_user_id,
                    "display_name": p.display_name,
                    "username": p.username,
                    "avatar_url": p.avatar_url,
                    "balance": p.balance,
                    "total_won": p.total_won,
                    "total_wagered": p.total_wagered,
                    "referral_bonus_locked": p.referral_bonus_locked,
                    "referral_bonus_unlocked": p.referral_bonus_unlocked,
                    "referral_pending_bonus": p.referral_pending_bonus,
                    "referral_wager_left": p.referral_wager_left,
                    "referrer_sid": p.referrer_sid,
                    "referred_users": p.referred_users,
                    "inventory": p.inventory,
                }
                for sid, p in states.items()
            },
            "tg_id_to_sid": {str(k): v for k, v in tg_id_to_sid.items()},
            "game_stats": {
                "crash_wagered": game_stats.crash_wagered,
                "crash_paid": game_stats.crash_paid,
                "mines_wagered": game_stats.mines_wagered,
                "mines_paid": game_stats.mines_paid,
                "upgrade_wagered": game_stats.upgrade_wagered,
                "upgrade_paid": game_stats.upgrade_paid,
            },
        }
 
    tmp_path = STATE_DB_FILE.with_suffix(".json.tmp")
    with tmp_path.open("w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
    tmp_path.replace(STATE_DB_FILE)
 
    with p_lock:
        state_dirty = False
 
 
def load_state_from_disk() -> None:
    if not STATE_DB_FILE.exists():
        return
 
    try:
        with STATE_DB_FILE.open("r", encoding="utf-8") as f:
            payload = json.load(f)
    except Exception:
        return
 
    loaded_states: dict[str, PlayerState] = {}
    for sid, raw in (payload.get("states") or {}).items():
        loaded_states[sid] = PlayerState(
            tg_user_id=raw.get("tg_user_id"),
            display_name=str(raw.get("display_name", "")),
            username=str(raw.get("username", "")),
            avatar_url=str(raw.get("avatar_url", "")),
            balance=float(raw.get("balance", 0.0)),
            total_won=float(raw.get("total_won", 0.0)),
            total_wagered=float(raw.get("total_wagered", 0.0)),
            referral_bonus_locked=float(raw.get("referral_bonus_locked", 0.0)),
            referral_bonus_unlocked=float(raw.get("referral_bonus_unlocked", 0.0)),
            referral_pending_bonus=float(raw.get("referral_pending_bonus", 0.0)),
            referral_wager_left=float(raw.get("referral_wager_left", 0.0)),
            referrer_sid=raw.get("referrer_sid"),
            referred_users=list(raw.get("referred_users", [])),
            inventory=list(raw.get("inventory", [])),
        )
 
    loaded_map: dict[int, str] = {}
    for k, v in (payload.get("tg_id_to_sid") or {}).items():
        try:
            loaded_map[int(k)] = v
        except Exception:
            continue
    raw_stats = payload.get("game_stats") or {}
 
    with p_lock:
        states.clear()
        states.update(loaded_states)
        tg_id_to_sid.clear()
        tg_id_to_sid.update(loaded_map)
    with stats_lock:
        game_stats.crash_wagered = float(raw_stats.get("crash_wagered", 0.0) or 0.0)
        game_stats.crash_paid = float(raw_stats.get("crash_paid", 0.0) or 0.0)
        game_stats.mines_wagered = float(raw_stats.get("mines_wagered", 0.0) or 0.0)
        game_stats.mines_paid = float(raw_stats.get("mines_paid", 0.0) or 0.0)
        game_stats.upgrade_wagered = float(raw_stats.get("upgrade_wagered", 0.0) or 0.0)
        game_stats.upgrade_paid = float(raw_stats.get("upgrade_paid", 0.0) or 0.0)
 
 
def autosave_worker() -> None:
    while True:
        try:
            save_state_to_disk(force=False)
        except Exception:
            pass
        time.sleep(2)
 
 
# ── Round manager ─────────────────────────────────────────────────────────────
 
def round_manager() -> None:
    global g_phase, g_cooldown_until
    global g_round_id, g_round_crash_at, g_round_started_at
    global g_pending_bets, g_active_bets, g_last_round_players
 
    with g_lock:
        g_cooldown_until = now_mono() + ROUND_COOLDOWN_SEC
 
    while True:
        try:
            with g_lock:
                phase = g_phase
 
            if phase == "cooldown":
                with g_lock:
                    remaining = g_cooldown_until - now_mono()
 
                if remaining <= 0:
                    new_id       = str(uuid.uuid4())
                    new_crash_at = generate_crash_point()
                    new_start    = now_mono()
 
                    with g_lock:
                        g_round_id         = new_id
                        g_round_crash_at   = new_crash_at
                        g_round_started_at = new_start
                        g_phase            = "active"
                        g_active_bets      = {
                            sid: {
                                "bet": info["bet"],
                                "cashed_out_at": None,
                                "payout": 0.0,
                                "auto_cashout_at": info.get("auto_cashout_at"),
                                "inventory_bet": info.get("inventory_bet"),
                            }
                            for sid, info in g_pending_bets.items()
                        }
                        g_pending_bets = {}
 
                    broadcast_all({"event": "round_start", "round_id": new_id, "online": online_count()})
                    broadcast_players(include_nft=False)
                else:
                    broadcast_all({"event": "cooldown", "cooldown_left": round(max(0.0, remaining), 2), "online": online_count()})
                    time.sleep(0.1)
 
            elif phase == "active":
                with g_lock:
                    started_at = g_round_started_at
                    crash_at   = g_round_crash_at
                    round_id   = g_round_id
 
                x = calc_multiplier(started_at)
 
                if x >= crash_at:
                    with g_lock:
                        # Final check for auto-cashout before crash
                        for sid, info in g_active_bets.items():
                            if info["cashed_out_at"] is None and info.get("auto_cashout_at"):
                                if info["auto_cashout_at"] <= crash_at:
                                    auto_x = info["auto_cashout_at"]
                                    payout = round(info["bet"] * auto_x * CRASH_PAYOUT_FACTOR, 2)
                                    info["cashed_out_at"] = auto_x
                                    info["payout"] = payout
                                    with p_lock:
                                        player = states.get(sid)
                                        if player:
                                            is_admin = player.tg_user_id in ADMIN_IDS
                                            credited, prize = award_crash_prize(player, payout)
                                            if not is_admin:
                                                player.total_won = round(player.total_won + payout, 2)
                                            record_game_paid("crash", payout, is_admin)
                                            mark_state_dirty()
                                            info["prize"] = prize
                                            info["credited"] = credited
                                            info["last_prize"] = prize
 
                        g_phase          = "cooldown"
                        g_cooldown_until = now_mono() + ROUND_COOLDOWN_SEC
                        g_history.appendleft({"multiplier": crash_at, "win": False})
                        bets_snap = dict(g_active_bets)
                        g_last_round_players = build_round_players("active", {}, bets_snap, current_x=crash_at)
                        for row in g_last_round_players:
                            if not row.get("cashout_x"):
                                row["status"] = "lost"
                                row["current_x"] = crash_at
                                row["live_payout"] = 0.0
                        g_active_bets = {}
                        history_list = list(g_history)
 
                    broadcast_all({
                        "event": "round_end", "round_id": round_id,
                        "crash_at": crash_at, "history": history_list,
                        "cooldown_left": ROUND_COOLDOWN_SEC, "online": online_count(),
                    })
                    broadcast_players(include_nft=False)
 
                    for sid, info in bets_snap.items():
                        with p_lock:
                            player = states.get(sid)
                        send_to(sid, {
                            "event":        "player_result",
                            "win":          info["cashed_out_at"] is not None,
                            "cashed_out_x": info["cashed_out_at"],
                            "payout":       info["payout"],
                            "credited":     info.get("credited", info["payout"]),
                            "prize":        with_epicgift_image(info.get("prize")),
                            "bet":          info["bet"],
                            "balance":      round(player.balance, 2) if player else None,
                        })
                else:
                    auto_cashout_events: list[dict[str, Any]] = []
                    with g_lock:
                        # check auto-cashouts during flight
                        for sid, info in g_active_bets.items():
                            if info["cashed_out_at"] is None and info.get("auto_cashout_at"):
                                if info["auto_cashout_at"] <= x:
                                    auto_x = info["auto_cashout_at"]
                                    payout = round(info["bet"] * auto_x * CRASH_PAYOUT_FACTOR, 2)
                                    info["cashed_out_at"] = auto_x
                                    info["payout"] = payout
                                    with p_lock:
                                        player = states.get(sid)
                                        if not player:
                                            continue
                                        is_admin = player.tg_user_id in ADMIN_IDS
                                        credited, prize = award_crash_prize(player, payout)
                                        bal = round(player.balance, 2)
                                        if not is_admin:
                                            player.total_won = round(player.total_won + payout, 2)
                                        record_game_paid("crash", payout, is_admin)
                                        mark_state_dirty()
                                        info["prize"] = prize
                                        info["credited"] = credited
                                        info["last_prize"] = prize
                                    auto_cashout_events.append({
                                        "sid": sid,
                                        "payload": {
                                        "event": "player_result",
                                        "win": True,
                                        "cashed_out_x": auto_x,
                                        "payout": payout,
                                        "credited": credited,
                                        "prize": with_epicgift_image(prize),
                                        "bet": info["bet"],
                                        "balance": bal,
                                        },
                                    })
 
                    for event in auto_cashout_events:
                        send_to(event["sid"], event["payload"])
 
                    broadcast_all({"event": "tick", "current_x": x, "round_id": round_id, "online": online_count()})
                    # Push the players list ~2×/sec so the "Ставки раунда"
                    # widget moves in lockstep with the X — without the
                    # per-client 1.2s polling lag.
                    global _last_players_broadcast
                    now_t = now_mono()
                    if now_t - _last_players_broadcast > 0.5:
                        _last_players_broadcast = now_t
                        broadcast_players(include_nft=True)
                    time.sleep(0.05)
        except Exception:
            logging.exception("round_manager loop error")
            time.sleep(0.1)
 
 
# ── Background TON polling ───────────────────────────────────────────────────
 
def toncenter_polling() -> None:
    last_hash = set()
    address = "UQBp8MGK02UMez-gbOC4NPSYZ5cyE2O2tI_t1BMzUvMw5m2y"
    while True:
        try:
            url = f"https://toncenter.com/api/v2/getTransactions?address={address}&limit=20"
            req = urllib.request.Request(url)
            ctx = ssl.create_default_context()
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
            with urllib.request.urlopen(req, context=ctx) as r:
                data = json.loads(r.read())
                
            for tx in data.get('result', []):
                tx_hash = tx.get('transaction_id', {}).get('hash')
                if not tx_hash or tx_hash in last_hash:
                    continue
                last_hash.add(tx_hash)
                
                in_msg = tx.get('in_msg', {})
                value = float(in_msg.get('value', 0)) / 1e9
                msg_text = in_msg.get('message', '')
                
                if msg_text.startswith("DEP-"):
                    tg_id_str = msg_text.split("-")[1]
                    if tg_id_str.isdigit():
                        tg_id = int(tg_id_str)
                        with p_lock:
                            target_sid = tg_id_to_sid.get(tg_id)
                        if target_sid and target_sid in states:
                            with p_lock:
                                states[target_sid].balance = round(states[target_sid].balance + value, 2)
                                bal = states[target_sid].balance
 
                                # Referral bonus: queue 10% of deposit, unlock after full playthrough or balance zero.
                                ref_sid = states[target_sid].referrer_sid
                                if ref_sid and ref_sid in states:
                                    bonus = round(value * 0.10, 4)
                                    states[target_sid].referral_pending_bonus = round(states[target_sid].referral_pending_bonus + bonus, 4)
                                    states[target_sid].referral_wager_left    = round(states[target_sid].referral_wager_left + value, 4)
                                    states[ref_sid].referral_bonus_locked     = round(states[ref_sid].referral_bonus_locked + bonus, 4)
                            mark_state_dirty()
 
                            send_to(target_sid, {"event": "notification", "message": f"💎 Ваш депозит на {value} TON успешно зачислен! Баланс пополнен."})
                            send_to(target_sid, {"event": "player_result", "balance": bal, "win": False})
        except Exception:
            pass
        time.sleep(15)
 
load_state_from_disk()
 
 
def gift_catalogue_refresher() -> None:
    """Background thread: keep the gift catalogue fresh (every 10 min) so
    award_crash_prize never has to touch the network on the hot tick path."""
    # Load last known catalogue immediately; network refresh can finish later.
    get_master_catalogue()
    while True:
        try:
            gifts = refresh_master_catalogue()
            logging.info("gift catalogue refreshed: %d items", len(gifts))
        except Exception:
            logging.exception("gift catalogue refresh failed")
        time.sleep(600)
 
 
threading.Thread(target=toncenter_polling, daemon=True).start()
threading.Thread(target=round_manager, daemon=True).start()
threading.Thread(target=autosave_worker, daemon=True).start()
threading.Thread(target=gift_catalogue_refresher, daemon=True).start()
 
 
# ── Helpers ───────────────────────────────────────────────────────────────────
 
def get_sid() -> str:
    sid = session.get("sid")
    if not sid:
        sid = str(uuid.uuid4())
        session["sid"] = sid
    return sid
 
 
def get_player(sid: str | None = None) -> PlayerState:
    if sid is None:
        sid = get_sid()
    with p_lock:
        if sid not in states:
            states[sid] = PlayerState()
            if LOCAL_DEV_START_BALANCE > 0:
                states[sid].balance = round(LOCAL_DEV_START_BALANCE, 2)
        return states[sid]
 
 
def inventory_item_index(player: PlayerState, item_id: str) -> int:
    for i, item in enumerate(player.inventory):
        if str(item.get("inventory_id") or item.get("id") or "") == item_id:
            return i
    return -1
 
 
def _pick_closest_gift(payout: float) -> dict[str, Any] | None:
    if payout < MIN_NFT_PAYOUT:
        return None
    """Find the single NFT whose price is closest to ``payout``.

    Reads from the pre-fetched in-memory catalogue only — NO HTTP on this
    path. The catalogue is refreshed every 10 min by a background thread.
    ``pick_closest_from_catalogue`` already falls back to the static
    fallback list (with the same overshoot cap) when the catalogue is
    cold, so we don't run a second by-distance pass here — that pass used
    to award a 12.5 TON Vice Cream for a 4.32 TON payout.
    """
    if payout <= 0:
        return None
    try:
        from giftpars import pick_closest_from_catalogue
        gift = pick_closest_from_catalogue(payout)
        if gift:
            gift = refresh_gift_floor(gift, payout) or gift
            if float(gift.get("price_ton") or 0) <= payout:
                return gift
    except Exception as e:
        logging.warning("award_crash_prize: catalogue lookup failed: %s", e)
    return None


def prize_preview_for_payout(payout: float) -> dict[str, Any]:
    gift = _pick_closest_gift(payout)
    if not gift:
        return {"prize": None, "credited": round(max(0.0, payout), 2)}
    gift_ton = float(gift.get("price_ton") or 0)
    if gift_ton <= 0:
        return {"prize": None, "credited": round(max(0.0, payout), 2)}
    prize = {
        "id": gift.get("id"),
        "name": gift.get("name") or "NFT",
        "number": gift.get("number"),
        "image_url": gift.get("image_url") or "",
        "animation_url": gift.get("animation_url") or "",
        "url": gift.get("url") or "",
        "source": gift.get("source"),
        "attrs": list(gift.get("attrs") or []),
        "price_ton": round(gift_ton, 4),
        "payout_ton": round(gift_ton, 4),
    }
    return {"prize": prize, "credited": round(max(0.0, payout - gift_ton), 4)}


def award_crash_prize(player: PlayerState, payout: float) -> tuple[float, dict[str, Any] | None]:
    """Give the player the NFT closest in price to `payout`.

    Player ALWAYS gets an NFT. If the chosen gift is cheaper than the payout
    the remainder is credited to the TON balance. If it overshoots the
    player just gets the more expensive NFT (house covers the overshoot).
    """
    preview = prize_preview_for_payout(payout)
    prize = preview["prize"]
    if prize:
        inventory_prize = with_epicgift_image(prize) or dict(prize)
        inventory_prize["inventory_id"] = str(uuid.uuid4())
        inventory_prize["won_at"] = int(time.time())
        player.inventory.append(inventory_prize)
        remainder = round(float(preview["credited"] or 0.0), 4)
        if remainder > 0:
            player.balance = round(player.balance + remainder, 4)
        return remainder, inventory_prize

    player.balance = round(player.balance + payout, 2)
    return payout, None


def award_mines_prize(player: PlayerState, payout: float) -> tuple[float, dict[str, Any] | None]:
    return award_crash_prize(player, payout)
 
 
def app_base_url() -> str:
    if TG_WEBAPP_URL:
        return TG_WEBAPP_URL.rstrip("/")
    return request.host_url.rstrip("/")
 
 
def give_balance_to_tg_user(tg_id: int, amount: float) -> tuple[bool, str, float]:
    if not isinstance(tg_id, int) or tg_id <= 0:
        return False, "no tg_id", 0.0
    if amount <= 0:
        return False, "amount must be > 0", 0.0
 
    with p_lock:
        sid = tg_id_to_sid.get(tg_id)
        if not sid:
            sid = str(uuid.uuid4())
            tg_id_to_sid[tg_id] = sid
            states[sid] = PlayerState()
            states[sid].tg_user_id = tg_id
 
        player = states[sid]
        player.balance = round(player.balance + amount, 2)
        balance = player.balance
        state_dirty_local = True
 
    if state_dirty_local:
        mark_state_dirty()
 
    send_to(sid, {"event": "notification", "message": f"💎 Администратор выдал вам {amount} TON!\nБаланс пополнен."})
    send_to(sid, {"event": "player_result", "balance": balance, "win": False})
    return True, sid, balance
 
 
def apply_withdraw_result(sid: str, status: str, amount: float, reason: str = "") -> tuple[bool, str, float]:
    if not sid:
        return False, "no sid", 0.0
    if status not in {"cancel", "done"}:
        return False, "invalid status", 0.0
 
    with p_lock:
        player = states.get(sid)
        if not player:
            return False, "not found", 0.0
        if status == "cancel":
            player.balance = round(player.balance + amount, 2)
            state_dirty_local = True
        else:
            state_dirty_local = False
        balance = player.balance
 
    if state_dirty_local:
        mark_state_dirty()
 
    if status == "cancel":
        send_to(sid, {"event": "notification", "message": f"❌ Вывод {amount} TON отменён. Причина: {reason}"})
    else:
        send_to(sid, {"event": "notification", "message": f"✅ Вывод {amount} TON успешно выполнен!"})
 
    send_to(sid, {"event": "player_result", "balance": balance, "win": False})
    return True, "", balance
 
 
# ── Routes ────────────────────────────────────────────────────────────────────
 
@app.get("/")
def root() -> Any:
    return render_template("index.html",
                           tg_bot_username=TG_BOT_USERNAME or "bet_gift_bot",
                           tg_webapp_url=TG_WEBAPP_URL)
 
 
@app.get("/obf")
def root_obf() -> Any:
    return render_template("index_obf.html")
 
 
@app.get("/styles.css")
def styles() -> Any:
    return send_from_directory(TEMPLATES_DIR, "styles.css")
 
 
@app.route("/tonconnect-manifest.json", methods=["GET", "OPTIONS"])
def tonconnect_manifest() -> Any:
    """Dynamic manifest — URL always matches the real host, fixes 404 in wallet."""
    if request.method == "OPTIONS":
        resp = app.response_class(status=200)
    else:
        base = app_base_url()
        payload = json.dumps({
            "url":      base,
            "name":     "BetGift",
            "iconUrl":  f"{base}/animation/ton.svg",
        })
        resp = app.response_class(response=payload, status=200, mimetype="application/json")
    
    resp.headers["Access-Control-Allow-Origin"] = "*"
    resp.headers["Access-Control-Allow-Methods"] = "GET, OPTIONS"
    resp.headers["Access-Control-Allow-Headers"] = "Content-Type"
    return resp
 
 
@app.get("/animation/<path:fname>")
def animations(fname: str) -> Any:
    return send_from_directory(ANIMATION_DIR, fname)
 
 
@app.get("/api/telegram/config")
def api_telegram_config() -> Any:
    return jsonify({"bot_username": TG_BOT_USERNAME, "webapp_url": TG_WEBAPP_URL, "has_bot_token": bool(TG_BOT_TOKEN)})
 
 
@app.get("/api/avatar/<int:user_id>")
def api_avatar(user_id: int) -> Any:
    """
    Proxy avatar bytes server-side so the bot token never appears in the
    browser's network tab (F12).  The client only ever sees /api/avatar/NNN.
    """
    token = TG_BOT_TOKEN
    if not token:
        return "No token", 404
    try:
        # Step 1 — get file_id
        with urllib.request.urlopen(
            f"https://api.telegram.org/bot{token}/getUserProfilePhotos?user_id={user_id}&limit=1"
        ) as r:
            data = json.loads(r.read())
        if not data.get("ok") or not data["result"].get("photos"):
            return "Avatar not found", 404
        file_id = data["result"]["photos"][0][-1]["file_id"]

        # Step 2 — resolve file path
        with urllib.request.urlopen(
            f"https://api.telegram.org/bot{token}/getFile?file_id={file_id}"
        ) as r:
            fdata = json.loads(r.read())
        if not fdata.get("ok"):
            return "Avatar not found", 404
        file_path = fdata["result"]["file_path"]

        # Step 3 — fetch the image server-side and stream it to the client.
        #          The token-bearing URL is only used inside this Python process.
        with urllib.request.urlopen(
            f"https://api.telegram.org/file/bot{token}/{file_path}"
        ) as img_resp:
            img_bytes = img_resp.read()
            content_type = img_resp.headers.get("Content-Type", "image/jpeg")

        from flask import Response
        resp = Response(img_bytes, status=200, mimetype=content_type)
        resp.headers["Cache-Control"] = "public, max-age=86400"
        return resp
    except Exception:
        return "Avatar not found", 404
 
 
@app.post("/api/register_tg")
def api_register_tg() -> Any:
    sid = get_sid()
    data = request.get_json(silent=True) or {}
    tg_id = data.get("tg_user_id")
    ref_id = data.get("ref_id")
    display_name = str(data.get("name") or "").strip()[:24]
    username = str(data.get("username") or "").strip().lstrip("@")[:32]
    avatar_url = str(data.get("avatar_url") or "").strip()[:512]
 
    if isinstance(tg_id, str) and tg_id.isdigit():
        tg_id = int(tg_id)
    if isinstance(ref_id, str) and ref_id.isdigit():
        ref_id = int(ref_id)
 
    if tg_id and isinstance(tg_id, int):
        with p_lock:
            existing_sid = tg_id_to_sid.get(tg_id)
 
            # If this tg account already has its own sid, reuse it.
            if existing_sid:
                existing_player = states.get(existing_sid)
                if existing_player and existing_player.tg_user_id not in (None, tg_id):
                    # Broken mapping in persisted DB: this sid belongs to another tg account.
                    effective_sid = str(uuid.uuid4())
                    states[effective_sid] = PlayerState(tg_user_id=tg_id)
                    tg_id_to_sid[tg_id] = effective_sid
                    mark_state_dirty()
                else:
                    effective_sid = existing_sid
            else:
                # If current sid is already bound to another tg account,
                # create isolated sid for this new account.
                current_player = states.get(sid)
                if current_player and current_player.tg_user_id and current_player.tg_user_id != tg_id:
                    effective_sid = str(uuid.uuid4())
                    states[effective_sid] = PlayerState()
                else:
                    effective_sid = sid
 
                tg_id_to_sid[tg_id] = effective_sid
 
            session["sid"] = effective_sid
 
            player = states.get(effective_sid)
            if not player:
                player = PlayerState()
                states[effective_sid] = player
 
            profile_changed = False
            if player.tg_user_id != tg_id:
                player.tg_user_id = tg_id
                profile_changed = True
            if player.display_name != display_name:
                player.display_name = display_name
                profile_changed = True
            if player.username != username:
                player.username = username
                profile_changed = True
            if player.avatar_url != avatar_url:
                player.avatar_url = avatar_url
                profile_changed = True
            if profile_changed:
                mark_state_dirty()
 
            if ref_id and isinstance(ref_id, int) and ref_id != tg_id and player.referrer_sid is None:
                referrer_sid = tg_id_to_sid.get(ref_id)
                if not referrer_sid:
                    # Create offline reference for referrer.
                    referrer_sid = str(uuid.uuid4())
                    tg_id_to_sid[ref_id] = referrer_sid
                    states[referrer_sid] = PlayerState()
                    states[referrer_sid].tg_user_id = ref_id
                    mark_state_dirty()
 
                referrer = states.get(referrer_sid)
                if referrer and effective_sid not in referrer.referred_users:
                    player.referrer_sid = referrer_sid
                    referrer.referred_users.append(effective_sid)
                    mark_state_dirty()
 
    return jsonify({"ok": True})
 
 
@app.get("/api/state")
def api_state() -> Any:
    sid    = get_sid()
    player = get_player(sid)
 
    with g_lock:
        phase         = g_phase
        cooldown_left = max(0.0, round(g_cooldown_until - now_mono(), 2))
        round_id      = g_round_id
        started_at    = g_round_started_at
        history       = list(g_history)
        my_active     = g_active_bets.get(sid)
        my_pending    = g_pending_bets.get(sid)
        pending_snap  = dict(g_pending_bets)
        active_snap   = dict(g_active_bets)
        last_players_snap = list(g_last_round_players)
 
    x      = calc_multiplier(started_at) if phase == "active" else 1.0
    my_bet = my_active or ({"bet": my_pending["bet"], "pending": True} if my_pending else None)
    if phase == "active" or pending_snap:
        players = build_round_players(phase, pending_snap, active_snap, viewer_sid=sid, current_x=x)
    else:
        # Re-tag is_me for this caller — the cached last_round_players was
        # built once at round end and doesn't know about new viewers.
        players = [dict(row, is_me=(row.get("sid") == sid)) for row in last_players_snap]
 
    return jsonify({
        "my_sid":        sid,
        "balance":       round(player.balance, 2),
        "total_won":     round(player.total_won, 2),
        "total_wagered": round(player.total_wagered, 2),
        "ref_locked":    round(player.referral_bonus_locked, 2),
        "ref_unlocked":  round(player.referral_bonus_unlocked, 2),
        "ref_count":     len(player.referred_users),
        "inventory":     with_epicgift_images(player.inventory),
        "crash": {
            "phase":         phase,
            "active":        phase == "active",
            "round_id":      round_id if phase == "active" else "",
            "current_x":     x if phase == "active" else 1.0,
            "cooldown_left": cooldown_left if phase == "cooldown" else 0.0,
            "history":       history,
            "my_bet":        my_bet,
            "players":       players,
            "payout_factor": CRASH_PAYOUT_FACTOR,
            "rtp":           public_rtp_stats(),
            "cooldown_sec":  ROUND_COOLDOWN_SEC,
            "online":        online_count(),
        },
    })
 
 
@app.get("/api/gifts")
def api_gifts() -> Any:
    source = str(request.args.get("source") or "both")
    sort = str(request.args.get("sort") or "price_asc")
    query = str(request.args.get("q") or "")
    try:
        limit = min(100, max(1, int(request.args.get("limit") or 60)))
    except Exception:
        limit = 60
    try:
        max_price = float(request.args.get("max_price") or 999999)
    except Exception:
        max_price = 999999
    gifts = get_gifts(source=source, sort=sort, name_filter=query, max_price=max_price, limit=limit)
    gifts = with_epicgift_images(gifts)
    return jsonify({"items": gifts, "count": len(gifts)})
 
 
@app.get("/api/inventory")
def api_inventory() -> Any:
    player = get_player()
    return jsonify({"items": with_epicgift_images(player.inventory), "count": len(player.inventory)})


@app.post("/api/mines/start")
def api_mines_start() -> Any:
    sid = get_sid()
    player = get_player(sid)
    data = request.get_json(silent=True) or {}

    # Support NFT bets (same as crash) ─────────────────────────────────────────
    raw_ids = data.get("inventory_item_ids") or (
        [data["inventory_item_id"]] if data.get("inventory_item_id") else []
    )
    inventory_bet_ids: list[str] = [str(i) for i in raw_ids if i]
    inventory_bet_items: list[dict[str, Any]] = []
    bet_from_nft = 0.0

    if inventory_bet_ids:
        for iid in inventory_bet_ids:
            idx = inventory_item_index(player, iid)
            if idx < 0:
                return jsonify({"error": f"NFT {iid} не найден в инвентаре"}), 404
            item = with_epicgift_image(player.inventory[idx])
            price = round(float(item.get("price_ton") or item.get("payout_ton") or 0), 4)
            if price <= 0:
                return jsonify({"error": f"NFT '{item.get('name')}' не имеет floor-цены"}), 400
            inventory_bet_items.append(item)
            bet_from_nft = round(bet_from_nft + price, 4)

    if inventory_bet_items:
        bet = bet_from_nft
    else:
        try:
            bet = round(float(data.get("bet", 0)), 2)
        except Exception:
            bet = 0.0

    try:
        mines_count = int(data.get("mines", 3))
    except Exception:
        mines_count = 3

    if bet <= 0:
        return jsonify({"error": "Нужна ставка"}), 400
    if not inventory_bet_items and bet < MIN_BET:
        return jsonify({"error": f"Минимальная ставка {MIN_BET:.1f} TON"}), 400
    if not inventory_bet_items and bet > player.balance:
        return jsonify({"error": "Недостаточно баланса"}), 400
    if mines_count < 2 or mines_count > 24:
        return jsonify({"error": "Мин должно быть от 2 до 24"}), 400

    with g_lock:
        old = mines_games.get(sid)
        if old and old.get("active"):
            return jsonify({"error": "Сначала завершите текущую игру в мины"}), 400
        mines = set(random.sample(range(25), mines_count))
        mines_games[sid] = {
            "active": True,
            "bet": bet,
            "mines_count": mines_count,
            "mines": mines,
            "revealed": set(),
            "started_at": time.time(),
            "inventory_bet_items": [with_epicgift_image(i) for i in inventory_bet_items],
        }

    # Deduct bet: remove NFTs or subtract balance
    if inventory_bet_items:
        for iid in reversed(inventory_bet_ids):
            idx = inventory_item_index(player, iid)
            if idx >= 0:
                player.inventory.pop(idx)
    else:
        player.balance = round(player.balance - bet, 2)

    is_admin = player.tg_user_id in ADMIN_IDS
    if not is_admin:
        player.total_wagered = round(player.total_wagered + bet, 2)
    record_game_wager("mines", bet, is_admin)
    mark_state_dirty()
    return jsonify({"ok": True, "balance": player.balance, "game": public_mines_game(mines_games[sid])})


@app.post("/api/mines/reveal")
def api_mines_reveal() -> Any:
    sid = get_sid()
    player = get_player(sid)
    data = request.get_json(silent=True) or {}
    try:
        cell = int(data.get("cell"))
    except Exception:
        return jsonify({"error": "Некорректная клетка"}), 400
    if cell < 0 or cell >= 25:
        return jsonify({"error": "Некорректная клетка"}), 400

    with g_lock:
        game = mines_games.get(sid)
        if not game or not game.get("active"):
            return jsonify({"error": "Нет активной игры"}), 400
        if cell in game["revealed"]:
            return jsonify({"error": "Клетка уже открыта"}), 400
        if cell in game["mines"]:
            game["active"] = False
            result = public_mines_game(game, reveal_mines=True)
            result["hit"] = cell
            result["lost"] = True
            result["payout"] = 0.0
            mark_state_dirty()
            return jsonify({"ok": True, "lost": True, "balance": round(player.balance, 2), "game": result})
        game["revealed"].add(cell)
        safe_cells = 25 - int(game["mines_count"])
        completed = len(game["revealed"]) >= safe_cells
        result = public_mines_game(game, reveal_mines=completed)
        if completed:
            game["active"] = False
            payout = result["payout"]
            is_admin = player.tg_user_id in ADMIN_IDS
            credited, prize = award_mines_prize(player, payout)
            if not is_admin:
                player.total_won = round(player.total_won + payout, 2)
            record_game_paid("mines", payout, is_admin)
            mark_state_dirty()
            result["completed"] = True
            result["credited"] = credited
            result["prize"] = with_epicgift_image(prize)
            result["nft_preview"] = with_epicgift_image(prize)

    return jsonify({"ok": True, "lost": False, "balance": round(player.balance, 2), "game": result})


@app.post("/api/mines/cashout")
def api_mines_cashout() -> Any:
    sid = get_sid()
    player = get_player(sid)
    with g_lock:
        game = mines_games.get(sid)
        if not game or not game.get("active"):
            return jsonify({"error": "Нет активной игры"}), 400
        if not game.get("revealed"):
            return jsonify({"error": "Откройте хотя бы одну клетку"}), 400
        game["active"] = False
        result = public_mines_game(game, reveal_mines=True)

    payout = round(float(result.get("payout") or 0), 2)
    is_admin = player.tg_user_id in ADMIN_IDS
    credited, prize = award_mines_prize(player, payout)
    if not is_admin:
        player.total_won = round(player.total_won + payout, 2)
    record_game_paid("mines", payout, is_admin)
    mark_state_dirty()
    result["credited"] = credited
    result["prize"] = with_epicgift_image(prize)
    result["nft_preview"] = with_epicgift_image(prize)
    return jsonify({"ok": True, "balance": player.balance, "payout": payout, "credited": credited, "prize": with_epicgift_image(prize), "game": result})


# ── Upgrade endpoints ─────────────────────────────────────────────────────────

@app.get("/api/upgrade/targets")
def api_upgrade_targets() -> Any:
    """Return catalogue items with price > min_price, deduplicated by gift type, sorted asc."""
    try:
        min_price = float(request.args.get("min_price", 0))
    except Exception:
        min_price = 0.0
    try:
        limit = min(50, max(1, int(request.args.get("limit", 20))))
    except Exception:
        limit = 20

    cat = get_master_catalogue()
    # Keep cheapest floor representative per gift type
    seen: dict[str, dict[str, Any]] = {}
    for item in cat:
        price = float(item.get("price_ton") or 0)
        if price <= min_price:
            continue
        key = gift_type_key(str(item.get("name") or ""))
        if not key:
            continue
        existing = seen.get(key)
        if existing is None or price < float(existing.get("price_ton") or 999999):
            seen[key] = item

    results = sorted(seen.values(), key=lambda x: float(x.get("price_ton") or 0))[:limit]
    return jsonify({"ok": True, "targets": [with_epicgift_image(t) for t in results]})


@app.post("/api/upgrade/play")
def api_upgrade_play() -> Any:
    """
    Attempt to upgrade a player's NFT to a higher-value target NFT.
    Win probability = min(0.97, source_price * UPGRADE_RTP / target_price).
    """
    sid = get_sid()
    player = get_player(sid)
    data = request.get_json(silent=True) or {}

    inventory_item_id = str(data.get("inventory_item_id") or "")
    target_name = str(data.get("target_name") or "")
    try:
        target_price_hint = float(data.get("target_price", 0))
    except Exception:
        target_price_hint = 0.0

    if not inventory_item_id:
        return jsonify({"error": "Не выбран NFT для апгрейда"}), 400
    if not target_name:
        return jsonify({"error": "Не выбрана цель апгрейда"}), 400

    # Find source item in inventory
    with p_lock:
        idx = inventory_item_index(player, inventory_item_id)
        if idx < 0:
            return jsonify({"error": "NFT не найден в инвентаре"}), 404
        source_item = with_epicgift_image(player.inventory[idx]) or dict(player.inventory[idx])

    source_price = float(source_item.get("price_ton") or source_item.get("payout_ton") or 0)
    if source_price <= 0:
        return jsonify({"error": "NFT не имеет floor-цены"}), 400

    # Find target in catalogue
    target_cat = lookup_gift_floor(target_name)
    if not target_cat:
        return jsonify({"error": f"Подарок '{target_name}' не найден в каталоге"}), 404
    target_price = float(target_cat.get("price_ton") or 0)
    if target_price <= 0:
        return jsonify({"error": "Цель не имеет floor-цены"}), 400
    if target_price <= source_price:
        return jsonify({"error": "Цель должна быть дороже текущего NFT"}), 400

    # Win probability
    win_prob = min(0.97, (source_price * UPGRADE_RTP) / target_price)

    # Remove source NFT
    with p_lock:
        idx2 = inventory_item_index(player, inventory_item_id)
        if idx2 >= 0:
            player.inventory.pop(idx2)

    is_admin = player.tg_user_id in ADMIN_IDS
    if not is_admin:
        player.total_wagered = round(player.total_wagered + source_price, 2)
    record_game_wager("upgrade", source_price, is_admin)

    # Roll
    won = random.random() < win_prob
    prize_item: dict[str, Any] | None = None

    if won:
        prize_item = with_epicgift_image(target_cat) or dict(target_cat)
        prize_item["inventory_id"] = str(uuid.uuid4())
        prize_item["won_at"] = int(time.time())
        with p_lock:
            player.inventory.append(prize_item)
        if not is_admin:
            player.total_won = round(player.total_won + target_price, 2)
        record_game_paid("upgrade", target_price, is_admin)

    mark_state_dirty()

    return jsonify({
        "ok": True,
        "won": won,
        "win_prob": round(win_prob * 100, 2),
        "source": source_item,
        "source_price": source_price,
        "target_price": target_price,
        "prize": prize_item,
        "balance": round(player.balance, 2),
    })


@app.post("/api/inventory/sell")
def api_inventory_sell() -> Any:
    player = get_player()
    data = request.get_json(silent=True) or {}
    item_id = str(data.get("item_id") or "")
    idx = inventory_item_index(player, item_id)
    if idx < 0:
        return jsonify({"error": "NFT не найден"}), 404
    item = with_epicgift_image(player.inventory.pop(idx)) or {}
    price = round(float(item.get("price_ton") or item.get("payout_ton") or 0), 4)
    if price <= 0:
        player.inventory.insert(idx, item)
        return jsonify({"error": "У NFT нет цены"}), 400
    player.balance = round(player.balance + price, 4)
    mark_state_dirty()
    return jsonify({"ok": True, "balance": player.balance, "sold_for": price, "items": with_epicgift_images(player.inventory)})
 
 
@app.post("/api/inventory/sell_all")
def api_inventory_sell_all() -> Any:
    player = get_player()
    if not player.inventory:
        return jsonify({"error": "Инвентарь пуст"}), 400

    sold_items: list[dict[str, Any]] = []
    kept_items: list[dict[str, Any]] = []
    total = 0.0
    for item in player.inventory:
        item = with_epicgift_image(item) or {}
        price = round(float(item.get("price_ton") or item.get("payout_ton") or 0), 4)
        if price > 0:
            total = round(total + price, 2)
            sold_items.append(item)
        else:
            kept_items.append(item)

    if total <= 0:
        return jsonify({"error": "У NFT нет цены"}), 400

    player.inventory = kept_items
    player.balance = round(player.balance + total, 4)
    mark_state_dirty()
    return jsonify({
        "ok": True,
        "balance": player.balance,
        "sold_for": total,
        "sold_count": len(sold_items),
        "items": with_epicgift_images(player.inventory),
    })
 
 
@app.post("/api/inventory/withdraw")
def api_inventory_withdraw() -> Any:
    sid = get_sid()
    player = get_player(sid)
    data = request.get_json(silent=True) or {}
    item_id = str(data.get("item_id") or "")
    idx = inventory_item_index(player, item_id)
    if idx < 0:
        return jsonify({"error": "NFT не найден"}), 404
    if player.balance < WITHDRAW_FEE_TON:
        return jsonify({"error": f"Недостаточно средств для комиссии ({WITHDRAW_FEE_TON} TON)"}), 400
    item = dict(player.inventory[idx])
    with p_lock:
        player.balance = round(player.balance - WITHDRAW_FEE_TON, 2)
    mark_state_dirty()
    username = f"@{player.username}" if player.username else "без username"
    text = (
        "ЗАЯВКА НА ВЫВОД\n"
        f"NFT: {item.get('name') or 'NFT'}\n"
        f"СТОИМОСТЬ: {float(item.get('price_ton') or item.get('payout_ton') or 0):.2f} TON\n"
        f"USERNAME / ID: {username} / {player.tg_user_id or sid}\n"
        f"КОМИССИЯ: {WITHDRAW_FEE_TON} TON (списана с баланса)\n"
        "ПЕРЕД ВЫВОДОМ: пользователь должен написать @akepka"
    )
    if TG_BOT_TOKEN:
        payload = {
            "chat_id": NFT_WITHDRAW_ADMIN_ID,
            "text": text,
            "reply_markup": {
                "inline_keyboard": [[
                    {"text": "Выполнен", "callback_data": f"nftwd_done:{player.tg_user_id}:{item_id}"},
                    {"text": "Отказ", "callback_data": f"nftwd_cancel:{player.tg_user_id}:{item_id}"}
                ]]
            }
        }
        try:
            req = urllib.request.Request(f"https://api.telegram.org/bot{TG_BOT_TOKEN}/sendMessage", data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"})
            ctx = ssl.create_default_context()
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
            resp = urllib.request.urlopen(req, timeout=10, context=ctx)
            logging.info("NFT withdraw sendMessage OK: %s", resp.read().decode())
        except Exception as e:
            logging.error("NFT withdraw sendMessage FAILED: %s", e)
    send_to(sid, {"event": "notification", "message": f"Заявка отправлена. Комиссия {WITHDRAW_FEE_TON} TON списана с баланса. Напишите @akepka."})
    return jsonify({"ok": True, "message": f"Заявка отправлена. Комиссия {WITHDRAW_FEE_TON} TON списана.", "balance": player.balance})
 
 
def apply_nft_withdraw_result(sid: str, item_id: str, status: str) -> tuple[bool, str]:
    with p_lock:
        player = states.get(sid)
        if not player:
            return False, "not found"
        idx = inventory_item_index(player, item_id)
        if idx < 0:
            return False, "NFT не найден"
        if status == "done":
            player.inventory.pop(idx)
            mark_state_dirty()
    if status == "done":
        send_to(sid, {"event": "notification", "message": "✅ Гифт выведен."})
    else:
        send_to(sid, {"event": "notification", "message": "❌ Отказали в выводе. Напишите @akepka для уточнения."})
    return True, ""
 
 
@app.post("/api/crash/start")
def api_crash_start() -> Any:
    sid    = get_sid()
    player = get_player(sid)
    data   = request.get_json(silent=True) or {}
 
    # Принимаем как "inventory_item_ids" (список), так и "inventory_item_id" (одиночный)
    raw_ids = data.get("inventory_item_ids") or (
        [data["inventory_item_id"]] if data.get("inventory_item_id") else []
    )
    inventory_bet_ids: list[str] = [str(i) for i in raw_ids if i]
    inventory_bet_items: list[dict[str, Any]] = []
    bet_from_nft = 0.0

    if inventory_bet_ids:
        for iid in inventory_bet_ids:
            idx = inventory_item_index(player, iid)
            if idx < 0:
                return jsonify({"error": f"NFT {iid} не найден в инвентаре"}), 404
            item = with_epicgift_image(player.inventory[idx])
            price = round(float(item.get("price_ton") or item.get("payout_ton") or 0), 4)
            if price <= 0:
                return jsonify({"error": f"NFT '{item.get('name')}' не имеет floor-цены"}), 400
            inventory_bet_items.append(item)
            bet_from_nft = round(bet_from_nft + price, 4)

    if inventory_bet_items:
        bet = bet_from_nft
        inventory_bet_item = inventory_bet_items[0] if len(inventory_bet_items) == 1 else {
            "id": "multi-nft", "name": f"{len(inventory_bet_items)} NFT",
            "image_url": inventory_bet_items[0].get("image_url", ""),
            "price_ton": bet_from_nft, "payout_ton": bet_from_nft,
        }
    else:
        inventory_bet_item = None
        try:
            bet = float(data.get("bet", 0))
        except Exception:
            bet = 0.0
 
    try:
        auto_x = data.get("auto_cashout_at")
        auto_cashout_at = float(auto_x) if auto_x is not None else None
    except Exception:
        auto_cashout_at = None
 
    if auto_cashout_at is not None and auto_cashout_at < 1.01:
        return jsonify({"error": "auto_cashout_at должен быть не ниже 1.01"}), 400
    if auto_cashout_at is not None and auto_cashout_at > CRASH_MAX_MULTIPLIER:
        return jsonify({"error": f"auto_cashout_at должен быть не выше {CRASH_MAX_MULTIPLIER:.2f}"}), 400
 
    if bet <= 0:
        return jsonify({"error": "Нужна ставка"}), 400
    if bet < MIN_BET:
        return jsonify({"error": f"Минимальная ставка {MIN_BET:.1f} TON"}), 400
 
    with g_lock:
        phase           = g_phase
        already_pending = sid in g_pending_bets
        already_active  = sid in g_active_bets
 
    if phase != "cooldown":
        return jsonify({"error": "Ставки принимаются только между раундами"}), 400
    if already_pending or already_active:
        return jsonify({"error": "Ставка уже принята"}), 400
    if not inventory_bet_item and bet > player.balance:
        return jsonify({"error": "Недостаточно баланса"}), 400
 
    if inventory_bet_items:
        for iid in reversed(inventory_bet_ids):
            idx = inventory_item_index(player, iid)
            if idx >= 0:
                player.inventory.pop(idx)
    else:
        player.balance = round(player.balance - bet, 2)
    is_admin = player.tg_user_id in ADMIN_IDS
    if not is_admin:
        player.total_wagered += bet
    record_game_wager("crash", bet, is_admin)
    mark_state_dirty()
 
    # Referral: unlock queued 10% bonus only after referred user plays deposit amount or loses all balance.
    if not is_admin and player.referrer_sid and player.referral_pending_bonus > 0:
        player.referral_wager_left = round(max(0.0, player.referral_wager_left - bet), 4)
        should_unlock = player.referral_wager_left <= 0.0 or player.balance <= 0.0
        if should_unlock:
            pending_bonus = player.referral_pending_bonus
            player.referral_pending_bonus = 0.0
            player.referral_wager_left = 0.0
 
            with p_lock:
                referrer = states.get(player.referrer_sid)
                if referrer and pending_bonus > 0:
                    referrer.balance = round(referrer.balance + pending_bonus, 2)
                    referrer.referral_bonus_locked = round(max(0.0, referrer.referral_bonus_locked - pending_bonus), 4)
                    referrer.referral_bonus_unlocked = round(referrer.referral_bonus_unlocked + pending_bonus, 4)
            mark_state_dirty()
 
    with g_lock:
        g_pending_bets[sid] = {
            "bet": bet,
            "auto_cashout_at": auto_cashout_at,
            "inventory_bet": with_epicgift_image(inventory_bet_item),
            "inventory_bet_items": [with_epicgift_image(i) for i in inventory_bet_items],
        }
 
    # Realtime push of updated players so the widget shows the new bet
    # immediately (no 1.2s polling lag).
    broadcast_players(include_nft=True)
 
    return jsonify({"ok": True, "balance": round(player.balance, 2)})
 
 
@app.post("/api/crash/cashout")
def api_crash_cashout() -> Any:
    sid    = get_sid()
    player = get_player(sid)
 
    with g_lock:
        phase      = g_phase
        bet_info   = g_active_bets.get(sid)
        started_at = g_round_started_at
        crash_at   = g_round_crash_at
        round_id   = g_round_id
 
    if phase != "active":
        return jsonify({"error": "Раунд не активен"}), 400
    if bet_info is None:
        return jsonify({"error": "Нет активной ставки"}), 400
    if bet_info["cashed_out_at"] is not None:
        return jsonify({"error": f"Уже выведено на x{bet_info['cashed_out_at']:.2f}"}), 400
 
    x = calc_multiplier(started_at)
    if x >= crash_at:
        return jsonify({"crashed": True, "crash_at": crash_at}), 200
 
    x      = max(1.0, x)
    payout = round(bet_info["bet"] * x * CRASH_PAYOUT_FACTOR, 2)
 
    with g_lock:
        if sid in g_active_bets:
            g_active_bets[sid]["cashed_out_at"] = round(x, 2)
            g_active_bets[sid]["payout"]         = payout
 
    is_admin = player.tg_user_id in ADMIN_IDS
    if not is_admin:
        player.total_won = round(player.total_won + payout, 2)
    credited, prize = award_crash_prize(player, payout)
    with g_lock:
        if sid in g_active_bets:
            g_active_bets[sid]["credited"] = credited
            g_active_bets[sid]["prize"] = prize
            g_active_bets[sid]["last_prize"] = prize
    record_game_paid("crash", payout, is_admin)
    mark_state_dirty()
 
    broadcast_players(include_nft=True)
 
    return jsonify({"crashed": False, "cashout_x": round(x, 2), "payout": payout,
                    "credited": credited, "prize": with_epicgift_image(prize),
                    "balance": round(player.balance, 2), "round_id": round_id})
 
 
@app.post("/api/withdraw")
def api_withdraw() -> Any:
    sid     = get_sid()
    player  = get_player(sid)
    data    = request.get_json(silent=True) or {}
    amount  = float(data.get("amount", 0))
    address = data.get("address", "")

    total_deduct = round(amount + WITHDRAW_FEE_TON, 2)   # сумма + комиссия 0.2 TON

    if amount < 5:
        return jsonify({"error": "Минимум 5 TON"}), 400
    if total_deduct > player.balance:
        return jsonify({"error": f"Недостаточно средств (нужно {total_deduct} TON включая комиссию {WITHDRAW_FEE_TON} TON)"}), 400

    with p_lock:
        player.balance = round(player.balance - total_deduct, 2)
    mark_state_dirty()

    username = f"@{player.username}" if player.username else "без username"
    if TG_BOT_TOKEN:
        payload = {
            "chat_id": NFT_WITHDRAW_ADMIN_ID,
            "text": (
                f"📤 *Новая заявка на вывод TON*\n\n"
                f"👤 {username} / ID: {player.tg_user_id}\n"
                f"💰 Сумма: {amount} TON\n"
                f"🔧 Комиссия: {WITHDRAW_FEE_TON} TON (списана с баланса)\n"
                f"💼 Кошелёк: `{address}`"
            ),
            "parse_mode": "Markdown",
            "reply_markup": {
                "inline_keyboard": [[
                    {"text": "✅ Выполнено", "callback_data": f"wd_done_{sid}_{amount}"},
                    {"text": "❌ Отмена",    "callback_data": f"wd_cancel_{sid}_{amount}"}
                ]]
            }
        }
        try:
            req = urllib.request.Request(
                f"https://api.telegram.org/bot{TG_BOT_TOKEN}/sendMessage",
                data=json.dumps(payload).encode(),
                headers={"Content-Type": "application/json"},
            )
            urllib.request.urlopen(req)
        except Exception:
            pass

    send_to(sid, {"event": "notification", "message": f"✅ Заявка на вывод {amount} TON отправлена. Комиссия {WITHDRAW_FEE_TON} TON списана с баланса."})
    return jsonify({"ok": True, "balance": player.balance, "fee": WITHDRAW_FEE_TON})
 
 
@app.post("/api/internal/withdraw_result")
def api_internal_withdraw_result() -> Any:
    data = request.get_json(silent=True) or {}
    sid = data.get("sid")
    status = data.get("status")
    amount = float(data.get("amount", 0))
    reason = data.get("reason", "")
 
    ok, err, balance = apply_withdraw_result(sid=sid, status=status, amount=amount, reason=reason)
    if not ok:
        return jsonify({"error": err}), 400 if err != "not found" else 404
    return jsonify({"ok": True, "balance": balance})
 
 
@app.post("/api/internal/give_balance")
def api_internal_give_balance() -> Any:
    data = request.get_json(silent=True) or {}
    tg_id = data.get("tg_id")
    amount = float(data.get("amount", 0))
 
    ok, err_or_sid, balance = give_balance_to_tg_user(tg_id=tg_id, amount=amount)
    if not ok:
        return jsonify({"error": err_or_sid}), 400
    return jsonify({"ok": True, "sid": err_or_sid, "balance": balance})
 
 
# ── WebSocket ─────────────────────────────────────────────────────────────────
 
@sock.route("/ws/crash")
def ws_crash(ws: Any) -> None:
    sid    = get_sid()
    player = get_player(sid)
 
    with g_lock:
        g_ws_sockets[sid] = ws
        phase         = g_phase
        cooldown_left = max(0.0, round(g_cooldown_until - now_mono(), 2))
        round_id      = g_round_id
        started_at    = g_round_started_at
        history       = list(g_history)
        my_active     = g_active_bets.get(sid)
        my_pending    = g_pending_bets.get(sid)
        pending_snap  = dict(g_pending_bets)
        active_snap   = dict(g_active_bets)
        inventory_snap = with_epicgift_images(player.inventory)
        last_round_snap = list(g_last_round_players)
 
    x      = calc_multiplier(started_at) if phase == "active" else 1.0
    my_bet = my_active or ({"bet": my_pending["bet"], "pending": True} if my_pending else None)
 
    # Players visible during cooldown (pending bets) so the bets widget shows
    # immediately on page load, not only after the round starts. If we're
    # in cooldown right after a round ended and there are no fresh bets
    # yet, fall back to the last round's results so winners/losers stay on
    # screen for a beat.
    players_snap: list[dict[str, Any]] = []
    if phase == "active" or pending_snap:
        try:
            players_snap = build_round_players(phase, pending_snap, active_snap, viewer_sid=sid, current_x=x)
        except Exception:
            players_snap = []
    elif last_round_snap:
        for row in last_round_snap:
            copy = dict(row)
            copy["is_me"] = (copy.get("sid") == sid)
            players_snap.append(copy)
 
    try:
        ws.send(json.dumps({
            "event":         "snapshot",
            "my_sid":        sid,
            "balance":       round(player.balance, 2),
            "total_won":     round(player.total_won, 2),
            "total_wagered": round(player.total_wagered, 2),
            "ref_count":     len(player.referred_users),
            "history":       history,
            "phase":         phase,
            "round_id":      round_id if phase == "active" else "",
            "current_x":     x if phase == "active" else 1.0,
            "cooldown_left": cooldown_left if phase == "cooldown" else 0.0,
            "my_bet":        my_bet,
            "payout_factor": CRASH_PAYOUT_FACTOR,
            "rtp":           public_rtp_stats(),
            "cooldown_sec":  ROUND_COOLDOWN_SEC,
            "online":        online_count(),
            "inventory":     inventory_snap,
            "players":       players_snap,
        }))
 
        while True:
            try:
                ws.receive(timeout=60)
            except Exception:
                break
    finally:
        with g_lock:
            g_ws_sockets.pop(sid, None)
 
 
if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.getenv("PORT", "8044")), debug=True)