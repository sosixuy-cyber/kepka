package ru.etc1337.client.modules.impl.combat.aura.ai3;

import ai.djl.Model;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.nn.Activation;
import ai.djl.nn.Block;
import ai.djl.nn.Parameter;
import ai.djl.nn.SequentialBlock;
import ai.djl.nn.core.Linear;
import ai.djl.training.DefaultTrainingConfig;
import ai.djl.training.EasyTrain;
import ai.djl.training.ParameterStore;
import ai.djl.training.Trainer;
import ai.djl.training.dataset.ArrayDataset;
import ai.djl.training.dataset.Batch;
import ai.djl.training.initializer.XavierInitializer;
import ai.djl.training.loss.Loss;
import ai.djl.training.optimizer.Optimizer;
import ai.djl.training.tracker.Tracker;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/**
 * AuraAI3 v8 — Sliding Window MLP с обучением как в MouseMovementPredictor.
 *
 * Архитектура:
 *   Вход: 20 фреймов × 2 фичи (dxN, dyN) = 40 чисел
 *   40 → 128 → 64 → 32 → 2 (ReLU + Xavier)
 *   Loss: L2, Adam 1e-3, 1000 epochs
 *
 * Запись: при движении мыши пишем (dxN, dyN, mvx, mvy, episode_start).
 * Predict: окно последних 20 кадров → нейронка → шаг (px или градусы).
 */
public final class AuraAI3 {
    public static final int SEQ_LEN = 20;
    public static final int FEATURES = 2;
    public static final int IN_DIM = SEQ_LEN * FEATURES;
    public static final int OUT_DIM = 2;
    public static final double STEP_SCALE_PX_TRAIN = 30.0;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static AuraAI3 INSTANCE;
    public static volatile Consumer<String> chatSink = System.out::println;

    // Записанные кадры: (dxN, dyN, mvx, mvy, episode_start)
    public final transient List<double[]> recordedRows = new ArrayList<>();

    public boolean trained = false;
    public transient float lastLoss = Float.MAX_VALUE;

    // DJL ресурсы (transient — не сериализуются)
    private transient NDManager djlManager;
    private transient Model djlModel;
    private transient Block block;
    private transient ParameterStore parameterStore;

    // Sliding window для predict
    private transient final ArrayDeque<float[]> seqBuffer = new ArrayDeque<>(SEQ_LEN);
    private transient boolean episodeBoundary = true;

    public static synchronized AuraAI3 get() {
        if (INSTANCE == null) INSTANCE = load();
        return INSTANCE;
    }

    private AuraAI3() {
        try {
            djlManager = NDManager.newBaseManager();
        } catch (Throwable t) {
            chatSink.accept("§c[AuraAI3] DJL init failed: " + t.getMessage());
        }
        loadCsv();
        tryLoadModel();
    }

    // ═══ BACKWARD COMPAT: для AiAura3Command, AuraAI3TrainerScreen, Aura.java ═══
    public final List<Gesture> gestures = new ArrayList<>();

    public synchronized void addGesture(List<Float> yaws, List<Float> pitches, float targetAngle) {
        if (yaws.size() < 3) return;
        Gesture g = new Gesture();
        g.dYaw = new float[yaws.size()];
        g.dPitch = new float[pitches.size()];
        for (int i = 0; i < yaws.size(); i++) { g.dYaw[i] = yaws.get(i); g.dPitch[i] = pitches.get(i); }
        g.totalAngle = targetAngle;
        gestures.add(g);
    }

    public static final class Gesture {
        public float[] dYaw, dPitch;
        public float totalAngle, sumYaw, sumPitch;
    }

    // ═══ НОВЫЙ API ═══

    /** Записывает кадр: (dx_norm, dy_norm) → (mvx, mvy) */
    public synchronized void addRow(float dxN, float dyN, float mvx, float mvy) {
        int marker = episodeBoundary ? 1 : 0;
        episodeBoundary = false;
        recordedRows.add(new double[]{dxN, dyN, mvx, mvy, marker});
    }

    /** Помечаем границу эпизода (смена цели, начало записи) */
    public synchronized void markEpisodeBoundary() {
        episodeBoundary = true;
        seqBuffer.clear();
    }

