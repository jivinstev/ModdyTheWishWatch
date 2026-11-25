package com.moddingfromamod.safety;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.moddingfromamod.agent.ActionLog;

/**
 * <b>L0 at the tool choke point: every word the agent is about to put in front of the child.</b>
 *
 * <p>{@code docs/CHILD_SAFETY_PLAN.md} §3 L0, second half. The first half runs inside
 * {@code WishService.submit} and grades what the CHILD typed. This grades what MODDY is about to
 * say — which is a different question with a different failure mode, and it is the one the plan's
 * two model layers structurally cannot answer: L1 saw the wish and L2 saw the plan, and neither of
 * them has seen the sentence the agent composed three tool calls later.
 *
 * <h2>Why the lexicon and not a model here</h2>
 *
 * <p>§3 L2 settles it: "a model round trip per tool call would add minutes to a wish". A wish makes
 * tens of calls. So this layer is deterministic, instant and deliberately narrow — it is the FLOOR,
 * exactly as {@link SafetyLexicon}'s own documentation insists, and everything nuanced stays with
 * the graders that already ran.
 *
 * <h2>The table is the whole design</h2>
 *
 * <p>{@link #FIELDS} names, per tool, the arguments a child actually reads — eighteen of them.
 * Nothing else is looked at: a tool with no row exits before its arguments are touched at all, and
 * a tool WITH one is read at that one argument. {@code build_structure} is the shape of both — its
 * {@code name} is what the child is told the thing is called, and its shape array runs to 20 KB and
 * is never opened.
 *
 * <p>A full-args scan would be simpler to write and would be both slower and <i>wronger</i>: a
 * block id, a shape name and a command's tag are not things a child reads, and a false positive on
 * one of them refuses a wish that was never a problem.
 *
 * <h2>Two outcomes</h2>
 *
 * <ul>
 *   <li><b>{@code CLEARLY}</b> on any axis → {@link Act#BLOCK}. The tool body never runs, and the
 *       refusal <b>names the field</b> and says to rewrite that one field in kid-safe words —
 *       {@code RefusalRedirectTest}'s rule, because a refusal naming no alternative makes an agent
 *       improvise, which is worse than the sentence objected to.</li>
 *   <li>anything else → {@link Act#PASS}.</li>
 * </ul>
 *
 * <h2>What this layer cannot see, stated rather than implied</h2>
 *
 * <p>Text that reaches the child through NBT — a {@code summon} carrying a {@code CustomName}, a
 * sign, a written book — is not swept. Naming that is the honest position: a sweep that claimed to
 * cover "everything the child reads" while reading seventeen arguments would be a check whose
 * SCOPE is smaller than its name.
 */
public final class SafetyTextSweep {

    private SafetyTextSweep() {
    }

    /** What the choke point should do about this call. */
    public enum Act {
        /** Nothing to say; run the tool. */
        PASS,
        /**
         * Refuse the call; the agent rewrites the field and calls the tool again.
         *
         * <p>Named BLOCK, not REFUSE, so the enum constant and the logged {@code action} are the
         * same word. Two names for one concept is how a report starts printing
         * {@code <-- not a known action}.
         */
        BLOCK
    }

    /** Which shape an argument is, because reading one out of JSON differs per shape. */
    public enum Shape {
        /** A plain string argument. */
        STRING,
        /** An array of strings; every element is swept. */
        STRING_ARRAY,
        /** An array of objects; one named member of each is swept. */
        OBJECT_ARRAY_MEMBER,
        /** A list of Minecraft commands; only the ones a child can READ are swept. */
        COMMANDS
    }

    /**
     * One child-visible argument.
     *
     * @param tool   the tool name as the agent calls it
     * @param arg    the argument name <b>as the schema spells it</b> — this runs after
     *               {@code ArgAliases.normalise}, so a near-miss has already been repaired
     * @param shape  how to read it
     * @param member for {@link Shape#OBJECT_ARRAY_MEMBER}, which member of each object
     */
    public record Field(String tool, String arg, Shape shape, String member) {

        static Field of(String tool, String arg) {
            return new Field(tool, arg, Shape.STRING, "");
        }

        static Field array(String tool, String arg) {
            return new Field(tool, arg, Shape.STRING_ARRAY, "");
        }

        static Field member(String tool, String arg, String member) {
            return new Field(tool, arg, Shape.OBJECT_ARRAY_MEMBER, member);
        }

        static Field commands(String tool, String arg) {
            return new Field(tool, arg, Shape.COMMANDS, "");
        }

