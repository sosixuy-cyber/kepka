package ru.etc1337.client.modules.impl.visuals;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import ru.etc1337.Client;
import ru.etc1337.api.TempColor;
import ru.etc1337.api.events.impl.game.EventUpdate;
import ru.etc1337.api.events.impl.render.EventRender3D;
import ru.etc1337.api.settings.impl.BooleanSetting;
import ru.etc1337.api.settings.impl.SliderSetting;
import ru.etc1337.api.util.color.ColorUtility;
import ru.etc1337.api.util.render.RenderUtility;
import ru.etc1337.client.modules.Module;
import ru.etc1337.client.modules.api.ModuleCategory;
import ru.etc1337.client.modules.api.ModuleInfo;
import ru.etc1337.client.modules.impl.combat.Aura;

/**
 * Target ESP с двумя режимами:
 *   • CRYSTALS (по умолчанию) — орбитальные октаэдры на 3-х уровнях.
 *   • SKAT     — стая скатов летает по орбите вокруг таргета,
 *                машут крыльями, ориентируются по касательной.
 */
@ModuleInfo(name = "Target ESP", category = ModuleCategory.VISUALS)
public class TargetESP extends Module {

    private final BooleanSetting skatMode    = new BooleanSetting("Skat Mode",   this);
    private final SliderSetting crystalCount = new SliderSetting("Crystals",     this, 5f,  3f,  8f,  1f);
    private final SliderSetting crystalSize  = new SliderSetting("Crystal Size", this, 0.18f, 0.10f, 0.32f, 0.01f);
    private final SliderSetting orbitSpeed   = new SliderSetting("Orbit Speed",  this, 1.0f, 0.3f, 3.0f, 0.05f);
    private final SliderSetting spinSpeed    = new SliderSetting("Spin Speed",   this, 1.5f, 0.3f, 4.0f, 0.05f);
    private final SliderSetting radiusMul    = new SliderSetting("Orbit Radius", this, 0.95f, 0.55f, 1.8f, 0.05f);
    private final SliderSetting lineWidth    = new SliderSetting("Line Width",   this, 1.4f, 0.5f, 3.5f, 0.1f);
    private final BooleanSetting groundRing  = new BooleanSetting("Ground Ring", this);
    private final BooleanSetting trail       = new BooleanSetting("Trail",       this);

    private LivingEntity lastTarget;
    private float alpha;

    @EventHandler
    public void onUpdate(EventUpdate eventUpdate) {
        Aura aura = Client.getInstance().getModuleManager().get(Aura.class);
        LivingEntity activeTarget = aura != null ? aura.getTarget() : null;

        if (activeTarget != null && activeTarget.isAlive()) {
            lastTarget = activeTarget;
            alpha = Math.min(1.0f, alpha + 0.10f);
        } else {
            alpha = Math.max(0.0f, alpha - 0.07f);
            if (alpha <= 0.0f) lastTarget = null;
        }
    }

    @EventHandler
    public void onRender3D(EventRender3D eventRender3D) {
        if (mc.player == null || mc.world == null || lastTarget == null || alpha <= 0.0f) return;

        float pt = eventRender3D.getPartialTicks();
        Box box = getInterpolatedBox(lastTarget, pt);

        // Базовая точка под ногами таргета
        Vec3d feet = new Vec3d(box.getCenter().x, box.minY, box.getCenter().z);
        double height = Math.max(0.6, box.getLengthY());
        double baseR = Math.max(box.getLengthX(), box.getLengthZ()) * 0.78 * radiusMul.getValue();

        long time = System.currentTimeMillis();
        float pulse = (float) (0.5 + 0.5 * Math.sin((time % 1100L) / 1100.0 * Math.PI * 2));
        float breathe = alpha * (0.72f + 0.28f * pulse);

        int clientCol = TempColor.getClientColor().getRGB();
        int hurtAccent = 0xFFC85878;
        float hurtMix = MathHelper.clamp(lastTarget.hurtTime / 10f, 0f, 1f);

        int colMain  = ColorUtility.multAlpha(blend(clientCol, hurtAccent, hurtMix), breathe);
        int colSoft  = ColorUtility.multAlpha(blend(clientCol, hurtAccent, hurtMix), breathe * 0.45f);
        int colGhost = ColorUtility.multAlpha(blend(clientCol, hurtAccent, hurtMix), breathe * 0.18f);
        int colWhite = ColorUtility.multAlpha(0xFFFFFFFF, breathe * 0.78f);

        float width = lineWidth.getValue();
        float thin  = Math.max(0.55f, width - 0.5f);

        if (skatMode.isEnabled()) {
            renderSkats(feet, height, baseR, time, colMain, colSoft, colGhost, colWhite, width, thin);
        } else {
            renderCrystals(feet, height, baseR, time, colMain, colSoft, colGhost, colWhite, width, thin);
        }

        // Опциональное наземное кольцо
        if (groundRing.isEnabled()) {
            Vec3d ringCenter = feet.add(0, 0.03, 0);
            drawRing(ringCenter, baseR * 1.04, 64, 0,            colGhost, width + 2.0f);
            drawRing(ringCenter, baseR,        64, 0,            colMain,  width);
            double spin = (time % 2400L) / 2400.0 * Math.PI * 2.0;
            drawArc(ringCenter.add(0, 0.04, 0), baseR * 0.86, 30, spin,             Math.PI * 0.7, colWhite, thin);
            drawArc(ringCenter.add(0, 0.06, 0), baseR * 0.86, 30, spin + Math.PI,   Math.PI * 0.7, colWhite, thin);
        }
    }

