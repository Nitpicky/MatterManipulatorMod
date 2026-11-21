package com.ethermod.trait;

import com.ethermod.manipulator.Manipulator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.*;

/** Gravity-relative geometry and authoritative movement intent. */
public final class MovementPhysics {
    private MovementPhysics() {}

    public static Direction down(Player p) {
        int n = p.getPersistentData().getInt("mm_gravity");
        return n >= 0 && n < 6 ? Direction.from3DDataValue(n) : Direction.DOWN;
    }

    public static Vec3 vector(Direction d) {
        return new Vec3(d.getStepX(), d.getStepY(), d.getStepZ());
    }

    public static Vec3 rotate(Vec3 v, Direction d) {
        return switch (d) {
            case DOWN -> v;
            case UP -> new Vec3(v.x, -v.y, -v.z);
            case EAST -> new Vec3(-v.y, v.x, v.z);
            case WEST -> new Vec3(v.y, -v.x, v.z);
            case NORTH -> new Vec3(v.x, -v.z, v.y);
            case SOUTH -> new Vec3(v.x, v.z, -v.y);
        };
    }

    public static org.joml.Quaternionf rotation(Direction d) {
        return switch (d) {
            case DOWN -> new org.joml.Quaternionf();
            case UP -> new org.joml.Quaternionf().rotateX((float) Math.PI);
            case EAST -> new org.joml.Quaternionf().rotateZ((float) Math.PI / 2);
            case WEST -> new org.joml.Quaternionf().rotateZ((float) -Math.PI / 2);
            case NORTH -> new org.joml.Quaternionf().rotateX((float) Math.PI / 2);
            case SOUTH -> new org.joml.Quaternionf().rotateX((float) -Math.PI / 2);
        };
    }

    private record Turn(org.joml.Quaternionf from, org.joml.Quaternionf to, long start) {}

    private static final Map<Player, Turn> TURNS = Collections.synchronizedMap(new WeakHashMap<>());

    public static boolean isTurning(Player p) {
        var turn = TURNS.get(p);
        return down(p) != Direction.DOWN
                || (turn != null && p.level().getGameTime() < turn.start() + 10);
    }

    public static org.joml.Quaternionf viewRotation(Player p, float partial) {
        var turn = TURNS.get(p);
        if (turn == null) return rotation(down(p));
        double t = Math.clamp((p.level().getGameTime() + partial - turn.start()) / 10.0, 0, 1);
        t = t * t * (3 - 2 * t);
        return new org.joml.Quaternionf(turn.from()).slerp(turn.to(), (float) t);
    }

    public static Vec3 orientView(Player p, Vec3 v, float partial) {
        var f =
                viewRotation(p, partial)
                        .transform(new org.joml.Vector3f((float) v.x, (float) v.y, (float) v.z));
        return new Vec3(f.x, f.y, f.z);
    }

    public static Vec3 eye(Player p, float partial) {
        Vec3 center =
                p.getPosition(partial)
                        .add(vector(down(p).getOpposite()).scale(p.getBbHeight() / 2));
        Vec3 desired =
                center.add(
                        orientView(
                                p,
                                new Vec3(0, p.getEyeHeight() - p.getBbHeight() / 2, 0),
                                partial));
        var hit =
                p.level()
                        .clip(
                                new ClipContext(
                                        center,
                                        desired,
                                        ClipContext.Block.VISUAL,
                                        ClipContext.Fluid.NONE,
                                        p));
        return hit.getType() == HitResult.Type.BLOCK
                ? hit.getLocation().subtract(desired.subtract(center).normalize().scale(.08))
                : desired;
    }

    public static void beginTurn(Player p, Direction target, long start) {
        TURNS.put(p, new Turn(viewRotation(p, 0), rotation(target), start));
    }

    public static AABB boundsAt(Player p, Vec3 feet, Direction direction) {
        return boundsAt(feet, direction, p.getBbWidth(), p.getBbHeight());
    }

    public static AABB poseBounds(Player p, net.minecraft.world.entity.Pose pose) {
        var dimensions = p.getDimensions(pose);
        return boundsAt(p.position(), down(p), dimensions.width(), dimensions.height());
    }

