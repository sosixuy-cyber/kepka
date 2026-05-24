package ru.etc1337.client.modules.impl.combat.aura.ai3;

import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector2f;
import ru.etc1337.client.modules.impl.combat.aura.rotation.api.Rotation;

import java.util.concurrent.ThreadLocalRandom;

/**
 * AuraAI3Rotation v7 — АГРЕССИВНАЯ ротация в стиле оригинального AuraAIRotation,
 * но с твоими движениями из нейронки поверх (small style modulation).
 *
 * Принцип:
 * - Мощная баллистика (как в нативном AuraAIRotation) — быстро доезжает до цели
 * - Нейронка добавляет МИКРО-вариации траектории (твой стиль)
 * - НЕ блокирует движение, всегда быстро бьёт
 */
public final class AuraAI3Rotation extends Rotation {

    private static final int HIST = 8;

    private float smoothYaw = Float.NaN, smoothPitch = Float.NaN;
    private float prevDYaw = 0f, prevDPitch = 0f;
    private float prevDYaw2 = 0f, prevDPitch2 = 0f;
    private boolean firstTick = true;
    private LivingEntity prevTarget;

    private int swingTick = 0;
    private int overshootPhase = 0;
    private float focus = 1.0f;
    private long focusSeed = ThreadLocalRandom.current().nextLong();

