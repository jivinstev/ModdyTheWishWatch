package com.moddingfromamod.wish;

import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>The next wish, ready in the box</b> (step 5). When he opens the wish box
 * with nothing in it, it opens with ONE idea already typed, and Moddy says so in a line. He can send it as it is,
 * change it, or clear it and type his own. The idea comes from the first of:
 * <ol>
 *   <li>Moddy's own "you could wish…" after a wish that could be better ({@link #rememberAgent});</li>
 *   <li>the world guide's wish for the goal he is on, once per goal ({@code GoalGuide});</li>
 *   <li>at random, a starter he can make again, or a change to the last thing he made in the Builder
 *       ({@code EditIdeas}, a change the editor really accepts): to show him what Moddy can do.</li>
 * </ol>
 * An idea sent EXACTLY as offered is granted the instant way where it has one: a starter as its own button
 * (PRESET, its recipe), a change through the Builder's edit ({@code BuilderMake.modify}). Edited, it is an ordinary
 * typed wish. Nothing is promised about its price (owner: tell him what it cost after, never before). Every offer and
 * what he did with it is an action-log {@code prefill} record. Off for a world by the {@code wish.prefill} knob.
 */
public final class NextWish {

    private NextWish() {
    }

    /** The knob that turns it off on a server (on when unset). */
    public static final String KNOB = "wish.prefill";

    /** What he is told when the box opens with an idea in it. */
    public static final String SAID = "💡 I put an idea in your wish box! Send it, change it, or type your own.";

    /** Where an idea came from, for the action log. */
    public enum Rung { AGENT, GUIDE, STARTER, CHANGE }

    /**
     * An idea in the box: its words, where it came from, and how to grant it as it is ({@code recipe} for a starter,
     * {@code change} for a Builder edit; neither means an ordinary typed wish).
     */
    public record Idea(String words, Rung rung, String recipe, String changeOf,
                       java.util.List<com.moddingfromamod.wishbuilder.BuilderEdit.Op> change) {
    }

    private static final Map<UUID, String> AGENT = new ConcurrentHashMap<>();
    private static final Map<UUID, Set<String>> GUIDED = new ConcurrentHashMap<>();
    private static final Map<UUID, Idea> OFFERED = new ConcurrentHashMap<>();
    private static final Set<UUID> QUIET = ConcurrentHashMap.newKeySet();
    /** he pressed ✖ on the idea: the box reopens empty this once. */
    private static final Set<UUID> CLEARED = ConcurrentHashMap.newKeySet();
    private static volatile Random random = new Random();

    /** Moddy's own suggestion after a "could be better" wish: the first idea his next box gets. */
    public static void rememberAgent(UUID player, String suggestion) {
        if (player != null && suggestion != null && !suggestion.isBlank()) {
            AGENT.put(player, suggestion.strip());
        }
    }

    /** Is it on for this server? */
    public static boolean on() {
        return !"off".equals(com.moddingfromamod.config.ModdySettings.get(KNOB).orElse("on"));
    }

    /** The idea for his box now, or null (none, or turned off). Remembered as offered. Server thread. */
    public static Idea offer(ServerPlayer player) {
        if (player == null || !on() || QUIET.contains(player.getUUID())) {
            return null;
        }
        Idea idea = choose(player);
        if (idea != null) {
            OFFERED.put(player.getUUID(), idea);
            com.moddingfromamod.agent.ActionLog.prefill(player.getUUID().toString(), idea.rung().name(), "offered");
        }
        return idea;
    }

    /** An idea for his box, and whether it is NEW (so Moddy says so) or the one already there. */
    public record Held(Idea idea, boolean fresh) {
    }

    /**
     * the text-first watch opens the box EVERY time, so an idea he has not used stays in it and is said once.
     * A suggestion from the agent since still takes its place (and is said). Null: no idea. Server thread.
     */
    public static Held hold(ServerPlayer player) {
        if (player == null || !on() || QUIET.contains(player.getUUID())) {
            return null;
        }
        if (CLEARED.remove(player.getUUID())) {
            return null;   //: he cleared the idea: this box is empty
        }
        Idea kept = OFFERED.get(player.getUUID());
        if (kept != null && !AGENT.containsKey(player.getUUID())) {
            return new Held(kept, false);
        }
        Idea idea = offer(player);
        return idea == null ? null : new Held(idea, true);
    }

