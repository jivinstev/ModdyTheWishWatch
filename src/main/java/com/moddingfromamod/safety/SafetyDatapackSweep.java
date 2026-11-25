package com.moddingfromamod.safety;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.moddingfromamod.agent.ActionLog;

/**
 * <b>L0 at CBScript compile time: the words a datapack will say to the child days from now.</b>
 *
 * <p>{@code docs/CHILD_SAFETY_PLAN.md} §12 decision 10, the fourth child-visible surface. The other
 * three are answered before anything is built — the input grade reads the wish, the plan grade reads
 * the plan, and {@link SafetyTextSweep} reads the sentence the agent is about to hand to a tool. A
 * datapack is none of those: CBScript compiles to mcfunction, the mcfunction is saved into the
 * world, and it prints its text <b>every time the trigger fires</b>, for as long as the world
 * exists. There is no tool call at that moment to sweep, and no grader awake to ask. So the only
 * place left to look is the one moment the text passes through this process: the compile.
 *
 * <p>This was the {@code datapacks / CS} GAP in {@link com.moddingfromamod.wish.GuardrailMatrix},
 * cited to, and it was the only real one in the matrix.
 *
 * <h2>The SOURCE, not the compiled output — four reasons, and the plan named the wrong one</h2>
 *
 * <p>§14 said closing this gap meant "sweeping the COMPILED output in {@code author_datapack}".
 * Both halves are wrong, and the reasons are worth keeping:
 *
 * <ol>
 *   <li><b>The output only exists if the compile SUCCEEDED.</b> A sweep of it could not refuse a
 *       compile — it would have to unpick one, after {@code DatapackForge} has already copied the
 *       zip into the durable store and stamped its provenance. Refusing before the subprocess runs
 *       costs nothing and leaves nothing to undo.</li>
 *   <li><b>Most drafts never compile.</b> That is the whole premise of
 *       {@code tools/mine-datapack-corpus.py} — the corpus is the agent's real attempts, classified
 *       by failure mode. An output-only sweep would be blind to the text of every failing draft,
 *       and a failing draft is retried, so the words come round again.</li>
 *   <li><b>The compiler re-splices the text.</b> {@code tellraw.py}'s {@code formatJsonText} cuts a
 *       string at every {@code &#123;C} colour marker and every {@code [label](command)} link into
 *       separate {@code &#123;"text":"…"&#125;} segments, escaping every quote on the way. A term
 *       that is one contiguous phrase in the source can be split across two JSON objects in the
 *       output, where whole-word matching cannot see it. The source is where the words are still
 *       whole.</li>
 *   <li><b>A refusal has to name something the agent can find.</b> It wrote the source; it has
 *       never seen the generated mcfunction, and a line number in generated output is not an
 *       instruction.</li>
 * </ol>
 *
 * <p>And the placement: {@link com.moddingfromamod.datapack.CbScriptCompiler#compile} rather than
 * the tool, because <b>every</b> compile goes through it — the wish path via {@code DatapackForge},
 * the item catalogue, the effect templates, the spikes and every GameTest. One hook, no second
 * path. It is also the point at which the text is FINAL: the {@code $&#123;NAME&#125;} templates in
 * the shipped {@code .cbs} catalogue are substituted Java-side before the source arrives here —
 * measured, 5040 of 5040 sign-text arrays in the real compile log were already substituted — so
 * this sees the name the child will actually read rather than a placeholder.
 *
 * <h2>What counts as human-readable, and what is deliberately not looked at</h2>
 *
 * <p>Not "every string literal". A CBScript source is mostly <i>code</i>: selectors, block ids, NBT
 * keys, objective names, function names, tags, coordinates. The lexicon's word boundary is "no
 * letter or digit either side", which makes {@code _} a boundary — so {@code meth_timer} matches the
 * term {@code meth} and a sweep of every literal would refuse a wish over a variable name. §12's
 * decision says identifiers are not looked at, and {@link #CHANNELS} is how that is enforced: each
 * row is one way CBScript puts words in front of a player, with its own reading.
 *
 * <p>{@link Channel#CHAT} reuses {@link SafetyTextSweep}'s own technique — everything after the
 * verb, following {@code execute … run}, rather than a parse of the component JSON. Its reasoning
 * carries over verbatim: the lexicon matches whole words, JSON punctuation is neither a letter nor a
 * digit, so a term inside {@code &#123;"text":"…"&#125;} still matches while the keys
 * ({@code text}, {@code color}, {@code bold}) and a selector are not terms. It is a superset of the
 * visible text and it is immune to a rewrite of the component format, which a parser would not be.
 *
 * <p><b>Four things this cannot see, stated rather than implied</b> — the same honesty
 * {@link SafetyTextSweep} insists on, because a check whose scope is smaller than its name is worse
 * than no check:
 *
 * <ul>
 *   <li><b>A term split by markup.</b> {@code "have &#123;Csex"} is two words to the lexicon and one
 *       phrase on screen. Nothing whole-word can catch that, in the source or in the output.</li>
 *   <li><b>Text assembled from a CBScript macro.</b> {@code title @a "$(GREETING)"} is swept as the
 *       literal {@code $(GREETING)}; the macro's own value is a string literal somewhere else, and
 *       sweeping macro values is exactly the identifier false positive above (a macro is as likely
 *       to hold {@code stone} as a sentence). Measured: {@code $(…)} macros appear <b>0 times</b> in
 *       all 5,187 real sources, so this is a bypass nothing has ever walked down.</li>
 *   <li><b>Text the datapack reads back out of the world</b> — a score, a player name, an entity's
 *       existing name spliced in with {@code (@Selector)}. That is not text the agent wrote.</li>
 *   <li><b>The pack's own {@code description}</b>, which rides beside the source into
 *       {@code pack.mcmeta}. Deliberately out of scope rather than overlooked: this sweep answers
 *       "what will this datapack SAY to the child", and a pack description is metadata in the
 *       datapack list, not a line anything prints at runtime. It is also not a source line, so it
 *       has no line number to name in a refusal — folding it in would cost the one thing the
 *       refusal is useful for. If it ever needs covering it belongs in {@link SafetyTextSweep}'s
 *       table, where the tool's own argument is.</li>
 * </ul>
 *
 * <h2>The verified-first rule</h2>
 *
 * <p>The condition set on this chunk was explicit: it may not refuse anything until it is shown
 * to trip no false positive on what the agent has really written.
 * {@code SafetyDatapackSweepTest.theRealCorpusIsClean} replays every source in
 * {@code corpus/datapacks.jsonl} and every shipped {@code .cbscript}/{@code .cbs} through this
 * extractor and the real lexicon on <b>every build</b>, and fails on a single {@code CLEARLY}. The
 * one-off measurement including the machine's live compile log is in the ledger, taken by the
 * opt-in {@code theLiveCompileLogIsClean} rather than by a script written to estimate it:
 * <b>60,646 readable snippets over 5,187 real sources, 0 hits</b> (2026-09-12; the live log grows
 * with every gate run, so that denominator is a floor rather than a census — the committed half,
 * 310 snippets over 193 sources, is the one re-measured on every build).
 */
