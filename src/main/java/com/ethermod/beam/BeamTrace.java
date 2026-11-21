package com.ethermod.beam;

import com.ethermod.util.FoliageHelper;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The block the beam actually works on, seen from the client. Matches the server's foliage-aware
 * cast (BeamCastHelper.castThroughFoliage): grass, flowers and leaves in the way are passed
 * through, so the beam end, the aim preview and the glow land on what will really be mined.
 */
public final class BeamTrace {

    private BeamTrace() {}

    /**
     * The beam's end: the nearest living creature on the ray (on the surface of its hitbox, where
     * the beam meets it), otherwise the block.
     */
    public static HitResult castWithEntities(Player player, double range, float pt) {
        HitResult locked = locked(player, range, pt);
        if (locked != null) return locked;
        BlockHitResult block = cast(player, range, pt);
        Vec3 eye = player.getEyePosition(pt);
        Vec3 dir = player.getViewVector(pt);
        double maxDist = block.getLocation().distanceTo(eye);
        Vec3 end = eye.add(dir.scale(maxDist));
        net.minecraft.world.phys.AABB search =
                player.getBoundingBox().expandTowards(dir.scale(maxDist)).inflate(1.0);
        net.minecraft.world.phys.EntityHitResult best = null;
        double bestD = Double.MAX_VALUE;
        for (net.minecraft.world.entity.LivingEntity e :
                player.level()
                        .getEntitiesOfClass(
                                net.minecraft.world.entity.LivingEntity.class,
                                search,
                                e ->
                                        !com.ethermod.beam.Mounts.own(player, e)
                                                && e.isAlive()
                                                && e.isPickable()
                                                && !e.isSpectator())) {
            var box = e.getBoundingBox().inflate(e.getPickRadius());
            var at = box.clip(eye, end);
            if (box.contains(eye)) at = java.util.Optional.of(eye);
            if (at.isEmpty()) continue;
            double d = eye.distanceToSqr(at.get());
            if (d < bestD) {
                bestD = d;
                best = new net.minecraft.world.phys.EntityHitResult(e, at.get());
            }
        }
        return best != null ? best : block;
    }

    /** Same cone as the server's Lock-On (BeamCastHelper). */
    private static final double LOCK_COS = Math.cos(Math.toRadians(12));

    /**
     * Lock-On: the creature the server says the beam holds, while it stays inside the lock cone and
     * in sight, hit at its centre. Null when there is no lock.
     */
    private static HitResult locked(Player player, double range, float pt) {
        RemoteBeams.Pulse pulse = RemoteBeams.pulseOf(player.getId());
        if (pulse == null || pulse.lock < 0) return null;
        if (!(player.level().getEntity(pulse.lock)
                        instanceof net.minecraft.world.entity.LivingEntity e)
                || !e.isAlive()) return null;
        Vec3 eye = player.getEyePosition(pt);
        Vec3 centre =
                e.getPosition(pt).add(0, e.getBbHeight() / 2, 0);
        Vec3 to = centre.subtract(eye);
        if (to.length() > range || to.normalize().dot(player.getViewVector(pt)) <= LOCK_COS)
            return null;
        var wall =
                player.level()
                        .clip(
                                new ClipContext(
                                        eye,
                                        centre,
                                        ClipContext.Block.VISUAL,
                                        ClipContext.Fluid.NONE,
                                        player));
        if (wall.getType() != HitResult.Type.MISS) return null;
        return new net.minecraft.world.phys.EntityHitResult(e, centre);
    }

    public static BlockHitResult cast(Player player, double range, float pt) {
        Vec3 dir = player.getViewVector(pt);
        Vec3 cur = player.getEyePosition(pt);
        Vec3 end = cur.add(dir.scale(range));
        double remaining = range;
        for (int guard = 0; guard < 32 && remaining > 0.01; guard++) {
            Vec3 to = cur.add(dir.scale(remaining));
            BlockHitResult hit =
                    player.level()
                            .clip(
                                    new ClipContext(
                                            cur,
                                            to,
                                            ClipContext.Block.COLLIDER,
                                            ClipContext.Fluid.NONE,
                                            player));
            if (hit.getType() == HitResult.Type.MISS) break;
            net.minecraft.world.level.block.state.BlockState bs =
                    player.level().getBlockState(hit.getBlockPos());
            if (!FoliageHelper.isFoliage(bs)) return hit;
            Vec3 centre = Vec3.atCenterOf(hit.getBlockPos());
            double step = cur.distanceTo(centre) + 0.01;
            remaining -= step;
            cur = cur.add(dir.scale(step));
        }
        return BlockHitResult.miss(
                end,
                net.minecraft.core.Direction.getNearest(dir.x, dir.y, dir.z),
                net.minecraft.core.BlockPos.containing(end));
    }
}
