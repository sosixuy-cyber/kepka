package ru.etc1337.client.modules.impl.combat.aura.ai3;

import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector2f;
import ru.etc1337.client.modules.impl.combat.aura.rotation.api.Rotation;

/**
 * AuraAI3Rotation v5 — БЫСТРЫЙ smooth + neural style overlay.
 *
 * - Базовая ротация ОЧЕНЬ быстрая (как раньше было), не тормозит
 * - Нейронка добавляет ТВОЙ характер поверх
 * - Safety: если сеть зависает, smooth дотягивает
 */
public final class AuraAI3Rotation extends Rotation {

    private float smoothYaw = Float.NaN, smoothPitch = Float.NaN;
    private boolean firstTick = true;
    private LivingEntity prevTarget;

    @Override
    public void update(LivingEntity target) {
        if (mc.player == null || mc.world == null || target == null) return;
        if (Float.isNaN(smoothYaw)) { smoothYaw = mc.player.getYaw(); smoothPitch = mc.player.getPitch(); }

        Vec3d eyePos = mc.player.getEyePos();
        Vec3d targetPos = target.getEyePos();
        Vec3d diff = targetPos.subtract(eyePos);
        double h = Math.hypot(diff.x, diff.z);
        float targetYaw = MathHelper.wrapDegrees((float) Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90f);
        float targetPitch = MathHelper.clamp((float) -Math.toDegrees(Math.atan2(diff.y, h)), -88f, 88f);

        float dYaw = MathHelper.wrapDegrees(targetYaw - smoothYaw);
        float dPitch = targetPitch - smoothPitch;
        float totalAngle = (float) Math.sqrt(dYaw * dYaw + dPitch * dPitch);

        if (firstTick || prevTarget != target) { firstTick = false; prevTarget = target; }

        // ═══ БЫСТРЫЙ SMOOTH (как было) ═══
        float smoothSpeed;
        if (totalAngle > 40f)      smoothSpeed = 0.95f;
        else if (totalAngle > 15f) smoothSpeed = 0.85f;
        else if (totalAngle > 4f)  smoothSpeed = 0.70f;
        else                       smoothSpeed = 0.50f;

        float smoothStepYaw = dYaw * smoothSpeed;
        float smoothStepPitch = dPitch * smoothSpeed;

        // ═══ NEURAL STYLE OVERLAY ═══
        float finalStepYaw = smoothStepYaw;
        float finalStepPitch = smoothStepPitch;

        AuraAI3 ai = AuraAI3.get();
        if (ai.trained && ai.mlp != null && totalAngle > 0.5f) {
            float yawNorm = MathHelper.clamp(dYaw / 60f, -1f, 1f);
            float pitchNorm = MathHelper.clamp(dPitch / 30f, -1f, 1f);

            float[] neuralStep = ai.predict(yawNorm, pitchNorm);
            float neuralYaw = neuralStep[0];
            float neuralPitch = neuralStep[1];

            // Если сеть зависла (выдает почти 0) — игнорируем её
            float neuralMag = (float) Math.hypot(neuralYaw, neuralPitch);
            if (neuralMag < 0.3f && totalAngle > 1f) {
                // Нейронка не помогает, чистый smooth
                finalStepYaw = smoothStepYaw;
                finalStepPitch = smoothStepPitch;
            } else {
                // Корректируем направление если сеть пошла не туда
                if (Math.abs(dYaw) > 1f && Math.signum(neuralYaw) != Math.signum(dYaw)) neuralYaw = -neuralYaw;
                if (Math.abs(dPitch) > 1f && Math.signum(neuralPitch) != Math.signum(dPitch)) neuralPitch = -neuralPitch;

                // Зажимаем чтобы не улетело за цель сильно
                neuralYaw = clampSigned(neuralYaw, Math.max(Math.abs(dYaw) * 1.3f, 2f));
                neuralPitch = clampSigned(neuralPitch, Math.max(Math.abs(dPitch) * 1.3f, 1.5f));

                // Микс: 70% neural характер + 30% smooth для точности
                float mix = 0.70f;
                if (totalAngle < 2f) mix = 0.4f; // близко — больше smooth
                if (totalAngle > 25f) mix = 0.85f; // далеко — больше характер

                finalStepYaw = smoothStepYaw * (1f - mix) + neuralYaw * mix;
                finalStepPitch = smoothStepPitch * (1f - mix) + neuralPitch * mix;

                // SAFETY: если итоговый шаг меньше 30% от smooth — берём smooth (не залипнет)
                float finalMag = (float) Math.hypot(finalStepYaw, finalStepPitch);
                float smoothMag = (float) Math.hypot(smoothStepYaw, smoothStepPitch);
                if (finalMag < smoothMag * 0.3f && totalAngle > 1f) {
                    finalStepYaw = smoothStepYaw;
                    finalStepPitch = smoothStepPitch;
                }
            }
        }

        smoothYaw += finalStepYaw;
        smoothPitch = MathHelper.clamp(smoothPitch + finalStepPitch, -88f, 88f);

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
        rotation = new Vector2f(0, 0);
    }

    private static float clampSigned(float v, float max) { return Math.signum(v) * Math.min(Math.abs(v), max); }
}
