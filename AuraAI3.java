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
 * AuraAI3 v3 — Формат как в нормальном чите.
 * Вход: yaw_norm, pitch_norm (нормализованное смещение до цели [-1,1])
 * Выход: move_dyaw, move_dpitch (реальный шаг поворота игрока)
 * Сеть: 2 → 32 → 16 → 2, Adam, 5000 эпох, без фейков.
 */
public final class AuraAI3 {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static AuraAI3 INSTANCE;
    public static volatile Consumer<String> chatSink = System.out::println;

    public final List<Gesture> gestures = new ArrayList<>();
    public final transient List<TrainingSample> samples = new ArrayList<>();
    public DeepMLP mlp = new DeepMLP();
    public boolean trained = false;
    public transient float lastLoss = Float.MAX_VALUE;

    public static synchronized AuraAI3 get() {
        if (INSTANCE == null) INSTANCE = load();
        return INSTANCE;
    }

    /** Записывает сэмпл: нормализованное смещение → реальный шаг */
    public synchronized void addSample(float yawNorm, float pitchNorm, float moveDyaw, float moveDpitch) {
        samples.add(new TrainingSample(yawNorm, pitchNorm, moveDyaw, moveDpitch));
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

    public synchronized void trainModel(int epochs, Consumer<Float> progressCallback) {
        if (samples.size() < 10) {
            chatSink.accept("§b[AuraAI3] §cМинимум 10 сэмплов! Сейчас: " + samples.size());
            progressCallback.accept(1.0f);
            return;
        }
        new Thread(() -> {
            try { trainInternal(epochs, progressCallback); }
            catch (Throwable t) { chatSink.accept("§c[AuraAI3] Ошибка: " + t.getMessage()); progressCallback.accept(1.0f); }
        }, "AuraAI3-Train").start();
    }

    private void trainInternal(int epochs, Consumer<Float> progressCallback) {
        int n = samples.size();
        List<TrainingSample> data = new ArrayList<>(samples);

        float maxMove = 1f;
        for (TrainingSample s : data)
            maxMove = Math.max(maxMove, Math.max(Math.abs(s.moveDyaw), Math.abs(s.moveDpitch)));

        float lr = 0.003f;
        float beta1 = 0.9f, beta2 = 0.999f, eps = 1e-8f;
        DeepMLP net = new DeepMLP();
        net.heInit();

        float[][] mW1 = new float[2][32], vW1 = new float[2][32];
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
                float[][] gW1 = new float[2][32]; float[] gB1n = new float[32];
                float[][] gW2 = new float[32][16]; float[] gB2n = new float[16];
                float[][] gW3 = new float[16][2]; float[] gB3n = new float[2];

                for (int i = batch; i < end; i++) {
                    TrainingSample s = data.get(i);
                    float[] input = { s.yawNorm, s.pitchNorm };
                    float tgtX = s.moveDyaw / maxMove;
                    float tgtY = s.moveDpitch / maxMove;

                    float[] h1r = new float[32], h1 = new float[32];
                    for (int j = 0; j < 32; j++) {
                        float sum = net.b1[j]; for (int k = 0; k < 2; k++) sum += input[k] * net.w1[k][j];
                        h1r[j] = sum; h1[j] = sum > 0 ? sum : sum * 0.05f;
                    }
                    float[] h2r = new float[16], h2 = new float[16];
                    for (int j = 0; j < 16; j++) {
                        float sum = net.b2[j]; for (int k = 0; k < 32; k++) sum += h1[k] * net.w2[k][j];
                        h2r[j] = sum; h2[j] = sum > 0 ? sum : sum * 0.05f;
                    }
                    float[] out = new float[2];
                    for (int j = 0; j < 2; j++) {
                        float sum = net.b3[j]; for (int k = 0; k < 16; k++) sum += h2[k] * net.w3[k][j];
                        out[j] = sum;
                    }

                    float errX = out[0] - tgtX, errY = out[1] - tgtY;
                    epochLoss += errX * errX + errY * errY;
                    float[] dOut = { errX * 2f / batchSize, errY * 2f / batchSize };

                    float[] dH2 = new float[16];
                    for (int j = 0; j < 2; j++) { for (int k = 0; k < 16; k++) { gW3[k][j] += dOut[j] * h2[k]; dH2[k] += dOut[j] * net.w3[k][j]; } gB3n[j] += dOut[j]; }
                    for (int j = 0; j < 16; j++) dH2[j] *= (h2r[j] > 0 ? 1f : 0.05f);

                    float[] dH1 = new float[32];
                    for (int j = 0; j < 16; j++) { for (int k = 0; k < 32; k++) { gW2[k][j] += dH2[j] * h1[k]; dH1[k] += dH2[j] * net.w2[k][j]; } gB2n[j] += dH2[j]; }
                    for (int j = 0; j < 32; j++) dH1[j] *= (h1r[j] > 0 ? 1f : 0.05f);

                    for (int j = 0; j < 32; j++) { for (int k = 0; k < 2; k++) gW1[k][j] += dH1[j] * input[k]; gB1n[j] += dH1[j]; }
                }

                float bc1 = 1f - (float)Math.pow(beta1, t), bc2 = 1f - (float)Math.pow(beta2, t);
                for (int k = 0; k < 2; k++) for (int j = 0; j < 32; j++) { mW1[k][j] = beta1*mW1[k][j]+(1-beta1)*gW1[k][j]; vW1[k][j] = beta2*vW1[k][j]+(1-beta2)*gW1[k][j]*gW1[k][j]; net.w1[k][j] -= lr*(mW1[k][j]/bc1)/((float)Math.sqrt(vW1[k][j]/bc2)+eps); }
                for (int j = 0; j < 32; j++) { mB1[j] = beta1*mB1[j]+(1-beta1)*gB1n[j]; vB1[j] = beta2*vB1[j]+(1-beta2)*gB1n[j]*gB1n[j]; net.b1[j] -= lr*(mB1[j]/bc1)/((float)Math.sqrt(vB1[j]/bc2)+eps); }
                for (int k = 0; k < 32; k++) for (int j = 0; j < 16; j++) { mW2[k][j] = beta1*mW2[k][j]+(1-beta1)*gW2[k][j]; vW2[k][j] = beta2*vW2[k][j]+(1-beta2)*gW2[k][j]*gW2[k][j]; net.w2[k][j] -= lr*(mW2[k][j]/bc1)/((float)Math.sqrt(vW2[k][j]/bc2)+eps); }
                for (int j = 0; j < 16; j++) { mB2[j] = beta1*mB2[j]+(1-beta1)*gB2n[j]; vB2[j] = beta2*vB2[j]+(1-beta2)*gB2n[j]*gB2n[j]; net.b2[j] -= lr*(mB2[j]/bc1)/((float)Math.sqrt(vB2[j]/bc2)+eps); }
                for (int k = 0; k < 16; k++) for (int j = 0; j < 2; j++) { mW3[k][j] = beta1*mW3[k][j]+(1-beta1)*gW3[k][j]; vW3[k][j] = beta2*vW3[k][j]+(1-beta2)*gW3[k][j]*gW3[k][j]; net.w3[k][j] -= lr*(mW3[k][j]/bc1)/((float)Math.sqrt(vW3[k][j]/bc2)+eps); }
                for (int j = 0; j < 2; j++) { mB3[j] = beta1*mB3[j]+(1-beta1)*gB3n[j]; vB3[j] = beta2*vB3[j]+(1-beta2)*gB3n[j]*gB3n[j]; net.b3[j] -= lr*(mB3[j]/bc1)/((float)Math.sqrt(vB3[j]/bc2)+eps); }
            }

            epochLoss /= n;
            if (epochLoss < bestLoss) { bestLoss = epochLoss; bestNet = net.copy(); patience = 0; }
            else { patience++; if (patience > 200) { lr *= 0.5f; patience = 0; if (lr < 1e-5f) break; } }
            if (epoch % 10 == 0) progressCallback.accept((float) epoch / epochs);
            try { Thread.sleep(1); } catch (InterruptedException ignored) { break; }
        }

