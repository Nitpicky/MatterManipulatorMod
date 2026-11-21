package com.ethermod.beam;

import com.ethermod.trait.Traits;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Server-side beam state of one player, kept between beams.
 *
 * <p>Mining ramp: {@code ticks} counts how long the beam has been held.
 *
 * <p>Pressure: builds while the beam stays on creatures (and on blocks with Momentum), level 2
 * after 1 s, 3 after 2 s, 4 after 3 s with Overload. It belongs to the player, not to a target:
 * switching targets keeps it. Once you stop building it drains stage by stage, each stage faster
 * than the last (the first at normal speed, then x2, x4, x6). Annihilate: a kill holds it for a
 * moment.
 */
public final class BeamState {

    public static final int LEVEL_2_TICKS = 20;
    public static final int LEVEL_3_TICKS = 40;
    public static final int LEVEL_4_TICKS = 60;

    /** Ticks of pressure one stage holds, and how long Annihilate holds it after a kill. */
    private static final int STAGE = 20, KILL_GRACE = 60;

    public int ticks;

    /** Last block the utility beam acted on, so each block is changed once per sweep. */
    public long lastUtilityPos = Long.MIN_VALUE;

    int targetId = -1;
    int targetTicks;

    private boolean overload;

    /** Game time of the last update, the level drain started from, and the kill grace end. */
    private long lastTick = Long.MIN_VALUE;

    private int drainFrom = -1;
    private long graceUntil;
    private float drainDebt, gainDebt;

    /** Drains for the ticks since the last update (between beams too), then notes a kill. */
    private void catchUp(Level level, ItemStack tool, int interval) {
        overload = Traits.has(tool, "plasmic.overload");
        long now = level.getGameTime();
        long idle = lastTick == Long.MIN_VALUE ? 0 : Math.min(400, now - lastTick - interval);
        for (long t = 0; t < idle; t++) drain(now - idle + t);
        lastTick = now;
        int cap = overload ? LEVEL_4_TICKS + STAGE : LEVEL_3_TICKS + STAGE;
        targetTicks = Math.min(targetTicks, cap);
        // Annihilate: the creature you were on just died
        if (targetId >= 0
                && !(level.getEntity(targetId) instanceof LivingEntity le && le.isAlive())) {
            if (Traits.has(tool, "plasmic.annihilate")) graceUntil = now + KILL_GRACE;
            targetId = -1;
        }
    }

    /** One tick without building: the stage drains, faster for every stage already lost. */
    private void drain(long time) {
        if (time < graceUntil || targetTicks <= 0) return;
        if (drainFrom < 0) drainFrom = level();
        int lost = drainFrom - level();
        float rate = lost == 0 ? 1f : 2f * lost; // x1, then x2, x4, x6
        drainDebt += rate;
        int d = (int) drainDebt;
        drainDebt -= d;
        targetTicks = Math.max(0, targetTicks - d);
    }

    private void build(int interval, ItemStack tool) {
        drainFrom = -1;
        drainDebt = 0;
        int cap = overload ? LEVEL_4_TICKS + STAGE : LEVEL_3_TICKS + STAGE;
        // Priming: pressure builds 20% faster
        float gain = Traits.hasCommon(tool, Traits.PLASMIC, 0) ? 1.2f : 1f;
        gainDebt += interval * gain;
        int g = (int) gainDebt;
        gainDebt -= g;
        targetTicks = Math.min(cap, targetTicks + g);
    }

    /**
     * Called each beam tick with the creature hit (or -1). On a creature pressure builds; off one
     * it drains. Returns the level.
     */
    int trackTarget(int entityId, int interval, ItemStack tool, Level level) {
        catchUp(level, tool, interval);
        if (entityId < 0) {
            for (int t = 0; t < interval; t++) drain(level.getGameTime() - interval + 1 + t);
            return level();
        }
        targetId = entityId;
        build(interval, tool);
        return level();
    }

    /** Momentum: mining builds the same pressure. Returns the level. */
    int trackMining(int interval, ItemStack tool, Level level) {
        catchUp(level, tool, interval);
        targetId = -1;
        build(interval, tool);
        return level();
    }

    public int level() {
        if (overload && targetTicks >= LEVEL_4_TICKS) return 4;
        if (targetTicks >= LEVEL_3_TICKS) return 3;
        if (targetTicks >= LEVEL_2_TICKS) return 2;
        return 1;
    }

    /**
     * Ticks until the beam can strike again. Pressure sets the cadence and the hit: a fresh beam
     * ticks slowly and lightly, a full one fast and hard (the same climb Momentum gives mining).
     */
    int hitCooldown;

    /** Last pressure level and strike told to clients (-1: nothing sent yet). */
    int sentLevel = -1;

    /** The creature Lock-On holds, as last told to clients (-1: none). */
    int sentLock = -1;

    /** A new beam: tell clients the pressure again. */
    public void resync() {
        sentLevel = -1;
        sentLock = -1;
    }

    /** Ticks between strikes per pressure level: 2/s, ~3/s, 4/s, 5/s. */
    public static int hitInterval(int level) {
        return switch (level) {
            case 4 -> 4;
            case 3 -> 5;
            case 2 -> 7;
            default -> 10;
        };
    }

    /** Damage per strike per pressure level, x1 / x1.5 / x2 / x2.5 (with the cadence: x1 to x6). */
    static float levelMultiplier(int level) {
        return 1f + 0.5f * (Math.max(1, level) - 1);
    }

    /** Momentum: mining speed per pressure level, x1 / x1.5 / x2 / x2.5. */
    static float miningMultiplier(int level) {
        return 1f + 0.5f * (Math.max(1, level) - 1);
    }
}
