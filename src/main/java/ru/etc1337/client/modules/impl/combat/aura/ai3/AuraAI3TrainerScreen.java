package ru.etc1337.client.modules.impl.combat.aura.ai3;

import net.minecraft.client.gui.DrawContext;
import ru.etc1337.api.ui.clickgui.api.MenuScreen;
import ru.etc1337.api.ui.clickgui.screen.InterfaceScreen;
import ru.etc1337.api.render.font.Fonts;
import ru.etc1337.api.render.rect.ShapeProperties;
import ru.etc1337.api.util.color.ColorUtility;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * AuraAI3TrainerScreen - A MenuScreen-based trainer panel that appears
 * inside the ClickGUI. Records mouse gestures on aim targets and
 * provides training controls.
 */
public class AuraAI3TrainerScreen extends MenuScreen {

    private boolean recording = false;
    private final Random random = new Random();

    // Static aim targets within the panel area
    private final List<float[]> targets = new ArrayList<>();
    private static final int TARGET_COUNT = 4;
    private static final float TARGET_SIZE = 14f;

    // Gesture recording
    private final List<Float> gestureYaws = new ArrayList<>();
    private final List<Float> gesturePitches = new ArrayList<>();
    private double lastMx = -1, lastMy = -1;

    // Stats
    private int totalHits = 0;
    private int totalMisses = 0;
    private String statusText = "";

    public AuraAI3TrainerScreen() {
        // MenuScreen no-arg constructor
    }

    @Override
    public void init() {
        this.width = 220;
        this.height = 260;
        spawnTargets();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float partialTicks) {
        float alpha = getAlpha();

        // Panel background
        context.fill((int) x, (int) y, (int)(x + width), (int)(y + height),
                ColorUtility.rgba(20, 20, 28, (int)(alpha * 230)));

        // Border
        context.fill((int) x, (int) y, (int)(x + width), (int) y + 1,
                ColorUtility.rgba(80, 100, 180, (int)(alpha * 200)));
        context.fill((int) x, (int)(y + height - 1), (int)(x + width), (int)(y + height),
                ColorUtility.rgba(80, 100, 180, (int)(alpha * 200)));

        // Title
        context.drawCenteredTextWithShadow(mc.textRenderer,
                "\u00a7b\u00a7lAI3 Trainer", (int)(x + width / 2), (int) y + 6, 0xFFFFFF);

        // Stats line
        AuraAI3 ai = AuraAI3.get();
        String gestureLine = String.format("\u00a77Gestures: \u00a7f%d", ai.gestures.size());
        context.drawTextWithShadow(mc.textRenderer, gestureLine, (int) x + 6, (int) y + 20, 0xFFFFFF);

        String hitsLine = String.format("\u00a7aHits: %d  \u00a7cMisses: %d", totalHits, totalMisses);
        context.drawTextWithShadow(mc.textRenderer, hitsLine, (int) x + 6, (int) y + 32, 0xFFFFFF);

        String samplesLine = String.format("\u00a77Samples: \u00a7f%d", ai.sampleCount());
        context.drawTextWithShadow(mc.textRenderer, samplesLine, (int) x + 6, (int) y + 44, 0xFFFFFF);

        if (!statusText.isEmpty()) {
            context.drawTextWithShadow(mc.textRenderer, statusText, (int) x + 6, (int) y + 56, 0xFFFFFF);
        }

        // Game area
        int areaX = (int) x + 10;
        int areaY = (int) y + 70;
        int areaW = (int) width - 20;
        int areaH = 130;

        context.fill(areaX, areaY, areaX + areaW, areaY + areaH,
                ColorUtility.rgba(10, 10, 15, (int)(alpha * 200)));
        // Area border
        context.fill(areaX, areaY, areaX + areaW, areaY + 1,
                ColorUtility.rgba(50, 50, 70, (int)(alpha * 150)));
        context.fill(areaX, areaY + areaH - 1, areaX + areaW, areaY + areaH,
                ColorUtility.rgba(50, 50, 70, (int)(alpha * 150)));

        // Render targets
        for (float[] t : targets) {
            float tx = areaX + t[0];
            float ty = areaY + t[1];
            float dist = (float) Math.hypot(mouseX - tx, mouseY - ty);
            boolean hovered = dist <= TARGET_SIZE;

            int tColor = hovered
                    ? ColorUtility.rgba(255, 80, 80, (int)(alpha * 230))
                    : ColorUtility.rgba(220, 50, 50, (int)(alpha * 180));

            int sz = (int) TARGET_SIZE;
            context.fill((int)(tx - sz), (int)(ty - sz), (int)(tx + sz), (int)(ty + sz), tColor);
            // Center dot
            context.fill((int)(tx - 2), (int)(ty - 2), (int)(tx + 2), (int)(ty + 2),
                    ColorUtility.rgba(255, 255, 255, (int)(alpha * 220)));
        }

        // Track mouse for gesture recording
        if (recording && lastMx >= 0) {
            float dx = (float)(mouseX - lastMx);
            float dy = (float)(mouseY - lastMy);
            if (Math.abs(dx) > 0.1f || Math.abs(dy) > 0.1f) {
                gestureYaws.add(dx);
                gesturePitches.add(dy);
            }
        }
        lastMx = mouseX;
        lastMy = mouseY;

        // Buttons
        int btnY = (int)(y + height - 35);
        int btnW = 55;
        int gap = 6;
        int btnStartX = (int) x + 10;

        renderBtn(context, recording ? "STOP" : "RECORD", btnStartX, btnY, btnW, 18, recording, mouseX, mouseY, alpha);
        renderBtn(context, "CLEAR", btnStartX + btnW + gap, btnY, btnW, 18, false, mouseX, mouseY, alpha);
    }

