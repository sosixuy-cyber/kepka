package ru.etc1337.client.modules.impl.combat.aura.ai3;

import ai.djl.Model;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.nn.Activation;
import ai.djl.nn.SequentialBlock;
import ai.djl.nn.core.Linear;
import ai.djl.training.DefaultTrainingConfig;
import ai.djl.training.EasyTrain;
import ai.djl.training.Trainer;
import ai.djl.training.dataset.ArrayDataset;
import ai.djl.training.initializer.NormalInitializer;
import ai.djl.training.loss.Loss;
import ai.djl.training.optimizer.Optimizer;
import ai.djl.training.tracker.Tracker;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * AuraAI3 v6 — Обучение на нативном PyTorch через DJL.
 * Сеть: 2 → 64 → 32 → 16 → 2  (yaw_norm, pitch_norm → move_dyaw, move_dpitch)
 * Optimizer: Adam, Loss: MSE/L2, 5000 epochs, batch=64, LeakyReLU
 *
 * После обучения веса экспортируются в Java-массивы для быстрого predict без DJL overhead.
 */
public final class AuraAI3 {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static AuraAI3 INSTANCE;
    public static volatile Consumer<String> chatSink = System.out::println;

    public final List<Gesture> gestures = new ArrayList<>();
    public final transient List<TrainingSample> samples = new ArrayList<>();
    public TorchMLP mlp = new TorchMLP();
    public boolean trained = false;
    public transient float lastLoss = Float.MAX_VALUE;

    public static synchronized AuraAI3 get() {
        if (INSTANCE == null) INSTANCE = load();
        return INSTANCE;
    }

    public synchronized void addSample(float yawNorm, float pitchNorm, float moveDyaw, float moveDpitch) {
        samples.add(new TrainingSample(yawNorm, pitchNorm, moveDyaw, moveDpitch));
    }

    public synchronized void addGesture(List<Float> yaws, List<Float> pitches, float targetAngle) {
        if (yaws.size() < 3) return;
        Gesture g = new Gesture();
        g.dYaw = new float[yaws.size()];
        g.dPitch = new float[pitches.size()];
        float sumY = 0, sumP = 0;
        for (int i = 0; i < yaws.size(); i++) {
            g.dYaw[i] = yaws.get(i); g.dPitch[i] = pitches.get(i);
            sumY += Math.abs(g.dYaw[i]); sumP += Math.abs(g.dPitch[i]);
        }
        g.totalAngle = targetAngle; g.sumYaw = sumY; g.sumPitch = sumP;
        gestures.add(g); save();
    }

    public synchronized Gesture findBestGesture(float targetAngle) {
        if (gestures.isEmpty()) return null;
        Gesture best = null; float minDiff = Float.MAX_VALUE;
        for (Gesture g : gestures) {
            float d = Math.abs(g.totalAngle - targetAngle);
            if (d < minDiff) { minDiff = d; best = g; }
        }
        return best;
    }

    public synchronized void trainModel(int epochs, Consumer<Float> progressCallback) {
        if (samples.size() < 10) {
            chatSink.accept("§b[AuraAI3] §cМинимум 10 сэмплов!");
            progressCallback.accept(1f);
            return;
        }
        new Thread(() -> {
            try { trainPyTorch(epochs, progressCallback); }
            catch (Throwable t) {
                t.printStackTrace();
                chatSink.accept("§c[AuraAI3] PyTorch ошибка: " + t.getMessage());
                progressCallback.accept(1f);
            }
        }, "AuraAI3-PyTorch").start();
    }

