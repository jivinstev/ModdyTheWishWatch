package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.ui.PickPage;
import com.moddingfromamod.wishbuilder.BuilderAllowlist;
import com.moddingfromamod.wishbuilder.BuilderSpec;
import com.moddingfromamod.wishbuilder.MakeFacts;
import com.moddingfromamod.wishbuilder.MakeFit;
import com.moddingfromamod.world.MakeView;
import com.moddingfromamod.world.WorldLimits;
import com.moddingfromamod.world.WorldMakes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The core's one way to ask this world what may be made here, and how big</b> ({@link WorldMakes}), and to show,
 * fit and say its answer. Every place a make starts or is offered asks through here (a Builder page's tiles, its tune
 * dialog and its Make, an Apprentice make, a recipe, "Make another one", a hotbar fire, "Give me one", a thing on his
 * watch), so the sweep, the fallback and the fitting ({@link MakeVerdict}, {@link MakeFit}) are the
 * same at each. A VIEW: nothing here is written to the shelf or any store. Server thread.
 *
 * <p>Safety runs before it at every start (the lexicon on a wish's words, the agent's own checks, a world's rules
 * file), so nothing safety refuses ever reaches a world to be fitted or locked.
 */
public final class MakeGate {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    private MakeGate() {
    }

    /** What this world says about the make {@code facts} describes, for {@code ask}, decided. */
    public static MakeVerdict ask(MakeFacts facts, WishAsk ask) {
        WorldMakes world = WorldMakes.get();
        if (world == WorldMakes.NONE || !anyWorld()) {
            return MakeVerdict.ALLOWED;   // the plain game: nothing to ask
        }
        MakeView view;
        Map<String, Double> upFront;
        try {
            view = world.view(facts, ask);
            WorldLimits l = com.moddingfromamod.world.RegionalMakes.limitsFor(world, ask);   // #182: by its place
            upFront = l == null ? Map.of() : l.limits();
        } catch (RuntimeException | LinkageError e) {
            // a world that cannot answer stops nothing: the game as it is with no world answering
            LOGGER.warn("the world's WorldMakes threw on {}: {}", facts, e.toString());
            view = MakeView.ALLOWED;
            upFront = Map.of();
        }
        MakeVerdict v = MakeVerdict.decide(view, upFront, line -> WishFeedback.sweeps(line, ""));
        // a make over a limit it carries no knob for cannot be fitted by anyone: locked in the core's words
        if (v.state() == MakeView.State.FIT && !MakeFit.over(facts, v.limits()).isEmpty()
                && MakeFit.over(facts, v.limits()).stream().anyMatch(k -> !MakeFit.FACTS.get(k).clampable())) {
            return v.tooMuch();
        }
        return v;
    }

    /**
     * Where this world sends a finished make (#70, {@link WorldMakes#landing}), asked before its first placing step.
     * {@link com.moddingfromamod.world.Landing#LANDS} with no world, and when the world throws or answers null.
     */
    public static com.moddingfromamod.world.Landing landing(MakeFacts facts, WishAsk ask) {
        WorldMakes world = WorldMakes.get();
        if (world == WorldMakes.NONE || !anyWorld()) {
            return com.moddingfromamod.world.Landing.LANDS;
        }
        try {
            com.moddingfromamod.world.Landing l = world.landing(facts, ask);
            return l == null ? com.moddingfromamod.world.Landing.LANDS : l;
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("the world's WorldMakes.landing threw on {}: {}", facts, e.toString());
            return com.moddingfromamod.world.Landing.LANDS;
        }
    }

    /** Tell the world a make it asked to keep was not kept (#70); a world that throws stops nothing. */
    public static void notKept(com.moddingfromamod.world.NotKept what) {
        WorldMakes world = WorldMakes.get();
        if (world == WorldMakes.NONE || !anyWorld()) {
            return;
        }
        try {
            world.notKept(what);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("the world's WorldMakes.notKept threw on {}: {}", what, e.toString());
        }
    }

    /** Tell the world a kept make was handed over (#70); a world that throws stops nothing. */
    public static void handedOver(com.moddingfromamod.world.HandedOver what) {
        WorldMakes world = WorldMakes.get();
        if (world == WorldMakes.NONE || !anyWorld()) {
            return;
        }
        try {
            world.handedOver(what);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("the world's WorldMakes.handedOver threw on {}: {}", what, e.toString());
        }
    }

    /** The world's limits for every make of this player's, or none. Cheap: a menu asks per draw. */
    public static WorldLimits limits(java.util.UUID player) {
        WorldMakes world = WorldMakes.get();
        if (world == WorldMakes.NONE || player == null) {
            return WorldLimits.NONE;
        }
        try {
            WorldLimits l = world.limits(player);
            return l == null ? WorldLimits.NONE : l;
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("the world's WorldMakes threw on limits: {}", e.toString());
            return WorldLimits.NONE;
        }
    }