    private static AABB boundsAt(Vec3 feet, Direction direction, double width, double height) {
        Vec3 u = vector(direction.getOpposite());
        double w = width / 2;
        Vec3 end = feet.add(u.scale(height));
        return new AABB(
                Math.min(feet.x, end.x) - (u.x == 0 ? w : 0),
                Math.min(feet.y, end.y) - (u.y == 0 ? w : 0),
                Math.min(feet.z, end.z) - (u.z == 0 ? w : 0),
                Math.max(feet.x, end.x) + (u.x == 0 ? w : 0),
                Math.max(feet.y, end.y) + (u.y == 0 ? w : 0),
                Math.max(feet.z, end.z) + (u.z == 0 ? w : 0));
    }

    public static boolean setGravity(ServerPlayer p, Direction direction) {
        if (down(p) == direction) return true;
        Vec3 center = p.getBoundingBox().getCenter(),
                up = vector(direction.getOpposite()),
                chosen = null;
        var offsets = new ArrayList<Vec3>();
        offsets.add(Vec3.ZERO);
        Vec3 oldUp = vector(down(p).getOpposite());
        for (double distance = .125; distance <= 1.5; distance += .125) {
            offsets.add(up.scale(distance));
            offsets.add(oldUp.scale(distance));
            for (var axis : Direction.values()) offsets.add(vector(axis).scale(distance));
        }
        for (var offset : offsets) {
            Vec3 candidate = center.add(offset).subtract(up.scale(p.getBbHeight() / 2));
            AABB box = boundsAt(p, candidate, direction);
            if (!p.level().getWorldBorder().isWithinBounds(box)
                    || !p.level().noCollision(p, box.deflate(.000001))) continue;
            var path =
                    p.level()
                            .clip(
                                    new ClipContext(
                                            center,
                                            center.add(offset),
                                            ClipContext.Block.COLLIDER,
                                            ClipContext.Fluid.NONE,
                                            p));
            if (path.getType() != HitResult.Type.MISS) continue;
            chosen = candidate;
            break;
        }
        if (chosen == null) return false;
        long start = p.level().getGameTime();
        beginTurn(p, direction, start);
        p.getPersistentData().putInt("mm_gravity", direction.get3DDataValue());
        p.setPos(chosen);
        p.refreshDimensions();
        p.resetFallDistance();
        p.setNoGravity(direction != Direction.DOWN);
        p.teleportTo(
                p.serverLevel(),
                chosen.x,
                chosen.y,
                chosen.z,
                java.util.Set.of(),
                p.getYRot(),
                p.getXRot());
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                p,
                new com.ethermod.network.payload.ModulePayloads.Gravity(
                        p.getId(), direction.get3DDataValue(), start));
        return true;
    }

    /** Back to normal gravity where the player is, without moving them: mounting something erases a gravity shift
     *  (a teleport would throw them off the mount again). */
    public static void clearGravity(ServerPlayer p) {
        if (down(p) == Direction.DOWN) return;
        long start = p.level().getGameTime();
        beginTurn(p, Direction.DOWN, start);
        p.getPersistentData().putInt("mm_gravity", Direction.DOWN.get3DDataValue());
        p.refreshDimensions();
        p.resetFallDistance();
        p.setNoGravity(false);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                p,
                new com.ethermod.network.payload.ModulePayloads.Gravity(
                        p.getId(), Direction.DOWN.get3DDataValue(), start));
    }

    /** Known held-key bits of the Motion payload: jump, sneak, sprint, forward, back, left, right. */
    public static final int MOTION_BITS = 127;

    /**
     * Server: the movement keys each player holds (Motion payload). Kept in memory only, never in
     * the player's saved data; cleared on logout and server stop.
     */
    private static final Map<UUID, Integer> MOTION = new HashMap<>();

    public static void input(ServerPlayer p, int flags) {
        flags &= MOTION_BITS;
        if (flags == 0) MOTION.remove(p.getUUID());
        else MOTION.put(p.getUUID(), flags);
    }

    /** Forgets a player's held keys (logout). */
    public static void forgetInput(UUID player) {
        MOTION.remove(player);
    }

    public static void clearInputs() {
        MOTION.clear();
    }

    /** Jump held: the client reads its own keys (ClientMobility), the server the last Motion. */
    public static boolean jump(Player p) {
        if (p.level().isClientSide) return (p.getPersistentData().getInt("mm_motion") & 1) != 0;
        return (MOTION.getOrDefault(p.getUUID(), 0) & 1) != 0;
    }

    public static AABB bounds(Player p) {
        return boundsAt(p, p.position(), down(p));
    }

    /** A thin slab immediately below the feet, independent of a stale onGround flag. */
    public static boolean hasRealSupport(Player p) {
        Vec3 d = vector(down(p));
        Vec3 center = p.position().add(d.scale(.04));
        double w = p.getBbWidth() * .48;
        double x = d.x == 0 ? w : .045, y = d.y == 0 ? w : .045, z = d.z == 0 ? w : .045;
        return !p.level()
                .noCollision(
                        p,
                        new AABB(
                                center.x - x,
                                center.y - y,
                                center.z - z,
                                center.x + x,
                                center.y + y,
                                center.z + z));
    }

    /**
     * Upright corner samples hit the supporting wall; only escape an actually intersecting body.
     */
    public static void escapeEmbedded(Player p) {
        AABB box = p.getBoundingBox().deflate(.0000001);
        if (p.noPhysics || p.level().noCollision(p, box)) return;
        for (double distance = .1; distance <= 1.5; distance += .1)
            for (Direction direction : Direction.values()) {
                Vec3 offset = vector(direction).scale(distance);
                if (p.level().noCollision(p, box.move(offset))) {
                    Vec3 axis = vector(direction), v = p.getDeltaMovement();
                    p.setDeltaMovement(v.subtract(axis.scale(v.dot(axis))).add(axis.scale(.1)));
                    return;
                }
            }
    }

    private static boolean supportWithin(Player p, Vec3 lateral, double depth) {
        Vec3 feet = p.position().add(lateral).add(vector(down(p)).scale(depth + .00001));
        return !p.level().noCollision(p, boundsAt(feet, down(p), p.getBbWidth(), depth));
    }

    /** Vanilla sneak ledge retention, expressed in the current ground plane. */
    public static Vec3 sneakEdge(Player p, Vec3 movement, MoverType type) {
        Vec3 v = local(p, movement);
        double step = p.maxUpStep();
        if (p.getAbilities().flying
                || v.y > 0
                || !p.isShiftKeyDown()
                || (type != MoverType.SELF && type != MoverType.PLAYER)
                || !(p.onGround()
                        || p.fallDistance < step
                                && supportWithin(
                                        p, Vec3.ZERO, Math.max(.00001, step - p.fallDistance))))
            return movement;
        double x = v.x, z = v.z;
        while (x != 0 && !supportWithin(p, rotate(new Vec3(x, 0, 0), down(p)), step))
            x = Math.abs(x) <= .05 ? 0 : x - Math.copySign(.05, x);
        while (z != 0 && !supportWithin(p, rotate(new Vec3(0, 0, z), down(p)), step))
            z = Math.abs(z) <= .05 ? 0 : z - Math.copySign(.05, z);
        while (x != 0 && z != 0 && !supportWithin(p, rotate(new Vec3(x, 0, z), down(p)), step)) {
            x = Math.abs(x) <= .05 ? 0 : x - Math.copySign(.05, x);
            z = Math.abs(z) <= .05 ? 0 : z - Math.copySign(.05, z);
        }
        return rotate(new Vec3(x, v.y, z), down(p));
    }

    public static Vec3 local(Player p, Vec3 world) {
        var v =
                rotation(down(p))
                        .conjugate()
                        .transform(
                                new org.joml.Vector3f(
                                        (float) world.x, (float) world.y, (float) world.z));
        return new Vec3(v.x, v.y, v.z);
    }

    public static Vec3 withUp(Player p, Vec3 velocity, double speed) {
        var up = vector(down(p).getOpposite());
        return velocity.subtract(up.scale(velocity.dot(up))).add(up.scale(speed));
    }

    public static void jumpFromGround(Player p) {
        double strength =
                p.getAttributeValue(
                        net.minecraft.world.entity.ai.attributes.Attributes.JUMP_STRENGTH);
        if (p.hasEffect(net.minecraft.world.effect.MobEffects.JUMP))
            strength +=
                    .1
                            * (p.getEffect(net.minecraft.world.effect.MobEffects.JUMP)
                                            .getAmplifier()
                                    + 1);
        Vec3 v = withUp(p, p.getDeltaMovement(), strength);
        if (p.isSprinting())
            v =
                    v.add(
                            rotate(
                                    new Vec3(
                                            -Math.sin(Math.toRadians(p.getYRot())) * .2,
                                            0,
                                            Math.cos(Math.toRadians(p.getYRot())) * .2),
                                    down(p)));
        p.setDeltaMovement(v);
        p.hasImpulse = true;
        p.setOnGround(false);
    }

    public static boolean travel(Player p, Vec3 input) {
        Direction direction = down(p);
        if (direction == Direction.DOWN) return false;
        Vec3 up = vector(direction.getOpposite());
        p.setNoGravity(true);
        boolean flying = p.getAbilities().flying,
                water = p.isInWater(),
                lava = p.isInLava(),
                ground = p.onGround();
        Vec3 wish =
                rotate(
                        new Vec3(input.x, 0, input.z).yRot((float) -Math.toRadians(p.getYRot())),
                        direction);
        if (wish.lengthSqr() > 1) wish = wish.normalize();
        Vec3 v = p.getDeltaMovement();
        double vertical = v.dot(up);
        Vec3 tangent = v.subtract(up.scale(vertical));
        double friction = .91;
        if (flying) {
            double acceleration = p.getAbilities().getFlyingSpeed() * (p.isSprinting() ? 2 : 1);
            v = v.add(wish.scale(acceleration));
        } else if (p.isFallFlying()) {
            Vec3 lv = local(p, v), look = local(p, p.getLookAngle());
            double h = Math.sqrt(look.x * look.x + look.z * look.z),
                    speed = lv.horizontalDistance();
            double pitch = Math.toRadians(p.getXRot()), lift = Math.cos(pitch);
            lift *= lift;
            lv = lv.add(0, -.08 + lift * .06, 0);
            if (lv.y < 0 && h > 0) {
                double a = lv.y * -.1 * lift;
                lv = lv.add(look.x * a / h, a, look.z * a / h);
            }
            if (pitch < 0 && h > 0) {
                double a = speed * -Math.sin(pitch) * .04;
                lv = lv.add(-look.x * a / h, a * 3.2, -look.z * a / h);
            }
            if (h > 0)
                lv = lv.add((look.x / h * speed - lv.x) * .1, 0, (look.z / h * speed - lv.z) * .1);
            v = rotate(lv.multiply(.99, .98, .99), direction);
        } else if (water || lava) {
            v = v.add(wish.scale(.02));
            if (jump(p)) v = v.add(up.scale(.04));
        } else {
            var block =
                    p.level()
                            .getBlockState(
                                    BlockPos.containing(p.position().subtract(up.scale(.2))));
            double slipperiness = block.getBlock().getFriction();
            friction = ground ? slipperiness * .91 : .91;
            double acceleration =
                    ground
                            ? p.getSpeed()
                                    * .21600002
                                    / (slipperiness * slipperiness * slipperiness)
                            : p.isSprinting() ? .026 : .02;
            v = v.add(wish.scale(acceleration));
        }
        Vec3 before = p.position();
        p.move(MoverType.SELF, v);
        Vec3 actual = p.position().subtract(before);
        boolean below = v.dot(up) < 0 && actual.dot(up) > v.dot(up) + .00001;
        p.verticalCollision = Math.abs(actual.dot(up) - v.dot(up)) > .00001;
        p.verticalCollisionBelow = below;
        Vec3 sideWanted = v.subtract(up.scale(v.dot(up))),
                sideMoved = actual.subtract(up.scale(actual.dot(up)));
        p.horizontalCollision = sideWanted.distanceToSqr(sideMoved) > .000001;
        p.setOnGround(below);
        // the holder's own steps (others hear them from the server tick below)
        if (below && !water && !lava && !p.isSpectator() && p.level().isClientSide)
            footsteps(p, sideMoved, up);
        Vec3 after =
                new Vec3(
                        Math.abs(actual.x - v.x) > .00001 ? 0 : v.x,
                        Math.abs(actual.y - v.y) > .00001 ? 0 : v.y,
                        Math.abs(actual.z - v.z) > .00001 ? 0 : v.z);
        if (flying) {
            double n = after.dot(up);
            after = after.subtract(up.scale(n)).scale(.91).add(up.scale(n * .6));
            p.resetFallDistance();
        } else if (!p.isFallFlying()) {
            double gravity =
                    water || lava
                            ? .02
                            : p.getAttributeValue(
                                    net.minecraft.world.entity.ai.attributes.Attributes.GRAVITY);
            if (p.hasEffect(net.minecraft.world.effect.MobEffects.SLOW_FALLING)
                    && after.dot(up) <= 0) gravity = Math.min(gravity, .01);
            if (p.hasEffect(net.minecraft.world.effect.MobEffects.LEVITATION))
                after =
                        withUp(
                                p,
                                after,
                                after.dot(up)
                                        + (.05
                                                                * (p.getEffect(
                                                                                        net
                                                                                                .minecraft
                                                                                                .world
                                                                                                .effect
                                                                                                .MobEffects
                                                                                                .LEVITATION)
                                                                                .getAmplifier()
                                                                        + 1)
                                                        - after.dot(up))
                                                * .2);
            else after = after.subtract(up.scale(gravity));
            double n = after.dot(up), drag = water ? .8 : lava ? .5 : friction;
            after =
                    after.subtract(up.scale(n))
                            .scale(drag)
                            .add(up.scale(n * (water || lava ? drag : .98)));
        }
        p.setDeltaMovement(after);
        // no fall damage while gravity is shifted: landing just ends the fall
        if (below) p.resetFallDistance();
        p.calculateEntityAnimation(false);
        return true;
    }

    /**
     * Vanilla only plays footsteps off the block under the feet in world terms, so on a wall or a
     * ceiling they went silent. Same rhythm as vanilla (a step every 1/0.6 blocks walked), from the
     * block under the feet along this player's own gravity. Runs on both sides like vanilla: the
     * client plays its own steps, the server sends them to everyone else.
     */
    private static void footsteps(Player p, Vec3 moved, Vec3 up) {
        var data = p.getPersistentData();
        float walked = data.getFloat("mm_step") + (float) moved.length() * .6f;
        if (walked < 1) {
            data.putFloat("mm_step", walked);
            return;
        }
        data.putFloat("mm_step", walked - 1);
        BlockPos pos = BlockPos.containing(p.position().subtract(up.scale(.2)));
        var state = p.level().getBlockState(pos);
        if (state.isAir()) return;
        var sound = state.getSoundType(p.level(), pos, p);
        p.playSound(sound.getStepSound(), sound.getVolume() * .15f, sound.getPitch());
    }

    @SubscribeEvent
    public static void tick(PlayerTickEvent.Post ev) {
        if (!(ev.getEntity() instanceof ServerPlayer p)) return;
        var st = Manipulator.stack(p);
        if (down(p) != Direction.DOWN && p.isPassenger()) clearGravity(p);   // riding anything ends a gravity shift
        if (down(p) != Direction.DOWN
                && (!p.isAlive()
                        || !Traits.hasLine(st, Traits.GRAVITIC)
                        || !Traits.has(st, "gravitic.gravity_shift")))
            setGravity(p, Direction.DOWN);
        if (down(p) != Direction.DOWN) {
            // the server only learns positions from the client, so it measures the walk itself
            var data = p.getPersistentData();
            Vec3 last =
                    data.contains("mm_step_x")
                            ? new Vec3(
                                    data.getDouble("mm_step_x"),
                                    data.getDouble("mm_step_y"),
                                    data.getDouble("mm_step_z"))
                            : p.position();
            Vec3 up = vector(down(p).getOpposite()), moved = p.position().subtract(last);
            if (p.onGround() && !p.isInWater() && !p.isSpectator() && moved.lengthSqr() < 4)
                footsteps(p, moved.subtract(up.scale(moved.dot(up))), up);
            data.putDouble("mm_step_x", p.getX());
            data.putDouble("mm_step_y", p.getY());
            data.putDouble("mm_step_z", p.getZ());
        }
        if (p.onGround()) {
            p.getPersistentData().putBoolean("mm_gust_boosted", false);
            p.getPersistentData().putBoolean("mm_gust_impact_boosted", false);
            p.getPersistentData().putBoolean("mm_airjump_used", false);
            p.getPersistentData().putLong("mm_grounded", p.level().getGameTime());
        }
        // Pile Driver: Sneak in the air (pressed there, not held from a charged jump) slams you
        // down; the impact's damage, reach and knockback grow with the height you fell.
        var data = p.getPersistentData();
        double height = p.position().dot(vector(down(p).getOpposite()));
        if (p.onGround()) {
            data.putDouble("mm_air_peak", height);
            data.putBoolean("mm_slam_ready", false);
        } else {
            data.putDouble("mm_air_peak", Math.max(data.getDouble("mm_air_peak"), height));
            if (!p.isShiftKeyDown()) data.putBoolean("mm_slam_ready", true);
        }
        if (p.isShiftKeyDown()
                && !p.onGround()
                && data.getBoolean("mm_slam_ready")
                && Traits.has(st, "pneumatic.ground_slam")
                && !p.isInWater()) {
            data.putBoolean("mm_slamming", true);
            p.setDeltaMovement(p.getDeltaMovement().add(vector(down(p)).scale(.7)));
            p.hurtMarked = true;
        }
        if (p.onGround() && data.getBoolean("mm_slamming")) {
            data.remove("mm_slamming");
            double fell = Math.max(0, data.getDouble("mm_slam_peak") - height);
            // everything grows from almost nothing: a plain jump is a nudge, a big fall a quake
            float damage = (float) Math.min(24, 1 + fell * 1.3);
            double reach = Math.min(8, 2 + fell / 3);
            int phase = fell >= 10 ? 2 : fell >= 4 ? 1 : 0;
            for (var target :
                    p.serverLevel()
                            .getEntitiesOfClass(
                                    net.minecraft.world.entity.LivingEntity.class,
                                    p.getBoundingBox().inflate(reach),
                                    e -> Allegiance.enemy(p, e))) {
                double near = 1 - Math.min(1, target.distanceTo(p) / (reach + 1));
                target.hurt(p.damageSources().playerAttack(p), damage * (float) (.5 + .5 * near));
                // the shock throws them away and up, harder the further you fell
                Vec3 away = target.position().subtract(p.position()).multiply(1, 0, 1);
                if (away.lengthSqr() < 1e-4) away = p.getLookAngle().multiply(1, 0, 1);
                double force = Math.min(2.2, .2 + fell * .09) * (.4 + .6 * near);
                Stasis.impulse(
                        target, away.normalize().scale(force).add(0, Math.min(.6, .08 + fell * .03), 0));
            }
            // the gust blast (which can throw mobs into a fall) only from a real fall
            if (fell >= 4)
                com.ethermod.beam.ProjectileEffects.gust(
                        p.serverLevel(), p, st, p.position(), phase, false);
            p.serverLevel()
                    .playSound(
                            null,
                            p.getX(),
                            p.getY(),
                            p.getZ(),
                            com.ethermod.sound.ModSounds.WORLD_MACE_SMASH_GROUND_HEAVY,
                            net.minecraft.sounds.SoundSource.PLAYERS,
                            Math.min(1.2f, .5f + (float) fell * .05f),
                            .9f);
        }
        if (!data.getBoolean("mm_slamming") || p.onGround())
            data.putDouble("mm_slam_peak", data.getDouble("mm_air_peak"));
    }
}
