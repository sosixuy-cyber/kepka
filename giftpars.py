"""
giftpars.py — Portal-Market gift catalogue parser for BetGift.

Image URL format:
  https://storage.portal-market.com/portals-market/gifts/{collection_slug}/models/png/{model_slug}.png

All auth tokens come from the environment — NEVER hardcoded.
"""

from __future__ import annotations

import json
import logging
import os
import re
import threading
import time
from typing import Any

import requests

logger = logging.getLogger(__name__)

# ── Config ────────────────────────────────────────────────────────────────────

# Put your TMA token in .env as PORTAL_MARKET_AUTH (rotates periodically)
AUTH_TOKEN: str = os.getenv("PORTAL_MARKET_AUTH", "")

PORTAL_BASE    = "https://portal-market.com/api"
STORAGE_BASE   = "https://storage.portal-market.com/portals-market/gifts"
FRAGMENT_BASE  = "https://fragment.com/file/gifts"   # public, no auth needed
REFRESH_EVERY  = 600          # seconds between background refreshes
CATALOGUE_FILE = "floors.json"

_HEADERS = {
    "accept":          "application/json, text/plain, */*",
    "accept-language": "ru,en;q=0.9,en-GB;q=0.8,en-US;q=0.7",
    "referer":         "https://portal-market.com/",
    "origin":          "https://portal-market.com",
    "user-agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/128.0.0.0 Safari/537.36"
    ),
}


def _auth_headers() -> dict[str, str]:
    """Build request headers, injecting auth token if configured."""
    h = dict(_HEADERS)
    token = AUTH_TOKEN or os.getenv("PORTAL_MARKET_AUTH", "")
    if token:
        h["authorization"] = token
    return h


# ── In-memory catalogue ───────────────────────────────────────────────────────

_catalogue: list[dict[str, Any]] = []
_catalogue_lock = threading.Lock()
_catalogue_loaded = False


# ── Slug helpers ──────────────────────────────────────────────────────────────

# Known Telegram gift slugs that need exact mapping (spaces/hyphens stripped differently)
_SLUG_ALIASES: dict[str, str] = {
    "snakebox":       "snake-box",
    "xmasstocking":   "xmas-stocking",
    "xmasstockings":  "xmas-stocking",
    "chillflame":     "chill-flame",
    "lunarsnake":     "lunar-snake",
    "snowglobe":      "snow-globe",
    "lovepotion":     "love-potion",
    "berrybox":       "berry-box",
    "plushpepe":      "plush-pepe",
    "santahat":       "santa-hat",
    "candycane":      "candy-cane",
    "homemadecake":   "homemade-cake",
    "jazzbar":        "jazz-bar",
    "jazzybass":      "jazzy-bass",
    "duralumin":      "duralumin",
    "astralstaff":    "astral-staff",
    "heartcandle":    "heart-candle",
    "goldenstar":     "golden-star",
    "signetring":     "signet-ring",
    "vintagedoll":    "vintage-doll",
    "vintagecigar":   "vintage-cigar",
    "vicecream":      "vice-cream",
    "moussakee":      "mousse-cake",
    "moussecake":     "mousse-cake",
    "generictoken":   "generic-token",
    "cookieheart":    "cookie-heart",
    "jellybunny":     "jelly-bunny",
    "spicedwine":     "spiced-wine",
    "easterroaster":  "easter-roaster",
    "eternalegg":     "eternal-egg",
}


def _slugify(text: str) -> str:
    """'Bunny Muffin' → 'bunnymuffin', 'Airy Souffle' → 'airysouffle'."""
    return re.sub(r"[^a-z0-9]", "", str(text or "").lower())


def _to_fragment_slug(name_or_slug: str) -> str:
    """Convert a gift name/slug to the canonical fragment.com slug.

    Handles hyphenated, space-separated, and run-together variants.
    E.g. "Snake Box" → "snake-box", "snakebox" → "snake-box"
    """
    raw = str(name_or_slug or "").strip()
    # Strip number suffix
    base = raw.split("#", 1)[0].strip()
    # Check alias map first (run-together form)
    compressed = re.sub(r"[^a-z0-9]", "", base.lower())
    if compressed in _SLUG_ALIASES:
        return _SLUG_ALIASES[compressed]
    # Convert spaces/underscores to hyphens, lowercase
    hyphened = re.sub(r"[\s_]+", "-", base.lower())
    hyphened = re.sub(r"[^a-z0-9-]", "", hyphened)
    return hyphened.strip("-")


