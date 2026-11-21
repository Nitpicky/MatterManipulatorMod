package com.ethermod.event;

import com.ethermod.item.ManipulatorColors;
import com.ethermod.manipulator.Manipulator;
import com.ethermod.trait.BuildCode;
import com.ethermod.trait.ModuleUnlocks;
import com.ethermod.trait.TraitBuild;
import com.ethermod.trait.TraitEnchants;
import com.ethermod.trait.TraitLine;
import com.ethermod.trait.TraitLines;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * Commands that set a Manipulator up directly (operators, modpacks, sharing builds):
 *
 * <ul>
 *   <li>{@code set_module <players> <slot> <module|none> [A|B] [A|B] [A|B]}
 *   <li>{@code set_build <players> <code>} and {@code export_build [player]}, codes from {@link BuildCode}
 *   <li>{@code set_color <players> <body hex|default> [beam hex|default]}
 * </ul>
 *
 * Slots and tiers the Manipulator's stage doesn't open yet keep their choice and turn on when it is
 * upgraded. A Module set this way is unlocked for the player.
 */
final class BuildCommands {
    private BuildCommands() {}

    private static final DynamicCommandExceptionType BAD_MODULE =
            new DynamicCommandExceptionType(v -> Component.translatable("command.ethermod.bad_module", v));
    private static final DynamicCommandExceptionType BAD_CHOICE =
            new DynamicCommandExceptionType(v -> Component.translatable("command.ethermod.bad_choice", v));
    private static final DynamicCommandExceptionType BAD_CODE =
            new DynamicCommandExceptionType(v -> Component.translatable("command.ethermod.bad_code", v));
    private static final DynamicCommandExceptionType BAD_COLOUR =
            new DynamicCommandExceptionType(v -> Component.translatable("command.ethermod.bad_colour", v));

    static ArgumentBuilder<CommandSourceStack, ?> setModule() {
        var c3 = Commands.argument("choice3", StringArgumentType.word()).suggests(BuildCommands::ab).executes(c -> setModule(c, 3));
        var c2 = Commands.argument("choice2", StringArgumentType.word()).suggests(BuildCommands::ab).executes(c -> setModule(c, 2)).then(c3);
        var c1 = Commands.argument("choice1", StringArgumentType.word()).suggests(BuildCommands::ab).executes(c -> setModule(c, 1)).then(c2);
        return Commands.literal("set_module")
                .then(Commands.argument("targets", EntityArgument.players())
                        .then(Commands.argument("slot", IntegerArgumentType.integer(1, TraitBuild.MAX_SLOTS))
                                .then(Commands.argument("module", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(moduleNames(c), b))
                                        .executes(c -> setModule(c, 0))
                                        .then(c1))));
    }

    static ArgumentBuilder<CommandSourceStack, ?> setBuild() {
        return Commands.literal("set_build")
                .then(Commands.argument("targets", EntityArgument.players())
                        .then(Commands.argument("code", StringArgumentType.word())
                                .executes(BuildCommands::setBuild)));
    }

