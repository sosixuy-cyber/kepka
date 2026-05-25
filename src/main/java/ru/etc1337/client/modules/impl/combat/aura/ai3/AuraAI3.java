package ru.etc1337.client.modules.impl.combat.aura.ai3;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * AuraAI3 — Singleton controller for the AI3 rotation neural network.
 *
 * Manages the sliding window of inputs, model weights, training state,
 * and prediction for AuraAI3Rotation.
 */
public final class AuraAI3 {

    private static final AuraAI3 INSTANCE = new AuraAI3();

    public static AuraAI3 get() {
        return INSTANCE;
    }

    // ═══ Model parameters ═══
    private static final int WINDOW_SIZE = 16;
    private static final int INPUT_DIM = 2;  // dxN, dyN
    private static final int HIDDEN_DIM = 32;
    private static final int OUTPUT_DIM = 2;  // stepYaw, stepPitch (normalized)

    // ═══ State ═══
    public volatile boolean trained = false;
    public volatile boolean recording = false;

    private final float[][] window = new float[WINDOW_SIZE][INPUT_DIM];
    private int windowPos = 0;
    private boolean windowFull = false;

    // ═══ Model weights (simple 2-layer MLP) ═══
    private float[][] w1;  // [WINDOW_SIZE * INPUT_DIM][HIDDEN_DIM]
    private float[] b1;    // [HIDDEN_DIM]
    private float[][] w2;  // [HIDDEN_DIM][OUTPUT_DIM]
    private float[] b2;    // [OUTPUT_DIM]

    // ═══ Training data ═══
    private final List<float[]> recordedInputs = new ArrayList<>();
    private final List<float[]> recordedOutputs = new ArrayList<>();

    private AuraAI3() {
        initWeights();
    }

    private void initWeights() {
        int flatInput = WINDOW_SIZE * INPUT_DIM;
        w1 = new float[flatInput][HIDDEN_DIM];
        b1 = new float[HIDDEN_DIM];
        w2 = new float[HIDDEN_DIM][OUTPUT_DIM];
        b2 = new float[OUTPUT_DIM];

        Random rng = new Random(42);
        float scale1 = (float) Math.sqrt(2.0 / flatInput);
        for (int i = 0; i < flatInput; i++)
            for (int j = 0; j < HIDDEN_DIM; j++)
                w1[i][j] = (float) rng.nextGaussian() * scale1;

        float scale2 = (float) Math.sqrt(2.0 / HIDDEN_DIM);
        for (int i = 0; i < HIDDEN_DIM; i++)
            for (int j = 0; j < OUTPUT_DIM; j++)
                w2[i][j] = (float) rng.nextGaussian() * scale2;
    }

    // ═══ Prediction ═══

    /**
     * Feed current normalized deltas into sliding window and predict next rotation step.
     * @return float[2] — predicted [dyaw, dpitch] in normalized space
     */
    public float[] predict(float dxN, float dyN) {
        // Push to sliding window
        window[windowPos][0] = dxN;
        window[windowPos][1] = dyN;
        windowPos = (windowPos + 1) % WINDOW_SIZE;
        if (windowPos == 0) windowFull = true;

        // Flatten window (oldest first)
        int flatInput = WINDOW_SIZE * INPUT_DIM;
        float[] flat = new float[flatInput];
        int start = windowFull ? windowPos : 0;
        for (int i = 0; i < WINDOW_SIZE; i++) {
            int idx = (start + i) % WINDOW_SIZE;
            flat[i * INPUT_DIM] = window[idx][0];
            flat[i * INPUT_DIM + 1] = window[idx][1];
        }

        // Forward pass: hidden = relu(flat @ w1 + b1)
        float[] hidden = new float[HIDDEN_DIM];
        for (int j = 0; j < HIDDEN_DIM; j++) {
            float sum = b1[j];
            for (int i = 0; i < flatInput; i++) {
                sum += flat[i] * w1[i][j];
            }
            hidden[j] = Math.max(0f, sum); // ReLU
        }

        // Output = tanh(hidden @ w2 + b2)
        float[] output = new float[OUTPUT_DIM];
        for (int j = 0; j < OUTPUT_DIM; j++) {
            float sum = b2[j];
            for (int i = 0; i < HIDDEN_DIM; i++) {
                sum += hidden[i] * w2[i][j];
            }
            output[j] = (float) Math.tanh(sum);
        }

        return output;
    }

    // ═══ Sequence management ═══

    public void resetSequence() {
        for (int i = 0; i < WINDOW_SIZE; i++) {
            window[i][0] = 0f;
            window[i][1] = 0f;
        }
        windowPos = 0;
        windowFull = false;
    }

    public void markEpisodeBoundary() {
        // Mark boundary in training data if recording
        if (recording && !recordedInputs.isEmpty()) {
            recordedInputs.add(new float[]{Float.NaN, Float.NaN});
            recordedOutputs.add(new float[]{Float.NaN, Float.NaN});
        }
    }

    // ═══ Recording ═══

    public void startRecording() {
        recordedInputs.clear();
        recordedOutputs.clear();
        recording = true;
    }

    public void stopRecording() {
        recording = false;
    }

    public void recordSample(float inputDx, float inputDy, float outputDx, float outputDy) {
        if (!recording) return;
        recordedInputs.add(new float[]{inputDx, inputDy});
        recordedOutputs.add(new float[]{outputDx, outputDy});
    }

    public int getRecordedSamples() {
        return recordedInputs.size();
    }

    // ═══ Training ═══

