package ru.etc1337.client.modules.impl.combat.aura.ai3;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import ru.etc1337.api.interfaces.QuickImports;
import ru.etc1337.api.render.font.Fonts;
import ru.etc1337.api.render.rect.ShapeProperties;
import ru.etc1337.api.util.color.ColorUtility;

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

    private enum Mode { IDLE, RECORD, TRAIN, PREDICT, STOP, CLEAR }

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

    // Anti-stall timer
    private long lastTargetHitTime = 0;
    private static final long STALL_TIMEOUT_MS = 8000;

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
        lastTargetHitTime = System.currentTimeMillis();
    }

    @Override
    public void renderBackground(DrawContext ctx, int mx, int my, float delta) {
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        MatrixStack matrices = ctx.getMatrices();

        // Dark background
        rectangle.render(ShapeProperties.create(matrices, 0, 0, this.width, this.height)
                .color(ColorUtility.getColor(15, 15, 20, 230)).build());

        // Top border
        rectangle.render(ShapeProperties.create(matrices, 0, 0, this.width, 2)
                .color(ColorUtility.getColor(80, 80, 120, 255)).build());

        // Bottom border
        rectangle.render(ShapeProperties.create(matrices, 0, this.height - 2, this.width, 2)
                .color(ColorUtility.getColor(80, 80, 120, 255)).build());

        // Title
        Fonts.MNTSB.get(14).drawCenteredString(matrices, "AuraAI3 Neuro Trainer",
                this.width / 2f, 8, ColorUtility.getColor(100, 200, 255, 255));

        // Status bar - mode display
        String modeStr = switch (mode) {
            case IDLE -> "IDLE";
            case RECORD -> "RECORDING";
            case TRAIN -> "TRAINING";
            case PREDICT -> "PREDICTING";
            case STOP -> "STOPPED";
            case CLEAR -> "IDLE";
        };
        int modeColor = switch (mode) {
            case RECORD -> ColorUtility.getColor(255, 80, 80, 255);
            case PREDICT -> ColorUtility.getColor(80, 255, 80, 255);
            case TRAIN -> ColorUtility.getColor(255, 200, 50, 255);
            default -> ColorUtility.getColor(180, 180, 180, 255);
        };
        Fonts.MNTSB.get(11).drawCenteredString(matrices, "Mode: " + modeStr,
                this.width / 2f, 24, modeColor);

        // Stats
        AuraAI3 ai = AuraAI3.get();
        String stats = String.format("Samples: %d  Hits: %d  Misses: %d  Model: %s",
                ai.sampleCount(), hits, misses, ai.trained ? "Trained" : "Not trained");
        Fonts.MNTSB.get(9).drawCenteredString(matrices, stats,
                this.width / 2f, 38, ColorUtility.getColor(200, 200, 200, 255));

        if (!statusText.isEmpty()) {
            Fonts.MNTSB.get(9).drawCenteredString(matrices, statusText,
                    this.width / 2f, 50, ColorUtility.getColor(220, 220, 220, 255));
        }

        // Training progress bar
        if (trainProgress >= 0f && trainProgress < 1f) {
            int barW = 200;
            int barX = this.width / 2 - barW / 2;
            int barY = 60;
            rectangle.render(ShapeProperties.create(matrices, barX, barY, barW, 6)
                    .round(3).color(ColorUtility.getColor(40, 40, 40, 255)).build());
            int filledW = (int) (barW * trainProgress);
            if (filledW > 0) {
                rectangle.render(ShapeProperties.create(matrices, barX, barY, filledW, 6)
                        .round(3).color(ColorUtility.getColor(0, 200, 100, 255)).build());
            }
        }

        // Update trail
        updateTrail(mx, my);

        // Render trail
        renderTrail(matrices);

        // Render targets
        for (Target t : targets) {
            renderTarget(matrices, t, mx, my);
        }

        // Render particles
        updateAndRenderParticles(matrices, delta);

        // Anti-stall: respawn targets if none hit for too long
        if (mode == Mode.RECORD && System.currentTimeMillis() - lastTargetHitTime > STALL_TIMEOUT_MS) {
            targets.clear();
            spawnTargets();
            lastTargetHitTime = System.currentTimeMillis();
        }

        // Record mouse movement data
        if (mode == Mode.RECORD || mode == Mode.PREDICT) {
            processMouseMovement(mx, my);
        }

        // Buttons at the bottom
        int btnY = this.height - 30;
        int btnW = 60;
        int btnH = 20;
        int gap = 8;
        int totalW = btnW * 5 + gap * 4;
        int startX = this.width / 2 - totalW / 2;

        renderButton(matrices, "RECORD", startX, btnY, btnW, btnH, mode == Mode.RECORD, mx, my);
        renderButton(matrices, "TRAIN", startX + btnW + gap, btnY, btnW, btnH, mode == Mode.TRAIN, mx, my);
        renderButton(matrices, "PREDICT", startX + (btnW + gap) * 2, btnY, btnW, btnH, mode == Mode.PREDICT, mx, my);
        renderButton(matrices, "STOP", startX + (btnW + gap) * 3, btnY, btnW, btnH, false, mx, my);
        renderButton(matrices, "CLEAR", startX + (btnW + gap) * 4, btnY, btnW, btnH, false, mx, my);

        super.render(ctx, mx, my, delta);
    }

    private void renderButton(MatrixStack matrices, String label, int x, int y, int w, int h,
                              boolean active, int mx, int my) {
        boolean hovered = mx >= x && mx <= x + w && my >= y && my <= y + h;
        int bg;
        if (active) {
            bg = ColorUtility.getColor(200, 50, 50, 200);
        } else if (hovered) {
            bg = ColorUtility.getColor(60, 60, 80, 220);
        } else {
            bg = ColorUtility.getColor(35, 35, 50, 220);
        }
        rectangle.render(ShapeProperties.create(matrices, x, y, w, h)
                .round(4).color(bg).build());

        // Top highlight
        rectangle.render(ShapeProperties.create(matrices, x, y, w, 1)
                .color(ColorUtility.getColor(100, 100, 140, 180)).build());

        Fonts.MNTSB.get(9).drawCenteredString(matrices, label,
                x + w / 2f, y + 6, ColorUtility.getColor(255, 255, 255, 255));
    }

    private void renderTarget(MatrixStack matrices, Target t, int mx, int my) {
        float dist = (float) Math.hypot(mx - t.x, my - t.y);
        boolean hovered = dist <= TARGET_RADIUS;
        int color = hovered
                ? ColorUtility.getColor(255, 100, 100, 220)
                : ColorUtility.getColor(255, 60, 60, 180);

        // Outer ring
        float outerR = TARGET_RADIUS;
        rectangle.render(ShapeProperties.create(matrices, t.x - outerR, t.y - outerR, outerR * 2, outerR * 2)
                .round((int) outerR).color(ColorUtility.getColor(255, 255, 255, 40)).build());

        // Main body
        float innerR = TARGET_RADIUS * 0.8f;
        rectangle.render(ShapeProperties.create(matrices, t.x - innerR, t.y - innerR, innerR * 2, innerR * 2)
                .round((int) innerR).color(color).build());

        // Center dot
        rectangle.render(ShapeProperties.create(matrices, t.x - 3, t.y - 3, 6, 6)
                .round(3).color(ColorUtility.getColor(255, 255, 255, 255)).build());
    }

    private void renderTrail(MatrixStack matrices) {
        for (int i = 1; i < trail.size(); i++) {
            float[] prev = trail.get(i - 1);
            float[] curr = trail.get(i);
            float alpha = (float) i / trail.size();
            int color = ColorUtility.getColor(100, 180, 255, (int) (alpha * 150));
            float minX = Math.min(prev[0], curr[0]);
            float minY = Math.min(prev[1], curr[1]);
            float w = Math.max(Math.abs(curr[0] - prev[0]), 1);
            float h = Math.max(Math.abs(curr[1] - prev[1]), 1);
            rectangle.render(ShapeProperties.create(matrices, minX, minY, w, h)
                    .color(color).build());
        }
    }

    private void updateTrail(int mx, int my) {
        trail.add(new float[]{mx, my});
        while (trail.size() > MAX_TRAIL) {
            trail.remove(0);
        }
    }

    private void updateAndRenderParticles(MatrixStack matrices, float delta) {
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
            int alpha = (int) (p.life * 255);
            int color = ColorUtility.getColor(255, 200, 50, alpha);
            rectangle.render(ShapeProperties.create(matrices, p.x - 1, p.y - 1, 2, 2)
                    .round(1).color(color).build());
        }
    }

    private void spawnParticles(float x, float y) {
        for (int i = 0; i < 12; i++) {
            Particle p = new Particle();
            p.x = x;
            p.y = y;
            double angle = random.nextDouble() * Math.PI * 2;
            float speed = 1f + random.nextFloat() * 3f;
            p.vx = (float) (Math.cos(angle) * speed);
            p.vy = (float) (Math.sin(angle) * speed);
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
            if (!targets.isEmpty()) {
                Target closest = null;
                float minDist = Float.MAX_VALUE;
                for (Target t : targets) {
                    float dist = (float) Math.hypot(mx - t.x, my - t.y);
                    if (dist < minDist) {
                        minDist = dist;
                        closest = t;
                    }
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
            // Predicted output in out[0], out[1]
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
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
                    statusText = "Recording started...";
                    lastTargetHitTime = System.currentTimeMillis();
                } else {
                    mode = Mode.IDLE;
                    statusText = "Recording stopped.";
                }
                return true;
            }
            if (isInButton(mouseX, mouseY, startX + btnW + gap, btnY, btnW, 20)) {
                // TRAIN
                mode = Mode.TRAIN;
                trainProgress = 0f;
                statusText = "Training...";
                Consumer<Float> progressCb = p -> {
                    trainProgress = p;
                    if (p >= 1f) {
                        mode = Mode.IDLE;
                        statusText = "Training complete! Loss: " + String.format("%.5f", AuraAI3.get().lastLoss);
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
                    statusText = "Prediction mode active.";
                } else {
                    statusText = "Model not trained yet!";
                }
                return true;
            }
            if (isInButton(mouseX, mouseY, startX + (btnW + gap) * 3, btnY, btnW, 20)) {
                // STOP
                mode = Mode.STOP;
                mode = Mode.IDLE;
                statusText = "Stopped.";
                return true;
            }
            if (isInButton(mouseX, mouseY, startX + (btnW + gap) * 4, btnY, btnW, 20)) {
                // CLEAR
                AuraAI3.get().clear();
                hits = 0;
                misses = 0;
                trainProgress = -1f;
                statusText = "All data cleared.";
                return true;
            }

            // Check target hits
            for (int i = targets.size() - 1; i >= 0; i--) {
                Target t = targets.get(i);
                float dist = (float) Math.hypot(mouseX - t.x, mouseY - t.y);
                if (dist <= TARGET_RADIUS) {
                    hits++;
                    lastTargetHitTime = System.currentTimeMillis();
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
        int areaTop = 70;
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

    private static class Target {
        float x, y;
        Target(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    private static class Particle {
        float x, y, vx, vy, life;
    }
}
