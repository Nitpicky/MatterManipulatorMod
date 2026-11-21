package com.ethermod.beam;

import com.ethermod.item.ManipulatorStage;
import com.ethermod.item.OrbMode;
import com.ethermod.manipulator.Manipulator;
import com.ethermod.registry.ModEntities;
import com.ethermod.trait.Traits;
import com.ethermod.trait.TempBlocks;
import com.ethermod.trait.TempLights;
import com.ethermod.trait.OwnedFire;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * What an orb does where it stops: on a creature, on a block, at max range, or when Detonator
 * bursts it. Split out of {@link BeamBallEntity}; every method takes the orb as {@code b}.
 */
final class OrbImpact {

    private OrbImpact() {}

    /**
     * Max range reached without hitting anything: the projectile still does its job where it is,
     * instead of just vanishing.
     */
    static void expire(BeamBallEntity b) {
        if (b.getMode() == OrbMode.TETHER && b.has("gravitic.skyhook") && !b.zipPlacing) {
            OrbTether.hookAir(b);
            return;
        }

        ServerLevel sl = (ServerLevel) b.level();
        Vec3 at = b.position();
        BlockPos here = b.blockPosition();
        switch (b.getMode()) {
            case ENERGY -> {
                double r = BeamBallEntity.PHASE_RADIUS[b.getPhase()] * 2 + 1;
                for (LivingEntity e :
                        sl.getEntitiesOfClass(
                                LivingEntity.class,
                                b.getBoundingBox().inflate(r),
                                e -> e != b.getOwner() && e.isAlive())) {
                    e.hurt(
                            b.damageSources()
                                    .thrown(
                                            b,
                                            b.getOwner() instanceof LivingEntity le ? le : b),
                            b.damage * 0.6f);
                }
            }
            case WATER -> {
                OrbFluids.placeFluid(b, here, Direction.UP, Fluids.WATER);
                ProjectileEffects.waterImpact(b, b.traits, at);
            }
            case LAVA -> {
                if (OrbFluids.placeFluid(b, here, Direction.UP, Fluids.LAVA))
                    OrbFluids.lavaPlaced(b, here);
            }
            case IGNITE -> OrbNature.ignite(b, here.below(), Direction.UP);
            case FROST -> {
                if (b.mayBuild(here))
                    TempBlocks.tempBlock(
                            sl,
                            here,
                            Blocks.PACKED_ICE.defaultBlockState(),
                            OrbFrost.bridgeTicks(b),
                            true);
            }
            case LIGHT -> {
                OrbLight.lightImpact(b, here, null);
                OrbLight.lightExtras(b, at);
            }
            case GUST -> OrbGust.gustBurst(b, at);
            case MASS -> OrbMass.dropCarried(b, at);
            case GROWTH -> OrbNature.grow(b, here);
            default -> {}
        }
        b.impactFx(at);
        b.discard();
    }

