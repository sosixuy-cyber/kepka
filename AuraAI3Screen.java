package ru.etc1337.client.modules.impl.combat.aura.ai3;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import ru.etc1337.api.interfaces.QuickImports;
import ru.etc1337.api.render.font.Fonts;
import ru.etc1337.api.render.rect.ShapeProperties;
import ru.etc1337.api.util.color.ColorUtility;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * AuraAI3Screen v8 — Sliding Window обучение по образцу MouseMovementPredictor.
 *
 * RECORD: пишет (dxN, dyN, mvx, mvy, episode_start) когда мышь двигается.
 * TRAIN: 1000 эпох DJL/PyTorch, 40→128→64→32→2.
 * PREDICT: sliding window последних 20 кадров → нейронка → шаг с anti-stall.
 */
public final class AuraAI3Screen extends Screen implements QuickImports {
    private static final int SEQ_LEN = AuraAI3.SEQ_LEN;
    private static final double STEP_SCALE_PX = 30.0;
    private static final double MAX_STEP_PX = 35.0;
    private static final int OSC_WINDOW = 30;
    private static final double OSC_NET_THRESHOLD = 12.0;

    private final List<AimTarget> targets = new ArrayList<>();
    private final List<Particle> particles = new ArrayList<>();
    private final ArrayDeque<TrailPoint> trail = new ArrayDeque<>();
    private final ArrayDeque<float[]> screenSeq = new ArrayDeque<>(SEQ_LEN);
    private final ArrayDeque<float[]> recentPositions = new ArrayDeque<>();
    private final Random random = new Random();

    private boolean recording = false, predictMode = false, training = false;
    private float trainProgress = 0f;
    private int hits = 0, misses = 0;
    private boolean spawned = false;

    private float lastMX = -1f, lastMY = -1f;
    private float prevMX = -1f, prevMY = -1f;
    private float predX = -1f, predY = -1f;

    private long lastSampleTime = 0;
    private long lastPredictMove = 0;
    private int predictTicksOnTarget = 0;
    private int predictStallTicks = 0;

    // Animations
    private float jellyScale = 1f, jellyVel = 0f;
    private float panelScale = 0.92f, panelAlpha = 0f, animTime = 0f;
    private float[] btnHover = new float[5];
    private float[] btnActive = new float[5];

    private static final float PW = 740f, PH = 520f;
    private float pX, pY, gX, gY, gW, gH;
    private float[] btnX = new float[5], btnY_ = new float[5];
    private final float BW = 90f, BH = 28f;
    private final String[] LABELS = {"RECORD", "TRAIN", "PREDICT", "STOP", "CLEAR"};

    public AuraAI3Screen() { super(Text.literal("AuraAI3 Neuro Trainer")); }

    @Override protected void init() {
        pX = (width - PW) / 2f; pY = (height - PH) / 2f;
        gX = pX + 20f; gY = pY + 60f; gW = PW - 40f; gH = PH - 120f;
        float bY = pY + PH - 42f;
        float total = BW * 5 + 44f;
        float sx = pX + (PW - total) / 2f;
        for (int i = 0; i < 5; i++) { btnX[i] = sx + i * (BW + 11); btnY_[i] = bY; }

        targets.clear(); trail.clear(); particles.clear(); spawned = false;
        screenSeq.clear(); recentPositions.clear();
        lastMX = lastMY = prevMX = prevMY = -1; predX = predY = -1f;
        recording = false; predictMode = false; training = false;
        panelScale = 0.92f; panelAlpha = 0f;
        AuraAI3.get().resetSequence();
    }

    @Override public void render(DrawContext ctx, int mx, int my, float delta) {
        try { doRender(ctx, mx, my, delta); } catch (Exception e) { e.printStackTrace(); }
        super.render(ctx, mx, my, delta);
    }

