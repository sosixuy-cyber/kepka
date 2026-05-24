package ru.etc1337.client.modules.impl.combat.aura.ai3;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import ru.etc1337.api.interfaces.QuickImports;
import ru.etc1337.api.render.font.Fonts;
import ru.etc1337.api.render.rect.ShapeProperties;
import ru.etc1337.api.util.color.ColorUtility;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * AuraAI3Screen v3 — Liquid Glass + Jelly + Shader-like effects.
 * Формат записи: yaw_norm, pitch_norm -> move_dyaw, move_dpitch
 * Predict: 85% твои движения, 15% smooth доводка.
 */
public final class AuraAI3Screen extends Screen implements QuickImports {
    private final List<AimTarget> targets = new ArrayList<>();
    private final Random random = new Random();
    private final List<TrailPoint> trail = new ArrayList<>();

    private boolean recording = false, predictMode = false, training = false;
    private float trainProgress = 0f;
    private int hits = 0, misses = 0;
    private boolean spawned = false;
    private float lastMX = -1f, lastMY = -1f;
    private float virtualX = -1f, virtualY = -1f;

    // Jelly physics
    private float jellyScale = 1f, jellyVel = 0f;
    private long lastHitTime = 0;

    // Panel jelly (при появлении)
    private float panelScale = 0.9f;
    private float panelAlpha = 0f;

    // Glow pulse
    private float glowPhase = 0f;

    private static final float PW = 720f, PH = 500f;
    private float pX, pY, gX, gY, gW, gH;
    private float bRecX, bRecY, bTrnX, bTrnY, bPrdX, bPrdY, bStpX, bStpY, bClrX, bClrY;
    private final float BW = 82f, BH = 24f;

    public AuraAI3Screen() { super(Text.literal("AuraAI3")); }

