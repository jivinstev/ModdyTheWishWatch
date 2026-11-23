package com.moddingfromamod.wish;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Moddy's closing line may not promise a part no call built</b>.
 *
 * <h2>The hole this closes</h2>
 * {@link TestedClaim} catches the line that vouches for a TEST nobody ran. Its sibling is the line
 * that describes a PART nobody made, and an earlier issue's step-6 pass found it on about one grant in seven,
 * in every class: a hot chocolate maker "with a glowing red heart" (four {@code make_item} calls,
 * none of them lit), "your cool underground base" built on the grass, a black hole that "now pulls
 * WAY harder" when a brand-new one had been made beside his, a pet that "loves picking up blocks to
 * bring to you" with nothing anywhere that carries anything. A child is told about a part he then
 * cannot find — worse than a smaller wish described honestly.
 *
 * <h2>What it checks, and the rule that keeps it quiet</h2>
 * A handful of claim FAMILIES, each a phrase in {@code say} and the evidence that would back it,
 * read from the calls this wish actually made ({@link Evidence}). A phrase is sent back only when
 * NOTHING recorded backs it. <b>Precision over recall, deliberately</b>: a false refusal costs a
 * turn on a good wish, and a guard that cries wolf is one the agent learns to route around. So:
 * <ul>
 *   <li>Backing is lenient. Any mention, anywhere in the MECHANICAL arguments of a call that changed
 *       the world, of something that could produce the claim counts — a {@code light} block for
 *       "glowing", a {@code follow} flag for "follows you". The check does not judge whether the
 *       part works; it asks only whether any call even tried.</li>
 *   <li>The agent's own NARRATION does not back anything: a build's {@code name}, {@code id} and
 *       {@code note}, a template's {@code say}. Measured — the above-ground base was called
 *       {@code underground_glass_base}, named "Cool Underground Glass Base" and noted
 *       "Underground hidden base", and every shape stood at {@code dy >= 0}.</li>
 *   <li>Setting words ("underground", "under the sea") are only asked about when this wish placed
 *       something; "explore your underwater dome" on an item wish is talking about a dome he
 *       already had.</li>
 *   <li>"Upgraded" is backed by any edit — a {@code modify_*} tool, a {@code start_from}, a thing
 *       that existed before this wish — and only refused when everything this wish touched is
 *       new.</li>
 * </ul>
 *
 * <h2>What it is NOT</h2>
 * It never objects to what was BUILT, and it misses — by design — a part a call DID try and did
 * badly (a storm that lasted six seconds, fireballs that never burst). Those are quality, and a
 * phrase list cannot see them. Replayed over every archived grant ({@code SayClaimArchiveReplayTest}).
 */
public final class SayClaim {

    private SayClaim() {
    }

    /** One promise in {@code say} that nothing backs. */
    public record Unbacked(String family, String phrase, String clause) {
    }

    /** A claim family: the words that make it, and what in a call would back it. */
    private record Family(String name, Pattern said, Pattern backedBy) {
    }

    private static final int F = Pattern.CASE_INSENSITIVE;

    private static final List<Family> LEXICAL = List.of(
            // "a glowing red heart" — but not "glowing purple eyes": an enderman's are lit by
            // vanilla, and eyes are the one part every sculpt colours to look lit.
            new Family("glow",
                    Pattern.compile("\\bglow(?:s|ing|y)?\\b(?! (?:\\w+ ){0,2}eyes)", F),
                    Pattern.compile("finds|glow|light|lantern|lamp|torch|shroomlight|froglight"
                            + "|end_rod|candle|beacon|emissive|lava|magma|shin|soul_fire|campfire", F)),
            // Singular on purpose: "pigs and horses will follow you" is a carrot, and vanilla.
            new Family("follow",
                    Pattern.compile("\\bfollows (?:you|him)\\b|\\bby your side\\b"
                            + "|\\bstays? (?:right )?(?:with|by|next to) you\\b", F),
                    Pattern.compile("follow|companion|tame|pet\\b|owner|friend|leash", F)),
            new Family("stay",
                    // "when it stands still THEY stay put" is the monsters, not him (archive, 2026-11-18).
                    Pattern.compile("(?<!\\bthey )\\bstays? (?:\\w+ )?(?:where you left|put|right there|in place)"
                            + "|\\bwon'?t wander\\b", F),
                    Pattern.compile("stay|persist|noai|no_ai|sit|anchor|still|wander|home|leash"
                            + "|follow", F)),
            new Family("fetch",
                    // "the Call horn brings HIM to you" is the creature coming (CreatureHorn), not a thing fetched.
                    Pattern.compile("\\b(?:brings?|fetch(?:es)?|collects?|gathers?|carries)\\b(?! (?:him|her|it|them)(?: back)? to you)"
                            + "[^.!?]{0,30}\\b(?:to|for) you\\b"
                            + "|\\bdrops?\\b[^.!?]{0,25}\\bfor you\\b", F),
                    Pattern.compile("bring|fetch|collect|gather|carry|drop|give|loot|item", F)),
            new Family("guard",
                    Pattern.compile("\\b(?:protects?|guards?|defends?|fights?|attacks?)\\b[^.!?]{0,12}"
                            + "\\b(?:you|your|monsters?|mobs?|zombies?|enemies|bad guys)\\b", F),
                    Pattern.compile("protect|guard|defend|fight|attack|hostile|aggress|hurt|damage"
                            + "|target|golem|kill", F)));