public final class SafetyDatapackSweep {

    private SafetyDatapackSweep() {
    }

    /** The log's {@code layer} for this surface, and the one new word in §6's vocabulary. */
    public static final String LAYER = "datapack";

    /**
     * What the compile should do about this source.
     *
     * <p>The same two as {@link SafetyTextSweep.Act} and named the same, because the logged
     * {@code action} has to be a word {@code tools/safety-report.py} already knows: two names for
     * one concept is how a report starts printing {@code <-- not a known action}.
     */
    public enum Act {
        /** Nothing to say; compile it. */
        PASS,
        /** Refuse the compile; the agent rewrites the line and authors it again. */
        BLOCK
    }

    /** How to read one kind of player-readable text out of a line of CBScript. */
    public enum Channel {
        /**
         * A raw {@code /}-command whose verb speaks to a player. Everything after the verb.
         *
         * <p>Whole-tail because every token after the verb is either the message or a selector, and
         * a selector is not a term — {@link SafetyTextSweep#visibleText}'s argument.
         */
        CHAT,
        /**
         * A raw {@code /}-command that carries a display NAME among identifiers. Quoted parts only.
         *
         * <p>{@code /bossbar set <id> name <component>}, {@code /scoreboard objectives add <obj>
         * <criteria> <displayName>}, {@code /team modify <team> displayName <component>}. The tail
         * of these is mostly code — an objective name, a criteria, a bossbar id — and a display name
         * is always either a quoted string or a JSON component, while an identifier never is. So
         * the quoted segments are exactly the readable half, and taking the whole tail would be the
         * identifier false positive §12 forbids.
         */
        NAMED,
        /**
         * CBScript's own player-text statements: {@code tell} / {@code title} / {@code subtitle} /
         * {@code actionbar}. The LAST quoted literal on the statement.
         *
         * <p>The grammar puts the text in a final {@code NORMSTRING}
         * ({@code codeblock : title fullselector [const const const] string}), and a
         * {@code NORMSTRING} cannot span a newline, so the last quote on the line is the message
         * and nothing before it can be. Unused by the agent so far — 0 occurrences in 5,187 real
         * sources, which are written with raw {@code /tellraw} — and covered anyway, because a new
         * capability makes new wrong outputs possible and this one is already in the language.
         */
        KEYWORD,
        /**
         * A readable NBT / component member anywhere in the source. The first quoted value after it.
         *
         * <p>This is the channel the agent actually uses: an entity's {@code CustomName}, an item's
         * {@code custom_name} and {@code lore}, a sign's {@code messages}. Note it is member-based
         * rather than command-based on purpose — the same {@code CustomName} arrives through
         * {@code /summon}, {@code /give}, {@code /data modify … set value} and a {@code loot_table}
         * definition, and one row covers all four.
         */
        MEMBER
    }

