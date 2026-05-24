package ru.etc1337.client.modules.impl.combat.aura.ai3;

import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector2f;
import ru.etc1337.client.modules.impl.combat.aura.rotation.api.Rotation;

/**
 * AuraAI3Rotation v8 — Sliding Window predict + быстрый smooth fallback.
 *
 * Логика:
 * 1. БЫСТРЫЙ smooth (как в оригинале) — гарантирует что голова доедет.
 * 2. Если сеть обучена — она даёт sliding window predict, добавляем её "характер" (40%).
 * 3. Anti-stall: если нейронка слабая → чистый smooth.
 */
public final class AuraAI3Rotation extends Rotation {

    // Нормализация: dxN = dYaw / YAW_NORM, dyN = dPitch / PITCH_NORM
    private static final float YAW_NORM = 60f;
    private static final float PITCH_NORM = 30f;
    // На предикте умножаем нормализованный output на эти scale (в "model units" → градусы)
    private static final float STEP_SCALE_YAW = 30f;
    private static final float STEP_SCALE_PITCH = 15f;

    private float smoothYaw = Float.NaN, smoothPitch = Float.NaN;
    private boolean firstTick = true;
    private LivingEntity prevTarget;

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

        if (firstTick || prevTarget != target) {
            firstTick = false;
            prevTarget = target;
            AuraAI3.get().resetSequence();
            AuraAI3.get().markEpisodeBoundary();
        }

        // ═══ БЫСТРЫЙ SMOOTH ═══
        float smoothSpeed;
        if (totalAngle > 40f)      smoothSpeed = 0.92f;
        else if (totalAngle > 15f) smoothSpeed = 0.80f;
        else if (totalAngle > 4f)  smoothSpeed = 0.65f;
        else                       smoothSpeed = 0.45f;

        float smoothStepYaw = dYaw * smoothSpeed;
        float smoothStepPitch = dPitch * smoothSpeed;

        float finalStepYaw = smoothStepYaw;
        float finalStepPitch = smoothStepPitch;

        // ═══ NEURAL OVERLAY (sliding window) ═══
        AuraAI3 ai = AuraAI3.get();
        if (ai.trained && totalAngle > 0.5f) {
            float dxN = MathHelper.clamp(dYaw / YAW_NORM, -1f, 1f);
            float dyN = MathHelper.clamp(dPitch / PITCH_NORM, -1f, 1f);

            float[] out = ai.predict(dxN, dyN);
            float neuralYaw = out[0] * STEP_SCALE_YAW;
            float neuralPitch = out[1] * STEP_SCALE_PITCH;

            float neuralMag = (float) Math.hypot(neuralYaw, neuralPitch);
            if (neuralMag > 0.3f) {
                // Корректируем направление если сеть пошла не туда
                if (Math.abs(dYaw) > 1f && Math.signum(neuralYaw) != Math.signum(dYaw))
                    neuralYaw = -neuralYaw;
                if (Math.abs(dPitch) > 1f && Math.signum(neuralPitch) != Math.signum(dPitch))
                    neuralPitch = -neuralPitch;

                // Не даём улететь сильно за цель
                neuralYaw = clampSigned(neuralYaw, Math.max(Math.abs(dYaw) * 1.3f, 2f));
                neuralPitch = clampSigned(neuralPitch, Math.max(Math.abs(dPitch) * 1.3f, 1.5f));

                // 60% smooth (база) + 40% neural (характер)
                float mix = 0.40f;
                if (totalAngle < 2f) mix = 0.20f;
                else if (totalAngle > 25f) mix = 0.55f;

                finalStepYaw = smoothStepYaw * (1f - mix) + neuralYaw * mix;
                finalStepPitch = smoothStepPitch * (1f - mix) + neuralPitch * mix;

                // Safety: если итог слишком слабый — берём smooth (не залипаем)
                float finalMag = (float) Math.hypot(finalStepYaw, finalStepPitch);
                float smoothMag = (float) Math.hypot(smoothStepYaw, smoothStepPitch);
                if (finalMag < smoothMag * 0.4f && totalAngle > 1f) {
                    finalStepYaw = smoothStepYaw;
                    finalStepPitch = smoothStepPitch;
                }
            }
        }

        smoothYaw += finalStepYaw;
        smoothPitch = MathHelper.clamp(smoothPitch + finalStepPitch, -88f, 88f);

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
        firstTick = true; prevTarget = null;
        AuraAI3.get().resetSequence();
        rotation = new Vector2f(0, 0);
    }

    private static float clampSigned(float v, float max) {
        return Math.signum(v) * Math.min(Math.abs(v), max);
    }
}