    private static final Pattern UNDERGROUND =
            Pattern.compile("\\bunder ?ground\\b|\\bburied\\b", F);
    /** A shape sunk below its anchor, or a command reaching below the player's feet. */
    private static final Pattern BELOW =
            Pattern.compile("\"dy\"\\s*:\\s*-\\d|~-\\d|\\bbelow\\b", F);
    private static final Pattern UNDERWATER =
            Pattern.compile("\\bunder ?water\\b|\\bunder the (?:sea|ocean|water|waves)\\b", F);
    private static final Pattern EDIT = Pattern.compile(
            "\\b(?:upgraded|improved|souped[- ]up)\\b"
            + "|\\bnow (?:\\w+ ){0,2}(?:way |much |even |super |a lot )?"
            + "(?:harder|stronger|bigger|faster|further|farther|higher|longer|louder|brighter|taller)\\b",
            F);

    /** Tools that change a thing that was already there. */
    private static final Set<String> EDITORS = Set.of(
            "modify_mob", "modify_player", "modify_item", "modify_painting", "modify_event");

    /** Tools that put something in a PLACE — the only wishes a setting word can be about. */
    private static final Set<String> PLACERS = Set.of(
            "build_structure", "stage_event", "paint_surface");

    /**
     * Tools that make a PICTURE. A picture cannot give light, so in a wish that made only pictures
     * "a big glowing sun" describes what it shows, not a promise of a light (an earlier issue's archive: four
     * sunset paintings, every one sent back).
     */
    private static final Set<String> PICTURES = Set.of("paint_surface", "modify_painting");

    /**
     * World-changing calls whose arguments are the agent's own words rather than a part: a
     * template repeats the say, conjure_at narrates the sparkles. Neither can back a claim.
     */
    private static final Set<String> NOT_EVIDENCE = Set.of(
            "add_template", "conjure_at", "take_me_to", "finish_wish",
            "finish_building", "plan_wish", "set_status", "notify_player");

    /** Argument keys that NAME or NARRATE rather than make. */
    private static final Set<String> NARRATION = Set.of(
            "note", "say", "status", "message", "title", "name", "id", "doing", "any", "all",
            "tried", "careful", "state", "suggestion", "reuse_words", "keywords", "icon");

    private static final int MAX_CHARS = 512 * 1024;

    /**
     * What this wish's calls actually did, as text a claim can be looked up in. Built call by call
     * — live from {@code McpProtocol}, offline from an archived action log — so both read the same
     * evidence through the same code.
     */
    public static final class Evidence {
        private final StringBuilder mechanical = new StringBuilder();
        private final Set<String> made = new java.util.HashSet<>();
        private boolean edited;
        private boolean placed;
        private boolean inheritsCreature;
        private int pictures;
        private int others;

        /** Record one SUCCESSFUL world-changing call. {@code result} may be null. */
        public synchronized void add(String tool, JsonObject args, String result) {
            if (tool == null || NOT_EVIDENCE.contains(tool)) {
                return;
            }
            // A creature started from — or changed — one that existed BEFORE this wish keeps its
            // behaviour (base, powers), which no call here restates (his companion robot
            // puppy, recoloured, "follows you everywhere" — true, and sent back).
            String base = key(args, CREATURE_FROM.get(tool));
            if (base != null && !made.contains(base)) {
                inheritsCreature = true;
            }
            String mine = key(args, "id");
            if (mine != null) {
                made.add(mine);
            }
            if (EDITORS.contains(tool)
                    || (args != null && args.has("start_from"))
                    || (result != null && result.toLowerCase(Locale.ROOT).contains("rebuilt in place"))) {
                edited = true;
            }
            if (PLACERS.contains(tool)) {
                placed = true;
            }
            if (PICTURES.contains(tool)) {
                pictures++;
            } else {
                others++;
            }
            if (mechanical.length() < MAX_CHARS) {
                mechanical.append(' ').append(tool).append(' ');
                appendMechanical(args, mechanical);
            }
        }

        /** Something this wish touched existed before it (live: {@code WishRun.existedBefore()}). */
        public synchronized void markEdited() {
            edited = true;
        }

        synchronized String text() {
            return mechanical.toString();
        }

        synchronized boolean edited() {
            return edited;
        }