    private void doRender(DrawContext ctx, int mx, int my, float delta) {
        var m = ctx.getMatrices();
        long now = System.currentTimeMillis();
        animTime += delta * 0.05f;

        panelScale += (1f - panelScale) * 0.15f;
        panelAlpha = Math.min(1f, panelAlpha + 0.05f);
        jellyVel += (1f - jellyScale) * 0.35f;
        jellyVel *= 0.6f;
        jellyScale += jellyVel;

        int alphaMain = (int)(panelAlpha * 255);
        float glowPulse = 0.5f + 0.5f * (float) Math.sin(animTime * 2f);

        // ═══ DIM ═══
        rectangle.render(ShapeProperties.create(m, 0, 0, width, height)
                .color(ColorUtility.getColor(0, 0, 0, (int)(panelAlpha * 130))).build());

        // ═══ PANEL (rectangle-based liquid glass) ═══
        float scaledW = PW * panelScale, scaledH = PH * panelScale;
        float spX = pX + (PW - scaledW) / 2f, spY = pY + (PH - scaledH) / 2f;

        // Multi-layer glow
        for (int i = 5; i > 0; i--) {
            int gA = (int)((8 + 12 * glowPulse) * panelAlpha * (i / 5f));
            rectangle.render(ShapeProperties.create(m, spX - i, spY - i, scaledW + i * 2, scaledH + i * 2)
                    .round(15f + i).color(ColorUtility.getColor(80, 150, 240, gA)).build());
        }
        // Main glass body
        rectangle.render(ShapeProperties.create(m, spX, spY, scaledW, scaledH)
                .round(14f).color(ColorUtility.getColor(8, 12, 22, (int)(panelAlpha * 215))).build());
        rectangle.render(ShapeProperties.create(m, spX + 1, spY + 1, scaledW - 2, scaledH - 2)
                .round(13f).color(ColorUtility.getColor(15, 25, 45, (int)(panelAlpha * 80))).build());
        // Top reflection
        rectangle.render(ShapeProperties.create(m, spX + 4, spY + 3, scaledW - 8, 35)
                .round(10f).color(ColorUtility.getColor(255, 255, 255, (int)(panelAlpha * 12))).build());

        // Header
        Fonts.MNTSB.get(14).drawString(m, "Neuro", pX + 22f, pY + 18f,
                ColorUtility.getColor(230, 240, 255, alphaMain));
        Fonts.MNTSB.get(11).drawString(m, "BWorld", pX + 88f, pY + 21f,
                ColorUtility.getColor(100, 160, 220, alphaMain));

        String mode = recording ? "mode=RECORD" : predictMode ? "mode=PREDICT" : "mode=IDLE";
        String info = mode + "  rec=" + AuraAI3.get().sampleCount() + "  hits=" + hits;
        if (AuraAI3.get().trained) info += String.format("  loss=%.3f", AuraAI3.get().lastLoss);
        float iw = Fonts.MNTSB.get(9).getStringWidth(info);
        Fonts.MNTSB.get(9).drawString(m, info, pX + PW - iw - 18f, pY + 21f,
                ColorUtility.getColor(150, 180, 210, alphaMain));

        rectangle.render(ShapeProperties.create(m, pX + 16, pY + 46, PW - 32, 1)
                .color(ColorUtility.getColor(100, 160, 220, (int)(panelAlpha * 35))).build());

        // ═══ GAME AREA ═══
        for (int i = 3; i > 0; i--) {
            int gA = (int)(8 * panelAlpha * (i / 3f));
            rectangle.render(ShapeProperties.create(m, gX - i, gY - i, gW + i * 2, gH + i * 2)
                    .round(13f + i).color(ColorUtility.getColor(60, 130, 200, gA)).build());
        }
        rectangle.render(ShapeProperties.create(m, gX, gY, gW, gH)
                .round(12f).color(ColorUtility.getColor(2, 4, 10, (int)(panelAlpha * 240))).build());
        rectangle.render(ShapeProperties.create(m, gX + 2, gY + 2, gW - 4, gH / 3)
                .round(10f).color(ColorUtility.getColor(15, 25, 45, (int)(panelAlpha * 35))).build());

        // ═══ Spawn target ═══
        if (!spawned && gW > 60) { spawned = true; spawnTarget(); }
        while (targets.size() < 1 && gW > 60) spawnTarget();

        AimTarget activeTarget = null;
        for (AimTarget t : targets) {
            activeTarget = t;
            float rad = t.rad * jellyScale;
            int tGlowA = (int)(20 + 18 * glowPulse);
            drawGlowCircle(ctx, t.x, t.y, rad + 14f, ColorUtility.getColor(80, 140, 255, tGlowA / 2));
            drawGlowCircle(ctx, t.x, t.y, rad + 8f, ColorUtility.getColor(110, 170, 255, tGlowA));
            drawOutlineCircle(ctx, t.x, t.y, rad, ColorUtility.getColor(160, 210, 255, 230));
            rectangle.render(ShapeProperties.create(m, t.x - 4f, t.y - 4f, 8f, 8f)
                    .round(4f).color(ColorUtility.getColor(120, 180, 255, 90)).build());
            rectangle.render(ShapeProperties.create(m, t.x - 2.5f, t.y - 2.5f, 5f, 5f)
                    .round(2.5f).color(ColorUtility.getColor(200, 230, 255, 255)).build());
        }

        // ═══ PREDICT (sliding window + anti-stall, как в MouseMovementPredictor) ═══
        if (predictMode && activeTarget != null && AuraAI3.get().trained) {
            if (predX < 0) { predX = gX + gW / 2f; predY = gY + gH / 2f; }

            tickPredict(activeTarget, now);

            // crosshair
            drawGlowCircle(ctx, predX, predY, 16f, ColorUtility.getColor(255, 80, 80, 25));
            drawGlowCircle(ctx, predX, predY, 11f, ColorUtility.getColor(255, 100, 100, 50));
            drawOutlineCircle(ctx, predX, predY, 7f, ColorUtility.getColor(255, 130, 130, 240));
            rectangle.render(ShapeProperties.create(m, predX - 18f, predY - 0.6f, 36f, 1.2f)
                    .color(ColorUtility.getColor(255, 110, 110, 180)).build());
            rectangle.render(ShapeProperties.create(m, predX - 0.6f, predY - 18f, 1.2f, 36f)
                    .color(ColorUtility.getColor(255, 110, 110, 180)).build());

            trail.add(new TrailPoint(predX, predY, now));
        } else {
            predX = mx; predY = my;
            trail.add(new TrailPoint(mx, my, now));
        }

        // ═══ RECORD ═══
        if (recording && activeTarget != null && lastMX >= 0) {
            tickRecord(activeTarget, mx, my);
        }
        prevMX = lastMX; prevMY = lastMY;
        lastMX = mx; lastMY = my;

        // ═══ Trail ═══
        trail.removeIf(pt -> now - pt.time > 1500);
        TrailPoint p1 = null;
        for (TrailPoint p2 : trail) {
            if (p1 != null) {
                float f = 1f - (float)(now - p1.time) / 1500f;
                if (f > 0) {
                    drawLine(ctx, p1.x, p1.y, p2.x, p2.y, 2.4f * f,
                            ColorUtility.getColor(80, 160, 255, (int)(f * 50)));
                    drawLine(ctx, p1.x, p1.y, p2.x, p2.y, 1.2f * f,
                            ColorUtility.getColor(150, 210, 255, (int)(f * 150)));
                }
            }
            p1 = p2;
        }

        // ═══ Particles ═══
        Iterator<Particle> pIt = particles.iterator();
        while (pIt.hasNext()) {
            Particle p = pIt.next();
            p.x += p.vx; p.y += p.vy;
            p.vx *= 0.92f; p.vy *= 0.92f; p.vy += 0.1f;
            p.life -= 0.02f;
            if (p.life <= 0) { pIt.remove(); continue; }
            float ps = 3.5f * p.life;
            int pa = (int)(p.life * 230);
            rectangle.render(ShapeProperties.create(m, p.x - ps, p.y - ps, ps * 2, ps * 2)
                    .round(ps).color(ColorUtility.getColor(p.r, p.g, p.b, pa)).build());
            rectangle.render(ShapeProperties.create(m, p.x - ps * 2, p.y - ps * 2, ps * 4, ps * 4)
                    .round(ps * 2).color(ColorUtility.getColor(p.r, p.g, p.b, pa / 4)).build());
        }

        // ═══ Buttons ═══
        boolean[] active = {recording, training, predictMode, false, false};
        for (int i = 0; i < 5; i++) {
            boolean hover = mx >= btnX[i] && mx <= btnX[i] + BW && my >= btnY_[i] && my <= btnY_[i] + BH;
            btnHover[i] += ((hover ? 1f : 0f) - btnHover[i]) * 0.2f;
            btnActive[i] += ((active[i] ? 1f : 0f) - btnActive[i]) * 0.15f;
            drawBtn(m, btnX[i], btnY_[i], LABELS[i], btnHover[i], btnActive[i]);
        }

        // ═══ Training overlay ═══
        if (training) {
            rectangle.render(ShapeProperties.create(m, gX, gY, gW, gH)
                    .round(12f).color(ColorUtility.getColor(2, 4, 10, 235)).build());
            String txt = "TRAINING (PyTorch): " + (int)(trainProgress * 100) + "%";
            Fonts.MNTSB.get(13).drawCenteredString(m, txt, gX + gW / 2f, gY + gH / 2f - 14, 0xFFA0D0FF);
            float bw = 280f, bh = 7f, bx = gX + (gW - bw) / 2f, by = gY + gH / 2f + 14;
            rectangle.render(ShapeProperties.create(m, bx - 1, by - 1, bw + 2, bh + 2).round(4f)
                    .color(ColorUtility.getColor(60, 100, 160, 80)).build());
            rectangle.render(ShapeProperties.create(m, bx, by, bw, bh).round(3.5f)
                    .color(ColorUtility.getColor(15, 25, 45, 255)).build());
            rectangle.render(ShapeProperties.create(m, bx, by, bw * trainProgress, bh).round(3.5f)
                    .color(ColorUtility.getColor(60, 130, 230, 255)).build());
            rectangle.render(ShapeProperties.create(m, bx, by, bw * trainProgress, bh / 2f).round(3f)
                    .color(ColorUtility.getColor(150, 210, 255, 200)).build());
        }

        String st = String.format("RECORD: %d frames | sliding window: %d/%d",
                AuraAI3.get().sampleCount(), screenSeq.size(), SEQ_LEN);
        Fonts.MNTSB.get(10).drawString(m, st, pX + 22f, pY + PH - 16f,
                ColorUtility.getColor(120, 150, 180, alphaMain));
    }

