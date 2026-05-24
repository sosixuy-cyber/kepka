package ru.etc1337.client.modules.impl.combat.aura.ai3;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import ru.etc1337.api.interfaces.QuickImports;
import ru.etc1337.api.render.font.Fonts;
import ru.etc1337.api.render.rect.ShapeProperties;
import ru.etc1337.api.util.color.ColorUtility;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * AuraAI3Screen v2 — Тренажер с ПРАВИЛЬНОЙ записью контекста движений.
 *
 * Что записывает:
 * - Расстояние и угол до цели (полярные координаты)
 * - Текущую скорость курсора
 * - Предыдущий шаг (инерция)
 * - Фазу наведения (0=далеко, 1=близко к цели)
 * - Реальный шаг мыши (то что руками делаешь)
 *
 * Predict: воспроизводит ТВОИ движения — с рывками, кривыми, ускорениями.
 */
public final class AuraAI3Screen extends Screen implements QuickImports {

    private final List<AimTarget>  targets = new ArrayList<>();
    private final Random           random  = new Random();
    private final List<TrailPoint> trail   = new ArrayList<>();


    private boolean recording   = false;
    private boolean predictMode = false;
    private boolean training    = false;
    private float   trainProgress = 0f;

    private int     hits   = 0;
    private int     misses = 0;
    private boolean spawned = false;

    // Состояние записи: предыдущая позиция и шаг
    private float lastMX = -1f, lastMY = -1f;
    private float prevStepX = 0f, prevStepY = 0f;
    private float initialDist = 0f; // расстояние при старте наведения на цель
    private int   samplesSinceHit = 0;

    // Виртуальный курсор для predict
    private float virtualX = -1f, virtualY = -1f;
    private float vPrevStepX = 0f, vPrevStepY = 0f;
    private float vInitialDist = 0f;
    private int   vStepCount = 0;

    private static final float PW = 680f;
    private static final float PH = 460f;

    private float pX, pY;
    private float gX, gY, gW, gH;

    // Кнопки
    private float bRecX, bRecY; private final float bRecW = 75f, bRecH = 18f;
    private float bTrnX, bTrnY; private final float bTrnW = 75f, bTrnH = 18f;
    private float bPrdX, bPrdY; private final float bPrdW = 75f, bPrdH = 18f;
    private float bStpX, bStpY; private final float bStpW = 75f, bStpH = 18f;
    private float bClrX, bClrY; private final float bClrW = 75f, bClrH = 18f;

    public AuraAI3Screen() {
        super(Text.literal("AuraAI3 Neural Trainer v2"));
    }


    @Override
    protected void init() {
        pX = (this.width - PW) / 2f;
        pY = (this.height - PH) / 2f;
        gX = pX + 14f;
        gY = pY + 48f;
        gW = PW - 28f;
        gH = PH - 92f;

        float btnY = pY + PH - 32f;
        float totalBW = bRecW + bTrnW + bPrdW + bStpW + bClrW + 24f;
        float startX = pX + (PW - totalBW) / 2f;

        bRecX = startX;                 bRecY = btnY;
        bTrnX = bRecX + bRecW + 6f;     bTrnY = btnY;
        bPrdX = bTrnX + bTrnW + 6f;     bPrdY = btnY;
        bStpX = bPrdX + bPrdW + 6f;     bStpY = btnY;
        bClrX = bStpX + bStpW + 6f;     bClrY = btnY;

        targets.clear();
        trail.clear();
        spawned = false;
        lastMX = -1f; lastMY = -1f;
        prevStepX = 0f; prevStepY = 0f;
        virtualX = -1f; virtualY = -1f;
        vPrevStepX = 0f; vPrevStepY = 0f;
        recording = false;
        predictMode = false;
        training = false;
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        try {
            doRender(ctx, mx, my);
        } catch (Exception e) { e.printStackTrace(); }
        super.render(ctx, mx, my, delta);
    }