    /** The orb hit a creature. */
    static void onEntity(BeamBallEntity b, EntityHitResult result) {
        if (b.level().isClientSide) return;
        ServerLevel sl = (ServerLevel) b.level();
        Entity target = result.getEntity();
        Entity owner = b.getOwner();
        if (b.getMode() == OrbMode.WATER)
            ProjectileEffects.waterImpact(b, b.traits, result.getLocation());
        if (b.getMode() == OrbMode.WATER
                && target instanceof net.minecraft.world.entity.monster.EnderMan
                && owner instanceof ServerPlayer rain)
            com.ethermod.advancement.ManipulatorTrigger.fire(rain, "rain_check");
        int ph = b.getPhase();
        if (owner instanceof ServerPlayer sp
                && target instanceof LivingEntity le
                && (b.getMode() == OrbMode.LAVA || b.getMode() == OrbMode.IGNITE))
            com.ethermod.trait.Burning.burn(le, sp, 4 + ph * 2, ph);
        switch (b.getMode()) {
            case ENERGY -> {
                target.hurt(
                        b.damageSources().thrown(b, owner instanceof LivingEntity le ? le : b),
                        b.damage);
            }
            case WATER -> {
                target.clearFire();
                double force = 0.4 + 0.3 * ph;
                Vec3 push = b.getDeltaMovement().normalize().scale(force);
                target.setDeltaMovement(
                        target.getDeltaMovement().add(push.x, 0.15 + force * 0.1, push.z));
                target.hurtMarked = true;
                OrbFluids.placeFluid(b, target.blockPosition(), null, Fluids.WATER);
            }
            case LAVA -> {
                target.igniteForSeconds(5);
                if (OrbFluids.placeFluid(b, target.blockPosition(), null, Fluids.LAVA))
                    OrbFluids.lavaPlaced(b, target.blockPosition());
            }
            case FROST -> {
                if (target instanceof LivingEntity le) {
                    le.addEffect(
                            new net.minecraft.world.effect.MobEffectInstance(
                                    net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN,
                                    100 + 60 * ph,
                                    1 + ph));
                    le.setTicksFrozen(
                            Math.max(le.getTicksFrozen(), le.getTicksRequiredToFreeze() + 60));
                    le.hurt(b.damageSources().freeze(), 1f + ph);
                }
            }
            case IGNITE -> target.igniteForSeconds(4 + 2 * ph);
            case GROWTH -> {
                if (target instanceof LivingEntity victim
                        && owner instanceof ServerPlayer sp
                        && com.ethermod.trait.Allegiance.enemy(sp, victim)
                        && (victim instanceof net.minecraft.world.entity.monster.Enemy
                                || victim instanceof net.minecraft.world.entity.Mob m
                                        && m.getTarget() == sp)) {
                    int potency = 0;
                    victim.addEffect(
                            new net.minecraft.world.effect.MobEffectInstance(
                                    net.minecraft.world.effect.MobEffects.POISON, 100, potency));
                    victim.addEffect(
                            new net.minecraft.world.effect.MobEffectInstance(
                                    net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN,
                                    100,
                                    potency));
                }
                if (target instanceof net.minecraft.world.entity.AgeableMob baby && baby.isBaby()) {
                    baby.ageUp(
                            net.minecraft.world.entity.AgeableMob.getSpeedUpSecondsWhenFeeding(
                                    -baby.getAge()),
                            true);
                }
            }
            case LIGHT -> {
                OrbLight.lightImpact(b, target.blockPosition(), null);
                OrbLight.lightExtras(b, result.getLocation());
            }
            case GUST -> OrbGust.gustBurst(b, result.getLocation());
            case MASS -> {
                // the block bounces back off the creature and flies on, then lands as usual
                OrbMass.massHitEntity(b, target, result.getLocation());
                return;
            }
            case TETHER -> {
                // the hook catches the creature and holds; right click reels it in (tetherTick)
                if (owner instanceof ServerPlayer sp) OrbTether.catchEntity(b, target, sp);
                return;
            }
            default -> {}
        }
        b.impactFx(result.getLocation());
        b.discard();
    }

