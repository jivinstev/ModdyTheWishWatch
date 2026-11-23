package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>{@code /moddy try <wish id>} — anyone holding a wish's id gets a copy of what it made</b>
 *.
 *
 * <p>Permission 0, by design: the id is the capability, and a full id is
 * a random v4 UUID ({@code Wish.java:41}), so it cannot be guessed. It is how a server operator's tool
 * lets a grown-up try a child's wish in game before promoting it, and how one child can
 * hand another a thing by telling him its number.
 *
 * <p><b>It is the CALLER's wish</b>, granted through {@link WishService#submitReplay} — the same
 * gates, book entry, acknowledgement and executor as a watch press. It never touches the maker's
 * shelf: every replay command it runs reads the thing, and the two that used to write something
 * keyed by the thing's id — a building's remembered spot and a picture's placement — now leave
 * the maker's alone for a copy ({@code BuildCommand}, {@code PictureCommand}).
 *
 * <p><b>What a wish made</b> is read, in order, from: the recipe it was granted from (a press, a
 * cached wish — the same commands again); else the thing its handover recorded
 * ({@link ArtifactMemoryStore}, keyed by the wish's full id). A wish that made nothing, or a kind
 * with no replay yet (a rule, a changed vanilla mob), says so in plain words.
 */
public final class WishTry {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(com.moddingfromamod.ModdingFromAModMod.MODID);

    /** What happened, for the command's return value and the tests. */
    public enum Result { GRANTED, NOT_AN_ID, UNKNOWN, NOTHING_TO_TRY, NOT_GRANTED }

    private WishTry() {
    }

    /** Run {@code /moddy try <arg>} for {@code player}. Server thread. */
    public static Result tryIt(ServerPlayer player, String arg) {
        MinecraftServer server = player.getServer();
        Optional<TryPlan.Id> id = TryPlan.parse(arg);
        if (id.isEmpty()) {
            say(player, "🤔 \"" + clip(arg) + "\" isn't a wish id. It looks like "
                    + "1a2b3c4d, or the long one with dashes.", ChatFormatting.GRAY);
            return Result.NOT_AN_ID;
        }
        Optional<Wish> found = find(server, id.get());
        if (found.isEmpty()) {
            say(player, "🤔 I can't find a wish " + id.get().shortId()
                    + " in this world. Check the id and try again!", ChatFormatting.GRAY);
            return Result.UNKNOWN;
        }
        Wish wish = found.get();
        List<String> commands = commandsFor(server, wish);
        if (commands.isEmpty()) {
            say(player, "🤔 Wish " + wish.shortId() + " didn't leave anything I can hand "
                    + "you a copy of.", ChatFormatting.GRAY);
            return Result.NOTHING_TO_TRY;
        }
        WishService.Outcome outcome = WishService.submitReplay(player, wish.text(),
                CommandPlan.of(commands, ""));
        LOGGER.info("moddy try: {} tried wish {} ({}) -> {}: {}",
                player.getGameProfile().getName(), wish.shortId(), wish.playerName(), outcome, commands);
        return outcome == WishService.Outcome.FULFILLING ? Result.GRANTED : Result.NOT_GRANTED;
    }

    /**
     * The wish this id names, from the whole journal (every player's, and every wish since the
     * world began that compaction kept), not only the ones still in memory. A SHORT id must name
     * exactly one wish: two wishes sharing eight characters is rare and the answer to it is to
     * paste the full one, never to guess.
     */
    static Optional<Wish> find(MinecraftServer server, TryPlan.Id id) {
        WishBook book = WishBook.get(server);
        if (id.isFull()) {
            Optional<Wish> hit = book.byId(id.full());
            if (hit.isPresent()) {
                return hit;
            }
        }
        List<Wish> matches = new ArrayList<>();
        java.util.Set<java.util.UUID> seen = new java.util.HashSet<>();
        List<Wish> pool = new ArrayList<>(book.recent(500));
        pool.addAll(book.journal().readAll());
        for (Wish w : pool) {
            boolean hit = id.isFull() ? w.id().equals(id.full()) : w.shortId().equals(id.shortId());
            if (hit && seen.add(w.id())) {
                matches.add(w);
            }
        }
        // The journal holds a line per status change; the newest line of a wish is its truth.
        return matches.size() == 1 ? Optional.of(latest(pool, matches.get(0))) : Optional.empty();
    }

    private static Wish latest(List<Wish> pool, Wish w) {
        Wish best = w;
        for (Wish x : pool) {
            if (x.id().equals(w.id())) {
                best = x;
            }
        }
        return best;
    }

    /** The replay commands that give a copy of what {@code wish} made, or empty. */
    static List<String> commandsFor(MinecraftServer server, Wish wish) {
        String templateId = wish.templateId();
        if (templateId != null && !templateId.isBlank()) {
            var recipe = WishCatalog.templates().byId(templateId, wish.text());
            if (recipe.isPresent()) {
                return recipe.get().plan().commands();
            }
        }
        String full = wish.id().toString();
        for (var memory : ArtifactMemoryStore.all()) {
            for (var turn : memory.turns()) {
                if (full.equals(turn.wishId())) {
                    String kind = memory.kind() == null || memory.kind().isBlank()
                            ? kindOf(memory.artifactId()) : memory.kind();
                    return TryPlan.commandFor(kind, memory.artifactId()).map(List::of)
                            .orElse(List.of());
                }
            }
        }
        return List.of();
    }

    /** Which store knows a bare artifact id — for a handover that named the thing but not its kind. */
    private static String kindOf(String id) {
        return TryPlan.inferKind(
                com.moddingfromamod.species.SpeciesRegistry.has(id),
                com.moddingfromamod.builder.BuildRegistry.get(id) != null,
                com.moddingfromamod.item.ItemSpecStore.get(id) != null,
                com.moddingfromamod.paint.PictureStore.get(id) != null);
    }

    private static void say(ServerPlayer player, String text, ChatFormatting colour) {
        player.sendSystemMessage(Component.literal(text).setStyle(Style.EMPTY.withColor(colour)));
    }

    private static String clip(String s) {
        String t = s == null ? "" : s.trim();
        return t.length() > 40 ? t.substring(0, 40) + "…" : t;
    }
}
