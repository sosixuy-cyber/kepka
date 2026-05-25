package ru.etc1337.client.modules.impl.combat.aura.ai3;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import ru.etc1337.api.interfaces.QuickImports;
import ru.etc1337.api.render.font.Fonts;
import ru.etc1337.api.render.rect.ShapeProperties;
import ru.etc1337.api.ui.clickgui.InterfaceScreen;
import ru.etc1337.api.ui.clickgui.api.MenuScreen;
import ru.etc1337.api.ui.style.Theme;
import ru.etc1337.api.util.color.ColorUtility;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * AuraAI3TrainerScreen - A MenuScreen-based trainer panel that appears
 * inside the ClickGUI. Records mouse gestures on aim targets and
 * provides training controls.
 */
public class AuraAI3TrainerScreen extends MenuScreen implements QuickImports {

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
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float partialTicks) {
        MatrixStack matrices = context.getMatrices();

        InterfaceScreen gui = getClickGUI();
        float alpha = gui != null ? gui.getAlpha().getValue() : 1.0f;
        int bgAlpha = (int) (alpha * 230);

        float panelW = 220;
        float panelH = 260;
        float panelX = this.x;
        float panelY = this.y;

        // Panel background
        rectangle.render(ShapeProperties.create(matrices, panelX, panelY, panelW, panelH)
                .round(6).color(ColorUtility.getColor(20, 20, 28, bgAlpha)).build());

        // Top border accent
        rectangle.render(ShapeProperties.create(matrices, panelX, panelY, panelW, 1)
                .color(ColorUtility.getColor(80, 100, 180, (int) (alpha * 200))).build());

        // Bottom border accent
        rectangle.render(ShapeProperties.create(matrices, panelX, panelY + panelH - 1, panelW, 1)
                .color(ColorUtility.getColor(80, 100, 180, (int) (alpha * 200))).build());

        // Title
        Fonts.MNTSB.get(12).drawCenteredString(matrices, "AI3 Trainer",
                panelX + panelW / 2f, panelY + 8, Theme.textHeader((int) (alpha * 255)));

        // Stats
        AuraAI3 ai = AuraAI3.get();
        Fonts.MNTSB.get(9).drawString(matrices, "Gestures: " + ai.gestures.size(),
                panelX + 8, panelY + 24, Theme.textPrimary((int) (alpha * 255)));

        String hitsLine = "Hits: " + totalHits + "  Misses: " + totalMisses;
        Fonts.MNTSB.get(9).drawString(matrices, hitsLine,
                panelX + 8, panelY + 36, Theme.textSecondary((int) (alpha * 255)));

        Fonts.MNTSB.get(9).drawString(matrices, "Samples: " + ai.sampleCount(),
                panelX + 8, panelY + 48, Theme.textSecondary((int) (alpha * 255)));

        if (!statusText.isEmpty()) {
            Fonts.MNTSB.get(8).drawString(matrices, statusText,
                    panelX + 8, panelY + 60, Theme.textPrimary((int) (alpha * 255)));
        }

        // Game area
        float areaX = panelX + 10;
        float areaY = panelY + 74;
        float areaW = panelW - 20;
        float areaH = 130;

        rectangle.render(ShapeProperties.create(matrices, areaX, areaY, areaW, areaH)
                .round(4).color(ColorUtility.getColor(10, 10, 15, (int) (alpha * 200))).build());

        // Area top border
        rectangle.render(ShapeProperties.create(matrices, areaX, areaY, areaW, 1)
                .color(ColorUtility.getColor(50, 50, 70, (int) (alpha * 150))).build());

        // Area bottom border
        rectangle.render(ShapeProperties.create(matrices, areaX, areaY + areaH - 1, areaW, 1)
                .color(ColorUtility.getColor(50, 50, 70, (int) (alpha * 150))).build());

        // Render targets
        for (float[] t : targets) {
            float tx = areaX + t[0];
            float ty = areaY + t[1];
            float dist = (float) Math.hypot(mouseX - tx, mouseY - ty);
            boolean hovered = dist <= TARGET_SIZE;

            int tColor = hovered
                    ? ColorUtility.getColor(255, 80, 80, (int) (alpha * 230))
                    : ColorUtility.getColor(220, 50, 50, (int) (alpha * 180));

            float sz = TARGET_SIZE;
            rectangle.render(ShapeProperties.create(matrices, tx - sz, ty - sz, sz * 2, sz * 2)
                    .round((int) sz).color(tColor).build());

            // Center dot
            rectangle.render(ShapeProperties.create(matrices, tx - 2, ty - 2, 4, 4)
                    .round(2).color(ColorUtility.getColor(255, 255, 255, (int) (alpha * 220))).build());
        }

        // Track mouse for gesture recording
        if (recording && lastMx >= 0) {
            float dx = (float) (mouseX - lastMx);
            float dy = (float) (mouseY - lastMy);
            if (Math.abs(dx) > 0.1f || Math.abs(dy) > 0.1f) {
                gestureYaws.add(dx);
                gesturePitches.add(dy);
            }
        }
        lastMx = mouseX;
        lastMy = mouseY;

        // Buttons
        float btnY2 = panelY + panelH - 35;
        int btnW = 55;
        int btnH = 18;
        int gap = 6;
        float btnStartX = panelX + 10;

        renderBtn(matrices, recording ? "STOP" : "START", btnStartX, btnY2, btnW, btnH,
                recording, mouseX, mouseY, alpha);
        renderBtn(matrices, "CLEAR", btnStartX + btnW + gap, btnY2, btnW, btnH,
                false, mouseX, mouseY, alpha);
    }

    private void renderBtn(MatrixStack matrices, String label, float bx, float by,
                           int bw, int bh, boolean active, int mx, int my, float alpha) {
        boolean hovered = mx >= bx && mx <= bx + bw && my >= by && my <= by + bh;
        int bg;
        if (active) {
            bg = ColorUtility.getColor(180, 40, 40, (int) (alpha * 210));
        } else if (hovered) {
            bg = ColorUtility.getColor(55, 55, 75, (int) (alpha * 220));
        } else {
            bg = ColorUtility.getColor(35, 35, 50, (int) (alpha * 200));
        }
        rectangle.render(ShapeProperties.create(matrices, bx, by, bw, bh)
                .round(4).color(bg).build());

        Fonts.MNTSB.get(9).drawCenteredString(matrices, label,
                bx + bw / 2f, by + 5, ColorUtility.getColor(255, 255, 255, (int) (alpha * 255)));
    }

    @Override
    public void mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return;

        float panelW = 220;
        float panelH = 260;
        float panelX = this.x;
        float panelY = this.y;

        // Button clicks
        float btnY2 = panelY + panelH - 35;
        int btnW = 55;
        int gap = 6;
        float btnStartX = panelX + 10;

        if (isInRect(mouseX, mouseY, btnStartX, btnY2, btnW, 18)) {
            // START / STOP toggle
            recording = !recording;
            if (recording) {
                gestureYaws.clear();
                gesturePitches.clear();
                AuraAI3.get().markEpisodeBoundary();
                statusText = "Recording...";
            } else {
                if (gestureYaws.size() >= 3) {
                    float totalAngle = 0f;
                    for (int i = 0; i < gestureYaws.size(); i++) {
                        totalAngle += (float) Math.hypot(gestureYaws.get(i), gesturePitches.get(i));
                    }
                    AuraAI3.get().addGesture(gestureYaws, gesturePitches, totalAngle);
                    statusText = "Gesture saved! (" + gestureYaws.size() + " pts)";
                } else {
                    statusText = "Gesture too short.";
                }
                gestureYaws.clear();
                gesturePitches.clear();
            }
            return;
        }

        if (isInRect(mouseX, mouseY, btnStartX + btnW + gap, btnY2, btnW, 18)) {
            // CLEAR
            AuraAI3.get().clear();
            totalHits = 0;
            totalMisses = 0;
            statusText = "Cleared.";
            return;
        }

        // Check target hits in game area
        float areaX = panelX + 10;
        float areaY = panelY + 74;

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

    private boolean isInRect(double mx, double my, float rx, float ry, int rw, int rh) {
        return mx >= rx && mx <= rx + rw && my >= ry && my <= ry + rh;
    }

    private void spawnTargets() {
        int areaW = 180;
        int areaH = 110;
        while (targets.size() < TARGET_COUNT) {
            float tx = 10 + random.nextFloat() * areaW;
            float ty = 10 + random.nextFloat() * areaH;
            targets.add(new float[]{tx, ty});
        }
    }

    private InterfaceScreen getClickGUI() {
        if (mc.currentScreen instanceof InterfaceScreen gui) {
            return gui;
        }
        return null;
    }
}
