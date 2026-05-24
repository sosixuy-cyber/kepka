package ru.etc1337.client.modules.impl.combat.aura.ai3;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/**
 * AuraAI3 v2 — НАСТОЯЩЕЕ обучение стилю игрока.
 *
 * Ключевые отличия от v1:
 * 1. Расширенный вход: [dist, angle, speed, prevStepX, prevStepY, phase, noise] = 7 входов
 * 2. Двухслойная сеть: 7 → 24 → 16 → 2 (достаточно для захвата нелинейных паттернов)
 * 3. Запись ПОСЛЕДОВАТЕЛЬНОСТЕЙ движений, а не отдельных точек
 * 4. Predict воспроизводит ТВОЙ характер: рывки, замедления, кривые траектории
 */
public final class AuraAI3 {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static AuraAI3 INSTANCE;

    public static volatile Consumer<String> chatSink = System.out::println;


    public final List<Gesture> gestures = new ArrayList<>();
    public final transient List<TrainingSample> samples = new ArrayList<>();

    public DeepMLP mlp = new DeepMLP();
    public boolean trained = false;

    // Статистика обучения
    public transient float lastLoss = Float.MAX_VALUE;

    public static synchronized AuraAI3 get() {
        if (INSTANCE == null) INSTANCE = load();
        return INSTANCE;
    }

    /**
     * Записывает сэмпл с ПОЛНЫМ контекстом движения.
     * Входы: расстояние, угол, текущая скорость, предыдущий шаг, фаза.
     * Выходы: реальный шаг игрока (dx, dy пикселей).
     */
    public synchronized void addSample(float distToTarget, float angleToTarget,
                                        float currentSpeed, float prevStepX, float prevStepY,
                                        float phase, float actualStepX, float actualStepY) {
        samples.add(new TrainingSample(
                distToTarget, angleToTarget, currentSpeed,
                prevStepX, prevStepY, phase,
                actualStepX, actualStepY
        ));
    }


    public synchronized void addGesture(List<Float> yaws, List<Float> pitches, float targetAngle) {
        if (yaws.size() < 3) return;

        Gesture g = new Gesture();
        g.dYaw = new float[yaws.size()];
        g.dPitch = new float[pitches.size()];

        float sumYaw = 0, sumPitch = 0;
        for (int i = 0; i < yaws.size(); i++) {
            g.dYaw[i] = yaws.get(i);
            g.dPitch[i] = pitches.get(i);
            sumYaw += Math.abs(g.dYaw[i]);
            sumPitch += Math.abs(g.dPitch[i]);
        }

        g.totalAngle = targetAngle;
        g.sumYaw = sumYaw;
        g.sumPitch = sumPitch;

        gestures.add(g);
        save();
        chatSink.accept("§b[AuraAI3] §aЗаписан жест! Всего в базе: §f" + gestures.size());
    }

    public synchronized Gesture findBestGesture(float targetAngle) {
        if (gestures.isEmpty()) return null;
        Gesture best = null;
        float minDiff = Float.MAX_VALUE;
        for (Gesture g : gestures) {
            float diff = Math.abs(g.totalAngle - targetAngle);
            if (diff < minDiff) { minDiff = diff; best = g; }
        }
        return best;
    }


    /**
     * НАСТОЯЩЕЕ обучение — Adam optimizer, mini-batch, early stopping.
     * Сеть учит ТВОЙ стиль: как ты разгоняешься, как тормозишь, как кривишь траекторию.
     */
    public synchronized void trainModel(int epochs, Consumer<Float> progressCallback) {
        if (samples.size() < 20) {
            chatSink.accept("§b[AuraAI3] §cНужно минимум 20 сэмплов! Сейчас: " + samples.size());
            progressCallback.accept(1.0f);
            return;
        }

        new Thread(() -> {
            try {
                trainInternal(epochs, progressCallback);
            } catch (Throwable t) {
                chatSink.accept("§c[AuraAI3] Ошибка обучения: " + t.getMessage());
                progressCallback.accept(1.0f);
            }
        }, "AuraAI3-Train").start();
    }

