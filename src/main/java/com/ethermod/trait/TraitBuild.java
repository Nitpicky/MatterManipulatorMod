package com.ethermod.trait;

import com.ethermod.item.ManipulatorStage;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import javax.annotation.Nullable;

/**
 * The trait build stored on the Manipulator: up to 3 slots, each an equipped line plus the chosen
 * trait id for each tier ("" when not chosen yet).
 *
 * <p>Gating follows the stage: Stone has none, Iron opens 1 slot and the Adept tier, Diamond 2
 * slots and Master, Netherite 3 slots and Grandmaster.
 */
public final class TraitBuild {

    private TraitBuild() {}

    public static final int MAX_SLOTS = 3;
    private static final String TAG = "mm_traits";

    public static int openSlots(ItemStack stack) {
        return ManipulatorStage.get(stack).traitTiers();
    }

    public static int openTiers(ItemStack stack) {
        return ManipulatorStage.get(stack).traitTiers();
    }

    private static ListTag slotsTag(ItemStack stack) {
        CompoundTag root =
                stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).getUnsafe();
        return root.getList(TAG, Tag.TAG_COMPOUND);
    }

    /** The line in a slot, or null. Slots above the open count read as empty. */
    @Nullable
    public static ResourceLocation line(ItemStack stack, int slot) {
        if (slot < 0 || slot >= openSlots(stack)) return null;
        ListTag list = slotsTag(stack);
        if (slot >= list.size()) return null;
        String s = list.getCompound(slot).getString("line");
        return s.isEmpty() ? null : ResourceLocation.tryParse(s);
    }

    /** The chosen trait id for a slot and tier, or "" (also "" if the tier isn't open). */
    public static String pick(ItemStack stack, int slot, int tier) {
        if (tier < 0 || tier >= openTiers(stack) || line(stack, slot) == null) return "";
        ListTag picks = slotsTag(stack).getCompound(slot).getList("picks", Tag.TAG_STRING);
        java.util.List<String> saved = new java.util.ArrayList<>();
        for (int i = 0; i < picks.size(); i++) saved.add(picks.getString(i));
        return TraitChoices.resolve(line(stack, slot).getPath(), saved, tier);
    }

    public static int slotOf(ItemStack stack, ResourceLocation lineId) {
        for (int i = 0; i < MAX_SLOTS; i++) if (lineId.equals(line(stack, i))) return i;
        return -1;
    }

    public static boolean isEmpty(ItemStack stack) {
        for (int i = 0; i < MAX_SLOTS; i++) if (line(stack, i) != null) return false;
        return true;
    }

    // ---- Writes (server) -----------------------------------------------------

    public static void setLine(ItemStack stack, int slot, @Nullable ResourceLocation lineId) {
        edit(
                stack,
                list -> {
                    CompoundTag s = new CompoundTag();
                    s.putString("line", lineId == null ? "" : lineId.toString());
                    s.put("picks", new ListTag());
                    list.set(slot, s);
                });
    }

    public static void setPick(ItemStack stack, int slot, int tier, String traitId) {
        String[] normalized = {pick(stack, slot, 0), pick(stack, slot, 1), pick(stack, slot, 2)};
        edit(
                stack,
                list -> {
                    CompoundTag s = list.getCompound(slot);
                    ListTag picks = new ListTag();
                    for (String id : normalized) picks.add(net.minecraft.nbt.StringTag.valueOf(id));
                    while (picks.size() <= tier) picks.add(net.minecraft.nbt.StringTag.valueOf(""));
                    picks.set(tier, net.minecraft.nbt.StringTag.valueOf(traitId));
                    s.put("picks", picks);
                    list.set(slot, s);
                });
    }

    /** The stored line of a slot whether or not the stage opens it yet (commands, build codes). */
    @Nullable
    public static ResourceLocation storedLine(ItemStack stack, int slot) {
        ListTag list = slotsTag(stack);
        if (slot < 0 || slot >= list.size()) return null;
        String s = list.getCompound(slot).getString("line");
        return s.isEmpty() ? null : ResourceLocation.tryParse(s);
    }

    /** The stored picks of a slot (3 entries, "" where none), whether or not their tiers are open. */
    public static String[] storedPicks(ItemStack stack, int slot) {
        String[] out = {"", "", ""};
        ListTag list = slotsTag(stack);
        if (slot < 0 || slot >= list.size()) return out;
        ListTag picks = list.getCompound(slot).getList("picks", Tag.TAG_STRING);
        for (int i = 0; i < Math.min(3, picks.size()); i++) out[i] = picks.getString(i);
        return out;
    }

    /**
     * Writes a slot as given, ignoring stage gating: a slot or tier the stage doesn't open yet keeps
     * its choice and turns on once the Manipulator is upgraded.
     */
    public static void setStored(ItemStack stack, int slot, @Nullable ResourceLocation lineId, String[] picks) {
        edit(
                stack,
                list -> {
                    CompoundTag s = new CompoundTag();
                    s.putString("line", lineId == null ? "" : lineId.toString());
                    ListTag p = new ListTag();
                    for (int i = 0; i < 3; i++)
                        p.add(net.minecraft.nbt.StringTag.valueOf(lineId == null || i >= picks.length ? "" : picks[i]));
                    s.put("picks", p);
                    list.set(slot, s);
                });
    }

    private static void edit(ItemStack stack, java.util.function.Consumer<ListTag> f) {
        CompoundTag root =
                stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        ListTag list = root.getList(TAG, Tag.TAG_COMPOUND);
        while (list.size() < MAX_SLOTS) list.add(new CompoundTag());
        f.accept(list);
        root.put(TAG, list);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
    }
}