    @Override protected void init() {
        pX = (width - PW) / 2f; pY = (height - PH) / 2f;
        gX = pX + 18f; gY = pY + 56f; gW = PW - 36f; gH = PH - 110f;
        float btnY = pY + PH - 40f;
        float total = BW * 5 + 32f;
        float sx = pX + (PW - total) / 2f;
        bRecX = sx; bRecY = btnY;
        bTrnX = sx + BW + 8; bTrnY = btnY;
        bPrdX = sx + (BW + 8) * 2; bPrdY = btnY;
        bStpX = sx + (BW + 8) * 3; bStpY = btnY;
        bClrX = sx + (BW + 8) * 4; bClrY = btnY;
        targets.clear(); trail.clear(); spawned = false;
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

        // Panel open animation (jelly spring)
        panelScale += (1f - panelScale) * 0.12f;
        panelAlpha = Math.min(1f, panelAlpha + 0.06f);

        // Target jelly bounce
        jellyVel += (1f - jellyScale) * 0.3f;
        jellyVel *= 0.65f;
        jellyScale += jellyVel;

        // Glow pulse
        glowPhase += delta * 0.04f;
        float glowPulse = 0.5f + 0.5f * (float)Math.sin(glowPhase);

        int alphaMain = (int)(panelAlpha * 255);

        // ═══ FULLSCREEN DIM ═══
        rectangle.render(ShapeProperties.create(m, 0, 0, width, height)
                .color(ColorUtility.getColor(0, 0, 0, (int)(panelAlpha * 150))).build());

        // ═══ LIQUID GLASS PANEL ═══
        float scaledW = PW * panelScale, scaledH = PH * panelScale;
        float spX = pX + (PW - scaledW) / 2f, spY = pY + (PH - scaledH) / 2f;

        // Outer glow (shader-like)
        int glowAlpha = (int)(30 + 25 * glowPulse);
        rectangle.render(ShapeProperties.create(m, spX - 3, spY - 3, scaledW + 6, scaledH + 6)
                .round(16f).color(ColorUtility.getColor(80, 160, 255, glowAlpha)).build());
        rectangle.render(ShapeProperties.create(m, spX - 1, spY - 1, scaledW + 2, scaledH + 2)
                .round(15f).color(ColorUtility.getColor(120, 200, 255, (int)(40 * panelAlpha))).build());

        // Main glass body (frosted glass effect — multi-layer)
        rectangle.render(ShapeProperties.create(m, spX, spY, scaledW, scaledH)
                .round(14f).color(ColorUtility.getColor(10, 15, 25, (int)(panelAlpha * 200))).build());
        // Inner frost layer
        rectangle.render(ShapeProperties.create(m, spX + 1, spY + 1, scaledW - 2, scaledH - 2)
                .round(13f).color(ColorUtility.getColor(20, 30, 50, (int)(panelAlpha * 40))).build());
        // Top reflection (glass highlight)
        rectangle.render(ShapeProperties.create(m, spX + 4, spY + 3, scaledW - 8, 35)
                .round(10f).color(ColorUtility.getColor(255, 255, 255, (int)(panelAlpha * 8))).build());
        // Bottom subtle reflection
        rectangle.render(ShapeProperties.create(m, spX + 20, spY + scaledH - 50, scaledW - 40, 30)
                .round(8f).color(ColorUtility.getColor(80, 140, 255, (int)(panelAlpha * 5))).build());

        // ═══ HEADER ═══
        Fonts.MNTSB.get(14).drawString(m, "Neuro", pX + 20f, pY + 16f, ColorUtility.getColor(220, 235, 255, alphaMain));
        Fonts.MNTSB.get(11).drawString(m, "BWorld", pX + 82f, pY + 18f, ColorUtility.getColor(80, 140, 200, alphaMain));

        String mode = recording ? "mode=RECORD" : predictMode ? "mode=PREDICT" : "mode=IDLE";
        String info = mode + "  rec=" + AuraAI3.get().samples.size() + "  rl=on  hist=" + hits;
        float iw = Fonts.MNTSB.get(9).getStringWidth(info);
        Fonts.MNTSB.get(9).drawString(m, info, pX + PW - iw - 18f, pY + 18f,
                ColorUtility.getColor(140, 170, 200, alphaMain));

        // ═══ GAME AREA (glass inset) ═══
        // Outer border glow
        rectangle.render(ShapeProperties.create(m, gX - 1, gY - 1, gW + 2, gH + 2)
                .round(12f).color(ColorUtility.getColor(60, 130, 200, (int)(20 * panelAlpha))).build());
        // Dark inset
        rectangle.render(ShapeProperties.create(m, gX, gY, gW, gH)
                .round(11f).color(ColorUtility.getColor(3, 5, 12, (int)(panelAlpha * 235))).build());
        // Inner edge highlight (top)
        rectangle.render(ShapeProperties.create(m, gX + 3, gY + 2, gW - 6, 2)
                .round(1f).color(ColorUtility.getColor(100, 180, 255, (int)(panelAlpha * 15))).build());

        // ═══ SPAWN + DRAW TARGETS ═══
        if (!spawned && gW > 60) { spawned = true; spawnTarget(); }
        while (targets.size() < 1 && gW > 60) spawnTarget();

        AimTarget activeTarget = null;
        for (AimTarget t : targets) {
            activeTarget = t;
            float rad = t.rad * jellyScale;

            // Outer glow ring (shader effect)
            int tGlowA = (int)(25 + 20 * glowPulse);
            drawGlowCircle(ctx, t.x, t.y, rad + 10f, ColorUtility.getColor(100, 160, 255, tGlowA));
            drawGlowCircle(ctx, t.x, t.y, rad + 5f, ColorUtility.getColor(130, 180, 255, tGlowA + 10));

            // Main circle outline
            drawOutlineCircle(ctx, t.x, t.y, rad, ColorUtility.getColor(140, 190, 255, 200));

            // Inner dot with glow
            rectangle.render(ShapeProperties.create(m, t.x - 2.5f, t.y - 2.5f, 5f, 5f)
                    .round(2.5f).color(ColorUtility.getColor(180, 220, 255, 255)).build());
        }

        // ═══ PREDICT MODE ═══
        if (predictMode && activeTarget != null && AuraAI3.get().trained) {
            if (virtualX < 0) { virtualX = gX + gW / 2f; virtualY = gY + gH / 2f; }

            float dx = activeTarget.x - virtualX, dy = activeTarget.y - virtualY;
            float dist = (float) Math.hypot(dx, dy);
            float yawNorm = MathHelper.clamp(dx / (gW * 0.5f), -1f, 1f);
            float pitchNorm = MathHelper.clamp(dy / (gH * 0.5f), -1f, 1f);

            float[] step = AuraAI3.get().predict(yawNorm, pitchNorm);
            virtualX += step[0]; virtualY += step[1];
            trail.add(new TrailPoint(virtualX, virtualY, now));

            if (dist <= activeTarget.rad + 4) {
                targets.remove(activeTarget); hits++; lastHitTime = now;
                jellyScale = 1.25f; jellyVel = -0.15f; // Jelly bounce!
                if (mc.player != null) mc.player.playSound(
                        net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 1.6f);
            }

            // Crosshair with glow
            drawGlowCircle(ctx, virtualX, virtualY, 12f, ColorUtility.getColor(255, 80, 80, 30));
            drawOutlineCircle(ctx, virtualX, virtualY, 7f, ColorUtility.getColor(255, 100, 100, 230));
            rectangle.render(ShapeProperties.create(m, virtualX - 16f, virtualY - 0.5f, 32f, 1f)
                    .color(ColorUtility.getColor(255, 100, 100, 160)).build());
            rectangle.render(ShapeProperties.create(m, virtualX - 0.5f, virtualY - 16f, 1f, 32f)
                    .color(ColorUtility.getColor(255, 100, 100, 160)).build());
        } else {
            virtualX = mx; virtualY = my;
            trail.add(new TrailPoint(mx, my, now));
        }

        // ═══ RECORD MODE ═══
        if (recording && lastMX >= 0 && activeTarget != null) {
            float stepX = mx - lastMX, stepY = my - lastMY;
            if (stepX != 0 || stepY != 0) {
                float dx = activeTarget.x - lastMX, dy = activeTarget.y - lastMY;
                float yawNorm = MathHelper.clamp(dx / (gW * 0.5f), -1f, 1f);
                float pitchNorm = MathHelper.clamp(dy / (gH * 0.5f), -1f, 1f);
                AuraAI3.get().addSample(yawNorm, pitchNorm, stepX, stepY);
            }
        }
        lastMX = mx; lastMY = my;

        // ═══ TRAIL (glass fade) ═══
        trail.removeIf(pt -> now - pt.time > 2000);
        for (int i = 0; i < trail.size() - 1; i++) {
            TrailPoint p1 = trail.get(i), p2 = trail.get(i + 1);
            float f = 1f - (float)(now - p1.time) / 2000f;
            if (f <= 0) continue;
            drawLine(ctx, p1.x, p1.y, p2.x, p2.y, 1.4f * f,
                    ColorUtility.getColor(100, 180, 255, (int)(f * 100)));
        }

        // ═══ GLASS BUTTONS ═══
        drawGlassBtn(m, mx, my, bRecX, bRecY, "RECORD", recording);
        drawGlassBtn(m, mx, my, bTrnX, bTrnY, "TRAIN", training);
        drawGlassBtn(m, mx, my, bPrdX, bPrdY, "PREDICT", predictMode);
        drawGlassBtn(m, mx, my, bStpX, bStpY, "STOP", false);
        drawGlassBtn(m, mx, my, bClrX, bClrY, "CLEAR", false);

        // ═══ TRAINING OVERLAY ═══
        if (training) {
            rectangle.render(ShapeProperties.create(m, gX, gY, gW, gH)
                    .round(11f).color(ColorUtility.getColor(3, 5, 12, 230)).build());
            String txt = "TRAINING MLP: " + (int)(trainProgress * 100) + "%";
            Fonts.MNTSB.get(12).drawCenteredString(m, txt, gX + gW / 2f, gY + gH / 2f - 12, 0xFF8ABAFF);
            float bw = 240f, bh = 6f, bx = gX + (gW - bw) / 2f, by = gY + gH / 2f + 14;
            rectangle.render(ShapeProperties.create(m, bx, by, bw, bh).round(3f)
                    .color(ColorUtility.getColor(20, 35, 60, 255)).build());
            rectangle.render(ShapeProperties.create(m, bx, by, bw * trainProgress, bh).round(3f)
                    .color(ColorUtility.getColor(80, 160, 255, 255)).build());
            // Glow on progress tip
            if (trainProgress > 0.01f) {
                float tipX = bx + bw * trainProgress;
                rectangle.render(ShapeProperties.create(m, tipX - 4, by - 2, 8, bh + 4).round(4f)
                        .color(ColorUtility.getColor(120, 200, 255, 100)).build());
            }
        }

        // ═══ BOTTOM STATUS ═══
        String st = String.format("RECORD: %d samples.", AuraAI3.get().samples.size());
        Fonts.MNTSB.get(10).drawString(m, st, pX + 20f, pY + PH - 16f,
                ColorUtility.getColor(100, 130, 160, alphaMain));
    }

