package com.moddingfromamod.safety;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>L0 at CBScript compile time, and above all the condition set on it.</b>
 *
 * <p>§12 decision 10 does not merely ask for the sweep; it says <i>"verify it trips no false
 * positive on the current corpus of real compiles before it can refuse anything"</i>. So the
 * headline test here is not a fixture at all: it replays every CBScript program the agent has
 * really written — {@code corpus/datapacks.jsonl}, mined by {@code tools/mine-datapack-corpus.py},
 * plus every {@code .cbscript}/{@code .cbs} this mod ships — through the extractor and the
 * <b>shipped</b> lexicon, on every build. A sweep proven against hand-made fixtures is a sweep
 * proven against programs nobody runs.
 *
 * <p>And it asserts the SCOPE as well as the finding. "I looked and found nothing" and "I looked at
 * nothing" print the same word, and an extractor that silently matched no channel would report a
 * perfect clean sweep — so the replay requires a floor of readable snippets and requires that every
 * channel the corpus actually contains was exercised. That is the failure mode the migrator's
 * catalogue records over and over (§X27's {@code checked == 0}); it is cheaper to assert than to
 * discover.
 *
 * <p>The A/B is the other half: a lexicon term planted in a {@code /say} must be CAUGHT, or all the
 * zeroes above are the zeroes of a dead check.
 */
class SafetyDatapackSweepTest {

    private static final String MODE = ModdingFromAModMod.MODID + ".childsafety";
    private static final String HOME = ModdingFromAModMod.MODID + ".home";

    /** A term from the shipped lexicon that is CLEARLY on one axis. */
    private static final String CLEARLY = "cocaine";


    /**
     * The smallest number of readable snippets the committed corpus must yield.
     *
     * <p>A FLOOR rather than the exact count, so adding a corpus row does not fail this file — but
     * present, because the whole zero-false-positive claim is worthless if the extractor reads
     * nothing. Measured at 274 over the 193 sources this test reads (154 mined + 39 shipped); the
     * floor is deliberately well under that so ordinary churn is not a failure while a collapse
     * is.
     */
    private static final int MIN_CORPUS_SNIPPETS = 40;

    private String homeBefore;

    @BeforeEach
    void ownHome(@TempDir Path dir) {
        homeBefore = System.getProperty(HOME);
        System.setProperty(HOME, dir.toString());
        // The ENFORCING arm, explicitly: everything below but the shadow twin is a claim about
        // what enforcement does.
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

    private static void set(String key, String value) {
        System.setProperty(key, value);
        RuntimeConfig.invalidate();
    }

    // ── THE OWNER'S CONDITION: zero false positives on what the agent really wrote ───────────

    /** One real source, with something to name it by when it fails. */
    private record Source(String where, String body) { }

    /** Every CBScript program the agent has really written, out of the committed corpus. */
    private static List<Source> minedCorpus() {
        List<Source> out = new ArrayList<>();
        try (InputStream in = SafetyDatapackSweepTest.class
                .getResourceAsStream("/corpus/datapacks.jsonl")) {
            if (in == null) {
                return out;
            }
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("//")) {
                    continue;
                }
                JsonObject o = JsonParser.parseString(t).getAsJsonObject();
                out.add(new Source("corpus/datapacks.jsonl:" + o.get("id").getAsString(),
                        o.get("source").getAsString()));
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not read the datapack corpus", e);
        }
        return out;
    }

    /**
     * Every CBScript this mod SHIPS — the item catalogue, the effect templates, the spikes.
     *
     * <p>Read off the source tree rather than the classpath: {@code .cbs} and {@code .cbscript} are
     * resources with no index, so there is nothing to enumerate at runtime, and the tree is the
     * thing a change would edit. Skipped rather than failed when the working directory is not the
     * project (a packaged run), because a missing corpus is an absent measurement and not a defect
     * — the mined corpus above is on the classpath and always measured.
     */
    private static List<Source> shippedScripts() {
        List<Source> out = new ArrayList<>();
        Path root = Path.of("src", "main", "resources");
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String n = p.getFileName().toString();
                if (n.endsWith(".cbs") || n.endsWith(".cbscript")) {
                    out.add(new Source(p.toString(), Files.readString(p, StandardCharsets.UTF_8)));
                }
            }
        } catch (Exception e) {
            throw new UncheckedIOException("could not read the shipped CBScript",
                    new java.io.IOException(e));
        }
        return out;
    }

    @Test
    @DisplayName("every CBScript the agent has really written comes back clean — the "
            + "condition on this chunk, re-measured on every build")
    void theRealCorpusIsClean() {
        List<Source> all = new ArrayList<>(minedCorpus());
        all.addAll(shippedScripts());
        assertFalse(all.isEmpty(), "no real CBScript was scanned at all — this test would then "
                + "pass having measured nothing, which is the failure it exists to prevent");

        SafetyLexicon lexicon = SafetyLexicon.shared();
        assertTrue(lexicon.size() > 0, "the shipped lexicon loaded EMPTY, so every source below "
                + "would come back clean for the wrong reason");

        int snippets = 0;
        Map<SafetyDatapackSweep.Channel, Integer> byChannel =
                new EnumMap<>(SafetyDatapackSweep.Channel.class);
        List<String> hits = new ArrayList<>();
        for (Source s : all) {
            for (SafetyDatapackSweep.Snippet sn : SafetyDatapackSweep.extract(s.body())) {
                snippets++;
                byChannel.merge(sn.channel(), 1, Integer::sum);
                lexicon.check(sn.text()).ifPresent(v -> {
                    if (v.overall() == SafetyVerdict.Band.CLEARLY) {
                        hits.add(s.where() + " @ " + sn.label() + " → " + v.overall()
                                + " on " + v.reasons() + ": " + clip(sn.text()));
                    }
                });
            }
        }

        // The SCOPE, before the finding. A silent extractor reports the same zero as a clean one.
        assertTrue(snippets >= MIN_CORPUS_SNIPPETS,
                "the extractor found only " + snippets + " readable snippet(s) across " + all.size()
                        + " real source(s), under the floor of " + MIN_CORPUS_SNIPPETS
                        + " — either a channel stopped matching or the corpus shrank. A zero-hit "
                        + "sweep over nothing is not a clean sweep.");
        // Every channel the real corpus is known to contain. CHAT is /say + /tellraw + /title,
        // MEMBER is CustomName + custom_name + lore + messages -- both are in the mined corpus and
        // in the shipped catalogue, so a missing one means the reading broke rather than that the
        // corpus changed. NAMED and KEYWORD are deliberately NOT required: the agent has never
        // written either (0 occurrences in 3,863 real sources), so requiring them would fail on a
        // fact about the agent rather than about this code.
        assertTrue(byChannel.getOrDefault(SafetyDatapackSweep.Channel.CHAT, 0) > 0,
                "no /say, /tellraw or /title was read out of the real corpus, which is full of "
                        + "them — the CHAT channel has stopped matching");
        assertTrue(byChannel.getOrDefault(SafetyDatapackSweep.Channel.MEMBER, 0) > 0,
                "no CustomName / custom_name / lore / messages was read out of the real corpus, "
                        + "which is full of them — the MEMBER channel has stopped matching");

        // Printed, not only asserted: the count IS the measurement the ledger quotes, and a number
        // in the log is what lets a later reader see a collapse that is still above the floor.
        System.out.println("[datapack sweep] " + all.size() + " real source(s), " + snippets
                + " readable snippet(s), " + hits.size() + " hit(s); by channel " + byChannel);

        assertTrue(hits.isEmpty(), "the datapack sweep would REFUSE " + hits.size() + " of "
                + all.size() + " real CBScript source(s) (" + snippets + " readable snippets "
                + "scanned). §12 decision 10 says this may not refuse anything until that number "
                + "is zero, and a hit here is a finding about the LEXICON or the EXTRACTOR, never "
                + "something to suppress:\n  " + String.join("\n  ", hits));
    }

    /**
     * The same sweep over the MACHINE-LOCAL compile log, opt-in and skipping loudly.
     *
     * <pre>
     *   MFAM_DATAPACK_SWEEP_LIVE=1 ./gradlew test --tests '*SafetyDatapackSweepTest*' -i
     * </pre>
     *
     * <p>{@code ~/.moddingfromamod/cbscript-compiles.jsonl} is every CBScript attempt this machine
     * has ever made — far more than the committed corpus, which is a mined and classified subset —
     * and on a machine that is played on it is the real play history rather than gate traffic. It is
     * deliberately NOT part of Gate A: a unit suite whose verdict depends on machine-local data is
     * the flakiness this repo keeps paying for, and the committed corpus above is the hermetic
     * measurement. But it skips <b>by name</b>, because a green tick for a check that never ran is
     * the disease — and it exists so the ledger's number comes from the SHIPPED extractor rather
     * than from a script written to estimate it.
     */
    @Test
    @DisplayName("the machine's whole compile log is clean too (opt-in: MFAM_DATAPACK_SWEEP_LIVE=1)")
    void theLiveCompileLogIsClean() {
        if (System.getenv("MFAM_DATAPACK_SWEEP_LIVE") == null) {
            System.out.println("[datapack sweep] MFAM_DATAPACK_SWEEP_LIVE not set — skipping the "
                    + "machine-local compile log. The committed corpus was still swept.");
            return;
        }
        Path log = Path.of(System.getProperty("user.home"), ".moddingfromamod",
                "cbscript-compiles.jsonl");
        assertTrue(Files.isRegularFile(log), "no compile log at " + log + " — nothing to measure, "
                + "and reporting a pass over an absent file is exactly what the opt-in avoids");
        SafetyLexicon lexicon = SafetyLexicon.shared();
        int sources = 0;
        int snippets = 0;
        Map<SafetyDatapackSweep.Channel, Integer> byChannel =
                new EnumMap<>(SafetyDatapackSweep.Channel.class);
        List<String> hits = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
                String t = line.strip();
                if (t.isEmpty() || !t.startsWith("{")) {
                    continue;
                }
                JsonObject o;
                try {
                    o = JsonParser.parseString(t).getAsJsonObject();
                } catch (RuntimeException torn) {
                    continue;   // a torn final line is normal in an append-only log
                }
                if (!o.has("source") || o.get("source").isJsonNull()) {
                    continue;
                }
                String src = o.get("source").getAsString();
                if (src.isBlank()) {
                    continue;
                }
                sources++;
                for (SafetyDatapackSweep.Snippet sn : SafetyDatapackSweep.extract(src)) {
                    snippets++;
                    byChannel.merge(sn.channel(), 1, Integer::sum);
                    lexicon.check(sn.text()).ifPresent(v -> {
                        if (v.overall() == SafetyVerdict.Band.CLEARLY) {
                            hits.add(sn.label() + ": " + clip(sn.text()));
                        }
                    });
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not read " + log, e);
        }
        System.out.println("[datapack sweep] LIVE " + sources + " source(s), " + snippets
                + " readable snippet(s), " + hits.size() + " hit(s); by channel " + byChannel);
        assertTrue(sources > 0, "the compile log holds no sources, so nothing was measured");
        assertTrue(hits.isEmpty(), "the sweep would refuse " + hits.size() + " real compile(s):\n  "
                + String.join("\n  ", hits));
    }

    @Test
    @DisplayName("…and it reads the actual words a child sees, not the code around them")
    void itReadsTheWordsAndNotTheIdentifiers() {
        // The shapes the real corpus uses, verbatim in form. Each one is a sentence a child reads.
        String source = String.join("\n",
                "function greet()",
                "    /say M64 PING",
                "    /tellraw @s [\"\",{\"text\":\"SUPER PLUMO 64 FACE! \",\"color\":\"red\"}]",
                "    /title @a title {\"text\":\"DINKO WINS!\",\"color\":\"green\"}",
                "    /summon zombie ~ ~ ~ {CustomName:'{\"text\":\"Fire Ghost\"}'}",
                "    /give @s snowball[minecraft:custom_name='{\"text\":\"Sparkle Bomb\"}']",
                "end");
        List<String> texts = SafetyDatapackSweep.extract(source).stream().map(s -> s.text())
                .toList();
        String joined = String.join(" | ", texts);
        for (String word : List.of("M64 PING", "SUPER PLUMO 64 FACE!", "DINKO WINS!", "Fire Ghost",
                "Sparkle Bomb")) {
            assertTrue(joined.contains(word),
                    "the sweep never read the child-visible words \"" + word + "\"; it saw: "
                            + joined);
        }
    }

    @Test
    @DisplayName("code is not text: a selector, an objective, a block id and a function name are "
            + "never swept")
    void identifiersAreNotSwept() {
        // Every line here is code, and every one of them contains a lexicon term as an
        // IDENTIFIER. The lexicon's word boundary is "no letter or digit either side", which makes
        // `_` a boundary -- so `meth_timer` matches the term `meth`, and a sweep of every string
        // literal in a source would refuse this whole file. That is exactly what §12 decision 10
        // rules out, and this is the test that says so.
        String source = String.join("\n",
                "import common",
                "",
                "define name meth = \"cocaine\"",
                "",
                "function meth_timer()",
                "    /scoreboard objectives add meth_count dummy",
                "    /tag @e[tag=rape_zone] add cocaine_flag",
                "    /setblock ~ ~ ~ minecraft:heroin_block",
                "    /summon zombie ~ ~ ~ {Tags:[\"meth\"],block_state:{Name:\"minecraft:meth\"}}",
                "    /data modify entity @s Inventory set value [{id:\"minecraft:meth\"}]",
                "end");
        List<SafetyDatapackSweep.Snippet> found = SafetyDatapackSweep.extract(source);
        assertTrue(found.isEmpty(),
                "code was read as readable text, which is the identifier false positive §12 "
                        + "decision 10 forbids: " + found);
        assertEquals(SafetyDatapackSweep.Act.PASS, SafetyDatapackSweep.sweep("dp", source).act(),
                "a source whose only lexicon terms are identifiers must compile");
    }

    // ── THE A/B: planted in a /say, it IS caught ────────────────────────────────────────────

    @Test
    @DisplayName("A/B — a lexicon term in a /say REFUSES the compile, so the zeroes above are not "
            + "the zeroes of a dead check")
    void aPlantedTermIsCaught() {
        SafetyDatapackSweep.Decision d = SafetyDatapackSweep.sweep("dp",
                "function greet()\n    /say you look like " + CLEARLY + "\nend");
        assertEquals(SafetyDatapackSweep.Act.BLOCK, d.act(), "the planted term was not caught");
        assertTrue(d.refuses(), "a BLOCK must refuse the compile");
        assertTrue(d.where().contains("line 2"),
                "the refusal must name the line the agent can rewrite; it said: " + d.where());
        assertEquals(SafetyVerdict.Band.CLEARLY, d.verdict().overall());
    }

    @Test
    @DisplayName("…in every channel it claims, not just the one the A/B used")
    void everyChannelIsReallyWired() {
        Map<String, String> perChannel = Map.of(
                "CHAT /say", "/say a bag of " + CLEARLY,
                "CHAT /tellraw", "/tellraw @a {\"text\":\"a bag of " + CLEARLY + "\"}",
                "CHAT through execute", "/execute as @a run say a bag of " + CLEARLY,
                "NAMED /bossbar", "/bossbar set minecraft:bar name {\"text\":\"" + CLEARLY + "\"}",
                "KEYWORD title", "title @a \"the " + CLEARLY + " bar\"",
                "MEMBER CustomName", "/summon pig ~ ~ ~ {CustomName:'{\"text\":\"" + CLEARLY
                        + "\"}'}",
                "MEMBER custom_name", "/give @s stick[minecraft:custom_name='{\"text\":\""
                        + CLEARLY + "\"}']",
                "MEMBER lore", "/give @s stick[minecraft:lore=['{\"text\":\"" + CLEARLY + "\"}']]",
                "MEMBER messages", "/setblock ~ ~ ~ oak_sign{front_text:{messages:['{\"text\":\""
                        + CLEARLY + "\"}']}}");
        List<String> missed = new ArrayList<>();
        perChannel.forEach((name, line) -> {
            if (!SafetyDatapackSweep.sweep("dp", "function f()\n    " + line + "\nend").refuses()) {
                missed.add(name);
            }
        });
        assertTrue(missed.isEmpty(), "these channels are in CHANNELS and catch nothing, so the "
                + "table claims coverage it does not have: " + missed);
    }

    @Test
    @DisplayName("every LINE of a sign is read, not just the first — including a bare-string line, "
            + "which has no `text` member to be caught by")
    void everyElementOfAListIsRead() {
        // The shipped catalogue really writes signs this way: some lines are components carrying
        // their own "text", and some are bare strings. The `text` catch-all covers the first kind,
        // so a reader that took only the FIRST element of the list left a hole exactly the shape of
        // "everything after line one of a sign".
        String bare = "/setblock ~ ~ ~ oak_sign{front_text:{messages:['\"line one\"',"
                + "'\"a bag of " + CLEARLY + "\"','\"line three\"']}}";
        assertTrue(SafetyDatapackSweep.sweep("dp", "function f()\n    " + bare + "\nend").refuses(),
                "the SECOND line of the sign was never read");
        List<String> texts = SafetyDatapackSweep.extract(bare).stream().map(s -> s.text()).toList();
        assertTrue(texts.stream().anyMatch(t -> t.contains("line three")),
                "the THIRD line was not read either; the sweep saw: " + texts);

        // …and the bracket is what bounds it: nothing after the list is swept, or a member row
        // would read the rest of the NBT, which is identifiers (the §12 rule this file exists for).
        String after = "/give @s stick[minecraft:lore=['\"hello\"'],minecraft:custom_data="
                + "{meth_counter:1b,tag:\"" + CLEARLY + "\"}]";
        List<SafetyDatapackSweep.Snippet> got = SafetyDatapackSweep.extract(after);
        assertTrue(got.stream().noneMatch(s -> s.token().equals("lore") && s.text().contains(CLEARLY)),
                "the lore member read past its own closing bracket and into the NBT after it: "
                        + got);
    }

    @Test
    @DisplayName("a scalar member reads ONE value, so it cannot walk into the NBT beside it")
    void aScalarMemberDoesNotOverread() {
        // CustomName is a scalar. Reading more than its own value would sweep whatever the summon
        // carries next, which is exactly the identifier false positive the channel table avoids.
        List<SafetyDatapackSweep.Snippet> got = SafetyDatapackSweep.extract(
                "/summon pig ~ ~ ~ {CustomName:'{\"text\":\"Bob\"}',Tags:[\"" + CLEARLY + "\"]}");
        assertTrue(got.stream().noneMatch(s -> s.token().equals("CustomName")
                        && s.text().contains(CLEARLY)),
                "CustomName read past its own value into Tags: " + got);
        assertTrue(got.stream().anyMatch(s -> s.text().contains("Bob")), "and it must read Bob");
    }

    @Test
    @DisplayName("a quoted literal comes back with its escapes RESOLVED, so the swept text is what "
            + "a reader would see rather than what the file spells")
    void escapesResolveToTheCharacter() {
        // An escaped component is what the compiler's own formatJsonText emits, and it is also how
        // an agent writes a nested one by hand. Asserted through KEYWORD rather than CHAT on
        // purpose: CHAT takes the whole raw tail, so it would pass whatever the scanner did with
        // the backslashes — a test that passes for the wrong reason. KEYWORD reads the literal's
        // CONTENTS, which is the only place the escape rule is observable.
        List<SafetyDatapackSweep.Snippet> got = SafetyDatapackSweep.extract(
                "title @a \"{\\\"text\\\":\\\"hello there\\\"}\"");
        assertEquals(1, got.size(), "expected exactly one KEYWORD snippet: " + got);
        assertEquals("{\"text\":\"hello there\"}", got.get(0).text(),
                "the escapes were not resolved, so the lexicon is reading the FILE's spelling "
                        + "rather than the reader's text");

        // And the reason it matters at all: an escape must not be left sitting inside a word,
        // where it would split one term into two the lexicon cannot match.
        assertTrue(SafetyDatapackSweep.sweep("dp",
                        "function f()\n    title @a \"{\\\"text\\\":\\\"a bag of " + CLEARLY
                                + "\\\"}\"\nend").refuses(),
                "a term inside an escaped component was not caught");
    }

    @Test
    @DisplayName("a term in a comment is still swept if it is in a readable line, and a bare "
            + "comment is not a readable line")
    void aCommentIsNotAChannel() {
        assertEquals(SafetyDatapackSweep.Act.PASS,
                SafetyDatapackSweep.sweep("dp", "# a note about " + CLEARLY + "\nfunction f()\nend")
                        .act(),
                "a CBScript comment is never shown to the child, so it is not a channel");
    }

    // ── THE ARMS ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("shadow assesses the block, acts on nothing, and logs SHADOW_BLOCK")
    void shadowActsOnNothing() {
        set(MODE, "shadow");
        SafetyDatapackSweep.Decision d = SafetyDatapackSweep.sweep("dp",
                "function f()\n    /say a bag of " + CLEARLY + "\nend");
        assertEquals(SafetyDatapackSweep.Act.BLOCK, d.assessed(),
                "shadow must still THINK the right thing — it is the only claim it can make");
        assertEquals(SafetyDatapackSweep.Act.PASS, d.act(), "shadow acts on nothing");
        assertFalse(d.refuses());
        assertTrue(d.message().isEmpty(),
                "a tool result the agent reads is an action, so shadow must not send one");
        String log = readAll(ActionLog.path());
        assertTrue(log.contains("SHADOW_BLOCK"), "shadow must log SHADOW_BLOCK, not BLOCK");
        assertTrue(log.contains("\"layer\":\"" + SafetyDatapackSweep.LAYER + "\""),
                "the record must name the datapack layer; log was: " + clip(log));
    }

    @Test
    @DisplayName("off grades nothing, logs nothing and costs nothing")
    void offIsTheControl() {
        set(MODE, "off");
        SafetyDatapackSweep.Decision d = SafetyDatapackSweep.sweep("dp",
                "function f()\n    /say a bag of " + CLEARLY + "\nend");
        assertEquals(SafetyDatapackSweep.Act.PASS, d.act());
        assertEquals(SafetyDatapackSweep.Act.PASS, d.assessed(),
                "the OFF arm must not even assess, or its cost is not zero");
        assertEquals("", readAll(ActionLog.path()), "the control arm must write no record");
    }

    @Test
    @DisplayName("the enforcing arm logs a bare BLOCK and names the datapack layer")
    void enforcingLogsABareBlock() {
        SafetyDatapackSweep.sweep("icy", "function f()\n    /say a bag of " + CLEARLY + "\nend");
        String log = readAll(ActionLog.path());
        assertTrue(log.contains("\"action\":\"BLOCK\""), "log was: " + clip(log));
        assertFalse(log.contains("SHADOW_"), "the enforcing arm must not write the shadow prefix");
        assertTrue(log.contains("\"layer\":\"datapack\""),
                "the layer must be the one docs/CHILD_SAFETY.md documents; log was: " + clip(log));
        assertTrue(log.contains("\"wishId\":\"icy\""),
                "the featureId is the only id a compile always has; log was: " + clip(log));
        // …and it claims no TOOL. `author_datapack` is the obvious value and would be a small lie:
        // the item catalogue, the effect templates and every GameTest compile without it, so a
        // consumer filtering by tool would get an inflated count for a verb the agent never called.
        assertFalse(log.contains("\"tool\""),
                "the record must not claim a tool — most compiles are not from author_datapack: "
                        + clip(log));
    }

    @Test
    @DisplayName("a clean source writes NO record — only the exceptions are logged")
    void aCleanCompileIsSilent() {
        SafetyDatapackSweep.sweep("dp", "function f()\n    /say hello there\nend");
        assertEquals("", readAll(ActionLog.path()),
                "a record per compile would be one more line per attempt in the file every other "
                        + "instrument here reads, and CbScriptCompileLog already counts them");
    }

    // ── THE WORDS ───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the refusal says the pack did not compile, names the line, asks for a rewrite, "
            + "and does not tell the child")
    void theRefusalIsActionable() {
        String m = SafetyDatapackSweep.sweep("dp",
                "function f()\n    /say a bag of " + CLEARLY + "\nend").message();
        assertTrue(m.contains("NOT compiled"), m);
        assertTrue(m.contains("line 2"), "the refusal must name the line: " + m);
        assertTrue(m.toLowerCase(java.util.Locale.ROOT).contains("rewrite"), m);
        assertTrue(m.contains("Do NOT tell the child"),
                "§5: a child is never told what was wrong with his wish. " + m);
        assertFalse(m.contains(CLEARLY),
                "the refusal must not quote the term back — an agent narrating its own refusal is "
                        + "how the child hears it anyway");
    }

    // ── THE TABLE ───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("every channel in the table has tokens, and no channel is declared twice")
    void theTableIsWellFormed() {
        List<SafetyDatapackSweep.Channel> seen = new ArrayList<>();
        for (SafetyDatapackSweep.Rule r : SafetyDatapackSweep.CHANNELS) {
            assertFalse(r.tokens().isEmpty(), r.channel() + " has no tokens, so it matches nothing "
                    + "while reading as covered");
            assertFalse(seen.contains(r.channel()), r.channel() + " is declared twice, and "
                    + "tokensOf returns the FIRST — so the second row is silently dead");
            seen.add(r.channel());
        }
        for (SafetyDatapackSweep.Channel c : SafetyDatapackSweep.Channel.values()) {
            assertTrue(seen.contains(c), c + " is a Channel with no row in CHANNELS, so extract() "
                    + "can never produce it");
        }
    }

    @Test
    @DisplayName("`Name` is deliberately NOT a member: it is a block id five times more often "
            + "than it is a name")
    void bareNameIsNotAMember() {
        assertFalse(SafetyDatapackSweep.tokensOf(SafetyDatapackSweep.Channel.MEMBER)
                        .contains("Name"),
                "block_state:{Name:\"minecraft:x\"} outnumbers CustomName 61 to 11 in the "
                        + "committed corpus; a bare `Name` row sweeps five times more identifiers "
                        + "than text");
        assertTrue(SafetyDatapackSweep.extract(
                        "    /summon block_display ~ ~ ~ {block_state:{Name:\"minecraft:" + CLEARLY
                                + "\"}}").isEmpty(),
                "a block id was read as readable text");
    }

    @Test
    @DisplayName("the member pattern was built AFTER the table — and an EMPTY alternation would "
            + "over-match every colon, not under-match, so both directions are asserted")
    void theMemberPatternSawTheTable() {
        // §A#3b of the migrator's catalogue, in this file: static fields initialise in source
        // order, so MEMBER compiled above CHANNELS would read a CHANNELS that is still null.
        //
        // ⚠ And the failure is NOT the one the shape suggests. `String.join("|", List.of())` is
        // the empty string, so the pattern becomes `["']?()["']?\s*[:=]\s*` — a group that matches
        // EMPTY at every position, i.e. every colon and every equals in the file. It would sweep
        // block ids, selectors and NBT keys wholesale, which is the identifier false positive §12
        // forbids. So "a real member IS read" is only half the guard; the other half is that a
        // colon which is not a member is read as NOTHING.
        assertNotEquals(0, SafetyDatapackSweep.extract(
                        "/summon pig ~ ~ ~ {CustomName:'{\"text\":\"Bob\"}'}").size(),
                "the MEMBER pattern matches nothing, which means it was compiled before CHANNELS "
                        + "was initialised");
        assertTrue(SafetyDatapackSweep.extract(
                        "/setblock ~ ~ ~ minecraft:stone{Tags:[\"a\"],facing:\"north\"}").isEmpty(),
                "a bare colon was read as a readable member, which is what an EMPTY alternation "
                        + "does — the pattern is matching the empty string at every position");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private static String readAll(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String clip(String s) {
        return s == null ? "" : (s.length() <= 240 ? s : s.substring(0, 240) + "…");
    }

    /**
     * the Magician check's own datapack sources go through the REAL sweep the way the check sends them. The
     * first live start-up check failed here, because a bare {@code tellraw} (no CBScript slash) is never read.
     */
    @Test
    void theMagicianChecksDatapackSourcesAreReadAndJudged() {
        assertFalse(SafetyDatapackSweep.sweep("moddy_check",
                com.moddingfromamod.config.MagicianCheck.DATAPACK_FRIENDLY).refuses());
        assertTrue(SafetyDatapackSweep.sweep("moddy_check",
                com.moddingfromamod.config.MagicianCheck.DATAPACK_TRIP).refuses());
        assertFalse(SafetyDatapackSweep.sweep("moddy_check", "tellraw @a \""
                + com.moddingfromamod.config.MagicianCheck.SWEEP_TRIP + "\"").refuses(),
                "a bare command is not what the sweep reads; if it now is, the check's sources may drop the slash");
    }
}