    public int sampleCount() { return recordedRows.size(); }

    // ════════════════════ ОБУЧЕНИЕ ════════════════════

    public synchronized void trainModel(int epochs, Consumer<Float> progressCallback) {
        if (recordedRows.size() < 32) {
            chatSink.accept("§c[AuraAI3] Нужно минимум 32 кадра, сейчас: " + recordedRows.size());
            progressCallback.accept(1f);
            return;
        }
        new Thread(() -> {
            try { runTraining(epochs, progressCallback); }
            catch (Throwable t) {
                t.printStackTrace();
                chatSink.accept("§c[AuraAI3] Train error: " + t.getMessage());
                progressCallback.accept(1f);
            }
        }, "AuraAI3-Train").start();
    }

    private void runTraining(int epochs, Consumer<Float> progress) throws Exception {
        saveCsv();

        float[][][] xy = buildXYPair();
        float[][] X = xy[0];
        float[][] Y = xy[1];
        int n = X.length;
        if (n < 8) throw new IllegalStateException("not enough samples: " + n);

        chatSink.accept("§b[AuraAI3] §eОбучение PyTorch... samples=" + n + " epochs=" + epochs);

        Path modelDir = modelDir();
        Files.createDirectories(modelDir);

        try (Model m = buildModel();
             NDManager mgr = NDManager.newBaseManager()) {

            // Warm-start если веса уже есть
            Path paramsFile = findParamsFile(modelDir);
            if (paramsFile != null) {
                try {
                    m.load(modelDir, "aura_ai3");
                    chatSink.accept("§b[AuraAI3] §7warm-start from " + paramsFile.getFileName());
                } catch (Exception ignored) {}
            }

            // Плоские NDArray
            float[] flatX = new float[n * IN_DIM];
            float[] flatY = new float[n * OUT_DIM];
            for (int i = 0; i < n; i++) {
                System.arraycopy(X[i], 0, flatX, i * IN_DIM, IN_DIM);
                System.arraycopy(Y[i], 0, flatY, i * OUT_DIM, OUT_DIM);
            }
            NDArray xArr = mgr.create(flatX, new Shape(n, IN_DIM));
            NDArray yArr = mgr.create(flatY, new Shape(n, OUT_DIM));

            int batchSize = Math.max(8, Math.min(64, n / 8));
            ArrayDataset ds = new ArrayDataset.Builder()
                    .setData(xArr).optLabels(yArr)
                    .setSampling(batchSize, true)
                    .build();

            Optimizer opt = Optimizer.adam()
                    .optLearningRateTracker(Tracker.fixed(1e-3f))
                    .build();
            DefaultTrainingConfig cfg = new DefaultTrainingConfig(Loss.l2Loss())
                    .optOptimizer(opt)
                    .optInitializer(new XavierInitializer(), Parameter.Type.WEIGHT.toString());

            Loss lossFn = Loss.l2Loss();
            float bestLoss = Float.MAX_VALUE;

            try (Trainer trainer = m.newTrainer(cfg)) {
                trainer.initialize(new Shape(1, IN_DIM));

                for (int epoch = 0; epoch < epochs; epoch++) {
                    float epochLoss = 0f; int nBatches = 0;
                    for (Batch b : trainer.iterateDataset(ds)) {
                        EasyTrain.trainBatch(trainer, b);
                        trainer.step();
                        try (NDManager scope = mgr.newSubManager()) {
                            NDArray pred = trainer.forward(b.getData()).singletonOrThrow();
                            NDArray loss = lossFn.evaluate(b.getLabels(), new NDList(pred));
                            epochLoss += loss.getFloat();
                        } catch (Exception ignored) {}
                        nBatches++;
                        b.close();
                    }
                    trainer.notifyListeners(l -> l.onEpoch(trainer));
                    float avg = nBatches > 0 ? epochLoss / nBatches : 0f;
                    if (avg < bestLoss) bestLoss = avg;

                    if (epoch % 20 == 0 || epoch == epochs - 1) {
                        progress.accept((float) epoch / epochs);
                    }
                    if (epoch % 100 == 0) {
                        chatSink.accept(String.format("§b[AuraAI3] §7ep=%d loss=%.5f", epoch, avg));
                    }
                }
            }

            // Сохраняем
            m.save(modelDir, "aura_ai3");
            chatSink.accept(String.format("§b[AuraAI3] §aГотово! loss=%.5f saved=%s",
                    bestLoss, modelDir.resolve("aura_ai3-0000.params")));
        }

        this.lastLoss = bestLoss;
        this.trained = true;
        save();
        tryLoadModel();
        progress.accept(1f);
    }