    /** The orb hit a block (or burst in the air, see {@link #detonate}). */
    static void onBlock(BeamBallEntity b, BlockHitResult result) {
        if (b.level().isClientSide) {
            b.discard();
            return;
        }
        ServerLevel sl = (ServerLevel) b.level();
        BlockPos hitPos = result.getBlockPos();
        Direction face = result.getDirection();
        BlockPos front = hitPos.relative(face);
        int ph = b.getPhase();
        if (b.getMode() == OrbMode.WATER)
            ProjectileEffects.waterImpact(b, b.traits, result.getLocation());

        // Tether: the hook catches the block and holds (see tetherTick)
        if (b.getMode() == OrbMode.TETHER) {
            // Blink and Gravity Shift wait for a double tap of right click (OrbTether.press)
            b.hookFace = face;
            Vec3 at = result.getLocation();
            b.setPos(at.x, at.y, at.z);
            b.setDeltaMovement(Vec3.ZERO);
            b.getEntityData().set(BeamBallEntity.STUCK, true);
            b.stuckBlock = b.virtualHit ? null : hitPos;
            if (b.getOwner() instanceof ServerPlayer sp) b.ropeStart = sp.getEyePosition();
            // the sound and the reel wait for the claw to clamp (OrbTether.HOOK_DELAY)
            b.hookAt = sl.getGameTime() + OrbTether.HOOK_DELAY;
            b.hookPitch = 1.3f;
            if (b.getOwner() instanceof ServerPlayer sp) {
                if (b.zipPlacing) {
                    com.ethermod.trait.Ziplines.onHookStuck(sp, b);
                    if (!b.isAnchor()) com.ethermod.trait.Ziplines.pin(sp, b);
                }
            }
            return;
        }

        // Bank Shot (and the Light orb's own bounces)
        if (b.bouncesLeft > 0) {
            b.bouncesLeft--;
            b.traveledDistance = 0f; // each ricochet gets the full range again
            if (b.getMode() == OrbMode.LIGHT && sl.getBlockState(front).isAir() && b.mayBuild(front)) {
                TempLights.tempLight(sl, front, 13, BeamBallEntity.BOUNCE_LIGHT_TICKS);
                sl.sendParticles(
                        ParticleTypes.END_ROD,
                        front.getX() + 0.5,
                        front.getY() + 0.5,
                        front.getZ() + 0.5,
                        6,
                        0.2,
                        0.2,
                        0.2,
                        0.05);
            }
            Vec3 v = b.getDeltaMovement();
            Vec3 n = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
            Vec3 r = v.subtract(n.scale(2 * v.dot(n)));
            b.setDeltaMovement(r);
            if (b.getMode() == OrbMode.LIGHT)
                ProjectileEffects.lightBounce(b, b.traits, result.getLocation().add(n.scale(0.3)));
            Vec3 at = result.getLocation().add(n.scale(0.2));
            b.setPos(at.x, at.y, at.z);
            sl.playSound(
                    null,
                    at.x,
                    at.y,
                    at.z,
                    com.ethermod.sound.ModSounds.WORLD_AMETHYST_BLOCK_HIT,
                    SoundSource.PLAYERS,
                    0.8f,
                    1.6f);
            return;
        }

        switch (b.getMode()) {
            case ENERGY -> {}
            case WATER -> {
                b.carrying = false;
                // Cauldrons and other mods' tanks take the bucket
                if (OrbFluids.fillContainer(b, hitPos, face, Fluids.WATER)) break;
                BlockState hitState = sl.getBlockState(hitPos);
                // a waterloggable block takes the water; otherwise it goes in front of the face
                if (!(hitState.getBlock() instanceof SimpleWaterloggedBlock
                        && OrbFluids.placeFluid(b, hitPos, face, Fluids.WATER)))
                    OrbFluids.placeFluid(b, front, face, Fluids.WATER);
                OrbFluids.extinguish(b, front, Math.max(ph, 1));
                if (ph >= 1) {
                    // a splash: shove creatures back from the impact
                    for (LivingEntity e :
                            sl.getEntitiesOfClass(
                                    LivingEntity.class,
                                    new AABB(front).inflate(1 + ph),
                                    e -> e != b.getOwner())) {
                        Vec3 away = e.position().subtract(Vec3.atCenterOf(front));
                        away = away.lengthSqr() < 1e-4 ? new Vec3(0, 1, 0) : away.normalize();
                        e.setDeltaMovement(
                                e.getDeltaMovement()
                                        .add(away.x * 0.6 * ph, 0.25, away.z * 0.6 * ph));
                        e.hurtMarked = true;
                        e.clearFire();
                    }
                }
                if (ph == 2) {
                    // and soak the soil: farmland 5x5 becomes fully watered
                    for (BlockPos bp :
                            BlockPos.betweenClosed(
                                    front.offset(-2, -2, -2), front.offset(2, 1, 2))) {
                        BlockState fs = sl.getBlockState(bp);
                        if (fs.getBlock() instanceof net.minecraft.world.level.block.FarmBlock
                                && b.mayBuild(bp)) {
                            sl.setBlock(
                                    bp,
                                    fs.setValue(
                                            net.minecraft.world.level.block.FarmBlock.MOISTURE, 7),
                                    Block.UPDATE_CLIENTS);
                        }
                    }
                    sl.sendParticles(
                            ParticleTypes.SPLASH,
                            front.getX() + 0.5,
                            front.getY() + 0.2,
                            front.getZ() + 0.5,
                            40,
                            1.5,
                            0.2,
                            1.5,
                            0.1);
                }
            }
            case LAVA -> {
                if (OrbFluids.forgeHeart(b, hitPos)) break;
                if (OrbFluids.fillContainer(b, hitPos, face, Fluids.LAVA)) break;
                if (OrbFluids.placeFluid(b, front, face, Fluids.LAVA)) {
                    OrbFluids.lavaPlaced(b, front);
                    // charged: fire splashes out around the pool
                    if (ph >= 1) OwnedFire.fireRing(sl, front, b.getOwner(), ph);
                }
            }
            case FROST -> {
                // Tap: freeze the water block hit, or the air in front of the face, into ice
                if (sl.getBlockState(hitPos).is(Blocks.WATER) && b.mayBuild(hitPos)) {
                    TempBlocks.tempBlock(
                            sl,
                            hitPos,
                            Blocks.PACKED_ICE.defaultBlockState(),
                            OrbFrost.bridgeTicks(b),
                            true);
                } else if (b.mayBuild(front)) {
                    TempBlocks.tempBlock(
                            sl,
                            front,
                            Blocks.PACKED_ICE.defaultBlockState(),
                            OrbFrost.bridgeTicks(b),
                            true);
                }
                // Charged: freeze a patch of water (lava turns to obsidian for good)
                int r = ph == 2 ? 2 : ph;
                for (BlockPos p :
                        BlockPos.betweenClosed(front.offset(-r, -1, -r), front.offset(r, 0, r))) {
                    if (sl.getBlockState(p).is(Blocks.WATER)
                            && sl.getFluidState(p).isSource()
                            && b.mayBuild(p)) {
                        TempBlocks.tempBlock(
                                sl,
                                p.immutable(),
                                Blocks.PACKED_ICE.defaultBlockState(),
                                OrbFrost.bridgeTicks(b),
                                true);
                    } else if (sl.getBlockState(p).is(Blocks.LAVA)
                            && sl.getFluidState(p).isSource()
                            && b.mayBuild(p)) {
                        sl.setBlockAndUpdate(p, Blocks.OBSIDIAN.defaultBlockState());
                    }
                }
                for (LivingEntity e :
                        sl.getEntitiesOfClass(
                                LivingEntity.class,
                                new AABB(front).inflate(1 + r),
                                e -> e != b.getOwner())) {
                    e.addEffect(
                            new net.minecraft.world.effect.MobEffectInstance(
                                    net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN,
                                    100,
                                    1));
                }
            }
            case IGNITE -> {
                if (!OrbFluids.forgeHeart(b, hitPos)) OrbNature.ignite(b, hitPos, face);
            }
            case GROWTH -> OrbNature.grow(b, hitPos);
            case LIGHT -> {
                OrbLight.lightImpact(b, front, hitPos);
                OrbLight.lightExtras(b, result.getLocation());
            }
            case GUST -> OrbGust.gustBurst(b, result.getLocation());
            case MASS -> {
                // a thrown block bounces off the first thing it hits, then places itself
                if (b.massBounces > 0) {
                    b.massBounces--;
                    OrbMass.bounce(b, face, result.getLocation());
                    return;
                }
                OrbMass.massPlace(b, hitPos, face, result.getLocation());
            }
            default -> {}
        }
        b.impactFx(result.getLocation());
        b.discard();
    }

