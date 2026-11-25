package com.moddingfromamod.wishbuilder;

import com.moddingfromamod.ui.MenuKit;
import com.moddingfromamod.ui.PickPage;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The Wish Builder, in the game</b> (B0b). Opened from the watch's ✦ tile or {@code /moddy builder};
 * every screen is one of {@link MenuKit}'s two forms, so Java and Bedrock get the measured mapping.
 * {@link BuilderFlow} decides what comes next; this class only opens it and runs the make.
 *
 * <ul>
 *   <li>{@code /moddy builder} — open it for yourself (anyone, on a world whose Builder is on).</li>
 *   <li>{@code /moddyop builder open <player>} — operators, RCON and the gates.</li>
 *   <li>{@code /moddy builder make <token> …} — the tune dialog's Make; answers only the player whose flow
 *       holds that token, at the tune step.</li>
 *   <li>{@code /moddy builder edit <id>} — "change one thing": reopen a make he keeps
 *       ({@link BuilderSpecStore}), every pick already made, and change that same thing.</li>
 * </ul>
 * Log lines (what the gates read): {@code builder page <step> <player>}, {@code builder press <id>
 * <player>}, {@code builder tune <player>}, then {@code BuilderMake}'s {@code builder make …}.
 */
public final class BuilderMenus {

    private static final Logger LOGGER = LoggerFactory.getLogger("moddingfromamod/builder");

    private static final Map<UUID, BuilderFlow> FLOWS = new ConcurrentHashMap<>();

    private BuilderMenus() {
    }