    /**
     * <b>Every way CBScript can put words in front of a child, and nothing else.</b>
     *
     * <p>Derived from the language, not from prose: {@link Channel#CHAT} and {@link Channel#NAMED}
     * from the {@code COMMAND} token, {@link Channel#KEYWORD} from the four text
     * keywords in {@code scriptlex.py}, {@link Channel#MEMBER} from the vanilla NBT and component
     * members that hold a text component. Adding one is a row.
     *
     * <p>Two deliberate absences, each one a measured decision:
     * <ul>
     *   <li><b>{@code Name}</b> — {@code block_state:&#123;Name:"minecraft:black_concrete"&#125;}
     *       is a block id, and it occurs 61 times in the committed corpus against 11 for
     *       {@code CustomName}. A bare {@code Name} row would sweep five times more identifiers
     *       than text.</li>
     *   <li><b>{@code $print(…)}</b> — it writes to the compiler log, which only a developer
     *       reads.</li>
     * </ul>
     */
    public static final List<Rule> CHANNELS = List.of(
            // The chat verbs. `w` and `tm` are the vanilla aliases of `msg` and `teammsg`, and
            // `me` is included because an emote is still a line in the chat a child reads.
            new Rule(Channel.CHAT, List.of("say", "tellraw", "title", "tell", "msg", "w", "me",
                    "teammsg", "tm")),
            new Rule(Channel.NAMED, List.of("bossbar", "scoreboard", "team")),
            new Rule(Channel.KEYWORD, List.of("tell", "title", "subtitle", "actionbar")),
            // `text` and `translate` are the catch-all and the reason the list above it need not be
            // exhaustive: every readable string in a datapack ends up inside a text component, so
            // a component reached through a member nobody listed is still swept at its own `text`.
            // That is what makes this immune to a rewrite of the component format.
            new Rule(Channel.MEMBER, List.of("CustomName", "custom_name", "item_name",
                    "Lore", "lore", "display_name", "messages", "pages", "title", "description",
                    "text", "translate")));

    /**
     * One channel and the words that open it.
     *
     * @param channel how to read it
     * @param tokens  the command verbs, keywords or member names, lower-cased for CHAT/NAMED/KEYWORD
     *                and case-sensitive for MEMBER (both {@code CustomName} and {@code custom_name}
     *                are real and spelled differently by Minecraft itself)
     */
    public record Rule(Channel channel, List<String> tokens) { }

    /**
     * One piece of player-readable text, and where it came from.
     *
     * @param line    1-based line in the source the agent wrote, so a refusal can name it
     * @param channel which rule found it
     * @param token   the verb, keyword or member that opened the channel
     * @param text    the readable content — a superset of what is on screen, never less
     */
    public record Snippet(int line, Channel channel, String token, String text) {

        /** How a refusal and the log name this snippet. */
        public String label() {
            return switch (channel) {
                case CHAT, NAMED -> "line " + line + ", the `/" + token + "` command";
                case KEYWORD -> "line " + line + ", the `" + token + "` statement";
                case MEMBER -> "line " + line + ", the `" + token + "` text";
            };
        }
    }

