package com.ethermod.beam;

import com.ethermod.item.ManipulatorStage;
import com.ethermod.trait.Traits;
import com.ethermod.util.FoliageHelper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Server-side beam ray-cast, fired every BEAM_TICK_INTERVAL ticks from ModEvents.
 *
 * <p>The beam is context sensitive: - on a creature: continuous damage that ramps 1x / 2x / 3x the
 * longer it stays on target (Symmetra) - on a block: mining that ramps from 0.8x to 2x vanilla
 * speed over 2 s - while sneaking: a harmless utility beam that does the right-click jobs vanilla
 * tools lose (till, path, strip, scrape, light campfires/candles) and pulls dropped items and
 * experience towards you
 */
public final class BeamCastHelper {

    private BeamCastHelper() {}

    /**
     * Damage of one strike at pressure 1 is stage.beamTickDamage() (+ the Plasmic badge) scaled by
     * this; pressure raises both the strike and the cadence ({@link BeamState#hitInterval}). No
     * knockback, no i-frames.
     */
    private static final float DAMAGE_SCALE = 1.0f;

    /** Arc: how far a strike can jump from the creature in the beam. */
    private static final double ARC_RANGE = 5;

    /**
     * Tells this player and everyone watching the beam's pressure when it changes, and each
     * strike (with the creatures it arced to), so the beam's look and sound follow the real
     * numbers.
     */
    private static void sync(
            ServerPlayer player, BeamState state, int level, boolean strike, List<Integer> arcs) {
        sync(player, state, level, strike, arcs, -1);
    }