    private void drawGlassBtn(net.minecraft.client.util.math.MatrixStack m,
                              int mx, int my, float x, float y, String label, boolean active) {
        boolean hover = mx >= x && mx <= x + BW && my >= y && my <= y + BH;
        // Button body
        int bg = active ? ColorUtility.getColor(60, 140, 255, 140)
                : ColorUtility.getColor(30, 50, 70, hover ? 120 : 70);
        rectangle.render(ShapeProperties.create(m, x, y, BW, BH).round(7f).color(bg).build());
        // Glass top highlight
        rectangle.render(ShapeProperties.create(m, x + 2, y + 2, BW - 4, BH / 2 - 2)
                .round(5f).color(ColorUtility.getColor(255, 255, 255, active ? 12 : 6)).build());
        // Border
        if (active || hover) {
            rectangle.render(ShapeProperties.create(m, x - 0.5f, y - 0.5f, BW + 1, BH + 1)
                    .round(7.5f).color(ColorUtility.getColor(100, 180, 255, active ? 60 : 30)).build());
            rectangle.render(ShapeProperties.create(m, x, y, BW, BH).round(7f).color(bg).build());
        }
        Fonts.MNTSB.get(10).drawCenteredString(m, label, x + BW / 2f, y + 7f,
                active ? 0xFFFFFFFF : (hover ? 0xFFCCDDEE : 0xFF8899AA));
    }

