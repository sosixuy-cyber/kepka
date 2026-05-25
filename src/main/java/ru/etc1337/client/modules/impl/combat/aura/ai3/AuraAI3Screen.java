package ru.etc1337.client.modules.impl.combat.aura.ai3;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import ru.etc1337.api.interfaces.QuickImports;
import ru.etc1337.api.render.font.Fonts;
import ru.etc1337.api.render.rect.ShapeProperties;
import ru.etc1337.api.util.color.ColorUtility;

import java.awt.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/**
 * AuraAI3Screen - OSU-like aim trainer for recording mouse movement data,
 * training the neural network, and testing predictions.
 */
public class AuraAI3Screen extends Screen implements QuickImports {

    private enum Mode { IDLE, RECORD, PREDICT }

    private Mode mode = Mode.IDLE;
    private final Random random = new Random();

    // Target circles
    private final List<Target> targets = new ArrayList<>();
    private static final int MAX_TARGETS = 3;
    private static final float TARGET_RADIUS = 20f;

    // Trail rendering
    private final List<float[]> trail = new ArrayList<>();
    private static final int MAX_TRAIL = 60;

    // Particles
    private final List<Particle> particles = new ArrayList<>();

    // Stats
    private int hits = 0;
    private int misses = 0;
    private float trainProgress = -1f;
    private String statusText = "";

    // Sliding window for predict mode
    private final List<float[]> predictWindow = new ArrayList<>();

    // Previous mouse position for delta calculation
    private double prevMouseX = -1, prevMouseY = -1;

    public AuraAI3Screen() {
        super(Text.literal("AuraAI3 Neuro Trainer"));
    }

    @Override
    protected void init() {
        super.init();
        spawnTargets();
        trail.clear();
        particles.clear();
        predictWindow.clear();
        prevMouseX = -1;
        prevMouseY = -1;
    }

    @Override
    public void renderBackground(DrawContext ctx, int mx, int my, float delta) {
        // Empty - we draw our own background
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        // Dark background
        ctx.fill(0, 0, this.width, this.height, ColorUtility.rgba(15, 15, 20, 230));

        // Border
        ctx.fill(0, 0, this.width, 2, ColorUtility.rgba(80, 80, 120, 255));
        ctx.fill(0, this.height - 2, this.width, this.height, ColorUtility.rgba(80, 80, 120, 255));

        // Title
        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("\u00a7b\u00a7lAuraAI3 Neuro Trainer"), this.width / 2, 8, 0xFFFFFF);

        // Status bar
        String modeStr = switch (mode) {
            case IDLE -> "\u00a77IDLE";
            case RECORD -> "\u00a7c\u00a7lRECORDING";
            case PREDICT -> "\u00a7a\u00a7lPREDICTING";
        };
        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Mode: " + modeStr), this.width / 2, 22, 0xFFFFFF);