    // ── CRYSTALS MODE ────────────────────────────────────────────────────────

    private void renderCrystals(Vec3d feet, double height, double baseR, long time,
                                int colMain, int colSoft, int colGhost, int colWhite,
                                float width, float thin) {
        double[] yFrac = { 0.92, 0.58, 0.18 };
        int     count  = (int) crystalCount.getValue();
        double  size   = crystalSize.getValue();

        for (int level = 0; level < 3; level++) {
            double dir = (level % 2 == 0) ? 1.0 : -1.0;
            double orbitT = dir * (time % 6000L) / 6000.0 * Math.PI * 2 * orbitSpeed.getValue();

            for (int i = 0; i < count; i++) {
                double phase     = i * (Math.PI * 2 / count) + level * 0.7;
                double r         = baseR + Math.sin(time / 1500.0 + level + i) * 0.08;
                double orbitA    = orbitT + phase;
                double cx        = feet.x + Math.cos(orbitA) * r;
                double cz        = feet.z + Math.sin(orbitA) * r;
                double yBob      = Math.sin(time / 900.0 + i * 1.3 + level * 1.7) * 0.07;
                double cy        = feet.y + height * yFrac[level] + yBob;

                double crystalSpin = (time % 4000L) / 4000.0 * Math.PI * 2 * spinSpeed.getValue()
                        + i * 0.4 + level * 0.9;

                Vec3d center = new Vec3d(cx, cy, cz);
                drawCrystal(center, size, crystalSpin, colMain, colSoft, colWhite, width, thin);

                if (trail.isEnabled()) {
                    for (int t = 1; t <= 2; t++) {
                        double trailA = orbitA - dir * t * 0.18;
                        double tx = feet.x + Math.cos(trailA) * r;
                        double tz = feet.z + Math.sin(trailA) * r;
                        double ty = feet.y + height * yFrac[level] + yBob * 0.6;
                        float k = 1f - (t / 3f);
                        int dim = ColorUtility.multAlpha(colGhost, k);
                        drawCrystal(new Vec3d(tx, ty, tz),
                                size * (0.78f - t * 0.18f),
                                crystalSpin - t * 0.5,
                                dim, dim, dim, thin, Math.max(0.45f, thin - 0.2f));
                    }
                }
            }
        }
    }

    /**
     * Октаэдр (3D-ромб): 4 верхних ребра + 4 нижних + 4 экватор + 2 диагонали.
     */
    private void drawCrystal(Vec3d center, double size, double yawSpin,
                             int colTop, int colMid, int colEdge,
                             float width, float thin) {
        Vec3d top    = center.add(0,  size, 0);
        Vec3d bottom = center.add(0, -size, 0);

        Vec3d[] mids = new Vec3d[4];
        for (int i = 0; i < 4; i++) {
            double a = yawSpin + i * (Math.PI / 2);
            mids[i] = center.add(Math.cos(a) * size, 0, Math.sin(a) * size);
        }

        for (int i = 0; i < 4; i++)
            RenderUtility.drawLine(top, mids[i], colTop, width, true);
        for (int i = 0; i < 4; i++)
            RenderUtility.drawLine(bottom, mids[i], colMid, thin, true);
        for (int i = 0; i < 4; i++)
            RenderUtility.drawLine(mids[i], mids[(i + 1) % 4], colMid, thin, true);

        RenderUtility.drawLine(mids[0], mids[2], colEdge, Math.max(0.4f, thin - 0.3f), true);
        RenderUtility.drawLine(mids[1], mids[3], colEdge, Math.max(0.4f, thin - 0.3f), true);
    }

    // ── SKAT MODE ────────────────────────────────────────────────────────────

