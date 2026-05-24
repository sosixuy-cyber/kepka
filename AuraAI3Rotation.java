package ru.etc1337.client.modules.impl.combat.aura.ai3;

import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector2f;
import ru.etc1337.client.modules.impl.combat.aura.rotation.api.Rotation;

/**
 * AuraAI3Rotation v3 — Гибрид: smooth ротация + нейронный стиль.
 *
 * Как работает:
 * 1. Считает smooth-шаг к цели (обычная плавная ротация, гарантирует попадание)
 * 2. Нормализует смещение до цели в yaw_norm/pitch_norm [-1,1]
 * 3. Спрашивает нейронку: "при таком смещении, как бы ТЫ повернул?"
 * 4. Миксует: smoothStep * (1 - neuralMix) + neuralStep * neuralMix
 *
 * Результат: голова доезжает до цели КАК ОБЫЧНО, но характер движения — ТВОЙ.
 */
public final class AuraAI3Rotation extends Rotation {

    // Миксовка: 85% нейронка, 15% smooth (только для гарантии доезда)
    private static final float NEURAL_MIX = 0.85f;

    // Smooth параметры
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

        // Считаем углы до цели
        Vec3d eyePos = mc.player.getEyePos();
        Vec3d targetPos = target.getEyePos();
        Vec3d diff = targetPos.subtract(eyePos);
        double h = Math.hypot(diff.x, diff.z);

        float targetYaw = MathHelper.wrapDegrees((float) Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90f);
        float targetPitch = MathHelper.clamp((float) -Math.toDegrees(Math.atan2(diff.y, h)), -88f, 88f);

        // Дельта до цели
        float dYaw = MathHelper.wrapDegrees(targetYaw - smoothYaw);
        float dPitch = targetPitch - smoothPitch;
        float totalAngle = (float) Math.sqrt(dYaw * dYaw + dPitch * dPitch);

        // Сброс при смене цели
        if (firstTick || prevTarget != target) {
            firstTick = false;
            prevTarget = target;
        }

        // ═══════════════════════════════════════════
        // 1. SMOOTH BASE — обычный плавный поворот
        // ═══════════════════════════════════════════
        float smoothSpeed;
        if (totalAngle > 30f) smoothSpeed = 0.85f;
        else if (totalAngle > 10f) smoothSpeed = 0.55f;
        else if (totalAngle > 3f) smoothSpeed = 0.35f;
        else smoothSpeed = 0.20f;

        float smoothStepYaw = dYaw * smoothSpeed;
        float smoothStepPitch = dPitch * smoothSpeed;

        // ═══════════════════════════════════════════
        // 2. NEURAL STYLE — нейронка говорит как ТЫ бы повернул
        // ═══════════════════════════════════════════
        float finalStepYaw = smoothStepYaw;
        float finalStepPitch = smoothStepPitch;

        AuraAI3 ai = AuraAI3.get();
        if (ai.trained && ai.mlp != null && totalAngle > 0.5f) {
            // Нормализуем смещение до цели в [-1, 1]
            // yaw_norm: положительный = цель справа, отрицательный = слева
            // pitch_norm: положительный = цель ниже, отрицательный = выше
            float yawNorm = MathHelper.clamp(dYaw / 180f, -1f, 1f);
            float pitchNorm = MathHelper.clamp(dPitch / 90f, -1f, 1f);

            // Спрашиваем нейронку
            float[] neuralStep = ai.predict(yawNorm, pitchNorm);

            // neuralStep — это move_dyaw/move_dpitch из твоих записей
            // Масштабируем обратно в градусы
            float neuralYaw = neuralStep[0];
            float neuralPitch = neuralStep[1];

            // Зажимаем чтобы не улетело
            neuralYaw = clampSigned(neuralYaw, Math.abs(dYaw) * 1.2f);
            neuralPitch = clampSigned(neuralPitch, Math.abs(dPitch) * 1.2f);

            // Если нейронка пытается уйти В ДРУГУЮ сторону от цели — корректируем знак
            if (Math.abs(dYaw) > 1f && Math.signum(neuralYaw) != Math.signum(dYaw))
                neuralYaw = -neuralYaw;
            if (Math.abs(dPitch) > 1f && Math.signum(neuralPitch) != Math.signum(dPitch))
                neuralPitch = -neuralPitch;

            // ═══════════════════════════════════════════
            // 3. МИКС: smooth + neural
            // ═══════════════════════════════════════════
            float mix = NEURAL_MIX;
            // При очень маленьком отклонении — чуть больше smooth для финальной доводки
            if (totalAngle < 2f) mix *= 0.6f;
            // При среднем и большом — почти полностью нейронка
            else if (totalAngle > 10f) mix = Math.min(mix * 1.1f, 0.95f);

            finalStepYaw = smoothStepYaw * (1f - mix) + neuralYaw * mix;
            finalStepPitch = smoothStepPitch * (1f - mix) + neuralPitch * mix;
        }

        // Применяем
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

    @Override
    public void attacked() {
        // Ничего — стиль удара уже в нейронке
    }

    @Override
    public void reset() {
        smoothYaw = Float.NaN;
        smoothPitch = Float.NaN;
        firstTick = true;
        prevTarget = null;
        rotation = new Vector2f(0, 0);
    }

    private static float clampSigned(float v, float max) {
        return Math.signum(v) * Math.min(Math.abs(v), max);
    }
}