        /** How the refusal and the log name this field. */
        public String label() {
            return switch (shape) {
                case STRING -> arg;
                case STRING_ARRAY -> arg + "[]";
                case OBJECT_ARRAY_MEMBER -> arg + "[]." + member;
                case COMMANDS -> arg + "[]";
            };
        }
    }

    /**
     * <b>Every field a child reads, and nothing else.</b>
     *
     * <p>Each one was checked against the tool's own {@code inputSchema()} rather than against the
     * plan's prose, and two of them came back different:
     *
     * <ul>
     *   <li>§3 names {@code make_item.description} and <b>there is no such argument</b> —
     *       {@code description} is an {@code ArgAliases} synonym for {@code finish_wish.note},
     *       which is already in this table, so the field the plan meant is covered under its real
     *       name.</li>
     *   <li>{@code make_item.variants[].name} is NOT in §3 and is child-visible: it is what each
     *       flavour of a set is called, in his inventory, in his own words.</li>
     * </ul>
     *
     * <p>⚠ And a whole family §3 misses: <b>every creation tool has a {@code name}</b>, and each
     * one's own schema says what it is for — "Display name shown above the creature", "What to call
     * it to the child". §3 lists only {@code modify_mob}'s, which is the verb that RENAMES a
     * creature; the five verbs that name one in the first place were not in the plan. Found by
     * reading the schemas rather than the prose, which is what
     * {@code SafetyTextSweepTest.everyRowNamesSomethingThatExists} now does on every build.
     *
     * <p>{@code finish_building.label} is deliberately absent: its own schema says it is the name
     * of a PHOTO FILE, so it is not read by anybody.
     */
    public static final List<Field> FIELDS = List.of(
            Field.of("notify_player", "message"),
            Field.of("finish_building", "say"),
            Field.of("finish_wish", "note"),
            Field.of("finish_wish", "suggestion"),
            // the description of the thing moved here from finish_building's say — it is
            // said to the child after the photo is judged, so it is swept like the line it replaced.
            Field.of("finish_wish", "say"),
            Field.of("set_status", "doing"),
            Field.of("plan_wish", "status"),
            Field.array("plan_wish", "steps"),
            // the agent's own question, put to him in chat and as a chest's title.
            Field.of("ask_which", "question"),
            Field.of("make_item", "name"),
            Field.member("make_item", "variants", "name"),
            Field.of("modify_mob", "name"),
            // The three the plan does not name, one per creation class (five until the train and
            // machine ones went with Create) — every one of them a
            // display name the child reads off the thing he just wished for.
            Field.of("define_creature", "name"),
            Field.of("sculpt_creature", "name"),
            Field.of("build_structure", "name"),
            // A picture has no display name; the words a child reads on a picture wish are the
            // ride-along status ToolNarrator shows the instant the call arrives.
            Field.of("paint_surface", "status"),
            Field.of("conjure_at", "message"),
            Field.commands("run_command", "command"),
            // A rule's commands run LATER, days after the wish, when no tool call is left to sweep
            // — the same reason CS-D4 sweeps a datapack's source. So they are swept when set.
            Field.commands("set_rule", "do"),
            // An event's title is the big words across his screen when it starts.
            Field.of("stage_event", "title"));

    /**
     * The command verbs that put words on a child's screen.
     *
     * <p>Read off {@code CommandGuard}'s table, whose rows say which commands put words on a
     * screen — {@code say}, {@code tellraw}, {@code title}, and the bossbar and team names
     * allowed — so a command allowed for its text is swept by the same edit that allows it. The
     * rest place blocks, summon mobs and grant effects; none of them is a sentence he reads.
     * {@code execute … run <one of these>} is followed too, because that is a real shape and the
     * wrapper is not what makes the text visible.
     */
    static final List<String> VISIBLE_VERBS = List.copyOf(com.moddingfromamod.wish.CommandGuard.textVerbs());

    /**
     * One decision, plus what it would have been.
     *
     * @param act      what will actually happen — never {@link Act#BLOCK} in shadow mode
     * @param assessed what the sweep found, whatever the mode. Kept apart from {@code act} for the
     *                 same reason {@code SafetyPlanGate.Decision} does: a test asserting that the
     *                 layer THOUGHT the right thing while doing nothing is the only claim shadow
     *                 mode can make about itself
     * @param message  the refusal the agent reads; empty unless {@code act} is BLOCK
     * @param field    which field tripped, as {@link Field#label()}; empty when nothing did
     * @param verdict  what the lexicon said
     */
    public record Decision(Act act, Act assessed, String message, String field,
                           SafetyVerdict verdict) {

        /** Nothing looked at, nothing found — a tool with no child-visible text, or the OFF arm. */
        public static final Decision PASS =
                new Decision(Act.PASS, Act.PASS, "", "", SafetyVerdict.fine(
                        SafetyVerdict.Provenance.LEXICON));

        /** Should the tool body be skipped? */
        public boolean refuses() {
            return act == Act.BLOCK;
        }
    }