    /**
     * Стая скатов летит по орбите. Уровни как у кристаллов.
     * Каждый скат ориентирован по касательной (как будто плывёт),
     * крылья машут синусоидой (wingFlap), хвост волочится сзади.
     */
    private void renderSkats(Vec3d feet, double height, double baseR, long time,
                             int colMain, int colSoft, int colGhost, int colWhite,
                             float width, float thin) {
        double[] yFrac = { 0.95, 0.62, 0.22 };
        int     count  = (int) crystalCount.getValue();
        // Скаты крупнее чем кристаллы — масштабируем до видимого размера.
        double  size   = crystalSize.getValue() * 2.4;

        for (int level = 0; level < 3; level++) {
            double dir = (level % 2 == 0) ? 1.0 : -1.0;
            double orbitT = dir * (time % 6000L) / 6000.0 * Math.PI * 2 * orbitSpeed.getValue();

            for (int i = 0; i < count; i++) {
                double phase  = i * (Math.PI * 2 / count) + level * 0.7;
                double r      = baseR + Math.sin(time / 1500.0 + level + i) * 0.08;
                double orbitA = orbitT + phase;
                double cx     = feet.x + Math.cos(orbitA) * r;
                double cz     = feet.z + Math.sin(orbitA) * r;
                double yBob   = Math.sin(time / 900.0 + i * 1.3 + level * 1.7) * 0.10;
                double cy     = feet.y + height * yFrac[level] + yBob;

                Vec3d center = new Vec3d(cx, cy, cz);

                // Касательная к орбите — вектор движения ската.
                // d/dα (cos α, sin α) = (-sin α, cos α). Знак — направление обхода.
                double tx = -Math.sin(orbitA) * dir;
                double tz =  Math.cos(orbitA) * dir;
                double yaw = Math.atan2(tz, tx);

                // Взмах крыльев: фаза индивидуальна на ската + scale spinSpeed.
                double flapPhase = (time % 1600L) / 1600.0 * Math.PI * 2 * spinSpeed.getValue()
                        + i * 0.7 + level * 1.1;
                double flap = Math.sin(flapPhase);

                drawSkat(center, size, yaw, flap, colMain, colSoft, colWhite, colGhost, width, thin);

                // Trail — призрачные скаты позади по орбите
                if (trail.isEnabled()) {
                    for (int t = 1; t <= 2; t++) {
                        double trailA = orbitA - dir * t * 0.16;
                        double ttx = feet.x + Math.cos(trailA) * r;
                        double ttz = feet.z + Math.sin(trailA) * r;
                        double tty = feet.y + height * yFrac[level] + yBob * 0.5;
                        double trYaw = Math.atan2(Math.cos(trailA) * dir, -Math.sin(trailA) * dir);
                        float k = 1f - (t / 3f);
                        int dim = ColorUtility.multAlpha(colGhost, k);
                        drawSkat(new Vec3d(ttx, tty, ttz), size * (0.78 - t * 0.18),
                                trYaw, flap * 0.8,
                                dim, dim, dim, dim, thin, Math.max(0.45f, thin - 0.2f));
                    }
                }
            }
        }
    }

