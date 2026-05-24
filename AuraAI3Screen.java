package ru.etc1337.client.modules.impl.combat.aura.ai3;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import ru.etc1337.api.interfaces.QuickImports;
import ru.etc1337.api.render.font.Fonts;
import ru.etc1337.api.render.rect.ShapeProperties;
import ru.etc1337.api.util.color.ColorUtility;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * AuraAI3Screen v6 — Настоящий Liquid Glass через MirageGlassPipeline (шейдеры).
 * RECORD throttled на 20 sample/sec (50ms интервал).
 * Predict с safety от залипания.
 */
public final class AuraAI3Screen extends Screen implements QuickImports {
    private final List<AimTarget> targets = new ArrayList<>();
    private final List<Particle> particles = new ArrayList<>();
    private final List<TrailPoint> trail = new ArrayList<>();
    private final Random random = new Random();

    private boolean recording = false, predictMode = false, training = false;
    private float trainProgress = 0f;
    private int hits = 0, misses = 0;
    private boolean spawned = false;
    private float lastMX = -1f, lastMY = -1f;
    private float virtualX = -1f, virtualY = -1f;

    // Throttle для записи
    private long lastSampleTime = 0;
    private static final long SAMPLE_INTERVAL_MS = 50; // 20 раз/сек

    // Jelly physics
    private float jellyScale = 1f, jellyVel = 0f;
    private float panelScale = 0.92f;
    private float panelAlpha = 0f;
    private float animTime = 0f;

    // Smooth hover для кнопок
    private float[] btnHover = new float[5];
    private float[] btnActive = new float[5];

    private static final float PW = 740f, PH = 520f;
    private float pX, pY, gX, gY, gW, gH;
    private float[] btnX = new float[5], btnY_ = new float[5];
    private final float BW = 90f, BH = 28f;
    private final String[] LABELS = {"RECORD", "TRAIN", "PREDICT", "STOP", "CLEAR"};

    // Цвета liquid glass (тинт)
    private static final Color GLASS_TINT = new Color(80, 130, 200, 30);
    private static final Color GLASS_INSET_TINT = new Color(20, 40, 70, 80);
    private static final Color BTN_TINT = new Color(60, 100, 180, 25);
    private static final Color BTN_ACTIVE_TINT = new Color(80, 150, 255, 60);

    public AuraAI3Screen() { super(Text.literal("AuraAI3")); }