    /** Запись кадра как в MouseMovementPredictor.tickRecord */
    private void tickRecord(AimTarget target, int mx, int my) {
        float mvx = mx - lastMX, mvy = my - lastMY;
        if (Math.hypot(mvx, mvy) < 0.5f) return; // мышь стоит, не пишем

        // throttle 50ms
        long now = System.currentTimeMillis();
        if (now - lastSampleTime < 25) return; // ~40fps записи
        lastSampleTime = now;

        // Фичи: состояние ИЗ которого решали двинуться (lastMX/Y → prevPos)
        float dxN = (target.x - lastMX) / gW;
        float dyN = (target.y - lastMY) / gH;
        AuraAI3.get().addRow(dxN, dyN, mvx, mvy);
    }

    /** Predict как в MouseMovementPredictor.tickPredict */
    private void tickPredict(AimTarget target, long now) {
        float dx = target.x - predX;
        float dy = target.y - predY;
        float dist = (float) Math.hypot(dx, dy);

        // Hit
        if (dist < target.rad + 4) {
            predictTicksOnTarget++;
            if (predictTicksOnTarget >= 2) {
                spawnHitParticles(target.x, target.y);
                targets.remove(target); hits++;
                jellyScale = 1.4f; jellyVel = -0.2f;
                predictTicksOnTarget = 0;
                predictStallTicks = 0;
                AuraAI3.get().markEpisodeBoundary();
                AuraAI3.get().resetSequence();
                recentPositions.clear();
                if (mc.player != null) mc.player.playSound(
                        net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 1.6f);
                return;
            }
        } else {
            predictTicksOnTarget = 0;
        }

        // Throttle update (как в примере: 8ms)
        if (now - lastPredictMove < 8) return;
        lastPredictMove = now;

        float dxN = dx / gW;
        float dyN = dy / gH;

        // Получаем предикт через sliding window (внутри AuraAI3)
        float[] out = AuraAI3.get().predict(dxN, dyN);
        double moveX = out[0] * STEP_SCALE_PX;
        double moveY = out[1] * STEP_SCALE_PX;

        // Замедление у цели
        if (dist < 60) {
            double brake = Math.max(0.25, dist / 60.0);
            moveX *= brake; moveY *= brake;
        }

        // Cap
        double stepLen = Math.hypot(moveX, moveY);
        if (stepLen > MAX_STEP_PX) {
            moveX = moveX / stepLen * MAX_STEP_PX;
            moveY = moveY / stepLen * MAX_STEP_PX;
        }

        // anti-stall #1: модель зависла
        if (Math.hypot(moveX, moveY) < 0.4 && dist > target.rad) {
            predictStallTicks++;
            if (predictStallTicks > 6) {
                double nx = dx / Math.max(1.0, dist);
                double ny = dy / Math.max(1.0, dist);
                moveX = nx * 4.0; moveY = ny * 4.0;
                predictStallTicks = 0;
                AuraAI3.get().resetSequence();
            }
        } else {
            predictStallTicks = 0;
        }

        // anti-stall #2: осцилляция
        if (recentPositions.size() >= OSC_WINDOW && dist > target.rad) {
            float[] oldest = recentPositions.peekFirst();
            double netDisp = Math.hypot(predX - oldest[0], predY - oldest[1]);
            if (netDisp < OSC_NET_THRESHOLD) {
                double nx = dx / Math.max(1.0, dist);
                double ny = dy / Math.max(1.0, dist);
                moveX = nx * 6.0; moveY = ny * 6.0;
                recentPositions.clear();
                AuraAI3.get().resetSequence();
            }
        }

        predX += (float) moveX;
        predY += (float) moveY;
        predX = MathHelper.clamp(predX, gX, gX + gW);
        predY = MathHelper.clamp(predY, gY, gY + gH);

        recentPositions.addLast(new float[]{predX, predY});
        while (recentPositions.size() > OSC_WINDOW) recentPositions.pollFirst();
    }

