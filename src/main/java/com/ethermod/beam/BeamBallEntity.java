package com.ethermod.beam;

import com.ethermod.item.ManipulatorStage;
import com.ethermod.item.OrbMode;
import com.ethermod.manipulator.Manipulator;
import com.ethermod.registry.ModEntities;
import com.ethermod.trait.Traits;
import com.ethermod.trait.TempBlocks;

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
 * The RMB projectile. Each mode does its vanilla job on a quick tap and gains depth with charge:
 *
 * <p>ENERGY Symmetra-style orb: damage, scaled by charge; bursts at max range WATER lobbed blob:
 * places water like a bucket; charged, it splashes out fire, knocks back and waters farmland LAVA
 * heavy glob: places lava like a bucket; charged, sets fire around FROST fast shard: freezes the
 * block it hits; charged, lays a temporary ice bridge under its flight that cracks and melts after
 * 30 s IGNITE fire seed: lights what it hits like flint and steel; charged, a ring of fire GROWTH
 * tap: a puff that bonemeals what it hits; charged: a slow pollen cloud that drifts where you look,
 * growing what it passes LIGHT bouncing ball: leaves a light where it stops, and (charged) a light
 * at every bounce for 3 minutes TETHER grapple hook: sticks, then reels you in while RMB is held
 * (OrbMode.VACUUM is only a save-data placeholder; no orb uses it)
 *
 * <p>Every projectile does its job where it stops at max range too.
 *
 * <p>Foliage in its path is cut. Only the server runs game logic; the client just moves it
 * (maxRange is 0 client-side).
 */
public class BeamBallEntity extends Projectile {

    static final EntityDataAccessor<Integer> PHASE =
            SynchedEntityData.defineId(BeamBallEntity.class, EntityDataSerializers.INT);
    static final EntityDataAccessor<Boolean> SOULFIRE =
            SynchedEntityData.defineId(BeamBallEntity.class, EntityDataSerializers.BOOLEAN);
    static final EntityDataAccessor<Integer> MODE =
            SynchedEntityData.defineId(BeamBallEntity.class, EntityDataSerializers.INT);
    static final EntityDataAccessor<Integer> STAGE =
            SynchedEntityData.defineId(BeamBallEntity.class, EntityDataSerializers.INT);
    static final EntityDataAccessor<Integer> COLOR =
            SynchedEntityData.defineId(BeamBallEntity.class, EntityDataSerializers.INT);

    /** Tether hook that has caught a block. */
    static final EntityDataAccessor<Boolean> STUCK =
            SynchedEntityData.defineId(BeamBallEntity.class, EntityDataSerializers.BOOLEAN);

    /** Mass Driver: the block it carries. */
    static final EntityDataAccessor<BlockState> CARRIED =
            SynchedEntityData.defineId(BeamBallEntity.class, EntityDataSerializers.BLOCK_STATE);

    /**
     * Zipline: this hook is an anchor end of a rope; PARTNER is the other end (-1 while waiting for
     * it).
     */
    static final EntityDataAccessor<Boolean> ANCHOR =
            SynchedEntityData.defineId(BeamBallEntity.class, EntityDataSerializers.BOOLEAN);

    static final EntityDataAccessor<Integer> PARTNER =
            SynchedEntityData.defineId(BeamBallEntity.class, EntityDataSerializers.INT);

    /** Mass Driver: held in a formation (the client then only follows the server, no physics). */
    static final EntityDataAccessor<Boolean> MASS_HELD =
            SynchedEntityData.defineId(BeamBallEntity.class, EntityDataSerializers.BOOLEAN);

    /** Mass Driver: this piece's place in the held formation (for the landing preview). */
    static final EntityDataAccessor<BlockPos> MASS_OFFSET =
            SynchedEntityData.defineId(BeamBallEntity.class, EntityDataSerializers.BLOCK_POS);

    /** Fired with left click (Detonator: the same input bursts it). */
    static final EntityDataAccessor<Boolean> FIRED_LEFT =
            SynchedEntityData.defineId(BeamBallEntity.class, EntityDataSerializers.BOOLEAN);

    /** How long a Light orb's bounce lights last, in ticks (3 minutes). */
    static final int BOUNCE_LIGHT_TICKS = 3600;