    /**
     * {@code /moddy builder …}: the child's own verbs (— a child sees only {@code /wish} and
     * {@code /moddy}; {@code WishChatGameTest.aChildSeesOnlyWishAndModdy} enumerates the tree). Added to
     * {@code /moddy} by {@code WishCommands}.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> childBranch() {
        var b = Commands.literal(com.moddingfromamod.wish.ChildCommands.BUILDER).executes(ctx -> {
            ServerPlayer p = ctx.getSource().getPlayer();
            return p != null && open(p) ? 1 : 0;
        });
        b.then(Commands.literal("make")
                .then(Commands.argument("token", StringArgumentType.word())
                        .executes(ctx -> make(ctx.getSource().getPlayer(), StringArgumentType.getString(ctx, "token"), ""))
                        .then(Commands.argument("values", StringArgumentType.greedyString())
                                .executes(ctx -> make(ctx.getSource().getPlayer(),
                                        StringArgumentType.getString(ctx, "token"),
                                        StringArgumentType.getString(ctx, "values"))))));
        b.then(Commands.literal("find")   //: the 🔍 Find's box sends his words here
                .then(Commands.argument("token", StringArgumentType.word())
                        .executes(ctx -> find(ctx.getSource().getPlayer(), StringArgumentType.getString(ctx, "token"), ""))
                        .then(Commands.argument("words", StringArgumentType.greedyString())
                                .executes(ctx -> find(ctx.getSource().getPlayer(),
                                        StringArgumentType.getString(ctx, "token"),
                                        StringArgumentType.getString(ctx, "words"))))));
        b.then(Commands.literal("magician")   //: the Builder on a creature the Magician made
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayer();
                            return p != null && editMagician(p, StringArgumentType.getString(ctx, "id")) ? 1 : 0;
                        })));
        b.then(Commands.literal("edit")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayer();
                            return p != null && edit(p, StringArgumentType.getString(ctx, "id")) ? 1 : 0;
                        })));
        return b;
    }

    /** {@code /moddyop builder open <player>}: operators, RCON and the gates. Added by {@code OpsCommands}. */
    public static LiteralArgumentBuilder<CommandSourceStack> opBranch() {
        return Commands.literal(com.moddingfromamod.wish.ChildCommands.BUILDER)
                .then(Commands.literal("open")
                        .then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
                            ServerPlayer p = EntityArgument.getPlayer(ctx, "player");
                            boolean opened = open(p);
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "builder " + (opened ? "opened" : "is off") + " for " + p.getGameProfile().getName()), false);
                            return opened ? 1 : 0;
                        })));
    }

    /** The line a chat link runs to reopen a kept make. */
    public static String editCommand(String id) {
        return "/" + com.moddingfromamod.wish.ChildCommands.MODDY + " " + com.moddingfromamod.wish.ChildCommands.BUILDER
                + " edit " + id;
    }

    /** Open the Builder's first page for {@code p}; false when this world's Builder is off. */
    public static boolean open(ServerPlayer p) {
        return open(p, BuilderSettings.forPlayer(p.getUUID()));
    }

    /**
     * …with these settings rather than the world's — the Bedrock batch's staging, which must not switch the
     * Builder on for the whole server while the watch cases beside it count the watch's buttons.
     */
    public static boolean open(ServerPlayer p, BuilderSettings settings) {
        if (!settings.enabled()) {
            p.sendSystemMessage(Component.literal("The Wish Builder isn't open in this world yet."));
            return false;
        }
        BuilderFlow flow = new BuilderFlow(settings.allow(), LibraryPresets.SHIPPED);
        FLOWS.put(p.getUUID(), flow);
        show(p, flow);
        return true;
    }

    /** "Change one thing": reopen the make {@code p} keeps as {@code id}. False when there is none. */
    public static boolean edit(ServerPlayer p, String id) {
        return edit(p, id, BuilderSettings.forPlayer(p.getUUID()));
    }

    public static boolean edit(ServerPlayer p, String id, BuilderSettings settings) {
        if (!settings.enabled()) {
            p.sendSystemMessage(Component.literal("The Wish Builder isn't open in this world yet."));
            return false;
        }
        BuilderSpecStore.Made kept = BuilderSpecStore.get(p.getUUID().toString(), id);
        BuilderSpec spec = null;
        try {
            spec = kept == null ? null : kept.read();
        } catch (RuntimeException e) {
            LOGGER.warn("builder edit {}: its kept spec does not read: {}", id, e.toString());
        }
        if (spec == null) {
            p.sendSystemMessage(Component.literal("The Builder doesn't remember making that."));
            return false;
        }
        if (kept.magicianTouched()) {
            // a wish changed it since; reopening the old picks would undo that change without a word.
            p.sendSystemMessage(Component.literal(MAGICIAN_TOUCHED));
            LOGGER.info("builder edit {} refused for {}: a wish changed it since", id, p.getGameProfile().getName());
            return false;
        }
        BuilderFlow flow = BuilderFlow.editing(settings.allow(), LibraryPresets.SHIPPED, id, spec);
        FLOWS.put(p.getUUID(), flow);
        LOGGER.info("builder edit {} {}", id, p.getGameProfile().getName());
        show(p, flow);
        return true;
    }

    /** the command a Magician creature's "✦ Change it ✪" ({@link MagicianCreature#VERB_LABEL}) runs. */
    public static String magicianCommand(String id) {
        return "/" + com.moddingfromamod.wish.ChildCommands.MODDY + " " + com.moddingfromamod.wish.ChildCommands.BUILDER
                + " magician " + id;
    }

    /**
     * {@code id} when it is a creature HE owns that the Builder did not make (or a wish has changed since), so the
     * Builder may change what it can of it; else null. Never a library one (owned by nobody: it is everyone's).
     */
    public static String magicianCreatureId(java.util.UUID owner, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        com.moddingfromamod.species.Species sp = com.moddingfromamod.species.SpeciesRegistry.get(id);
        if (sp == null) {
            return null;
        }
        if (sp.ownerId() == null || !sp.ownerId().equals(owner.toString())) {
            // Somebody else's, or nobody's: a library creature is everyone's (ForkPolicy: a curated one is owned by
            // nobody), so changing it in place would change it for every child. A change of it is a fork, the Magician's.
            return null;
        }
        BuilderSpecStore.Made kept = BuilderSpecStore.get(owner.toString(), id);
        return kept != null && !kept.magicianTouched() ? null : id;
    }

    /** what the Builder reads of a Magician creature. Null when there is none. */
    public static MagicianCreature.Thing magicianThing(String id) {
        com.moddingfromamod.species.Species sp = com.moddingfromamod.species.SpeciesRegistry.get(id);
        if (sp == null) {
            return null;
        }
        java.util.List<String> powers = new java.util.ArrayList<>();
        sp.powers().forEach(pw -> powers.add(pw.name().toLowerCase(java.util.Locale.ROOT)));
        // Its parts as the Builder's colour schemes read them (name, colour, size), from its own sculpt (slice 2).
        com.google.gson.JsonArray parts = new com.google.gson.JsonArray();
        for (var part : com.moddingfromamod.runtime.ModelRepository.sculptedParts(id)) {
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("name", part.name());
            o.addProperty("colour", String.format("#%06X", part.colour() & 0xFFFFFF));
            com.google.gson.JsonArray from = new com.google.gson.JsonArray();
            com.google.gson.JsonArray to = new com.google.gson.JsonArray();
            for (int i = 0; i < 3; i++) {
                from.add(part.from()[i]);
                to.add(part.to()[i]);
            }
            o.add("from", from);
            o.add("to", to);
            parts.add(o);
        }
        return new MagicianCreature.Thing(sp.id(), sp.name(), sp.scale(), powers, parts);
    }

    /**
     * slice 2: {@code id} when it is a standing building HE owns that the Builder did not make (or a wish has
     * changed since); else null. Never one nobody owns (the library's).
     */
    public static String magicianBuildingId(java.util.UUID owner, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        com.moddingfromamod.builder.BuildPlan plan = com.moddingfromamod.builder.BuildRegistry.get(id);
        if (plan == null || plan.ownerId() == null || !plan.ownerId().equals(owner.toString())
                || !com.moddingfromamod.builder.BuildRegistry.standing(id)) {
            return null;
        }
        BuilderSpecStore.Made kept = BuilderSpecStore.get(owner.toString(), id);
        return kept != null && !kept.magicianTouched() ? null : id;
    }

    /** slice 2: what the Builder reads of a Magician building: its stored plan. Null when there is none. */
    public static MagicianBuilding.Thing magicianBuilding(String id) {
        com.moddingfromamod.builder.BuildPlan plan = com.moddingfromamod.builder.BuildRegistry.get(id);
        return plan == null ? null : new MagicianBuilding.Thing(plan.id(), plan.name(), plan.toJson());
    }

    /**
     * his own item the Magician made, by id, for a sure typed change; null when it is not his, not stored, or a
     * Builder make (the Builder's own editor takes those).
     */
    public static MagicianItem.Thing magicianItem(java.util.UUID owner, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        com.moddingfromamod.item.ItemSpecStore.Spec spec = com.moddingfromamod.item.ItemSpecStore.get(id);
        if (spec == null) {
            spec = com.moddingfromamod.item.ItemDesignId.designFor(id);   // the watch knows an item by its catalogue key
        }
        if (spec == null || spec.ownerId() == null || !spec.ownerId().equals(owner.toString())) {
            return null;   // somebody else's or the library's: a change of it is a fork, the Magician's
        }
        BuilderSpecStore.Made kept = BuilderSpecStore.get(owner.toString(), id);
        if (kept != null && !kept.magicianTouched()) {
            return null;
        }
        return new MagicianItem.Thing(spec.id(), spec.name(), spec.baseItem() == null ? "" : spec.baseItem());
    }

    /** his Magician creature or building by id, whichever it is; null when the Builder may change neither. */
    public static String magicianThingId(java.util.UUID owner, String id) {
        String c = magicianCreatureId(owner, id);
        return c != null ? c : magicianBuildingId(owner, id);
    }

    /** slice 1: open the Builder on a creature the Magician made, with the note that it changes only part of it. */
    public static boolean editMagician(ServerPlayer p, String id) {
        BuilderSettings settings = BuilderSettings.forPlayer(p.getUUID());
        if (!settings.enabled()) {
            p.sendSystemMessage(Component.literal("The Wish Builder isn't open in this world yet."));
            return false;
        }
        MagicianCreature.Thing thing = magicianCreatureId(p.getUUID(), id) == null ? null : magicianThing(id);
        if (thing == null) {
            MagicianBuilding.Thing building = magicianBuildingId(p.getUUID(), id) == null ? null : magicianBuilding(id);
            if (building == null) {
                p.sendSystemMessage(Component.literal("The Builder can't change that one."));
                return false;
            }
            BuilderFlow flow = BuilderFlow.editingMagicianBuilding(settings.allow(), LibraryPresets.SHIPPED, building);
            FLOWS.put(p.getUUID(), flow);
            LOGGER.info("builder magician building edit {} {}", id, p.getGameProfile().getName());
            p.sendSystemMessage(Component.literal(MagicianCreature.SAID).withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE));
            show(p, flow);
            return true;
        }
        BuilderFlow flow = BuilderFlow.editingMagician(settings.allow(), LibraryPresets.SHIPPED, thing);
        FLOWS.put(p.getUUID(), flow);
        LOGGER.info("builder magician edit {} {}", id, p.getGameProfile().getName());
        p.sendSystemMessage(Component.literal(MagicianCreature.SAID).withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE));
        show(p, flow);
        return true;
    }

    /** What he is told when the Builder cannot change a thing a wish has changed since. */
    static final String MAGICIAN_TOUCHED = "Moddy changed that one with a wish after the Builder made it, so the Builder "
            + "can't change it any more. You can make a new one in the Builder, or wish for the change!";

    /**
     * An Apprentice tile, pressed: MAKE what it says, at once, for the Builder's half a wish. No
     * tune dialog first, because a wish has none; the spec is the one the tune dialog would have started on (the
     * size, count and colours his words named, Moddy's name for it). When it stands he is told it can be changed in
     * the Builder from its page on his watch, free once until his next wish ({@link FreeEdit}). False when the pick
     * cannot be pressed on this world's menus (said, never made short of a piece).
     */
    public static boolean makeApprentice(ServerPlayer p, BuilderSettings settings, BuilderTree tree, Apprentice.Pick pick) {
        return makeApprentice(p, settings, tree, pick, null);
    }

    /**
     * …named {@code name} (the name a Wish Magician gave in his words), when it passes the input lexicon and the
     * text sweep the agent's own names pass (SafetyTextSweep, make_item's name). One that does not is dropped without a
     * word, and the make keeps the Builder's own name.
     */
    public static boolean makeApprentice(ServerPlayer p, BuilderSettings settings, BuilderTree tree, Apprentice.Pick pick,
                                         String name) {
        p.closeContainer();
        if (!settings.enabled()) {
            p.sendSystemMessage(Component.literal("The Wish Builder isn't open in this world yet."));
            return false;
        }
        BuilderSpec spec;
        try {
            Apprentice.Pick full = Apprentice.filled(tree, pick);
            spec = ApprenticeMake.pressed(settings.allow(), LibraryPresets.SHIPPED, full)
                    .shelf(shelfNames(p.getUUID().toString(), null)).spec(ApprenticeMake.values(full));
        } catch (RuntimeException e) {
            LOGGER.warn("apprentice pick {} does not press on this world's Builder: {}", Apprentice.key(pick), e.toString());
            p.sendSystemMessage(Component.literal("That one didn't work. Try the Wish Builder on your watch."));
            return false;
        }
        if (name != null && !name.isBlank() && nameIsSafe(name)) {
            spec = spec.withName(name);
        }
        LOGGER.info("apprentice make {} {}{}", Apprentice.key(pick), p.getGameProfile().getName(),
                spec.name().equals(name) ? " (his name)" : "");
        UUID who = p.getUUID();
        java.util.function.Consumer<BuilderSpec> seam = APPRENTICE_MADE_FOR_TEST.remove(who);
        if (seam != null) {
            seam.accept(spec);
            return true;
        }
        p.sendSystemMessage(Component.literal(BuilderWords.making(spec, false)));
        BuilderMake.make(p, spec, settings.allow(), null, true).thenAccept(r -> p.getServer().execute(() -> {
            ServerPlayer now = p.getServer().getPlayerList().getPlayer(who);
            if (now == null) {
                return;
            }
            String say = switch (r.outcome()) {
                case REFUSED -> "The Builder can't make that here: " + r.detail();
                case BANK_EMPTY, NOT_HERE -> r.detail();
                case BUSY -> "Your last Builder make is still on its way.";
                default -> "";
            };
            if (!say.isEmpty()) {
                now.sendSystemMessage(Component.literal(say));
            } else if (FreeEdit.isFree(who, r.madeId())) {
                now.sendSystemMessage(Component.literal(ApprenticeWords.CHANGE_IT_FREE)
                        .withStyle(net.minecraft.ChatFormatting.AQUA));
            }
        }));
        return true;
    }

    /** a name he typed may be kept: no lexicon hit, and make_item's own name sweep passes it. */
    public static boolean nameIsSafe(String name) {
        if (com.moddingfromamod.safety.SafetyLexicon.shared().check(name).isPresent()) {
            return false;
        }
        com.google.gson.JsonObject args = new com.google.gson.JsonObject();
        args.addProperty("name", name);
        return !com.moddingfromamod.safety.SafetyTextSweep.sweep("make_item", args, "").refuses();
    }

    /**
     * Test seam: the next Apprentice make for this player hands its spec here instead of making it (a GameTest proves
     * what a tile makes without the real tools; {@code BuilderMakeGameTest} proves the make itself).
     */
    private static final Map<UUID, java.util.function.Consumer<BuilderSpec>> APPRENTICE_MADE_FOR_TEST = new ConcurrentHashMap<>();

    public static void apprenticeMadeForTest(UUID player, java.util.function.Consumer<BuilderSpec> seam) {
        APPRENTICE_MADE_FOR_TEST.put(player, seam);
    }

    public static BuilderFlow flowOf(ServerPlayer p) {
        return FLOWS.get(p.getUUID());
    }

    static void show(ServerPlayer p, BuilderFlow flow) {
        if (flow.asking()) {
            p.closeContainer();
            LOGGER.info("builder find box {}", p.getGameProfile().getName());
            MenuKit.tune(p, flow.findBox());
            return;
        }
        if (flow.step() == BuilderFlow.Step.TUNE) {
            p.closeContainer();
            LOGGER.info("builder tune {} {}", flow.kind().id(), p.getGameProfile().getName());
            com.moddingfromamod.ui.TuneDialog dialog = flow.tune();
            if (com.moddingfromamod.wish.MakeGate.anyWorld() && flow.draft() != null) {
                // #14: as far as this world's limits go (the make it holds, asked once; the Make fits it anyway)
                BuilderAllowlist allow = BuilderSettings.forPlayer(p.getUUID()).allow();
                var v = com.moddingfromamod.wish.MakeGate.ask(MakeFacts.of(allow.capped(flow.draft())), askAbout(p, flow));
                dialog = BuilderPageFit.tune(flow, dialog, v.limits());
            }
            MenuKit.tune(p, dialog);
            return;
        }
        if (flow.wantsView()) {
            flow.seeing(BuilderView.sample(p));   // §1: once, when the From page first opens
        }
        if (flow.wantsRecent()) {
            flow.recentTo(recentTo(p.getUUID().toString()));
        }
        LOGGER.info("builder page {} {}", flow.step().name().toLowerCase(java.util.Locale.ROOT), p.getGameProfile().getName());
        PickPage page = flow.page();
        if (com.moddingfromamod.wish.MakeGate.anyWorld()) {
            // #14: this world's view of every tile, compared against facts worked out off the server thread
            BuilderAllowlist allow = BuilderSettings.forPlayer(p.getUUID()).allow();
            page = BuilderPageFit.draw(flow, page, BuilderPageFacts.forWorld(allow), world(p, flow));
            if (BuilderPageFit.gettingReady(page)) {
                String state = BuilderPageFacts.stateKey(flow);
                UUID who = p.getUUID();
                net.minecraft.server.MinecraftServer server = p.level().getServer();
                BuilderPageFacts.need(allow, flow.snapshot(), () -> server.execute(() -> {
                    ServerPlayer now = server.getPlayerList().getPlayer(who);
                    if (now != null && FLOWS.get(who) == flow && flow.step() != BuilderFlow.Step.TUNE
                            && BuilderPageFacts.stateKey(flow).equals(state)) {
                        show(now, flow);   // ready: drawn again, fitted
                    }
                }));
            }
        }
        MenuKit.pick(p, "builder", page, (sp, id) -> press(sp, id));
    }

    /** What this world is asked about a make on {@code p}'s Builder pages: a Builder make, his. */
    static com.moddingfromamod.wish.WishAsk askAbout(ServerPlayer p, BuilderFlow flow) {
        return BuilderMake.askOf(p.getUUID(), BuilderMake.TEXT_PREFIX.strip(), flow.editOf(), false);
    }

    /**
     * This world's answer about a make's facts on {@code p}'s Builder pages (#14), through the one gate a make passes
     * ({@code MakeGate.ask}): kept within its limits, locked with its line, or dropped (hidden, or more than any fit
     * could make it).
     */
    public static BuilderPageFit.World world(ServerPlayer p, BuilderFlow flow) {
        com.moddingfromamod.wish.WishAsk ask = askAbout(p, flow);
        return facts -> {
            var v = com.moddingfromamod.wish.MakeGate.ask(facts, ask);
            if (v.makes()) {
                return new BuilderPageFit.Judged(BuilderPageFit.Seen.KEEP, "", v.limits());
            }
            return v.locked() && !v.line().equals(com.moddingfromamod.wish.MakeVerdict.TOO_MUCH)
                    ? new BuilderPageFit.Judged(BuilderPageFit.Seen.LOCK, v.line())
                    : new BuilderPageFit.Judged(BuilderPageFit.Seen.DROP, v.line());
        };
    }

    /** One press on a pick page. Public for the GameTest, which presses as a client would. */
    public static void press(ServerPlayer p, String id) {
        BuilderFlow flow = FLOWS.get(p.getUUID());
        if (flow == null) {
            p.closeContainer();
            return;
        }
        LOGGER.info("builder press {} {}", id, p.getGameProfile().getName());
        if (com.moddingfromamod.wish.MakeGate.anyWorld() && id != null && !id.startsWith("@")) {
            // #14: a tile this world locks or does not offer is never pressed into a make, whatever page sent it
            var index = BuilderPageFacts.forWorld(BuilderSettings.forPlayer(p.getUUID()).allow());
            var tiles = index == null ? null : index.page(BuilderPageFacts.stateKey(flow));
            BuilderPageFit.Judged j = tiles == null ? new BuilderPageFit.Judged(BuilderPageFit.Seen.LOCK, BuilderPageFit.GETTING_READY)
                    : BuilderPageFit.judge(tiles.get(id), world(p, flow));
            if (!j.makes()) {
                p.sendSystemMessage(Component.literal(j.line().isEmpty() ? com.moddingfromamod.wish.MakeVerdict.HIDDEN_LINE : j.line()));
                show(p, flow);
                return;
            }
        }
        BuilderFlow.Outcome o = flow.press(id);
        if (o.close()) {
            FLOWS.remove(p.getUUID());
            p.closeContainer();
            return;
        }
        if (!o.say().isEmpty()) {
            p.sendSystemMessage(Component.literal(o.say()));
        }
        show(p, flow);
    }

    /**
     * the 🔍 Find's Submit. His words go through the input safety lexicon as a wish's do (a term it blocks is
     * refused in the wish box's words, CHILD_SAFETY.md), and are never logged or kept: only how many results came back.
     * A blank box is a cancel (TuneDialog.Words), back to the page. Public for the GameTest, which sends as a client would.
     */
    public static int find(ServerPlayer p, String token, String raw) {
        if (p == null) {
            return 0;
        }
        BuilderFlow flow = FLOWS.get(p.getUUID());
        if (flow == null || !flow.token.equals(token) || !flow.asking()) {
            LOGGER.info("builder find refused for {}: no open Find box with token {}", p.getGameProfile().getName(), token);
            return 0;
        }
        String typed = com.moddingfromamod.wish.WishText.fromDialog(
                com.moddingfromamod.ui.TuneDialog.Words.unmark(raw == null ? "" : raw)).strip();
        if (typed.isEmpty()) {
            flow.stopAsking();
            show(p, flow);
            return 0;
        }
        var verdict = com.moddingfromamod.safety.ChildSafetyConfig.grading()
                ? com.moddingfromamod.safety.SafetyLexicon.shared().check(typed)
                : java.util.Optional.<com.moddingfromamod.safety.SafetyVerdict>empty();
        if (com.moddingfromamod.safety.ChildSafetyConfig.enforcing() && verdict.isPresent()
                && verdict.get().overall() == com.moddingfromamod.safety.SafetyVerdict.Band.CLEARLY) {
            com.moddingfromamod.agent.ActionLog.safety("", "input", verdict.get(),
                    com.moddingfromamod.safety.SafetyInputGate.Act.BLOCK.name(), 0L, 0.0,
                    "lexicon", "", "", 0, false, false, p.getStringUUID());
            flow.stopAsking();
            p.sendSystemMessage(Component.literal(com.moddingfromamod.safety.SafetyWords.sayBlocked()));
            show(p, flow);
            return 0;
        }
        flow.find(typed);
        LOGGER.info("builder find {} results for {}", flow.found() == null ? 0 : flow.found().size(),
                p.getGameProfile().getName());
        show(p, flow);
        return 1;
    }

    static int make(ServerPlayer p, String token, String values) {
        if (p == null) {
            return 0;
        }
        BuilderFlow flow = FLOWS.get(p.getUUID());
        if (flow == null || !flow.token.equals(token) || flow.step() != BuilderFlow.Step.TUNE) {
            // An old dialog, or a Make nobody opened: nothing is made, and nothing is charged.
            LOGGER.info("builder make refused for {}: no flow at the tune step with token {}", p.getGameProfile().getName(), token);
            return 0;
        }
        FLOWS.remove(p.getUUID());
        if (flow.magician() != null) {
            return makeMagician(p, flow, values);
        }
        if (flow.magicianBuilding() != null) {
            return makeMagicianBuilding(p, flow, values);
        }
        BuilderSpec spec;
        try {
            spec = flow.shelf(shelfNames(p.getUUID().toString(), flow.editOf())).spec(flow.values(values));
        } catch (RuntimeException e) {
            p.sendSystemMessage(Component.literal("That didn't work. Open the Builder and try again."));
            LOGGER.warn("builder make for {}: bad values '{}': {}", p.getGameProfile().getName(), values, e.toString());
            return 0;
        }
        p.sendSystemMessage(Component.literal(BuilderWords.making(spec, flow.editOf() != null)));   //
        BuilderMake.make(p, spec, BuilderSettings.forPlayer(p.getUUID()).allow(), flow.editOf()).thenAccept(r -> {
            String say = switch (r.outcome()) {
                case REFUSED -> "The Builder can't make that here: " + r.detail();
                case BANK_EMPTY, NOT_HERE -> r.detail();
                case BUSY -> "Your last Builder make is still on its way.";
                default -> "";
            };
            if (!say.isEmpty()) {
                p.getServer().execute(() -> p.sendSystemMessage(Component.literal(say)));
            }
        });
        return 1;
    }

    /** slice 2: the Make of a Builder change on a Magician building: its colours and what goes outside it. */
    private static int makeMagicianBuilding(ServerPlayer p, BuilderFlow flow, String values) {
        java.util.List<BuilderLowering.Call> calls;
        try {
            calls = flow.magicianBuildingCalls(flow.values(values));
        } catch (RuntimeException e) {
            p.sendSystemMessage(Component.literal("That didn't work. Open the Builder and try again."));
            LOGGER.warn("builder magician building change for {}: bad values '{}': {}", p.getGameProfile().getName(), values, e.toString());
            return 0;
        }
        String name = flow.magicianBuilding().name().isEmpty() ? "building" : flow.magicianBuilding().name();
        if (calls.isEmpty()) {
            p.sendSystemMessage(Component.literal("Nothing was changed, so your " + name + " is just as it was."));
            return 0;
        }
        p.sendSystemMessage(Component.literal("✦ Changing your " + name + "…"));
        BuilderMake.magicianBuilding(p, flow.magicianBuilding(), calls).thenAccept(r -> {
            String say = switch (r.outcome()) {
                case REFUSED -> "The Builder can't change that here: " + r.detail();
                case BANK_EMPTY, NOT_HERE -> r.detail();
                case BUSY -> "Your last Builder make is still on its way.";
                default -> "";
            };
            if (!say.isEmpty()) {
                p.getServer().execute(() -> p.sendSystemMessage(Component.literal(say)));
            }
        });
        return 1;
    }

    /** the Make of a Builder change on a Magician creature: only what changed is sent; nothing changed, nothing made. */
    private static int makeMagician(ServerPlayer p, BuilderFlow flow, String values) {
        java.util.List<BuilderLowering.Call> calls;
        try {
            calls = flow.magicianCalls(flow.values(values));
        } catch (RuntimeException e) {
            p.sendSystemMessage(Component.literal("That didn't work. Open the Builder and try again."));
            LOGGER.warn("builder magician change for {}: bad values '{}': {}", p.getGameProfile().getName(), values, e.toString());
            return 0;
        }
        String name = flow.magician().name().isEmpty() ? "creature" : flow.magician().name();
        if (calls.isEmpty()) {
            p.sendSystemMessage(Component.literal("Nothing was changed, so your " + name + " is just as it was."));
            return 0;
        }
        p.sendSystemMessage(Component.literal("✦ Changing your " + name + "…"));
        BuilderMake.magician(p, flow.magician(), calls).thenAccept(r -> {
            String say = switch (r.outcome()) {
                case REFUSED -> "The Builder can't change that here: " + r.detail();
                case BANK_EMPTY, NOT_HERE -> r.detail();
                case BUSY -> "Your last Builder make is still on its way.";
                default -> "";
            };
            if (!say.isEmpty()) {
                p.getServer().execute(() -> p.sendSystemMessage(Component.literal(say)));
            }
        });
        return 1;
    }

    /**
     * The names on his shelf but the one being changed, so Moddy's name for a new make tells it apart from
     * what he has. A shelf that cannot be read is no names: the make goes ahead with Moddy's first choice.
     */
    static java.util.List<String> shelfNames(String owner, String editOf) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            BuilderSpecStore.all(com.moddingfromamod.runtime.RuntimeConfig.playersDir(), owner).forEach((id, m) -> {
                if (!id.equals(editOf)) {
                    try {
                        String n = m.read().name();
                        if (!n.isBlank()) {
                            out.add(n);
                        }
                    } catch (RuntimeException e) {
                        // a row that does not read names nothing
                    }
                }
            });
        } catch (RuntimeException e) {
            LOGGER.warn("builder names: the shelf of {} did not read: {}", owner, e.toString());
        }
        return out;
    }

    /**
     * §1: what he turned things into on his last makes, newest first (the To of every Turn-into item on his shelf,
     * the Apprentice's included). A shelf that cannot be read is none.
     */
    static java.util.List<String> recentTo(String owner) {
        java.util.List<BuilderSpecStore.Made> made = new java.util.ArrayList<>();
        try {
            made.addAll(BuilderSpecStore.all(com.moddingfromamod.runtime.RuntimeConfig.playersDir(), owner).values());
        } catch (RuntimeException e) {
            LOGGER.warn("builder recent: the shelf of {} did not read: {}", owner, e.toString());
            return java.util.List.of();
        }
        made.sort(java.util.Comparator.comparingLong(BuilderSpecStore.Made::at).reversed());
        java.util.List<String> out = new java.util.ArrayList<>();
        for (BuilderSpecStore.Made m : made) {
            try {
                BuilderSpec spec = m.read();
                String to = spec.opt(BuilderTurnInto.TO_SLOT);
                if (BuilderTurnInto.POWER.equals(spec.opt("power")) && !to.isEmpty() && !out.contains(to)) {
                    out.add(to);
                }
            } catch (RuntimeException e) {
                // a row that does not read is no recent target
            }
        }
        return out;
    }

    /** Forget a player's flow when they leave (nothing to clean up in the world). */
    public static void forget(UUID player) {
        FLOWS.remove(player);
    }
}