    /** Сборка sliding window пар (X: 40, Y: 2) с учётом episode markers */
    private float[][][] buildXYPair() {
        // Делим на эпизоды по episode_start
        List<int[]> segments = new ArrayList<>();
        int segStart = 0;
        for (int i = 0; i < recordedRows.size(); i++) {
            if (i > 0 && (int) recordedRows.get(i)[4] == 1) {
                segments.add(new int[]{segStart, i});
                segStart = i;
            }
        }
        segments.add(new int[]{segStart, recordedRows.size()});

        List<float[]> Xs = new ArrayList<>();
        List<float[]> Ys = new ArrayList<>();

        for (int[] seg : segments) {
            int s = seg[0], e = seg[1];
            for (int i = s; i < e; i++) {
                float[] x = new float[IN_DIM];
                int slot = SEQ_LEN - 1;
                for (int k = i; k >= s && slot >= 0; k--, slot--) {
                    x[slot * 2]     = (float) recordedRows.get(k)[0];
                    x[slot * 2 + 1] = (float) recordedRows.get(k)[1];
                }
                // padding первым кадром
                if (slot >= 0) {
                    int firstFilled = slot + 1;
                    float fx = x[firstFilled * 2];
                    float fy = x[firstFilled * 2 + 1];
                    for (int k = slot; k >= 0; k--) {
                        x[k * 2] = fx;
                        x[k * 2 + 1] = fy;
                    }
                }
                double[] r = recordedRows.get(i);
                float clampX = (float) Math.max(-1.0, Math.min(1.0, r[2] / STEP_SCALE_PX_TRAIN));
                float clampY = (float) Math.max(-1.0, Math.min(1.0, r[3] / STEP_SCALE_PX_TRAIN));
                Xs.add(x);
                Ys.add(new float[]{clampX, clampY});
            }
        }
        return new float[][][]{
                Xs.toArray(new float[0][]),
                Ys.toArray(new float[0][])
        };
    }

    private static Model buildModel() {
        Model m = Model.newInstance("aura_ai3");
        SequentialBlock mlp = new SequentialBlock()
                .add(Linear.builder().setUnits(128).build())
                .add(Activation::relu)
                .add(Linear.builder().setUnits(64).build())
                .add(Activation::relu)
                .add(Linear.builder().setUnits(32).build())
                .add(Activation::relu)
                .add(Linear.builder().setUnits(OUT_DIM).build());
        m.setBlock(mlp);
        return m;
    }

    // ════════════════════ PREDICT ════════════════════

    /**
     * Predict со sliding window. Возвращает (mvx, mvy) в "model units".
     * Чтобы получить пиксели/градусы — умножь на свою STEP_SCALE.
     */
    public float[] predict(float dxN, float dyN) {
        if (!trained || block == null || parameterStore == null) {
            return new float[]{0f, 0f};
        }
        // Обновляем sliding window
        if (seqBuffer.size() < SEQ_LEN) {
            // Заполняем стартовым состоянием
            seqBuffer.clear();
            for (int i = 0; i < SEQ_LEN; i++) seqBuffer.addLast(new float[]{dxN, dyN});
        } else {
            seqBuffer.pollFirst();
            seqBuffer.addLast(new float[]{dxN, dyN});
        }

        try (NDManager scope = djlManager.newSubManager()) {
            float[] flat = new float[IN_DIM];
            int t = 0;
            for (float[] row : seqBuffer) {
                System.arraycopy(row, 0, flat, t, FEATURES);
                t += FEATURES;
            }
            NDArray in = scope.create(flat, new Shape(1, IN_DIM));
            NDList out = block.forward(parameterStore, new NDList(in), false);
            float[] arr = out.singletonOrThrow().toFloatArray();
            out.close();
            return new float[]{arr[0], arr[1]};
        } catch (Throwable e) {
            return new float[]{0f, 0f};
        }
    }