    private void trainInternal(int epochs, Consumer<Float> progressCallback) {
        int n = samples.size();
        List<TrainingSample> data = new ArrayList<>(samples);

        // Нормализация: вычисляем max значения для каждого входа
        float maxDist = 1f, maxSpeed = 1f, maxStep = 1f;
        for (TrainingSample s : data) {
            maxDist = Math.max(maxDist, Math.abs(s.dist));
            maxSpeed = Math.max(maxSpeed, Math.abs(s.speed));
            maxStep = Math.max(maxStep, Math.max(Math.abs(s.stepX), Math.abs(s.stepY)));
        }


        // Adam optimizer state
        float lr = 0.003f;
        float beta1 = 0.9f, beta2 = 0.999f, eps = 1e-8f;
        DeepMLP net = new DeepMLP();
        net.heInit();

        // Adam momentum/velocity для всех весов
        float[][] mW1 = new float[6][32], vW1 = new float[6][32];
        float[] mB1 = new float[32], vB1 = new float[32];
        float[][] mW2 = new float[32][16], vW2 = new float[32][16];
        float[] mB2 = new float[16], vB2 = new float[16];
        float[][] mW3 = new float[16][2], vW3 = new float[16][2];
        float[] mB3 = new float[2], vB3 = new float[2];

        int batchSize = Math.min(64, n);
        int t = 0;
        float bestLoss = Float.MAX_VALUE;
        DeepMLP bestNet = null;
        int patience = 0;

        for (int epoch = 0; epoch < epochs; epoch++) {
            Collections.shuffle(data);
            float epochLoss = 0f;

            for (int batch = 0; batch < n; batch += batchSize) {
                int end = Math.min(batch + batchSize, n);
                t++;

                // Аккумуляторы градиентов
                float[][] gW1 = new float[6][32];
                float[] gB1 = new float[32];
                float[][] gW2 = new float[32][16];
                float[] gB2 = new float[16];
                float[][] gW3 = new float[16][2];
                float[] gB3 = new float[2];


                for (int i = batch; i < end; i++) {
                    TrainingSample s = data.get(i);

                    // Нормализация входов в [-1, 1] — БЕЗ noise
                    float[] input = new float[] {
                        s.dist / maxDist,
                        s.angle / (float)Math.PI,
                        s.speed / maxSpeed,
                        s.prevStepX / maxStep,
                        s.prevStepY / maxStep,
                        s.phase
                    };

                    float targetX = s.stepX / maxStep;
                    float targetY = s.stepY / maxStep;

                    // Forward pass: input(6) -> hidden1(32) -> hidden2(16) -> out(2)
                    float[] h1raw = new float[32];
                    float[] h1 = new float[32];
                    for (int j = 0; j < 32; j++) {
                        float sum = net.b1[j];
                        for (int k = 0; k < 6; k++) sum += input[k] * net.w1[k][j];
                        h1raw[j] = sum;
                        h1[j] = sum > 0 ? sum : sum * 0.05f;
                    }

                    float[] h2raw = new float[16];
                    float[] h2 = new float[16];
                    for (int j = 0; j < 16; j++) {
                        float sum = net.b2[j];
                        for (int k = 0; k < 32; k++) sum += h1[k] * net.w2[k][j];
                        h2raw[j] = sum;
                        h2[j] = sum > 0 ? sum : sum * 0.05f;
                    }

                    float[] out = new float[2];
                    for (int j = 0; j < 2; j++) {
                        float sum = net.b3[j];
                        for (int k = 0; k < 16; k++) sum += h2[k] * net.w3[k][j];
                        out[j] = sum; // Linear output
                    }


                    // Loss (MSE)
                    float errX = out[0] - targetX;
                    float errY = out[1] - targetY;
                    epochLoss += errX * errX + errY * errY;

                    // Backprop: output layer
                    float[] dOut = new float[] { errX * 2f / batchSize, errY * 2f / batchSize };

                    // Градиенты W3, B3
                    float[] dH2 = new float[16];
                    for (int j = 0; j < 2; j++) {
                        for (int k = 0; k < 16; k++) {
                            gW3[k][j] += dOut[j] * h2[k];
                            dH2[k] += dOut[j] * net.w3[k][j];
                        }
                        gB3[j] += dOut[j];
                    }

                    // Через LeakyReLU hidden2
                    for (int j = 0; j < 16; j++) {
                        dH2[j] *= (h2raw[j] > 0 ? 1f : 0.05f);
                    }

                    // Градиенты W2, B2
                    float[] dH1 = new float[32];
                    for (int j = 0; j < 16; j++) {
                        for (int k = 0; k < 32; k++) {
                            gW2[k][j] += dH2[j] * h1[k];
                            dH1[k] += dH2[j] * net.w2[k][j];
                        }
                        gB2[j] += dH2[j];
                    }

                    // Через LeakyReLU hidden1
                    for (int j = 0; j < 32; j++) {
                        dH1[j] *= (h1raw[j] > 0 ? 1f : 0.05f);
                    }

                    // Градиенты W1, B1
                    for (int j = 0; j < 32; j++) {
                        for (int k = 0; k < 6; k++) {
                            gW1[k][j] += dH1[j] * input[k];
                        }
                        gB1[j] += dH1[j];
                    }
                }


                // Adam update
                float bc1 = 1f - (float)Math.pow(beta1, t);
                float bc2 = 1f - (float)Math.pow(beta2, t);

                for (int k = 0; k < 6; k++) {
                    for (int j = 0; j < 32; j++) {
                        mW1[k][j] = beta1 * mW1[k][j] + (1 - beta1) * gW1[k][j];
                        vW1[k][j] = beta2 * vW1[k][j] + (1 - beta2) * gW1[k][j] * gW1[k][j];
                        float mh = mW1[k][j] / bc1;
                        float vh = vW1[k][j] / bc2;
                        net.w1[k][j] -= lr * mh / ((float)Math.sqrt(vh) + eps);
                    }
                }
                for (int j = 0; j < 32; j++) {
                    mB1[j] = beta1 * mB1[j] + (1 - beta1) * gB1[j];
                    vB1[j] = beta2 * vB1[j] + (1 - beta2) * gB1[j] * gB1[j];
                    net.b1[j] -= lr * (mB1[j] / bc1) / ((float)Math.sqrt(vB1[j] / bc2) + eps);
                }

                for (int k = 0; k < 32; k++) {
                    for (int j = 0; j < 16; j++) {
                        mW2[k][j] = beta1 * mW2[k][j] + (1 - beta1) * gW2[k][j];
                        vW2[k][j] = beta2 * vW2[k][j] + (1 - beta2) * gW2[k][j] * gW2[k][j];
                        float mh = mW2[k][j] / bc1;
                        float vh = vW2[k][j] / bc2;
                        net.w2[k][j] -= lr * mh / ((float)Math.sqrt(vh) + eps);
                    }
                }
                for (int j = 0; j < 16; j++) {
                    mB2[j] = beta1 * mB2[j] + (1 - beta1) * gB2[j];
                    vB2[j] = beta2 * vB2[j] + (1 - beta2) * gB2[j] * gB2[j];
                    net.b2[j] -= lr * (mB2[j] / bc1) / ((float)Math.sqrt(vB2[j] / bc2) + eps);
                }


                for (int k = 0; k < 16; k++) {
                    for (int j = 0; j < 2; j++) {
                        mW3[k][j] = beta1 * mW3[k][j] + (1 - beta1) * gW3[k][j];
                        vW3[k][j] = beta2 * vW3[k][j] + (1 - beta2) * gW3[k][j] * gW3[k][j];
                        float mh = mW3[k][j] / bc1;
                        float vh = vW3[k][j] / bc2;
                        net.w3[k][j] -= lr * mh / ((float)Math.sqrt(vh) + eps);
                    }
                }
                for (int j = 0; j < 2; j++) {
                    mB3[j] = beta1 * mB3[j] + (1 - beta1) * gB3[j];
                    vB3[j] = beta2 * vB3[j] + (1 - beta2) * gB3[j] * gB3[j];
                    net.b3[j] -= lr * (mB3[j] / bc1) / ((float)Math.sqrt(vB3[j] / bc2) + eps);
                }
            }

            epochLoss /= n;

            // Early stopping: сохраняем лучшую модель
            if (epochLoss < bestLoss) {
                bestLoss = epochLoss;
                bestNet = net.copy();
                patience = 0;
            } else {
                patience++;
                if (patience > 150) {
                    // LR decay
                    lr *= 0.5f;
                    patience = 0;
                    if (lr < 1e-5f) break;
                }
            }

            if (epoch % 10 == 0 || epoch == epochs - 1) {
                progressCallback.accept((float) epoch / epochs);
            }

            try { Thread.sleep(1); } catch (InterruptedException ignored) { break; }
        }


        // Применяем лучшую модель
        if (bestNet != null) {
            this.mlp = bestNet;
        } else {
            this.mlp = net;
        }
        this.mlp.maxDist = maxDist;
        this.mlp.maxSpeed = maxSpeed;
        this.mlp.maxStep = maxStep;
        this.lastLoss = bestLoss;
        this.trained = true;
        save();
        chatSink.accept(String.format(
                "§b[AuraAI3] §aОбучение завершено! Loss=%.5f, samples=%d", bestLoss, n));
        progressCallback.accept(1.0f);
    }