    private void drawBtn(net.minecraft.client.util.math.MatrixStack m,
                         float x, float y, String label, float hover, float active) {
        float hF = MathHelper.clamp(hover, 0f, 1f);
        float aF = MathHelper.clamp(active, 0f, 1f);
        if (aF > 0.01f || hF > 0.01f) {
            int gA = (int)(15 + 50 * aF + 12 * hF);
            rectangle.render(ShapeProperties.create(m, x - 2, y - 2, BW + 4, BH + 4)
                    .round(9f).color(ColorUtility.getColor(80, 160, 255, gA)).build());
        }
        int rBg = (int) MathHelper.lerp(aF, 30, 60);
        int gBg = (int) MathHelper.lerp(aF, 50, 130);
        int bBg = (int) MathHelper.lerp(aF, 80, 230);
        int aBg = (int) (75 + 60 * aF + 40 * hF);
        rectangle.render(ShapeProperties.create(m, x, y, BW, BH).round(7f)
                .color(ColorUtility.getColor(rBg, gBg, bBg, aBg)).build());
        rectangle.render(ShapeProperties.create(m, x + 2, y + 2, BW - 4, BH / 2f - 1)
                .round(5f).color(ColorUtility.getColor(255, 255, 255,
                        (int)(8 + 14 * aF + 6 * hF))).build());
        if (aF > 0.5f) {
            rectangle.render(ShapeProperties.create(m, x + 1, y + 1, BW - 2, BH - 2)
                    .round(6f).color(ColorUtility.getColor(150, 200, 255, (int)(40 * aF))).build());
            rectangle.render(ShapeProperties.create(m, x + 2, y + 2, BW - 4, BH - 4)
                    .round(5f).color(ColorUtility.getColor(rBg, gBg, bBg, aBg)).build());
        }
        int textC = aF > 0.5f ? 0xFFFFFFFF : (hF > 0.5f ? 0xFFD0E0F0 : 0xFF8FA3B8);
        Fonts.MNTSB.get(10).drawCenteredString(m, label, x + BW / 2f, y + 9f, textC);
    }

