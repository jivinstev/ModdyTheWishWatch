package com.moddingfromamod.safety;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ActionLog;
import com.moddingfromamod.runtime.RuntimeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>L0 at the tool choke point, with no model, no server and no child.</b>
 *
 * <p>The sweep is pure by construction, so every row of its table and every rule about it is
 * answerable in milliseconds. What this file deliberately does <b>not</b> do is stub the lexicon:
 * it grades against the SHIPPED {@code /safety/lexicon.txt}, because a sweep proven against a
 * fixture list is a sweep proven against a list nobody runs.
 */
class SafetyTextSweepTest {

    private static final String MODE = ModdingFromAModMod.MODID + ".childsafety";
    private static final String HOME = ModdingFromAModMod.MODID + ".home";

    /** A term from the shipped lexicon that is CLEARLY on one axis. */
    private static final String CLEARLY = "cocaine";

    /** A term from the shipped lexicon that is WELLBEING — a flag, never a band. */
    private static final String WELLBEING = "hurt myself";

    private String homeBefore;

    @BeforeEach
    void ownHome(@TempDir Path dir) {
        homeBefore = System.getProperty(HOME);
        System.setProperty(HOME, dir.toString());
        // The ENFORCING arm, explicitly: the shipped default is SHADOW, and everything below but
        // the shadow twin is a claim about what enforcement does.
        System.setProperty(MODE, "on");
        RuntimeConfig.invalidate();
        ActionLog.resetForTest();
        SafetyLexicon.reload();
    }

    @AfterEach
    void restore() {
        if (homeBefore == null) {
            System.clearProperty(HOME);
        } else {
            System.setProperty(HOME, homeBefore);
        }
        System.clearProperty(MODE);
        RuntimeConfig.invalidate();
        ActionLog.resetForTest();
        SafetyLexicon.reload();
    }

    // ---- fixtures ----

    private static void set(String key, String value) {
        System.setProperty(key, value);
        RuntimeConfig.invalidate();
    }

    private static JsonObject args(String key, String value) {
        JsonObject o = new JsonObject();
        o.addProperty(key, value);
        return o;
    }

    private static JsonObject argsArray(String key, String... values) {
        JsonObject o = new JsonObject();
        JsonArray a = new JsonArray();
        for (String v : values) {
            a.add(v);
        }
        o.add(key, a);
        return o;
    }

    private static SafetyTextSweep.Decision sweep(String tool, JsonObject args) {
        return SafetyTextSweep.sweep(tool, args, "abcd1234");
    }

    /** Every {@code safety} record this test's own home has collected, oldest first. */
    private static List<JsonObject> safetyRecords() throws Exception {
        return records("safety");
    }