    /** Обучение через DJL+PyTorch (нативный CPU) */
    private void trainPyTorch(int epochs, Consumer<Float> progressCallback) throws Exception {
        int n = samples.size();
        chatSink.accept("§b[AuraAI3] §eЗапуск PyTorch (CPU)... samples=" + n);

        // Нормализация выходов
        float maxMove = 1f;
        for (TrainingSample s : samples) {
            maxMove = Math.max(maxMove, Math.max(Math.abs(s.moveDyaw), Math.abs(s.moveDpitch)));
        }
        final float maxMoveF = maxMove;

        try (NDManager mgr = NDManager.newBaseManager()) {
            float[] inputData = new float[n * 2];
            float[] targetData = new float[n * 2];
            for (int i = 0; i < n; i++) {
                TrainingSample s = samples.get(i);
                inputData[i * 2] = s.yawNorm;
                inputData[i * 2 + 1] = s.pitchNorm;
                targetData[i * 2] = s.moveDyaw / maxMoveF;
                targetData[i * 2 + 1] = s.moveDpitch / maxMoveF;
            }

            NDArray inputArr = mgr.create(inputData, new Shape(n, 2));
            NDArray targetArr = mgr.create(targetData, new Shape(n, 2));

            ArrayDataset dataset = new ArrayDataset.Builder()
                    .setData(inputArr)
                    .optLabels(targetArr)
                    .setSampling(64, true)
                    .build();

            // Сеть: 2 → 64 → 32 → 16 → 2 с LeakyReLU
            SequentialBlock net = new SequentialBlock();
            net.add(Linear.builder().setUnits(64).build());
            net.add(Activation::leakyRelu);
            net.add(Linear.builder().setUnits(32).build());
            net.add(Activation::leakyRelu);
            net.add(Linear.builder().setUnits(16).build());
            net.add(Activation::leakyRelu);
            net.add(Linear.builder().setUnits(2).build());

            try (Model model = Model.newInstance("aura_ai3")) {
                model.setBlock(net);

                Tracker lr = Tracker.fixed(0.003f);
                Optimizer adam = Optimizer.adam().optLearningRateTracker(lr).build();

                DefaultTrainingConfig cfg = new DefaultTrainingConfig(Loss.l2Loss())
                        .optOptimizer(adam)
                        .optInitializer(new NormalInitializer(0.1f), "weight");

                try (Trainer trainer = model.newTrainer(cfg)) {
                    trainer.initialize(new Shape(1, 2));
                    float bestLoss = Float.MAX_VALUE;

                    for (int epoch = 0; epoch < epochs; epoch++) {
                        float epochLoss = 0f; int batches = 0;
                        for (var batch : trainer.iterateDataset(dataset)) {
                            EasyTrain.trainBatch(trainer, batch);
                            trainer.step();
                            float l = trainer.getLoss().getAccumulator("train_all").floatValue();
                            epochLoss += l; batches++;
                            batch.close();
                        }
                        trainer.notifyListeners(l -> l.onEpoch(trainer));
                        if (batches > 0) epochLoss /= batches;
                        if (epochLoss < bestLoss) bestLoss = epochLoss;

                        if (epoch % 20 == 0 || epoch == epochs - 1) {
                            progressCallback.accept((float) epoch / epochs);
                        }
                        if (epoch % 200 == 0) {
                            chatSink.accept(String.format("§b[AuraAI3] §7epoch=%d loss=%.5f", epoch, epochLoss));
                        }
                    }

                    extractWeights(model);
                    this.mlp.maxMove = maxMoveF;
                    this.lastLoss = bestLoss;
                    this.trained = true;
                    save();
                    chatSink.accept(String.format("§b[AuraAI3] §aPyTorch готово! loss=%.5f", bestLoss));
                }
            }
        }
        progressCallback.accept(1f);
    }

    /** Извлекаем веса из обученной DJL модели в наш TorchMLP формат для быстрого predict */
    private void extractWeights(Model model) {
        var params = model.getBlock().getParameters();
        int[][] shapes = {{2, 64}, {64, 32}, {32, 16}, {16, 2}};
        float[][][] weights = new float[4][][];
        float[][] biases = new float[4][];

        int linearIdx = 0;
        for (var pair : params) {
            String name = pair.getKey().toLowerCase();
            NDArray arr = pair.getValue().getArray();
            if (name.contains("weight")) {
                int li = linearIdx;
                int rows = shapes[li][0], cols = shapes[li][1];
                float[] flat = arr.toFloatArray();
                weights[li] = new float[rows][cols];
                if (flat.length == rows * cols) {
                    // DJL Linear weight shape: (out_features, in_features) = (cols, rows)
                    // транспонируем в (rows, cols) = (in, out)
                    for (int i = 0; i < rows; i++)
                        for (int j = 0; j < cols; j++)
                            weights[li][i][j] = flat[j * rows + i];
                }
            } else if (name.contains("bias")) {
                biases[linearIdx] = arr.toFloatArray();
                linearIdx++;
                if (linearIdx >= 4) break;
            }
        }

        mlp.w1 = weights[0]; mlp.b1 = biases[0];
        mlp.w2 = weights[1]; mlp.b2 = biases[1];
        mlp.w3 = weights[2]; mlp.b3 = biases[2];
        mlp.w4 = weights[3]; mlp.b4 = biases[3];
    }