def gift_type_key(name: str) -> str:
    """Stable lookup key for a gift type — strips the '#NNN' number suffix."""
    base = str(name or "").split("#", 1)[0].strip()
    return _slugify(base)


# ── Image URL ─────────────────────────────────────────────────────────────────

def _build_image_url(collection_slug: str, model_slug: str) -> str:
    return f"{STORAGE_BASE}/{collection_slug}/models/png/{model_slug}.png"


def _fragment_image_url(slug: str) -> str:
    """
    Public fragment.com thumbnail — no auth required.
    Format: https://fragment.com/file/gifts/{slug}/thumb.webp
    Converts any name form to canonical fragment slug automatically.
    """
    canonical = _to_fragment_slug(slug)
    return f"{FRAGMENT_BASE}/{canonical}/thumb.webp"


def epicgift_placeholder_url(name: str) -> str:
    """
    Return the image URL for a gift name.

    Priority:
      1. Exact match from catalogue (has real image_url).
      2. Fuzzy match by type key (ignores #NNN suffix).
      3. Fragment CDN thumbnail (public, no auth): fragment.com/file/gifts/{slug}/thumb.webp
    """
    if not name:
        return ""

    with _catalogue_lock:
        cat = list(_catalogue)

    # 1 — exact name match
    for item in cat:
        if str(item.get("name") or "") == name and item.get("image_url"):
            return item["image_url"]

    # 2 — type key match (ignores #NNN)
    key = gift_type_key(name)
    for item in cat:
        if gift_type_key(str(item.get("name") or "")) == key and item.get("image_url"):
            return item["image_url"]

    # 3 — fall back to fragment CDN using canonical slug
    base = str(name).split("#", 1)[0].strip()
    slug = _to_fragment_slug(base)
    return _fragment_image_url(slug) if slug else ""


# ── API fetchers ──────────────────────────────────────────────────────────────

def _safe_float(val: Any, default: float = 0.0) -> float:
    try:
        return float(val)
    except (TypeError, ValueError):
        return default


def _get(url: str, params: dict | None = None) -> "requests.Response | None":
    """
    Try request without auth first (most endpoints are public).
    If 401/403, retry with PORTAL_MARKET_AUTH token.
    Returns None on unrecoverable error.
    """
    bare = {k: v for k, v in _HEADERS.items() if k != "authorization"}

    # attempt 1 — no auth (public endpoint)
    try:
        r = requests.get(url, params=params, headers=bare, timeout=30)
        if r.status_code not in (401, 403):
            r.raise_for_status()
            return r
    except requests.HTTPError:
        pass
    except Exception as e:
        logger.debug("portal-market GET %s (no-auth) error: %s", url, e)

    # attempt 2 — with auth token
    token = AUTH_TOKEN or os.getenv("PORTAL_MARKET_AUTH", "")
    if not token:
        logger.warning(
            "portal-market: %s requires auth but PORTAL_MARKET_AUTH is missing in .env\n"
            "  How to refresh: open portal-market.com in Telegram, F12 → Network → "
            "copy \'authorization\' header → put it in .env as PORTAL_MARKET_AUTH",
            url,
        )
        return None

    try:
        r = requests.get(url, params=params, headers=_auth_headers(), timeout=30)
        if r.status_code == 401:
            logger.warning(
                "portal-market: token expired (401) for %s\n"
                "  TMA tokens live ~24 h. To refresh:\n"
                "  1. Open https://portal-market.com in Telegram\n"
                "  2. F12 → Network → any /api/ call → copy Authorization header\n"
                "  3. Update PORTAL_MARKET_AUTH= in .env and restart the bot",
                url,
            )
            return None
        r.raise_for_status()
        return r
    except requests.HTTPError as e:
        logger.warning("portal-market GET %s failed: %s", url, e)
        return None
    except Exception as e:
        logger.warning("portal-market GET %s error: %s", url, e)
        return None


def _fetch_collections() -> list[dict[str, Any]]:
    """GET /api/collections?limit=500 — usually public, no auth needed."""
    r = _get(f"{PORTAL_BASE}/collections", params={"limit": 500})
    if r is None:
        return []
    try:
        data = r.json()
        return data.get("collections") or data.get("results") or []
    except Exception as e:
        logger.warning("portal-market: failed to parse collections: %s", e)
        return []


