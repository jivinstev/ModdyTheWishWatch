package com.moddingfromamod.wishbuilder;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.McpProtocol;
import com.moddingfromamod.agent.McpSessions;
import com.moddingfromamod.agent.WishAgent;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.WishRun;
import com.moddingfromamod.wish.Wish;
import com.moddingfromamod.wish.WishBook;
import com.moddingfromamod.wish.WishFeedback;
import com.moddingfromamod.wish.WishProgress;
import com.moddingfromamod.wish.WishSource;
import com.moddingfromamod.wish.WishStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Runs a Builder make as a wish of its own</b> (B0a; docs/WISH_BUILDER_PLAN.md §2).
 *
 * <p>The lowered calls go through {@link McpProtocol#handlePost} — the SAME dispatch the wish worker's
 * calls go through — so a make gets everything a wish gets without a second path: the cancel gate,
 * L0's sweep of every child-visible word, the action log, the run's {@code made} list (undo, the
 * shelf), provenance and the wish card photo. The only difference is {@link WishRun#markBuilderMade}:
 * the plan grade (L2) skips a plan no model wrote.
 *
 * <p>The wish is {@link WishSource#BUILDER}, charged HALF like every served wish
 * ({@code WishBankService.charge(…, false)}), and refunded by the book if it does not settle DONE.
 *
 * <p>Tool calls run OFF the server thread (they hop onto it themselves, and refuse to be called from
 * it — {@code AgentBridge.onServer}); the settle hops back.
 */
public final class BuilderMake {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    private BuilderMake() {
    }

    public enum Outcome { MADE, PARTIAL, REFUSED, BANK_EMPTY, BUSY, FAILED }

    /**
     * How long the pillar stands before the make's first tool runs: a served wish's full rise,
     * {@link com.moddingfromamod.wish.reveal.FanfareTiming#FULL_RISE} ticks. A test that is not about the look
     * sets 0 for its own run and puts it back.
     */
    /**
     * A real mob is lowered as {@code summon <mob> ~ ~ ~}, and run_command runs at HIS feet: the cow came
     * out beside him while the pillar stood where he aimed (an earlier issue's first Gate B run). With a locked spot,
     * a relative summon is moved there — the run's anchor, after plan_wish's kind slide.
     */
    static BuilderLowering.Call atTheSpot(BuilderLowering.Call call, com.moddingfromamod.marshal.Spot at) {
        if (at == null || !"run_command".equals(call.tool()) || !call.args().has("command")
                || !call.args().get("command").isJsonArray()) {
            return call;
        }
        JsonObject a = call.args().deepCopy();
        com.google.gson.JsonArray out = new com.google.gson.JsonArray();
        boolean moved = false;
        for (var c : call.args().getAsJsonArray("command")) {
            String cmd = c.getAsString();
            if (cmd.startsWith("summon ") && cmd.contains(" ~ ~ ~")) {
                cmd = cmd.replaceFirst(" ~ ~ ~", " " + at.x() + " " + at.y() + " " + at.z());
                moved = true;
            }
            out.add(cmd);
        }
        if (!moved) {
            return call;
        }
        a.add("command", out);
        return new BuilderLowering.Call(call.tool(), a);
    }

    /** Where each make was locked to land: read by the tests that check it landed THERE. */
    private static final java.util.Map<UUID, net.minecraft.core.BlockPos> AIMED = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<UUID, net.minecraft.core.BlockPos> e) {
                    return size() > 64;
                }
            });

    /** The spot a make was locked to when Make was pressed, or null (no player online). */
    public static net.minecraft.core.BlockPos aimedAt(UUID wishId) {
        return wishId == null ? null : AIMED.get(wishId);
    }

    public static volatile long riseMillis = com.moddingfromamod.wish.reveal.FanfareTiming.FULL_RISE * 50L;

    /**
     * What happened. {@code calls} is each tool called, in order, with {@code !} after a refused one;
     * {@code detail} is the first refusal's words (or the problems with the spec).
     */
    public record Result(Outcome outcome, UUID wishId, String madeId, String detail, List<String> calls) {
    }

    /** A make in memory: whose, which wish, what was picked. */
    public record Made(UUID player, UUID wishId, BuilderSpec spec) {
    }

    private static final java.util.Deque<Made> RECENT = new java.util.concurrent.ConcurrentLinkedDeque<>();

    /**
     * The last makes (newest last, at most 50), in memory only: what a Gate C client reads back, and
     * the start of the stored spec "change one thing" needs (docs/WISH_BUILDER_PLAN.md §6, still owed).
     */
    public static List<Made> recent() {
        return List.copyOf(RECENT);
    }

    private static void remember(UUID player, UUID wishId, BuilderSpec spec) {
        RECENT.addLast(new Made(player, wishId, spec));
        while (RECENT.size() > 50) {
            RECENT.pollFirst();
        }
    }

    /** One make per child at a time: a second press while one is running is refused, not queued. */
    private static final Set<UUID> MAKING = ConcurrentHashMap.newKeySet();

    /** True while one of this child's Builder makes or changes is running (one at a time per child). */
    public static boolean making(UUID who) {
        return MAKING.contains(who);
    }

    private static volatile McpProtocol shared;

    /** The dispatch a make runs through: its own sessions over the agent's own tool set. */
    /** The dispatch a make runs through, for a GameTest outside this package. */
    public static McpProtocol protocolForTest() {
        return protocol();
    }

    static McpProtocol protocol() {
        McpProtocol p = shared;
        if (p == null) {
            synchronized (BuilderMake.class) {
                if (shared == null) {
                    shared = new McpProtocol(WishAgent.buildTools(), new McpSessions());
                }
                p = shared;
            }
        }
        return p;
    }

    /** Who a make is for: enough to file the wish, with no player object (a GameTest has none). */
    public record Who(UUID id, String name, String dimension, double x, double y, double z) {
        public static Who of(ServerPlayer p) {
            return new Who(p.getUUID(), p.getName().getString(),
                    p.level().dimension().identifier().toString(), p.getX(), p.getY(), p.getZ());
        }
    }

    /** Make {@code spec} for {@code player}, charged half a wish. Call on the server thread. */
    public static CompletableFuture<Result> make(ServerPlayer player, BuilderSpec spec, BuilderAllowlist allow) {
        return make(player, spec, allow, null);
    }

    /**
     * …or, with {@code editOf}, change the thing that make made. An edit is charged like a make, except the
     * one change {@link FreeEdit} holds free (the last Apprentice make's thing, until his next wish).
     */
    public static CompletableFuture<Result> make(ServerPlayer player, BuilderSpec spec, BuilderAllowlist allow,
                                                 String editOf) {
        return make(player, spec, allow, editOf, false);
    }

    /**
     * …and {@code apprentice}: a make an Apprentice tile pressed. What it makes is then the one
     * thing he may change for free, once, until his next wish ({@link FreeEdit}).
     */
    public static CompletableFuture<Result> make(ServerPlayer player, BuilderSpec spec, BuilderAllowlist allow,
                                                 String editOf, boolean apprentice) {
        String text = textOf(spec);
        boolean free = editOf != null && FreeEdit.isFree(player.getUUID(), editOf);
        if (!free && com.moddingfromamod.wish.WishCharge.get().refusesAtSubmit(player, false, text, true)) {
            return done(Outcome.BANK_EMPTY, null, "", "the wish bank is empty");
        }
        return runCapped(player.level().getServer(), Who.of(player), allow == null ? spec : allow.capped(spec), allow, protocol(),
                true, takeCard, editOf, apprentice);
    }

    /**
     * Whether a make from the menus takes the wish card. Always true in production; a GameTest that drives the
     * real command path (BuilderMenusGameTest) sets it false, the way the others pass {@code card=false} to
     * {@link #run}: the card is non-fatal and needs a camera a GameTest does not have, so it spent ~85 of that
     * test's 94 s waiting for one (a 36 s spin wait, then a stills orbit; D3).
     */
    public static volatile boolean takeCard = true;

    /**
     * The make itself. {@code charge} false is the GameTest seam (no bank); a test passes its own
     * dispatch too. Call on the server thread.
     */
    public static CompletableFuture<Result> run(MinecraftServer server, Who person, BuilderSpec spec,
                                                BuilderAllowlist allow, McpProtocol protocol, boolean charge) {
        return run(server, person, spec, allow, protocol, charge, true);
    }

    /**
     * …and whether to take the wish card ({@code finish_building}'s photograph and clip, ~40–60 s with
     * a child present). A GameTest leaves it out: it proves the THING is made, and the card is
     * non-fatal anyway. Production always takes it.
     */
    public static CompletableFuture<Result> run(MinecraftServer server, Who person, BuilderSpec spec,
                                                BuilderAllowlist allow, McpProtocol protocol, boolean charge,
                                                boolean card) {
        return run(server, person, spec, allow, protocol, charge, card, null);
    }

    /**
     * …and, with {@code editOf}, an EDIT: change the thing that make made — the same id, each tool's
     * own "change the one he has" form ({@link BuilderLowering.Edit}) — rather than make another. Refused
     * when the child keeps no make of that id, or the spec is a different kind.
     */
    public static CompletableFuture<Result> run(MinecraftServer server, Who person, BuilderSpec spec,
                                                BuilderAllowlist allow, McpProtocol protocol, boolean charge,
                                                boolean card, String editOf) {
        // an item is made at this world's tier, whoever wrote the spec (a saved make, the Apprentice).
        return runCapped(server, person, allow == null ? spec : allow.capped(spec), allow, protocol, charge, card, editOf,
                false);
    }

    /**
     * CHANGE his kept Builder make {@code id} by operators (the editor, {@link BuilderEdit}): the Builder's own
     * edit mode presses the changes, and the result runs as an ordinary Builder edit ({@link #make} with
     * {@code editOf}), charged the same. Refused, with nothing made, when he keeps no such make, when a wish changed
     * it since, or when the Builder's edit cannot make the change; then the wish belongs to the wish worker.
     * Call on the server thread.
     */
    public static CompletableFuture<Result> modify(ServerPlayer player, BuilderAllowlist allow, String id,
                                                   List<BuilderEdit.Op> ops) {
        Edited e = edited(player.getUUID(), allow, id, ops);
        return e.refusal() != null ? done(Outcome.REFUSED, null, "", e.refusal()) : make(player, e.spec(), allow, id);
    }

    /**
     * slice 1: a Builder change on a creature the MAGICIAN made ({@link MagicianCreature}): its calls, made like any
     * Builder make (the wish, its price, the reveal), and nothing kept on the Builder's shelf, because it is still the
     * Magician's creature. Charged as a Builder change is.
     */
    public static CompletableFuture<Result> magician(ServerPlayer player, MagicianCreature.Thing thing,
                                                   List<BuilderLowering.Call> calls) {
        if (calls.isEmpty()) {
            return done(Outcome.REFUSED, null, "", "nothing was changed");
        }
        if (com.moddingfromamod.wish.WishCharge.get().refusesAtSubmit(player, false, magicianText(thing), true)) {
            return done(Outcome.BANK_EMPTY, null, "", "the wish bank is empty");
        }
        return magicianRun(player.level().getServer(), Who.of(player), thing, calls, protocol(), true, takeCard);
    }

    /** {@link #magician} with the GameTest's seams (a server, a person, a dispatch, no bank). */
    public static CompletableFuture<Result> magicianRun(MinecraftServer server, Who person, MagicianCreature.Thing thing,
                                                      List<BuilderLowering.Call> calls, McpProtocol protocol,
                                                      boolean charge, boolean card) {
        if (calls.isEmpty()) {
            return done(Outcome.REFUSED, null, "", "nothing was changed");
        }
        String name = thing.name().isEmpty() ? "creature" : thing.name();
        return execute(server, person, calls, protocol, charge, card, magicianText(thing), thing.id(), thing.id(), false,
                "a Builder change on the Magician's '" + thing.id() + "': "
                        + calls.stream().map(BuilderLowering.Call::tool).toList(),
                wishId -> { },
                ok -> ok ? "✪ Your " + name + " is changed! The rest is just as the Magician made it."
                        : "The Builder couldn't change your " + name + " this time.",
                (wishId, ok, free) -> { });
    }

    /**
     * slice 2: a Builder change on a BUILDING the Magician made ({@link MagicianBuilding}): its calls, made like any
     * Builder make, nothing kept on the Builder's shelf. Charged as a Builder change is.
     */
    public static CompletableFuture<Result> magicianBuilding(ServerPlayer player, MagicianBuilding.Thing thing,
                                                           List<BuilderLowering.Call> calls) {
        if (calls.isEmpty()) {
            return done(Outcome.REFUSED, null, "", "nothing was changed");
        }
        String text = "Builder: change " + (thing.name().isEmpty() ? thing.id() : thing.name()) + " (building, the Magician's)";
        if (com.moddingfromamod.wish.WishCharge.get().refusesAtSubmit(player, false, text, true)) {
            return done(Outcome.BANK_EMPTY, null, "", "the wish bank is empty");
        }
        return magicianBuildingRun(player.level().getServer(), Who.of(player), thing, calls, protocol(), true, takeCard);
    }

    /** a sure typed change on an ITEM the Magician made ({@link MagicianItem}), charged as a Builder change is. */
    public static CompletableFuture<Result> magicianItem(ServerPlayer player, MagicianItem.Thing thing,
                                                       List<BuilderLowering.Call> calls) {
        if (calls.isEmpty()) {
            return done(Outcome.REFUSED, null, "", "nothing was changed");
        }
        String text = "Builder: change " + (thing.name().isEmpty() ? thing.id() : thing.name()) + " (item, the Magician's)";
        if (com.moddingfromamod.wish.WishCharge.get().refusesAtSubmit(player, false, text, true)) {
            return done(Outcome.BANK_EMPTY, null, "", "the wish bank is empty");
        }
        return magicianItemRun(player.level().getServer(), Who.of(player), thing, calls, protocol(), true, takeCard);
    }

    /** {@link #magicianItem} with the GameTest's seams (a server, a person, a dispatch, no bank). */
    public static CompletableFuture<Result> magicianItemRun(MinecraftServer server, Who person, MagicianItem.Thing thing,
                                                          List<BuilderLowering.Call> calls, McpProtocol protocol,
                                                          boolean charge, boolean card) {
        if (calls.isEmpty()) {
            return done(Outcome.REFUSED, null, "", "nothing was changed");
        }
        String name = thing.name().isEmpty() ? "item" : thing.name();
        return execute(server, person, calls, protocol, charge, card,
                "Builder: change " + name + " (item, the Magician's)", thing.id(), thing.id(), false,
                "a Builder change on the Magician's '" + thing.id() + "': "
                        + calls.stream().map(BuilderLowering.Call::tool).toList(),
                wishId -> { },
                ok -> ok ? "✪ Your " + name + " is changed! The rest is just as the Magician made it."
                        : "The Builder couldn't change your " + name + " this time.",
                (wishId, ok, free) -> { });
    }

    /** {@link #magicianBuilding} with the GameTest's seams (a server, a person, a dispatch, no bank). */
    public static CompletableFuture<Result> magicianBuildingRun(MinecraftServer server, Who person, MagicianBuilding.Thing thing,
                                                              List<BuilderLowering.Call> calls, McpProtocol protocol,
                                                              boolean charge, boolean card) {
        if (calls.isEmpty()) {
            return done(Outcome.REFUSED, null, "", "nothing was changed");
        }
        String name = thing.name().isEmpty() ? "building" : thing.name();
        return execute(server, person, calls, protocol, charge, card,
                "Builder: change " + name + " (building, the Magician's)", thing.id(), thing.id(), false,
                "a Builder change on the Magician's '" + thing.id() + "': "
                        + calls.stream().map(BuilderLowering.Call::tool).toList(),
                wishId -> { },
                ok -> ok ? "✪ Your " + name + " is changed! The rest is just as the Magician made it."
                        : "The Builder couldn't change your " + name + " this time.",
                (wishId, ok, free) -> { });
    }

    static String magicianText(MagicianCreature.Thing thing) {
        return "Builder: change " + (thing.name().isEmpty() ? thing.id() : thing.name()) + " (creature, the Magician's)";
    }

    /** {@link #modify} with the GameTest's seams ({@link #run}'s): a server, a person, a dispatch, no bank. */
    public static CompletableFuture<Result> modifyRun(MinecraftServer server, Who person, BuilderAllowlist allow,
                                                      McpProtocol protocol, String id, List<BuilderEdit.Op> ops,
                                                      boolean charge, boolean card) {
        Edited e = edited(person.id(), allow, id, ops);
        return e.refusal() != null ? done(Outcome.REFUSED, null, "", e.refusal())
                : run(server, person, e.spec(), allow, protocol, charge, card, id);
    }

    /** an edit as the editor answers it, before anything is made: its new spec, or why not. */
    public record Edited(BuilderSpec spec, String refusal) {
    }

    public static Edited edited(UUID who, BuilderAllowlist allow, String id, List<BuilderEdit.Op> ops) {
        BuilderSpecStore.Made kept = BuilderSpecStore.get(who.toString(), id);
        if (kept == null) {
            return new Edited(null, "there is no Builder make '" + id + "' to change");
        }
        if (kept.magicianTouched()) {
            return new Edited(null, "'" + id + "' was changed by a wish since the Builder made it");
        }
        BuilderSpec prior;
        try {
            prior = kept.read();
        } catch (RuntimeException ex) {
            return new Edited(null, "its kept spec does not read: " + ex.getMessage());
        }
        BuilderEdit.Result r = BuilderEdit.apply(allow == null ? BuilderAllowlist.REVIEWED : allow, LibraryPresets.SHIPPED,
                id, prior, ops);
        return r.ok() ? new Edited(r.spec(), null) : new Edited(null, r.refusal());
    }

    /** {@link #run}, and whether an Apprentice tile pressed it ({@link FreeEdit}); public for the GameTest. */
    public static CompletableFuture<Result> runApprentice(MinecraftServer server, Who person, BuilderSpec spec,
                                                          BuilderAllowlist allow, McpProtocol protocol, boolean charge,
                                                          boolean card) {
        return runCapped(server, person, allow == null ? spec : allow.capped(spec), allow, protocol, charge, card, null, true);
    }

    private static CompletableFuture<Result> runCapped(MinecraftServer server, Who person, BuilderSpec spec,
                                                       BuilderAllowlist allow, McpProtocol protocol, boolean charge,
                                                       boolean card, String editOf, boolean apprentice) {
        UUID who = person.id();
        List<String> problems = allow == null ? List.of() : allow.problems(spec);
        if (!problems.isEmpty()) {
            return done(Outcome.REFUSED, null, "", String.join("; ", problems));
        }
        BuilderLowering.Edit edit = null;
        String newId;
        if (editOf != null) {
            BuilderSpecStore.Made kept = BuilderSpecStore.get(who.toString(), editOf);
            if (kept == null) {
                return done(Outcome.REFUSED, null, "", "there is no Builder make '" + editOf + "' to change");
            }
            if (kept.magicianTouched()) {   //: re-lowering its spec would throw the Magician's change away
                return done(Outcome.REFUSED, null, "", "'" + editOf + "' was changed by a wish since the Builder made it");
            }
            if (!kept.kind().equals(spec.kind().id())) {
                return done(Outcome.REFUSED, null, "", "'" + editOf + "' is a " + kept.kind() + ", not a " + spec.kind().id());
            }
            String about = com.moddingfromamod.agent.tools.PlanWishTool.canonicalId(kept.ref());
            edit = new BuilderLowering.Edit(kept.ref(), about == null ? "new" : about);
            newId = editOf;
        } else {
            newId = newId(spec);
        }
        List<BuilderLowering.Call> calls;
        try {
            calls = BuilderLowering.lower(spec, newId, LibraryPresets.SHIPPED, edit);
        } catch (IllegalArgumentException e) {
            return done(Outcome.REFUSED, null, "", e.getMessage());
        }
        final String madeId = newId;
        return execute(server, person, calls, protocol, charge, card, textOf(spec), newId, editOf, apprentice,
                spec.toJson().toString(), wishId -> remember(who, wishId, spec),
                ok -> ok ? BuilderWords.ready(spec) : BuilderWords.failed(spec),
                (wishId, ok, free) -> {
                    if (ok) {
                        keep(who, madeId, BuilderLowering.refOf(calls, madeId), spec, wishId);
                    }
                    settleFree(who, wishId, free, apprentice, ok, ok ? madeId : editOf, spec);
                });
    }

    /** What happens once a make's outcome is known (server thread): what it keeps, and the free change settled. */
    private interface Settle {
        void settle(UUID wishId, boolean ok, boolean free);
    }

    /**
     * The make itself, whatever its calls: the wish it is, its charge, where it lands, the calls one by one through the
     * marshal's own session, the reveal and what he is told. Shared by a Builder make ({@link #runCapped}) and a Builder
     * change on a Magician creature ({@link #magician}), so the two cannot drift. {@code logged} rides on the wish's
     * own log line (a make's spec, so it can be reopened; the Bedrock gate reads it).
     */
    private static CompletableFuture<Result> execute(MinecraftServer server, Who person, List<BuilderLowering.Call> calls,
                                                     McpProtocol protocol, boolean charge, boolean card, String text,
                                                     String newId, String editOf, boolean apprentice, String logged,
                                                     java.util.function.Consumer<UUID> remembered,
                                                     java.util.function.Function<Boolean, String> says, Settle after) {
        UUID who = person.id();
        final List<BuilderLowering.Call> toRun = card ? calls
                : calls.stream().filter(c -> !"finish_building".equals(c.tool())).toList();
        // a film's staged make stands beside his others (three pillars at once), so it skips the one-at-a-time gate.
        if (!com.moddingfromamod.wish.WishAimer.hasStage(who) && !MAKING.add(who)) {
            return done(Outcome.BUSY, null, "", "a Builder make is already on its way");
        }
        WishBook book = WishBook.get(server);
        Wish created = Wish.create(text, WishSource.BUILDER, System.currentTimeMillis(), who,
                person.name(), person.dimension(), person.x(), person.y(), person.z());
        // The Apprentice's free change (FreeEdit). A change of the one thing held free is not charged; the hold moves
        // onto this wish, so it comes back if the change does not stand and no other wish came meanwhile. An
        // Apprentice make holds its wish so that, when it stands, its thing becomes the free one. Either way the hold
        // is set BEFORE the wish book hears of the wish, because every other wish it hears of ends the free change.
        final boolean free = editOf != null && FreeEdit.take(who, editOf);
        if (free || apprentice) {
            FreeEdit.starting(who, created.id());
        }
        Wish wish = book.add(created);
        if (free) {
            LOGGER.info("builder make {} for {}: the free change of {} (the last Apprentice make)", wish.shortId(), person.name(), editOf);
        }
        if (charge && !free) {
            var paid = com.moddingfromamod.wish.WishCharge.get().charge(who, wish.id(), false);
            if (paid.refused()) {
                book.complete(wish.id(), WishStatus.REJECTED, "the wish bank emptied before this make could be paid for");
                FreeEdit.failed(who, wish.id());
                MAKING.remove(who);
                return done(Outcome.BANK_EMPTY, wish.id(), "", "the wish bank is empty");
            }
        }
        // The spec rides on the wish's own log line, so a make can be reopened and replayed.
        LOGGER.info("builder make {} for {}: {}", wish.shortId(), person.name(), logged);
        remembered.accept(wish.id());

        Marshal marshal = protocol.marshal();
        WishRun run = marshal.submit(wish.id(), who, text);
        if (run == null) {
            book.complete(wish.id(), WishStatus.FAILED, "the marshal would not take the make");
            after.settle(wish.id(), false, free);
            MAKING.remove(who);
            return done(Outcome.FAILED, wish.id(), "", "the marshal would not take the make");
        }
        run.markBuilderMade();
        // A MAKE LOOKS AND LANDS LIKE A WISH. The same three things WishService.submit does
        // for a typed wish, in the same order: lock where it manifests to where he is looking NOW (aim, then clamp,
        // kept on the run so plan_wish's kind slide and every placing tool use it — before this a make had no anchor,
        // so no pillar, and a picture aimed at a lake was built on its bed), the sparkles' focus there, and its own
        // bar. The pillar (WishOrbs) follows from the anchor; the reveal (Reveals) from the making tools, as for any wish.
        ServerPlayer wisher = server.getPlayerList().getPlayer(who);
        final long rise = wisher == null ? 0 : riseMillis;
        if (wisher != null) {
            net.minecraft.core.BlockPos aim = com.moddingfromamod.wish.WishAimer.lockAtWishTime(wisher, run, wish.shortId());
            WishProgress.defaultAnchor(wisher, aim);
            AIMED.put(wish.id(), aim.immutable());
            WishProgress.startThinking(wisher, wish.id(), run.displayName());
        }
        String session = openSession(protocol);
        marshal.assign(session, wish.id());

        CompletableFuture<Result> result = new CompletableFuture<>();
        // the whole make runs LOCAL ONLY (marshal/LocalOnly): any model or web call asked on this thread is refused
        // and recorded. A Builder, Apprentice, re-wish or free-change make never calls either.
        Thread t = new Thread(() -> com.moddingfromamod.marshal.LocalOnly.during(() -> {
            // The pillar's rise before anything is made: a served wish's three beats (FanfareTiming.FULL_RISE), so the
            // wish is SEEN being granted where it will appear instead of the thing popping into being.
            if (rise > 0) {
                try {
                    Thread.sleep(rise);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            List<String> called = new ArrayList<>();
            String firstRefusal = "";
            boolean failed = false;
            for (BuilderLowering.Call call : toRun) {
                JsonObject reply;
                try {
                    reply = call(protocol, session, atTheSpot(call, run.anchor()));
                } catch (RuntimeException e) {
                    LOGGER.warn("builder make {}: {} threw", wish.shortId(), call.tool(), e);
                    reply = errorReply(e.toString());
                }
                boolean error = reply.has("isError") && reply.get("isError").getAsBoolean();
                called.add(call.tool() + (error ? "!" : ""));
                // PUT IT IN HIS HAND (a Builder lightning sword arrived as nothing). make_item makes
                // the item and then says how it reaches him — the wish worker always runs that command, and the Builder
                // never did, so every item it made was announced and never handed over.
                // Only the LAST make_item is his (a snack machine's food is made first, and the machine pours it);
                // the whole reply is read, because a machine's names its command past textOf's 400 characters.
                String give = error || !"make_item".equals(call.tool()) || call != lastItemOf(toRun) ? null
                        : handOverCommand(fullTextOf(reply));
                if (give != null) {
                    JsonObject g = new JsonObject();
                    com.google.gson.JsonArray cmd = new com.google.gson.JsonArray();
                    cmd.add(give);
                    g.add("command", cmd);
                    JsonObject given = call(protocol, session, new BuilderLowering.Call("run_command", g));
                    boolean gerr = given.has("isError") && given.get("isError").getAsBoolean();
                    called.add("run_command(give)" + (gerr ? "!" : ""));
                    LOGGER.info("builder make {}: handed over with '{}'{}", wish.shortId(), give, gerr ? " — REFUSED: " + textOf(given) : "");
                    if (gerr) {
                        error = true;
                        reply = given;
                    }
                }
                if (error) {
                    String words = textOf(reply);
                    LOGGER.warn("builder make {}: {} refused: {}", wish.shortId(), call.tool(), words);
                    if (firstRefusal.isEmpty()) {
                        firstRefusal = call.tool() + ": " + words;
                    }
                    // The photograph is the card, not the thing: a make whose thing exists stands.
                    if (!"finish_building".equals(call.tool())) {
                        failed = true;
                        break;
                    }
                }
            }
            final boolean ok = !failed;
            final String refusal = firstRefusal;
            server.execute(() -> {
                try {
                    String say = says.apply(ok);   //
                    var landedAt = run.anchor();   // where it really went: the aim after the kind's slide and any correction
                    if (landedAt != null) {
                        AIMED.put(wish.id(), new net.minecraft.core.BlockPos(landedAt.x(), landedAt.y(), landedAt.z()));
                    }
                    book.complete(wish.id(), ok ? WishStatus.DONE : WishStatus.FAILED, ok ? say : refusal);
                    marshal.finish(wish.id());
                    WishProgress.clearWishBar(wish.id());
                    com.moddingfromamod.vanilla.RigReveal.reveal(server);
                    ServerPlayer p = server.getPlayerList().getPlayer(who);
                    if (p != null) {
                        if (ok) {
                            WishFeedback.granted(p, say);
                        } else {
                            WishFeedback.partial(p, say);
                        }
                    }
                    Outcome o = !ok ? Outcome.FAILED : refusal.isEmpty() ? Outcome.MADE : Outcome.PARTIAL;
                    after.settle(wish.id(), ok, free);
                    result.complete(new Result(o, wish.id(), newId, refusal, called));
                } catch (RuntimeException e) {
                    result.completeExceptionally(e);
                } finally {
                    MAKING.remove(who);
                }
            });
            return null;
        }), "builder-make-" + wish.shortId());
        t.setDaemon(true);
        t.start();
        return result;
    }

    /**
     * Settle the free change once a make's outcome is known (server thread). A free change that stood is spent; one
     * that did not comes back (when no other wish came meanwhile). An Apprentice make that stood makes its thing the
     * free one, unless it is a real mob, which is not kept and so has nothing to change.
     */
    private static void settleFree(UUID who, UUID wishId, boolean free, boolean apprentice, boolean ok, String thing,
                                   BuilderSpec spec) {
        if (free) {
            if (ok) {
                FreeEdit.failed(who, wishId);
            } else {
                FreeEdit.stood(who, wishId, thing);
            }
        } else if (apprentice) {
            if (ok && !Catalogue.prefix(spec.from()).equals("mob") && !BuilderSupplies.is(spec)) {   //: nothing kept to change
                FreeEdit.stood(who, wishId, thing);
            } else {
                FreeEdit.failed(who, wishId);
            }
        }
    }

    /**
     * Keep what this make was made FROM on his shelf, so "change one thing" can reopen it. A real mob
     * is not kept (nothing has an id to change). Never fatal: the thing stands whether or not the row is
     * written.
     */
    private static void keep(UUID who, String id, String ref, BuilderSpec spec, UUID wishId) {
        if (Catalogue.prefix(spec.from()).equals("mob") || BuilderSupplies.is(spec)) {   //: supplies are used up
            return;
        }
        try {
            // an item is known to the wish worker by the id make_item minted, never the Builder's: keep it, so a
            // Magician edit by that id still finds this row.
            com.moddingfromamod.item.ItemSpecStore.Spec item = ref.equals(id) ? null
                    : com.moddingfromamod.item.ItemSpecStore.find(ref);
            if (!BuilderSpecStore.put(who.toString(), new BuilderSpecStore.Made(id, ref, spec.kind().id(),
                    spec.toJson(), wishId.toString(), System.currentTimeMillis(),
                    item == null || item.id().equals(id) ? "" : item.id(), ""))) {
                LOGGER.warn("builder make {}: its spec was not kept (the shelf file refused it)", id);
            }
        } catch (java.io.IOException | RuntimeException e) {
            LOGGER.warn("builder make {}: its spec was not kept", id, e);
        }
    }

    /**
     * The command {@code make_item}'s reply says puts the item in his hand ({@code wishdatapack <id> give}), or null when
     * the reply asks for none (an edit restamps what he holds; a placed item is already where it goes). Only that one
     * command shape is ever run from a reply.
     */
    static String handOverCommand(String reply) {
        if (reply == null || !reply.contains("PUT IT IN HIS HAND")) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("run_command:\\s*(wishdatapack [a-z0-9_]+ give)\\b").matcher(reply);
        return m.find() ? m.group(1) : null;
    }

    /**
     * What every Builder make's wish text starts with. A run that starts so is the Builder's own reviewed lowering, not
     * the wish worker reading a child's words: {@code modify_mob}'s scope guard (which stops the worker widening "him"
     * into "all of them") does not second-guess it (every "Follows you" make was refused as 'group').
     */
    public static final String TEXT_PREFIX = "Builder: ";

    /** The wish's text: what was made, in words (no free text but the swept name). */
    static String textOf(BuilderSpec spec) {
        return TEXT_PREFIX + BuilderLowering.label(spec) + " (" + spec.kind().id().replace('_', ' ') + ")";
    }

    /** A fresh id for what this make saves: readable, and never someone else's. */
    static String newId(BuilderSpec spec) {
        String base = spec.name().isEmpty() ? BuilderLowering.label(spec) : spec.name();
        String slug = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (slug.isEmpty()) {
            slug = spec.kind().id();
        }
        if (slug.length() > 24) {
            slug = slug.substring(0, 24);
        }
        return slug + "_" + UUID.randomUUID().toString().substring(0, 6);
    }

    private static CompletableFuture<Result> done(Outcome o, UUID wishId, String madeId, String detail) {
        return CompletableFuture.completedFuture(new Result(o, wishId, madeId, detail, List.of()));
    }

    // ------------------------------------------------------------------ the in-process MCP client

    private static String openSession(McpProtocol protocol) {
        var reply = protocol.handlePost(headers(null),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", System.currentTimeMillis());
        return reply.headers().get("Mcp-Session-Id");
    }

    private static JsonObject call(McpProtocol protocol, String session, BuilderLowering.Call call) {
        JsonObject params = new JsonObject();
        params.addProperty("name", call.tool());
        params.add("arguments", call.args());
        JsonObject msg = new JsonObject();
        msg.addProperty("jsonrpc", "2.0");
        msg.addProperty("id", 2);
        msg.addProperty("method", "tools/call");
        msg.add("params", params);
        var reply = protocol.handlePost(headers(session), msg.toString(), System.currentTimeMillis());
        JsonObject body = JsonParser.parseString(reply.body()).getAsJsonObject();
        if (body.has("error")) {
            return errorReply(body.get("error").toString());
        }
        return body.getAsJsonObject("result");
    }

    private static McpProtocol.Headers headers(String session) {
        Map<String, String> m = new HashMap<>();
        m.put("Content-Type", "application/json");
        m.put("Accept", "application/json, text/event-stream");
        if (session != null) {
            m.put("Mcp-Session-Id", session);
        }
        return McpProtocol.Headers.of(m);
    }

    private static JsonObject errorReply(String words) {
        JsonObject r = new JsonObject();
        r.addProperty("isError", true);
        var content = new com.google.gson.JsonArray();
        JsonObject t = new JsonObject();
        t.addProperty("type", "text");
        t.addProperty("text", words);
        content.add(t);
        r.add("content", content);
        return r;
    }

    /** The last make_item of a make: the thing he is handed (null when it makes no item). */
    static BuilderLowering.Call lastItemOf(List<BuilderLowering.Call> calls) {
        BuilderLowering.Call last = null;
        for (BuilderLowering.Call c : calls) {
            if ("make_item".equals(c.tool())) {
                last = c;
            }
        }
        return last;
    }

    /** A reply's whole text (textOf cuts it for a log line). */
    static String fullTextOf(JsonObject result) {
        StringBuilder sb = new StringBuilder();
        if (result.has("content") && result.get("content").isJsonArray()) {
            for (var e : result.getAsJsonArray("content")) {
                JsonObject c = e.getAsJsonObject();
                if (c.has("text")) {
                    sb.append(c.get("text").getAsString());
                }
            }
        }
        return sb.toString();
    }

    static String textOf(JsonObject result) {
        StringBuilder sb = new StringBuilder();
        if (result.has("content") && result.get("content").isJsonArray()) {
            for (var e : result.getAsJsonArray("content")) {
                JsonObject c = e.getAsJsonObject();
                if (c.has("text")) {
                    sb.append(c.get("text").getAsString());
                }
            }
        }
        String s = sb.toString().strip();
        return s.length() > 400 ? s.substring(0, 400) + "…" : s;
    }
}