    // server-only working state for the newer modes
    int lifeTicks;
    /** Tether: game time the claw clamps on what it hit (sound, then reeling); -1 when done. */
    long hookAt = -1;
    float hookPitch;
    boolean zipPlacing;
    boolean virtualHit, airReelAfterRelease;

    /** Tether reeling: a tap reels all the way (reelFull), holding reels while held (reeling). */
    boolean reelFull, reeling, jumpWas, reelWas;

    /** Playtest: the tether's reel state. */
    public String debugTether() {
        return "stuck=" + isStuck() + " hooked=" + (hooked == null ? "none" : hooked.getType().toShortString()) + " hookAt=" + hookAt
                + " reeling=" + reeling + " full=" + reelFull;
    }

    /** Gravity Shift: carrying its owner onto the caught face (game time it started, or -1). */
    long landingSince = -1;

    /** Tether: the caught face and the last right click (ropeLength: the rope while not reeling). */
    Direction hookFace;
    long lastPress = -100;
    int idleTicks;

    /**
     * Mass Driver: the block is let go and flies on its own. {@code bounces}: how many times it
     * bounces off what it hits before it places itself; {@code gentle} (Steady Hand): it hurts
     * nothing.
     */
    public void releaseMass(int bounces, boolean gentle) {
        massBounces = bounces;
        massGentle = gentle;
        entityData.set(MASS_HELD, false);
    }

    int massBounces;
    boolean massGentle;

    int bridgeLaid;
    final java.util.Set<Long> bridgeSeen = new java.util.HashSet<>();
    final java.util.Map<Long, Long> growSeen = new java.util.HashMap<>();
    /** Game time of the pollen cloud's last bloom pop (one pop per second at most). */
    long popAt = -100;
    BlockPos stuckBlock;

    /** Tether: what the hook caught, if a creature or item (Recall). */
    Entity hooked;

    /** Where the hook struck what it caught, in that body's own frame (null: its middle). */
    Vec3 hookOffset;

    /** Tether with Swing: the rope's length while swinging. */
    double ropeLength;

    /** Tether: where its owner's hands were when it caught (Zipline's far end). */
    Vec3 ropeStart;

    /** Tidecaller: the owner rides along this Water orb. */
    boolean carrying;

    /** Zipline anchor: ticks left. */
    int anchorLife;

    // Server-only — NOT synced intentionally (defaults to 0 on client)
    float damage;
    float maxRange;
    float traveledDistance;

    public static final float[] PHASE_RADIUS = {0.18f, 0.35f, 0.58f};

    // 6/16 blocks — hitbox smaller than the visual 8-unit model
    static final float HITBOX = 6f / 16f;
    static final EntityDimensions DIMS = EntityDimensions.scalable(HITBOX, HITBOX);

    static final String TAG_PHASE = "mm_phase";
    static final String TAG_DAMAGE = "mm_ball_dmg";
    static final String TAG_RANGE = "mm_ball_range";
    static final String TAG_TRAVEL = "mm_ball_travel";

    // =========================================================================
    // Constructors
    // =========================================================================

    /** Ticks after the shot during which it can't hit its own shooter (jumping into a fresh shot). */
    static final int OWNER_GRACE = 10;

    /**
     * Never the shooter's own mount (or anyone riding with them): shots fired from the saddle go
     * past it. Never the shooter either for the first {@link #OWNER_GRACE} ticks.
     */
    @Override
    protected boolean canHitEntity(Entity e) {
        if (e == getOwner() && tickCount < OWNER_GRACE) return false;
        return super.canHitEntity(e) && !com.ethermod.beam.Mounts.own(getOwner(), e)
                && ((getMode() == OrbMode.TETHER ? com.ethermod.config.ServerConfig.tetherPlayers() : com.ethermod.config.ServerConfig.hurtPlayers())
                        || !(e instanceof net.minecraft.world.entity.player.Player) || e == getOwner());
    }

    public BeamBallEntity(EntityType<? extends BeamBallEntity> type, Level level) {
        super(type, level);
    }