    @Override public boolean mouseClicked(double mx, double my, int btn) {
        if (btn == 0 && !training) {
            if (inBtn(mx, my, 0)) {
                recording = !recording;
                predictMode = false;
                if (recording) AuraAI3.get().markEpisodeBoundary();
                return true;
            }
            if (inBtn(mx, my, 1)) {
                if (AuraAI3.get().sampleCount() < 32)
                    AuraAI3.chatSink.accept("§b[AuraAI3] §cМинимум 32 кадра!");
                else {
                    recording = false; predictMode = false;
                    training = true; trainProgress = 0f;
                    AuraAI3.get().trainModel(1000, p -> {
                        trainProgress = p;
                        if (p >= 1f) training = false;
                    });
                }
                return true;
            }
            if (inBtn(mx, my, 2)) {
                if (AuraAI3.get().trained) {
                    predictMode = !predictMode;
                    recording = false;
                    predX = (float) mx; predY = (float) my;
                    AuraAI3.get().resetSequence();
                    recentPositions.clear();
                    predictStallTicks = 0;
                } else AuraAI3.chatSink.accept("§b[AuraAI3] §cСначала Train!");
                return true;
            }
            if (inBtn(mx, my, 3)) { recording = false; predictMode = false; return true; }
            if (inBtn(mx, my, 4)) {
                AuraAI3.get().clear();
                hits = 0; misses = 0;
                targets.clear(); spawned = false;
                predictMode = false;
                screenSeq.clear(); recentPositions.clear();
                return true;
            }
            if (!predictMode) {
                for (AimTarget t : targets) {
                    if (Math.hypot(mx - t.x, my - t.y) <= t.rad + 6) {
                        spawnHitParticles(t.x, t.y);
                        targets.remove(t); hits++;
                        jellyScale = 1.45f; jellyVel = -0.22f;
                        if (recording) AuraAI3.get().markEpisodeBoundary();
                        if (mc.player != null) mc.player.playSound(
                                net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK.value(), 0.4f, 1.5f);
                        return true;
                    }
                }
                if (recording && mx >= gX && mx <= gX + gW && my >= gY && my <= gY + gH) misses++;
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    private boolean inBtn(double mx, double my, int i) {
        return mx >= btnX[i] && mx <= btnX[i] + BW && my >= btnY_[i] && my <= btnY_[i] + BH;
    }

    private void spawnHitParticles(float x, float y) {
        for (int i = 0; i < 16; i++) {
            Particle p = new Particle();
            p.x = x; p.y = y;
            float a = random.nextFloat() * (float) Math.PI * 2f;
            float s = 1.5f + random.nextFloat() * 3.5f;
            p.vx = (float) Math.cos(a) * s; p.vy = (float) Math.sin(a) * s;
            p.life = 0.7f + random.nextFloat() * 0.5f;
            int v = random.nextInt(3);
            if (v == 0) { p.r = 100; p.g = 180; p.b = 255; }
            else if (v == 1) { p.r = 150; p.g = 130; p.b = 255; }
            else { p.r = 180; p.g = 220; p.b = 255; }
            particles.add(p);
        }
    }

    private void spawnTarget() {
        AimTarget t = new AimTarget();
        t.rad = 14f + random.nextFloat() * 5f;
        t.x = gX + t.rad + 30 + random.nextFloat() * (gW - t.rad * 2 - 60);
        t.y = gY + t.rad + 30 + random.nextFloat() * (gH - t.rad * 2 - 60);
        targets.add(t);
    }

    private void drawLine(DrawContext ctx, float x1, float y1, float x2, float y2, float w, int c) {
        var m = ctx.getMatrices(); float dx=x2-x1,dy=y2-y1; float d=(float)Math.hypot(dx,dy); if(d==0)return;
        int steps=(int)Math.max(1,d/0.7f);
        for(int i=0;i<=steps;i++){float t=(float)i/steps;float px=x1+dx*t,py=y1+dy*t;
            rectangle.render(ShapeProperties.create(m,px-w/2,py-w/2,w,w).round(w/2).color(c).build());}
    }

    private void drawOutlineCircle(DrawContext ctx, float cx, float cy, float r, int color) {
        var m=ctx.getMatrices(); int seg=48; double step=2*Math.PI/seg;
        for(int i=0;i<seg;i++){float px=cx+(float)(r*Math.cos(step*i)),py=cy+(float)(r*Math.sin(step*i));
            rectangle.render(ShapeProperties.create(m,px-0.85f,py-0.85f,1.7f,1.7f).round(0.85f).color(color).build());}
    }

    private void drawGlowCircle(DrawContext ctx, float cx, float cy, float r, int color) {
        var m=ctx.getMatrices(); int seg=20; double step=2*Math.PI/seg;
        for(int i=0;i<seg;i++){float px=cx+(float)(r*Math.cos(step*i)),py=cy+(float)(r*Math.sin(step*i));
            rectangle.render(ShapeProperties.create(m,px-3.5f,py-3.5f,7f,7f).round(3.5f).color(color).build());}
    }

    @Override public boolean shouldCloseOnEsc() { return true; }
    @Override public void renderBackground(DrawContext ctx, int mx, int my, float delta) {}

    private static class TrailPoint { float x, y; long time; TrailPoint(float x, float y, long t){this.x=x;this.y=y;this.time=t;}}
    private static class AimTarget { float x, y, rad; }
    private static class Particle { float x, y, vx, vy, life; int r, g, b; }
}