    /**
     * test prop {@code nextwishpin} (base64 of the words): every idea is these words, so a phone run can show a
     * long one on the Bedrock idea tile. Never set on a live server.
     */
    static String pinned() {
        String b64 = System.getProperty("moddingfromamod.nextwishpin", "");
        if (b64.isBlank()) {
            return null;
        }
        try {
            return new String(java.util.Base64.getDecoder().decode(b64.strip()), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static Idea choose(ServerPlayer player) {
        UUID id = player.getUUID();
        String pin = pinned();
        if (pin != null) {
            return new Idea(pin, Rung.AGENT, "", "", java.util.List.of());
        }
        String agent = AGENT.remove(id);
        if (agent != null) {
            return new Idea(agent, Rung.AGENT, "", "", java.util.List.of());
        }
        try {
            var guide = com.moddingfromamod.world.WorldFacts.current().guide();
            var goal = com.moddingfromamod.world.GoalGuide.next(player, guide).goal();
            if (goal != null && !goal.wish().isBlank()
                    && GUIDED.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(goal.id())) {
                return new Idea(goal.wish(), Rung.GUIDE, "", "", java.util.List.of());
            }
        } catch (RuntimeException e) {
            // no guide on this world, or its tree not loaded: the next rung
        }
        Idea change = change(player);
        Idea starter = starter();
        if (change != null && starter != null) {
            return random.nextBoolean() ? change : starter;
        }
        return change != null ? change : starter;
    }

    /** A starter, at random, to make again: its own button's words and recipe. */
    static Idea starter() {
        var starters = com.moddingfromamod.library.WishLibrary.asPresets();
        if (starters.isEmpty()) {
            return null;
        }
        var p = starters.get(random.nextInt(starters.size()));
        return new Idea(p.wishText(), Rung.STARTER, p.templateId(), "", java.util.List.of());
    }

    /** A change to the newest Builder make he still keeps (one a wish has not changed since). */
    static Idea change(ServerPlayer player) {
        var settings = com.moddingfromamod.wishbuilder.BuilderSettings.forPlayer(player.getUUID());
        if (!settings.enabled()) {
            return null;
        }
        var newest = com.moddingfromamod.wishbuilder.BuilderSpecStore.all(
                        com.moddingfromamod.runtime.RuntimeConfig.playersDir(), player.getUUID().toString())
                .values().stream().filter(m -> !m.magicianTouched())
                .max(java.util.Comparator.comparingLong(com.moddingfromamod.wishbuilder.BuilderSpecStore.Made::at))
                .orElse(null);
        if (newest == null) {
            return null;
        }
        try {
            var idea = com.moddingfromamod.wishbuilder.EditIdeas.pick(settings.allow(),
                    com.moddingfromamod.wishbuilder.LibraryPresets.SHIPPED, newest.id(), newest.read(), random);
            return idea == null ? null : new Idea(idea.words(), Rung.CHANGE, "", newest.id(), idea.ops());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The box came back: what he sent, against what it was offered. True when this handled the wish (an idea sent as
     * it is, granted the instant way); false for the ordinary typed path. A blank send is a cancel and handles
     * nothing. Server thread.
     */
    public static boolean sent(ServerPlayer player, String typed) {
        Idea idea = OFFERED.remove(player.getUUID());
        if (idea == null) {
            return false;
        }
        String id = player.getUUID().toString();
        if (typed == null || WishText.sanitize(typed).isEmpty()) {
            com.moddingfromamod.agent.ActionLog.prefill(id, idea.rung().name(), "cleared");
            return false;
        }
        if (!same(typed, idea.words())) {
            com.moddingfromamod.agent.ActionLog.prefill(id, idea.rung().name(), "edited");
            return false;
        }
        com.moddingfromamod.agent.ActionLog.prefill(id, idea.rung().name(), "sent");
        if (!idea.recipe().isEmpty()) {
            WishService.submit(player, idea.words(), WishSource.PRESET, idea.recipe());
            return true;
        }
        if (!idea.change().isEmpty()) {
            var settings = com.moddingfromamod.wishbuilder.BuilderSettings.forPlayer(player.getUUID());
            String words = idea.words();
            // Tried on the editor when offered; if the make changed since and the edit is refused now, he still gets
            // his wish: the ordinary typed way, never nothing.
            com.moddingfromamod.wishbuilder.BuilderMake.modify(player, settings.allow(), idea.changeOf(), idea.change())
                    .thenAccept(r -> {
                        if (r.outcome() == com.moddingfromamod.wishbuilder.BuilderMake.Outcome.REFUSED) {
                            player.level().getServer().execute(() -> WishService.submit(player, words, WishSource.TYPED));
                        }
                    });
            return true;
        }
        return false;   // Moddy's or the guide's idea: an ordinary wish, as typed
    }

    /** The same words, ignoring case, spacing and end punctuation. Pure. */
    static boolean same(String a, String b) {
        return norm(a).equals(norm(b));
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
    }

    /** The box was opened with other words: no idea of ours is in it. */
    public static void withdraw(UUID player) {
        OFFERED.remove(player);
    }

    /** ✖ pressed on the idea in his box: it goes, recorded as cleared, and the next box opens empty. */
    public static void clear(UUID player) {
        Idea idea = OFFERED.remove(player);
        if (idea != null) {
            com.moddingfromamod.agent.ActionLog.prefill(player.toString(), idea.rung().name(), "cleared");
        }
        CLEARED.add(player);
    }

    /** Test seams. */
    public static void randomForTest(Random r) {
        random = r == null ? new Random() : r;
    }

    /** The idea his box holds now, or null. */
    public static Idea offered(UUID player) {
        return OFFERED.get(player);
    }

    public static void offerForTest(UUID player, Idea idea) {
        OFFERED.put(player, idea);
    }

    /** A test about something else opens HIS box with nothing offered (other players are untouched). */
    public static void quietForTest(UUID player) {
        QUIET.add(player);
    }

    public static void forget(UUID player) {
        QUIET.remove(player);
        AGENT.remove(player);
        GUIDED.remove(player);
        OFFERED.remove(player);
        CLEARED.remove(player);
    }
}
