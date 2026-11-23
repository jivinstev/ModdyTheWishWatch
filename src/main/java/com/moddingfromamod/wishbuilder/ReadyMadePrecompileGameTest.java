package com.moddingfromamod.wishbuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.datapack.PrecompiledPacks;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;

/**
 * <b>Every Builder and Apprentice power is made with NO Python</b>. Each power the Builder offers — every
 * special move of a creature, every (stock item, power, strength), every magic block (look, trigger, what it does,
 * strength), every "turn into" pick in the ability test, every item and machine preset — is made through the real
 * Builder ({@link BuilderMake}, the real tools) while the CBScript compiler's Python is switched OFF
 * ({@code cbscript.python=off}), so a make stands only if its pack came from the precompiled store
 * ({@link PrecompiledPacks}). The packs are not loaded (the tools' own A/B seams skip activation): what they DO is the
 * ability tests' job, which a {@code -Ptestprops=cbscript.python=off} Gate B run proves the same way.
 *
 * <p>A make that fails here is a ready-made the store does not hold: a template, a catalogue, the compiler or a
 * tool's rendering changed without {@code ./tools/precompile-ready-mades.sh}. That script runs this test with
 * {@code -Ptestprops=precompiled.record=<dir>} (Python ON), and every skeleton it asks for is written there to be
 * compiled into the store.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class ReadyMadePrecompileGameTest {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    private static final String PY = ModdingFromAModMod.MODID + ".cbscript.python";
    private static final String SKIP_ITEM = ModdingFromAModMod.MODID + ".skipItemActivate";
    private static final String SKIP_EFFECT = ModdingFromAModMod.MODID + ".skipEffectActivate";

    private ReadyMadePrecompileGameTest() {
    }

    /** One make: a label for the failure and the Builder spec. */
    record Make(String label, java.util.function.Supplier<BuilderSpec> spec) {
        Make(String label, String json) {
            this(label, () -> BuilderSpec.read(JsonParser.parseString(json).getAsJsonObject()));
        }
    }

    /**
     * Every power the Builder offers, as specs: every (stock, power, strength); each colour scheme, each tier and each
     * item look at least once; every magic block (what it does, when, strength) with the looks taking turns and each
     * look at least once; the turn-into cases; every item and machine preset; every creature move.
     */
    static List<Make> makes() {
        List<Make> out = new ArrayList<>();
        for (BuilderAbilities.Ability a : BuilderAbilities.ALL) {
            out.add(new Make("move " + a.id(), "{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\","
                    + "\"size\":1,\"name\":\"Mover\",\"powers\":[\"ability:" + a.id() + "\"]}"));
        }
        List<String[]> pairs = new ArrayList<>();
        for (BuilderItemPowers.Stock s : BuilderItemPowers.STOCK) {
            for (BuilderItemPowers.Power w : BuilderItemPowers.fitting(s)) {
                if (w.id().equals(BuilderTurnInto.POWER) || w.effect().isEmpty()) {
                    continue;   // turn-into's picks are From and To (below); "just for fun" is the item looks (below)
                }
                pairs.add(new String[] {s.id(), w.id()});
                for (int strength = 1; strength <= 3; strength++) {
                    out.add(item("item " + s.id() + " + " + w.id() + " @" + strength, s.id(), w.id(), strength, "", "", ""));
                }
            }
        }
        int i = 0;
        for (Schemes.Scheme sc : Schemes.ALL) {
            String[] pw = pairs.get(i++ % pairs.size());
            out.add(item("scheme " + sc.id() + " on " + pw[0] + " + " + pw[1], pw[0], pw[1], 2, sc.id(), "", ""));
        }
        for (BuilderItemPowers.Stock s : BuilderItemPowers.STOCK) {
            for (String[] pw : pairs) {
                if (pw[0].equals(s.id()) && BuilderItemPowers.args(s.id(), pw[1], 2, BuilderItemPowers.TIER_BASIC,
                        "", "") != null) {
                    out.add(item("tier basic " + s.id() + " + " + pw[1], s.id(), pw[1], 2, "",
                            BuilderItemPowers.TIER_BASIC, ""));
                    break;
                }
            }
        }
        for (BuilderLooks.Look look : BuilderLooks.all()) {
            out.add(new Make("look " + look.id(), () -> BuilderLooksGameTest.pressed(look.id(), BuilderLooks.SHOW)));
        }
        for (BuilderAbilityGameTest.Case c : BuilderAbilityGameTest.turnIntoCases()) {
            out.add(new Make("turn into " + c.name(), c.spec()));
        }
        List<BuilderBlocks.Look> looks = BuilderBlocks.LOOKS;
        int k = 0;
        for (BuilderBlocks.Does d : BuilderBlocks.DOES) {
            for (String when : d.whens()) {
                for (int strength = 1; strength <= 3; strength++) {
                    out.add(block(looks.get(k++ % looks.size()), when, d, strength));
                }
            }
        }
        for (BuilderBlocks.Look look : looks) {
            BuilderBlocks.Does d = BuilderBlocks.DOES.get(k++ % BuilderBlocks.DOES.size());
            out.add(block(look, d.whens().get(0), d, 2));
        }
        // Composed snack machines: every food look, the treats, strengths, ingredients, fills and both ways of
        // pouring taking turns, and every pair of treats that may ride together.
        List<BuilderItemPowers.Power> treats = BuilderSnacks.TREATS;
        int n = 0;
        for (BuilderSnacks.Look look : BuilderSnacks.LOOKS) {
            String ingredient = ingredient(look, n);
            out.add(snack("snack " + look.id(), look, treats.get(n % treats.size()).id(), "", 1 + n % 3, ingredient,
                    1 + n % 8, n % 2 == 0 ? "pour" : "convert"));
            n++;
        }
        // "Just yummy" is offered only for a plain food (a Popcorn needs a power): the first look that has one.
        BuilderSnacks.Look plain = BuilderSnacks.LOOKS.stream()
                .filter(l -> !BuilderSnacks.machine(l).plain().isEmpty()).findFirst().orElseThrow();
        out.add(snack("snack yummy " + plain.id(), plain, BuilderSnacks.YUMMY.id(), "", 2, ingredient(plain, 0), 3,
                "pour"));
        for (int a = 0; a < treats.size(); a++) {
            for (int b = a + 1; b < treats.size(); b++) {
                if (BuilderSnacks.LOOKS.isEmpty() || BuilderLimits.problem("snack_powers",
                        List.of(treats.get(a).id(), treats.get(b).id())) != null) {
                    continue;
                }
                BuilderSnacks.Look look = BuilderSnacks.LOOKS.get(n++ % BuilderSnacks.LOOKS.size());
                out.add(snack("snack " + look.id() + " " + treats.get(a).id() + "+" + treats.get(b).id(), look,
                        treats.get(a).id(), treats.get(b).id(), 2, ingredient(look, n), 4, n % 2 == 0 ? "pour" : "convert"));
            }
        }
        for (String m : Catalogue.presets()) {
            if (!m.startsWith("items/")) {
                continue;
            }
            JsonObject j = LibraryPresets.SHIPPED.read(m);
            String shape = j == null || !j.has("shape") ? "" : j.get("shape").getAsString();
            String kind = "machine".equals(shape) ? "machine" : "magic_block".equals(shape) ? "magic_block" : "item";
            out.add(new Make("preset " + m, BuilderAbilityGameTest.spec(kind, m)));
        }
        return out;
    }

    private static Make item(String label, String stock, String power, int strength, String scheme, String tier,
                             String look) {
        String name = BuilderItemPowers.name(stock, power);
        return new Make(label, "{\"builder\":1,\"kind\":\"item\",\"from\":\"\""
                + (look.isEmpty() ? ",\"name\":\"" + name + "\"" : "")
                + (scheme.isEmpty() ? "" : ",\"scheme\":\"" + scheme + "\"")
                + ",\"opts\":{\"stock\":\"" + stock + "\",\"power\":\"" + power + "\",\"strength\":\"" + strength + "\""
                + (tier.isEmpty() ? "" : ",\"tier\":\"" + tier + "\"")
                + (look.isEmpty() ? "" : ",\"" + BuilderLooks.SLOT + "\":\"" + look + "\"") + "}}");
    }

    /**
     * an ingredient the look's machine takes, as the Builder offers it (its own first, then the others in turn).
     * This used to send none, or any ingredient whatever the look, and every one of those makes was REFUSED ("'' cannot
     * go in a snack machine") and logged as passing, so no snack machine here was ever made.
     */
    private static String ingredient(BuilderSnacks.Look look, int n) {
        List<String> in = BuilderSnacks.ingredientsFor(look);
        return "ingredient:" + in.get(n % in.size());
    }

    private static Make snack(String label, BuilderSnacks.Look look, String power, String power2, int strength,
                              String ingredient, int fill, String how) {
        JsonObject spec = JsonParser.parseString("{\"builder\":1,\"kind\":\"machine\",\"from\":\"\"}")
                .getAsJsonObject();
        spec.addProperty("name", "Zoomy " + look.label());
        JsonObject opts = new JsonObject();
        opts.addProperty("look", look.id());
        opts.addProperty("snack_power", power);
        if (!power2.isEmpty()) {
            opts.addProperty("snack_power2", power2);
        }
        opts.addProperty("strength", String.valueOf(strength));
        opts.addProperty("ingredient", ingredient);
        opts.addProperty("fill", String.valueOf(fill));
        opts.addProperty("how", how);
        spec.add("opts", opts);
        return new Make(label + " @" + strength + " fill " + fill + " " + how, spec.toString());
    }

    private static Make block(BuilderBlocks.Look look, String when, BuilderBlocks.Does d, int strength) {
        JsonObject spec = JsonParser.parseString("{\"builder\":1,\"kind\":\"magic_block\",\"from\":\"\"}")
                .getAsJsonObject();
        spec.addProperty("name", BuilderBlocks.name(look, d));
        JsonObject opts = new JsonObject();
        BuilderBlocks.opts(look.id(), when, d.id(), strength).forEach(opts::addProperty);
        spec.add("opts", opts);
        return new Make("block " + look.id() + "/" + when + "/" + d.id() + " @" + strength, spec.toString());
    }

    @GameTest(template = "empty_test", batch = "readyMadePrecompile", timeoutTicks = 400000)
    public static void everyBuilderPowerIsMadeWithNoPython(GameTestHelper helper) {
        boolean recording = System.getProperty(ModdingFromAModMod.MODID + ".precompiled.record") != null;
        // -Ptestprops=precompiled=verify (the regeneration's last step): Python ON, every pack compiled AND built from the
        // store, and the two must be the same files.
        boolean verifying = "verify".equals(PrecompiledPacks.mode());
        PrecompiledPacks.clearMismatches();
        if (!recording && PrecompiledPacks.size() == 0) {
            throw com.moddingfromamod.testing.gt.GameTestCompat.assertion("there is no precompiled store "
                    + "(precompiled/ready-mades.json.gz): run ./tools/precompile-ready-mades.sh");
        }
        var p = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper, "gtprecompile");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        com.moddingfromamod.testing.MockPlayerNetwork.actingChildFor(helper, p);
        var server = helper.getLevel().getServer();
        List<Make> makes = makes();
        List<String> wrong = java.util.Collections.synchronizedList(new ArrayList<>());
        int[] doneCount = {0};
        boolean[] done = {false};
        Thread t = new Thread(() -> {
            long rise = BuilderMake.riseMillis;
            String py = System.getProperty(PY);
            String si = System.getProperty(SKIP_ITEM);
            String se = System.getProperty(SKIP_EFFECT);
            BuilderMake.riseMillis = 0;
            if (!recording && !verifying) {
                System.setProperty(PY, "off");   // a PC with no Python: only the precompiled store can build a pack
            }
            System.setProperty(SKIP_ITEM, "true");
            System.setProperty(SKIP_EFFECT, "true");
            long t0 = System.currentTimeMillis();
            try {
                for (Make m : makes) {
                    BuilderSpec spec = m.spec().get();
                    BuilderMake.Result made = BuilderAbilityGameTest.onServer(server,
                            () -> BuilderMake.run(server, BuilderMake.Who.of(p), spec, null,
                                    BuilderAbilityGameTest.protocol(), false, false))
                            .get(120, TimeUnit.SECONDS).get(120, TimeUnit.SECONDS);
                    doneCount[0]++;
                    // a make its tool REFUSES is a Builder tile that fails in front of the player, with or
                    // without Python, so it fails this test too (it used to be logged and passed: found two
                    // looks, a Cookie Jar and a Fishing Rod, refused by make_item's real-item hint).
                    String detail = String.valueOf(made.detail());
                    boolean buildFailed = detail.contains("compiler") || detail.contains("Python")
                            || detail.contains("precompiled") || detail.contains("compile");
                    if (made.outcome() != BuilderMake.Outcome.MADE && !buildFailed) {
                        wrong.add(m.label() + ": refused by its tool (not a build): " + detail);
                        LOGGER.warn("ready-made precompile GT: {} refused by its tool (not a build): {}", m.label(), detail);
                    } else if (made.outcome() != BuilderMake.Outcome.MADE) {
                        wrong.add(m.label() + ": " + made.outcome() + " " + made.detail());
                        LOGGER.warn("ready-made precompile GT: {} -> {} {}", m.label(), made.outcome(), made.detail());
                    }
                }
            } catch (Throwable e) {
                wrong.add("threw: " + e);
            } finally {
                BuilderMake.riseMillis = rise;
                restore(PY, py);
                restore(SKIP_ITEM, si);
                restore(SKIP_EFFECT, se);
                if (verifying) {
                    for (String mm : PrecompiledPacks.mismatches()) {
                        wrong.add("not the compiler's pack: " + mm);
                    }
                }
                LOGGER.info("ready-made precompile GT: {} makes in {} ms, {} wrong (store holds {}{})", makes.size(),
                        System.currentTimeMillis() - t0, wrong.size(), PrecompiledPacks.size(),
                        verifying ? ", verified against the compiler" : "");
                done[0] = true;
            }
        }, "ready-made-precompile-gt");
        t.setDaemon(true);
        t.start();
        helper.onEachTick(() -> {
            if (done[0] && !wrong.isEmpty() && !recording) {
                throw com.moddingfromamod.testing.gt.GameTestCompat.assertion(wrong.size() + " of " + makes.size()
                        + " Builder powers could not be made: refused by a tool, or not without Python (run ./tools/precompile-ready-mades.sh "
                        + "if a template, a catalogue, the compiler or a tool's rendering changed): " + wrong);
            }
        });
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () ->
                helper.assertTrue(done[0], Component.literal("still at " + doneCount[0] + " of " + makes.size())));
    }

    private static void restore(String key, String was) {
        if (was == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, was);
        }
    }
}