    /**
     * Предсказание шага — 100% ТВОЙ стиль, без фейков и рандома.
     * Сеть выдает РОВНО то что выучила из твоих движений.
     */
    public float[] predict(float dist, float angle, float speed,
                           float prevStepX, float prevStepY, float phase) {
        if (!trained || mlp == null) return new float[] { 0f, 0f };
        return mlp.forward(dist, angle, speed, prevStepX, prevStepY, phase);
    }

    public synchronized void clear() {
        gestures.clear();
        samples.clear();
        mlp = new DeepMLP();
        trained = false;
        lastLoss = Float.MAX_VALUE;
        save();
        chatSink.accept("§b[AuraAI3] §cБаза и веса полностью очищены.");
    }


    public static Path file() {
        Path dir = MinecraftClient.getInstance().runDirectory.toPath().resolve("dreamcore");
        try { Files.createDirectories(dir); } catch (IOException ignored) {}
        return dir.resolve("aura_ai3.json");
    }

    public synchronized void save() {
        try {
            Files.writeString(file(), GSON.toJson(this));
        } catch (Throwable t) {
            chatSink.accept("§c[AuraAI3] Ошибка сохранения: " + t.getMessage());
        }
    }

    private static AuraAI3 load() {
        try {
            Path f = file();
            if (Files.exists(f)) {
                AuraAI3 parsed = GSON.fromJson(Files.readString(f), AuraAI3.class);
                if (parsed != null) {
                    if (parsed.mlp == null) parsed.mlp = new DeepMLP();
                    return parsed;
                }
            }
        } catch (Throwable ignored) {}
        return new AuraAI3();
    }