    @Override public boolean mouseClicked(double mx, double my, int btn) {
        if (btn == 0 && !training) {
            if (inBox(mx, my, bRecX, bRecY)) { recording = !recording; predictMode = false; return true; }
            if (inBox(mx, my, bTrnX, bTrnY)) {
                if (AuraAI3.get().samples.size() < 10) AuraAI3.chatSink.accept("§b[AuraAI3] §cМинимум 10 сэмплов!");
                else { recording = false; predictMode = false; training = true; trainProgress = 0f;
                    AuraAI3.get().trainModel(5000, p -> { trainProgress = p; if (p >= 1f) training = false; }); }
                return true;
            }
            if (inBox(mx, my, bPrdX, bPrdY)) {
                if (AuraAI3.get().trained) { predictMode = !predictMode; recording = false; virtualX = (float)mx; virtualY = (float)my; }
                else AuraAI3.chatSink.accept("§b[AuraAI3] §cОбучи сначала!");
                return true;
            }
            if (inBox(mx, my, bStpX, bStpY)) { recording = false; predictMode = false; return true; }
            if (inBox(mx, my, bClrX, bClrY)) { AuraAI3.get().clear(); hits = 0; misses = 0; targets.clear(); spawned = false; predictMode = false; return true; }

            if (!predictMode) {
                for (AimTarget t : targets) {
                    if (Math.hypot(mx - t.x, my - t.y) <= t.rad + 6) {
                        targets.remove(t); hits++; lastHitTime = System.currentTimeMillis();
                        jellyScale = 1.3f; jellyVel = -0.2f;
                        if (mc.player != null) mc.player.playSound(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK.value(), 0.4f, 1.5f);
                        return true;
                    }
                }
                if (recording && mx >= gX && mx <= gX + gW && my >= gY && my <= gY + gH) misses++;
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    private boolean inBox(double mx, double my, float x, float y) {
        return mx >= x && mx <= x + BW && my >= y && my <= y + BH;
    }

    private void spawnTarget() {
        AimTarget t = new AimTarget();
        t.rad = 14f + random.nextFloat() * 5f;
        t.x = gX + t.rad + random.nextFloat() * (gW - t.rad * 2);
        t.y = gY + t.rad + random.nextFloat() * (gH - t.rad * 2);
        targets.add(t);
    }

    private void drawLine(DrawContext ctx, float x1, float y1, float x2, float y2, float w, int c) {
        var m = ctx.getMatrices(); float dx=x2-x1,dy=y2-y1; float d=(float)Math.hypot(dx,dy); if(d==0)return;
        int steps=(int)Math.max(1,d/0.7f); for(int i=0;i<=steps;i++){float t=(float)i/steps;float px=x1+dx*t,py=y1+dy*t;
        rectangle.render(ShapeProperties.create(m,px-w/2,py-w/2,w,w).round(w/2).color(c).build());}
    }

    private void drawOutlineCircle(DrawContext ctx, float cx, float cy, float r, int color) {
        var m=ctx.getMatrices(); int seg=48; double step=2*Math.PI/seg;
        for(int i=0;i<seg;i++){float px=cx+(float)(r*Math.cos(step*i)),py=cy+(float)(r*Math.sin(step*i));
        rectangle.render(ShapeProperties.create(m,px-0.8f,py-0.8f,1.6f,1.6f).round(0.8f).color(color).build());}
    }

    private void drawGlowCircle(DrawContext ctx, float cx, float cy, float r, int color) {
        var m=ctx.getMatrices(); int seg=24; double step=2*Math.PI/seg;
        for(int i=0;i<seg;i++){float px=cx+(float)(r*Math.cos(step*i)),py=cy+(float)(r*Math.sin(step*i));
        rectangle.render(ShapeProperties.create(m,px-3f,py-3f,6f,6f).round(3f).color(color).build());}
    }

    @Override public boolean shouldCloseOnEsc() { return true; }
    @Override public void renderBackground(DrawContext ctx, int mx, int my, float delta) {}

    private static class TrailPoint { float x,y; long time; TrailPoint(float x,float y,long t){this.x=x;this.y=y;this.time=t;}}
    private static class AimTarget { float x,y,rad; }
}