    @Override protected void init() {
        pX = (width - PW) / 2f; pY = (height - PH) / 2f;
        gX = pX + 22f; gY = pY + 64f; gW = PW - 44f; gH = PH - 122f;
        float bY = pY + PH - 44f;
        float total = BW * 5 + 44f;
        float sx = pX + (PW - total) / 2f;
        for (int i = 0; i < 5; i++) { btnX[i] = sx + i * (BW + 11); btnY_[i] = bY; }

        targets.clear(); trail.clear(); particles.clear(); spawned = false;
        lastMX = -1; lastMY = -1; virtualX = -1; virtualY = -1;
        recording = false; predictMode = false; training = false;
        panelScale = 0.92f; panelAlpha = 0f;
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
        var matrix = m.peek().getPositionMatrix();

        // ═══ DIM ═══
        rectangle.render(ShapeProperties.create(m, 0, 0, width, height)
                .color(ColorUtility.getColor(0, 0, 0, (int)(panelAlpha * 130))).build());

        // ═══ LIQUID GLASS PANEL (MirageGlass shader) ═══
        float scaledW = PW * panelScale, scaledH = PH * panelScale;
        float spX = pX + (PW - scaledW) / 2f, spY = pY + (PH - scaledH) / 2f;

        // Главная стеклянная панель — РЕАЛЬНЫЙ liquid glass с blur+distortion
        try {
            MirageGlassPipeline.draw(matrix, spX, spY, scaledW, scaledH,
                    16f,    // radius
                    8f,     // blur (размытие фона)
                    14f,    // distortion (искажение волн)
                    0.6f,   // shine
                    GLASS_TINT,
                    panelAlpha,
                    0f);
        } catch (Throwable t) {
            // Fallback если шейдер не загрузился
            rectangle.render(ShapeProperties.create(m, spX, spY, scaledW, scaledH)
                    .round(15f).color(ColorUtility.getColor(15, 25, 45, (int)(panelAlpha * 200))).build());
        }

        // Glow border
        float glowPulse = 0.5f + 0.5f * (float) Math.sin(animTime * 2f);
        for (int i = 4; i > 0; i--) {
            int gA = (int)((6 + 10 * glowPulse) * panelAlpha * (i / 4f));
            rectangle.render(ShapeProperties.create(m, spX - i, spY - i, scaledW + i * 2, scaledH + i * 2)
                    .round(16f + i).color(ColorUtility.getColor(80, 150, 240, gA)).build());
        }

        // ═══ HEADER ═══
        Fonts.MNTSB.get(15).drawString(m, "Neuro", pX + 24f, pY + 20f,
                ColorUtility.getColor(235, 245, 255, alphaMain));
        Fonts.MNTSB.get(11).drawString(m, "BWorld", pX + 92f, pY + 23f,
                ColorUtility.getColor(110, 170, 220, alphaMain));

        String mode = recording ? "mode=RECORD" : predictMode ? "mode=PREDICT" : "mode=IDLE";
        String info = mode + "  rec=" + AuraAI3.get().samples.size() + "  rl=on  hits=" + hits;
        float iw = Fonts.MNTSB.get(9).getStringWidth(info);
        Fonts.MNTSB.get(9).drawString(m, info, pX + PW - iw - 24f, pY + 23f,
                ColorUtility.getColor(160, 190, 220, alphaMain));

        // Header divider
        rectangle.render(ShapeProperties.create(m, pX + 18, pY + 50, PW - 36, 1)
                .color(ColorUtility.getColor(100, 160, 220, (int)(panelAlpha * 35))).build());

        // ═══ GAME AREA (тоже через liquid glass — более тёмный) ═══
        try {
            MirageGlassPipeline.draw(matrix, gX, gY, gW, gH,
                    13f,   // radius
                    4f,    // blur (меньше, чтобы было видно мишени)
                    6f,    // distortion (легкое волнение)
                    0.4f,  // shine
                    GLASS_INSET_TINT,
                    panelAlpha,
                    0f);
        } catch (Throwable t) {
            rectangle.render(ShapeProperties.create(m, gX, gY, gW, gH)
                    .round(12f).color(ColorUtility.getColor(5, 10, 20, (int)(panelAlpha * 230))).build());
        }

        // Game area glow border
        for (int i = 2; i > 0; i--) {
            int gA = (int)(8 * panelAlpha * (i / 2f));
            rectangle.render(ShapeProperties.create(m, gX - i, gY - i, gW + i * 2, gH + i * 2)
                    .round(13f + i).color(ColorUtility.getColor(70, 140, 210, gA)).build());
        }

        // ═══ Spawn targets ═══
        if (!spawned && gW > 60) { spawned = true; spawnTarget(); }
        while (targets.size() < 1 && gW > 60) spawnTarget();

        AimTarget activeTarget = null;
        for (AimTarget t : targets) {
            activeTarget = t;
            float rad = t.rad * jellyScale;

            int tGlowA = (int)(20 + 18 * glowPulse);
            drawGlowCircle(ctx, t.x, t.y, rad + 14f, ColorUtility.getColor(80, 140, 255, tGlowA / 2));
            drawGlowCircle(ctx, t.x, t.y, rad + 8f, ColorUtility.getColor(110, 170, 255, tGlowA));
            drawGlowCircle(ctx, t.x, t.y, rad + 4f, ColorUtility.getColor(140, 200, 255, tGlowA + 10));
            drawOutlineCircle(ctx, t.x, t.y, rad, ColorUtility.getColor(170, 215, 255, 240));

            rectangle.render(ShapeProperties.create(m, t.x - 4f, t.y - 4f, 8f, 8f)
                    .round(4f).color(ColorUtility.getColor(120, 180, 255, 90)).build());
            rectangle.render(ShapeProperties.create(m, t.x - 2.5f, t.y - 2.5f, 5f, 5f)
                    .round(2.5f).color(ColorUtility.getColor(210, 235, 255, 255)).build());
        }

        // ═══ PREDICT MODE с safety ═══
        if (predictMode && activeTarget != null && AuraAI3.get().trained) {
            if (virtualX < 0) { virtualX = gX + gW / 2f; virtualY = gY + gH / 2f; }
            float dx = activeTarget.x - virtualX, dy = activeTarget.y - virtualY;
            float dist = (float) Math.hypot(dx, dy);

            float[] step;
            if (dist < 1f) {
                step = new float[]{0f, 0f};
            } else {
                float yawNorm = MathHelper.clamp(dx / (gW * 0.5f), -1f, 1f);
                float pitchNorm = MathHelper.clamp(dy / (gH * 0.5f), -1f, 1f);
                step = AuraAI3.get().predict(yawNorm, pitchNorm);
                float stepMag = (float) Math.hypot(step[0], step[1]);
                if (stepMag < 1.5f) {
                    float ss = dist > 30 ? 0.25f : dist > 10 ? 0.18f : 0.12f;
                    step[0] += dx * ss; step[1] += dy * ss;
                }
                if (Math.abs(dx) > 2 && Math.signum(step[0]) != Math.signum(dx)) step[0] = dx * 0.18f;
                if (Math.abs(dy) > 2 && Math.signum(step[1]) != Math.signum(dy)) step[1] = dy * 0.18f;
                if (Math.abs(step[0]) > Math.abs(dx) * 1.5f) step[0] = dx * 0.6f;
                if (Math.abs(step[1]) > Math.abs(dy) * 1.5f) step[1] = dy * 0.6f;
            }

            virtualX += step[0]; virtualY += step[1];
            trail.add(new TrailPoint(virtualX, virtualY, now));

            if (dist <= activeTarget.rad + 4) {
                spawnHitParticles(activeTarget.x, activeTarget.y);
                targets.remove(activeTarget); hits++;
                jellyScale = 1.4f; jellyVel = -0.2f;
                if (mc.player != null) mc.player.playSound(
                        net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 1.6f);
            }

            drawGlowCircle(ctx, virtualX, virtualY, 16f, ColorUtility.getColor(255, 80, 80, 25));
            drawGlowCircle(ctx, virtualX, virtualY, 11f, ColorUtility.getColor(255, 100, 100, 50));
            drawOutlineCircle(ctx, virtualX, virtualY, 7f, ColorUtility.getColor(255, 130, 130, 245));
            rectangle.render(ShapeProperties.create(m, virtualX - 18f, virtualY - 0.6f, 36f, 1.2f)
                    .color(ColorUtility.getColor(255, 110, 110, 190)).build());
            rectangle.render(ShapeProperties.create(m, virtualX - 0.6f, virtualY - 18f, 1.2f, 36f)
                    .color(ColorUtility.getColor(255, 110, 110, 190)).build());
        } else {
            virtualX = mx; virtualY = my;
            trail.add(new TrailPoint(mx, my, now));
        }

        // ═══ RECORD MODE с throttle ═══
        if (recording && lastMX >= 0 && activeTarget != null) {
            float stepX = mx - lastMX, stepY = my - lastMY;
            // Throttle: только если прошло >= 50ms И есть движение
            if ((stepX != 0 || stepY != 0) && (now - lastSampleTime >= SAMPLE_INTERVAL_MS)) {
                float dx = activeTarget.x - lastMX, dy = activeTarget.y - lastMY;
                float yawNorm = MathHelper.clamp(dx / (gW * 0.5f), -1f, 1f);
                float pitchNorm = MathHelper.clamp(dy / (gH * 0.5f), -1f, 1f);
                AuraAI3.get().addSample(yawNorm, pitchNorm, stepX, stepY);
                lastSampleTime = now;
            }
        }
        lastMX = mx; lastMY = my;

        // ═══ TRAIL ═══
        trail.removeIf(pt -> now - pt.time > 1500);
        for (int i = 0; i < trail.size() - 1; i++) {
            TrailPoint p1 = trail.get(i), p2 = trail.get(i + 1);
            float f = 1f - (float)(now - p1.time) / 1500f;
            if (f <= 0) continue;
            drawLine(ctx, p1.x, p1.y, p2.x, p2.y, 2.6f * f,
                    ColorUtility.getColor(80, 160, 255, (int)(f * 50)));
            drawLine(ctx, p1.x, p1.y, p2.x, p2.y, 1.3f * f,
                    ColorUtility.getColor(150, 210, 255, (int)(f * 160)));
        }

        // ═══ PARTICLES ═══
        Iterator<Particle> pIt = particles.iterator();
        while (pIt.hasNext()) {
            Particle p = pIt.next();
            p.x += p.vx; p.y += p.vy;
            p.vx *= 0.92f; p.vy *= 0.92f; p.vy += 0.1f;
            p.life -= 0.02f;
            if (p.life <= 0) { pIt.remove(); continue; }
            float pSize = 3.5f * p.life;
            int pAlpha = (int)(p.life * 230);
            rectangle.render(ShapeProperties.create(m, p.x - pSize, p.y - pSize, pSize * 2, pSize * 2)
                    .round(pSize).color(ColorUtility.getColor(p.r, p.g, p.b, pAlpha)).build());
            rectangle.render(ShapeProperties.create(m, p.x - pSize * 2, p.y - pSize * 2, pSize * 4, pSize * 4)
                    .round(pSize * 2).color(ColorUtility.getColor(p.r, p.g, p.b, pAlpha / 4)).build());
        }

        // ═══ GLASS BUTTONS (через MirageGlass) ═══
        boolean[] active = {recording, training, predictMode, false, false};
        for (int i = 0; i < 5; i++) {
            boolean hover = mx >= btnX[i] && mx <= btnX[i] + BW && my >= btnY_[i] && my <= btnY_[i] + BH;
            btnHover[i] += ((hover ? 1f : 0f) - btnHover[i]) * 0.2f;
            btnActive[i] += ((active[i] ? 1f : 0f) - btnActive[i]) * 0.15f;
            drawLiquidBtn(ctx, matrix, btnX[i], btnY_[i], LABELS[i], btnHover[i], btnActive[i], panelAlpha);
        }

        // ═══ TRAINING OVERLAY ═══
        if (training) {
            try {
                MirageGlassPipeline.draw(matrix, gX, gY, gW, gH, 13f, 12f, 8f, 0.5f,
                        new Color(10, 20, 40, 200), panelAlpha, 0f);
            } catch (Throwable t) {
                rectangle.render(ShapeProperties.create(m, gX, gY, gW, gH).round(12f)
                        .color(ColorUtility.getColor(2, 4, 10, 235)).build());
            }
            String txt = "TRAINING (PyTorch): " + (int)(trainProgress * 100) + "%";
            Fonts.MNTSB.get(13).drawCenteredString(m, txt, gX + gW / 2f, gY + gH / 2f - 14, 0xFFA0D0FF);
            float bw = 280f, bh = 7f, bx = gX + (gW - bw) / 2f, by = gY + gH / 2f + 14;
            rectangle.render(ShapeProperties.create(m, bx - 1, by - 1, bw + 2, bh + 2)
                    .round(4f).color(ColorUtility.getColor(60, 100, 160, 80)).build());
            rectangle.render(ShapeProperties.create(m, bx, by, bw, bh).round(3.5f)
                    .color(ColorUtility.getColor(15, 25, 45, 255)).build());
            float pw = bw * trainProgress;
            rectangle.render(ShapeProperties.create(m, bx, by, pw, bh).round(3.5f)
                    .color(ColorUtility.getColor(60, 130, 230, 255)).build());
            rectangle.render(ShapeProperties.create(m, bx, by, pw, bh / 2f).round(3f)
                    .color(ColorUtility.getColor(150, 210, 255, 200)).build());
            if (trainProgress > 0.01f) {
                float tipX = bx + pw;
                rectangle.render(ShapeProperties.create(m, tipX - 6, by - 3, 12, bh + 6).round(6f)
                        .color(ColorUtility.getColor(190, 230, 255, 130)).build());
            }
        }

        String st = String.format("RECORD: %d samples (20/sec).", AuraAI3.get().samples.size());
        Fonts.MNTSB.get(10).drawString(m, st, pX + 24f, pY + PH - 18f,
                ColorUtility.getColor(130, 160, 190, alphaMain));
    }