    /**
     * Train the MLP on recorded data. Simple SGD with MSE loss.
     * @param epochs number of training epochs
     * @param lr learning rate
     */
    public void train(int epochs, float lr) {
        if (recordedInputs.isEmpty()) return;

        // Build training sequences from recorded data
        List<float[]> inputs = new ArrayList<>();
        List<float[]> targets = new ArrayList<>();

        float[][] trainWindow = new float[WINDOW_SIZE][INPUT_DIM];
        int wPos = 0;
        boolean wFull = false;

        for (int s = 0; s < recordedInputs.size(); s++) {
            float[] inp = recordedInputs.get(s);
            float[] tgt = recordedOutputs.get(s);

            // Skip episode boundaries
            if (Float.isNaN(inp[0])) {
                trainWindow = new float[WINDOW_SIZE][INPUT_DIM];
                wPos = 0;
                wFull = false;
                continue;
            }

            trainWindow[wPos][0] = inp[0];
            trainWindow[wPos][1] = inp[1];
            wPos = (wPos + 1) % WINDOW_SIZE;
            if (wPos == 0) wFull = true;

            if (wFull || wPos >= WINDOW_SIZE / 2) {
                // Flatten window
                int flatInput = WINDOW_SIZE * INPUT_DIM;
                float[] flat = new float[flatInput];
                int start = wFull ? wPos : 0;
                for (int i = 0; i < WINDOW_SIZE; i++) {
                    int idx = (start + i) % WINDOW_SIZE;
                    flat[i * INPUT_DIM] = trainWindow[idx][0];
                    flat[i * INPUT_DIM + 1] = trainWindow[idx][1];
                }
                inputs.add(flat);
                targets.add(new float[]{tgt[0], tgt[1]});
            }
        }

        if (inputs.isEmpty()) return;

        int flatInput = WINDOW_SIZE * INPUT_DIM;
        Random rng = new Random();

        for (int epoch = 0; epoch < epochs; epoch++) {
            // Shuffle
            for (int i = inputs.size() - 1; i > 0; i--) {
                int j = rng.nextInt(i + 1);
                Collections.swap(inputs, i, j);
                Collections.swap(targets, i, j);
            }

            for (int s = 0; s < inputs.size(); s++) {
                float[] flat = inputs.get(s);
                float[] tgt = targets.get(s);

                // Forward
                float[] hidden = new float[HIDDEN_DIM];
                for (int j = 0; j < HIDDEN_DIM; j++) {
                    float sum = b1[j];
                    for (int i = 0; i < flatInput; i++) sum += flat[i] * w1[i][j];
                    hidden[j] = Math.max(0f, sum);
                }

                float[] output = new float[OUTPUT_DIM];
                for (int j = 0; j < OUTPUT_DIM; j++) {
                    float sum = b2[j];
                    for (int i = 0; i < HIDDEN_DIM; i++) sum += hidden[i] * w2[i][j];
                    output[j] = (float) Math.tanh(sum);
                }

                // Loss gradient (MSE): dL/do = 2*(output - target)
                // tanh derivative: dtanh = 1 - tanh^2
                float[] dOutput = new float[OUTPUT_DIM];
                for (int j = 0; j < OUTPUT_DIM; j++) {
                    float err = output[j] - tgt[j];
                    float dtanh = 1f - output[j] * output[j];
                    dOutput[j] = 2f * err * dtanh;
                }

                // Backprop to w2, b2
                float[] dHidden = new float[HIDDEN_DIM];
                for (int i = 0; i < HIDDEN_DIM; i++) {
                    for (int j = 0; j < OUTPUT_DIM; j++) {
                        float grad = hidden[i] * dOutput[j];
                        w2[i][j] -= lr * grad;
                        dHidden[i] += w2[i][j] * dOutput[j];
                    }
                }
                for (int j = 0; j < OUTPUT_DIM; j++) {
                    b2[j] -= lr * dOutput[j];
                }

                // ReLU derivative
                for (int i = 0; i < HIDDEN_DIM; i++) {
                    if (hidden[i] <= 0f) dHidden[i] = 0f;
                }

                // Backprop to w1, b1
                for (int i = 0; i < flatInput; i++) {
                    for (int j = 0; j < HIDDEN_DIM; j++) {
                        w1[i][j] -= lr * flat[i] * dHidden[j];
                    }
                }
                for (int j = 0; j < HIDDEN_DIM; j++) {
                    b1[j] -= lr * dHidden[j];
                }
            }
        }

        trained = true;
    }

    // ═══ Persistence ═══

    public void saveModel(Path path) throws IOException {
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(path)))) {
            int flatInput = WINDOW_SIZE * INPUT_DIM;
            for (int i = 0; i < flatInput; i++)
                for (int j = 0; j < HIDDEN_DIM; j++)
                    dos.writeFloat(w1[i][j]);
            for (int j = 0; j < HIDDEN_DIM; j++)
                dos.writeFloat(b1[j]);
            for (int i = 0; i < HIDDEN_DIM; i++)
                for (int j = 0; j < OUTPUT_DIM; j++)
                    dos.writeFloat(w2[i][j]);
            for (int j = 0; j < OUTPUT_DIM; j++)
                dos.writeFloat(b2[j]);
        }
    }

    public void loadModel(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(path)))) {
            int flatInput = WINDOW_SIZE * INPUT_DIM;
            for (int i = 0; i < flatInput; i++)
                for (int j = 0; j < HIDDEN_DIM; j++)
                    w1[i][j] = dis.readFloat();
            for (int j = 0; j < HIDDEN_DIM; j++)
                b1[j] = dis.readFloat();
            for (int i = 0; i < HIDDEN_DIM; i++)
                for (int j = 0; j < OUTPUT_DIM; j++)
                    w2[i][j] = dis.readFloat();
            for (int j = 0; j < OUTPUT_DIM; j++)
                b2[j] = dis.readFloat();
            trained = true;
        }
    }

    public void resetModel() {
        initWeights();
        trained = false;
        resetSequence();
    }
}