    /** Detonator: burst right where it is, with its full impact effect. */
    static void detonate(BeamBallEntity b) {
        if (b.level().isClientSide
                || b.isRemoved()
                || b.isStuck()
                || b.isAnchor()
                || b.getMode() == OrbMode.TETHER
                || MassDriver.isHeld(b)) return;
        b.bouncesLeft = 0;
        BlockPos here = b.blockPosition();
        b.onHitBlock(new BlockHitResult(b.position(), Direction.UP, here.below(), false));
    }

    /** In the air and able to burst: not a hook, an anchor or a held block. */
    static boolean detonatable(BeamBallEntity b) {
        return !b.isRemoved()
                && !b.isStuck()
                && !b.isAnchor()
                && b.getMode() != OrbMode.TETHER
                && b.getMode() != OrbMode.MASS;
    }

    /**
     * Detonator: pressing the input that fired a projectile again, while it is still in the air,
     * bursts it (every one, for a Split volley) instead of firing another. True when something
     * burst, so the press fires nothing; on the client it only answers whether one would.
     */
    static boolean detonateFor(Player p, ItemStack stack, boolean left) {
        if (!Traits.has(stack, "kinetic.detonator")) return false;
        var live =
                p.level()
                        .getEntitiesOfClass(
                                BeamBallEntity.class,
                                p.getBoundingBox().inflate(160),
                                b -> b.getOwner() == p && b.firedLeft() == left && detonatable(b));
        if (live.isEmpty()) return false;
        if (!p.level().isClientSide) for (BeamBallEntity b : live) detonate(b);
        return true;
    }
}