    private static void sync(
            ServerPlayer player,
            BeamState state,
            int level,
            boolean strike,
            List<Integer> arcs,
            int lock) {
        if (!strike && level == state.sentLevel && lock == state.sentLock) return;
        state.sentLevel = level;
        state.sentLock = lock;
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                player,
                new com.ethermod.network.payload.BeamPulsePayload(
                        player.getId(), level, strike, arcs, lock));
    }

    /** The beam's own damage type: no knockback (tagged), credited to the player. */
    static net.minecraft.world.damagesource.DamageSource beamSource(ServerLevel level, ServerPlayer player) {
        return new net.minecraft.world.damagesource.DamageSource(
                level.registryAccess()
                        .registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE)
                        .getHolderOrThrow(BEAM_DAMAGE),
                player);
    }

    public static final net.minecraft.resources.ResourceKey<net.minecraft.world.damagesource.DamageType>
            BEAM_DAMAGE =
                    net.minecraft.resources.ResourceKey.create(
                            net.minecraft.core.registries.Registries.DAMAGE_TYPE,
                            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                                    "ethermod", "beam"));

    public static Vec3 tickBeam(
            ServerLevel level,
            ServerPlayer player,
            ManipulatorStage stage,
            BeamState state,
            int interval) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        ItemStack tool = player.getMainHandItem();
        float range = Traits.beamRange(tool);

        if (player.isShiftKeyDown()) {
            Vec3 end =
                    level.clip(
                                    new ClipContext(
                                            eye,
                                            eye.add(look.scale(range)),
                                            ClipContext.Block.COLLIDER,
                                            ClipContext.Fluid.NONE,
                                            player))
                            .getLocation();
            BlockHitResult bh =
                    level.clip(
                            new ClipContext(
                                    eye,
                                    eye.add(look.scale(range)),
                                    ClipContext.Block.OUTLINE,
                                    ClipContext.Fluid.NONE,
                                    player));
            Optional<EntityHitResult> animalHit =
                    (Traits.has(tool, "botanic.herder")
                                    || Traits.has(tool, "botanic.groundskeeper"))
                            ? findEntityOnRay(level, player, eye, look, (float) end.distanceTo(eye))
                            : Optional.empty();
            if (animalHit.isPresent()
                    && animalHit.get().getEntity()
                            instanceof net.minecraft.world.entity.animal.Animal animal) {
                if (Traits.has(tool, "botanic.herder"))
                    UtilityBeam.herd(level, player, animal, state);
                else UtilityBeam.shearEntity(level, player, animal, state);
            } else if (bh.getType() == HitResult.Type.BLOCK) {
                UtilityBeam.apply(level, player, bh, state);
            }
            tractor(level, player, eye, end);
            sync(player, state, state.trackTarget(-1, interval, tool, level), false, List.of());
            return end;
        }

        // --- Strike cadence: pressure sets how often the beam bites ----------
        state.hitCooldown = Math.max(0, state.hitCooldown - interval);
        boolean strike = state.hitCooldown == 0;

        // --- Foliage-aware block ray-cast ------------------------------------
        Vec3 hitPos = castThroughFoliage(level, player, eye, look, range);
        // --- Entity: Lock-On keeps the last target inside a small cone ---------
        LivingEntity target = null;
        Vec3 targetPoint = null;
        if (Traits.has(tool, "plasmic.lock_on")
                && state.level() >= 2
                && level.getEntity(state.targetId) instanceof LivingEntity locked
                && locked.isAlive()) {
            Vec3 to = locked.getBoundingBox().getCenter().subtract(eye);
            if (to.length() <= range
                    && to.normalize().dot(look) > Math.cos(Math.toRadians(12))
                    && player.hasLineOfSight(locked)) {
                target = locked;
                targetPoint = locked.getBoundingBox().getCenter();
            }
        }
        if (target == null) {
            float entityRange = (float) hitPos.distanceTo(eye);
            Optional<EntityHitResult> entityHit =
                    findEntityOnRay(level, player, eye, look, entityRange);
            if (entityHit.isPresent()) {
                target = (LivingEntity) entityHit.get().getEntity();
                targetPoint = entityHit.get().getLocation();
            }
        }

        if (target != null) {
            int combatLevel = state.trackTarget(target.getId(), interval, tool, level);
            player.getPersistentData().putInt("mm_beam_level", combatLevel);
            com.ethermod.util.MobMemory.of(target).putInt("mm_combat_pressure", combatLevel);
            // Lock-On: from pressure 2 the beam holds this creature; clients bend the beam onto it
            int lock =
                    Traits.has(tool, "plasmic.lock_on") && combatLevel >= 2 ? target.getId() : -1;
            if (!strike) {
                sync(player, state, combatLevel, false, List.of(), lock);
                return targetPoint;
            }
            state.hitCooldown = BeamState.hitInterval(combatLevel);
            float dmg =
                    (stage.beamTickDamage() + Traits.beamDamageBonus(tool))
                            * DAMAGE_SCALE
                            * BeamState.levelMultiplier(combatLevel);
            beamHit(level, player, tool, target, dmg);

            // Arc: pressure jumps the strike to the nearest other creatures, one more per level
            // from level 2 (1 / 2 / 3), each taking half the strike
            List<Integer> arcs = new java.util.ArrayList<>();
            if (combatLevel >= 2 && Traits.has(tool, "plasmic.arc")) {
                final LivingEntity first = target;
                level
                        .getEntitiesOfClass(
                                LivingEntity.class,
                                target.getBoundingBox().inflate(ARC_RANGE),
                                e ->
                                        e != first
                                                && !com.ethermod.beam.Mounts.own(player, e)
                                                && e.isAlive()
                                                && e.isPickable()
                                                && !(e
                                                        instanceof
                                                        net.minecraft.world.entity.player.Player)
                                                && !(e
                                                                instanceof
                                                                net.minecraft.world.entity
                                                                        .OwnableEntity
                                                                own
                                                        && player.getUUID()
                                                                .equals(own.getOwnerUUID())))
                        .stream()
                        .sorted(Comparator.comparingDouble(e -> e.distanceToSqr(first)))
                        .limit(combatLevel - 1)
                        .forEach(
                                e -> {
                                    beamHit(level, player, tool, e, dmg * 0.5f);
                                    arcs.add(e.getId());
                                });
            }
            sync(player, state, combatLevel, true, arcs, lock);
            return targetPoint;
        }
        boolean mining = hitPos.distanceTo(eye) < range - 0.1;
        // Momentum: mining builds (and keeps) the beam's pressure; otherwise it runs down
        boolean momentum = mining && Traits.has(tool, "tectonic.momentum");
        int pressure =
                momentum
                        ? state.trackMining(interval, tool, level)
                        : state.trackTarget(-1, interval, tool, level);
        if (momentum) player.getPersistentData().putInt("mm_beam_level", pressure);
        sync(player, state, pressure, false, List.of());

        // --- Block mining with ramp ------------------------------------------
        if (mining) {
            // step slightly INTO the block that was hit (not back towards the eye)
            BlockPos blockPos = BlockPos.containing(hitPos.add(look.scale(0.05)));
            float rampTicks = Traits.hasCommon(tool, Traits.TECTONIC, 0) ? 32f : 40f;
            float ramp = Math.min(1.0f, state.ticks / rampTicks);
            float speed =
                    (stage.miningSpeed() + Traits.miningSpeedBonus(tool))
                            * (momentum ? BeamState.miningMultiplier(pressure) : 1f);
            mine(level, player, stage, tool, blockPos, speed, ramp, interval);
        }
        return hitPos;
    }

    /** Applies one beam tick of mining to a block (nothing if it can't be mined). */
    private static void mine(
            ServerLevel level,
            ServerPlayer player,
            ManipulatorStage stage,
            ItemStack tool,
            BlockPos pos,
            float speed,
            float ramp,
            int interval) {
        BlockState block = level.getBlockState(pos);
        if (block.isAir()
                || !block.getFluidState().isEmpty()
                        && block.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)
            return;
        float hardness = block.getDestroySpeed(level, pos);
        if (hardness < 0) return;
        // Botanic: the beam leaves crops that aren't ripe
        if (block.getBlock() instanceof CropBlock crop
                && !crop.isMaxAge(block)
                && Traits.hasLine(tool, Traits.BOTANIC)) return;
        float dmg;
        if (hardness == 0f || player.isCreative()) {
            dmg = 1.0f;
        } else {
            // Vanilla: speed / hardness / 30 per tick with the right tool, / 100 without.
            float perTick = speed / (stage.canHarvest(block) ? 30.0f : 100.0f);
            dmg = perTick * interval * (0.8f + 1.2f * ramp) / hardness;
        }
        // One crack id per player and block: a block keeps its crack (and its
        // progress, see MiningDamageTracker) while the beam works elsewhere.
        int crack = -1 - ((pos.hashCode() * 31 + player.getId()) & 0x3fffffff);
        MiningDamageTracker.applyDamage(level, pos, crack, dmg, player);
        // Vanilla's mining hit sound, every 4 ticks like a pickaxe (the main block only)
        if (level.getGameTime() % 4 < interval
                && !level.getBlockState(pos).isAir()) {
            net.minecraft.world.level.block.SoundType st = block.getSoundType(level, pos, player);
            level.playSound(
                    null,
                    pos,
                    st.getHitSound(),
                    net.minecraft.sounds.SoundSource.BLOCKS,
                    (st.getVolume() + 1f) / 8f,
                    st.getPitch() * 0.5f);
        }
    }

    /** One beam hit on a creature, with the Combat line's on-hit traits. */
    private static void beamHit(
            ServerLevel level,
            ServerPlayer player,
            ItemStack tool,
            LivingEntity target,
            float dmg) {
        // Disrupt: the beam goes straight through shields and knocks them out
        if (target instanceof net.minecraft.world.entity.player.Player p
                && p.isBlocking()
                && Traits.has(tool, "plasmic.disrupt")) {
            p.disableShield();
        }
        if (Traits.has(tool, "plasmic.disrupt")
                && player.getPersistentData().getInt("mm_beam_level") >= 2) {
            dmg *= 1.15f;
            target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 30, 0, false, false, true));
        }
        dmg *= com.ethermod.config.ServerConfig.beamDamage();
        Vec3 incomingVelocity = target.getDeltaMovement();
        float before = target.getHealth();
        // A continuous beam: ignore the 10-tick invulnerability window.
        target.invulnerableTime = 0;
        target.hurt(beamSource(level, player), dmg);
        target.invulnerableTime = 0;
        target.setDeltaMovement(incomingVelocity);
        float dealt = Math.max(0, before - target.getHealth());
        com.ethermod.util.MobMemory.of(target)
                .putInt(
                        "mm_combat_pressure",
                        Math.max(1, player.getPersistentData().getInt("mm_beam_level")));

        // Continuous damage must not erase the target's voluntary movement.
        // Restore the incoming velocity after damage, without repeatedly pinning it.

    }

    // =========================================================================
    // Internals
    // =========================================================================

    /** Pulls item entities and experience orbs near the ray towards the player. */
    private static void tractor(ServerLevel level, ServerPlayer player, Vec3 start, Vec3 end) {
        AABB box = new AABB(start, end).inflate(1.5);
        Vec3 dir = end.subtract(start);
        double lenSq = Math.max(1e-6, dir.lengthSqr());
        for (Entity e :
                level.getEntities(
                        player, box, e -> e instanceof ItemEntity || e instanceof ExperienceOrb)) {
            Vec3 p = e.position();
            double t = Math.max(0, Math.min(1, p.subtract(start).dot(dir) / lenSq));
            if (p.distanceToSqr(start.add(dir.scale(t))) > 2.25) continue;
            Vec3 pull = player.position().add(0, 0.6, 0).subtract(p).normalize().scale(0.35);
            e.setDeltaMovement(e.getDeltaMovement().scale(0.5).add(pull));
            e.hurtMarked = true;
        }
    }

    private static Vec3 castThroughFoliage(
            ServerLevel level, ServerPlayer player, Vec3 start, Vec3 dir, float range) {
        Vec3 current = start;
        float remaining = range;

        while (remaining > 0.01f) {
            Vec3 end = current.add(dir.scale(remaining));
            BlockHitResult hit =
                    level.clip(
                            new ClipContext(
                                    current,
                                    end,
                                    ClipContext.Block.COLLIDER,
                                    ClipContext.Fluid.NONE,
                                    player));

            if (hit.getType() == HitResult.Type.MISS) return end;

            BlockPos pos = hit.getBlockPos();
            BlockState state = level.getBlockState(pos);

            if (FoliageHelper.isFoliage(state)) {
                // foliage the server lets the beam pass (grass, flowers… and leaves if set) stays standing
                Vec3 face = Vec3.atCenterOf(pos);
                float step = (float) current.distanceTo(face) + 0.01f;
                remaining -= step;
                current = current.add(dir.scale(step));
            } else {
                return hit.getLocation();
            }
        }
        return current;
    }

    private static Optional<EntityHitResult> findEntityOnRay(
            Level level, ServerPlayer player, Vec3 start, Vec3 dir, float maxDist) {
        Vec3 end = start.add(dir.scale(maxDist));
        AABB search = player.getBoundingBox().expandTowards(dir.scale(maxDist)).inflate(1.0);

        return level
                .getEntitiesOfClass(
                        LivingEntity.class,
                        search,
                        e -> !com.ethermod.beam.Mounts.own(player, e) && e.isAlive() && e.isPickable()
                                && (com.ethermod.config.ServerConfig.hurtPlayers() || !(e instanceof net.minecraft.world.entity.player.Player)))
                .stream()
                .filter(e -> e.getBoundingBox().inflate(0.1).clip(start, end).isPresent())
                .min(Comparator.comparingDouble(e -> start.distanceToSqr(e.getEyePosition())))
                .map(
                        e ->
                                new EntityHitResult(
                                        e,
                                        e.getBoundingBox()
                                                .inflate(0.1)
                                                .clip(start, end)
                                                .orElse(e.position())));
    }
}
