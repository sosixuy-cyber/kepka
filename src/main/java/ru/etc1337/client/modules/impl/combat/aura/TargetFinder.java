package ru.etc1337.client.modules.impl.combat.aura;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.UtilityClass;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import ru.etc1337.api.interfaces.QuickImports;
import ru.etc1337.api.settings.impl.MultiModeSetting;

import java.util.Comparator;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@UtilityClass
public class TargetFinder implements QuickImports {

    public LivingEntity currentTarget = null;
    public Stream<LivingEntity> potentialTargets = Stream.empty();

    // ── Target lock / release ─────────────────────────────────────────────────

    public void lockTarget(LivingEntity target) {
        if (currentTarget == null) currentTarget = target;
    }

    public void releaseTarget() {
        currentTarget = null;
    }

    /**
     * Валидирует текущую цель и ищет новую если нужно.
     * Приоритет: сохранить текущую цель (sticky lock) — переключаемся
     * только если она стала невалидной.
     */
    public void validateTarget(Predicate<LivingEntity> predicate) {
        // Сначала проверяем текущую — если она всё ещё валидна, не переключаемся.
        if (currentTarget != null && predicate.test(currentTarget)) return;

        // Текущая цель невалидна — ищем новую.
        if (currentTarget != null) releaseTarget();
        findFirstMatch(predicate).ifPresent(TargetFinder::lockTarget);
    }

    /**
     * Обновляет поток кандидатов.
     * Сортировка: сначала по дистанции eye→eye (точнее чем позиция bbox).
     * Если цель вышла за maxDistance — сразу сбрасываем.
     */
    public void searchTargets(Iterable<Entity> entities, float maxDistance) {
        if (isTargetOutOfRange(maxDistance)) releaseTarget();
        potentialTargets = buildStream(entities, maxDistance);
    }

    // ── Внутренние ────────────────────────────────────────────────────────────

    private boolean isTargetOutOfRange(float maxDistance) {
        if (currentTarget == null || mc.player == null) return false;
        // Используем eye-to-eye для точности (как и при фильтрации)
        return mc.player.getEyePos().distanceTo(currentTarget.getEyePos()) > maxDistance;
    }

    private Stream<LivingEntity> buildStream(Iterable<Entity> entities, float maxDistance) {
        if (mc.player == null) return Stream.empty();
        return StreamSupport.stream(entities.spliterator(), false)
                .filter(LivingEntity.class::isInstance)
                .map(LivingEntity.class::cast)
                .filter(e -> mc.player.getEyePos().distanceTo(e.getEyePos()) <= maxDistance)
                // Ближайшие первые по eye-to-eye
                .sorted(Comparator.comparingDouble(
                        e -> mc.player.getEyePos().distanceTo(e.getEyePos())));
    }

    private Optional<LivingEntity> findFirstMatch(Predicate<LivingEntity> predicate) {
        return potentialTargets.filter(predicate).findFirst();
    }

    // ── Entity filter ─────────────────────────────────────────────────────────

    @RequiredArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
    public static class EntityFilter {
        MultiModeSetting targetSettings;

        public boolean isValid(LivingEntity entity) {
            if (isLocalPlayer(entity))  return false;
            if (isInvalidHealth(entity)) return false;
            if (isInvisibleBot(entity))  return false;
            return isValidEntityType(entity);
        }

        private boolean isLocalPlayer(LivingEntity entity) {
            return entity == mc.player;
        }

        /** Мёртвые или уже 0 хп — не трогаем. */
        private boolean isInvalidHealth(LivingEntity entity) {
            return !entity.isAlive() || entity.getHealth() <= 0f;
        }

        /**
         * Базовая anti-bot проверка без внешней зависимости:
         * игроки с нулевым ping (NPC-боты некоторых плагинов) — пропускаем.
         * Полноценную проверку добавить через AntiBot.isBot() если есть.
         */
        private boolean isInvisibleBot(LivingEntity entity) {
            if (!(entity instanceof PlayerEntity player)) return false;
            // Раскомментировать когда будет AntiBot:
            // return AntiBot.isBot(player);
            return false;
        }

        private boolean isValidEntityType(LivingEntity entity) {
            if (entity instanceof PlayerEntity player) {
                // Раскомментировать с FriendManager:
                // if (!targetSettings.get("Friends").isEnabled() &&
                //     FriendManager.isFriend(player.getName().getString())) return false;
                return targetSettings.get(0).isEnabled();
            }
            if (entity instanceof MobEntity) {
                return targetSettings.get(1).isEnabled();
            }
            // ArmorStand — всегда пропускаем, остальные AnimalsEntity etc. — по настройке
            if (entity instanceof ArmorStandEntity) return false;
            return targetSettings.get(2).isEnabled(); // Animals / Others
        }
    }
}
