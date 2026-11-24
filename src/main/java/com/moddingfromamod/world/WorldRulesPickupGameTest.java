package com.moddingfromamod.world;

import net.minecraft.network.chat.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.WishQueue;
import com.moddingfromamod.agent.tools.AwaitWishTool;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.safety.SafetyGrader;
import com.moddingfromamod.safety.SafetyVerdict;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.wish.Wish;
import com.moddingfromamod.wish.WishBook;
import com.moddingfromamod.wish.WishProgress;
import com.moddingfromamod.wish.WishSource;
import com.moddingfromamod.wish.WishStatus;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Gate B for the pickup enforcement</b>: the REAL {@link AwaitWishTool}, a real wish in the
 * book, a real marshal run, the chat on the wire — and the world-rules grader STUBBED, because every
 * question here is about wiring ("is a world-wide wish refused before a worker is spent?"), never
 * about the model; the model's calibration is {@code tools/world-rules-eval.py} over the labelled
 * corpus. The safety grader is stubbed FINE so the only decision in play is the world's.
 *
 * <p>ONE BATCH PER TEST: the rules in force and both graders' stubs are process-wide statics, and
 * batches are strictly sequential (CLAUDE.md), so a batch each is real isolation.
 *
 * <p>The locked done-when, one test each: a clearly world-wide wish is refused at pickup with the
 * kind message; an unsure one is granted locally; a mixed one is split; editing the rules file
 * changes behaviour with no restart.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class WorldRulesPickupGameTest {

    /** A shared island world's rules, inline: the committed file is checked by WorldRulesFilesTest. */
    static final String CLOSED_RULES = """
            {"store":"world-rules","v":1,"world":"closed","workshop":"Moddy's Workshop",
             "rules":[
              {"id":"same-time-for-all","type":"deny_world_effect","effects":["time"],
               "message":{"rule":"time is the same for every island","why":"so the sun rises and sets for everyone together"}},
              {"id":"your-own-island","type":"base_reach","effects":["outside_own_base","others_bases","other_dimensions"],
               "message":{"rule":"a wish stays on your own island","why":"every island belongs to the player who lives there"}}]}""";

    /** The same world after an operator's edit: time is allowed now. */
    static final String CLOSED_RULES_TIME_ALLOWED = """
            {"store":"world-rules","v":1,"world":"closed","workshop":"Moddy's Workshop",
             "rules":[
              {"id":"your-own-island","type":"base_reach","effects":["outside_own_base","others_bases","other_dimensions"],
               "message":{"rule":"a wish stays on your own island","why":"every island belongs to the player who lives there"}}]}""";

    private record Wishing(ServerPlayer player, Wish wish, WishQueue queue, StringBuilder said, Path log) { }

    private static WorldRules closedRules() {
        return WorldRules.parse(CLOSED_RULES, "closed").rules();
    }

    private static Wishing begin(GameTestHelper helper, String tag, WorldRules rules, WorldRulesVerdict graded) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        String text = "make it " + tag + " " + UUID.randomUUID().toString().substring(0, 8);
        Wish wish = WishBook.get(helper.getLevel().getServer()).add(Wish.create(text, WishSource.COMMAND,
                System.currentTimeMillis(), p.getUUID(), p.getGameProfile().name(),
                p.level().dimension().identifier().toString(), p.getX(), p.getY(), p.getZ()));
        Marshal.global().submit(wish.id(), p.getUUID(), text);
        WishProgress.startThinking(p, wish.id(), text);
        WishQueue queue = new WishQueue();
        helper.assertTrue(queue.offer(wish), Component.literal("the test queue refused the wish"));
        SafetyGrader.forTest(t -> SafetyVerdict.fine(SafetyVerdict.Provenance.HAIKU));
        if (rules != null) {
            WorldRulesService.forTest(rules);
        }
        WorldRulesGrader.forTest(graded == null ? null : t -> graded);
        Path log;
        try {
            log = Files.createTempFile("world-rules-", ".jsonl");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        WorldRulesLog.fileForTest(log);
        Wishing w = new Wishing(p, wish, queue, new StringBuilder(), log);
        helper.testInfo.addListener(new GameTestListener() {
            @Override public void testStructureLoaded(GameTestInfo info) {
            }

            @Override public void testPassed(GameTestInfo info, GameTestRunner runner) {
                end(w);
            }

            @Override public void testFailed(GameTestInfo info, GameTestRunner runner) {
                end(w);
            }

            @Override public void testAddedForRerun(GameTestInfo a, GameTestInfo b, GameTestRunner r) {
                end(w);
            }
        });
        return w;
    }

    private static void end(Wishing w) {
        SafetyGrader.forTest(null);
        WorldRulesGrader.forTest(null);
        WorldRulesService.forTest(null);
        WorldRulesLog.fileForTest(null);
        try {
            Marshal.global().cancel(w.wish().id(), com.moddingfromamod.marshal.CancelMode.STOP, "", "");
        } catch (RuntimeException ignored) {
            // already over
        }
        WishProgress.clearWishBar(w.wish().id());
        MockPlayerNetwork.remove(w.player());
    }

    private static WorldRulesVerdict verdict(WorldRulesVerdict.Kind k, String part, WorldEffect... effects) {
        EnumSet<WorldEffect> e = EnumSet.noneOf(WorldEffect.class);
        java.util.Collections.addAll(e, effects);
        return new WorldRulesVerdict(k, e, part, "", WorldRulesVerdict.Provenance.TEST);
    }

    private static AtomicReference<ToolResult> await(WishQueue queue, AtomicReference<Throwable> blew) {
        AtomicReference<ToolResult> out = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                out.set(new AwaitWishTool(queue).call(new JsonObject()));
            } catch (Throwable e) {
                blew.set(e);
            }
        }, "mfam-test-await-worldrules");
        t.setDaemon(true);
        t.start();
        return out;
    }

    /** Chat on the wire, accumulated (readOutbound is destructive and succeedWhen re-runs). */
    private static String chat(Wishing w) {
        var channel = w.player().connection.getConnection().channel();
        if (channel instanceof io.netty.channel.embedded.EmbeddedChannel embedded) {
            Object o;
            while ((o = embedded.readOutbound()) != null) {
                if (o instanceof ClientboundSystemChatPacket c
                        && !(c.content().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents)) {
                    w.said().append(c.content().getString()).append('\n');
                }
            }
        }
        return w.said().toString();
    }

    private static String logged(Wishing w) {
        try {
            return Files.readString(w.log(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    // ── a clearly world-wide wish is refused at pickup, with the kind message ────────────────────

    @GameTest(template = "empty_test", batch = "worldrules_refuse", timeoutTicks = 600)
    public static void aClearlyWorldWideWishIsRefusedAtPickupWithTheKindMessage(GameTestHelper helper) {
        Wishing w = begin(helper, "daytime forever", closedRules(),
                verdict(WorldRulesVerdict.Kind.WORLD_WIDE, "", WorldEffect.TIME));
        AtomicReference<Throwable> blew = new AtomicReference<>();
        AtomicReference<ToolResult> out = await(w.queue(), blew);
        helper.succeedWhen(() -> {
            helper.assertTrue(blew.get() == null, Component.literal("await_wish threw: " + blew.get()));
            ToolResult r = out.get();
            helper.assertTrue(r != null, Component.literal("await_wish has not returned yet"));
            helper.assertTrue(r.isError() && r.text().contains("declined by this world's rules")
                            && r.text().contains("await_wish"),
                    Component.literal("a refusal is an error naming the next step: " + r.text()));
            helper.assertFalse(r.text().contains(w.wish().text()), Component.literal("the refused wish reached the agent: " + r.text()));
            helper.assertTrue(Marshal.global().runForWorker("") == null
                            || !w.wish().id().equals(Marshal.global().runForWorker("").wishId()),
                    Component.literal("no worker may be assigned a refused wish"));
            Wish after = WishBook.get(helper.getLevel().getServer()).byId(w.wish().id()).orElse(null);
            helper.assertTrue(after != null && after.status() == WishStatus.REJECTED,
                    Component.literal("the wish must be over (REJECTED): " + (after == null ? null : after.status())));
            String said = chat(w);
            helper.assertTrue(said.contains("I heard your wish") && said.contains("I know just how")
                            && said.contains("time is the same for every island")
                            && said.contains("so the sun rises") && said.contains("Moddy's Workshop")
                            && said.contains("Here,"),
                    Component.literal("the kind message — heard you, I know how, the rule and why, where it works, "
                            + "something instead: " + said));
            helper.assertFalse(said.toLowerCase(java.util.Locale.ROOT).contains("charge"),
                    Component.literal("a refusal never costs anything, and never says so as if it might: " + said));
            String log = logged(w);
            helper.assertTrue(log.contains("\"kind\":\"grade\"") && log.contains("\"act\":\"REFUSE\"")
                            && log.contains("\"rule\":\"same-time-for-all\""),
                    Component.literal("every grade is logged with its verdict and rule: " + log));
        });
    }

    // ── an unsure one is granted locally, with the world's rules in the brief ────────────────────

    @GameTest(template = "empty_test", batch = "worldrules_unsure", timeoutTicks = 600)
    public static void anUnsureWishIsGrantedLocallyWithTheRulesInTheBrief(GameTestHelper helper) {
        Wishing w = begin(helper, "rain cod", closedRules(), verdict(WorldRulesVerdict.Kind.UNSURE, ""));
        AtomicReference<Throwable> blew = new AtomicReference<>();
        AtomicReference<ToolResult> out = await(w.queue(), blew);
        helper.succeedWhen(() -> {
            helper.assertTrue(blew.get() == null, Component.literal("await_wish threw: " + blew.get()));
            ToolResult r = out.get();
            helper.assertTrue(r != null, Component.literal("await_wish has not returned yet"));
            helper.assertFalse(r.isError(), Component.literal("an unsure wish is granted: " + r.text()));
            helper.assertTrue(r.text().contains(w.wish().text()), Component.literal("the agent gets the wish: " + r.text()));
            helper.assertTrue(r.text().contains("THIS WORLD'S RULES") && r.text().contains("ON HIS OWN BASE")
                            && r.text().contains("WORLD RULES — UNSURE"),
                    Component.literal("…with the world's rules in its brief, and the unsure note: " + r.text()));
            helper.assertTrue(logged(w).contains("\"act\":\"UNSURE\""), Component.literal("logged: " + logged(w)));
        });
    }

    // ── a mixed one is split: the allowed part granted, the rest explained ──────────────────────

    @GameTest(template = "empty_test", batch = "worldrules_split", timeoutTicks = 600)
    public static void aMixedWishIsSplit(GameTestHelper helper) {
        Wishing w = begin(helper, "night and build a castle", closedRules(),
                verdict(WorldRulesVerdict.Kind.MIXED, "a castle on my island", WorldEffect.TIME));
        AtomicReference<Throwable> blew = new AtomicReference<>();
        AtomicReference<ToolResult> out = await(w.queue(), blew);
        helper.succeedWhen(() -> {
            helper.assertTrue(blew.get() == null, Component.literal("await_wish threw: " + blew.get()));
            ToolResult r = out.get();
            helper.assertTrue(r != null, Component.literal("await_wish has not returned yet"));
            helper.assertFalse(r.isError(), Component.literal("a split wish is granted: " + r.text()));
            helper.assertTrue(r.text().contains("a castle on my island") && r.text().contains("THIS WISH WAS SPLIT"),
                    Component.literal("the agent gets the ALLOWED part, and why: " + r.text()));
            String said = chat(w);
            helper.assertTrue(said.contains("I'll grant the part I can here: a castle on my island")
                            && said.contains("time is the same for every island"),
                    Component.literal("the child is told what is granted and why the rest is not: " + said));
            helper.assertTrue(logged(w).contains("\"act\":\"SPLIT\""), Component.literal("logged: " + logged(w)));
        });
    }

    // ── editing the rules file changes behaviour, with no restart ────────────────────────────────

    @GameTest(template = "empty_test", batch = "worldrules_reload", timeoutTicks = 900)
    public static void editingTheRulesFileChangesBehaviourWithNoRestart(GameTestHelper helper) {
        Path dir;
        try {
            dir = Files.createTempDirectory("world-rules-reload");
            Files.writeString(dir.resolve("world-rules.json"), CLOSED_RULES);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        Path file = dir.resolve("world-rules.json");
        // The real watcher on a real file; no test pin, so current() is what the FILE says.
        WorldRulesService.forTest(null);
        WorldRulesService.start(file, "closed", WorldRules.CLOSED);
        helper.assertTrue(WorldRulesService.current().denies(WorldEffect.TIME), Component.literal("the file denies time: "
                + WorldRulesService.current()));
        Wishing first = begin(helper, "day first", null,
                verdict(WorldRulesVerdict.Kind.WORLD_WIDE, "", WorldEffect.TIME));
        helper.testInfo.addListener(new GameTestListener() {
            @Override public void testStructureLoaded(GameTestInfo info) {
            }

            @Override public void testPassed(GameTestInfo info, GameTestRunner runner) {
                restore();
            }

            @Override public void testFailed(GameTestInfo info, GameTestRunner runner) {
                restore();
            }

            @Override public void testAddedForRerun(GameTestInfo a, GameTestInfo b, GameTestRunner r) {
                restore();
            }

            private void restore() {
                WorldRulesService.start(WorldRulesService.defaultFile(), WorldModuleHost.worldId(),
                        WorldRules.ALLOW_ALL);
            }
        });
        AtomicReference<Throwable> blew = new AtomicReference<>();
        AtomicReference<ToolResult> out1 = await(first.queue(), blew);
        AtomicReference<AtomicReference<ToolResult>> out2 = new AtomicReference<>();
        AtomicReference<Wishing> second = new AtomicReference<>();
        helper.succeedWhen(() -> {
            helper.assertTrue(blew.get() == null, Component.literal("await_wish threw: " + blew.get()));
            ToolResult r1 = out1.get();
            helper.assertTrue(r1 != null, Component.literal("the first await has not returned yet"));
            helper.assertTrue(r1.isError(), Component.literal("under the first file, time is denied: " + r1.text()));
            if (second.get() == null) {
                // THE OWNER'S EDIT: the file is rewritten under the running server. No restart; the
                // watcher's own poll picks it up (driven now rather than waiting out its interval).
                try {
                    Files.writeString(file, CLOSED_RULES_TIME_ALLOWED);
                    Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(
                            System.currentTimeMillis() + 2_000));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                WorldRulesReloader.Outcome o = WorldRulesService.reloader().poll();
                helper.assertTrue(o == WorldRulesReloader.Outcome.LOADED, Component.literal("the edit must load: " + o));
                helper.assertFalse(WorldRulesService.current().denies(WorldEffect.TIME),
                        Component.literal("after the edit, time is allowed: " + WorldRulesService.current()));
                Wishing w2 = begin(helper, "day second", null,
                        verdict(WorldRulesVerdict.Kind.WORLD_WIDE, "", WorldEffect.TIME));
                second.set(w2);
                out2.set(await(w2.queue(), blew));
            }
            ToolResult r2 = out2.get().get();
            helper.assertTrue(r2 != null, Component.literal("the second await has not returned yet"));
            helper.assertFalse(r2.isError(), Component.literal("the same verdict, after the edit, must be granted: " + r2.text()));
            helper.assertTrue(r2.text().contains(second.get().wish().text()), Component.literal("the agent got it: " + r2.text()));
        });
    }

    // ── the rule's words pass the sweep when said (#43): a line that no longer passes is replaced ──

    /** The same world, its why carrying a made-up word the bundled lexicon does not know, so the file loads. */
    static final String CLOSED_RULES_ODD_WORD = CLOSED_RULES.replace("so the sun rises and sets for everyone together",
            "so the zorblaxian sun rises for everyone together");

    /**
     * The file loaded with the lexicon of the day; then an operator's override adds a term its words now trip. The
     * refusal at pickup is still made, and what he reads is the fixed line, not the file's words: the line is swept
     * when it is said ({@code WishFeedback.worldRule}), not only when the file was read.
     */
    @GameTest(template = "empty_test", batch = "worldrules_swept", timeoutTicks = 600)
    public static void aRuleLineTheLexiconNowFlagsIsReplacedWhenSaid(GameTestHelper helper) {
        WorldRules.Parsed parsed = WorldRules.parse(CLOSED_RULES_ODD_WORD, "closed");
        helper.assertTrue(parsed.ok(), Component.literal("the file loads with the bundled lexicon: " + parsed.errors()));
        Path override = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get()
                .resolve(com.moddingfromamod.safety.SafetyLexicon.OVERRIDE_DIR)
                .resolve(com.moddingfromamod.safety.SafetyLexicon.FILE_NAME);
        String before;
        try {
            before = Files.isRegularFile(override) ? Files.readString(override, StandardCharsets.UTF_8) : null;
            String bundled;
            try (var in = com.moddingfromamod.safety.SafetyLexicon.class.getResourceAsStream(
                    "/safety/" + com.moddingfromamod.safety.SafetyLexicon.FILE_NAME)) {
                bundled = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Files.createDirectories(override.getParent());
            Files.writeString(override, (before != null ? before : bundled) + "\nVIOLENCE\tzorblaxian\tOTHER\n",
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("could not write the lexicon override", e);
        }
        com.moddingfromamod.safety.SafetyLexicon.reload();
        Runnable restore = () -> {
            try {
                if (before == null) {
                    Files.deleteIfExists(override);
                } else {
                    Files.writeString(override, before, StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) {
                // the next reload reads whatever is there
            }
            com.moddingfromamod.safety.SafetyLexicon.reload();
        };
        helper.testInfo.addListener(new GameTestListener() {
            @Override public void testStructureLoaded(GameTestInfo info) {
            }

            @Override public void testPassed(GameTestInfo info, GameTestRunner runner) {
                restore.run();
            }

            @Override public void testFailed(GameTestInfo info, GameTestRunner runner) {
                restore.run();
            }

            @Override public void testAddedForRerun(GameTestInfo a, GameTestInfo b, GameTestRunner r) {
                restore.run();
            }
        });
        Wishing w = begin(helper, "daytime forever", parsed.rules(),
                verdict(WorldRulesVerdict.Kind.WORLD_WIDE, "", WorldEffect.TIME));
        AtomicReference<Throwable> blew = new AtomicReference<>();
        AtomicReference<ToolResult> out = await(w.queue(), blew);
        helper.succeedWhen(() -> {
            helper.assertTrue(blew.get() == null, Component.literal("await_wish threw: " + blew.get()));
            ToolResult r = out.get();
            helper.assertTrue(r != null, Component.literal("await_wish has not returned yet"));
            helper.assertTrue(r.isError() && r.text().contains("declined by this world's rules"),
                    Component.literal("the world's rule still refuses it: " + r.text()));
            String said = chat(w);
            helper.assertTrue(said.contains(com.moddingfromamod.wish.WishFeedback.WORLD_RULE_FALLBACK),
                    Component.literal("the fixed line is said in place of the flagged one: " + said));
            helper.assertFalse(said.contains("zorblaxian"),
                    Component.literal("the flagged word reached his chat: " + said));
        });
    }
}