    /**
     * Sweep one call's child-visible text.
     *
     * <p>Costs nothing measurable and is on the path of <b>every</b> tool call, so the cheap exit
     * comes first: a tool with no row in {@link #FIELDS} returns before its arguments are read.
     *
     * <p>⚠ <b>No length cap, deliberately.</b> Capping the text swept per field would be one line
     * and would create a bypass exactly the length of the cap. The lexicon is a couple of dozen
     * linear scans; the schemas already bound these fields; and a bound that can be walked past is
     * worse than no bound, because it reads as protection.
     *
     * @param tool   the tool being called
     * @param args   its arguments, <b>after</b> {@code ArgAliases.normalise} — see the caller
     * @param wishId the short id, for the log
     */
    public static Decision sweep(String tool, JsonObject args, String wishId) {
        if (tool == null || args == null || !ChildSafetyConfig.grading()) {
            return Decision.PASS;
        }
        List<Field> fields = fieldsFor(tool);
        if (fields.isEmpty()) {
            return Decision.PASS;
        }
        SafetyLexicon lexicon = SafetyLexicon.shared();

        // The FIRST field that trips is the one reported. A refusal naming two fields invites the
        // agent to rewrite both and re-check neither; naming one gives it a single thing to do,
        // and if a second field is also bad the next call finds it.
        for (Field f : fields) {
            for (String text : readAll(args, f)) {
                Optional<SafetyVerdict> hit = lexicon.check(text);
                if (hit.isEmpty()) {
                    continue;
                }
                SafetyVerdict v = hit.get();
                if (v.overall() == SafetyVerdict.Band.CLEARLY) {
                    return decide(Act.BLOCK, refusal(tool, f), f.label(), v, wishId, tool);
                }
            }
        }
        return Decision.PASS;
    }

    /** The rows for one tool, or empty. */
    static List<Field> fieldsFor(String tool) {
        List<Field> out = new ArrayList<>();
        for (Field f : FIELDS) {
            if (f.tool().equals(tool)) {
                out.add(f);
            }
        }
        return out;
    }

    /**
     * Log it, then say what happens.
     *
     * <p>Written BEFORE the action and for the refusals only — unlike L1 and L2, which log every
     * grade because their false-positive rate needs a denominator. This layer has no denominator
     * worth having: it would be one record per tool call per wish, tens per wish, every one of them
     * {@code PASS FINE}, and {@code agent-actions.jsonl} is the file every other instrument in this
     * repo reads. What a report needs from here is the exceptions, and the tool-call record already
     * counts the calls.
     *
     * <p><b>The logged word is {@link Act}'s own name</b>, so the enum and the {@code action}
     * field cannot drift — {@code BLOCK}, never a second spelling invented here. {@code PASS}
     * itself never reaches this method — a call with nothing to say returns {@link Decision#PASS}
     * unlogged, which is the paragraph above.
     */
    private static Decision decide(Act assessed, String message, String field, SafetyVerdict verdict,
                                   String wishId, String tool) {
        boolean enforcing = ChildSafetyConfig.enforcing();
        ActionLog.safety(wishId, "text", verdict,
                (enforcing ? "" : "SHADOW_") + assessed.name(),
                0L, 0.0, "lexicon", null, tool, 0, false, false,
                com.moddingfromamod.agent.AgentContext.currentPlayerId());
        if (!enforcing) {
            // Shadow acts on nothing — the refusal included, because a tool result the agent reads
            // is an action.
            return new Decision(Act.PASS, assessed, "", field, verdict);
        }
        return new Decision(assessed == Act.BLOCK ? Act.BLOCK : Act.PASS, assessed, message, field,
                verdict);
    }

    // ---- the words ----

    /**
     * The refusal the agent reads.
     *
     * <p>Four things, in order, each one a measured requirement rather than a flourish: that the
     * tool did <b>not</b> run (so the agent does not assume the child has already read it), WHICH
     * field, WHAT to do about it — a rewrite of that one field, which is an alternative rather than
     * a prohibition — and that the rest of the call can stay as it is, so a twelve-argument
     * {@code make_item} is not re-derived from scratch.
     *
     * <p>And, as at L2: <b>do not tell the child.</b> §5's rule is that a child is never told what
     * was wrong with his wish. Naming the category to him is both a vocabulary lesson he did not
     * ask for and an invitation to find out how close he can get — and an agent left to itself
     * narrates its own refusals, cheerfully.
     */
    static String refusal(String tool, Field field) {
        return "SAFETY — that wording was not accepted, so " + tool + " did NOT run and the child "
                + "has NOT read it.\n"
                + "The problem is the `" + field.label() + "` field: it contains a word Moddy must "
                + "not say to a 7-year-old.\n"
                + "Rewrite `" + field.label() + "` in kid-safe words — the same idea in ordinary "
                + "friendly language — and call " + tool + " again.\n"
                + "Everything else about the call can stay exactly as it is.\n"
                + "Do NOT tell the child about this, do not apologise to him and do not mention "
                + "safety — just say it the other way and carry on.";
    }

