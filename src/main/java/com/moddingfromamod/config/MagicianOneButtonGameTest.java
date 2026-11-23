package com.moddingfromamod.config;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.ui.MenuKit;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>The Magician from the watch, with one button</b>, driven with the programs and the check stubbed:
 *
 * <ul>
 *   <li>the watch's tile for the host is "✪ Turn on the Magician", a guest's "✪ Magician: off", and reading it looks
 *       for no program;</li>
 *   <li>the setup's first page has one button per program found, "Use API keys" only with a key set;</li>
 *   <li>"Use my Claude subscription" sets the four choices, checks Claude Code, runs the full check and turns the
 *       Magician on, with one result line; the tile then says what it runs on;</li>
 *   <li>a program that is not signed in stops it with the one thing to do, and the check never runs;</li>
 *   <li>with no working Python 3 the Magician still turns on, and one result line names Python and the fix;
 *       with Python there, nothing is said about it.</li>
 * </ul>
 *
 * <p>Its own batch: it hides any plug-in's override and sets the setup's statics, which are global.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class MagicianOneButtonGameTest {

    /** Counts every look and every call, so a test can say nothing ran. */
    private static final class Probe implements HarnessProbe {
        final AtomicInteger looked = new AtomicInteger();
        final AtomicInteger ran = new AtomicInteger();
        final AtomicInteger pythonAsked = new AtomicInteger();
        final boolean claudeSignedIn;
        final com.moddingfromamod.datapack.PythonCheck.Verdict python;

        Probe(boolean claudeSignedIn) {
            this(claudeSignedIn, new com.moddingfromamod.datapack.PythonCheck.Verdict(
                    com.moddingfromamod.datapack.PythonCheck.Kind.FOUND, "/stand-in/python3", "3.12.1", ""));
        }

        Probe(boolean claudeSignedIn, com.moddingfromamod.datapack.PythonCheck.Verdict python) {
            this.claudeSignedIn = claudeSignedIn;
            this.python = python;
        }

        @Override
        public com.moddingfromamod.datapack.PythonCheck.Verdict python() {
            pythonAsked.incrementAndGet();
            return python;
        }

        @Override
        public boolean installed(SupportedModels.Harness h) {
            looked.incrementAndGet();
            return true;
        }

        @Override
        public Result probe(SupportedModels.Harness h, java.util.function.Function<String, String> env) {
            ran.incrementAndGet();
            String sub = h == SupportedModels.Harness.CLAUDE_CODE && !claudeSignedIn
                    ? "not signed in: run `claude` once and sign in" : "";
            return new Result(h, true, "stub", Map.of(SupportedModels.Mode.SUBSCRIPTION, sub));
        }
    }

    private static final class Passing implements MagicianCheck.Steps {
        volatile boolean wished;

        private static MagicianCheck.Step ok(String n) {
            return new MagicianCheck.Step(n, true, "ok (stub)", 0.0001);
        }

        public MagicianCheck.Step inputAllows() {
            return ok("input");
        }

        public MagicianCheck.Step inputRefuses() {
            return ok("refusal");
        }

        public MagicianCheck.Step plan() {
            return ok("plan");
        }

        public MagicianCheck.Step textSweep() {
            return ok("text");
        }

        public MagicianCheck.Step datapackSweep() {
            return ok("datapack");
        }

        public void wish(Consumer<MagicianCheck.Step> done) {
            wished = true;
            done.accept(ok(MagicianCheck.WISH_STEP));
        }
    }

    private static Runnable setUp(ServerPlayer host, Probe probe, Passing steps, Map<String, String> env,
                                  ServerPlayer... others) {
        com.moddingfromamod.plugin.Plugins.shared().hideForTest(SettingsOverride.class, true);
        PlayerSettings.shared().clearForTest();
        MagicianCheckRecord.shared().clearForTest();
        SettingsCommand.hostForTest(host.getUUID());
        MagicianSetup.forTest(probe, env::get, (p, picks) -> steps);
        return () -> {
            com.moddingfromamod.plugin.Plugins.shared().hideForTest(SettingsOverride.class, false);
            PlayerSettings.shared().clearForTest();
            MagicianCheckRecord.shared().clearForTest();
            SettingsCommand.hostForTest(null);
            MagicianSetup.forTest(null, null, null);
            MockPlayerNetwork.remove(host);
            for (ServerPlayer o : others) {
                MockPlayerNetwork.remove(o);
            }
        };
    }

    private static java.util.List<String> tileIds(ServerPlayer p) {
        return p.containerMenu instanceof MenuKit.PickMenu pm
                ? pm.page().tiles().stream().map(t -> t.id()).toList() : java.util.List.of();
    }

    @GameTest(template = "empty_test", batch = "magician_one_button", timeoutTicks = 400)
    public static void oneButtonTurnsTheMagicianOn(GameTestHelper helper) {
        ServerPlayer host = MockPlayerNetwork.inLevel(helper, "OneButtonHost");
        ServerPlayer guest = MockPlayerNetwork.inLevel(helper, "OneButtonGuest");
        Probe probe = new Probe(true);
        Passing steps = new Passing();
        Runnable cleanup = setUp(host, probe, steps, Map.of(), guest);
        try {
            // The watch's tile: the host's way in, a guest's "off"; reading it looks for nothing (MagicianOffTest).
            helper.assertTrue(MagicianSetup.watchTile(host).orElse("").equals(MagicianPresets.TURN_ON),
                    Component.literal("the host's tile: " + MagicianSetup.watchTile(host)));
            helper.assertTrue(MagicianSetup.watchTile(guest).orElse("").equals(MagicianPresets.OFF_GUEST),
                    Component.literal("the guest's tile: " + MagicianSetup.watchTile(guest)));
            helper.assertTrue(probe.looked.get() == 0 && probe.ran.get() == 0 && probe.pythonAsked.get() == 0,
                    Component.literal("the watch looked for a program before anything was pressed"));
            // The tile runs /moddy magician: a guest is told only the host sets it up.
            guest.getServer().getCommands().performPrefixedCommand(guest.createCommandSourceStack(), "moddy magician");
            helper.assertTrue(MockPlayerNetwork.chat(guest).contains(MagicianSetup.HOST_ONLY),
                    Component.literal("the guest was not told why: " + MockPlayerNetwork.chat(guest)));
            host.getServer().getCommands().performPrefixedCommand(host.createCommandSourceStack(), "moddy magician");
            helper.assertTrue(tileIds(host).containsAll(java.util.List.of(MagicianPresets.CLAUDE, MagicianPresets.MUSE,
                            MagicianSetup.MANUAL)) && !tileIds(host).contains(MagicianPresets.API),
                    Component.literal("the first page's buttons (no key set): " + tileIds(host)));
            helper.assertTrue(probe.ran.get() == 0 && probe.pythonAsked.get() == 0,
                    Component.literal("opening the page ran a program"));
            MagicianSetup.press(host, MagicianPresets.CLAUDE);
            helper.assertTrue(MagicianPicks.current().equals(MagicianPresets.CLAUDE_SUBSCRIPTION.picks()),
                    Component.literal("the one button did not set the four choices: " + MagicianPicks.current()));
        } catch (RuntimeException | AssertionError e) {
            cleanup.run();
            throw e;
        }
        boolean[] done = {false};
        helper.onEachTick(() -> {
            if (done[0] || MagicianSetup.busy() || !steps.wished) {
                return;
            }
            done[0] = true;
            try {
                helper.assertTrue(Magician.enabled(), Component.literal(
                        "one button and the Magician is still off: " + MockPlayerNetwork.chat(host)));
                helper.assertTrue(MockPlayerNetwork.chat(host).contains(
                                MagicianPresets.turnedOn(MagicianPresets.CLAUDE_SUBSCRIPTION)),
                        Component.literal("no result line: " + MockPlayerNetwork.chat(host)));
                helper.assertTrue(probe.pythonAsked.get() > 0
                                && MockPlayerNetwork.chat(host).stream().noneMatch(l -> l.contains("Python")),
                        Component.literal("Python was not checked, or a working one was talked about: "
                                + MockPlayerNetwork.chat(host)));
                helper.assertTrue(MockPlayerNetwork.chat(host).stream().noneMatch(l -> l.startsWith("Set ")),
                        Component.literal("the one button said each choice: " + MockPlayerNetwork.chat(host)));
                helper.assertTrue(MagicianSetup.watchTile(host).orElse("").equals("✪ Magician: on (Opus 5.5)"),
                        Component.literal("the tile once on: " + MagicianSetup.watchTile(host)));
                helper.assertTrue(MagicianSetup.watchTile(guest).isEmpty(),
                        Component.literal("a guest still has an 'off' tile with the Magician on"));
            } finally {
                cleanup.run();
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty_test", batch = "magician_one_button_fails", timeoutTicks = 400)
    public static void aProgramNotSignedInSaysTheOneThingToDo(GameTestHelper helper) {
        ServerPlayer host = MockPlayerNetwork.inLevel(helper, "OneButtonNoSignIn");
        Probe probe = new Probe(false);
        Passing steps = new Passing();
        Runnable cleanup = setUp(host, probe, steps, Map.of("ANTHROPIC_API_KEY", "set-in-the-test"));
        try {
            MagicianSetup.open(host);
            helper.assertTrue(tileIds(host).contains(MagicianPresets.API),
                    Component.literal("a key is set and there is no 'Use API keys': " + tileIds(host)));
            MagicianSetup.press(host, MagicianPresets.CLAUDE);
        } catch (RuntimeException | AssertionError e) {
            cleanup.run();
            throw e;
        }
        boolean[] done = {false};
        helper.onEachTick(() -> {
            if (done[0] || MagicianSetup.busy() || probe.ran.get() == 0) {
                return;
            }
            done[0] = true;
            try {
                helper.assertTrue(!Magician.enabled() && !steps.wished,
                        Component.literal("the check ran, or it turned on, without a sign-in"));
                String want = "✖ Claude Code isn't signed in. Run `claude` once in a terminal, sign in, then press the"
                        + " button again.";
                helper.assertTrue(MockPlayerNetwork.chat(host).contains(want),
                        Component.literal("not the one thing to do: " + MockPlayerNetwork.chat(host)));
            } finally {
                cleanup.run();
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty_test", batch = "magician_one_button_no_python", timeoutTicks = 400)
    public static void noPythonStillTurnsOnAndSaysTheOneThingToDo(GameTestHelper helper) {
        ServerPlayer host = MockPlayerNetwork.inLevel(helper, "OneButtonNoPython");
        Probe probe = new Probe(true, com.moddingfromamod.datapack.PythonCheck.Verdict.missing());
        Passing steps = new Passing();
        Runnable cleanup = setUp(host, probe, steps, Map.of());
        try {
            MagicianSetup.open(host);
            MagicianSetup.press(host, MagicianPresets.CLAUDE);
        } catch (RuntimeException | AssertionError e) {
            cleanup.run();
            throw e;
        }
        boolean[] done = {false};
        helper.onEachTick(() -> {
            if (done[0] || MagicianSetup.busy() || !steps.wished) {
                return;
            }
            done[0] = true;
            try {
                helper.assertTrue(Magician.enabled(), Component.literal(
                        "no Python and the Magician stayed off: " + MockPlayerNetwork.chat(host)));
                java.util.List<String> chat = MockPlayerNetwork.chat(host);
                String on = MagicianPresets.turnedOn(MagicianPresets.CLAUDE_SUBSCRIPTION);
                String py = com.moddingfromamod.datapack.PythonCheck.setupLine(
                        com.moddingfromamod.datapack.PythonCheck.Verdict.missing(),
                        com.moddingfromamod.datapack.PythonCheck.windows()).orElseThrow();
                helper.assertTrue(py.contains("Python 3") && py.contains("python.org") && py.contains("press the button"),
                        Component.literal("the line does not name Python and the fix: " + py));
                helper.assertTrue(chat.contains(on) && chat.contains(py) && chat.indexOf(py) > chat.indexOf(on),
                        Component.literal("not 'on' then the Python line: " + chat));
                helper.assertTrue(chat.stream().filter(l -> l.contains("Python")).count() == 1,
                        Component.literal("more than one Python line: " + chat));
                helper.assertTrue(MagicianSetup.python() != null && !MagicianSetup.python().works(),
                        Component.literal("the setup did not remember that Python is missing"));
            } finally {
                cleanup.run();
            }
            helper.succeed();
        });
    }
}