    static ArgumentBuilder<CommandSourceStack, ?> exportBuild() {
        return Commands.literal("export_build")
                .executes(c -> export(c, c.getSource().getPlayerOrException()))
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(c -> export(c, EntityArgument.getPlayer(c, "target"))));
    }

    static ArgumentBuilder<CommandSourceStack, ?> setColor() {
        return Commands.literal("set_color")
                .then(Commands.argument("targets", EntityArgument.players())
                        .then(Commands.argument("body", StringArgumentType.string())
                                .executes(c -> setColor(c, false))
                                .then(Commands.argument("beam", StringArgumentType.string())
                                        .executes(c -> setColor(c, true)))));
    }

    // ---- set_module ----------------------------------------------------------

    private static int setModule(CommandContext<CommandSourceStack> c, int choices) throws CommandSyntaxException {
        int slot = IntegerArgumentType.getInteger(c, "slot") - 1;
        String name = StringArgumentType.getString(c, "module").toLowerCase(Locale.ROOT);
        int[] picks = new int[choices];
        for (int i = 0; i < choices; i++) {
            String v = StringArgumentType.getString(c, "choice" + (i + 1));
            picks[i] = BuildCode.choice(v);
            if (picks[i] < 0) throw BAD_CHOICE.create(v);
        }
        ResourceLocation id = null;
        TraitLine line = null;
        if (!name.equals("none")) {
            id = moduleId(c, name);
            line = TraitLines.get(c.getSource().registryAccess(), id).orElseThrow();
        }
        var players = EntityArgument.getPlayers(c, "targets");
        for (ServerPlayer p : players) {
            ItemStack st = Manipulator.stack(p);
            apply(st, slot, id, line, picks);
            done(p, st);
        }
        feedback(c, players.size());
        return players.size();
    }

    /** Puts a Module and its choices in a slot, moving it out of any other slot. */
    private static void apply(ItemStack st, int slot, ResourceLocation id, TraitLine line, int[] picks) {
        if (id != null) {
            for (int s = 0; s < TraitBuild.MAX_SLOTS; s++)
                if (s != slot && id.equals(TraitBuild.storedLine(st, s))) TraitBuild.setStored(st, s, null, new String[0]);
            ModuleUnlocks.unlock(st, id);
        }
        String[] ids = {"", "", ""};
        if (line != null)
            for (int t = 0; t < Math.min(picks.length, TraitLine.TIERS); t++) ids[t] = line.tier(t).get(picks[t]).id();
        TraitBuild.setStored(st, slot, id, ids);
    }

    // ---- set_build / export_build ---------------------------------------------

    private static int setBuild(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String code = StringArgumentType.getString(c, "code");
        List<BuildCode.Slot> slots;
        try {
            slots = BuildCode.parse(code);
        } catch (IllegalArgumentException e) {
            throw BAD_CODE.create(e.getMessage());
        }
        var access = c.getSource().registryAccess();
        for (var s : slots) if (TraitLines.get(access, moduleId(c, s.module())).isEmpty()) throw BAD_MODULE.create(s.module());
        var players = EntityArgument.getPlayers(c, "targets");
        for (ServerPlayer p : players) {
            ItemStack st = Manipulator.stack(p);
            for (int s = 0; s < TraitBuild.MAX_SLOTS; s++) TraitBuild.setStored(st, s, null, new String[0]);
            for (var s : slots) {
                var id = moduleId(c, s.module());
                apply(st, s.slot(), id, TraitLines.get(access, id).orElseThrow(), s.choices());
            }
            done(p, st);
        }
        feedback(c, players.size());
        return players.size();
    }

    private static int export(CommandContext<CommandSourceStack> c, ServerPlayer p) {
        String code = code(Manipulator.stack(p), c);
        Component shown =
                Component.literal(code.isEmpty() ? "-" : code)
                        .withStyle(s -> s.withColor(net.minecraft.ChatFormatting.AQUA)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, code))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("chat.copy.click"))));
        c.getSource().sendSuccess(() -> Component.translatable("command.ethermod.build_code", p.getDisplayName(), shown), false);
        return 1;
    }

    /** The build code of a Manipulator, stored choices included. */
    static String code(ItemStack st, CommandContext<CommandSourceStack> c) {
        StringBuilder b = new StringBuilder();
        var access = c.getSource().registryAccess();
        for (int s = 0; s < TraitBuild.MAX_SLOTS; s++) {
            ResourceLocation id = TraitBuild.storedLine(st, s);
            if (id == null) continue;
            Character letter = BuildCode.LETTERS.get(id.getPath());
            TraitLine line = TraitLines.get(access, id).orElse(null);
            if (letter == null || line == null) continue;
            b.append(s + 1).append(letter);
            String[] picks = TraitBuild.storedPicks(st, s);
            for (int t = 0; t < TraitLine.TIERS; t++) {
                int choice = -1;
                for (int k = 0; k < 2; k++) if (line.tier(t).get(k).id().equals(picks[t]) && !picks[t].isEmpty()) choice = k;
                if (choice < 0) break;
                b.append(choice == 0 ? 'A' : 'B');
            }
        }
        return b.toString();
    }

    // ---- set_color -----------------------------------------------------------

    private static int setColor(CommandContext<CommandSourceStack> c, boolean withBeam) throws CommandSyntaxException {
        int body = colour(StringArgumentType.getString(c, "body"));
        int beam = withBeam ? colour(StringArgumentType.getString(c, "beam")) : -2;
        var players = EntityArgument.getPlayers(c, "targets");
        for (ServerPlayer p : players) {
            ItemStack st = Manipulator.stack(p);
            ManipulatorColors.setBody(st, body);
            if (beam != -2) ManipulatorColors.setBeam(st, beam < 0 ? ManipulatorColors.DEFAULT_BEAM : beam);
            Manipulator.sync(p);
        }
        feedback(c, players.size());
        return players.size();
    }

    /** "#RRGGBB", "RRGGBB" or "default" (-1). */
    private static int colour(String v) throws CommandSyntaxException {
        if (v.equalsIgnoreCase("default")) return -1;
        String h = v.startsWith("#") ? v.substring(1) : v;
        if (!h.matches("[0-9a-fA-F]{6}")) throw BAD_COLOUR.create(v);
        return Integer.parseInt(h, 16);
    }

    // ---- shared ----------------------------------------------------------------

    private static List<String> moduleNames(CommandContext<CommandSourceStack> c) {
        var names = new java.util.ArrayList<String>();
        names.add("none");
        for (var e : TraitLines.all(c.getSource().registryAccess())) names.add(e.getKey().getPath());
        return names;
    }

    private static ResourceLocation moduleId(CommandContext<CommandSourceStack> c, String name) throws CommandSyntaxException {
        var id = name.contains(":") ? ResourceLocation.tryParse(name) : ResourceLocation.fromNamespaceAndPath("ethermod", name);
        if (id == null || TraitLines.get(c.getSource().registryAccess(), id).isEmpty()) throw BAD_MODULE.create(name);
        return id;
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> ab(
            CommandContext<CommandSourceStack> c, com.mojang.brigadier.suggestion.SuggestionsBuilder b) {
        return SharedSuggestionProvider.suggest(List.of("A", "B"), b);
    }

    private static void done(ServerPlayer p, ItemStack st) {
        TraitEnchants.refresh(st, p.registryAccess());
        Manipulator.sync(p);
    }

    private static void feedback(CommandContext<CommandSourceStack> c, int n) {
        c.getSource().sendSuccess(() -> Component.translatable("command.ethermod.set", n), true);
    }
}
