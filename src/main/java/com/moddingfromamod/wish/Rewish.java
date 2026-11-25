package com.moddingfromamod.wish;

import com.moddingfromamod.marshal.WatchRow;
import com.moddingfromamod.wishbuilder.BuilderAllowlist;
import com.moddingfromamod.wishbuilder.BuilderEdit;
import com.moddingfromamod.wishbuilder.BuilderFlow;
import com.moddingfromamod.wishbuilder.BuilderMake;
import com.moddingfromamod.wishbuilder.BuilderMenus;
import com.moddingfromamod.wishbuilder.BuilderSettings;
import com.moddingfromamod.wishbuilder.BuilderSpecStore;
import com.moddingfromamod.wishbuilder.BuilderTree;
import com.moddingfromamod.wishbuilder.LibraryPresets;
import com.moddingfromamod.wishbuilder.MagicianCreature;
import com.moddingfromamod.wishbuilder.RewishReader;
import com.moddingfromamod.ui.PickPage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>Typed re-wishes, answered at once</b> (). A typed wish is
 * read by {@link RewishReader} against his own things before it is routed: when the reader is SURE, it is done here and
 * now, by the paths the game already has — an EDIT through the Builder's own edit (a Builder make, {@link BuilderMake#modify})
 * or its Magician-creature mode, a COPY by replaying the thing's recipe or the starter's button, a COMMAND by
 * the existing {@code /moddy} command. Anything it is not sure of goes on exactly as typed (the Apprentice or the
 * Magician): never half of a wish. Nothing here grants what the Builder could not, and no model reads the words.
 *
 * <p>On by default; off for a world by {@code wish.rewish} = off. Each answer is an action-log {@code rewish} record (what it read and did,
 * never the words). A Wish Crafter's wish that names one of his things in a way the Builder cannot change is told so,
 * whatever the knob: before, it opened a page of NEW makes.
 */
public final class Rewish {

    private Rewish() {
    }

    /** The knob that turns instant re-wishes off for a server. */
    public static final String KNOB = "wish.rewish";

    /** What a Crafter hears when his words change one of his things in a way the Builder cannot (he has no Magician). */
    public static final String CANT = "The Builder can't change that about it. Try a change it has, like bigger, a colour, or a power!";

    /** What a Crafter hears when the Builder CAN make his change but instant re-wishes are off on this world. */
    public static final String CHANGE_IT_HERE = "Open your wish watch, press 📚 My wishes, press that thing, and press "
            + "\"Change it in the Builder\" to change it!";

    private static final Map<BuilderAllowlist, RewishReader> READERS = new ConcurrentHashMap<>();

    /** Is it on for this server? On by default since SURE was graded right 60 of 60 times (2026-11-16). */
    public static boolean on() {
        Boolean t = forTest;
        return t != null ? t : !"off".equals(com.moddingfromamod.config.ModdySettings.get(KNOB).orElse("on"));
    }

    private static volatile Boolean forTest;

    /** Test seam: on, off, or null for the knob. */
    public static void onForTest(Boolean on) {
        forTest = on;
    }

    static RewishReader reader(BuilderAllowlist allow) {
        return READERS.computeIfAbsent(allow, a -> new RewishReader(BuilderTree.of(a, LibraryPresets.SHIPPED)));
    }

    /** One of his things, as the watch knows it. */
    record Mine(String id, String kind, String name, String recipeId, long at) {
    }

    static List<Mine> his(ServerPlayer p) {
        List<Mine> out = new ArrayList<>();
        for (String row : WatchSnapshot.creationsFor(p.getUUID().toString())) {
            try {
                WatchRow.Creation c = WatchRow.Creation.decode(row);
                if (!c.inFlight()) {
                    out.add(new Mine(c.id(), c.kind(), c.name(), c.recipeId(), c.updatedAt()));
                }
            } catch (RuntimeException ignored) {
                // a row that does not read is not one of his things here
            }
        }
        return out;
    }