    @Override
    public void update(LivingEntity target) {
        if (mc.player == null || mc.world == null || target == null) return;
        if (Float.isNaN(smoothYaw)) {
            smoothYaw = mc.player.getYaw();
            smoothPitch = mc.player.getPitch();
        }

        Vec3d eyePos = mc.player.getEyePos();
        Vec3d targetPos = target.getEyePos();
        Vec3d diff = targetPos.subtract(eyePos);
        double h = Math.hypot(diff.x, diff.z);
        float targetYaw = MathHelper.wrapDegrees((float) Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90f);
        float targetPitch = MathHelper.clamp((float) -Math.toDegrees(Math.atan2(diff.y, h)), -88f, 88f);

        float dYaw = MathHelper.wrapDegrees(targetYaw - smoothYaw);
        float dPitch = targetPitch - smoothPitch;
        float totalAngle = (float) Math.sqrt(dYaw * dYaw + dPitch * dPitch);
        float targetDist = mc.player.distanceTo(target);
        boolean closeRange = targetDist < 1.75f;

        if (firstTick || prevTarget != target) {
            firstTick = false;
            prevTarget = target;
            prevDYaw = 0f; prevDPitch = 0f;
            prevDYaw2 = 0f; prevDPitch2 = 0f;
            swingTick = 0;
            overshootPhase = 0;
        }
        swingTick++;

        // Фокус (как в оригинале)
        long tNow = System.currentTimeMillis();
        double phase = (tNow % 540_000L) / 540_000.0 * Math.PI * 2.0 + focusSeed;
        focus = MathHelper.clamp(0.98f + (float) Math.sin(phase) * 0.05f, 0.90f, 1.15f);

        // ═══ АГРЕССИВНАЯ БАЛЛИСТИКА (как в оригинале AuraAIRotation v5) ═══
        float ballisticT;
        if (totalAngle > 40f)      ballisticT = randomLerp(0.92f, 0.98f);
        else if (totalAngle > 15f) ballisticT = randomLerp(0.80f, 0.94f);
        else if (totalAngle > 4f)  ballisticT = randomLerp(0.68f, 0.82f);
        else                       ballisticT = randomLerp(0.50f, 0.70f);

        // Smoothstep
        ballisticT = ballisticT * ballisticT * (3f - 2f * ballisticT);
        if (swingTick <= 3 && totalAngle > 15f) ballisticT = Math.max(ballisticT, randomLerp(0.85f, 0.95f));
        float distScale = MathHelper.clamp(1.0f - (targetDist - 2f) * 0.04f, 0.85f, 1.0f);
        ballisticT *= distScale;

        // Базовый шаг
        float stepYaw = clampSigned(dYaw, 90f) * ballisticT;
        float stepPitch = clampSigned(dPitch, 45f) * ballisticT;

        // ═══ NEURAL STYLE (МИКРО-модуляция, не замена) ═══
        AuraAI3 ai = AuraAI3.get();
        if (ai.trained && ai.mlp != null && ai.mlp.w1 != null && totalAngle > 0.5f) {
            float yawNorm = MathHelper.clamp(dYaw / 60f, -1f, 1f);
            float pitchNorm = MathHelper.clamp(dPitch / 30f, -1f, 1f);
            float[] neural = ai.predict(yawNorm, pitchNorm);

            // Только направление и относительная сила, НЕ заменяем шаг
            float neuralMag = (float) Math.hypot(neural[0], neural[1]);
            float baseMag = (float) Math.hypot(stepYaw, stepPitch);

            if (neuralMag > 0.3f && baseMag > 0.1f) {
                // Корректируем направление если нейронка знает другое
                if (Math.abs(dYaw) > 1f && Math.signum(neural[0]) != Math.signum(dYaw)) neural[0] = -neural[0];
                if (Math.abs(dPitch) > 1f && Math.signum(neural[1]) != Math.signum(dPitch)) neural[1] = -neural[1];

                // Модулируем: 80% базы (быстрая баллистика) + 20% нейронной модуляции
                float neuralYaw = neural[0] * (baseMag / Math.max(neuralMag, 0.5f));
                float neuralPitch = neural[1] * (baseMag / Math.max(neuralMag, 0.5f));

                stepYaw = stepYaw * 0.80f + neuralYaw * 0.20f;
                stepPitch = stepPitch * 0.80f + neuralPitch * 0.20f;
            }
        }

        // Овершуты (как в оригинале)
        if (overshootPhase > 0) {
            float dampen = randomLerp(0.45f, 0.65f);
            stepYaw *= dampen;
            stepPitch *= dampen;
            overshootPhase--;
        } else if (totalAngle > 8f && totalAngle < 30f
                && Math.abs(stepYaw) > 1.5f
                && ThreadLocalRandom.current().nextFloat() < 0.08f) {
            float ovr = randomLerp(1.04f, 1.12f);
            stepYaw *= ovr;
            stepPitch *= ovr;
            overshootPhase = 1 + ThreadLocalRandom.current().nextInt(2);
        }

        // Малая инерция
        float inertia = closeRange ? 0.05f : 0.08f;
        if (totalAngle < 5f) inertia = Math.min(inertia * 1.1f, 0.15f);
        stepYaw = stepYaw * (1f - inertia) + prevDYaw * inertia;
        stepPitch = stepPitch * (1f - inertia) + prevDPitch * inertia;

        // Шум и фокус
        float commonMult = 1.0f + (float) (ThreadLocalRandom.current().nextGaussian() * 0.03f);
        commonMult *= focus;
        stepYaw *= commonMult;
        stepPitch *= commonMult;

        // Зажимы
        float clampYaw = (closeRange ? 65f : 85f) + (float) (ThreadLocalRandom.current().nextGaussian() * 2f);
        float clampPitch = (closeRange ? 20f : 28f) + (float) (ThreadLocalRandom.current().nextGaussian() * 1f);
        stepYaw = clampSigned(stepYaw, clampYaw);
        stepPitch = clampSigned(stepPitch, clampPitch);

        // Связь осей
        if (Math.abs(stepYaw) < 0.001f && Math.abs(stepPitch) > 0.001f)
            stepYaw += randomLerp(0.08f, 0.30f) * Math.signum(stepPitch);
        if (Math.abs(stepPitch) < 0.001f && Math.abs(stepYaw) > 0.001f)
            stepPitch += randomLerp(0.08f, 0.30f) * Math.signum(stepYaw);

        // Jitter
        float jitterScale = (closeRange ? 0.02f : (totalAngle > 20f ? 0.03f : 0.05f)) * (2.0f - focus);
        stepYaw += (float) (ThreadLocalRandom.current().nextGaussian() * jitterScale);
        stepPitch += (float) (ThreadLocalRandom.current().nextGaussian() * jitterScale * 0.65);

        // Мёртвая зона
        if (Math.abs(stepYaw) < 0.12f && Math.abs(dYaw) < 1.0f) stepYaw = 0f;
        if (Math.abs(stepPitch) < 0.08f && Math.abs(dPitch) < 0.8f) stepPitch = 0f;

        // 3-tap FIR фильтр
        float smoothStepYaw = stepYaw * 0.80f + prevDYaw * 0.14f + prevDYaw2 * 0.06f;
        float smoothStepPitch = stepPitch * 0.80f + prevDPitch * 0.14f + prevDPitch2 * 0.06f;
        if (totalAngle > 20f) {
            smoothStepYaw = stepYaw;
            smoothStepPitch = stepPitch;
        }
        stepYaw = smoothStepYaw;
        stepPitch = smoothStepPitch;

        smoothYaw += stepYaw;
        smoothPitch = MathHelper.clamp(smoothPitch + stepPitch, -88f, 88f);
        prevDYaw2 = prevDYaw; prevDPitch2 = prevDPitch;
        prevDYaw = stepYaw; prevDPitch = stepPitch;

        // GCD квантизация
        float gcd = (float) getGcd();
        float outYaw = smoothYaw, outPitch = smoothPitch;
        if (gcd > 0f) {
            float baseYaw = mc.player.getYaw();
            float basePitch = mc.player.getPitch();
            outYaw = baseYaw + Math.round((smoothYaw - baseYaw) / gcd) * gcd;
            outPitch = basePitch + Math.round((smoothPitch - basePitch) / gcd) * gcd;
        }
        outPitch = MathHelper.clamp(outPitch, -88f, 88f);

        Vector2f c = correctRotation(outYaw, outPitch);
        if (!Float.isNaN(c.x) && !Float.isNaN(c.y)) rotation = c;
    }

    @Override public void attacked() {}
    @Override public void reset() {
        smoothYaw = Float.NaN; smoothPitch = Float.NaN;
        prevDYaw = 0f; prevDPitch = 0f;
        prevDYaw2 = 0f; prevDPitch2 = 0f;
        firstTick = true; prevTarget = null;
        swingTick = 0; overshootPhase = 0;
        rotation = new Vector2f(0, 0);
    }

    private static float clampSigned(float v, float max) {
        return Math.signum(v) * Math.min(Math.abs(v), max);
    }

    private static float randomLerp(float min, float max) {
        return MathHelper.lerp(ThreadLocalRandom.current().nextFloat(), min, max);
    }
}
