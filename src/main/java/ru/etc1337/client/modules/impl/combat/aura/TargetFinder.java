package ru.etc1337.client.modules.impl.combat.aura;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * TargetFinder — finds and prioritizes valid combat targets
 * within range of the local player.
 */
public final class TargetFinder {

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private double range = 4.5;
    private boolean players = true;
    private boolean mobs = false;
    private boolean animals = false;
    private boolean invisible = false;
    private Predicate<LivingEntity> customFilter = null;

    public TargetFinder range(double range) {
        this.range = range;
        return this;
    }

    public TargetFinder players(boolean val) {
        this.players = val;
        return this;
    }

    public TargetFinder mobs(boolean val) {
        this.mobs = val;
        return this;
    }

    public TargetFinder animals(boolean val) {
        this.animals = val;
        return this;
    }

    public TargetFinder invisible(boolean val) {
        this.invisible = val;
        return this;
    }

    public TargetFinder filter(Predicate<LivingEntity> filter) {
        this.customFilter = filter;
        return this;
    }

    /**
     * Find the best target based on configured criteria.
     * @return best target or null if none found
     */
    public LivingEntity find() {
        if (mc.player == null || mc.world == null) return null;

        Vec3d eyePos = mc.player.getEyePos();
        Box searchBox = mc.player.getBoundingBox().expand(range);

        List<LivingEntity> candidates = new ArrayList<>();

        for (var entity : mc.world.getEntitiesByClass(
                LivingEntity.class, searchBox, e -> true)) {

            if (entity == mc.player) continue;
            if (!entity.isAlive()) continue;
            if (entity.isRemoved()) continue;

            double dist = eyePos.distanceTo(
                    entity.getPos().add(0, entity.getHeight() / 2.0, 0));
            if (dist > range) continue;

            if (!invisible && entity.isInvisible()) continue;

            if (entity instanceof PlayerEntity) {
                if (!players) continue;
            } else {
                // Simple heuristic: hostile mobs vs passive
                if (!mobs && !animals) continue;
            }

            if (customFilter != null && !customFilter.test(entity)) {
                continue;
            }

            candidates.add(entity);
        }

        if (candidates.isEmpty()) return null;

        // Sort by angle to crosshair (prioritize targets we're already looking at)
        candidates.sort(Comparator.comparingDouble(e -> {
            Vec3d toTarget = e.getEyePos().subtract(eyePos).normalize();
            Vec3d look = mc.player.getRotationVector().normalize();
            return -toTarget.dotProduct(look); // negative for ascending sort
        }));

        return candidates.get(0);
    }

    /**
     * Find all valid targets sorted by priority.
     */
    public List<LivingEntity> findAll() {
        if (mc.player == null || mc.world == null) {
            return List.of();
        }

        Vec3d eyePos = mc.player.getEyePos();
        Box searchBox = mc.player.getBoundingBox().expand(range);

        List<LivingEntity> candidates = new ArrayList<>();

        for (var entity : mc.world.getEntitiesByClass(
                LivingEntity.class, searchBox, e -> true)) {

            if (entity == mc.player) continue;
            if (!entity.isAlive()) continue;
            if (entity.isRemoved()) continue;

            double dist = eyePos.distanceTo(
                    entity.getPos().add(0, entity.getHeight() / 2.0, 0));
            if (dist > range) continue;

            if (!invisible && entity.isInvisible()) continue;

            if (entity instanceof PlayerEntity) {
                if (!players) continue;
            } else {
                if (!mobs && !animals) continue;
            }

            if (customFilter != null && !customFilter.test(entity)) {
                continue;
            }

            candidates.add(entity);
        }

        candidates.sort(Comparator.comparingDouble(e -> {
            Vec3d toTarget = e.getEyePos().subtract(eyePos).normalize();
            Vec3d look = mc.player.getRotationVector().normalize();
            return -toTarget.dotProduct(look);
        }));

        return candidates;
    }
}