    /**
     * The typed wish, read before it is routed. True when it was answered here (an instant re-wish, or a Crafter told
     * the Builder cannot); false to let it go on as typed. Server thread.
     */
    public static boolean tryAnswer(ServerPlayer p, String text, WishSource source, WishRoute.Hint hint) {
        // the re-wish reader is LOCAL ONLY, like the Apprentice: it runs inside the guard (marshal/LocalOnly).
        return com.moddingfromamod.marshal.LocalOnly.during(() -> tryAnswerLocally(p, text, source, hint));
    }

    /**
     * Would {@link #tryAnswer} take these words (a SURE change it does at once, or a CLOSE one it offers)? The same
     * reading with nothing done, said or logged: what the core asks before it asks the world's charge, so the charge is
     * told the words will be answered locally. A SURE answer that finds no instant way still passes them on; the core
     * then asks the charge again.
     */
    public static boolean wouldRead(ServerPlayer p, String text, WishSource source, WishRoute.Hint hint) {
        return com.moddingfromamod.marshal.LocalOnly.during(() -> {
            Reading r = reading(p, text, source, hint);
            return r != null && on() && (r.answer().band() == RewishReader.Band.SURE
                    || r.answer().band() == RewishReader.Band.CLOSE);
        });
    }

    /** His words read against his things, with what the reading needed; null when they are never read here. */
    private record Reading(boolean crafter, BuilderSettings settings, List<Mine> mine, RewishReader.Answer answer) {
    }

    private static Reading reading(ServerPlayer p, String text, WishSource source, WishRoute.Hint hint) {
        if (p == null || source != WishSource.TYPED || hint == WishRoute.Hint.MAGICIAN || WishIntent.isQuestion(text)) {
            return null;   // a press, a preset, a question, or "the Magician, please": never read here
        }
        boolean crafter = CrafterService.isCrafter(p);
        if (!on() && !crafter) {
            return null;
        }
        BuilderSettings settings = BuilderSettings.forPlayer(p.getUUID());
        if (!settings.enabled()) {
            return null;
        }
        List<Mine> mine = his(p);
        List<RewishReader.Thing> things = new ArrayList<>();
        for (Mine m : mine) {
            things.add(new RewishReader.Thing(m.id(), m.kind(), m.name()));
        }
        String last = mine.stream().max(Comparator.comparingLong(Mine::at)).map(Mine::id).orElse(null);
        List<String> starters = new ArrayList<>();
        for (var pr : com.moddingfromamod.library.WishLibrary.asPresets()) {
            starters.add(pr.templateId());
        }
        return new Reading(crafter, settings, mine, reader(settings.allow()).read(text, things, starters, last));
    }

    private static boolean tryAnswerLocally(ServerPlayer p, String text, WishSource source, WishRoute.Hint hint) {
        Reading r = reading(p, text, source, hint);
        if (r == null) {
            return false;
        }
        boolean crafter = r.crafter();
        BuilderSettings settings = r.settings();
        List<Mine> mine = r.mine();
        RewishReader.Answer a = r.answer();
        String outcome = !on() ? "passed"
                : a.band() == RewishReader.Band.SURE ? act(p, text, settings, a, mine)
                : a.band() == RewishReader.Band.CLOSE ? offer(p, text, settings, a, mine, crafter) : "passed";
        if (outcome.equals("passed") && crafter && a.intent() == RewishReader.Intent.EDIT && a.label() != null) {
            // A Crafter has no Magician to pass it to, and his words were never a NEW make: say what he can do.
            p.sendSystemMessage(Component.literal(a.band() == RewishReader.Band.NOTHING ? CANT : CHANGE_IT_HERE)
                    .withStyle(ChatFormatting.AQUA));
            outcome = "crafter-told";
        }
        com.moddingfromamod.agent.ActionLog.rewish(p.getUUID().toString(), a.intent().name(), a.band().name(), a.verb(),
                a.ops().stream().map(BuilderEdit.Op::slot).toList(), outcome);
        return !outcome.equals("passed");
    }

