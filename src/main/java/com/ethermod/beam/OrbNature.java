package com.ethermod.beam;

import com.ethermod.trait.Traits;
import com.ethermod.trait.OwnedFire;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Ignite and Growth modes: fire seeds and rings, bone-meal puffs and the drifting pollen cloud.
 * Split out of {@link BeamBallEntity}; every method takes the orb as {@code b}.
 */
final class OrbNature {

    private OrbNature() {}

    /**
     * Charged Growth: a slow pollen cloud that drifts toward where its owner looks, growing crops
     * and plants below it (each block once a second).
     */
    static void driftTick(BeamBallEntity b) {
        ServerLevel sl = (ServerLevel) b.level();
        int life = b.getPhase() == 2 ? 100 : 80;
        if (++b.lifeTicks > life) {
            b.impactFx(b.position());
            b.discard();
            return;
        }
        if (b.getOwner() instanceof ServerPlayer sp && sp.isAlive() && sp.level() == sl) {
            Vec3 eye = sp.getEyePosition();
            double reach =
                    !b.traits.isEmpty() && Traits.hasCommon(b.traits, Traits.BOTANIC, 1)
                            ? 48
                            : 24; // Pollen Trail
            Vec3 target =
                    sl.clip(
                                    new net.minecraft.world.level.ClipContext(
                                            eye,
                                            eye.add(sp.getLookAngle().scale(reach)),
                                            net.minecraft.world.level.ClipContext.Block.COLLIDER,
                                            net.minecraft.world.level.ClipContext.Fluid.NONE,
                                            sp))
                            .getLocation()
                            .add(0, 1.2, 0);
            Vec3 want = target.subtract(b.position());
            Vec3 v =
                    b.getDeltaMovement()
                            .scale(0.85)
                            .add(
                                    want.lengthSqr() > 0.25
                                            ? want.normalize().scale(0.06)
                                            : Vec3.ZERO);
            if (v.length() > 0.35) v = v.normalize().scale(0.35);
            b.setDeltaMovement(v);
        } else {
            b.setDeltaMovement(b.getDeltaMovement().scale(0.9));
        }
        Vec3 p = b.position().add(b.getDeltaMovement());
        b.setPos(p.x, p.y, p.z);

        ProjectileEffects.growthCloud(b, b.traits);
        int r = 1;
        BlockPos c = b.blockPosition();
        long now = sl.getGameTime();
        for (BlockPos bp : BlockPos.betweenClosed(c.offset(-r, -3, -r), c.offset(r, 0, r))) {
            BlockState bs = sl.getBlockState(bp);
            // fully grown plants are skipped before the claim probe (bone meal would do nothing)
            boolean growable =
                    bs.getBlock() instanceof net.minecraft.world.level.block.BonemealableBlock meal
                            && !decorative(bs)
                            && meal.isValidBonemealTarget(sl, bp, bs);
            if (!growable && !bs.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) continue;
            long key = bp.asLong();
            Long next = b.growSeen.get(key);
            if (next != null && next > now) continue;
            b.growSeen.put(key, now + 20);
            if (!b.mayBuild(bp)) continue;
            if (boneMeal(sl, bp.immutable(), 6) && now - b.popAt >= 20) {
                // a bloom opens: a berry pick over a bubble pop (FX bench), once a second at most
                b.popAt = now;
                sl.playSound(null, bp, com.ethermod.sound.ModSounds.WORLD_SWEET_BERRY_BUSH_PICK_BERRIES, SoundSource.PLAYERS, .3f, 1.5f);
                BlockPos at = bp.immutable();
                com.ethermod.trait.Scheduler.schedule(
                        sl,
                        1,
                        () -> sl.playSound(null, at, com.ethermod.sound.ModSounds.WORLD_BUBBLE_COLUMN_BUBBLE_POP, SoundSource.PLAYERS, .25f, 1.6f));
            }
        }
        if (now % 2 == 0) {
            sl.sendParticles(
                    ParticleTypes.SPORE_BLOSSOM_AIR,
                    b.getX(),
                    b.getY(),
                    b.getZ(),
                    4,
                    0.7,
                    0.3,
                    0.7,
                    0);
            sl.sendParticles(
                    ParticleTypes.HAPPY_VILLAGER,
                    b.getX(),
                    b.getY() - 0.5,
                    b.getZ(),
                    1,
                    0.8,
                    0.4,
                    0.8,
                    0);
        }
    }