    private void drawLiquidBtn(DrawContext ctx, org.joml.Matrix4f matrix,
                                float x, float y, String label, float hover, float active, float pA) {
        var m = ctx.getMatrices();
        float hF = MathHelper.clamp(hover, 0f, 1f);
        float aF = MathHelper.clamp(active, 0f, 1f);

        // Glow при hover/active
        if (aF > 0.01f || hF > 0.01f) {
            int gA = (int)(15 + 50 * aF + 12 * hF);
            rectangle.render(ShapeProperties.create(m, x - 2, y - 2, BW + 4, BH + 4)
                    .round(9f).color(ColorUtility.getColor(80, 160, 255, gA)).build());
        }

        // Сама кнопка через MirageGlass
        Color tint = aF > 0.5f
                ? new Color((int)(60 + 30 * hF), (int)(120 + 50 * aF), (int)(220 + 30 * aF), (int)(60 + 80 * aF))
                : new Color(50, 80, 130, (int)(20 + 60 * hF));
        try {
            MirageGlassPipeline.draw(matrix, x, y, BW, BH,
                    8f,                       // radius
                    3f + 4f * hF,            // blur (больше при hover)
                    4f + 6f * hF,            // distortion (больше при hover)
                    0.5f + 0.4f * aF,        // shine
                    tint,
                    pA,
                    0f);
        } catch (Throwable t) {
            int rBg = (int) MathHelper.lerp(aF, 30, 60);
            int gBg = (int) MathHelper.lerp(aF, 50, 130);
            int bBg = (int) MathHelper.lerp(aF, 80, 230);
            int aBg = (int) (75 + 60 * aF + 40 * hF);
            rectangle.render(ShapeProperties.create(m, x, y, BW, BH).round(8f)
                    .color(ColorUtility.getColor(rBg, gBg, bBg, aBg)).build());
        }

        // Top highlight
        rectangle.render(ShapeProperties.create(m, x + 2, y + 2, BW - 4, BH / 2f - 1)
                .round(6f).color(ColorUtility.getColor(255, 255, 255, (int)(10 + 16 * aF + 6 * hF))).build());

        // Активный border
        if (aF > 0.5f) {
            rectangle.render(ShapeProperties.create(m, x + 1, y + 1, BW - 2, BH - 2)
                    .round(7f).color(ColorUtility.getColor(150, 210, 255, (int)(60 * aF))).build());
        }

        int textC = aF > 0.5f ? 0xFFFFFFFF : (hF > 0.5f ? 0xFFD8E8F8 : 0xFF98ACBC);
        Fonts.MNTSB.get(10).drawCenteredString(m, label, x + BW / 2f, y + 9f, textC);
    }