    private void doRender(DrawContext ctx, int mx, int my) {
        var m = ctx.getMatrices();
        long now = System.currentTimeMillis();

        // Затемнение
        rectangle.render(ShapeProperties.create(m, 0, 0, this.width, this.height)
                .color(ColorUtility.getColor(0, 0, 0, 175)).build());
        // Панель
        rectangle.render(ShapeProperties.create(m, pX, pY, PW, PH)
                .round(10f).color(ColorUtility.getColor(180, 40, 40, 35)).build());
        rectangle.render(ShapeProperties.create(m, pX + 1f, pY + 1f, PW - 2f, PH - 2f)
                .round(9f).color(ColorUtility.getColor(12, 10, 10, 255)).build());

        // Заголовок
        Fonts.MNTSB.get(12).drawString(m, "Neuro / AuraAI3 v2", pX + 14f, pY + 14f, 0xFFEEEEEE);
        String status = "trained=" + AuraAI3.get().trained
                + "  samples=" + AuraAI3.get().samples.size()
                + (AuraAI3.get().trained ? String.format("  loss=%.4f", AuraAI3.get().lastLoss) : "");
        float sw = Fonts.MNTSB.get(10).getStringWidth(status);
        Fonts.MNTSB.get(10).drawString(m, status, pX + PW - sw - 14f, pY + 14f, 0xFF888888);

        // Игровая зона
        rectangle.render(ShapeProperties.create(m, gX, gY, gW, gH)
                .round(7f).color(ColorUtility.getColor(140, 30, 30, 20)).build());
        rectangle.render(ShapeProperties.create(m, gX + 1f, gY + 1f, gW - 2f, gH - 2f)
                .round(6f).color(ColorUtility.getColor(8, 6, 6, 255)).build());

        // Кнопки
        drawButton(m, mx, my, bRecX, bRecY, bRecW, bRecH, "RECORD", recording);
        drawButton(m, mx, my, bTrnX, bTrnY, bTrnW, bTrnH, "TRAIN", training);
        drawButton(m, mx, my, bPrdX, bPrdY, bPrdW, bPrdH, "PREDICT", predictMode);
        drawButton(m, mx, my, bStpX, bStpY, bStpW, bStpH, "STOP", false);
        drawButton(m, mx, my, bClrX, bClrY, bClrW, bClrH, "CLEAR", false);


        // Спавн мишеней
        if (!spawned && gW > 60 && gH > 60) { spawned = true; spawnTarget(); }
        while (targets.size() < 1 && gW > 60 && gH > 60) spawnTarget();

        // Отрисовка мишени
        AimTarget activeTarget = null;
        for (AimTarget t : targets) {
            t.tick();
            activeTarget = t;
            drawOutlineCircle(ctx, t.x, t.y, t.rad, ColorUtility.getColor(220, 50, 50, 240));
            rectangle.render(ShapeProperties.create(m, t.x - 2f, t.y - 2f, 4f, 4f)
                    .round(2f).color(ColorUtility.getColor(220, 50, 50, 255)).build());
        }

        // ═══════════════════════════════════════════════════════════════
        // РЕЖИМ PREDICT — ИИ двигает виртуальный курсор ТВОИМ стилем
        // ═══════════════════════════════════════════════════════════════
        if (predictMode && activeTarget != null && AuraAI3.get().trained) {
            if (virtualX < 0) {
                virtualX = gX + gW / 2f;
                virtualY = gY + gH / 2f;
                vPrevStepX = 0f; vPrevStepY = 0f;
                vInitialDist = (float) Math.hypot(activeTarget.x - virtualX, activeTarget.y - virtualY);
                vStepCount = 0;
            }

            float dx = activeTarget.x - virtualX;
            float dy = activeTarget.y - virtualY;
            float dist = (float) Math.hypot(dx, dy);
            float angle = (float) Math.atan2(dy, dx);
            float speed = (float) Math.hypot(vPrevStepX, vPrevStepY);

            // Фаза: 0 = далеко от цели, 1 = почти на цели
            float phase = vInitialDist > 1f ? MathHelper.clamp(1f - dist / vInitialDist, 0f, 1f) : 1f;

            // 100% твои движения — без noise и фейков
            float[] step = AuraAI3.get().predict(dist, angle, speed,
                    vPrevStepX, vPrevStepY, phase);

            virtualX += step[0];
            virtualY += step[1];
            vPrevStepX = step[0];
            vPrevStepY = step[1];
            vStepCount++;


            trail.add(new TrailPoint(virtualX, virtualY, now));

            // Попадание
            float hitDist = (float) Math.hypot(activeTarget.x - virtualX, activeTarget.y - virtualY);
            if (hitDist <= activeTarget.rad + 3) {
                targets.remove(activeTarget);
                hits++;
                if (mc.player != null)
                    mc.player.playSound(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 1.6f);
                // Сброс для новой мишени
                vPrevStepX = 0f; vPrevStepY = 0f;
                vStepCount = 0;
                // initialDist обновится при следующем кадре
                virtualX = virtualX; // оставляем на месте попадания
            }
            // Обновляем initialDist для новой мишени
            if (!targets.isEmpty() && vStepCount == 0) {
                AimTarget next = targets.get(0);
                vInitialDist = (float) Math.hypot(next.x - virtualX, next.y - virtualY);
            }

            // Рисуем виртуальный прицел
            drawOutlineCircle(ctx, virtualX, virtualY, 8f, ColorUtility.getColor(255, 60, 60, 255));
            rectangle.render(ShapeProperties.create(m, virtualX - 12f, virtualY - 0.75f, 24f, 1.5f)
                    .color(ColorUtility.getColor(255, 60, 60, 200)).build());
            rectangle.render(ShapeProperties.create(m, virtualX - 0.75f, virtualY - 12f, 1.5f, 24f)
                    .color(ColorUtility.getColor(255, 60, 60, 200)).build());
        } else {
            virtualX = mx; virtualY = my;
            trail.add(new TrailPoint(mx, my, now));
        }


        // ═══════════════════════════════════════════════════════════════
        // РЕЖИМ RECORD — записывает КОНТЕКСТ каждого шага мыши
        // ═══════════════════════════════════════════════════════════════
        if (recording && lastMX >= 0 && lastMY >= 0 && activeTarget != null) {
            float stepX = mx - lastMX;
            float stepY = my - lastMY;

            if (stepX != 0 || stepY != 0) {
                // Контекст ПЕРЕД шагом
                float distToTarget = (float) Math.hypot(activeTarget.x - lastMX, activeTarget.y - lastMY);
                float angleToTarget = (float) Math.atan2(activeTarget.y - lastMY, activeTarget.x - lastMX);
                float speed = (float) Math.hypot(prevStepX, prevStepY);

                // Фаза наведения
                if (initialDist < 1f) initialDist = distToTarget;
                float phase = MathHelper.clamp(1f - distToTarget / Math.max(initialDist, 1f), 0f, 1f);

                // Записываем полный контекст
                AuraAI3.get().addSample(
                        distToTarget, angleToTarget, speed,
                        prevStepX, prevStepY, phase,
                        stepX, stepY
                );

                prevStepX = stepX;
                prevStepY = stepY;
                samplesSinceHit++;
            }
        }
        lastMX = mx;
        lastMY = my;

        // Trail
        trail.removeIf(pt -> now - pt.time > 3000);
        for (int i = 0; i < trail.size() - 1; i++) {
            TrailPoint p1 = trail.get(i), p2 = trail.get(i + 1);
            float factor = 1f - (float)(now - p1.time) / 3000f;
            if (factor <= 0f) continue;
            drawLine(ctx, p1.x, p1.y, p2.x, p2.y, 1.6f * factor,
                    ColorUtility.getColor(180, 40, 40, (int)(factor * 160)));
        }


        // Прогресс-бар обучения
        if (training) {
            rectangle.render(ShapeProperties.create(m, gX, gY, gW, gH)
                    .round(6f).color(ColorUtility.getColor(12, 10, 10, 215)).build());
            String prgText = "TRAINING: " + (int)(trainProgress * 100) + "%";
            Fonts.MNTSB.get(11).drawCenteredString(m, prgText, gX + gW / 2f, gY + gH / 2f - 10f, 0xFFCC4444);
            float barW = 200f, barH = 4f;
            float barX = gX + (gW - barW) / 2f, barY = gY + gH / 2f + 8f;
            rectangle.render(ShapeProperties.create(m, barX, barY, barW, barH)
                    .round(2f).color(ColorUtility.getColor(40, 40, 40, 255)).build());
            rectangle.render(ShapeProperties.create(m, barX, barY, barW * trainProgress, barH)
                    .round(2f).color(ColorUtility.getColor(200, 40, 40, 255)).build());
        }

        // Статистика внизу
        String stats = String.format("samples=%d  hits=%d  misses=%d",
                AuraAI3.get().samples.size(), hits, misses);
        Fonts.MNTSB.get(10).drawString(m, stats, pX + 14f, pY + PH - 16f, 0xFF888888);
    }