    /**
     * Скат: ромбовидное тело + длинные крылья с волной + хвост с шипами.
     *
     * Геометрия в плоскости XZ (yaw — вокруг Y):
     *   forward = (cos yaw, 0, sin yaw)
     *   right   = (-sin yaw, 0, cos yaw)
     *
     * @param center   центр тела
     * @param size     базовый радиус
     * @param yaw      направление взгляда (рад)
     * @param flap     фаза взмаха крыльев [-1..1]
     */
    private void drawSkat(Vec3d center, double size, double yaw, double flap,
                          int colBody, int colWing, int colSpike, int colTail,
                          float width, float thin) {
        double cosY = Math.cos(yaw), sinY = Math.sin(yaw);
        // forward: куда плывёт скат
        double fx = cosY,  fz = sinY;
        // right: перпендикуляр (сторона крыла)
        double rx = -sinY, rz = cosY;

        double wingY    = flap * size * 0.45;
        double wingTipY = flap * size * 0.75;

        // Тело: нос → задняя точка
        Vec3d nose      = center.add( fx * size,           0,                    fz * size);
        Vec3d back      = center.add(-fx * size * 0.85,    0,                   -fz * size * 0.85);

        // Корни крыльев (ближе к телу) — волна вверх/вниз
        Vec3d wingLBase = center.add( rx * size * 0.55,    wingY * 0.4,          rz * size * 0.55);
        Vec3d wingRBase = center.add(-rx * size * 0.55,    wingY * 0.4,         -rz * size * 0.55);

        // Концы крыльев — машут сильнее
        Vec3d wingLTip  = center.add( rx * size * 1.45,    wingTipY,             rz * size * 1.45)
                                .add(fx * size * 0.05,     0,                    fz * size * 0.05);
        Vec3d wingRTip  = center.add(-rx * size * 1.45,    wingTipY,            -rz * size * 1.45)
                                .add(fx * size * 0.05,     0,                    fz * size * 0.05);

        // Передние кромки крыльев (от носа к концам) — главный силуэт
        RenderUtility.drawLine(nose, wingLTip, colBody, width, true);
        RenderUtility.drawLine(nose, wingRTip, colBody, width, true);

        // Задние кромки крыльев (от концов к спине)
        RenderUtility.drawLine(wingLTip, back, colWing, thin, true);
        RenderUtility.drawLine(wingRTip, back, colWing, thin, true);

        // Внутренние "жилы" крыла
        RenderUtility.drawLine(wingLBase, wingLTip, colWing, thin, true);
        RenderUtility.drawLine(wingRBase, wingRTip, colWing, thin, true);

        // Центральная ось тела
        RenderUtility.drawLine(nose, back, colSpike, Math.max(0.5f, thin - 0.2f), true);

        // Линия плеч (через центр)
        RenderUtility.drawLine(wingLBase, wingRBase, colWing,
                Math.max(0.5f, thin - 0.2f), true);

        // Хвост: длинный, тянется назад
        Vec3d tailMid = center.add(-fx * size * 1.6,  0, -fz * size * 1.6);
        Vec3d tailTip = center.add(-fx * size * 2.4,  0, -fz * size * 2.4);
        RenderUtility.drawLine(back,    tailMid, colTail, thin, true);
        RenderUtility.drawLine(tailMid, tailTip, colTail, Math.max(0.4f, thin - 0.2f), true);

        // Шип на хвосте — короткие отростки в стороны
        Vec3d spikeL = tailMid.add( rx * size * 0.18, 0,  rz * size * 0.18);
        Vec3d spikeR = tailMid.add(-rx * size * 0.18, 0, -rz * size * 0.18);
        RenderUtility.drawLine(tailMid, spikeL, colSpike, Math.max(0.4f, thin - 0.3f), true);
        RenderUtility.drawLine(tailMid, spikeR, colSpike, Math.max(0.4f, thin - 0.3f), true);
    }

    // ── Ring/arc helpers ─────────────────────────────────────────────────────

    private void drawRing(Vec3d center, double radius, int segments, double angleOffset, int color, float width) {
        Vec3d prev = ringPoint(center, radius, angleOffset);
        for (int i = 1; i <= segments; i++) {
            double a = angleOffset + (Math.PI * 2.0 / segments) * i;
            Vec3d cur = ringPoint(center, radius, a);
            RenderUtility.drawLine(prev, cur, color, width, true);
            prev = cur;
        }
    }

    private void drawArc(Vec3d center, double radius, int segments, double angleStart, double arcLength, int color, float width) {
        Vec3d prev = ringPoint(center, radius, angleStart);
        for (int i = 1; i <= segments; i++) {
            double a = angleStart + (arcLength / segments) * i;
            Vec3d cur = ringPoint(center, radius, a);
            RenderUtility.drawLine(prev, cur, color, width, true);
            prev = cur;
        }
    }

    private Vec3d ringPoint(Vec3d center, double radius, double angle) {
        return new Vec3d(center.x + Math.cos(angle) * radius, center.y, center.z + Math.sin(angle) * radius);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Box getInterpolatedBox(LivingEntity entity, float partialTicks) {
        double x = MathHelper.lerp(partialTicks, entity.prevX, entity.getX());
        double y = MathHelper.lerp(partialTicks, entity.prevY, entity.getY());
        double z = MathHelper.lerp(partialTicks, entity.prevZ, entity.getZ());
        return entity.getBoundingBox().offset(x - entity.getX(), y - entity.getY(), z - entity.getZ());
    }

    private static int blend(int a, int b, float t) {
        t = MathHelper.clamp(t, 0f, 1f);
        int aA = (a >> 24) & 0xFF;
        int aR = (a >> 16) & 0xFF, aG = (a >> 8) & 0xFF, aB = a & 0xFF;
        int bR = (b >> 16) & 0xFF, bG = (b >> 8) & 0xFF, bB = b & 0xFF;
        int rR = (int)(aR + (bR - aR) * t);
        int rG = (int)(aG + (bG - aG) * t);
        int rB = (int)(aB + (bB - aB) * t);
        return (aA << 24) | (rR << 16) | (rG << 8) | rB;
    }
}
