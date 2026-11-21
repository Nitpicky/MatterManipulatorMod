package com.ethermod.network.payload;

import com.ethermod.EtherMod;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Server → the beaming player and everyone watching: the beam's pressure level changed, or it just
 * struck (with the creatures the strike arced to), or Lock-On caught or let go of a creature
 * ({@code lock}, -1 when none). Drives the beam's look and sound.
 */
public record BeamPulsePayload(
        int entityId, int level, boolean strike, List<Integer> arcs, int lock)
        implements CustomPacketPayload {

    public static final Type<BeamPulsePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EtherMod.MODID, "beam_pulse"));

    public static final StreamCodec<ByteBuf, BeamPulsePayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,
                    BeamPulsePayload::entityId,
                    ByteBufCodecs.VAR_INT,
                    BeamPulsePayload::level,
                    ByteBufCodecs.BOOL,
                    BeamPulsePayload::strike,
                    ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(8)),
                    BeamPulsePayload::arcs,
                    ByteBufCodecs.VAR_INT.map(i -> i - 1, i -> i + 1),
                    BeamPulsePayload::lock,
                    BeamPulsePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
