package ru.etc1337.client.modules.impl.combat.aura.ai3;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;

/**
 * AuraAI3Screen — Main configuration screen for the AI3 aura module.
 *
 * Shows model status, recording controls, and links to trainer screen.
 */
public class AuraAI3Screen extends Screen {

    public AuraAI3Screen() {
        super(Text.literal("AuraAI3 Settings"));
    }

    @Override
    protected void init() {
        super.init();
        int centerX = this.width / 2;
        int y = this.height / 4;

        // Record button
        addDrawableChild(ButtonWidget.builder(
                Text.literal(AuraAI3.get().recording ? "Stop Recording" : "Start Recording"),
                button -> {
                    AuraAI3 ai = AuraAI3.get();
                    if (ai.recording) {
                        ai.stopRecording();
                        button.setMessage(Text.literal("Start Recording"));
                    } else {
                        ai.startRecording();
                        button.setMessage(Text.literal("Stop Recording"));
                    }
                }
        ).dimensions(centerX - 100, y, 200, 20).build());

        y += 30;

        // Open trainer screen
        addDrawableChild(ButtonWidget.builder(
                Text.literal("Open Trainer"),
                button -> {
                    if (client != null) {
                        client.setScreen(new AuraAI3TrainerScreen(this));
                    }
                }
        ).dimensions(centerX - 100, y, 200, 20).build());

        y += 30;

        // Reset model
        addDrawableChild(ButtonWidget.builder(
                Text.literal("Reset Model"),
                button -> {
                    AuraAI3.get().resetModel();
                }
        ).dimensions(centerX - 100, y, 200, 20).build());

        y += 30;

        // Back button
        addDrawableChild(ButtonWidget.builder(
                Text.literal("Back"),
                button -> close()
        ).dimensions(centerX - 100, y, 200, 20).build());
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        renderBackground(matrices);
        drawCenteredTextWithShadow(matrices, this.textRenderer,
                Text.literal("AuraAI3 Settings"), this.width / 2, 20, 0xFFFFFF);

        AuraAI3 ai = AuraAI3.get();
        int y = this.height / 4 - 20;

        String status = ai.trained ? "\u00a7aTrained" : "\u00a7cNot Trained";
        drawCenteredTextWithShadow(matrices, this.textRenderer,
                Text.literal("Model: " + status), this.width / 2, y, 0xFFFFFF);

        if (ai.recording) {
            drawCenteredTextWithShadow(matrices, this.textRenderer,
                    Text.literal("\u00a7cRecording... Samples: " + ai.getRecordedSamples()),
                    this.width / 2, y + 12, 0xFFFFFF);
        }

        super.render(matrices, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