    public BeamBallEntity(
            Level level, LivingEntity shooter, int phase, ManipulatorStage stage, OrbMode mode) {
        super(ModEntities.BEAM_BALL.get(), level);
        setOwner(shooter);

        int p = Math.max(0, Math.min(2, phase));
        entityData.set(PHASE, p);
        entityData.set(MODE, mode.ordinal());
        entityData.set(STAGE, stage.ordinal());

        this.damage = stage.ballDamage(p) * com.ethermod.config.ServerConfig.shotDamage();
        this.maxRange = mode.gravity() > 0 ? 96f : stage.ballRange();
        this.traveledDistance = 0f;

        // Server-side spawn: approximate barrel tip offset from eye.
        // The GeckoLib muzzle_anchor bone position is client-only, so we use
        // a close approximation here. The ball moves away immediately so
        // sub-block precision is not needed for gameplay.
        Vec3 eye = shooter.getEyePosition();
        Vec3 look = shooter.getLookAngle();
        Vec3 up =
                com.ethermod.trait.MovementPhysics.rotate(
                        new Vec3(0, 1, 0),
                        com.ethermod.trait.MovementPhysics.down(
                                (net.minecraft.world.entity.player.Player) shooter));
        Vec3 right = look.cross(up).normalize();
        Vec3 camUp = right.cross(look).normalize(); // true camera up, accounts for pitch
        Vec3 spawn = eye.add(right.scale(0.45)).add(camUp.scale(-0.15)).add(look.scale(0.70));
        setPos(spawn.x, spawn.y, spawn.z);

        shoot(look.x, look.y, look.z, mode.speed(p), 0f);
    }

    /** Adds trait damage on top of the stage damage (server-side, before spawning). */
    public void addBonusDamage(float bonus) {
        this.damage += bonus;
    }

    /** A snapshot of the firing Manipulator, for trait checks. Server-only, not saved. */
    ItemStack traits = ItemStack.EMPTY;

    int bouncesLeft;
    java.util.UUID seekerTarget;

    public void scaleDamage(float amount) {
        damage *= amount;
    }

    /** Applies the Orb line's launch-time traits. Call before spawning. */
    public void configure(ItemStack manipulator) {
        OrbFlight.configure(this, manipulator);
    }

    /** Mass Driver: the carried block (air if none). */
    /** A lifted container's contents (server config mass_driver_lifts_containers), or null. */
    public net.minecraft.nbt.CompoundTag carriedBe;

    public BlockState getCarried() {
        return entityData.get(CARRIED);
    }

    public void setCarried(BlockState state) {
        entityData.set(CARRIED, state);
    }

    /** Zipline: an anchored rope end. */
    public boolean isAnchor() {
        return entityData.get(ANCHOR);
    }

    public int getPartnerId() {
        return entityData.get(PARTNER);
    }

    /** Zipline: this stuck hook stays as a rope end, waiting for its partner (or linked to it). */
    public void makeAnchor(int life, int partnerId) {
        entityData.set(ANCHOR, true);
        entityData.set(PARTNER, partnerId);
        entityData.set(STUCK, true);
        setDeltaMovement(Vec3.ZERO);
        hooked = null;
        anchorLife = life;
    }

    public void linkAnchor(int partnerId, int life) {
        entityData.set(PARTNER, partnerId);
        anchorLife = life;
    }

    /** Zipline anchors melt away when their time is up or their partner is gone. */
    void anchorTick() {
        if (--anchorLife <= 0
                || (stuckBlock != null && level().getBlockState(stuckBlock).isAir())) {
            discard();
            return;
        }
        int partner = getPartnerId();
        if (partner >= 0
                && !(level().getEntity(partner) instanceof BeamBallEntity other && other.isAlive()))
            discard();
    }

    /** Tidecaller: the owner rides along. */
    public void carryOwner() {
        carrying = true;
    }

    /** Rotates the flight direction around the vertical axis (Split). */
    public void turn(float degrees) {
        Vec3 v = getDeltaMovement();
        double r = Math.toRadians(degrees), c = Math.cos(r), sn = Math.sin(r);
        setDeltaMovement(v.x * c - v.z * sn, v.y, v.x * sn + v.z * c);
    }

    boolean has(String trait) {
        return !traits.isEmpty() && Traits.has(traits, trait);
    }