    /**
     * One decision, plus what it would have been.
     *
     * @param act      what will actually happen — never {@link Act#BLOCK} in shadow mode
     * @param assessed what the sweep found, whatever the mode. Kept apart from {@code act} for the
     *                 reason {@link SafetyTextSweep.Decision} does: a test asserting the layer
     *                 THOUGHT the right thing while doing nothing is the only claim shadow mode can
     *                 make about itself
     * @param message  the refusal the agent reads; empty unless {@code act} is BLOCK
     * @param where    which snippet tripped, as {@link Snippet#label()}; empty when nothing did
     * @param verdict  what the lexicon said
     */
    public record Decision(Act act, Act assessed, String message, String where,
                           SafetyVerdict verdict) {

        /** Nothing readable, nothing found — or the OFF arm. */
        public static final Decision PASS =
                new Decision(Act.PASS, Act.PASS, "", "", SafetyVerdict.fine(
                        SafetyVerdict.Provenance.LEXICON));

        /** Should the compile be skipped? */
        public boolean refuses() {
            return act == Act.BLOCK;
        }
    }

    // ---- the question ----

    /**
     * Sweep one CBScript source before it is compiled.
     *
     * <p>Logs only the exceptions, like {@link SafetyTextSweep} and for the same reason: a record
     * per compile would be one more line per attempt in the file every other instrument in this repo
     * reads, and {@link com.moddingfromamod.datapack.CbScriptCompileLog} already counts the
     * attempts, so no denominator is lost.
     *
     * @param featureId the datapack namespace, used as the log's wish id when there is nothing
     *                  better — a compile is not always inside a wish (the catalogue and the
     *                  GameTests are not)
     * @param source    the CBScript body, after any Java-side {@code $&#123;…&#125;} substitution
     */
    public static Decision sweep(String featureId, String source) {
        if (source == null || source.isBlank() || !ChildSafetyConfig.grading()) {
            return Decision.PASS;
        }
        SafetyLexicon lexicon = SafetyLexicon.shared();
        for (Snippet s : extract(source)) {
            Optional<SafetyVerdict> hit = lexicon.check(s.text());
            if (hit.isEmpty()) {
                continue;
            }
            SafetyVerdict v = hit.get();
            if (v.overall() == SafetyVerdict.Band.CLEARLY) {
                // The FIRST snippet that trips is the one reported, exactly as at the tool choke
                // point: a refusal naming two lines invites the agent to rewrite both and re-check
                // neither, and if a second line is also bad the next compile finds it.
                return decide(Act.BLOCK, refusal(s), s, v, featureId);
            }
        }
        return Decision.PASS;
    }

    /** Log it, then say what happens. Written BEFORE the action, in every mode. */
    private static Decision decide(Act assessed, String message, Snippet where,
                                   SafetyVerdict verdict, String featureId) {
        boolean enforcing = ChildSafetyConfig.enforcing();
        // NO `tool`, deliberately. The obvious value is "author_datapack" and it would be a small
        // lie: most compiles are not from that tool at all (the item catalogue, the effect
        // templates, the spikes, every GameTest), and `ActionLog.safety` omits a blank tool rather
        // than writing one — so a consumer filtering by tool is not handed an inflated count for a
        // verb the agent never called. `layer: "datapack"` already says where the record came from,
        // and `wishId` carries the featureId, which is the only id a compile always has.
        //
        // THE LOGGED WORD IS THE ENUM'S OWN NAME, so the constant and the `action` field cannot
        // drift — the same rule as SafetyTextSweep. `Act.PASS` never reaches this method: a compile
        // with nothing to say returns Decision.PASS unlogged.
        // `currentPlayerId` is "" for the many compiles that are nobody's wish -- the item
        // catalogue, the effect templates, every GameTest -- which is the honest answer and the
        // same one `tool` is left blank for just above.
        ActionLog.safety(featureId, LAYER, verdict,
                (enforcing ? "" : "SHADOW_") + assessed.name(),
                0L, 0.0, "lexicon", null, "", 0, false, false,
                com.moddingfromamod.agent.AgentContext.currentPlayerId());
        if (!enforcing) {
            // Shadow acts on nothing — the refusal included, because a compile error the agent
            // reads is an action.
            return new Decision(Act.PASS, assessed, "", where.label(), verdict);
        }
        return new Decision(assessed == Act.BLOCK ? Act.BLOCK : Act.PASS, assessed, message,
                where.label(), verdict);
    }

