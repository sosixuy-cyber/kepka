package ru.etc1337.client.modules.impl.combat.aura.ai3;

import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector2f;
import ru.etc1337.client.modules.impl.combat.aura.rotation.api.Rotation;

/**
 * AuraAI3Rotation v10 — Smooth Aim + Neural Pacing.
 *
 * НАПРАВЛЕНИЕ — строго smooth'ом на голову цели (плавная аура).
 *   → Никогда не крутит мимо, гарантированно доводит до головы.
 *
 * ТЕМП (скорость шага) — модулируется нейронкой:
 *   → Сеть обучена на ТВОИХ движениях мышки.
 *   → Магнитуда её предикта = "сейчас рвануть" / "сейчас замедлиться".
 *   → Это придаёт smooth-ауре ТВОЙ почерк (паузы, рывки, замедления),
 *     но направление остаётся честным — на цель.
 *
 * Итог: плавный аим, попадает по голове, но ритм движения — твой.
 */
public final class AuraAI3Rotation extends Rotation {

    // Нормализация входа сети (как в тренере: dxN = dx/gW, dyN = dy/gH).
    private static final float YAW_NORM = 60f;
    private static final float PITCH_NORM = 30f;

    // Границы pace-мультипликатора, на который сеть умножает базовый smooth.
    // 0.55 = минимум (никогда не "зависает"), 1.40 = максимум (рывок).
    private static final float PACE_MIN = 0.55f;
    private static final float PACE_MAX = 1.40f;
    private static final float PACE_GAIN = 0.85f;

    private float curYaw = Float.NaN, curPitch = Float.NaN;
    private boolean firstTick = true;
    private LivingEntity prevTarget;

    @Override
    public void update(LivingEntity target) {
        if (mc.player == null || mc.world == null || target == null) return;

        if (Float.isNaN(curYaw)) {
            curYaw = mc.player.getYaw();
            curPitch = mc.player.getPitch();
        }

        // --- Куда смотреть (точка прицеливания: голова цели) ---
        Vec3d eyePos = mc.player.getEyePos();
        Vec3d targetPos = target.getEyePos();
        Vec3d diff = targetPos.subtract(eyePos);
        double h = Math.hypot(diff.x, diff.z);
        float targetYaw = MathHelper.wrapDegrees(
                (float) Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90f);
        float targetPitch = MathHelper.clamp(
                (float) -Math.toDegrees(Math.atan2(diff.y, h)), -88f, 88f);

        float dYaw = MathHelper.wrapDegrees(targetYaw - curYaw);
        float dPitch = targetPitch - curPitch;
        float dist = (float) Math.sqrt(dYaw * dYaw + dPitch * dPitch);

        // Смена цели → новый "эпизод" для sliding-window сети.
        if (firstTick || prevTarget != target) {
            firstTick = false;
            prevTarget = target;
            AuraAI3.get().resetSequence();
            AuraAI3.get().markEpisodeBoundary();
        }

        // ═══ БАЗОВЫЙ SMOOTH — направление на цель, плавно ═══
        // Чем ближе к цели, тем медленнее (классическая аура-плавность).
        float smoothSpeed;
        if (dist > 40f)      smoothSpeed = 0.55f;
        else if (dist > 15f) smoothSpeed = 0.40f;
        else if (dist > 4f)  smoothSpeed = 0.30f;
        else                 smoothSpeed = 0.22f;

        // ═══ NEURAL PACING — твой "почерк" (только темп, не направление) ═══
        float paceMul = 1f;
        AuraAI3 ai = AuraAI3.get();
        if (ai.trained && dist > 0.3f) {
            float dxN = MathHelper.clamp(dYaw / YAW_NORM, -1f, 1f);
            float dyN = MathHelper.clamp(dPitch / PITCH_NORM, -1f, 1f);
            float[] out = ai.predict(dxN, dyN);

            // Магнитуда предсказания сети (output ∈ [-1, 1]).
            // Большая магнитуда → рывок, малая → пауза/замедление.
            float neuralMag = (float) Math.hypot(out[0], out[1]);

            paceMul = MathHelper.clamp(
                    PACE_MIN + neuralMag * PACE_GAIN,
                    PACE_MIN, PACE_MAX);
        }

        // Финальный шаг: направление от smooth (на цель), темп от сети.
        float stepYaw = dYaw * smoothSpeed * paceMul;
        float stepPitch = dPitch * smoothSpeed * paceMul;

        // Не перелетаем цель.
        if (Math.abs(stepYaw) > Math.abs(dYaw)) stepYaw = dYaw;
        if (Math.abs(stepPitch) > Math.abs(dPitch)) stepPitch = dPitch;

        curYaw += stepYaw;
        curPitch = MathHelper.clamp(curPitch + stepPitch, -88f, 88f);

        applyOutput();
    }

    /** GCD-квантизация и публикация в rotation. */
    private void applyOutput() {
        float gcd = (float) getGcd();
        float outYaw = curYaw, outPitch = curPitch;
        if (gcd > 0f) {
            float baseYaw = mc.player.getYaw();
            float basePitch = mc.player.getPitch();
            outYaw = baseYaw + Math.round((curYaw - baseYaw) / gcd) * gcd;
            outPitch = basePitch + Math.round((curPitch - basePitch) / gcd) * gcd;
        }
        outPitch = MathHelper.clamp(outPitch, -88f, 88f);

        Vector2f c = correctRotation(outYaw, outPitch);
        if (!Float.isNaN(c.x) && !Float.isNaN(c.y)) rotation = c;
    }

    @Override public void attacked() {}

    @Override
    public void reset() {
        curYaw = Float.NaN;
        curPitch = Float.NaN;
        firstTick = true;
        prevTarget = null;
        AuraAI3.get().resetSequence();
        rotation = new Vector2f(0, 0);
    }
}