    private void drawButton(net.minecraft.client.util.math.MatrixStack m,
                            int mx, int my, float x, float y, float w, float h,
                            String label, boolean active) {
        boolean hover = inBox(mx, my, x, y, w, h);
        int bg = active ? ColorUtility.getColor(160, 30, 30, 200)
                        : ColorUtility.getColor(40, 42, 50, hover ? 180 : 120);
        rectangle.render(ShapeProperties.create(m, x, y, w, h).round(3f).color(bg).build());
        Fonts.MNTSB.get(9).drawCenteredString(m, label, x + w / 2f, y + 5f, 0xFFFFFFFF);
    }


    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (btn == 0 && !training) {
            if (inBox(mx, my, bRecX, bRecY, bRecW, bRecH)) {
                recording = !recording;
                predictMode = false;
                resetForNewTarget();
                return true;
            }
            if (inBox(mx, my, bTrnX, bTrnY, bTrnW, bTrnH)) {
                if (AuraAI3.get().samples.size() < 20) {
                    AuraAI3.chatSink.accept("§b[AuraAI3] §cМинимум 20 сэмплов! Поводи мышкой по кружкам в режиме RECORD.");
                } else {
                    recording = false; predictMode = false;
                    training = true; trainProgress = 0f;
                    AuraAI3.get().trainModel(5000, progress -> {
                        this.trainProgress = progress;
                        if (progress >= 1.0f) this.training = false;
                    });
                }
                return true;
            }
            if (inBox(mx, my, bPrdX, bPrdY, bPrdW, bPrdH)) {
                if (AuraAI3.get().trained) {
                    predictMode = !predictMode;
                    recording = false;
                    virtualX = (float) mx; virtualY = (float) my;
                    vPrevStepX = 0f; vPrevStepY = 0f;
                    vStepCount = 0;
                    if (!targets.isEmpty()) {
                        AimTarget t = targets.get(0);
                        vInitialDist = (float) Math.hypot(t.x - virtualX, t.y - virtualY);
                    }
                } else {
                    AuraAI3.chatSink.accept("§b[AuraAI3] §cСначала обучи модель! RECORD → TRAIN.");
                }
                return true;
            }
            if (inBox(mx, my, bStpX, bStpY, bStpW, bStpH)) {
                recording = false; predictMode = false; training = false;
                return true;
            }
            if (inBox(mx, my, bClrX, bClrY, bClrW, bClrH)) {
                AuraAI3.get().clear();
                hits = 0; misses = 0; targets.clear(); spawned = false;
                predictMode = false; virtualX = -1f; virtualY = -1f;
                return true;
            }


            // Клик по мишени (ручной режим)
            if (!predictMode) {
                AimTarget closest = null;
                double closestD = Double.MAX_VALUE;
                for (AimTarget t : targets) {
                    double d = Math.hypot(mx - t.x, my - t.y);
                    if (d <= t.rad + 7 && d < closestD) { closest = t; closestD = d; }
                }
                if (closest != null) {
                    targets.remove(closest);
                    hits++;
                    if (mc.player != null)
                        mc.player.playSound(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 1.6f);
                    resetForNewTarget();
                    return true;
                }
                if (recording && inBox(mx, my, gX, gY, gW, gH)) {
                    misses++;
                }
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    private void resetForNewTarget() {
        prevStepX = 0f; prevStepY = 0f;
        samplesSinceHit = 0;
        // Обновляем initialDist для следующей мишени
        if (!targets.isEmpty() && lastMX >= 0) {
            AimTarget t = targets.get(0);
            initialDist = (float) Math.hypot(t.x - lastMX, t.y - lastMY);
        } else {
            initialDist = 0f;
        }
    }

    private void spawnTarget() {
        if (gW < 60 || gH < 60) return;
        AimTarget t = new AimTarget();
        t.rad = 12f + random.nextFloat() * 6f;
        t.x = gX + t.rad + random.nextFloat() * (gW - t.rad * 2);
        t.y = gY + t.rad + random.nextFloat() * (gH - t.rad * 2);
        t.color = Color.HSBtoRGB(random.nextFloat(), 0.75f, 0.9f);
        t.spawnTime = System.currentTimeMillis();
        targets.add(t);
    }


    private void drawLine(DrawContext ctx, float x1, float y1, float x2, float y2, float width, int color) {
        var m = ctx.getMatrices();
        float dx = x2 - x1, dy = y2 - y1;
        float dist = (float) Math.hypot(dx, dy);
        if (dist == 0) return;
        int steps = (int) Math.max(1, dist / 0.5f);
        for (int i = 0; i <= steps; i++) {
            float t = (float) i / steps;
            float px = x1 + dx * t, py = y1 + dy * t;
            rectangle.render(ShapeProperties.create(m, px - width / 2f, py - width / 2f, width, width)
                    .round(width / 2f).color(color).build());
        }
    }

    private void drawOutlineCircle(DrawContext ctx, float cx, float cy, float r, int color) {
        var m = ctx.getMatrices();
        int seg = 48;
        double step = 2 * Math.PI / seg;
        for (int i = 0; i < seg; i++) {
            float px = cx + (float)(r * Math.cos(step * i));
            float py = cy + (float)(r * Math.sin(step * i));
            rectangle.render(ShapeProperties.create(m, px - 0.75f, py - 0.75f, 1.5f, 1.5f)
                    .round(0.75f).color(color).build());
        }
    }

    private boolean inBox(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    @Override public boolean shouldCloseOnEsc() { return true; }
    @Override public void renderBackground(DrawContext ctx, int mx, int my, float delta) {}

    private static class TrailPoint {
        float x, y; long time;
        TrailPoint(float x, float y, long time) { this.x = x; this.y = y; this.time = time; }
    }

    private static class AimTarget {
        float x, y, rad; int color; long spawnTime;
        void tick() {}
    }
}