    private void renderBtn(DrawContext context, String label, int bx, int by, int bw, int bh,
                           boolean active, int mx, int my, float alpha) {
        boolean hovered = mx >= bx && mx <= bx + bw && my >= by && my <= by + bh;
        int bg;
        if (active) {
            bg = ColorUtility.rgba(180, 40, 40, (int)(alpha * 210));
        } else if (hovered) {
            bg = ColorUtility.rgba(55, 55, 75, (int)(alpha * 220));
        } else {
            bg = ColorUtility.rgba(35, 35, 50, (int)(alpha * 200));
        }
        context.fill(bx, by, bx + bw, by + bh, bg);
        context.drawCenteredTextWithShadow(mc.textRenderer, label, bx + bw / 2, by + 5, 0xFFFFFF);
    }

    @Override
    public void mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return;

        // Button clicks
        int btnY = (int)(y + height - 35);
        int btnW = 55;
        int gap = 6;
        int btnStartX = (int) x + 10;

        if (isInRect(mouseX, mouseY, btnStartX, btnY, btnW, 18)) {
            // RECORD / STOP toggle
            recording = !recording;
            if (recording) {
                gestureYaws.clear();
                gesturePitches.clear();
                AuraAI3.get().markEpisodeBoundary();
                statusText = "\u00a7cRecording...";
            } else {
                // Save gesture if we have data
                if (gestureYaws.size() >= 3) {
                    float totalAngle = 0f;
                    for (int i = 0; i < gestureYaws.size(); i++) {
                        totalAngle += (float) Math.hypot(gestureYaws.get(i), gesturePitches.get(i));
                    }
                    AuraAI3.get().addGesture(gestureYaws, gesturePitches, totalAngle);
                    statusText = "\u00a7aGesture saved! (" + gestureYaws.size() + " pts)";
                } else {
                    statusText = "\u00a77Gesture too short.";
                }
                gestureYaws.clear();
                gesturePitches.clear();
            }
            return;
        }

        if (isInRect(mouseX, mouseY, btnStartX + btnW + gap, btnY, btnW, 18)) {
            // CLEAR
            AuraAI3.get().clear();
            totalHits = 0;
            totalMisses = 0;
            statusText = "\u00a7cCleared.";
            return;
        }

        // Check target hits in game area
        int areaX = (int) x + 10;
        int areaY = (int) y + 70;

        for (int i = targets.size() - 1; i >= 0; i--) {
            float[] t = targets.get(i);
            float tx = areaX + t[0];
            float ty = areaY + t[1];
            float dist = (float) Math.hypot(mouseX - tx, mouseY - ty);
            if (dist <= TARGET_SIZE) {
                totalHits++;
                targets.remove(i);

                // If recording, save the gesture to the target
                if (recording && gestureYaws.size() >= 3) {
                    float totalAngle = 0f;
                    for (int j = 0; j < gestureYaws.size(); j++) {
                        totalAngle += (float) Math.hypot(gestureYaws.get(j), gesturePitches.get(j));
                    }
                    AuraAI3.get().addGesture(new ArrayList<>(gestureYaws), new ArrayList<>(gesturePitches), totalAngle);
                    gestureYaws.clear();
                    gesturePitches.clear();
                    AuraAI3.get().markEpisodeBoundary();
                }

                spawnTargets();
                return;
            }
        }
        totalMisses++;
    }

    private boolean isInRect(double mx, double my, int rx, int ry, int rw, int rh) {
        return mx >= rx && mx <= rx + rw && my >= ry && my <= ry + rh;
    }

    private void spawnTargets() {
        int areaW = (int) width - 40;
        int areaH = 110;
        while (targets.size() < TARGET_COUNT) {
            float tx = 10 + random.nextFloat() * areaW;
            float ty = 10 + random.nextFloat() * areaH;
            targets.add(new float[]{tx, ty});
        }
    }

    private float getAlpha() {
        if (mc.currentScreen instanceof InterfaceScreen s) {
            // InterfaceScreen animation alpha
            return 1.0f;
        }
        return 1.0f;
    }
}