        synchronized boolean placed() {
            return placed;
        }

        synchronized boolean inheritsCreature() {
            return inheritsCreature;
        }

        /** The wish made pictures and nothing else: its say describes what they SHOW. */
        synchronized boolean picturesOnly() {
            return pictures > 0 && others == 0;
        }

        private static String key(JsonObject args, String field) {
            if (args == null || field == null || !args.has(field) || !args.get(field).isJsonPrimitive()) {
                return null;
            }
            String v = args.get(field).getAsString().strip().toLowerCase(Locale.ROOT).replace(' ', '_');
            return v.isEmpty() ? null : v;
        }
    }

    /** The argument that names the creature a call builds on, per creature tool. */
    private static final Map<String, String> CREATURE_FROM = Map.of(
            "sculpt_creature", "start_from", "define_creature", "start_from",
            "modify_mob", "target", "paint_creature", "id");

    /** What a creature DOES — the families a creature built on an older one inherits unseen. */
    private static final Set<String> BEHAVIOUR = Set.of("follow", "stay", "guard");

    private static void appendMechanical(JsonElement e, StringBuilder out) {
        if (e == null || e.isJsonNull() || out.length() >= MAX_CHARS) {
            return;
        }
        if (e.isJsonObject()) {
            for (Map.Entry<String, JsonElement> kv : e.getAsJsonObject().entrySet()) {
                if (NARRATION.contains(kv.getKey())) {
                    continue;
                }
                // Written as JSON-ish so a shape reads `"dy":-3` whatever the source spacing.
                out.append(" \"").append(kv.getKey()).append("\":");
                appendMechanical(kv.getValue(), out);
            }
        } else if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                out.append(' ');
                appendMechanical(x, out);
            }
        } else {
            out.append(e.getAsString());
        }
    }

    /** Every promise in {@code say} nothing backs, in the order they appear; empty when honest. */
    public static List<Unbacked> check(String say, Evidence ev) {
        List<Unbacked> out = new ArrayList<>();
        if (say == null || say.isBlank() || ev == null) {
            return out;
        }
        String text = ev.text();
        for (Family f : LEXICAL) {
            if (ev.inheritsCreature() && BEHAVIOUR.contains(f.name())) {
                continue;
            }
            if (ev.picturesOnly() && "glow".equals(f.name())) {
                continue;
            }
            Matcher m = f.said().matcher(say);
            if (m.find() && !f.backedBy().matcher(text).find()) {
                out.add(new Unbacked(f.name(), m.group(), TestedClaim.clauseAround(say, m.start())));
            }
        }
        if (ev.placed()) {
            Matcher m = UNDERGROUND.matcher(say);
            if (m.find() && !BELOW.matcher(text).find()) {
                out.add(new Unbacked("underground", m.group(),
                        TestedClaim.clauseAround(say, m.start())));
            }
            m = UNDERWATER.matcher(say);
            if (m.find() && !text.toLowerCase(Locale.ROOT).contains("water")) {
                out.add(new Unbacked("underwater", m.group(),
                        TestedClaim.clauseAround(say, m.start())));
            }
        }
        Matcher m = EDIT.matcher(say);
        if (m.find() && !ev.edited()) {
            out.add(new Unbacked("edit", m.group(), TestedClaim.clauseAround(say, m.start())));
        }
        out.sort((a, b) -> Integer.compare(say.indexOf(a.clause()), say.indexOf(b.clause())));
        return out;
    }

    /** The send-back: names each unbacked phrase and the two honest ways out (WISH_LINTERS P2). */
    public static String refusal(List<Unbacked> unbacked) {
        StringBuilder s = new StringBuilder("Not yet — your `say` promises him ");
        s.append(unbacked.size() == 1 ? "a part" : unbacked.size() + " parts")
                .append(" that none of this wish's calls made, and he is going to go and look:");
        for (Unbacked u : unbacked) {
            s.append("\n  • \"").append(u.phrase()).append("\" — in \"").append(u.clause())
                    .append("\". ").append(why(u.family()));
        }
        s.append("\nEither take those words out and describe what you DID make, or say plainly "
                + "that part could not be done this time. (If a call really did make it, keep the "
                + "words — call finish_wish again with the same say and it will go through.)");
        return s.toString();
    }

    private static String why(String family) {
        return switch (family) {
            case "glow" -> "Nothing you built gives light or a glow.";
            case "follow" -> "Nothing set it to follow him.";
            case "stay" -> "Nothing set it to stay put.";
            case "fetch" -> "Nothing makes it bring, drop or give him anything.";
            case "guard" -> "Nothing makes it guard or fight.";
            case "underground" -> "Every part was built at or above the ground.";
            case "underwater" -> "There is no water in what you built.";
            case "edit" -> "Everything this wish touched is NEW — nothing he already had was changed.";
            default -> "";
        };
    }
}