def _fetch_gifts_page(page: int = 1, limit: int = 100) -> list[dict[str, Any]]:
    """
    GET /api/gifts (or /items / /market) with auth-retry.
    Returns individual gift listings with model/collection info.
    """
    for endpoint in ("/gifts", "/items", "/market"):
        r = _get(
            f"{PORTAL_BASE}{endpoint}",
            params={"page": page, "limit": limit, "sort": "price_asc"},
        )
        if r is None:
            continue
        try:
            data = r.json()
        except Exception:
            continue
        items = (
            data.get("gifts")
            or data.get("items")
            or data.get("results")
            or (data if isinstance(data, list) else [])
        )
        if items:
            return items
    return []


def _parse_gift_item(raw: dict[str, Any], collection_floors: dict[str, float]) -> dict[str, Any] | None:
    """
    Normalise a raw API gift item into our internal format.

    Tries multiple field-name conventions from portal-market.
    """
    # ── name ──────────────────────────────────────────────────────────────────
    name = str(
        raw.get("name")
        or raw.get("title")
        or raw.get("gift_name")
        or ""
    ).strip()
    if not name:
        return None

    # ── collection slug ───────────────────────────────────────────────────────
    col = raw.get("collection") or {}
    if isinstance(col, str):
        col = {"name": col}
    col_name  = str(col.get("name") or col.get("title") or raw.get("collection_name") or "").strip()
    col_slug  = str(col.get("slug") or raw.get("collection_slug") or "").strip()
    if not col_slug:
        # Derive from collection name or gift name prefix
        col_slug = _slugify(col_name or name.split("#")[0])

    # ── model slug ────────────────────────────────────────────────────────────
    model = raw.get("model") or {}
    if isinstance(model, str):
        model = {"name": model}
    model_name = str(model.get("name") or model.get("title") or raw.get("model_name") or "").strip()
    model_slug = str(model.get("slug") or raw.get("model_slug") or "").strip()
    model_slug_from_api = bool(model_slug)   # True only when the API provided the slug
    if not model_slug:
        model_slug = _slugify(model_name or col_slug)

    # ── image URL ─────────────────────────────────────────────────────────────
    image_url = str(
        raw.get("image_url")
        or raw.get("image")
        or raw.get("preview_url")
        or model.get("image_url")
        or ""
    ).strip()

    if col_slug and model_slug_from_api:
        # Real model slug from API → use portal-market storage (highest quality)
        image_url = _build_image_url(col_slug, model_slug)
    elif not image_url and col_slug:
        # No API image and no real model slug → fragment CDN (public, always works)
        image_url = _fragment_image_url(col_slug)

    # ── price ─────────────────────────────────────────────────────────────────
    price_ton = _safe_float(
        raw.get("price_ton")
        or raw.get("price")
        or raw.get("floor_price")
        or collection_floors.get(col_slug, 0)
        or collection_floors.get(_slugify(col_name), 0)
    )

    # ── number ────────────────────────────────────────────────────────────────
    number = raw.get("number") or raw.get("serial_number") or raw.get("token_id")
    try:
        number = int(number) if number is not None else None
    except (TypeError, ValueError):
        number = None

    # ── attributes ────────────────────────────────────────────────────────────
    attrs_raw = raw.get("attributes") or raw.get("attrs") or raw.get("properties") or []
    attrs: list[dict[str, str]] = []
    for a in attrs_raw:
        if isinstance(a, dict):
            attrs.append({
                "trait_type": str(a.get("trait_type") or a.get("name") or ""),
                "value":      str(a.get("value") or ""),
            })

    item_id = str(raw.get("id") or raw.get("_id") or raw.get("item_id") or "")

    return {
        "id":              item_id,
        "name":            name,
        "number":          number,
        "collection_name": col_name,
        "collection_slug": col_slug,
        "model_name":      model_name,
        "model_slug":      model_slug,
        "image_url":       image_url,
        "animation_url":   str(raw.get("animation_url") or raw.get("lottie_url") or ""),
        "url":             str(raw.get("url") or raw.get("market_url") or f"https://portal-market.com/gift/{item_id}"),
        "price_ton":       round(price_ton, 4),
        "payout_ton":      round(price_ton, 4),
        "attrs":           attrs,
        "source":          "portal-market",
    }


# ── Catalogue build ───────────────────────────────────────────────────────────