    @Override public boolean mouseClicked(double mx, double my, int btn) {
        if (btn == 0 && !training) {
            if (inBtn(mx, my, 0)) { recording = !recording; predictMode = false; return true; }
            if (inBtn(mx, my, 1)) {
                if (AuraAI3.get().samples.size() < 10) AuraAI3.chatSink.accept("§b[AuraAI3] §cМинимум 10 сэмплов!");
                else { recording = false; predictMode = false; training = true; trainProgress = 0f;
                    AuraAI3.get().trainModel(5000, p -> { trainProgress = p; if (p >= 1f) training = false; }); }
                return true;
            }
            if (inBtn(mx, my, 2)) {
                if (AuraAI3.get().trained) {
                    predictMode = !predictMode; recording = false;
                    virtualX = (float)mx; virtualY = (float)my;
                } else AuraAI3.chatSink.accept("§b[AuraAI3] §cОбучи сначала!");
                return true;
            }
            if (inBtn(mx, my, 3)) { recording = false; predictMode = false; return true; }
            if (inBtn(mx, my, 4)) {
                AuraAI3.get().clear(); hits = 0; misses = 0;
                targets.clear(); spawned = false; predictMode = false;
                return true;
            }
            if (!predictMode) {
                for (AimTarget t : targets) {
                    if (Math.hypot(mx - t.x, my - t.y) <= t.rad + 6) {
                        spawnHitParticles(t.x, t.y);
                        targets.remove(t); hits++;
                        jellyScale = 1.45f; jellyVel = -0.22f;
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
            float ang = random.nextFloat() * (float) Math.PI * 2f;
            float spd = 1.5f + random.nextFloat() * 3.5f;
            p.vx = (float) Math.cos(ang) * spd;
            p.vy = (float) Math.sin(ang) * spd;
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
        var m=ctx.getMatrices(); int seg=52; double step=2*Math.PI/seg;
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

    private static class TrailPoint { float x, y; long time; TrailPoint(float x,float y,long t){this.x=x;this.y=y;this.time=t;}}
    private static class AimTarget { float x, y, rad; }
    private static class Particle { float x, y, vx, vy, life; int r, g, b; }
}