    /** The trait's rank on the Manipulator that fired this orb (0 if not picked). */
    int rank(String trait) {
        return traits.isEmpty() ? 0 : Traits.rank(traits, trait);
    }

    // =========================================================================
    // Entity definition
    // =========================================================================

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(SOULFIRE, false);
        builder.define(PHASE, 0);
        builder.define(MODE, 0);
        builder.define(STAGE, 0);
        builder.define(COLOR, com.ethermod.item.ManipulatorColors.DEFAULT_BEAM);
        builder.define(STUCK, false);
        builder.define(CARRIED, Blocks.AIR.defaultBlockState());
        builder.define(ANCHOR, false);
        builder.define(PARTNER, -1);
        builder.define(FIRED_LEFT, false);
        builder.define(MASS_HELD, false);
        builder.define(MASS_OFFSET, BlockPos.ZERO);
    }

    public void setMassHeld(boolean held, BlockPos offset) {
        entityData.set(MASS_HELD, held);
        entityData.set(MASS_OFFSET, offset);
    }

    public boolean isMassHeld() {
        return entityData.get(MASS_HELD);
    }

    public BlockPos massOffset() {
        return entityData.get(MASS_OFFSET);
    }

    public boolean isSoulFire() {
        return entityData.get(SOULFIRE);
    }

    public int getPhase() {
        return entityData.get(PHASE);
    }

    public boolean isStuck() {
        return entityData.get(STUCK);
    }

    /** A charged Growth orb: a slow pollen cloud that follows your aim. */
    public boolean isDrifting() {
        return getMode() == OrbMode.GROWTH && getPhase() >= 1;
    }

    public OrbMode getMode() {
        return OrbMode.byId(entityData.get(MODE));
    }

    /** The owner's beam colour (RGB), used for Energy orbs. */
    public int getBeamColor() {
        return entityData.get(COLOR);
    }

    public ManipulatorStage getStage() {
        ManipulatorStage[] v = ManipulatorStage.values();
        return v[Math.max(0, Math.min(v.length - 1, entityData.get(STAGE)))];
    }

    /** Smaller hitbox than the visual model (6 vs 8 model units). */
    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return DIMS;
    }

    // =========================================================================
    // Tick
    // =========================================================================

    /**
     * The local player's rope physics on a stuck tether (ClientMobility.ropeTick). Set by the
     * client setup, so this common class never names a client class; null on a dedicated server.
     */
    public static java.util.function.Consumer<BeamBallEntity> clientRopeTick;

    @Override
    public void tick() {
        OrbMode mode = getMode();
        // CLIENT: only advance position (with the mode's gravity), no game logic.
        // (maxRange = 0 on client, so a range check would instantly discard)
        if (level().isClientSide) {
            if (isStuck()) {
                if (mode == OrbMode.TETHER && !isAnchor() && clientRopeTick != null)
                    clientRopeTick.accept(this);
                return;
            }
            // a held Mass Driver block goes exactly where the server puts it: moving it on here
            // too made it run ahead and snap back on every wall
            if (mode == OrbMode.MASS && isMassHeld()) return;
            if (mode.gravity() > 0 && !isDrifting())
                setDeltaMovement(getDeltaMovement().add(0, -OrbFlight.gravity(this), 0));
            // thrown blocks fall as they do on the server
            if (mode == OrbMode.MASS) setDeltaMovement(getDeltaMovement().add(0, -.08, 0));
            Vec3 pos = position();
            Vec3 mov = getDeltaMovement();
            setPos(pos.x + mov.x, pos.y + mov.y, pos.z + mov.z);
            return;
        }

        if (mode == OrbMode.MASS && !level().isClientSide && MassDriver.heldTick(this)) return;
        // SERVER: full physics
        if ((mode == OrbMode.LAVA || mode == OrbMode.IGNITE) && getOwner() instanceof ServerPlayer pyro)
            pyro.getPersistentData()
                    .putLong(com.ethermod.advancement.SecretAdvancements.PYRO_TAG, level().getGameTime() + 1200);
        // an Energy orb bats a ghast's fireball back, as a punch would (Ping Pong)
        if (mode == OrbMode.ENERGY && getOwner() instanceof ServerPlayer batter)
            for (var fb :
                    level().getEntitiesOfClass(
                            net.minecraft.world.entity.projectile.LargeFireball.class,
                            getBoundingBox().inflate(.6),
                            f -> f.getOwner() instanceof net.minecraft.world.entity.monster.Ghast)) {
                fb.hurt(batter.damageSources().playerAttack(batter), 1f);
                com.ethermod.advancement.ManipulatorTrigger.fire(batter, "ping_pong");
                impactFx(position());
                discard();
                return;
            }
        super.tick();

        if (isAnchor()) {
            anchorTick();
            return;
        }
        if (mode == OrbMode.TETHER && !OrbTether.tetherTick(this)) return;
        if (mode == OrbMode.WATER || mode == OrbMode.FROST)
            com.ethermod.beam.ProjectileEffects.waterPath(this, traits);
        if (mode == OrbMode.LIGHT) com.ethermod.beam.ProjectileEffects.lightFlight(this, traits);
        if (mode == OrbMode.GUST && OrbGust.gustTick(this)) return;
        if (isDrifting()) {
            OrbNature.driftTick(this);
            return;
        }
        lifeTicks++;
        if (mode.gravity() > 0) setDeltaMovement(getDeltaMovement().add(0, -OrbFlight.gravity(this), 0));
        if (mode == OrbMode.WATER || mode == OrbMode.FROST) OrbFluids.steerWater(this);
        if (mode == OrbMode.MASS) {
            setDeltaMovement(getDeltaMovement().add(0, -.08, 0));
        }

        Vec3 movement = getDeltaMovement();
        // Slipstream: no range limit while flying through water
        boolean slip =
                (isInWater() || TempBlocks.isAirPocket((ServerLevel) level(), blockPosition()))
                        && (mode == OrbMode.WATER || mode == OrbMode.FROST)
                        && !traits.isEmpty()
                        && Traits.hasCommon(traits, Traits.HYDRAULIC, 1);
        if (!slip) traveledDistance += movement.length(); // Slipstream: no range spent underwater
        if (traveledDistance > maxRange || lifeTicks > 600) {
            OrbImpact.expire(this);
            return;
        }
        if (carrying) OrbFlight.carry(this);

        // fire and wind tear through foliage; every other shot passes through it
        if ((mode == OrbMode.LAVA || mode == OrbMode.IGNITE) && com.ethermod.config.ServerConfig.fireAndLava()
                || mode == OrbMode.GUST && com.ethermod.config.ServerConfig.gustCutsFoliage())
            FoliageCut.sweep(
                    (ServerLevel) level(),
                    getOwner() instanceof ServerPlayer sp ? sp : null,
                    position(),
                    position().add(movement),
                    mode != OrbMode.GUST);

        OrbFlight.seek(this);
        OrbFrost.frostBridge(this, position(), movement);
        movement = getDeltaMovement(); // Seeker may have steered

        // Hit detection. Grass and flowers have no collision; leaves do, and shots hit them unless
        // the server lets everything pass through leaves (then a leaf hit is looked past)
        java.util.function.Predicate<Entity> canHit = this::canHitEntity;
        HitResult hit = ProjectileUtil.getHitResultOnMoveVector(this, canHit);
        if (leaves(hit) && movement.lengthSqr() > 1e-6) {
            Vec3 end = position().add(movement), dir = movement.normalize();
            HitResult past = hit;
            for (int i = 0; i < 8 && leaves(past); i++)
                past =
                        level().clip(
                                        new net.minecraft.world.level.ClipContext(
                                                past.getLocation().add(dir.scale(.05)),
                                                end,
                                                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                                                net.minecraft.world.level.ClipContext.Fluid.NONE,
                                                this));
            if (leaves(past)) past = net.minecraft.world.phys.BlockHitResult.miss(end, Direction.UP, BlockPos.containing(end));
            Vec3 stop = past.getType() == HitResult.Type.MISS ? end : past.getLocation();
            var mob =
                    ProjectileUtil.getEntityHitResult(
                            level(), this, position(), stop, getBoundingBox().expandTowards(movement).inflate(1), canHit);
            hit = mob != null ? mob : past;
        }
        if (hit.getType() != HitResult.Type.MISS) {
            onHit(hit);
            return;
        }

        Vec3 pos = position();
        setPos(pos.x + movement.x, pos.y + movement.y, pos.z + movement.z);
    }

    /** Leaves let shots through only when the server makes them non-solid (see solid_leaves). */
    private boolean leaves(HitResult h) {
        return !com.ethermod.config.ServerConfig.beamMinesLeaves()
                && h instanceof net.minecraft.world.phys.BlockHitResult bh
                && h.getType() == HitResult.Type.BLOCK
                && level().getBlockState(bh.getBlockPos()).is(net.minecraft.tags.BlockTags.LEAVES);
    }

    // =========================================================================
    // Flight traits (Orb line: paths and Seeker)
    // =========================================================================

    // =========================================================================
    // Hit resolution
    // =========================================================================

    @Override
    protected void onHitEntity(EntityHitResult result) {
        OrbImpact.onEntity(this, result);
    }

    @Override
    protected void onHitBlock(BlockHitResult result) {
        OrbImpact.onBlock(this, result);
    }

    // =========================================================================
    // Mode effects (the bodies live in OrbImpact, OrbFx and the other Orb* helpers)
    // =========================================================================

    boolean mayBuild(BlockPos pos) {
        return !(getOwner() instanceof ServerPlayer sp) || com.ethermod.util.Protect.may(level(), sp, pos);
    }

    /** Impact burst, visible to everyone nearby. */
    void impactFx(Vec3 pos) {
        OrbFx.impact(this, pos);
    }

    // =========================================================================
    // Kinetic: Detonator
    // =========================================================================

    public void detonate() {
        OrbImpact.detonate(this);
    }

    public void setFiredLeft(boolean left) {
        entityData.set(FIRED_LEFT, left);
    }

    public boolean firedLeft() {
        return entityData.get(FIRED_LEFT);
    }

    /** Detonator: bursts this player's orbs fired with that input (see {@link OrbImpact#detonateFor}). */
    public static boolean detonateFor(Player p, ItemStack stack, boolean left) {
        return OrbImpact.detonateFor(p, stack, left);
    }

    // =========================================================================
    // NBT
    // =========================================================================

    /**
     * Only a Mass Driver orb carrying a block is saved (so a block mid-throw is not lost when its
     * chunk unloads). Every other orb, Tether hook and Zipline anchor is short-lived and keeps
     * runtime-only state (traits, rope partner ids), so it is dropped instead of coming back
     * half-initialised.
     */
    @Override
    public boolean shouldBeSaved() {
        return super.shouldBeSaved() && getMode() == OrbMode.MASS && !getCarried().isAir();
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("mm_soul_projectile", isSoulFire());
        tag.putInt(TAG_PHASE, getPhase());
        tag.putInt("mm_mode", getMode().ordinal());
        tag.putInt("mm_stage", entityData.get(STAGE));
        tag.putInt("mm_color", entityData.get(COLOR));
        tag.putFloat(TAG_DAMAGE, damage);
        tag.putFloat(TAG_RANGE, maxRange);
        tag.putFloat(TAG_TRAVEL, traveledDistance);
        if (!getCarried().isAir())
            tag.put("mm_carried", net.minecraft.nbt.NbtUtils.writeBlockState(getCarried()));
        if (carriedBe != null) tag.put("mm_carried_be", carriedBe);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        carriedBe = tag.contains("mm_carried_be") ? tag.getCompound("mm_carried_be") : null;
        entityData.set(PHASE, tag.getInt(TAG_PHASE));
        entityData.set(MODE, tag.getInt("mm_mode"));
        entityData.set(SOULFIRE, tag.getBoolean("mm_soul_projectile"));
        entityData.set(STAGE, tag.getInt("mm_stage"));
        if (tag.contains("mm_color")) entityData.set(COLOR, tag.getInt("mm_color"));
        damage = tag.getFloat(TAG_DAMAGE);
        maxRange = tag.getFloat(TAG_RANGE);
        traveledDistance = tag.getFloat(TAG_TRAVEL);
        if (tag.contains("mm_carried"))
            setCarried(
                    net.minecraft.nbt.NbtUtils.readBlockState(
                            net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(),
                            tag.getCompound("mm_carried")));
    }
}