    // ══════════════════════════════════════════════════════════════════
    // Вложенные классы
    // ══════════════════════════════════════════════════════════════════

    public static final class Gesture {
        public float[] dYaw;
        public float[] dPitch;
        public float totalAngle;
        public float sumYaw;
        public float sumPitch;
    }


    /**
     * Сэмпл: полный контекст движения. БЕЗ шума — только твои реальные данные.
     */
    public static final class TrainingSample {
        public float dist;      // расстояние до цели (пиксели)
        public float angle;     // угол к цели (радианы)
        public float speed;     // текущая скорость курсора
        public float prevStepX; // предыдущий шаг X
        public float prevStepY; // предыдущий шаг Y
        public float phase;     // фаза наведения [0=начало, 1=доводка]
        public float stepX;     // РЕАЛЬНЫЙ шаг игрока X
        public float stepY;     // РЕАЛЬНЫЙ шаг игрока Y

        public TrainingSample() {}

        public TrainingSample(float dist, float angle, float speed,
                              float prevStepX, float prevStepY, float phase,
                              float stepX, float stepY) {
            this.dist = dist;
            this.angle = angle;
            this.speed = speed;
            this.prevStepX = prevStepX;
            this.prevStepY = prevStepY;
            this.phase = phase;
            this.stepX = stepX;
            this.stepY = stepY;
        }
    }