    /** Do the SURE answer the instant way; "passed" when there is no such way for this thing (it goes on as typed). */
    private static String act(ServerPlayer p, String text, BuilderSettings settings, RewishReader.Answer a,
                              List<Mine> mine) {
        UUID who = p.getUUID();
        Mine thing = a.label() == null ? null : mine.stream().filter(m -> m.id().equals(a.label())).findFirst().orElse(null);
        switch (a.intent()) {
            case COMMAND -> {
                if (a.verb().equals("go")) {
                    // "take me to it" is what his last wish made; "take me to my castle" is that thing, found where it
                    // stands (a building, or a creature near enough). Not found: his words go on as typed.
                    if (thing != null && thing.id().equals(lastId(mine))
                            && WishGo.go(p, "") == WishGo.Result.WENT) {
                        return "command:go";
                    }
                    return thing != null && WishGo.goThing(p, thing.id(), thing.name()) == WishGo.Result.WENT
                            ? "command:go-thing" : "passed";
                }
                String cmd = switch (a.verb()) {
                    case "undo" -> ChildCommands.undo("");
                    case "stop" -> ChildCommands.stop("");
                    default -> null;
                };
                if (cmd == null) {
                    return "passed";
                }
                p.getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack(), cmd.substring(1));
                return "command:" + a.verb();
            }
            case COPY -> {
                if (a.label() != null && a.label().startsWith("starter:")) {
                    String id = a.label().substring("starter:".length());
                    for (var pr : com.moddingfromamod.library.WishLibrary.asPresets()) {
                        if (pr.templateId().equals(id)) {
                            WishService.submit(p, pr.wishText(), WishSource.PRESET, pr.templateId());
                            return "copy:starter";
                        }
                    }
                    return "passed";
                }
                if (thing == null || thing.recipeId().isBlank()) {
                    return "passed";
                }
                p.getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack(),
                        ChildCommands.again(thing.recipeId()).substring(1));
                return "copy:his";
            }
            case EDIT -> {
                if (thing == null) {
                    return "passed";
                }
                String builderId = BuilderSpecStore.idFor(who.toString(), thing.id(), thing.name());
                if (builderId != null) {
                    // ask the editor first, so a change it refuses is never announced: his words go on as typed
                    BuilderMake.Edited pre = BuilderMake.edited(p.getUUID(), settings.allow(), builderId, a.ops());
                    if (pre.refusal() != null) {
                        return "passed";
                    }
                    BuilderMake.modify(p, settings.allow(), builderId, a.ops()).thenAccept(r -> {
                        if (r.outcome() == BuilderMake.Outcome.BUSY || r.outcome() == BuilderMake.Outcome.BANK_EMPTY
                            || r.outcome() == BuilderMake.Outcome.NOT_HERE) {
                            p.getServer().execute(() -> p.sendSystemMessage(Component.literal(why(r))));
                        } else if (r.outcome() == BuilderMake.Outcome.REFUSED) {
                            // the editor refused it after all (a step past the Builder's end): his words go on as typed
                            p.getServer().execute(() -> WishService.submit(p, text, WishSource.TYPED, WishRoute.Hint.MAGICIAN));
                        }
                    });
                    p.sendSystemMessage(Component.literal(changing(thing.name(), a.ops(), pre.spec().name()))
                            .withStyle(ChatFormatting.AQUA));
                    return "edit:builder";
                }
                String buildingId = BuilderMenus.magicianBuildingId(who, thing.id());
                if (buildingId != null) {
                    var bcalls = magicianBuildingCalls(buildingId, a.ops());
                    if (bcalls == null || bcalls.isEmpty()) {
                        return "passed";
                    }
                    said(p, BuilderMake.magicianBuilding(p, BuilderMenus.magicianBuilding(buildingId), bcalls));
                    p.sendSystemMessage(Component.literal("✨ Changing your " + thing.name() + "…").withStyle(ChatFormatting.AQUA));
                    return "edit:magician-building";
                }
                var item = BuilderMenus.magicianItem(who, thing.id());
                if (item != null) {
                    // its colours or its look, made at once; its power or strength goes on to the Magician
                    var icalls = com.moddingfromamod.wishbuilder.MagicianItem.calls(item, a.ops());
                    if (icalls == null) {
                        return "passed";
                    }
                    said(p, BuilderMake.magicianItem(p, item, icalls));
                    p.sendSystemMessage(Component.literal(changing(thing.name(), a.ops())).withStyle(ChatFormatting.AQUA));
                    return "edit:magician-item";
                }
                String magicianId = BuilderMenus.magicianCreatureId(who, thing.id());
                if (magicianId == null) {
                    return "passed";
                }
                var calls = magicianCalls(settings.allow(), magicianId, a.ops());
                if (calls == null || calls.isEmpty()) {
                    return "passed";
                }
                said(p, BuilderMake.magician(p, BuilderMenus.magicianThing(magicianId), calls));
                p.sendSystemMessage(Component.literal("✨ Changing your " + thing.name() + "…").withStyle(ChatFormatting.AQUA));
                return "edit:magician";
            }
            default -> {
                return "passed";
            }
        }
    }

    /**
     * what he hears while his thing is changed: an item's new look and power by name ("✨ Turning your Crown into
     * a Top Hat…", "✨ Giving your Wand Super speed…"), anything else as before ("✨ Changing your Sparky…").
     */
    /** what he hears, and the thing's new name when the change renamed it ("… it's your Healing Donut now"). */
    static String changing(String name, List<BuilderEdit.Op> ops, String newName) {
        String said = changing(name, ops);
        return newName == null || newName.isBlank() || newName.equalsIgnoreCase(name) ? said
                : said + " it's your " + newName.strip() + " now";
    }

    static String changing(String name, List<BuilderEdit.Op> ops) {
        String look = null;
        String power = null;
        for (BuilderEdit.Op o : ops) {
            if (o.slot().equals(com.moddingfromamod.wishbuilder.BuilderLooks.SLOT)) {
                var l = com.moddingfromamod.wishbuilder.BuilderLooks.look(o.value());
                look = l == null ? null : l.label();
            } else if (o.slot().equals("power")) {
                var w = com.moddingfromamod.wishbuilder.BuilderItemPowers.power(o.value().substring(o.value().indexOf(':') + 1));
                power = w == null ? null : w.label();
            }
        }
        boolean other = ops.stream().anyMatch(o -> !o.slot().equals(com.moddingfromamod.wishbuilder.BuilderLooks.SLOT)
                && !o.slot().equals("power"));
        if (other || look == null && power == null) {
            return "✨ Changing your " + name + "…";
        }
        if (look != null) {
            look = withA(look);
        }
        if (power != null && power.startsWith("Just for fun")) {
            return look != null ? "✨ Turning your " + name + " into " + look + ", just for fun…"
                    : "✨ Taking the power off your " + name + "…";
        }
        if (look != null && power != null) {
            return "✨ Turning your " + name + " into " + look + " with " + power + "…";
        }
        return look != null ? "✨ Turning your " + name + " into " + look + "…" : "✨ Giving your " + name + " " + power + "…";
    }

    /** "a Crown", "an Ice Pop", "a pair of Headphones", "some Fries" (one rule, {@code English.withArticle}). */
    static String withA(String look) {
        return com.moddingfromamod.wishbuilder.English.withArticle(look, 1);
    }

    /** The words of an offer tile: what pressing it does to that thing. */
    static String tileWords(RewishReader.Answer a, String name) {
        if (a.intent() == RewishReader.Intent.COPY) {
            return "Another " + name;
        }
        List<String> what = new ArrayList<>();
        for (BuilderEdit.Op o : a.ops()) {
            String v = o.value() == null ? "" : o.value().substring(o.value().indexOf(':') + 1).replace('_', ' ');
            what.add(switch (o.slot()) {
                case "size" -> o.by() > 0 ? "bigger" : "smaller";
                case "speed" -> o.by() > 0 ? "faster" : "slower";
                case "strength" -> o.by() > 0 ? "stronger" : "weaker";
                case "scheme" -> v + " colours";
                case "temper" -> switch (v) {
                    case "pet" -> "follows you";
                    case "stays" -> "stays put";
                    default -> v;
                };
                case "itemlook" -> {   //: the look's own name ("a Top Hat")
                    var l = com.moddingfromamod.wishbuilder.BuilderLooks.look(o.value());
                    yield withA(l == null ? v : l.label());
                }
                case "power" -> {
                    var w = com.moddingfromamod.wishbuilder.BuilderItemPowers.power(v.replace(' ', '_'));
                    yield w == null ? v : w.label();
                }
                default -> (o.verb() == BuilderEdit.Verb.REMOVE ? "no " : "") + v;
            });
        }
        return name + ": " + String.join(", ", what);
    }

    /** The tile on an offer page that sends his words on as typed: the Magician's, or a Crafter's Apprentice. */
    static final String ELSE_ID = "rewish:else";

    /**
     * A CLOSE answer: a page of the things it may mean, one tile each saying what it would do,
     * and the way on as typed. Pressing a thing does it as if the reader had been sure of that one. Only an edit or a copy
     * is offered; anything else goes on as typed.
     */
    private static String offer(ServerPlayer p, String text, BuilderSettings settings, RewishReader.Answer a,
                                List<Mine> mine, boolean crafter) {
        if (a.intent() != RewishReader.Intent.EDIT && a.intent() != RewishReader.Intent.COPY || a.candidates().isEmpty()) {
            return "passed";
        }
        List<PickPage.Tile> tiles = new ArrayList<>();
        Map<String, String> names = new java.util.HashMap<>();
        for (Mine m : mine) {
            names.put(m.id(), m.name());
        }
        for (var pr : com.moddingfromamod.library.WishLibrary.asPresets()) {
            names.put("starter:" + pr.templateId(), pr.label());
        }
        for (String c : a.candidates().subList(0, Math.min(5, a.candidates().size()))) {
            tiles.add(new PickPage.Tile("rewish:" + c, a.intent() == RewishReader.Intent.COPY ? "minecraft:nether_star"
                    : "minecraft:crafting_table", tileWords(a, names.getOrDefault(c, c)), List.of("Moddy does it right now"), false));
        }
        tiles.add(new PickPage.Tile(ELSE_ID, "minecraft:writable_book", crafter ? "✨ Something else" : "✪ Ask the Magician",
                List.of(crafter ? "The Apprentice reads your words" : "The Magician reads your words"), false));
        com.moddingfromamod.ui.MenuKit.pick(p, "rewish", PickPage.of("Did you mean…", tiles, false), (pl, id) -> {
            pl.closeContainer();
            if (id.startsWith("rewish:") && !id.equals(ELSE_ID)) {
                String label = id.substring("rewish:".length());
                RewishReader.Answer sure = new RewishReader.Answer(a.intent(), label, a.ops(), a.verb(),
                        RewishReader.Band.SURE, List.of(), List.of(label));
                String done = act(pl, text, settings, sure, his(pl));
                com.moddingfromamod.agent.ActionLog.rewish(pl.getUUID().toString(), a.intent().name(), "CLOSE-PICKED",
                        a.verb(), a.ops().stream().map(BuilderEdit.Op::slot).toList(), done);
                if (done.equals("passed")) {
                    WishService.submit(pl, text, WishSource.TYPED, WishRoute.Hint.MAGICIAN);
                }
            } else if (id.equals(ELSE_ID)) {
                WishService.submit(pl, text, WishSource.TYPED, WishRoute.Hint.MAGICIAN);
            }
        });
        return "offered";
    }

    /** What he is told when a change could not start (the Builder's own menus say the same). */
    static String why(BuilderMake.Result r) {
        return switch (r.outcome()) {
            case BUSY -> "Your last change is still on its way. Wish it again in a moment!";
            case BANK_EMPTY, NOT_HERE -> r.detail();
            default -> "The Builder couldn't change that this time.";
        };
    }

    private static void said(ServerPlayer p, java.util.concurrent.CompletableFuture<BuilderMake.Result> f) {
        f.thenAccept(r -> {
            if (r.outcome() == BuilderMake.Outcome.BUSY || r.outcome() == BuilderMake.Outcome.BANK_EMPTY
                            || r.outcome() == BuilderMake.Outcome.NOT_HERE
                    || r.outcome() == BuilderMake.Outcome.REFUSED) {
                p.getServer().execute(() -> p.sendSystemMessage(Component.literal(why(r))));
            }
        });
    }

    private static String lastId(List<Mine> mine) {
        return mine.stream().max(Comparator.comparingLong(Mine::at)).map(Mine::id).orElse(null);
    }

    /** slice 2: a Magician building's change, from his words: its colours and what goes outside it; null otherwise. */
    static List<com.moddingfromamod.wishbuilder.BuilderLowering.Call> magicianBuildingCalls(String id, List<BuilderEdit.Op> ops) {
        var thing = BuilderMenus.magicianBuilding(id);
        if (thing == null) {
            return null;
        }
        String scheme = "as_is";
        List<String> outside = new ArrayList<>();
        for (BuilderEdit.Op o : ops) {
            switch (o.slot()) {
                case "scheme" -> scheme = o.value();
                case "outside" -> {
                    if (o.verb() == BuilderEdit.Verb.REMOVE) {
                        return null;   // what was there is the Magician's: the Builder only adds outside it
                    }
                    outside.add(o.value());
                }
                default -> {
                    return null;   // its size, or anything else: the Magician's
                }
            }
        }
        return com.moddingfromamod.wishbuilder.MagicianBuilding.calls(thing, scheme, outside);
    }

    /**
     * The Builder's Magician-creature pages, pressed as his words say: a power on or off, a temper, a size step.
     * Null when an operator is not one those pages offer (it goes on as typed).
     */
    static List<com.moddingfromamod.wishbuilder.BuilderLowering.Call> magicianCalls(BuilderAllowlist allow, String id,
                                                                                    List<BuilderEdit.Op> ops) {
        MagicianCreature.Thing thing = BuilderMenus.magicianThing(id);
        if (thing == null) {
            return null;
        }
        BuilderFlow f = BuilderFlow.editingMagician(allow, LibraryPresets.SHIPPED, thing);
        int size = MagicianCreature.NOW;
        String scheme = "as_is";
        for (BuilderEdit.Op o : ops) {
            switch (o.slot()) {
                case "size" -> size += o.by();
                case "scheme" -> {
                    if (thing.parts().size() == 0) {
                        return null;
                    }
                    scheme = o.value();
                }
                case "powers", "temper" -> {
                    boolean on = f.page().tiles().stream().anyMatch(t -> t.id().equals(o.value()) && t.selected());
                    boolean want = o.verb() != BuilderEdit.Verb.REMOVE;
                    if (f.page().tiles().stream().noneMatch(t -> t.id().equals(o.value()))) {
                        return null;
                    }
                    if (on != want || o.slot().equals("temper") && !on) {
                        if (!f.press(o.value()).say().isEmpty()) {
                            return null;
                        }
                    }
                }
                default -> {
                    return null;
                }
            }
        }
        if (size < 1 || size > 4) {
            return null;
        }
        f.press(PickPage.NEXT_ID);
        return f.magicianCalls(Map.of("size", String.valueOf(size), "scheme", scheme));
    }
}