    // ---- reading the arguments ----

    /** Every string this field carries, in order. */
    static List<String> readAll(JsonObject args, Field field) {
        List<String> out = new ArrayList<>();
        if (!args.has(field.arg())) {
            return out;
        }
        JsonElement el = args.get(field.arg());
        switch (field.shape()) {
            case STRING -> {
                if (el.isJsonPrimitive()) {
                    out.add(el.getAsString());
                }
            }
            case STRING_ARRAY -> {
                if (el.isJsonArray()) {
                    for (JsonElement e : el.getAsJsonArray()) {
                        if (e != null && e.isJsonPrimitive()) {
                            out.add(e.getAsString());
                        }
                    }
                }
            }
            case OBJECT_ARRAY_MEMBER -> {
                if (el.isJsonArray()) {
                    for (JsonElement e : el.getAsJsonArray()) {
                        if (e != null && e.isJsonObject()) {
                            JsonObject o = e.getAsJsonObject();
                            if (o.has(field.member()) && o.get(field.member()).isJsonPrimitive()) {
                                out.add(o.get(field.member()).getAsString());
                            }
                        }
                    }
                }
            }
            case COMMANDS -> {
                // The body of run_command accepts a bare string as well as the list its schema
                // asks for (its own comment records why), so both shapes are read here. A sweep
                // that only understood the documented shape would be blind to the degraded one,
                // which is the shape a model actually sends.
                if (el.isJsonPrimitive()) {
                    visibleText(el.getAsString()).ifPresent(out::add);
                } else if (el.isJsonArray()) {
                    JsonArray arr = el.getAsJsonArray();
                    for (JsonElement e : arr) {
                        if (e != null && e.isJsonPrimitive()) {
                            visibleText(e.getAsString()).ifPresent(out::add);
                        }
                    }
                }
            }
            default -> { }
        }
        return out;
    }

    /**
     * What a child would READ off this command, if anything.
     *
     * <p>Everything after the verb, rather than a parse of the component JSON — the lexicon matches
     * whole words and JSON punctuation is neither a letter nor a digit, so a term inside
     * {@code {"text":"…"}} still matches, and the keys ({@code text}, {@code color}, {@code bold})
     * and a target selector are not terms. It is a superset of the visible text and it is
     * <b>immune to a rewrite of the component format</b>, which a parser would not be.
     *
     * <p>{@code execute … run say …} is followed one level at a time; a command with no visible
     * verb is empty, which is the answer for the overwhelming majority of them.
     */
    static Optional<String> visibleText(String command) {
        String c = command == null ? "" : command.trim();
        for (int depth = 0; depth < 4 && !c.isEmpty(); depth++) {
            int sp = c.indexOf(' ');
            String first = (sp < 0 ? c : c.substring(0, sp)).toLowerCase(Locale.ROOT);
            // A leading slash is not part of the API this mod calls, but the agent sends one often
            // enough that a sweep which stopped at it would be blind exactly when the command is
            // most likely to be hand-written.
            if (first.startsWith("/")) {
                first = first.substring(1);
            }
            int colon = first.indexOf(':');
            String verb = colon >= 0 ? first.substring(colon + 1) : first;
            if (VISIBLE_VERBS.contains(verb)) {
                String rest = sp < 0 ? "" : c.substring(sp + 1).trim();
                return rest.isEmpty() ? Optional.empty() : Optional.of(rest);
            }
            if (!verb.equals("execute")) {
                return Optional.empty();
            }
            int run = indexOfRun(c);
            if (run < 0) {
                return Optional.empty();
            }
            c = c.substring(run + " run ".length()).trim();
        }
        return Optional.empty();
    }

    /**
     * Where {@code execute}'s tail begins.
     *
     * <p>The FIRST {@code " run "}, matching {@code CommandGuard}'s own reading: everything after
     * it is another command, and a later {@code run} belongs to that one rather than to this.
     */
    private static int indexOfRun(String command) {
        return command.toLowerCase(Locale.ROOT).indexOf(" run ");
    }
}
