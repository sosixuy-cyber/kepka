package ru.etc1337.client.modules.impl.combat.aura.ai3;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * AuraAI3TrainerScreen — Screen for training the neural network model.
 *
 * Allows the user to configure training parameters and run training
 * on recorded mouse movement data.
 */
public class AuraAI3TrainerScreen extends Screen {

    private static final Path MODEL_PATH = Paths.get("kepka", "ai3_model.bin");

    private final Screen parent;
    private int epochs = 50;
    private float learningRate = 0.001f;
    private volatile boolean training = false;
    private String statusMessage = "";

    public AuraAI3TrainerScreen(Screen parent) {
        super(Text.literal("AuraAI3 Trainer"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        int centerX = this.width / 2;
        int y = this.height / 4 + 20;

        // Epochs control
        addDrawableChild(ButtonWidget.builder(
                Text.literal("Epochs: " + epochs),
                button -> {
                    epochs = switch (epochs) {
                        case 10 -> 25;
                        case 25 -> 50;
                        case 50 -> 100;
                        case 100 -> 200;
                        case 200 -> 500;
                        default -> 10;
                    };
                    button.setMessage(Text.literal("Epochs: " + epochs));
                }
        ).dimensions(centerX - 100, y, 200, 20).build());

        y += 25;

        // Learning rate control
        addDrawableChild(ButtonWidget.builder(
                Text.literal("LR: " + learningRate),
                button -> {
                    if (learningRate >= 0.01f) learningRate = 0.001f;
                    else if (learningRate >= 0.001f) learningRate = 0.0005f;
                    else if (learningRate >= 0.0005f) learningRate = 0.0001f;
                    else learningRate = 0.01f;
                    button.setMessage(Text.literal("LR: " + learningRate));
                }
        ).dimensions(centerX - 100, y, 200, 20).build());

        y += 25;

        // Train button
        addDrawableChild(ButtonWidget.builder(
                Text.literal("Train Model"),
                button -> {
                    if (training) return;
                    training = true;
                    statusMessage = "Training...";
                    new Thread(() -> {
                        try {
                            AuraAI3.get().train(epochs, learningRate);
                            AuraAI3.get().saveModel(MODEL_PATH);
                            statusMessage = "\u00a7aTraining complete! Model saved.";
                        } catch (Exception e) {
                            statusMessage = "\u00a7cError: " + e.getMessage();
                        } finally {
                            training = false;
                        }
                    }, "AI3-Trainer").start();
                }
        ).dimensions(centerX - 100, y, 200, 20).build());

        y += 25;

        // Load model button
        addDrawableChild(ButtonWidget.builder(
                Text.literal("Load Model"),
                button -> {
                    try {
                        AuraAI3.get().loadModel(MODEL_PATH);
                        statusMessage = "\u00a7aModel loaded!";
                    } catch (Exception e) {
                        statusMessage = "\u00a7cFailed to load: " + e.getMessage();
                    }
                }
        ).dimensions(centerX - 100, y, 200, 20).build());

        y += 25;

        // Back button
        addDrawableChild(ButtonWidget.builder(
                Text.literal("Back"),
                button -> {
                    if (client != null) client.setScreen(parent);
                }
        ).dimensions(centerX - 100, y, 200, 20).build());
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        renderBackground(matrices);
        drawCenteredTextWithShadow(matrices, this.textRenderer,
                Text.literal("AuraAI3 Trainer"), this.width / 2, 20, 0xFFFFFF);

        AuraAI3 ai = AuraAI3.get();
        int y = this.height / 4;

        drawCenteredTextWithShadow(matrices, this.textRenderer,
                Text.literal("Recorded samples: " + ai.getRecordedSamples()),
                this.width / 2, y, 0xFFFFFF);

        if (!statusMessage.isEmpty()) {
            drawCenteredTextWithShadow(matrices, this.textRenderer,
                    Text.literal(statusMessage),
                    this.width / 2, y + 12, 0xFFFFFF);
        }

        super.render(matrices, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
}