    // ---- the words ----

    /**
     * The refusal the agent reads, shaped exactly like {@link SafetyTextSweep#refusal}.
     *
     * <p>Four things, in order, each a measured requirement rather than a flourish: that the datapack
     * was <b>not</b> compiled (so the agent does not assume it is live), WHICH line, WHAT to do
     * about it — rewrite that line, which is an alternative rather than a prohibition — and that the
     * rest of the script can stay, so a 200-line source is not re-derived from scratch.
     *
     * <p>And, as at every other layer: <b>do not tell the child.</b> §5's rule is that a child is
     * never told what was wrong with his wish; naming the category to him is a vocabulary lesson he
     * did not ask for and an invitation to find out how close he can get.
     */
    static String refusal(Snippet where) {
        return "SAFETY — that wording was not accepted, so the datapack was NOT compiled, is NOT "
                + "installed, and the child has NOT read it.\n"
                + "The problem is " + where.label() + ": it contains a word Moddy must not say to "
                + "a 7-year-old, and a datapack says its words again every time it runs.\n"
                + "Rewrite that one line in kid-safe words — the same idea in ordinary friendly "
                + "language — and author the datapack again.\n"
                + "Everything else about the script can stay exactly as it is.\n"
                + "Do NOT tell the child about this, do not apologise to him and do not mention "
                + "safety — just say it the other way and carry on.";
    }

    // ---- reading the source ----

    /**
     * Which member names open {@link Channel#MEMBER}, in one alternation.
     *
     * <p>⚠ Declared <b>after</b> {@link #CHANNELS} and it must stay there: static fields initialise
     * in source order, so this pattern reads a {@code CHANNELS} that is still null if it is moved
     * above it. That is catalog §A #3b of the migrator's pattern catalogue — a hoisted static that
     * compiles, loads, and is empty — and the failure here would be a sweep that matches no member
     * and reports a clean pass, which is the one shape {@code checked == 0} exists to catch.
     */
    private static final Pattern MEMBER = Pattern.compile(
            "(?<![\\w.])[\"']?(" + String.join("|", tokensOf(Channel.MEMBER)) + ")[\"']?\\s*[:=]\\s*");

    /**
     * How far past a member name to look for its value.
     *
     * <p>A bound rather than a cap on what is SWEPT, which is the distinction
     * {@link SafetyTextSweep} makes when it refuses a length cap outright: a cap on the text would
     * be a bypass exactly the length of the cap, while this only bounds how far a search for the
     * value runs before giving up. A member whose value is 4,000 characters away is not a member
     * with a value; the agent's own {@code /summon} NBT blobs are a few hundred at most.
     */
    private static final int MEMBER_VALUE_WINDOW = 4_000;

    /** How many {@code execute … run} wrappers to follow. */
    private static final int MAX_EXECUTE_DEPTH = 4;