    /**
     * The world's limits for this player's thing standing at {@code where} (#182): the region there answers when there
     * is one ({@code WorldRegions}), else as {@link #limits(java.util.UUID)}. What a living body is fitted by.
     */
    public static WorldLimits limits(java.util.UUID player, com.moddingfromamod.world.Bases.Pos where) {
        WorldMakes world = WorldMakes.get();
        if (world == WorldMakes.NONE || player == null) {
            return WorldLimits.NONE;
        }
        try {
            WorldLimits l = com.moddingfromamod.world.RegionalMakes.limitsFor(world,
                    WishAsk.replay(player, "").withWhere(where));
            return l == null ? WorldLimits.NONE : l;
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("the world's WorldMakes threw on limits: {}", e.toString());
            return WorldLimits.NONE;
        }
    }

    /** What a Builder spec becomes here: the verdict, and the spec fitted when it is let through within limits. */
    public record Built(MakeVerdict verdict, BuilderSpec spec, List<String> lowered, boolean unfit) {
        Built(MakeVerdict verdict, BuilderSpec spec, List<String> lowered) {
            this(verdict, spec, lowered, false);
        }

        public boolean makes() {
            return verdict.makes();
        }
    }

    /**
     * A Builder spec, asked and fitted: the world is asked about the spec as this world caps it; FIT fits it through
     * {@link BuilderAllowlist#capped(BuilderSpec, Map)}, the one fitting path, and the fitted make is asked again (a
     * world may lock what it fitted). One that cannot be fitted is locked in {@link MakeVerdict#TOO_MUCH}.
     */
    public static Built build(BuilderSpec spec, BuilderAllowlist allow, WishAsk ask) {
        BuilderSpec capped = allow == null ? spec : allow.capped(spec);
        if (!anyWorld()) {
            return new Built(MakeVerdict.ALLOWED, capped, List.of());   // the plain game: nothing read, nothing fitted
        }
        MakeVerdict v = ask(MakeFacts.of(capped), ask);
        if (!v.makes() || v.limits().isEmpty()) {
            return new Built(v, capped, List.of());
        }
        MakeFit.Fitted f = allow == null ? MakeFit.fit(capped, v.limits()) : allow.capped(spec, v.limits());
        if (!f.fits()) {
            return new Built(v.tooMuch(), capped, List.of(), true);
        }
        if (!f.changed()) {
            return new Built(v, f.spec(), List.of());
        }
        MakeVerdict again = ask(MakeFacts.of(f.spec()), ask);
        if (!again.makes()) {
            return new Built(again, f.spec(), List.of());
        }
        return new Built(v, f.spec(), f.lowered());
    }

    /** Is anything answering here at all? With nothing, every page is drawn exactly as before. */
    public static boolean anyWorld() {
        return WorldMakes.get() != WorldMakes.NONE;
    }

    /** Say a verdict's line, once; nothing when the make is let through. */
    public static void say(ServerPlayer player, MakeVerdict v) {
        if (player != null && !v.makes() && !v.line().isEmpty()) {
            player.sendSystemMessage(Component.literal(v.line()));
        }
    }

    /** Say, once, that a make was fitted (the core's own words, swept like any line); nothing when it was not. */
    public static void sayFitted(ServerPlayer player, List<String> lowered) {
        String line = MakeVerdict.fittedLine(lowered);
        if (player != null && !line.isEmpty() && WishFeedback.sweeps(line, "")) {
            TOLD.put(player.getUUID(), System.currentTimeMillis());
            player.sendSystemMessage(Component.literal(line));
        }
    }

    /** How long after a fitted line a creature fitted on spawn is not told again: the same make. */
    static final long TOLD_FOR_MILLIS = 30_000L;
    private static final Map<java.util.UUID, Long> TOLD = new java.util.concurrent.ConcurrentHashMap<>();

    /** For a GameTest that a creature made another way is told: forget who was told a moment ago. */
    public static void forgetToldForTest() {
        TOLD.clear();
    }

    /** Was this player told a make was fitted a moment ago (the make now standing up)? */
    static boolean toldLately(java.util.UUID player) {
        Long at = TOLD.get(player);
        return at != null && System.currentTimeMillis() - at < TOLD_FOR_MILLIS;
    }

    /** A make tile, drawn as locked: grey, with the world's words first under its name. Never pressable into a make. */
    public static PickPage.Tile lockedTile(PickPage.Tile t, MakeVerdict v) {
        List<String> lore = new ArrayList<>();
        lore.add(v.line());
        lore.addAll(t.lore());
        return new PickPage.Tile(t.id(), PickPage.BLOCKED_ITEM, t.label() + " 🔒", lore, false);
    }
}
