package com.ethermod.network;

import com.ethermod.beam.RemoteBeams;
import com.ethermod.item.OrbMode;
import com.ethermod.manipulator.Manipulator;
import com.ethermod.manipulator.ManipulatorData;
import com.ethermod.network.payload.BeamActivePayload;
import com.ethermod.network.payload.BeamStatePayload;
import com.ethermod.network.payload.ManipulatorSyncPayload;
import com.ethermod.network.payload.OrbModePayload;
import com.ethermod.network.payload.SummonTogglePayload;
import com.ethermod.network.payload.TraitPayloads;

import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class ModNetwork {

    private static final String VERSION = "20";

    private ModNetwork() {}

    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar reg = event.registrar(VERSION);
        reg.playToServer(
                com.ethermod.network.payload.ModulePayloads.Motion.TYPE,
                com.ethermod.network.payload.ModulePayloads.Motion.CODEC,
                ServerPayloadHandlers::motion);
        reg.playToClient(
                com.ethermod.network.payload.ModulePayloads.WaterCurrent.TYPE,
                com.ethermod.network.payload.ModulePayloads.WaterCurrent.CODEC,
                (p, ctx) ->
                        ctx.enqueueWork(
                                () -> {
                                    com.ethermod.SideHooks.get().waterCurrent(p);
                                }));
        reg.playToClient(
                com.ethermod.network.payload.ModulePayloads.SoulFlame.TYPE,
                com.ethermod.network.payload.ModulePayloads.SoulFlame.CODEC,
                (p, ctx) ->
                        ctx.enqueueWork(
                                () -> {
                                    com.ethermod.SideHooks.get().soulFlame(p);
                                }));
        reg.playToClient(
                com.ethermod.network.payload.ModulePayloads.Gravity.TYPE,
                com.ethermod.network.payload.ModulePayloads.Gravity.CODEC,
                (p, ctx) ->
                        ctx.enqueueWork(
                                () ->
                                        com.ethermod.SideHooks.get().gravity(
                                                p.entity(), p.down(), p.start())));
        reg.playToServer(
                BeamActivePayload.TYPE, BeamActivePayload.CODEC, ServerPayloadHandlers::beamActive);
        reg.playToServer(
                TraitPayloads.Equip.TYPE,
                TraitPayloads.Equip.CODEC,
                ServerPayloadHandlers::equip);
        reg.playToServer(
                TraitPayloads.Pick.TYPE,
                TraitPayloads.Pick.CODEC,
                ServerPayloadHandlers::pick);
        reg.playToServer(
                TraitPayloads.StageUpgrade.TYPE,
                TraitPayloads.StageUpgrade.CODEC,
                ServerPayloadHandlers::stageUpgrade);
        reg.playToServer(
                TraitPayloads.Paint.TYPE,
                TraitPayloads.Paint.CODEC,
                ServerPayloadHandlers::paint);
        reg.playToServer(
                TraitPayloads.PickColour.TYPE,
                TraitPayloads.PickColour.CODEC,
                ServerPayloadHandlers::pickColour);
        reg.playToServer(
                TraitPayloads.Loadout.TYPE,
                TraitPayloads.Loadout.CODEC,
                ServerPayloadHandlers::loadout);
        reg.playToServer(
                SummonTogglePayload.TYPE,
                SummonTogglePayload.CODEC,
                ServerPayloadHandlers::summonToggle);
        reg.playToServer(OrbModePayload.TYPE, OrbModePayload.CODEC, ServerPayloadHandlers::orbMode);
        reg.playToClient(
                com.ethermod.network.payload.BeamPulsePayload.TYPE,
                com.ethermod.network.payload.BeamPulsePayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> RemoteBeams.pulse(payload)));
        reg.playToClient(
                com.ethermod.network.payload.DryFirePayload.TYPE,
                com.ethermod.network.payload.DryFirePayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> com.ethermod.SideHooks.get().dryFire()));
        reg.playToClient(
                com.ethermod.network.payload.StasisForcePayload.TYPE,
                com.ethermod.network.payload.StasisForcePayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> com.ethermod.SideHooks.get().stasisForce(payload.entity(), payload.x(), payload.y(), payload.z())));
        reg.playToClient(
                com.ethermod.network.payload.StasisPayload.TYPE,
                com.ethermod.network.payload.StasisPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> com.ethermod.SideHooks.get().stasisMark(payload.entity(), payload.ticks())));
        reg.playToClient(
                com.ethermod.network.payload.PhotonicGlowPayload.TYPE,
                com.ethermod.network.payload.PhotonicGlowPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> com.ethermod.SideHooks.get().photonicGlow(payload.entity(), payload.on())));
        reg.playToClient(
                com.ethermod.network.payload.ReloadPayload.TYPE,
                com.ethermod.network.payload.ReloadPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> RemoteBeams.reload(payload.ticks())));
        reg.playToClient(
                BeamStatePayload.TYPE,
                BeamStatePayload.CODEC,
                (payload, ctx) ->
                        ctx.enqueueWork(
                                () -> RemoteBeams.set(payload.entityId(), payload.active())));
        reg.playToClient(
                com.ethermod.network.payload.LeftClickFxPayload.TYPE,
                com.ethermod.network.payload.LeftClickFxPayload.CODEC,
                (payload, ctx) ->
                        ctx.enqueueWork(
                                () -> com.ethermod.beam.LeftClickFxData.accept(payload)));
        reg.playToClient(
                ManipulatorSyncPayload.TYPE,
                ManipulatorSyncPayload.CODEC,
                ModNetwork::handleManipulatorSync);
        reg.playToServer(
                com.ethermod.network.payload.ModulePayloads.AirJump.TYPE,
                com.ethermod.network.payload.ModulePayloads.AirJump.CODEC,
                ServerPayloadHandlers::airJump);
    }

    public static void sendOrbMode(OrbMode mode) {
        PacketDistributor.sendToServer(new OrbModePayload(mode.ordinal()));
    }

    /** Client side. Only touches common classes, so it is safe to register on both sides. */
    private static void handleManipulatorSync(ManipulatorSyncPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(
                () -> {
                    Player player = ctx.player();
                    ManipulatorData data = Manipulator.data(player);
                    data.setStack(payload.stack());
                    data.setSummoned(payload.summoned());
                });
    }

    public static void send(
            net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    public static void sendSummonToggle(boolean summon) {
        PacketDistributor.sendToServer(new SummonTogglePayload(summon));
    }

    public static void sendBeamActive(boolean active) {
        PacketDistributor.sendToServer(new BeamActivePayload(active));
    }
}
