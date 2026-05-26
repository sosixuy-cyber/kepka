package ru.etc1337.client.modules.impl.combat.aura.ai3;

import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector2f;
import ru.etc1337.client.modules.impl.combat.aura.rotation.api.Rotation;

/**
 * AuraAI3Rotation v11 — Smooth Anchor + Full Neural Personality.
 *
 * ОПОРА (anchor) = плавная smooth-ротация на цель.
 *   → Гарантированно ведёт к голове противника, никогда не уходит мимо.
 *
 * ХАРАКТЕР = ПОЛНЫЙ выход нейронки (out[0], out[1]) — твои движения.
 *   Раскладываем нейронный шаг на 2 компоненты:
 *     • along  — вдоль направления на цель  → используется как pace-модулятор
 *                                              (рывки/паузы как у тебя)
 *     • perp   — поперёк направления на цель → подмешивается напрямую
 *                                              (твои виляния/мини-отклонения)
 *
 * Итог: плавная аура которая надёжно бьёт по голове,
 *       но движется с ТВОИМ почерком (темп + траектория-виляние).
 */
public final class AuraAI3Rotation extends Rotation {

    // Нормализация входа сети (как в тренере: dxN = dx/gW, dyN = dy/gH).
    private static final float YAW_NORM = 60f;
    private static final float PITCH_NORM = 30f;

    // Скейл выхода сети (output ∈ [-1, 1]) → градусы.
    private static final float STEP_SCALE_YAW = 30f;
    private static final float STEP_SCALE_PITCH = 15f;

    // Pace (вдоль): множитель к smooth-шагу. Никогда не зависает.
    private static final float PACE_MIN = 0.55f;
    private static final float PACE_MAX = 1.40f;
    private static final float PACE_GAIN = 0.85f;

    // Perp (поперёк): сила "виляния" от нейронки. 0 = чистый smooth, 1 = полная.
    private static final float PERP_WEIGHT = 0.35f;
    // Кап на поперёк, чтоб случайный шумный кадр не швырнул голову вбок.
    private static final float PERP_MAX_DEG = 6f;

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

        // --- Точка прицеливания: голова цели ---
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

        // ═══ БАЗОВЫЙ SMOOTH (опора, направление на цель, плавно) ═══
        float smoothSpeed;
        if (dist > 40f)      smoothSpeed = 0.55f;
        else if (dist > 15f) smoothSpeed = 0.40f;
        else if (dist > 4f)  smoothSpeed = 0.30f;
        else                 smoothSpeed = 0.22f;

        float baseStepYaw = dYaw * smoothSpeed;
        float baseStepPitch = dPitch * smoothSpeed;

        float stepYaw = baseStepYaw;
        float stepPitch = baseStepPitch;

        // ═══ NEURAL PERSONALITY (раскладываем по компонентам) ═══
        AuraAI3 ai = AuraAI3.get();
        if (ai.trained && dist > 0.3f) {
            float dxN = MathHelper.clamp(dYaw / YAW_NORM, -1f, 1f);
            float dyN = MathHelper.clamp(dPitch / PITCH_NORM, -1f, 1f);
            float[] out = ai.predict(dxN, dyN);

            // Полный нейронный вектор в degree-space.
            float neuralYaw = out[0] * STEP_SCALE_YAW;
            float neuralPitch = out[1] * STEP_SCALE_PITCH;

            // Единичное направление на цель.
            float distSafe = Math.max(0.0001f, dist);
            float dirYaw = dYaw / distSafe;
            float dirPitch = dPitch / distSafe;

            // Проекция нейронного шага на направление к цели (along).
            float along = neuralYaw * dirYaw + neuralPitch * dirPitch;
            // Перпендикулярная компонента (perp = neural - along * dir).
            float perpYaw = neuralYaw - along * dirYaw;
            float perpPitch = neuralPitch - along * dirPitch;

            // Cap на перпендикуляр.
            float perpLen = (float) Math.hypot(perpYaw, perpPitch);
            if (perpLen > PERP_MAX_DEG) {
                perpYaw = perpYaw / perpLen * PERP_MAX_DEG;
                perpPitch = perpPitch / perpLen * PERP_MAX_DEG;
            }

            // Pace из along-магнитуды (нейронка хочет "много вперёд" → ускоряем).
            float alongMag = Math.min(1f, Math.abs(along) / STEP_SCALE_YAW);
            float paceMul = MathHelper.clamp(
                    PACE_MIN + alongMag * PACE_GAIN,
                    PACE_MIN, PACE_MAX);

            // Финал: smooth * pace (темп) + perp * weight (виляние).
            stepYaw = baseStepYaw * paceMul + perpYaw * PERP_WEIGHT;
            stepPitch = baseStepPitch * paceMul + perpPitch * PERP_WEIGHT;
        }

        // Не перелетаем цель.
        if (Math.abs(stepYaw) > Math.abs(dYaw) && Math.signum(stepYaw) == Math.signum(dYaw))
            stepYaw = dYaw;
        if (Math.abs(stepPitch) > Math.abs(dPitch) && Math.signum(stepPitch) == Math.signum(dPitch))
            stepPitch = dPitch;

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
