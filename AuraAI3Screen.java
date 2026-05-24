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
import java.util.List;
import java.util.Random;

/**
 * AuraAI3Screen v3 — Liquid Glass + Jelly стиль.
 * Запись: yaw_norm, pitch_norm → move_dyaw, move_dpitch
 * Predict: 100% твои движения.
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

    // Jelly animation
    private float jellyScale = 1f;
    private long lastHitTime = 0;

    private static final float PW = 700f, PH = 480f;
    private float pX, pY, gX, gY, gW, gH;

    private float bRecX, bRecY, bTrnX, bTrnY, bPrdX, bPrdY, bStpX, bStpY, bClrX, bClrY;
    private final float BW = 80f, BH = 22f;

    public AuraAI3Screen() { super(Text.literal("AuraAI3 Neuro Trainer")); }

    @Override protected void init() {
        pX = (width - PW) / 2f; pY = (height - PH) / 2f;
        gX = pX + 16f; gY = pY + 52f; gW = PW - 32f; gH = PH - 100f;
        float btnY = pY + PH - 36f;
        float total = BW * 5 + 24f;
        float sx = pX + (PW - total) / 2f;
        bRecX = sx; bRecY = btnY; bTrnX = sx + BW + 6; bTrnY = btnY;
        bPrdX = sx + (BW + 6) * 2; bPrdY = btnY; bStpX = sx + (BW + 6) * 3; bStpY = btnY;
        bClrX = sx + (BW + 6) * 4; bClrY = btnY;
        targets.clear(); trail.clear(); spawned = false;
        lastMX = -1; lastMY = -1; virtualX = -1; virtualY = -1;
        recording = false; predictMode = false; training = false;
    }

    @Override public void render(DrawContext ctx, int mx, int my, float delta) {
        try { doRender(ctx, mx, my, delta); } catch (Exception e) { e.printStackTrace(); }
        super.render(ctx, mx, my, delta);
    }

    private void doRender(DrawContext ctx, int mx, int my, float delta) {
        var m = ctx.getMatrices();
        long now = System.currentTimeMillis();

        // Jelly bounce
        float jTarget = 1f;
        if (now - lastHitTime < 300) { float t = (now - lastHitTime) / 300f; jTarget = 1f + 0.15f * (float)Math.sin(t * Math.PI * 3) * (1f - t); }
        jellyScale += (jTarget - jellyScale) * 0.2f;

        // ═══ LIQUID GLASS BACKGROUND ═══
        rectangle.render(ShapeProperties.create(m, 0, 0, width, height).color(ColorUtility.getColor(0, 0, 0, 160)).build());

        // Glass panel with glow border
        int glassAlpha = (int)(180 + 30 * Math.sin(now / 1500.0));
        rectangle.render(ShapeProperties.create(m, pX - 1, pY - 1, PW + 2, PH + 2)
                .round(14f).color(ColorUtility.getColor(100, 180, 255, 60)).build());
        rectangle.render(ShapeProperties.create(m, pX, pY, PW, PH)
                .round(13f).color(ColorUtility.getColor(15, 15, 25, glassAlpha)).build());
        // Inner glass reflection
        rectangle.render(ShapeProperties.create(m, pX + 2, pY + 2, PW - 4, 40)
                .round(11f).color(ColorUtility.getColor(255, 255, 255, 12)).build());

        // Header
        Fonts.MNTSB.get(13).drawString(m, "Neuro", pX + 18f, pY + 15f, 0xFFE0E8FF);
        Fonts.MNTSB.get(10).drawString(m, "BWorld", pX + 75f, pY + 17f, 0xFF6688AA);

        // Status (top right)
        String mode = recording ? "mode=RECORD" : predictMode ? "mode=PREDICT" : "mode=IDLE";
        String info = mode + "  rec=" + AuraAI3.get().samples.size() + "  rl=on  hist=0";
        float iw = Fonts.MNTSB.get(9).getStringWidth(info);
        Fonts.MNTSB.get(9).drawString(m, info, pX + PW - iw - 16f, pY + 17f, 0xFF8899AA);

        // ═══ GAME AREA (Glass inset) ═══
        rectangle.render(ShapeProperties.create(m, gX, gY, gW, gH)
                .round(10f).color(ColorUtility.getColor(60, 120, 180, 15)).build());
        rectangle.render(ShapeProperties.create(m, gX + 1, gY + 1, gW - 2, gH - 2)
                .round(9f).color(ColorUtility.getColor(5, 8, 15, 240)).build());

        // Spawn targets
        if (!spawned && gW > 60) { spawned = true; spawnTarget(); }
        while (targets.size() < 1 && gW > 60) spawnTarget();

        // Draw target with jelly
        AimTarget activeTarget = null;
        for (AimTarget t : targets) {
            activeTarget = t;
            float rad = t.rad * jellyScale;
            // Glow ring
            drawGlowCircle(ctx, t.x, t.y, rad + 6f, ColorUtility.getColor(100, 150, 255, 40));
            // Main circle
            drawOutlineCircle(ctx, t.x, t.y, rad, ColorUtility.getColor(130, 180, 255, 220));
            // Center dot
            rectangle.render(ShapeProperties.create(m, t.x - 2f, t.y - 2f, 4f, 4f)
                    .round(2f).color(ColorUtility.getColor(130, 180, 255, 255)).build());
        }

        // ═══ PREDICT MODE ═══
        if (predictMode && activeTarget != null && AuraAI3.get().trained) {
            if (virtualX < 0) { virtualX = gX + gW / 2f; virtualY = gY + gH / 2f; }

            float dx = activeTarget.x - virtualX, dy = activeTarget.y - virtualY;
            float dist = (float) Math.hypot(dx, dy);
            // Normalize to [-1, 1]
            float yawNorm = MathHelper.clamp(dx / (gW * 0.5f), -1f, 1f);
            float pitchNorm = MathHelper.clamp(dy / (gH * 0.5f), -1f, 1f);

            float[] step = AuraAI3.get().predict(yawNorm, pitchNorm);
            virtualX += step[0]; virtualY += step[1];
            trail.add(new TrailPoint(virtualX, virtualY, now));

            if (dist <= activeTarget.rad + 4) {
                targets.remove(activeTarget); hits++; lastHitTime = now;
                if (mc.player != null) mc.player.playSound(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 1.6f);
            }

            // Crosshair (glass style)
            drawOutlineCircle(ctx, virtualX, virtualY, 8f, ColorUtility.getColor(255, 100, 100, 255));
            rectangle.render(ShapeProperties.create(m, virtualX - 14f, virtualY - 0.5f, 28f, 1f).color(ColorUtility.getColor(255, 100, 100, 180)).build());
            rectangle.render(ShapeProperties.create(m, virtualX - 0.5f, virtualY - 14f, 1f, 28f).color(ColorUtility.getColor(255, 100, 100, 180)).build());
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

        // ═══ TRAIL (fading glass) ═══
        trail.removeIf(pt -> now - pt.time > 2500);
        for (int i = 0; i < trail.size() - 1; i++) {
            TrailPoint p1 = trail.get(i), p2 = trail.get(i + 1);
            float f = 1f - (float)(now - p1.time) / 2500f;
            if (f <= 0) continue;
            int c = ColorUtility.getColor(100, 160, 255, (int)(f * 120));
            drawLine(ctx, p1.x, p1.y, p2.x, p2.y, 1.2f * f, c);
        }

        // ═══ BUTTONS (Glass pills) ═══
        drawGlassBtn(m, mx, my, bRecX, bRecY, "RECORD", recording);
        drawGlassBtn(m, mx, my, bTrnX, bTrnY, "TRAIN", training);
        drawGlassBtn(m, mx, my, bPrdX, bPrdY, "PREDICT", predictMode);
        drawGlassBtn(m, mx, my, bStpX, bStpY, "STOP", false);
        drawGlassBtn(m, mx, my, bClrX, bClrY, "CLEAR", false);

        // Training overlay
        if (training) {
            rectangle.render(ShapeProperties.create(m, gX, gY, gW, gH).round(9f).color(ColorUtility.getColor(5, 8, 15, 220)).build());
            String txt = "TRAINING MLP: " + (int)(trainProgress * 100) + "%";
            Fonts.MNTSB.get(11).drawCenteredString(m, txt, gX + gW / 2f, gY + gH / 2f - 8, 0xFF8ABAFF);
            float bw = 220f, bh = 5f, bx = gX + (gW - bw) / 2f, by = gY + gH / 2f + 12;
            rectangle.render(ShapeProperties.create(m, bx, by, bw, bh).round(2.5f).color(ColorUtility.getColor(30, 40, 60, 255)).build());
            rectangle.render(ShapeProperties.create(m, bx, by, bw * trainProgress, bh).round(2.5f).color(ColorUtility.getColor(80, 150, 255, 255)).build());
        }

        // Bottom status
        String st = String.format("RECORD: %d samples.", AuraAI3.get().samples.size());
        Fonts.MNTSB.get(10).drawString(m, st, pX + 18f, pY + PH - 14f, 0xFF667788);
    }

    private void drawGlassBtn(net.minecraft.client.util.math.MatrixStack m, int mx, int my, float x, float y, String label, boolean active) {
        boolean hover = mx >= x && mx <= x + BW && my >= y && my <= y + BH;
        int bg = active ? ColorUtility.getColor(80, 150, 255, 160) : ColorUtility.getColor(40, 60, 80, hover ? 140 : 90);
        rectangle.render(ShapeProperties.create(m, x, y, BW, BH).round(6f).color(bg).build());
        // Glass highlight
        rectangle.render(ShapeProperties.create(m, x + 2, y + 2, BW - 4, BH / 2 - 2).round(4f).color(ColorUtility.getColor(255, 255, 255, 10)).build());
        Fonts.MNTSB.get(10).drawCenteredString(m, label, x + BW / 2f, y + 6f, active ? 0xFFFFFFFF : 0xFFAABBCC);
    }

    @Override public boolean mouseClicked(double mx, double my, int btn) {
        if (btn == 0 && !training) {
            if (inBox(mx, my, bRecX, bRecY)) { recording = !recording; predictMode = false; return true; }
            if (inBox(mx, my, bTrnX, bTrnY)) {
                if (AuraAI3.get().samples.size() < 10) { AuraAI3.chatSink.accept("§b[AuraAI3] §cМинимум 10 сэмплов!"); }
                else { recording = false; predictMode = false; training = true; trainProgress = 0f; AuraAI3.get().trainModel(5000, p -> { trainProgress = p; if (p >= 1f) training = false; }); }
                return true;
            }
            if (inBox(mx, my, bPrdX, bPrdY)) { if (AuraAI3.get().trained) { predictMode = !predictMode; recording = false; virtualX = (float)mx; virtualY = (float)my; } else AuraAI3.chatSink.accept("§b[AuraAI3] §cОбучи сначала!"); return true; }
            if (inBox(mx, my, bStpX, bStpY)) { recording = false; predictMode = false; return true; }
            if (inBox(mx, my, bClrX, bClrY)) { AuraAI3.get().clear(); hits = 0; misses = 0; targets.clear(); spawned = false; predictMode = false; return true; }

            if (!predictMode) {
                for (AimTarget t : targets) { if (Math.hypot(mx - t.x, my - t.y) <= t.rad + 6) { targets.remove(t); hits++; lastHitTime = System.currentTimeMillis(); if (mc.player != null) mc.player.playSound(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK.value(), 0.4f, 1.5f); return true; } }
                if (recording && mx >= gX && mx <= gX + gW && my >= gY && my <= gY + gH) misses++;
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    private boolean inBox(double mx, double my, float x, float y) { return mx >= x && mx <= x + BW && my >= y && my <= y + BH; }

    private void spawnTarget() {
        AimTarget t = new AimTarget();
        t.rad = 14f + random.nextFloat() * 5f;
        t.x = gX + t.rad + random.nextFloat() * (gW - t.rad * 2);
        t.y = gY + t.rad + random.nextFloat() * (gH - t.rad * 2);
        targets.add(t);
    }

    private void drawLine(DrawContext ctx, float x1, float y1, float x2, float y2, float w, int c) {
        var m = ctx.getMatrices(); float dx = x2-x1, dy = y2-y1; float d = (float)Math.hypot(dx,dy); if (d==0) return;
        int steps = (int)Math.max(1, d/0.6f); for (int i=0;i<=steps;i++) { float t=(float)i/steps; float px=x1+dx*t,py=y1+dy*t; rectangle.render(ShapeProperties.create(m,px-w/2,py-w/2,w,w).round(w/2).color(c).build()); }
    }

    private void drawOutlineCircle(DrawContext ctx, float cx, float cy, float r, int color) {
        var m = ctx.getMatrices(); int seg = 48; double step = 2*Math.PI/seg;
        for (int i=0;i<seg;i++) { float px=cx+(float)(r*Math.cos(step*i)),py=cy+(float)(r*Math.sin(step*i)); rectangle.render(ShapeProperties.create(m,px-0.75f,py-0.75f,1.5f,1.5f).round(0.75f).color(color).build()); }
    }

    private void drawGlowCircle(DrawContext ctx, float cx, float cy, float r, int color) {
        var m = ctx.getMatrices(); int seg = 32; double step = 2*Math.PI/seg;
        for (int i=0;i<seg;i++) { float px=cx+(float)(r*Math.cos(step*i)),py=cy+(float)(r*Math.sin(step*i)); rectangle.render(ShapeProperties.create(m,px-2f,py-2f,4f,4f).round(2f).color(color).build()); }
    }

    @Override public boolean shouldCloseOnEsc() { return true; }
    @Override public void renderBackground(DrawContext ctx, int mx, int my, float delta) {}

    private static class TrailPoint { float x, y; long time; TrailPoint(float x, float y, long t) { this.x=x; this.y=y; this.time=t; } }
    private static class AimTarget { float x, y, rad; }
}
