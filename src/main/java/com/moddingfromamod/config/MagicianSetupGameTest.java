package com.moddingfromamod.config;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.ui.MenuKit;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>The Magician setup, driven to its check with the programs stubbed</b> (an earlier issue's exit). The host opens
 * {@code /moddy magician} (a real PICK page), checks the programs (a stub that says Claude Code and Muse Code both
 * work), picks a wish model and a helper model, saves how each is paid for (the TUNE dialog's command), and runs the
 * check (stub steps, no model call). Magician turns on only after it passes; a LAN guest is refused at every step.
 *
 * <p>Its own batch: it hides any plug-in's override ({@code Plugins.hideForTest}), which is global.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class MagicianSetupGameTest {

    private static final Map<String, String> KEYS = Map.of("ANTHROPIC_API_KEY", "set-in-the-test", "MUSE_API_KEY", "set");

    private static HarnessProbe works() {
        return (h, env) -> new HarnessProbe.Result(h, true, "stub", Map.of(SupportedModels.Mode.SUBSCRIPTION, "",
                SupportedModels.Mode.API, ""));
    }

    /** Six steps that pass, and a flag that the check really ran them. */
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
            done.accept(ok("wish"));
        }
    }

    private static String pageTitle(ServerPlayer p) {
        return p.containerMenu instanceof MenuKit.PickMenu pm ? pm.page().title() : "";
    }

    @GameTest(template = "empty_test", batch = "magician_setup", timeoutTicks = 400)
    public static void theHostSetsItUpAndItTurnsOnOnlyAfterTheCheck(GameTestHelper helper) {
        ServerPlayer host = MockPlayerNetwork.inLevel(helper, "MagicianHost");
        ServerPlayer guest = MockPlayerNetwork.inLevel(helper, "MagicianGuest");
        Passing steps = new Passing();
        com.moddingfromamod.plugin.Plugins.shared().hideForTest(SettingsOverride.class, true);
        PlayerSettings.shared().clearForTest();
        MagicianCheckRecord.shared().clearForTest();
        SettingsCommand.hostForTest(host.getUUID());
        MagicianSetup.forTest(works(), KEYS::get, (p, picks) -> steps);
        Runnable cleanup = () -> {
            com.moddingfromamod.plugin.Plugins.shared().hideForTest(SettingsOverride.class, false);
            PlayerSettings.shared().clearForTest();
            MagicianCheckRecord.shared().clearForTest();
            SettingsCommand.hostForTest(null);
            MagicianSetup.forTest(null, null, null);
            MockPlayerNetwork.remove(host);
            MockPlayerNetwork.remove(guest);
        };
        try {
            helper.assertTrue(!Magician.enabled(), Component.literal("Magician is on before any setup"));
            // A guest never opens it (D18), and is told why.
            helper.assertTrue(!MagicianSetup.open(guest) && MockPlayerNetwork.chat(guest).stream()
                    .anyMatch(l -> l.contains(MagicianSetup.HOST_ONLY)), Component.literal("a guest opened the setup"));
            helper.assertTrue(MagicianSetup.open(host) && MagicianSetup.TITLE.equals(pageTitle(host)),
                    Component.literal("the host's setup did not open as a pick page: " + pageTitle(host)));
            // Nothing can be checked yet: no picks.
            helper.assertTrue(!MagicianSetup.problems(MagicianPicks.current()).isEmpty(),
                    Component.literal("the check was offered with nothing picked"));
            MagicianSetup.press(host, MagicianSetup.PROGRAMS);
        } catch (RuntimeException | AssertionError e) {
            cleanup.run();
            throw e;
        }
        // The program check and the end-to-end check run off the server thread and come back on it, so the test moves
        // on a tick at a time: 0 = waiting for the programs, 1 = waiting for the programs the new choices re-checked
        //, 2 = waiting for the check, 3 = done.
        int[] stage = {0};
        helper.onEachTick(() -> {
            if (stage[0] == 0 && MagicianSetup.probed().size() == 2 && !MagicianSetup.busy()) {
                stage[0] = 1;
                try {
                    pickAndCheck(helper, host, guest);
                } catch (RuntimeException | AssertionError e) {
                    cleanup.run();
                    throw e;
                }
            } else if (stage[0] == 1 && !MagicianSetup.busy()) {
                stage[0] = 2;
                MagicianSetup.press(host, MagicianSetup.CHECK);
            } else if (stage[0] == 2 && !MagicianSetup.busy() && steps.wished) {
                stage[0] = 3;
                try {
                    helper.assertTrue(Magician.enabled(), Component.literal(
                            "the check passed and Magician is still off: " + MockPlayerNetwork.chat(host)));
                    helper.assertTrue(MockPlayerNetwork.chat(host).stream().anyMatch(l -> l.contains("The check passed")),
                            Component.literal("the host was not told the check passed: " + MockPlayerNetwork.chat(host)));
                    // A new pick asks for the check again.
                    MagicianSetup.pressMain(host, SupportedModels.WONDER);
                    helper.assertTrue(!Magician.enabled(), Component.literal("a new pick left Magician on unchecked"));
                } finally {
                    cleanup.run();
                }
                helper.succeed();
            }
        });
    }

    private static void pickAndCheck(GameTestHelper helper, ServerPlayer host, ServerPlayer guest) {
        MagicianSetup.pressMain(host, SupportedModels.OPUS);
        helper.assertTrue("Wish model".equals(pageTitle(host)), Component.literal("no wish-model page"));
        MagicianSetup.pressCheap(host, SupportedModels.EMBER);
        // The TUNE dialog's Save, as its command runs it (one dialog, both ways of paying).
        var dialog = MagicianSetup.payDialog();
        MenuKit.decode(host, dialog);   // vanilla's own codec reads it
        helper.assertTrue(dialog.keys().equals(List.of("main_mode", "cheap_mode")),
                Component.literal("the pay dialog's inputs: " + dialog.keys()));
        host.getServer().getCommands().performPrefixedCommand(host.createCommandSourceStack(),
                "moddy magician pay api subscription");
        guest.getServer().getCommands().performPrefixedCommand(guest.createCommandSourceStack(),
                "moddy magician pay subscription api");
        MagicianPicks picks = MagicianPicks.current();
        helper.assertTrue(picks.equals(new MagicianPicks(SupportedModels.OPUS, "api", SupportedModels.EMBER,
                "subscription")), Component.literal("the picks did not take, or a guest changed them: " + picks));
        // the fallback page. A paid entry says its price as it is picked (D27); the order picked is the order tried.
        MagicianSetup.press(host, MagicianSetup.FALLBACK);
        helper.assertTrue("Wish model fallbacks".equals(pageTitle(host)), Component.literal("no fallback page"));
        helper.assertTrue(MagicianSetup.fallbackPage(SupportedModels.Role.MAIN).tiles().stream()
                        .noneMatch(t -> t.id().equals(SupportedModels.OPUS + ":api")),
                Component.literal("the pick itself was offered as its own fallback"));
        MagicianSetup.pressFallback(host, SupportedModels.Role.MAIN, SupportedModels.SPARK_CONTRIBUTOR + ":api");
        MagicianSetup.pressFallback(host, SupportedModels.Role.MAIN, SupportedModels.WONDER + ":subscription");
        helper.assertTrue("muse-spark-1.2-contributor:api,muse-wonder-1.0:subscription".equals(
                        ModdySettings.get(SettingsCatalog.MAIN_FALLBACK).orElse("")),
                Component.literal("the fallback list: " + ModdySettings.get(SettingsCatalog.MAIN_FALLBACK)));
        helper.assertTrue(MockPlayerNetwork.chat(host).stream().anyMatch(l -> l.contains("$0.10 in / $0.20 out")),
                Component.literal("the paid fallback's price was not said: " + MockPlayerNetwork.chat(host)));
        helper.assertTrue(MagicianSetup.fallbackPage(SupportedModels.Role.MAIN).tiles().stream()
                        .anyMatch(t -> t.label().startsWith("1. Muse Spark 1.2 Contributor") && t.selected()),
                Component.literal("the first fallback is not numbered 1"));
        MagicianSetup.pressFallback(host, SupportedModels.Role.MAIN, SupportedModels.WONDER + ":subscription");
        helper.assertTrue("muse-spark-1.2-contributor:api".equals(ModdySettings.get(SettingsCatalog.MAIN_FALLBACK)
                .orElse("")), Component.literal("pressing a chosen fallback did not take it out"));
        guest.getServer().getCommands().performPrefixedCommand(guest.createCommandSourceStack(),
                "moddy setting magician.fallback none");
        helper.assertTrue(!"none".equals(ModdySettings.get(SettingsCatalog.MAIN_FALLBACK).orElse("")),
                Component.literal("a guest changed the host's fallback"));
        // Typed straight at the action, Magician still refuses to turn on: it has not been checked.
        host.getServer().getCommands().performPrefixedCommand(host.createCommandSourceStack(),
                "moddy setting magician.enabled on");
        helper.assertTrue(!Magician.enabled(), Component.literal("Magician turned on without its check"));
        helper.assertTrue(MagicianSetup.problems(picks).isEmpty(),
                Component.literal("the check is not offered: " + MagicianSetup.problems(picks)));
        // a change says the Magician is off until checked, with a [Turn on] link
        helper.assertTrue(MockPlayerNetwork.chat(host).stream().anyMatch(l -> l.startsWith(MagicianSetup.OFF_UNTIL_CHECKED)
                        && l.contains(MagicianSetup.TURN_ON_LINK)),
                Component.literal("no turn-on prompt after a change: " + MockPlayerNetwork.chat(host)));
    }
}