def _build_catalogue_from_collections(collections: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """
    Build a minimal catalogue entry from each collection's floor price.
    Used as fallback when the /gifts endpoint is unavailable (e.g. token expired).

    Image URL priority (NO extra HTTP requests):
      1. Direct image field in the collection object (image_url, image, icon, …)
      2. fragment.com public thumbnail — always works without any auth token:
         https://fragment.com/file/gifts/{slug}/thumb.webp
    """
    result = []
    for col in collections:
        col_name  = str(col.get("name") or "").strip()
        col_slug  = str(col.get("slug") or _slugify(col_name)).strip()
        floor     = _safe_float(col.get("floor_price") or col.get("floor") or 0)
        if not col_name or floor <= 0:
            continue

        # 1 — direct image field (may be present when token works)
        image_url = str(
            col.get("image_url")
            or col.get("image")
            or col.get("icon")
            or col.get("preview_url")
            or col.get("cover_url")
            or col.get("avatar_url")
            or col.get("thumbnail")
            or col.get("cover")
            or col.get("preview")
            or ""
        ).strip()

        # 2 — fragment CDN (always public, no auth) — use canonical slug
        if not image_url:
            image_url = _fragment_image_url(_to_fragment_slug(col_slug))

        result.append({
            "id":              f"floor-{col_slug}",
            "name":            col_name,
            "number":          None,
            "collection_name": col_name,
            "collection_slug": col_slug,
            "model_name":      col_name,
            "model_slug":      col_slug,
            "image_url":       image_url,
            "animation_url":   "",
            "url":             f"https://portal-market.com/collection/{col_slug}",
            "price_ton":       round(floor, 4),
            "payout_ton":      round(floor, 4),
            "attrs":           [],
            "source":          "portal-market",
        })
    return result


def _fetch_full_catalogue() -> list[dict[str, Any]]:
    """
    Fetch the complete catalogue:
    1. Pull collections for floor prices.
    2. Pull individual gift items (paginated) for exact model images.
    3. Fall back to collection-floor entries if /gifts is unavailable.
    """
    collections = _fetch_collections()

    # Build a slug → floor_price lookup for price enrichment
    col_floors: dict[str, float] = {}
    for col in collections:
        n = str(col.get("name") or "").strip()
        s = str(col.get("slug") or _slugify(n))
        f = _safe_float(col.get("floor_price") or col.get("floor") or 0)
        if s and f > 0:
            col_floors[s] = f
        if n:
            col_floors[_slugify(n)] = f

    # Fetch individual gift items (up to 10 pages × 100 = 1 000 items)
    all_items: list[dict[str, Any]] = []
    for page in range(1, 11):
        page_items = _fetch_gifts_page(page=page, limit=100)
        if not page_items:
            break
        parsed = [p for raw in page_items if (p := _parse_gift_item(raw, col_floors))]
        all_items.extend(parsed)
        if len(page_items) < 100:
            break          # last page

    if all_items:
        logger.info("giftpars: fetched %d individual gifts", len(all_items))
        return all_items

    # No /gifts endpoint available — fall back to collection floors
    logger.info("giftpars: no individual gifts found, using collection floors (%d)", len(collections))
    return _build_catalogue_from_collections(collections)


# ── Public catalogue API ──────────────────────────────────────────────────────

def refresh_master_catalogue() -> list[dict[str, Any]]:
    """Fetch fresh data from portal-market and update the in-memory catalogue."""
    global _catalogue_loaded
    items = _fetch_full_catalogue()
    if items:
        with _catalogue_lock:
            _catalogue.clear()
            _catalogue.extend(items)
            _catalogue_loaded = True
        _save_catalogue(items)
    return items


def get_master_catalogue() -> list[dict[str, Any]]:
    """
    Return the in-memory catalogue, loading from disk/network if needed.
    Safe to call from any thread — never blocks on network after first load.
    """
    global _catalogue_loaded
    with _catalogue_lock:
        if _catalogue:
            return list(_catalogue)

    # Try disk cache first (fast, no network)
    disk = _load_catalogue()
    if disk:
        with _catalogue_lock:
            _catalogue.clear()
            _catalogue.extend(disk)
            _catalogue_loaded = True
        return disk

    # Cold start — fetch synchronously once
    logger.info("giftpars: cold start, fetching catalogue…")
    return refresh_master_catalogue()


def get_gifts(
    source: str = "both",
    sort: str = "price_asc",
    name_filter: str = "",
    max_price: float = 999_999.0,
    limit: int = 60,
) -> list[dict[str, Any]]:
    """
    Return catalogue items filtered and sorted for the /api/gifts endpoint.
    """
    cat = get_master_catalogue()
    q   = name_filter.strip().lower()

    result = []
    seen_ids: set[str] = set()
    for item in cat:
        item_id = str(item.get("id") or "")
        if item_id in seen_ids:
            continue
        seen_ids.add(item_id)

        price = _safe_float(item.get("price_ton") or 0)
        if price > max_price:
            continue
        if q and q not in str(item.get("name") or "").lower():
            continue
        result.append(item)

    # Sort
    reverse = sort.endswith("_desc")
    if "price" in sort:
        result.sort(key=lambda x: _safe_float(x.get("price_ton") or 0), reverse=reverse)
    elif "name" in sort:
        result.sort(key=lambda x: str(x.get("name") or "").lower(), reverse=reverse)

    return result[:limit]


def lookup_gift_floor(name: str) -> dict[str, Any] | None:
    """
    Find the catalogue entry whose type key matches `name` and has the
    lowest price_ton (floor price).
    """
    key = gift_type_key(name)
    if not key:
        return None

    cat = get_master_catalogue()
    candidates = [
        item for item in cat
        if gift_type_key(str(item.get("name") or "")) == key
        and _safe_float(item.get("price_ton") or 0) > 0
    ]
    if not candidates:
        return None

    return min(candidates, key=lambda x: _safe_float(x.get("price_ton") or 0))


def pick_closest_from_catalogue(payout: float) -> dict[str, Any] | None:
    """
    Find the catalogue item whose price_ton is the closest to `payout`
    without exceeding it (price_ton ≤ payout).

    Falls back to the cheapest item if nothing fits under payout.
    """
    if payout <= 0:
        return None

    cat = get_master_catalogue()
    eligible = [
        item for item in cat
        if 0 < _safe_float(item.get("price_ton") or 0) <= payout
    ]

    if not eligible:
        return None

    return max(eligible, key=lambda x: _safe_float(x.get("price_ton") or 0))


# ── Disk persistence ──────────────────────────────────────────────────────────

def _save_catalogue(items: list[dict[str, Any]]) -> None:
    try:
        with open(CATALOGUE_FILE, "w", encoding="utf-8") as f:
            json.dump(items, f, ensure_ascii=False, indent=2)
    except Exception as e:
        logger.warning("giftpars: failed to save catalogue: %s", e)


def _load_catalogue() -> list[dict[str, Any]]:
    try:
        with open(CATALOGUE_FILE, "r", encoding="utf-8") as f:
            data = json.load(f)
        if isinstance(data, list) and data:
            return data
        # Old format: dict of {name: floor_price}
        if isinstance(data, dict):
            return _build_catalogue_from_collections([
                {"name": k, "floor_price": v}
                for k, v in data.items()
                if isinstance(v, (int, float))
            ])
    except Exception:
        pass
    return []


# ── Standalone monitor (run directly) ────────────────────────────────────────

def _monitor() -> None:
    """Print floor changes to stdout — run as `python giftpars.py`."""
    from datetime import datetime

    old_floors: dict[str, float] = {
        item.get("name", ""): _safe_float(item.get("price_ton") or 0)
        for item in _load_catalogue()
    }

    while True:
        print(f"\n[{datetime.now():%Y-%m-%d %H:%M:%S}] ОБНОВЛЕНИЕ КАТАЛОГА\n")
        try:
            items = refresh_master_catalogue()
            if not items:
                print("  Ничего не получили, ждём следующего цикла")
            else:
                # Group by collection
                by_col: dict[str, list[dict]] = {}
                for item in items:
                    col = item.get("collection_name") or item.get("name", "?")
                    by_col.setdefault(col, []).append(item)

                for col_name in sorted(by_col):
                    entries = by_col[col_name]
                    floor = min(_safe_float(e.get("price_ton") or 0) for e in entries)
                    old_f = old_floors.get(col_name, 0.0)
                    change = ""
                    if old_f and old_f != floor:
                        arrow = "▲" if floor > old_f else "▼"
                        change = f"  {arrow} {old_f:.4f} → {floor:.4f}"
                    print(f"  {col_name}: {floor:.4f} TON{change}")

                old_floors = {
                    item.get("name", ""): _safe_float(item.get("price_ton") or 0)
                    for item in items
                }
                print(f"\nСохранено {len(items)} подарков")
        except Exception as e:
            print(f"[ОШИБКА] {e}")

        print(f"\nСплю {REFRESH_EVERY // 60} мин…\n")
        time.sleep(REFRESH_EVERY)


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    _monitor()