    private static List<JsonObject> records(String kind) throws Exception {
        List<JsonObject> out = new ArrayList<>();
        Path f = "wellbeing".equals(kind) ? ActionLog.grownUpNotes() : ActionLog.path();
        if (f == null || !Files.exists(f)) {
            return out;
        }
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            if (kind.equals(o.get("kind").getAsString())) {
                out.add(o);
            }
        }
        return out;
    }

    // ---- the table ----

    /**
     * <b>The headline of the table: every listed field really is swept.</b>
     *
     * <p>Driven one field at a time through the real entry point, because the failure this guards
     * is a row that exists in {@link SafetyTextSweep#FIELDS} and is never read — which looks
     * identical, from outside, to a row that is read and finds nothing.
     */
    @Test
    @DisplayName("every field in the table is actually swept")
    void everyListedFieldIsSwept() {
        Set<String> covered = new LinkedHashSet<>();
        for (SafetyTextSweep.Field f : SafetyTextSweep.FIELDS) {
            SafetyTextSweep.Decision d = sweep(f.tool(), oneHit(f));
            assertTrue(d.refuses(),
                    "the sweep must refuse a CLEARLY term in " + f.tool() + "." + f.label()
                            + " — a row in FIELDS that is never read looks exactly like a row that "
                            + "is read and finds nothing");
            assertEquals(f.label(), d.field(),
                    "the decision must name the field that tripped");
            covered.add(f.tool() + "." + f.label());
        }
        assertEquals(SafetyTextSweep.FIELDS.size(), covered.size(),
                "two rows produced the same label, so one of them was never really exercised");
    }

    /** The arguments that put a CLEARLY term into exactly this field, in its own shape. */
    private static JsonObject oneHit(SafetyTextSweep.Field f) {
        String text = "Moddy made you a " + CLEARLY + " machine!";
        return switch (f.shape()) {
            case STRING -> args(f.arg(), text);
            case STRING_ARRAY -> argsArray(f.arg(), "Build the thing", text);
            case OBJECT_ARRAY_MEMBER -> {
                JsonObject o = new JsonObject();
                JsonArray arr = new JsonArray();
                JsonObject one = new JsonObject();
                one.addProperty(f.member(), text);
                arr.add(one);
                o.add(f.arg(), arr);
                yield o;
            }
            case COMMANDS -> argsArray(f.arg(), "say " + text);
        };
    }

    /**
     * <b>…and nothing else is.</b>
     *
     * <p>The expensive mistake in this whole layer is a false positive, so the sweep must be blind
     * to everything a child does not read. A block id, a shape name and a target selector are not
     * sentences, and refusing on one of them refuses a wish that was never a problem.
     */
    @Test
    @DisplayName("an unlisted field, and an unlisted tool, are not swept")
    void nothingOutsideTheTableIsSwept() {
        // Right tool, wrong field: notify_player's `message` is swept and nothing else of its is.
        assertFalse(sweep("notify_player", args("label", CLEARLY)).refuses(),
                "an argument with no row must not be read");
        // Right tool, right FIELD NAME, wrong argument: build_structure's `name` is swept (it is
        // what the child is told the thing is called) and its 20 KB shape array is not.
        assertFalse(sweep("build_structure", args("shapes", CLEARLY)).refuses(),
                "the payload must never be read — a block id and a shape name are not sentences, "
                        + "and reading them would cost every call and refuse wishes that were "
                        + "never a problem");
        // A tool with no row at all, carrying the term in the field a sibling tool DOES sweep.
        assertSame(SafetyTextSweep.Decision.PASS, sweep("paint_creature", args("name", CLEARLY)),
                "a tool with no row must exit before its arguments are touched at all");
    }

    // ---- the three outcomes ----

    @Test
    @DisplayName("a WELLBEING term passes, and writes a grown-up note")
    void wellbeingIsNeverARefusal() throws Exception {
        SafetyTextSweep.Decision d = sweep("notify_player",
                args("message", "I might " + WELLBEING + " if this does not work"));

        assertFalse(d.refuses(),
                "§1: a child in distress is never blocked, and that rule does not change because "
                        + "it was MODDY who said it — blocking here would delete the only evidence");
        assertEquals(SafetyTextSweep.Act.WELLBEING, d.assessed());
        assertTrue(d.verdict().wellbeing(), "the flag is the whole point of the record");
        assertEquals(SafetyVerdict.Band.FINE, d.verdict().overall(),
                "WELLBEING sets no band — a caller reading overall() must not see a refusal");

        assertEquals(1, records("wellbeing").size(),
                "the grown-up note is the action, since nothing was refused");
        assertTrue(records("wellbeing").get(0).get("text").getAsString().contains("notify_player"),
                "the note must say WHERE Moddy was about to say it: "
                        + records("wellbeing").get(0));

        List<JsonObject> log = safetyRecords();
        assertEquals(1, log.size());
        assertEquals("WELLBEING", log.get(0).get("action").getAsString(),
                "ONE vocabulary (decision 12.9). This used to write PASS while the INPUT layer "
                        + "wrote WELLBEING for the same finding, so `how often is a distress flag "
                        + "raised, and where` could not be answered by counting one field. The "
                        + "old reasoning was that WELLBEING was a word safety-report.py did not "
                        + "know — it has been in that tool's enforcing vocabulary since CS-S2");
        assertTrue(log.get(0).get("wellbeing").getAsBoolean());
        assertEquals("text", log.get(0).get("layer").getAsString());
    }

    @Test
    @DisplayName("a CLEARLY hit refuses, and the refusal names the field and the fix")
    void theRefusalNamesTheFieldAndTheFix() throws Exception {
        SafetyTextSweep.Decision d = sweep("notify_player",
                args("message", "Here is your " + CLEARLY + " potion!"));

        assertTrue(d.refuses());
        assertEquals("message", d.field());
        String msg = d.message();
        // RefusalRedirectTest's rule: a refusal that names no alternative makes the agent
        // improvise, which is worse than the sentence objected to.
        assertTrue(msg.contains("`message`"),
                "the refusal must name the FIELD, or the agent has a whole call to re-derive: "
                        + msg);
        assertTrue(msg.contains("notify_player"), "…and the tool to call again: " + msg);
        assertTrue(msg.toLowerCase(java.util.Locale.ROOT).contains("rewrite"),
                "it must say what to DO, not only what was wrong: " + msg);
        assertTrue(msg.contains("kid-safe"), "…and in what direction: " + msg);
        assertTrue(msg.contains("did NOT run"),
                "the agent must be told the child has not already read it, or it will assume the "
                        + "opposite and carry on: " + msg);
        // §5: the child is never told what was wrong with his wish.
        assertTrue(msg.contains("Do NOT tell the child"),
                "an agent left to itself narrates its own refusals, cheerfully: " + msg);
        // It must not repeat the offending word back in a sentence the agent might echo.
        assertFalse(msg.contains(CLEARLY),
                "the refusal must not quote the term — the agent copies its own tool results into "
                        + "chat more often than anyone expects: " + msg);

        List<JsonObject> log = safetyRecords();
        assertEquals(1, log.size());
        assertEquals("BLOCK", log.get(0).get("action").getAsString());
        assertEquals("text", log.get(0).get("layer").getAsString());
        assertEquals("notify_player", log.get(0).get("tool").getAsString());
        assertEquals("lexicon", log.get(0).get("grader").getAsString());
        assertEquals("CLEARLY", log.get(0).get("overall").getAsString());
    }

    @Test
    @DisplayName("ordinary text passes and writes nothing at all")
    void ordinaryTextIsFree() throws Exception {
        SafetyTextSweep.Decision d = sweep("notify_player",
                args("message", "SPIKOR IS HERE and he BREATHES FIRE! Look!"));
        assertSame(SafetyTextSweep.Decision.PASS, d);
        assertTrue(safetyRecords().isEmpty(),
                "a record per tool call per wish would be tens per wish, every one PASS FINE, in "
                        + "the file every other instrument in this repo reads");
    }

    /**
     * The edgiest real wishes are in {@code corpus/real-wishes.jsonl} and {@code SafetyLexiconTest}
     * already proves none of them trips the list. This is the other direction: the sentences MODDY
     * says about them.
     */
    @Test
    @DisplayName("the agent's own excited voice is not a refusal")
    void theRealVoiceOfTheModIsNotRefused() {
        List<String> real = List.of(
                "SPIKOR IS HERE and he BREATHES FIRE! Look!",
                "Your DYNAMITE sprays BUTT CHEEK WATER everywhere! Try it!",
                "The grinder is grinding mobs into meat — look at it go!",
                "I killed the ender dragon for you! The egg is yours.",
                "Milo's HEROBRINE eyes are HUGE now. Spooky!",
                "Two mobs are FIGHTING in the sky arena — go and watch!");
        for (String line : real) {
            assertFalse(sweep("notify_player", args("message", line)).refuses(),
                    "the floor must be silent on the mod's own voice: " + line);
        }
    }

    // ---- shadow ----

    @Test
    @DisplayName("shadow assesses the block and does not take it")
    void shadowActsOnNothing() throws Exception {
        set(MODE, "shadow");
        SafetyTextSweep.Decision d = sweep("notify_player",
                args("message", "Here is your " + CLEARLY + " potion!"));

        assertFalse(d.refuses(), "shadow acts on nothing — the tool result included");
        assertEquals(SafetyTextSweep.Act.PASS, d.act());
        assertEquals(SafetyTextSweep.Act.BLOCK, d.assessed(),
                "…and it must still say what it WOULD have done, or shadow mode collects no base "
                        + "rate and there is nothing to flip the default on");
        assertTrue(d.message().isEmpty(), "a refusal the agent reads is an action");

        List<JsonObject> log = safetyRecords();
        assertEquals(1, log.size());
        assertEquals("SHADOW_BLOCK", log.get(0).get("action").getAsString(),
                "the prefix is what stops a shadow BLOCK being counted as a child refused");
        assertEquals("shadow", log.get(0).get("mode").getAsString());
    }

    @Test
    @DisplayName("off sweeps nothing at all")
    void offIsTheControlArm() throws Exception {
        set(MODE, "off");
        assertSame(SafetyTextSweep.Decision.PASS, sweep("notify_player",
                args("message", "Here is your " + CLEARLY + " potion!")));
        assertTrue(safetyRecords().isEmpty(), "the OFF arm must not even log");
    }

    // ---- run_command ----

    @Test
    @DisplayName("a command a child READS is swept; one he cannot is not")
    void onlyTheVisibleCommandVerbsAreSwept() {
        for (String verb : List.of("say", "tellraw @a", "title @a title")) {
            assertTrue(sweep("run_command",
                            argsArray("command", verb + " " + CLEARLY)).refuses(),
                    "`" + verb + "` puts words on the child's screen: " + verb);
        }
        // The ~30 other allowed verbs place blocks, summon mobs and grant effects. None of them is
        // a sentence, and a tag or an id that happens to contain a term is not something he reads.
        for (String invisible : List.of(
                "summon minecraft:zombie ~ ~ ~ {Tags:[\"" + CLEARLY + "\"]}",
                "give @p diamond 1",
                "tag @e add " + CLEARLY,
                "scoreboard objectives add " + CLEARLY + " dummy")) {
            assertFalse(sweep("run_command", argsArray("command", invisible)).refuses(),
                    "a command nobody reads must not be swept: " + invisible);
        }
    }

    @Test
    @DisplayName("execute … run say is followed; a tellraw component is read as text")
    void theCommandShapesThatActuallyHappen() {
        assertTrue(sweep("run_command",
                        argsArray("command", "execute as @a at @s run say " + CLEARLY)).refuses(),
                "the wrapper is not what makes the text visible");
        assertTrue(sweep("run_command", argsArray("command",
                        "tellraw @a {\"text\":\"" + CLEARLY + "\",\"color\":\"red\"}")).refuses(),
                "a term inside a component is still a term — the lexicon matches whole words and "
                        + "JSON punctuation is neither a letter nor a digit");
        // The bare-string form the tool's body still accepts, because that is what a model sends.
        JsonObject bare = args("command", "say " + CLEARLY);
        assertTrue(sweep("run_command", bare).refuses(),
                "a sweep that only understood the documented shape would be blind to the degraded "
                        + "one, which is the one that actually arrives");
        // A batch: the second command is the bad one.
        assertTrue(sweep("run_command",
                        argsArray("command", "give @p diamond 1", "say " + CLEARLY)).refuses(),
                "every command in a batch is read, not just the first");
    }

    @Test
    @DisplayName("visibleText reads the tail of the verb and nothing else")
    void visibleTextIsTheTailOfTheVerb() {
        assertEquals("hello there", SafetyTextSweep.visibleText("say hello there").orElse(""));
        assertEquals("hello", SafetyTextSweep.visibleText("/say hello").orElse(""),
                "a leading slash is not part of the API and the agent sends one anyway");
        assertEquals("hello", SafetyTextSweep.visibleText("minecraft:say hello").orElse(""));
        assertTrue(SafetyTextSweep.visibleText("say").isEmpty(), "a verb with no tail says nothing");
        assertTrue(SafetyTextSweep.visibleText("give @p diamond").isEmpty());
        assertTrue(SafetyTextSweep.visibleText("execute as @a run give @p diamond").isEmpty(),
                "execute is followed, and what it runs is still not visible");
        assertTrue(SafetyTextSweep.visibleText(null).isEmpty());
        assertTrue(SafetyTextSweep.visibleText("").isEmpty());
    }

    // ---- the table itself ----

    @Test
    @DisplayName("the table names real tools and real arguments")
    void everyRowNamesSomethingThatExists() {
        var registry = com.moddingfromamod.agent.WishAgent.buildTools();
        for (SafetyTextSweep.Field f : SafetyTextSweep.FIELDS) {
            var tool = registry.byName(f.tool());
            assertTrue(tool != null,
                    "SafetyTextSweep names a tool that does not exist: " + f.tool()
                            + " — a row pointing at nothing is a field nobody is sweeping");
            JsonObject schema = tool.inputSchema();
            assertTrue(schema.has("properties")
                            && schema.getAsJsonObject("properties").has(f.arg()),
                    f.tool() + " has no `" + f.arg() + "` argument. Either the schema was renamed "
                            + "and this row went silently dead, or the row was wrong from the "
                            + "start — the plan's own list named make_item.description, which has "
                            + "never existed.");
        }
    }

    /**
     * The fields §3 L0 lists must all be present. Written as a SUBSET check rather than an equality
     * one: this table is allowed to cover more than the plan remembered (it does — the plan has no
     * row for {@code make_item.variants[].name}), and it is not allowed to cover less.
     */
    @Test
    @DisplayName("every field the plan names is in the table")
    void thePlanIsCovered() {
        Set<String> have = new LinkedHashSet<>();
        for (SafetyTextSweep.Field f : SafetyTextSweep.FIELDS) {
            have.add(f.tool() + "." + f.arg());
        }
        for (String want : List.of(
                "notify_player.message", "finish_building.say", "finish_wish.note",
                "finish_wish.suggestion", "set_status.doing", "plan_wish.status",
                "plan_wish.steps", "make_item.name", "modify_mob.name", "conjure_at.message",
                "run_command.command")) {
            assertTrue(have.contains(want),
                    "docs/CHILD_SAFETY_PLAN.md §3 L0 names " + want + " and the table does not "
                            + "have it: " + have);
        }
    }

    /**
     * <b>Every creation verb's display name is swept, and the plan named only one of them.</b>
     *
     * <p>§3 L0 lists {@code modify_mob}'s rename field — the verb that RENAMES a creature — and
     * none of the five verbs that name one in the first place. Each of their schemas says, in its
     * own words, that the string is read by the child ("Display name shown above the creature",
     * "What to call it to the child"). Pinned separately from the table so that a later trim has to
     * argue with a sentence rather than delete a row.
     */
    @Test
    @DisplayName("every creation verb's display name is swept, not only modify_mob's")
    void namingIsCoveredOnEveryClass() {
        for (String tool : List.of("define_creature", "sculpt_creature", "build_structure",
                "make_item", "modify_mob")) {
            assertTrue(sweep(tool, args("name", "The " + CLEARLY + " Express")).refuses(),
                    tool + ".name is a display name the child reads; it must be swept");
        }
    }
}