    /**
     * MLP: 6 → 32 → 16 → 2. Без noise — только твои реальные паттерны.
     */
    public static final class DeepMLP {
        public float[][] w1 = new float[6][32];   // input → hidden1
        public float[] b1 = new float[32];
        public float[][] w2 = new float[32][16];  // hidden1 → hidden2
        public float[] b2 = new float[16];
        public float[][] w3 = new float[16][2];   // hidden2 → output
        public float[] b3 = new float[2];

        // Нормализация (сохраняется с моделью)
        public float maxDist = 400f;
        public float maxSpeed = 50f;
        public float maxStep = 30f;

        public DeepMLP() {
            heInit();
        }

        public void heInit() {
            Random r = new Random();
            float scale1 = (float) Math.sqrt(2.0 / 6.0);
            for (int i = 0; i < 6; i++)
                for (int j = 0; j < 32; j++)
                    w1[i][j] = (float) r.nextGaussian() * scale1;
            for (int j = 0; j < 32; j++) b1[j] = 0f;

            float scale2 = (float) Math.sqrt(2.0 / 32.0);
            for (int i = 0; i < 32; i++)
                for (int j = 0; j < 16; j++)
                    w2[i][j] = (float) r.nextGaussian() * scale2;
            for (int j = 0; j < 16; j++) b2[j] = 0f;

            float scale3 = (float) Math.sqrt(2.0 / 16.0);
            for (int i = 0; i < 16; i++)
                for (int j = 0; j < 2; j++)
                    w3[i][j] = (float) r.nextGaussian() * scale3;
            b3[0] = 0f; b3[1] = 0f;
        }

        /**
         * Forward — чистый predict без шума. Выдает ТВОИ движения.
         */
        public float[] forward(float dist, float angle, float speed,
                               float prevStepX, float prevStepY, float phase) {
            float[] input = new float[] {
                clamp(dist / maxDist, -1f, 1f),
                angle / (float)Math.PI,
                clamp(speed / maxSpeed, -1f, 1f),
                clamp(prevStepX / maxStep, -1f, 1f),
                clamp(prevStepY / maxStep, -1f, 1f),
                clamp(phase, 0f, 1f)
            };

            // Hidden layer 1: LeakyReLU
            float[] h1 = new float[32];
            for (int j = 0; j < 32; j++) {
                float sum = b1[j];
                for (int k = 0; k < 6; k++) sum += input[k] * w1[k][j];
                h1[j] = sum > 0 ? sum : sum * 0.05f;
            }

            // Hidden layer 2: LeakyReLU
            float[] h2 = new float[16];
            for (int j = 0; j < 16; j++) {
                float sum = b2[j];
                for (int k = 0; k < 32; k++) sum += h1[k] * w2[k][j];
                h2[j] = sum > 0 ? sum : sum * 0.05f;
            }

            // Output: linear
            float[] out = new float[2];
            for (int j = 0; j < 2; j++) {
                float sum = b3[j];
                for (int k = 0; k < 16; k++) sum += h2[k] * w3[k][j];
                out[j] = sum;
            }

            out[0] *= maxStep;
            out[1] *= maxStep;

            if (Float.isNaN(out[0]) || Float.isInfinite(out[0])) out[0] = 0f;
            if (Float.isNaN(out[1]) || Float.isInfinite(out[1])) out[1] = 0f;
            return out;
        }

        public DeepMLP copy() {
            DeepMLP c = new DeepMLP();
            for (int i = 0; i < 6; i++)
                System.arraycopy(w1[i], 0, c.w1[i], 0, 32);
            System.arraycopy(b1, 0, c.b1, 0, 32);
            for (int i = 0; i < 32; i++)
                System.arraycopy(w2[i], 0, c.w2[i], 0, 16);
            System.arraycopy(b2, 0, c.b2, 0, 16);
            for (int i = 0; i < 16; i++)
                System.arraycopy(w3[i], 0, c.w3[i], 0, 2);
            System.arraycopy(b3, 0, c.b3, 0, 2);
            c.maxDist = maxDist;
            c.maxSpeed = maxSpeed;
            c.maxStep = maxStep;
            return c;
        }

        private static float clamp(float v, float min, float max) {
            return Math.max(min, Math.min(max, v));
        }
    }
}