    public float[] predict(float yawNorm, float pitchNorm) {
        if (!trained || mlp == null) return new float[]{0f, 0f};
        return mlp.forward(yawNorm, pitchNorm);
    }

    public synchronized void clear() {
        gestures.clear(); samples.clear(); mlp = new TorchMLP();
        trained = false; lastLoss = Float.MAX_VALUE; save();
        chatSink.accept("§b[AuraAI3] §cБаза очищена.");
    }

    public static Path file() {
        Path d = MinecraftClient.getInstance().runDirectory.toPath().resolve("dreamcore");
        try { Files.createDirectories(d); } catch (IOException ignored) {}
        return d.resolve("aura_ai3.json");
    }

    public synchronized void save() {
        try { Files.writeString(file(), GSON.toJson(this)); }
        catch (Throwable t) { chatSink.accept("§c[AuraAI3] " + t.getMessage()); }
    }

    private static AuraAI3 load() {
        try {
            Path f = file();
            if (Files.exists(f)) {
                AuraAI3 p = GSON.fromJson(Files.readString(f), AuraAI3.class);
                if (p != null) {
                    if (p.mlp == null) p.mlp = new TorchMLP();
                    return p;
                }
            }
        } catch (Throwable ignored) {}
        return new AuraAI3();
    }

    public static final class Gesture {
        public float[] dYaw, dPitch;
        public float totalAngle, sumYaw, sumPitch;
    }

    public static final class TrainingSample {
        public float yawNorm, pitchNorm;
        public float moveDyaw, moveDpitch;
        public TrainingSample() {}
        public TrainingSample(float yn, float pn, float my, float mp) {
            this.yawNorm = yn; this.pitchNorm = pn; this.moveDyaw = my; this.moveDpitch = mp;
        }
    }

    /**
     * Веса из обученной PyTorch-сети, выполняются на чистом Java для быстрого predict.
     * Сеть: 2 → 64 → 32 → 16 → 2 с LeakyReLU (как в DJL/PyTorch).
     */
    public static final class TorchMLP {
        public float[][] w1, w2, w3, w4;
        public float[] b1, b2, b3, b4;
        public float maxMove = 30f;

        public float[] forward(float yawNorm, float pitchNorm) {
            if (w1 == null) return new float[]{0f, 0f};
            float[] in = { Math.max(-1f, Math.min(1f, yawNorm)),
                           Math.max(-1f, Math.min(1f, pitchNorm)) };
            float[] h1 = leakyLayer(in, w1, b1, 64);
            float[] h2 = leakyLayer(h1, w2, b2, 32);
            float[] h3 = leakyLayer(h2, w3, b3, 16);
            float[] out = linearLayer(h3, w4, b4, 2);
            out[0] *= maxMove; out[1] *= maxMove;
            if (Float.isNaN(out[0]) || Float.isInfinite(out[0])) out[0] = 0f;
            if (Float.isNaN(out[1]) || Float.isInfinite(out[1])) out[1] = 0f;
            return out;
        }

        private float[] leakyLayer(float[] in, float[][] w, float[] b, int outSize) {
            float[] out = new float[outSize];
            for (int j = 0; j < outSize; j++) {
                float s = b[j];
                for (int k = 0; k < in.length; k++) s += in[k] * w[k][j];
                out[j] = s > 0 ? s : s * 0.01f;
            }
            return out;
        }

        private float[] linearLayer(float[] in, float[][] w, float[] b, int outSize) {
            float[] out = new float[outSize];
            for (int j = 0; j < outSize; j++) {
                float s = b[j];
                for (int k = 0; k < in.length; k++) s += in[k] * w[k][j];
                out[j] = s;
            }
            return out;
        }
    }
}