        this.mlp = bestNet != null ? bestNet : net;
        this.mlp.maxMove = maxMove;
        this.lastLoss = bestLoss;
        this.trained = true;
        save();
        chatSink.accept(String.format("§b[AuraAI3] §a Обучение готово! Loss=%.5f, samples=%d", bestLoss, n));
        progressCallback.accept(1.0f);
    }

    /** Predict: yaw_norm, pitch_norm → move_dyaw, move_dpitch */
    public float[] predict(float yawNorm, float pitchNorm) {
        if (!trained || mlp == null) return new float[]{0f, 0f};
        return mlp.forward(yawNorm, pitchNorm);
    }

    public synchronized void clear() {
        gestures.clear(); samples.clear(); mlp = new DeepMLP(); trained = false; lastLoss = Float.MAX_VALUE; save();
        chatSink.accept("§b[AuraAI3] §cБаза очищена.");
    }

    public static Path file() { Path d = MinecraftClient.getInstance().runDirectory.toPath().resolve("dreamcore"); try { Files.createDirectories(d); } catch (IOException ignored) {} return d.resolve("aura_ai3.json"); }
    public synchronized void save() { try { Files.writeString(file(), GSON.toJson(this)); } catch (Throwable t) { chatSink.accept("§c[AuraAI3] Ошибка: " + t.getMessage()); } }
    private static AuraAI3 load() { try { Path f = file(); if (Files.exists(f)) { AuraAI3 p = GSON.fromJson(Files.readString(f), AuraAI3.class); if (p != null) { if (p.mlp == null) p.mlp = new DeepMLP(); return p; } } } catch (Throwable ignored) {} return new AuraAI3(); }

    public static final class Gesture { public float[] dYaw, dPitch; public float totalAngle, sumYaw, sumPitch; }

    public static final class TrainingSample {
        public float yawNorm, pitchNorm;   // вход: нормализованное смещение [-1,1]
        public float moveDyaw, moveDpitch; // выход: реальный шаг поворота
        public TrainingSample() {}
        public TrainingSample(float yawNorm, float pitchNorm, float moveDyaw, float moveDpitch) {
            this.yawNorm = yawNorm; this.pitchNorm = pitchNorm; this.moveDyaw = moveDyaw; this.moveDpitch = moveDpitch;
        }
    }

    public static final class DeepMLP {
        public float[][] w1 = new float[2][32]; public float[] b1 = new float[32];
        public float[][] w2 = new float[32][16]; public float[] b2 = new float[16];
        public float[][] w3 = new float[16][2]; public float[] b3 = new float[2];
        public float maxMove = 30f;

        public DeepMLP() { heInit(); }
        public void heInit() {
            Random r = new Random();
            float s1 = (float)Math.sqrt(2.0/2.0); for (int i=0;i<2;i++) for (int j=0;j<32;j++) w1[i][j]=(float)r.nextGaussian()*s1; for (int j=0;j<32;j++) b1[j]=0f;
            float s2 = (float)Math.sqrt(2.0/32.0); for (int i=0;i<32;i++) for (int j=0;j<16;j++) w2[i][j]=(float)r.nextGaussian()*s2; for (int j=0;j<16;j++) b2[j]=0f;
            float s3 = (float)Math.sqrt(2.0/16.0); for (int i=0;i<16;i++) for (int j=0;j<2;j++) w3[i][j]=(float)r.nextGaussian()*s3; b3[0]=0f; b3[1]=0f;
        }

        public float[] forward(float yawNorm, float pitchNorm) {
            float[] in = { Math.max(-1f, Math.min(1f, yawNorm)), Math.max(-1f, Math.min(1f, pitchNorm)) };
            float[] h1 = new float[32];
            for (int j=0;j<32;j++) { float s=b1[j]; for (int k=0;k<2;k++) s+=in[k]*w1[k][j]; h1[j]=s>0?s:s*0.05f; }
            float[] h2 = new float[16];
            for (int j=0;j<16;j++) { float s=b2[j]; for (int k=0;k<32;k++) s+=h1[k]*w2[k][j]; h2[j]=s>0?s:s*0.05f; }
            float[] out = new float[2];
            for (int j=0;j<2;j++) { float s=b3[j]; for (int k=0;k<16;k++) s+=h2[k]*w3[k][j]; out[j]=s; }
            out[0] *= maxMove; out[1] *= maxMove;
            if (Float.isNaN(out[0])||Float.isInfinite(out[0])) out[0]=0f;
            if (Float.isNaN(out[1])||Float.isInfinite(out[1])) out[1]=0f;
            return out;
        }

        public DeepMLP copy() {
            DeepMLP c = new DeepMLP();
            for (int i=0;i<2;i++) System.arraycopy(w1[i],0,c.w1[i],0,32);
            System.arraycopy(b1,0,c.b1,0,32);
            for (int i=0;i<32;i++) System.arraycopy(w2[i],0,c.w2[i],0,16);
            System.arraycopy(b2,0,c.b2,0,16);
            for (int i=0;i<16;i++) System.arraycopy(w3[i],0,c.w3[i],0,2);
            System.arraycopy(b3,0,c.b3,0,2);
            c.maxMove=maxMove; return c;
        }
    }
}
