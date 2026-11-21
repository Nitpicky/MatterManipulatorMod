package com.ethermod.beam;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side set of other players whose beam is on, with the client game time it started (used for
 * the ramp visuals). Filled from BeamStatePayload. Holds no client classes so the payload handler
 * can reference it on both sides.
 */
public final class RemoteBeams {

    private RemoteBeams() {}

    private static final Map<Integer, Long> ACTIVE = new HashMap<>();
    private static long clientTime;

    public static void set(int entityId, boolean active) {
        if (active) ACTIVE.putIfAbsent(entityId, clientTime);
        else {
            ACTIVE.remove(entityId);
            Pulse s = PULSES.get(entityId);
            if (s != null) s.lock = -1;
        }
    }

    public static Map<Integer, Long> active() {
        return ACTIVE;
    }

    /** Called from the client tick. */
    public static void tick(long gameTime) {
        clientTime = gameTime;
    }

    public static void clear() {
        ACTIVE.clear();
        PULSES.clear();
    }

    /** A beam's last known pressure, its last strike (client time) and where it arced. */
    public static final class Pulse {
        public int level = 1;
        public long strikeAt = Long.MIN_VALUE;
        public List<Integer> arcs = List.of();
        /** Arc bolts get a fresh random shape every strike. */
        public int seed;
        /** The creature Lock-On holds the beam on, or -1. */
        public int lock = -1;
    }

    private static final Map<Integer, Pulse> PULSES = new HashMap<>();

    public static void pulse(com.ethermod.network.payload.BeamPulsePayload p) {
        Pulse s = PULSES.computeIfAbsent(p.entityId(), k -> new Pulse());
        s.level = Math.max(1, p.level());
        s.lock = p.lock();
        if (p.strike()) {
            s.strikeAt = clientTime;
            s.arcs = p.arcs();
            s.seed++;
        }
    }

    /** Pressure level of this player's beam (1 when unknown). */
    public static int level(int entityId) {
        Pulse s = PULSES.get(entityId);
        return s == null ? 1 : s.level;
    }

    public static Pulse pulseOf(int entityId) {
        return PULSES.get(entityId);
    }

    public static long clientTime() {
        return clientTime;
    }

    /** Client game time at which the local player's tank fills (reload animation), or MIN_VALUE. */
    private static long reloadFillAt = Long.MIN_VALUE;

    public static void reload(int ticks) {
        reloadFillAt = clientTime + ticks;
    }

    public static long reloadFillAt() {
        return reloadFillAt;
    }
}