    /** Сброс sliding window — вызывать при смене цели */
    public void resetSequence() { seqBuffer.clear(); }

    // ════════════════════ MODEL LOAD ════════════════════

    private Path findParamsFile(Path dir) {
        try {
            return Files.list(dir)
                    .filter(p -> p.getFileName().toString().startsWith("aura_ai3-")
                            && p.getFileName().toString().endsWith(".params"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private void tryLoadModel() {
        closeModel();
        Path dir = modelDir();
        Path pf = findParamsFile(dir);
        if (pf == null) {
            chatSink.accept("§b[AuraAI3] §7нет params, нужно обучить");
            return;
        }
        try {
            Model m = buildModel();
            m.load(dir, "aura_ai3");
            this.djlModel = m;
            this.block = m.getBlock();
            this.parameterStore = new ParameterStore(djlManager, false);
            this.trained = true;
            chatSink.accept("§b[AuraAI3] §aЗагружено: " + pf.getFileName());
        } catch (Exception e) {
            chatSink.accept("§c[AuraAI3] load failed: " + e.getMessage());
            closeModel();
        }
    }

    private void closeModel() {
        if (djlModel != null) {
            try { djlModel.close(); } catch (Exception ignored) {}
            djlModel = null;
        }
        block = null;
        parameterStore = null;
    }

    // ════════════════════ FILES ════════════════════

    public synchronized void clear() {
        recordedRows.clear();
        trained = false;
        lastLoss = Float.MAX_VALUE;
        seqBuffer.clear();
        episodeBoundary = true;
        // Удаляем CSV и params
        try {
            Path csv = csvFile();
            if (Files.exists(csv)) Files.delete(csv);
            Path dir = modelDir();
            if (Files.exists(dir)) {
                Files.list(dir).filter(p -> p.getFileName().toString().endsWith(".params"))
                        .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
            }
        } catch (Exception ignored) {}
        save();
        closeModel();
        chatSink.accept("§b[AuraAI3] §cБаза очищена");
    }

    public static Path modelDir() {
        Path d = MinecraftClient.getInstance().runDirectory.toPath().resolve("dreamcore").resolve("aura_ai3");
        try { Files.createDirectories(d); } catch (IOException ignored) {}
        return d;
    }

    public static Path csvFile() { return modelDir().resolve("data.csv"); }
    public static Path file() { return modelDir().resolve("meta.json"); }

    public synchronized void save() {
        try { Files.writeString(file(), GSON.toJson(this)); }
        catch (Throwable t) { chatSink.accept("§c[AuraAI3] save: " + t.getMessage()); }
    }

    private synchronized void saveCsv() {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(csvFile()))) {
            w.println("dx_norm,dy_norm,mvx,mvy,episode_start");
            for (double[] r : recordedRows) {
                w.printf(Locale.US, "%.6f,%.6f,%.4f,%.4f,%d%n",
                        r[0], r[1], r[2], r[3], (int) r[4]);
            }
        } catch (Exception e) {
            chatSink.accept("§c[AuraAI3] csv: " + e.getMessage());
        }
    }

    private void loadCsv() {
        try {
            Path f = csvFile();
            if (!Files.exists(f)) return;
            List<String> lines = Files.readAllLines(f);
            for (int i = 1; i < lines.size(); i++) {
                String[] parts = lines.get(i).split(",");
                if (parts.length < 5) continue;
                double[] r = new double[5];
                for (int j = 0; j < 5; j++) r[j] = Double.parseDouble(parts[j]);
                recordedRows.add(r);
            }
            chatSink.accept("§b[AuraAI3] §7CSV loaded: " + recordedRows.size() + " rows");
        } catch (Exception ignored) {}
    }

    private static AuraAI3 load() {
        try {
            Path f = file();
            if (Files.exists(f)) {
                AuraAI3 p = GSON.fromJson(Files.readString(f), AuraAI3.class);
                if (p != null) {
                    AuraAI3 fresh = new AuraAI3();
                    fresh.trained = p.trained;
                    fresh.lastLoss = p.lastLoss;
                    return fresh;
                }
            }
        } catch (Throwable ignored) {}
        return new AuraAI3();
    }
}