    /**
     * Every piece of player-readable text in this source, in the order a reader would find it.
     *
     * <p>Pure, and the seam every test uses: no Minecraft, no lexicon, no config. Overlapping
     * channels are <b>not</b> deduplicated — a {@code /tellraw} tail is swept whole by
     * {@link Channel#CHAT} and its {@code "text"} members again by {@link Channel#MEMBER} — because
     * two readings of one line cost two linear scans and a missed reading costs a word on a child's
     * screen.
     */
    public static List<Snippet> extract(String source) {
        List<Snippet> out = new ArrayList<>();
        if (source == null || source.isEmpty()) {
            return out;
        }
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].strip();
            if (t.isEmpty()) {
                continue;
            }
            if (t.startsWith("/")) {
                command(t, i + 1, out);
            } else {
                keyword(t, i + 1, out);
            }
        }
        members(source, out);
        return out;
    }

    /**
     * A raw command line: follow {@code execute … run} to the real verb, then read per channel.
     *
     * <p>A leading slash is how the {@code COMMAND} token is defined in CBScript's own lexer
     * ({@code ^\s*}{@code /.+}), so unlike {@link SafetyTextSweep#visibleText} it is always present here —
     * but it is stripped at every depth anyway, because {@code execute … run /say} is a shape the
     * agent writes and a sweep that stopped at the inner slash would be blind exactly where the
     * command was hand-assembled.
     */
    private static void command(String line, int lineNo, List<Snippet> out) {
        String c = line;
        for (int depth = 0; depth < MAX_EXECUTE_DEPTH && !c.isEmpty(); depth++) {
            int sp = c.indexOf(' ');
            String first = (sp < 0 ? c : c.substring(0, sp)).toLowerCase(Locale.ROOT);
            if (first.startsWith("/")) {
                first = first.substring(1);
            }
            int colon = first.indexOf(':');
            String verb = colon >= 0 ? first.substring(colon + 1) : first;
            String rest = sp < 0 ? "" : c.substring(sp + 1).strip();
            if (tokensOf(Channel.CHAT).contains(verb)) {
                if (!rest.isEmpty()) {
                    out.add(new Snippet(lineNo, Channel.CHAT, verb, rest));
                }
                return;
            }
            if (tokensOf(Channel.NAMED).contains(verb)) {
                for (String seg : quotedSegments(rest)) {
                    if (!seg.isBlank()) {
                        out.add(new Snippet(lineNo, Channel.NAMED, verb, seg));
                    }
                }
                return;
            }
            if (!verb.equals("execute")) {
                return;
            }
            // The FIRST " run ", matching CommandGuard's own reading: everything after it is
            // another command, and a later `run` belongs to that one rather than to this.
            int run = c.toLowerCase(Locale.ROOT).indexOf(" run ");
            if (run < 0) {
                return;
            }
            c = c.substring(run + " run ".length()).strip();
        }
    }

    /** A CBScript {@code tell}/{@code title}/{@code subtitle}/{@code actionbar} statement. */
    private static void keyword(String line, int lineNo, List<Snippet> out) {
        int sp = line.indexOf(' ');
        if (sp < 0) {
            return;
        }
        String word = line.substring(0, sp).toLowerCase(Locale.ROOT);
        if (!tokensOf(Channel.KEYWORD).contains(word)) {
            return;
        }
        List<String> segs = quotedSegments(line.substring(sp + 1));
        if (!segs.isEmpty()) {
            String last = segs.get(segs.size() - 1);
            if (!last.isBlank()) {
                out.add(new Snippet(lineNo, Channel.KEYWORD, word, last));
            }
        }
    }

    /**
     * Readable NBT / component members, over the whole source rather than line by line.
     *
     * <p>Whole-source because a {@code /summon}'s NBT is routinely wrapped across lines by the
     * agent, and a member whose value sits on the next line is still a name the child reads. The
     * reported line is the member's own, which is the one worth rewriting.
     */
    private static void members(String source, List<Snippet> out) {
        Matcher m = MEMBER.matcher(source);
        while (m.find()) {
            int line = lineOf(source, m.start());
            for (String seg : memberValue(source, m.end())) {
                out.add(new Snippet(line, Channel.MEMBER, m.group(1), seg));
            }
        }
    }

    /**
     * The readable value of a member: ONE quoted segment, or <b>every</b> segment of a list.
     *
     * <p>One segment is the right answer for a scalar — {@code CustomName:'…'} — and taking more
     * would walk on into the rest of the NBT, which is identifiers. But {@code lore},
     * {@code messages} and {@code pages} are <b>lists</b>, and a list's elements are all readable
     * by definition: a sign has four lines, a book has many pages, and every one of them is a
     * sentence the child reads. So a value that opens with {@code [} is read to its matching
     * bracket and no further — the bracket is what bounds the scope, so nothing outside the list
     * is ever swept.
     *
     * <p>⚠ <b>Without this a sign's line 2 could ship.</b> The {@code text} catch-all covers the
     * common shape, because a component element carries its own {@code "text":} — measured, that
     * one member alone is 19,666 of the live log's snippets. But an element written as a BARE
     * string ({@code messages:['"line one"','"line two"']}, which the shipped catalogue really
     * uses) has no {@code text} member, so only the first element was read: a hole exactly the
     * shape of "everything after the first line of a sign". It is the same lesson as the quoted-key
     * spelling this file already records — a rule that fires and reads half its family reports a
     * clean zero.
     */
    private static List<String> memberValue(String source, int from) {
        int i = from;
        while (i < source.length() && Character.isWhitespace(source.charAt(i))) {
            i++;
        }
        int limit = Math.min(source.length(), from + MEMBER_VALUE_WINDOW);
        if (i >= limit) {
            return List.of();
        }
        if (source.charAt(i) != '[') {
            List<String> one = quotedSegments(source.substring(i, limit));
            return one.isEmpty() || one.get(0).isBlank() ? List.of() : List.of(one.get(0));
        }
        List<String> out = new ArrayList<>();
        int depth = 0;
        while (i < limit) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                int end = endOfQuoted(source, i, limit);
                if (end < 0) {
                    return out;   // unterminated: stop rather than guess where the list ends
                }
                String seg = unquote(source, i, end);
                if (!seg.isBlank()) {
                    out.add(seg);
                }
                i = end + 1;
                continue;
            }
            if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                depth--;
                if (depth <= 0) {
                    return out;
                }
            }
            i++;
        }
        return out;
    }

    /**
     * Every quoted segment's contents, in order — single or double quoted, backslash escapes
     * honoured, an unterminated quote ignored.
     *
     * <p>Both quote styles because Minecraft's own NBT uses them interchangeably and the agent does
     * too: {@code CustomName:'&#123;"text":"Fire Ghost"&#125;'} nests one inside the other, and the
     * outer single-quoted segment is the whole component, which is what should be swept. An
     * unterminated quote yields nothing, which is the right answer — a half-written literal is not
     * text a child will read.
     *
     * <p>⚠ <b>Hand-written on purpose; the obvious regex overflows the stack.</b> The natural
     * spelling is {@code "((?:\\.|[^"\\])*)"}, and Java compiles an ALTERNATION inside a
     * {@code *} into a recursive {@code Loop} node — one stack frame per character consumed. Over
     * the 4,000-character window {@link #members} reads, that is a {@code StackOverflowError}
     * inside {@code Pattern}, which is what the corpus replay hit on its first run: a crash, not a
     * finding, on a source the extractor should simply have read. A character loop is O(n), cannot
     * recurse, and says the escape rule out loud.
     */
    private static List<String> quotedSegments(String s) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char q = s.charAt(i);
            if (q != '"' && q != '\'') {
                i++;
                continue;
            }
            int end = endOfQuoted(s, i, s.length());
            if (end < 0) {
                return out;
            }
            out.add(unquote(s, i, end));
            i = end + 1;
        }
        return out;
    }

    /**
     * Index of the closing quote that matches the one at {@code open}, or −1 if there is none
     * before {@code limit}.
     *
     * <p>The one place the escape rule lives, so {@link #quotedSegments} and
     * {@link #memberValue} cannot disagree about where a literal ends — which for a scanner that
     * also has to skip quoted regions while counting brackets is not a theoretical worry.
     */
    private static int endOfQuoted(String s, int open, int limit) {
        char q = s.charAt(open);
        int j = open + 1;
        while (j < limit) {
            char c = s.charAt(j);
            if (c == '\\' && j + 1 < limit) {
                j += 2;
                continue;
            }
            if (c == q) {
                return j;
            }
            j++;
        }
        return -1;
    }

    /**
     * The contents of the literal between the quotes at {@code open} and {@code close}, with
     * escapes resolved to the CHARACTER they escape.
     *
     * <p>The escaped character rather than the backslash: the lexicon reads whole words, and a
     * literal {@code \} left between two letters would split one — which is how an escaped
     * component ({@code "\"text\":\"…\""}, exactly what {@code formatJsonText} emits) would
     * otherwise be unreadable to it.
     */
    private static String unquote(String s, int open, int close) {
        StringBuilder sb = new StringBuilder(close - open);
        int j = open + 1;
        while (j < close) {
            char c = s.charAt(j);
            if (c == '\\' && j + 1 < close) {
                sb.append(s.charAt(j + 1));
                j += 2;
                continue;
            }
            sb.append(c);
            j++;
        }
        return sb.toString();
    }

    /** 1-based line of an offset. */
    private static int lineOf(String source, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** The tokens of one channel, for the table to stay the single source of truth. */
    static List<String> tokensOf(Channel channel) {
        for (Rule r : CHANNELS) {
            if (r.channel() == channel) {
                return r.tokens();
            }
        }
        return List.of();
    }
}
