package ru.etc1337.client.modules.impl.combat.aura.ai3;

import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector2f;
import ru.etc1337.client.modules.impl.combat.aura.rotation.api.Rotation;

/**
 * AuraAI3Rotation v9 — Pure Neural Output.
 *
 * Нейронка обучена на ТВОИХ реальных движениях мыши.
 * Выход нейронки = шаг поворота. Без миксов, без smooth, без коррекций.
 * Единственные ограничения: GCD (протокол Minecraft) и pitch [-88, 88].
 */
public final class AuraAI3Rotation extends Rotation {

    private static final float YAW_NORM = 60f;
    private static final float PITCH_NORM = 30f;
    private static final float STEP_SCALE_YAW = 30f;
    private static final float STEP_SCALE_PITCH = 15f;

    // Fallback speed when model is not trained (simple fraction of remaining angle)
    private static final float FALLBACK_SPEED = 0.5f;

    private float currentYaw = Float.NaN, currentPitch = Float.NaN;
    private boolean firstTick = true;
    private LivingEntity prevTarget;

    @Override
    public void update(LivingEntity target) {
        if (mc.player == null || mc.world == null || target == null) return;
        if (Float.isNaN(currentYaw)) {
            currentYaw = mc.player.getYaw();
            currentPitch = mc.player.getPitch();
        }

        Vec3d eyePos = mc.player.getEyePos();
        Vec3d targetPos = target.getEyePos();
        Vec3d diff = targetPos.subtract(eyePos);
        double h = Math.hypot(diff.x, diff.z);
        float targetYaw = MathHelper.wrapDegrees((float) Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90f);
        float targetPitch = MathHelper.clamp((float) -Math.toDegrees(Math.atan2(diff.y, h)), -88f, 88f);

        float dYaw = MathHelper.wrapDegrees(targetYaw - currentYaw);
        float dPitch = targetPitch - currentPitch;
        float totalAngle = (float) Math.sqrt(dYaw * dYaw + dPitch * dPitch);

        if (firstTick || prevTarget != target) {
            firstTick = false;
            prevTarget = target;
            AuraAI3.get().resetSequence();
            AuraAI3.get().markEpisodeBoundary();
        }

        float stepYaw, stepPitch;

        AuraAI3 ai = AuraAI3.get();
        if (ai.trained && totalAngle > 0.3f) {
            // ═══ PURE NEURAL ═══
            // Нормализуем входные данные точно как при записи
            float dxN = MathHelper.clamp(dYaw / YAW_NORM, -1f, 1f);
            float dyN = MathHelper.clamp(dPitch / PITCH_NORM, -1f, 1f);

            // Predict через sliding window — выход нейронки = наш шаг
            float[] out = ai.predict(dxN, dyN);
            stepYaw = out[0] * STEP_SCALE_YAW;
            stepPitch = out[1] * STEP_SCALE_PITCH;

            // Единственная защита: не улетаем дальше цели (overshoot clamp)
            // Но НЕ меняем направление и НЕ уменьшаем амплитуду искусственно
            if (Math.abs(stepYaw) > Math.abs(dYaw) * 1.5f && Math.abs(dYaw) > 0.5f) {
                stepYaw = dYaw;
            }
            if (Math.abs(stepPitch) > Math.abs(dPitch) * 1.5f && Math.abs(dPitch) > 0.5f) {
                stepPitch = dPitch;
            }
        } else if (totalAngle > 0.1f) {
            // ═══ FALLBACK (no model) — simple direct step ═══
            stepYaw = dYaw * FALLBACK_SPEED;
            stepPitch = dPitch * FALLBACK_SPEED;
        } else {
            stepYaw = dYaw;
            stepPitch = dPitch;
        }

        currentYaw += stepYaw;
        currentPitch = MathHelper.clamp(currentPitch + stepPitch, -88f, 88f);

        // GCD quantization (Minecraft protocol requirement)
        float gcd = (float) getGcd();
        float outYaw = currentYaw, outPitch = currentPitch;
        if (gcd > 0f) {
            float baseYaw = mc.player.getYaw();
            float basePitch = mc.player.getPitch();
            outYaw = baseYaw + Math.round((currentYaw - baseYaw) / gcd) * gcd;
            outPitch = basePitch + Math.round((currentPitch - basePitch) / gcd) * gcd;
        }
        outPitch = MathHelper.clamp(outPitch, -88f, 88f);

        Vector2f c = correctRotation(outYaw, outPitch);
        if (!Float.isNaN(c.x) && !Float.isNaN(c.y)) rotation = c;
    }

    @Override
    public void attacked() {}

    @Override
    public void reset() {
        currentYaw = Float.NaN;
        currentPitch = Float.NaN;
        firstTick = true;
        prevTarget = null;
        AuraAI3.get().resetSequence();
        rotation = new Vector2f(0, 0);
    }
}