        // Stats
        AuraAI3 ai = AuraAI3.get();
        String stats = String.format("\u00a77Samples: \u00a7f%d  \u00a77Hits: \u00a7a%d  \u00a77Misses: \u00a7c%d  \u00a77Model: %s",
                ai.sampleCount(), hits, misses, ai.trained ? "\u00a7aTrained" : "\u00a7cNot trained");
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(stats), this.width / 2, 34, 0xFFFFFF);

        if (!statusText.isEmpty()) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(statusText), this.width / 2, 46, 0xFFFFFF);
        }

        if (trainProgress >= 0f && trainProgress < 1f) {
            int barW = 200;
            int barX = this.width / 2 - barW / 2;
            int barY = 56;
            ctx.fill(barX, barY, barX + barW, barY + 6, ColorUtility.rgba(40, 40, 40, 255));
            ctx.fill(barX, barY, barX + (int)(barW * trainProgress), barY + 6, ColorUtility.rgba(0, 200, 100, 255));
        }

        // Update trail
        updateTrail(mx, my);

        // Render trail
        renderTrail(ctx);

        // Render targets
        for (Target t : targets) {
            renderTarget(ctx, t, mx, my);
        }

        // Render particles
        updateAndRenderParticles(ctx, delta);

        // Record mouse movement data
        if (mode == Mode.RECORD || mode == Mode.PREDICT) {
            processMouseMovement(mx, my);
        }

        // Buttons at the bottom
        int btnY = this.height - 30;
        int btnW = 60;
        int gap = 8;
        int totalW = btnW * 5 + gap * 4;
        int startX = this.width / 2 - totalW / 2;

        renderButton(ctx, "RECORD", startX, btnY, btnW, 20, mode == Mode.RECORD, mx, my);
        renderButton(ctx, "TRAIN", startX + btnW + gap, btnY, btnW, 20, false, mx, my);
        renderButton(ctx, "PREDICT", startX + (btnW + gap) * 2, btnY, btnW, 20, mode == Mode.PREDICT, mx, my);
        renderButton(ctx, "STOP", startX + (btnW + gap) * 3, btnY, btnW, 20, false, mx, my);
        renderButton(ctx, "CLEAR", startX + (btnW + gap) * 4, btnY, btnW, 20, false, mx, my);

        super.render(ctx, mx, my, delta);
    }

    private void renderButton(DrawContext ctx, String label, int x, int y, int w, int h, boolean active, int mx, int my) {
        boolean hovered = mx >= x && mx <= x + w && my >= y && my <= y + h;
        int bg;
        if (active) {
            bg = ColorUtility.rgba(200, 50, 50, 200);
        } else if (hovered) {
            bg = ColorUtility.rgba(60, 60, 80, 220);
        } else {
            bg = ColorUtility.rgba(35, 35, 50, 220);
        }
        ctx.fill(x, y, x + w, y + h, bg);
        ctx.fill(x, y, x + w, y + 1, ColorUtility.rgba(100, 100, 140, 180));
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(label), x + w / 2, y + 6, 0xFFFFFF);
    }

    private void renderTarget(DrawContext ctx, Target t, int mx, int my) {
        float dist = (float) Math.hypot(mx - t.x, my - t.y);
        boolean hovered = dist <= TARGET_RADIUS;
        int color = hovered ? ColorUtility.rgba(255, 100, 100, 220) : ColorUtility.rgba(255, 60, 60, 180);
        int outerColor = ColorUtility.rgba(255, 255, 255, 100);

        // Outer ring
        int r = (int) TARGET_RADIUS;
        ctx.fill((int)(t.x - r), (int)(t.y - r), (int)(t.x + r), (int)(t.y + r), ColorUtility.rgba(0, 0, 0, 0));

        // Filled circle approximation (square for simplicity, with inner highlight)
        int innerR = (int)(TARGET_RADIUS * 0.8f);
        ctx.fill((int)(t.x - innerR), (int)(t.y - innerR), (int)(t.x + innerR), (int)(t.y + innerR), color);

        // Center dot
        int dotR = 3;
        ctx.fill((int)(t.x - dotR), (int)(t.y - dotR), (int)(t.x + dotR), (int)(t.y + dotR), 0xFFFFFFFF);
    }

    private void renderTrail(DrawContext ctx) {
        for (int i = 1; i < trail.size(); i++) {
            float[] prev = trail.get(i - 1);
            float[] curr = trail.get(i);
            float alpha = (float) i / trail.size();
            int color = ColorUtility.rgba(100, 180, 255, (int)(alpha * 150));
            int x1 = (int) prev[0], y1 = (int) prev[1];
            int x2 = (int) curr[0], y2 = (int) curr[1];
            // Simple line approximation with a 1px rect
            ctx.fill(Math.min(x1, x2), Math.min(y1, y2),
                    Math.max(x1, x2) + 1, Math.max(y1, y2) + 1, color);
        }
    }

    private void updateTrail(int mx, int my) {
        trail.add(new float[]{mx, my});
        while (trail.size() > MAX_TRAIL) {
            trail.remove(0);
        }
    }

    private void updateAndRenderParticles(DrawContext ctx, float delta) {
        Iterator<Particle> it = particles.iterator();
        while (it.hasNext()) {
            Particle p = it.next();
            p.x += p.vx * delta;
            p.y += p.vy * delta;
            p.life -= delta * 0.05f;
            if (p.life <= 0f) {
                it.remove();
                continue;
            }
            int alpha = (int)(p.life * 255);
            int color = ColorUtility.rgba(255, 200, 50, alpha);
            ctx.fill((int) p.x - 1, (int) p.y - 1, (int) p.x + 1, (int) p.y + 1, color);
        }
    }

    private void spawnParticles(float x, float y) {
        for (int i = 0; i < 12; i++) {
            Particle p = new Particle();
            p.x = x;
            p.y = y;
            double angle = random.nextDouble() * Math.PI * 2;
            float speed = 1f + random.nextFloat() * 3f;
            p.vx = (float)(Math.cos(angle) * speed);
            p.vy = (float)(Math.sin(angle) * speed);
            p.life = 0.6f + random.nextFloat() * 0.4f;
            particles.add(p);
        }
    }

    private void processMouseMovement(int mx, int my) {
        if (prevMouseX < 0) {
            prevMouseX = mx;
            prevMouseY = my;
            return;
        }
        double dx = mx - prevMouseX;
        double dy = my - prevMouseY;
        prevMouseX = mx;
        prevMouseY = my;

        if (Math.abs(dx) < 0.001 && Math.abs(dy) < 0.001) return;

        float dxN = (float) Math.max(-1.0, Math.min(1.0, dx / 60.0));
        float dyN = (float) Math.max(-1.0, Math.min(1.0, dy / 30.0));

        if (mode == Mode.RECORD) {
            // Record: target direction as move velocity
            if (!targets.isEmpty()) {
                Target closest = null;
                float minDist = Float.MAX_VALUE;
                for (Target t : targets) {
                    float dist = (float) Math.hypot(mx - t.x, my - t.y);
                    if (dist < minDist) { minDist = dist; closest = t; }
                }
                if (closest != null) {
                    float tDx = closest.x - mx;
                    float tDy = closest.y - my;
                    float mvx = (float) Math.max(-1.0, Math.min(1.0, tDx / AuraAI3.STEP_SCALE_PX_TRAIN));
                    float mvy = (float) Math.max(-1.0, Math.min(1.0, tDy / AuraAI3.STEP_SCALE_PX_TRAIN));
                    AuraAI3.get().addRow(dxN, dyN, mvx, mvy);
                }
            }
        } else if (mode == Mode.PREDICT) {
            float[] out = AuraAI3.get().predict(dxN, dyN);
            // Predicted output available in out[0], out[1]
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // Check button clicks
            int btnY = this.height - 30;
            int btnW = 60;
            int gap = 8;
            int totalW = btnW * 5 + gap * 4;
            int startX = this.width / 2 - totalW / 2;

            if (isInButton(mouseX, mouseY, startX, btnY, btnW, 20)) {
                // RECORD
                if (mode != Mode.RECORD) {
                    mode = Mode.RECORD;
                    AuraAI3.get().markEpisodeBoundary();
                    statusText = "\u00a7cRecording started...";
                } else {
                    mode = Mode.IDLE;
                    statusText = "\u00a77Recording stopped.";
                }
                return true;
            }
            if (isInButton(mouseX, mouseY, startX + btnW + gap, btnY, btnW, 20)) {
                // TRAIN
                mode = Mode.IDLE;
                trainProgress = 0f;
                statusText = "\u00a7eTraining...";
                Consumer<Float> progressCb = p -> {
                    trainProgress = p;
                    if (p >= 1f) {
                        statusText = "\u00a7aTraining complete! Loss: " + String.format("%.5f", AuraAI3.get().lastLoss);
                    }
                };
                AuraAI3.get().trainModel(1000, progressCb);
                return true;
            }
            if (isInButton(mouseX, mouseY, startX + (btnW + gap) * 2, btnY, btnW, 20)) {
                // PREDICT
                if (AuraAI3.get().trained) {
                    mode = Mode.PREDICT;
                    AuraAI3.get().resetSequence();
                    statusText = "\u00a7aPrediction mode active.";
                } else {
                    statusText = "\u00a7cModel not trained yet!";
                }
                return true;
            }
            if (isInButton(mouseX, mouseY, startX + (btnW + gap) * 3, btnY, btnW, 20)) {
                // STOP
                mode = Mode.IDLE;
                statusText = "\u00a77Stopped.";
                return true;
            }
            if (isInButton(mouseX, mouseY, startX + (btnW + gap) * 4, btnY, btnW, 20)) {
                // CLEAR
                AuraAI3.get().clear();
                hits = 0;
                misses = 0;
                trainProgress = -1f;
                statusText = "\u00a7cAll data cleared.";
                return true;
            }

            // Check target hits
            for (int i = targets.size() - 1; i >= 0; i--) {
                Target t = targets.get(i);
                float dist = (float) Math.hypot(mouseX - t.x, mouseY - t.y);
                if (dist <= TARGET_RADIUS) {
                    hits++;
                    spawnParticles(t.x, t.y);
                    targets.remove(i);
                    spawnTargets();
                    return true;
                }
            }
            misses++;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean isInButton(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private void spawnTargets() {
        int margin = 80;
        int areaTop = 65;
        int areaBottom = this.height - 45;
        while (targets.size() < MAX_TARGETS) {
            float x = margin + random.nextFloat() * (this.width - margin * 2);
            float y = areaTop + random.nextFloat() * (areaBottom - areaTop);
            targets.add(new Target(x, y));
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        mode = Mode.IDLE;
        super.close();
    }

    // Inner classes

    private static class Target {
        float x, y;
        Target(float x, float y) { this.x = x; this.y = y; }
    }

    private static class Particle {
        float x, y, vx, vy, life;
    }
}
