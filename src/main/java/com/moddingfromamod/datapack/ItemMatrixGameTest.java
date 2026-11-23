package com.moddingfromamod.datapack;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Every item the catalogue can make, compiled — all 48 of them.</b>
 *
 * <p>Same argument as {@link TriggerMatrixGameTest}: the entire promise of a precomputed thing is
 * that it is <i>known</i> to compile, and a combination outside the matrix has only ever been
 * compiled with the one set of parameters somebody typed by hand. The effect catalogue learned this
 * the expensive way — 205 tests passed while four of its ten entries had never been compiled by
 * anything, which is the shape of green that means nothing.
 *
 * <p>Exhaustive rather than sampled, for the same reason: a cell costs a couple of hundred
 * milliseconds, so a pairwise covering array would save nothing worth having.
 *
 * <p><b>It drives the real tool, not the renderer.</b> Rendering is already covered off-game by
 * {@code ItemCatalogueTest}; what this adds is everything between the tool's arguments and a pack
 * on disk — the derivations, the refusals, the feature id, and the compile itself. A matrix over
 * {@code ItemCatalogue.render} would have re-tested the half that was already cheap to test.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class ItemMatrixGameTest {

    /**
     * A plausible, IN-RANGE value for every parameter in the catalogue.
     *
     * <p>Deliberately not the extremes: this matrix asks "does every combination build", and the
     * refusals at the edges are unit-tested where they can be asserted individually. A matrix that
     * also probed limits would fail without saying which of the two things went wrong.
     */
    private static final Map<String, String> VALUES = Map.ofEntries(
            Map.entry("MOB", "minecraft:zombie"),
            Map.entry("PLACE", "village"),
            Map.entry("COUNT", "3"),
            Map.entry("BONUS", "minecraft:speed"),
            Map.entry("SECONDS", "10"),
            Map.entry("HEARTS", "5"),
            Map.entry("BLOCK", "minecraft:water"),
            // the item only names a kept design; that it exists is the event's business.
            Map.entry("DESIGN", "tidal_wave"),
            Map.entry("SAFE", "yes"),
            Map.entry("SCALE", "4"),
            Map.entry("ITEM", "minecraft:emerald"),
            Map.entry("STRIKES", "2"),
            Map.entry("GLOW_SECONDS", "20"),
            Map.entry("POWER", "launch"),
            Map.entry("SIZE", "3"),
            Map.entry("BREAKS_BLOCKS", "no"),
            Map.entry("LEVEL", "2"),
            Map.entry("RATE", "4"),
            // `baby` rather than `plain`, deliberately: plain renders no NBT at all, so a matrix
            // run on it would compile the same command the parameter was added to change and prove
            // nothing about it. Every other value here is chosen the same way.
            Map.entry("AS", "baby"),
            // a kind that exists, not `any`, so the matrix builds the filtered call.
            Map.entry("KIND", "wolf"),
            // `mob` rather than `me`: a GameTest world has no player, so the `me` filter would
            // select nobody and the command would be a no-op the matrix could not tell from a pass.
            Map.entry("FIGHTS", "mob"),
            // load_and_pour's OUTPUT: a real vanilla item, never one in our own namespace.
            Map.entry("OUTPUT", "minecraft:mushroom_stew"),
            // turn_into: FROM dirt TO glass — both a block AND an item, so every shape builds
            // its whole mode (a machine his bag, held both sides, every other shape the blocks).
            Map.entry("TO", "minecraft:glass"),
            // 1, not 0 — 0 is refused on every shape that does not aim exactly, and the
            // matrix wants each cell to build.
            // speed, not water_breathing: LEVEL is shared at 2, and only a graded effect has a level 2.
            Map.entry("EFFECT", "speed"),
            Map.entry("FROM", "minecraft:dirt"),
            Map.entry("RADIUS", "1"),
            // fill: two ingredients, an extra, a chest -- the fullest render, so every
            // generated fragment is compiled rather than only the one-ingredient path.
            Map.entry("INGREDIENTS", "minecraft:ice, minecraft:sugar"),
            Map.entry("EXTRAS", "minecraft:snowball"),
            Map.entry("MAX", "5"),
            Map.entry("WHERE", "chest"),
            // pull, and the surprise table with a creature in it (a table of presents
            // alone is refused — it would be a vending machine).
            Map.entry("STRENGTH", "strong"),
            Map.entry("REACH", "8"),
            Map.entry("PRESENTS", "minecraft:diamond*2, minecraft:cake"),
            Map.entry("SURPRISES", "minecraft:chicken"),
            Map.entry("BOOM", "no"));

    private static JsonObject call(String effect, String shape, List<String> params, int n) {
        JsonObject o = new JsonObject();
        o.addProperty("name", "Matrix " + effect + " " + shape + " " + n);
        o.addProperty("icon", "orb");
        o.addProperty("colour", "#3B7BE3");
        o.addProperty("shape", shape);
        o.addProperty("effect", effect);
        o.addProperty("count", 1);
        JsonObject p = new JsonObject();
        for (String name : params) {
            // A MISSING VALUE IS A REPORT, NOT A CRASH. `addProperty(name, (String) null)` stores a
            // JsonNull, and the first read of it throws `UnsupportedOperationException: JsonNull`
            // out of the cell loop — before the "no matrix value for ..." check below can say which
            // parameter it was. Measured: adding `convert` gave a whole Gate B run whose only
            // output was the word `JsonNull`, with the guard written to explain it sitting unused
            // two lines away.
            p.addProperty(name, VALUES.getOrDefault(name, "MISSING_MATRIX_VALUE"));
        }
        o.add("params", p);
        return o;
    }

    /**
     * Run a build LOOP with datapack reloads batched into one.
     *
     * <p>Every {@code make_item} call installs a datapack and reloads the world, and a reload blocks
     * the server thread for ~3s. That is the right price for one wish and the wrong price for a
     * matrix: measured 2026-09-01, this file's three loops accounted for <b>161 of Gate B's 183
     * reloads</b> and for 514s of a 1,237s run -- 42% of the suite, spent by two batches that ran at
     * 3.8 and 5.9 ticks per second while every other test co-batched with them sat frozen behind the
     * same thread.
     *
     * <p><b>It does not weaken the matrix.</b> These loops assert that each cell <i>builds</i> --
     * {@code r.isError()} is a compile/validation result, and it is returned before activation is
     * ever reached. Whether a pack LOADS is a different claim that {@code activate} has never made
     * (see {@code everyHardcodedRegistryIdResolves}, the test that does make it, in 0.002s). So the
     * per-cell reload was proving nothing and costing three seconds.
     *
     * <p>Falls back to running the body plainly when there is no server, so the shortcut can never
     * be the reason a test fails to run.
     */
    private static void batched(GameTestHelper helper, DatapackReloader.ThrowingRunnable body) {
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        try {
            if (server == null) {
                body.run();
            } else {
                DatapackReloader.withDeferredReloads(server, body);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @GameTest(template = "empty_test", timeoutTicks = 6000)
    public static void everyEffectBuildsInEveryShape(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            // SAY SO LOUDLY. A skip that reads as a pass is how a matrix proves nothing for weeks.
            System.out.println("ITEM MATRIX: SKIPPED — no CBScript compiler. This proved NOTHING "
                    + "about whether the item catalogue generalises.");
            helper.succeed();
            return;
        }
        ItemCatalogue catalogue = ItemCatalogue.load();
        List<String> failures = new ArrayList<>();
        int cells;
        int[] counters = {0, 0};
        List<java.util.function.Supplier<String>> cellsToRun = new ArrayList<>();
        batched(helper, () -> {
            for (ItemCatalogue.Entry e : catalogue.all()) {
                for (String missing : e.params()) {
                    if (!VALUES.containsKey(missing)) {
                        // A new parameter with no value here would silently narrow the matrix to the
                        // entries that happen to be covered, which is the failure this class exists for.
                        failures.add("no matrix value for " + e.id() + "." + missing
                                + " — add one to VALUES, or this entry is not being tested at all");
                    }
                }
                for (String shape : ItemCatalogue.EFFECT_SHAPES) {
                    counters[0]++;
                    JsonObject args = call(e.id(), shape, e.params(), counters[1]++);
                    cellsToRun.add(() -> {
                        ToolResult r = new MakeItemTool().call(args);
                        return r.isError() ? e.id() + " / " + shape + ": " + r.text() : null;
                    });
                }
            }
            // ON A POOL: ~580 cells, a forked compile each, were 203 s serially.
            failures.addAll(ToolMatrix.run(helper.getLevel().getServer(), cellsToRun));
        });
        cells = counters[0];
        int expected = catalogue.all().size() * ItemCatalogue.EFFECT_SHAPES.size();
        if (cells != expected) {
            failures.add("ran " + cells + " cells, expected " + expected);
        }
        if (!failures.isEmpty()) {
            helper.fail(Component.literal("the item matrix is not whole:\n  " + String.join("\n  ", failures)));
            return;
        }
        // DERIVED, not typed. This line said "16 effects" while building 51 cells the moment a
        // seventeenth was added — a count that misstates its own coverage is how a later reader
        // concludes the matrix covers something it does not.
        System.out.println("ITEM MATRIX: " + cells + " cells built (" + catalogue.all().size()
                + " effects x " + ItemCatalogue.EFFECT_SHAPES.size() + " shapes).");
        // THE ONE BATCHED RELOAD MUST ENABLE EVERY PACK THE LOOP INSTALLED. The rescan
        // and the selection moved from each install into the flush; a flush that dropped one would
        // leave a cell "built" whose pack never loads, and nothing else here would notice.
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        List<String> flushed = DatapackReloader.lastFlushedFeatures();
        if (server == null) {
            helper.succeed();
            return;
        }
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertFalse(DatapackReloader.reloadInProgress(),
                        Component.literal("the batched reload is still running")))
                .thenExecute(() -> {
                    helper.assertTrue(!flushed.isEmpty(),
                            Component.literal("the matrix installed packs, so the batched reload must have enabled some"));
                    List<String> off = flushed.stream()
                            .filter(f -> !DatapackReloader.isEnabled(server, f)).toList();
                    helper.assertTrue(off.isEmpty(), Component.literal(off.size() + " of " + flushed.size()
                            + " packs the loop installed are not enabled after its one reload: "
                            + off.stream().limit(5).toList()));
                })
                .thenSucceed();
    }

    /**
     * <b>Every registry id a template hardcodes must actually EXIST.</b>
     *
     * <p>Found by the effect sweep, and invisible to everything else: {@code grow} emitted
     * {@code attribute @s minecraft:scale base set 3}, and <b>{@code minecraft:scale} is not an
     * attribute in 1.21.1</b>. The whole pack then failed to LOAD — not to run, to load — so the
     * effect and its clock were both dead, and a grow item did nothing at all for its entire life.
     *
     * <p>Nothing upstream could see it. CBScript compiles command text without resolving arguments,
     * the tool reported success, the matrix built all 51 cells, and Gate B was green. The only
     * evidence was a stack trace in the server log of a live world, which nobody reads when the
     * tests pass. It is the same shape as the mob-id lesson in the ability catalogue: a name that
     * does not resolve makes the command fail, and a failed command is silent.
     *
     * <p>So this resolves every id a template names, against the live registries.
     *
     * <p><b>⚠ TEMPLATE SOURCE IS ONLY ONE OF THE SURFACES, and the second one bit in the same
     * way.</b> The PROPERTIES axis renders its components from Java rather than from a {@code .cbs}
     * file, so {@code minecraft:attack_damage} — also not an attribute in 1.21.1 — walked past this
     * sweep, past the compiler and past nine unit tests. That surface is covered by
     * {@code ItemPropertiesGameTest.everyGiveLineThisToolCanRenderIsAcceptedByTheGame}, which
     * parses what the tool renders with the game's own {@code ItemParser}. <b>If you add a third
     * place that names a registry id, it needs its own sweep — this one cannot see it.</b>
     */
    /**
     * <b>EVERY SHAPE, through the REAL tool — including the ones with no effect.</b>
     *
     * <p><b>The gap this closes shipped and was measured green.</b> The matrix above loops
     * {@code EFFECT_SHAPES} by construction, and every other test of the {@code plain} shape calls
     * {@code gives()} directly — so <b>no gate anywhere had ever driven a plain wish through
     * {@code MakeItemTool.call()}</b>. It threw a {@link NullPointerException} on
     * {@code effect.split("+")} the first time an agent actually made one, which is to say: on every
     * plain wish, from the day the shape shipped.
     *
     * <p>It was measured selecting `plain` <b>16 times out of 16</b> in the routing arm and
     * every one of those was against a STUB. A tier that asks "does the agent reach for it" cannot
     * see that the thing it reached for throws.
     *
     * <p>So this is deliberately cheap and deliberately exhaustive over {@code SHAPES}, not
     * {@code EFFECT_SHAPES}: one call per shape, the smallest wish that shape can express, and the
     * only assertion is that the tool did not fall over.
     */
    @GameTest(template = "empty_test", timeoutTicks = 900)
    public static void everyShapeSurvivesTheRealCall(GameTestHelper helper) {
        List<String> failures = new ArrayList<>();
        int[] n = {0};
        batched(helper, () -> {
        for (String shape : ItemCatalogue.SHAPES) {
            JsonObject o = new JsonObject();
            o.addProperty("name", "Shape Probe " + (n[0]++));
            o.addProperty("icon", "orb");
            o.addProperty("colour", "white");
            o.addProperty("shape", shape);
            o.addProperty("count", 1);
            if (!ItemCatalogue.PLAIN.equals(shape)) {
                o.addProperty("effect", "heal");
                JsonObject params = new JsonObject();
                JsonObject heal = new JsonObject();
                heal.addProperty("SECONDS", "6");
                params.add("heal", heal);
                o.add("params", params);
            }
            try {
                ToolResult r = new MakeItemTool().call(o);
                if (r.isError()) {
                    failures.add(shape + ": refused — " + r.text());
                }
            } catch (RuntimeException bad) {
                failures.add(shape + ": THREW — " + bad);
            }
        }
        });
        if (!failures.isEmpty()) {
            helper.fail(Component.literal("a shape the tool offers cannot be called:\n  "
                    + String.join("\n  ", failures)));
            return;
        }
        System.out.println("SHAPES: all " + ItemCatalogue.SHAPES.size()
                + " survive a real make_item call.");
        helper.succeed();
    }

    @GameTest(template = "empty_test", timeoutTicks = 600)
    public static void everyHardcodedRegistryIdResolves(GameTestHelper helper) {
        List<String> bad = new ArrayList<>();
        java.util.regex.Pattern attr = java.util.regex.Pattern.compile(
                "attribute\\s+\\S+\\s+([a-z0-9_.:]+)");
        java.util.regex.Pattern effect = java.util.regex.Pattern.compile(
                "effect give \\S+\\s+([a-z0-9_.:]+)");
        for (ItemCatalogue.Entry e : ItemCatalogue.load().all()) {
            // Judged as COMPILED: DatapackForge puts every pack into this build's dialect first
            // (wish.CommandDialect), and that corrected text is what the game loads.
            String src = com.moddingfromamod.wish.CommandDialect.correct(
                    ItemCatalogue.source(e.template())).command();
            var m = attr.matcher(src);
            while (m.find()) {
                String id = m.group(1);
                var rl = net.minecraft.resources.ResourceLocation.tryParse(id);
                if (rl == null || !net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE
                        .containsKey(rl)) {
                    // NAME THE ALTERNATIVES. A refusal that only says "no" sends the next person
                    // guessing at a registry they cannot see from here, which is how this one was
                    // wrong in the first place.
                    String want = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
                    String near = net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE
                            .keySet().stream()
                            .map(net.minecraft.resources.ResourceLocation::toString)
                            .filter(k -> k.contains(want.replace("generic.", "")))
                            .reduce((a, b) -> a + ", " + b).orElse("(nothing similar)");
                    bad.add(e.id() + " names attribute '" + id + "', which does not exist in this "
                            + "version. The whole pack fails to LOAD, so every line in it is dead. "
                            + "Did you mean: " + near);
                }
            }
            var me = effect.matcher(src);
            while (me.find()) {
                String id = me.group(1);
                if (id.startsWith("${")) {
                    continue;   // supplied by the child, checked when it is chosen
                }
                var rl = net.minecraft.resources.ResourceLocation.tryParse(id);
                if (rl == null || !net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT
                        .containsKey(rl)) {
                    bad.add(e.id() + " names mob effect '" + id + "', which does not exist");
                }
            }
        }
        if (!bad.isEmpty()) {
            helper.fail(Component.literal("a template hardcodes an id that does not resolve:\n  "
                    + String.join("\n  ", bad)));
            return;
        }
        System.out.println("REGISTRY IDS: every attribute and mob effect named in a template "
                + "resolves. (scale is '"
                + net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.getKey(
                        net.minecraft.world.entity.ai.attributes.Attributes.SCALE.value())
                + "')");
        helper.succeed();
    }

    /**
     * <b>Composed items, through the real tool.</b>
     *
     * <p>Deliberately a chosen set rather than all 816 ordered pairs. The render-and-compile of
     * every pair is already proven off-game — 816/816 through the real CBScript compiler — so what
     * is left for this tier is the part only the TOOL does: grouping parameters per effect,
     * resolving one AIM for two effects that may disagree, and building a feature id that does not
     * collide. Those are exercised by crossing the axes that can actually differ, not by volume.
     */
    @GameTest(template = "empty_test", timeoutTicks = 3000)
    public static void composedItemsBuild(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            System.out.println("ITEM PAIRS: SKIPPED — no CBScript compiler. This proved NOTHING.");
            helper.succeed();
            return;
        }
        // Each pair crosses something that could break: a self effect with an ahead one (whose AIM
        // wins), a stateful one with a stateless one (two clocks in a file), world with target
        // (two different TARGET selectors), and the javelin's own case.
        List<String[]> pairs = List.of(
                new String[] {"hurt", "place"},      // the javelin: what he actually asked for
                new String[] {"hurt", "ignite"},     // a sword that burns what it cuts
                new String[] {"place", "hurt"},      // order reversed — the dispatcher must not care
                new String[] {"fly", "protect"},     // two self effects
                new String[] {"grow", "speed"},      // stateful + stateless, two clocks in one file
                new String[] {"summon", "lightning"},// two world effects, both multi-line derived
                new String[] {"heal", "hurt"},       // self + target: opposite selectors, one item
                new String[] {"freeze", "explode"}); // target + world
        List<String> failures = new ArrayList<>();
        int[] n = {800};
        List<java.util.function.Supplier<String>> pairCells = new ArrayList<>();
        batched(helper, () -> {
        for (String[] pair : pairs) {
            for (String shape : ItemCatalogue.EFFECT_SHAPES) {
                JsonObject o = call(pair[0], shape, List.of(), n[0]++);
                o.addProperty("effect", pair[0] + "+" + pair[1]);
                JsonObject grouped = new JsonObject();
                for (String id : pair) {
                    JsonObject one = new JsonObject();
                    for (String param : ItemCatalogue.load().get(id).params()) {
                        one.addProperty(param, VALUES.get(param));
                    }
                    grouped.add(id, one);
                }
                o.add("params", grouped);
                pairCells.add(() -> {
                    ToolResult r = new MakeItemTool().call(o);
                    return r.isError() ? pair[0] + "+" + pair[1] + " / " + shape + ": " + r.text() : null;
                });
            }
        }
        // ON A POOL, as everyEffectBuildsInEveryShape: ~55-75 s serially.
        failures.addAll(ToolMatrix.run(helper.getLevel().getServer(), pairCells));
        });
        // And the refusals that only exist for pairs.
        JsonObject flat = call("hurt", "held", List.of(), 890);
        flat.addProperty("effect", "hurt+ignite");
        JsonObject f = new JsonObject();
        f.addProperty("HEARTS", "5");
        f.addProperty("SECONDS", "6");
        flat.add("params", f);
        ToolResult flatResult = new MakeItemTool().call(flat);
        if (!flatResult.isError()) {
            // Two effects can both want SECONDS. A flat list cannot say whose it is, and guessing
            // would give one effect's value to the other with nothing to notice it.
            failures.add("flat params across two effects were ACCEPTED — one effect silently got "
                    + "the other's values");
        }
        if (!failures.isEmpty()) {
            helper.fail(Component.literal("composed items are not whole:\n  " + String.join("\n  ", failures)));
            return;
        }
        System.out.println("ITEM PAIRS: " + (pairs.size() * ItemCatalogue.EFFECT_SHAPES.size())
                + " composed items built through the real tool.");
        helper.succeed();
    }

    /**
     * The refusals, which are the tool's actual product.
     *
     * <p>Every one of these once had — or could trivially regain — a silent default, and a default
     * is a decision made after the wish text is gone. A tool that has never said NO is not enforcing
     * anything, so each case asserts the refusal AND that it explains itself.
     */
    @GameTest(template = "empty_test", timeoutTicks = 600)
    public static void itRefusesRatherThanGuessing(GameTestHelper helper) {
        List<String> problems = new ArrayList<>();

        // A missing effect parameter. The whole point of the tool.
        JsonObject noParams = call("hurt", "held", List.of(), 900);
        expectRefusal(problems, "hurt with no HEARTS", noParams, "HEARTS");

        // A misspelled one, which must not be quietly ignored.
        JsonObject misspelled = call("ignite", "held", List.of(), 901);
        misspelled.getAsJsonObject("params").addProperty("DURATION", "10");
        expectRefusal(problems, "ignite with DURATION instead of SECONDS", misspelled, "DURATION");

        // An unknown effect must list the real ones rather than pick a neighbour.
        expectRefusal(problems, "an effect that does not exist",
                call("vaporise", "held", List.of(), 902), "author_datapack");

        // An unknown icon must not silently wear the default artwork.
        JsonObject badIcon = call("lightning", "held", List.of("STRIKES"), 903);
        badIcon.addProperty("icon", "trident");
        expectRefusal(problems, "an icon that is not one of the 24", badIcon, "trident");

        // A word the tool does not know is refused, never middled. "as hard as you possibly can"
        // silently building a shove is a wrong answer only playing it would reveal.
        JsonObject badPower = call("launch", "held", List.of(), 904);
        badPower.getAsJsonObject("params").addProperty("POWER", "999");
        expectRefusal(problems, "launch POWER=999", badPower, "nudge");

        // Resistance IV is total immunity: an item that makes the game unloseable.
        JsonObject tooSafe = call("protect", "held", List.of("SECONDS"), 905);
        tooSafe.getAsJsonObject("params").addProperty("LEVEL", "4");
        expectRefusal(problems, "protect LEVEL=4", tooSafe, "immunity");

        // The BLAST question the child must actually be asked, because guessing costs him his base.
        JsonObject vagueBlast = call("explode", "thrown", List.of("SIZE"), 906);
        expectRefusal(problems, "explode with no BREAKS_BLOCKS", vagueBlast, "BREAKS_BLOCKS");

        if (!problems.isEmpty()) {
            helper.fail(Component.literal("make_item guessed where it should have refused:\n  "
                    + String.join("\n  ", problems)));
            return;
        }
        helper.succeed();
    }

    private static void expectRefusal(List<String> problems, String what, JsonObject args,
                                      String mustMention) {
        ToolResult r = new MakeItemTool().call(args);
        if (!r.isError()) {
            problems.add(what + " was ACCEPTED — something was silently filled in");
        } else if (!r.text().contains(mustMention)) {
            // A refusal that does not say what to do instead sends the agent probing, and a failed
            // probe is what pushed the last catalogue's agent onto a different, wrong entry.
            problems.add(what + " was refused without mentioning '" + mustMention + "': " + r.text());
        }
    }

    /**
     * The success message must hand over the command that WORKS.
     *
     * <p>From a real play session: the message said "RUN {@code <id>:give}", the agent did exactly
     * that, and a bare {@code /function} on a freshly-loaded pack fails with "This function should
     * not run". It retried nine times over two minutes — the child was watching an empty bag the
     * whole time — before discovering {@code wishdatapack <id> give}, which is the elevated replay
     * path that also guarantees the pack is loaded.
     *
     * <p>Pinned because it is invisible everywhere else: the tool returned ok, the pack was live,
     * the item was correct, and every gate was green. Only the child noticed.
     */
    @GameTest(template = "empty_test", timeoutTicks = 600)
    public static void itHandsBackTheGiveCommandThatActuallyWorks(GameTestHelper helper) {
        ToolResult r = new MakeItemTool().call(
                call("lightning", "held", List.of("STRIKES"), 910));
        if (r.isError()) {
            helper.fail(Component.literal("could not build the probe item: " + r.text()));
            return;
        }
        String id = MakeItemTool.featureId("Matrix lightning held 910", "lightning");
        if (!r.text().contains("wishdatapack " + id + " give")) {
            helper.fail(Component.literal("the success message does not give the agent the working command "
                    + "('wishdatapack " + id + " give'). It said:\n" + r.text()));
            return;
        }
        if (r.text().matches("(?s).*RUN " + id + ":give.*")) {
            helper.fail(Component.literal("the message still tells the agent to run a bare /function, which fails on "
                    + "a freshly-loaded pack and costs the child minutes of empty bag"));
            return;
        }
        helper.succeed();
    }

    /**
     * Two items in one world must not overwrite each other.
     *
     * <p>The feature id is derived from the child's name for the thing, and two wishes for "a bomb"
     * that differ only in what the bomb does would collide — the second silently replacing the
     * first. Two wishes in a row is not an edge case, it is Tuesday; the datapack layer learned
     * that in 015581b when the second activation switched the first pack off.
     */
    @GameTest(template = "empty_test", timeoutTicks = 600)
    public static void twoItemsWithTheSameNameDoNotCollide(GameTestHelper helper) {
        String a = MakeItemTool.featureId("a bomb", "explode");
        String b = MakeItemTool.featureId("a bomb", "ignite");
        if (a.equals(b)) {
            helper.fail(Component.literal("'a bomb' that explodes and 'a bomb' that burns share the feature id '" + a
                    + "', so the second wish would silently replace the first"));
            return;
        }
        if (!MakeItemTool.featureId("Bloop Slayer Trident!!", "hurt").matches("[a-z][a-z0-9_]{0,47}")) {
            helper.fail(Component.literal("a child's name for a thing must always become a legal feature id"));
            return;
        }
        helper.succeed();
    }
}