    /**
     * Flint and steel: light campfires/candles, prime TNT, or start a fire (and portals). Charged:
     * a ring of fire, or with Incendiary a 4-point star.
     */
    static void ignite(BeamBallEntity b, BlockPos hitPos, Direction face) {
        ServerLevel sl = (ServerLevel) b.level();
        Entity owner = b.getOwner();
        if (!b.mayBuild(hitPos)) return;
        BlockState bs = sl.getBlockState(hitPos);
        if (net.minecraft.world.level.block.CampfireBlock.canLight(bs)
                || net.minecraft.world.level.block.CandleBlock.canLight(bs)
                || net.minecraft.world.level.block.CandleCakeBlock.canLight(bs)) {
            sl.setBlock(
                    hitPos,
                    (b.isSoulFire() && bs.is(Blocks.CAMPFIRE)
                                    ? Blocks.SOUL_CAMPFIRE.withPropertiesOf(bs)
                                    : bs)
                            .setValue(
                                    net.minecraft.world.level.block.state.properties
                                            .BlockStateProperties.LIT,
                                    true),
                    Block.UPDATE_ALL_IMMEDIATE);
        } else if (bs.getBlock() instanceof net.minecraft.world.level.block.TntBlock
                && com.ethermod.config.ServerConfig.fireAndLava()) {
            bs.onCaughtFire(sl, hitPos, face, owner instanceof LivingEntity le ? le : null);
            sl.removeBlock(hitPos, false);
        } else {
            BlockPos firePos = hitPos.relative(face);
            if (net.minecraft.world.level.block.BaseFireBlock.canBePlacedAt(sl, firePos, face)
                    && b.mayBuild(firePos)) {
                // a portal frame lights through vanilla's own placement
                if (net.minecraft.world.level.block.BaseFireBlock.getState(sl, firePos)
                        .is(Blocks.FIRE)) {
                    OwnedFire.placeFire(sl, firePos, owner);
                } else {
                    OwnedFire.placeFire(sl, firePos, owner);
                }
                if (b.getPhase() >= 1)
                    OwnedFire.fireRing(
                            sl, firePos, owner, b.getPhase()); // charged: ring of 1, then 2
            }
        }
        sl.playSound(
                null,
                hitPos,
                com.ethermod.sound.ModSounds.WORLD_FLINTANDSTEEL_USE,
                SoundSource.BLOCKS,
                1f,
                0.9f + b.getRandom().nextFloat() * 0.2f);
    }

    /**
     * Exactly what a bone meal item does on this block: grows crops and saplings, spreads grass and
     * flowers on grass blocks (and moss, nylium...), and grows seagrass and coral plants on the
     * floor under water.
     */
    static boolean boneMeal(ServerLevel sl, BlockPos pos, int particles) {
        if (decorative(sl.getBlockState(pos))) return false;
        ItemStack meal = new ItemStack(net.minecraft.world.item.Items.BONE_MEAL);
        if (net.minecraft.world.item.BoneMealItem.growCrop(meal, sl, pos)) {
            sl.levelEvent(1505, pos, particles);
            return true;
        }
        BlockState bs = sl.getBlockState(pos);
        if (bs.getFluidState().is(net.minecraft.tags.FluidTags.WATER)
                && net.minecraft.world.item.BoneMealItem.growWaterPlant(meal, sl, pos, Direction.UP)) {
            sl.levelEvent(1505, pos, particles);
            return true;
        }
        return false;
    }

    /**
     * Grass, ferns and tall flowers: bone meal would only swap them for a taller copy (breaking
     * the old one) or pop out more flowers, so Growth leaves them alone.
     */
    static boolean decorative(BlockState bs) {
        return bs.getBlock() instanceof net.minecraft.world.level.block.TallGrassBlock
                || bs.getBlock() instanceof net.minecraft.world.level.block.DoublePlantBlock;
    }

    /** Bone meal on the block hit: phase 3 covers 5x5. */
    static void grow(BeamBallEntity b, BlockPos hitPos) {
        ServerLevel sl = (ServerLevel) b.level();
        int r = b.getPhase() == 2 ? 2 : 0;
        for (BlockPos p :
                BlockPos.betweenClosed(hitPos.offset(-r, -1, -r), hitPos.offset(r, 1, r))) {
            if (!b.mayBuild(p)) continue;
            boneMeal(sl, p.immutable(), 15);
        }
    }
}
