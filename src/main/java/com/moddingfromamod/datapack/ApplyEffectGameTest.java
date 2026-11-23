package com.moddingfromamod.datapack;

import net.minecraft.network.chat.Component;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.ApplyEffectTool;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Does the ready-made path actually work, end to end, in seconds?</b>
 *
 * <p>Three live A/B attempts produced zero {@code apply_effect} calls, for three different reasons,
 * and each one cost fifteen minutes of client time to discover. Every one of them would have been
 * visible here: the tool not registered, the tool erroring on its own catalogue, an entry that
 * cannot be instantiated from the wish it advertises.
 *
 * <p>So this is the gate that runs BEFORE any client time is spent. It does not ask whether the
 * agent CHOOSES the tool — only a live run can answer that — it asks whether the path works at all
 * when something calls it. Those are different questions, and conflating them is why the last three
 * runs each looked like a mystery instead of a bug.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class ApplyEffectGameTest {

    private static JsonObject args(String effect, String tag) {
        JsonObject o = new JsonObject();
        o.addProperty("effect", effect);
        o.addProperty("tag", tag);
        return o;
    }

    @GameTest(template = "empty_test", timeoutTicks = 400)
    public static void theReadyMadePathWorksWithoutWritingAnyCode(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            System.out.println("APPLY EFFECT: SKIPPED — no CBScript compiler. This proved NOTHING "
                    + "about the treatment arm.");
            helper.succeed();
            return;
        }
        ToolResult r = new ApplyEffectTool().call(args("inflict_nearby", "spider"));
        helper.assertTrue(!r.isError(),
                Component.literal("apply_effect failed on its own catalogue entry, so the treatment arm cannot work "
                        + "however well the agent is prompted: " + r.text()));
        helper.assertTrue(r.text().contains("compiled in"),
                Component.literal("the result should report its own timings, which is how the A/B measures the "
                        + "coding phase at all: " + r.text()));
        System.out.println("APPLY EFFECT: " + r.text().replace("\n", " | "));
        helper.succeed();
    }

    /**
     * The tool must describe the catalogue to the agent, because that description IS the selection
     * surface — an agent picks from it without reading any source.
     */
    @GameTest(template = "empty_test", timeoutTicks = 100)
    public static void theToolAdvertisesEveryEntry(GameTestHelper helper) {
        String description = new ApplyEffectTool().description();
        for (EffectCatalogue.Effect e : EffectCatalogue.load().all()) {
            helper.assertTrue(description.contains(e.id()),
                    Component.literal("the tool's description never mentions '" + e.id() + "', so the agent cannot "
                            + "know it exists and will write it from scratch"));
        }
        // WHEN IT FIRES. Omitted from this listing for months: the agent chose a trigger, then
        // described that trigger to the child, having never been shown the one field that says
        // what it MEANS. Both halves are consistent with that being the common cause.
        for (EffectCatalogue.Effect e : EffectCatalogue.load().all()) {
            helper.assertTrue(description.contains(e.when()),
                    Component.literal("the agent is never told when '" + e.id() + "' fires, so it is picking a "
                            + "trigger from the effect name alone"));
        }
        helper.assertTrue(!description.contains("could not be read"),
                Component.literal("the catalogue failed to load INSIDE the game — it works in unit tests and not "
                        + "here, which is exactly the gap a headless-only check would miss: "
                        + description));
        helper.succeed();
    }

    /**
     * A wish that names no creature cannot be served, and the tool must SAY so rather than invent a
     * tag.
     *
     * <p>This is the third A/B's failure, made cheap: {@code self_aura} advertised "make the mobs
     * come out already zapped", its {@code TAG} has no default, and "the mobs" names no creature. A
     * tool that guessed here would produce a datapack aimed at nothing.
     */
    @GameTest(template = "empty_test", timeoutTicks = 100)
    public static void aMissingCreatureIsRefusedRatherThanGuessed(GameTestHelper helper) {
        JsonObject o = new JsonObject();
        o.addProperty("effect", "inflict_nearby");
        o.addProperty("tag", "");
        ToolResult r = new ApplyEffectTool().call(o);
        helper.assertTrue(r.isError(),
                Component.literal("an empty creature tag was accepted; the pack would target nothing and look fine"));
        helper.succeed();
    }

    /**
     * A COMMAND the server cannot parse kills the whole ability, and the tool must SAY so (
     * chunk 3 panel: "damage @e[type=zombie,distance=..3] 12" — damage takes ONE entity — dropped
     * on_step's tick function at load while apply_effect reported "now LIVE in the world").
     */
    @GameTest(template = "empty_test", timeoutTicks = 400, batch = "apply_effect_refused")
    public static void aCommandTheServerRefusesIsReportedNotLive(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            helper.fail(Component.literal("no CBScript compiler: this test would prove nothing"));
            return;
        }
        JsonObject bad = args("on_step", "gt_refused");
        JsonObject p = new JsonObject();
        // A number the server cannot read. (It was `damage @e[...] 12`, which apply_effect now
        // refuses before it is ever compiled, — see aSelectorInsideATriggerIsRefused.)
        p.addProperty("COMMAND", "damage @s twelve");
        bad.add("params", p);
        ToolResult r = new ApplyEffectTool().call(bad);
        helper.assertTrue(r.isError(), Component.literal("a function the server dropped was reported as working: " + r.text()));
        helper.assertTrue(r.text().contains("NOT live") && r.text().contains("on_step_gt_refused"),
                Component.literal("the refusal must say it is not live and name the pack: " + r.text()));
        // Left on, its dropped tick function makes Minecraft refuse the whole #minecraft:tick tag,
        // and every other pack's clock stops — ten unrelated GameTests went red that way.
        helper.assertTrue(DatapackReloader.disabledIds().contains(DatapackReloader.packId("on_step_gt_refused")),
                Component.literal("the refused pack was left switched on, which stops every datapack's tick"));

        JsonObject good = args("on_step", "gt_accepted");
        JsonObject q = new JsonObject();
        q.addProperty("COMMAND", "damage @s 2");
        good.add("params", q);
        ToolResult ok = new ApplyEffectTool().call(good);
        helper.assertTrue(!ok.isError(), Component.literal("a good COMMAND was refused: " + ok.text()));
        // ...and switching the good one ON must not switch the refused one back on with it. It did:
        // the next activation rebuilt its selection from the saved packs and the stale applied set,
        // both of which still held it, and ten unrelated GameTests went red behind it.
        helper.assertTrue(!DatapackReloader.selectionWith(helper.getLevel().getServer(), java.util.List.of())
                        .contains(DatapackReloader.packId("on_step_gt_refused")),
                Component.literal("the next activation switched the refused pack back on"));
        helper.succeed();
    }

    /**
     * a trigger's COMMAND already runs AS each one it steps or lands near, and a selector in
     * it built a push that pushed nothing (Opus's bh-t05, Spark's bh-t02 in chunk 3). Refused before
     * it compiles, naming the forms that work; a {@code by @e[...]} damage source is allowed, because
     * that is what knocks them away from it.
     */
    @GameTest(template = "empty_test", timeoutTicks = 400, batch = "apply_effect_selector")
    public static void aSelectorInsideATriggerIsRefused(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            helper.fail(Component.literal("no CBScript compiler: this test would prove nothing"));
            return;
        }
        JsonObject bad = args("on_step", "gt_selector_step");
        JsonObject p = new JsonObject();
        p.addProperty("COMMAND", "execute as @e[type=#minecraft:undead,distance=..3] run damage @s 1");
        bad.add("params", p);
        ToolResult r = new ApplyEffectTool().call(bad);
        helper.assertTrue(r.isError(), Component.literal("a selector inside on_step's COMMAND was installed: " + r.text()));
        helper.assertTrue(r.text().contains("@s already IS") && r.text().contains("Motion"),
                Component.literal("the refusal must say @s is already the victim and how to push: " + r.text()));
        JsonObject land = args("on_land", "gt_selector_land");
        JsonObject q = new JsonObject();
        q.addProperty("COMMAND", "damage @e[type=minecraft:zombie,distance=..4] 6");
        land.add("params", q);
        helper.assertTrue(new ApplyEffectTool().call(land).isError(),
                Component.literal("a selector inside on_land's COMMAND was installed"));

        JsonObject by = args("on_step", "gt_selector_by");
        JsonObject b = new JsonObject();
        b.addProperty("COMMAND", "damage @s 1 minecraft:mob_attack by @e[tag=wishmob_gt_selector_by,sort=nearest,limit=1]");
        b.addProperty("TARGET", "monsters");
        by.add("params", b);
        ToolResult ok = new ApplyEffectTool().call(by);
        helper.assertTrue(!ok.isError(), Component.literal("a 'by @e[...]' damage source was refused: " + ok.text()));
        helper.succeed();
    }

    /**
     * on_step / on_land on a creature that also fights by itself is a DECISION. The warning
     * alone was read and shipped past (bh-t02, bh-r03: a guardian that bit the monsters while it
     * stood still, for a wish that said only as it walks). Red if the tool installs it unasked.
     */
    @GameTest(template = "empty_test", timeoutTicks = 400, batch = "apply_effect_still")
    public static void aFighterMustSayWhetherItAlsoFightsStandingStill(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            helper.fail(Component.literal("no CBScript compiler: this test would prove nothing"));
            return;
        }
        var guard = com.moddingfromamod.species.Species.fromJson(com.google.gson.JsonParser.parseString(
                "{\"id\":\"gt_still_guard\",\"name\":\"Still Guard\",\"base\":\"guardian\"}").getAsJsonObject());
        com.moddingfromamod.testing.SpeciesForTest.put(helper, guard);
        JsonObject unasked = args("on_step", "gt_still_guard");
        JsonObject p = new JsonObject();
        p.addProperty("COMMAND", "damage @s 4");
        unasked.add("params", p);
        ToolResult r = new ApplyEffectTool().call(unasked);
        helper.assertTrue(r.isError() && r.text().contains("ALSO_WHEN_STILL"),
                Component.literal("a guardian got on_step without saying whether it also fights standing still: " + r.text()));

        JsonObject no = unasked.deepCopy();
        no.getAsJsonObject("params").addProperty("ALSO_WHEN_STILL", "no");
        ToolResult n = new ApplyEffectTool().call(no);
        helper.assertTrue(n.isError() && n.text().contains("wild"),
                Component.literal("'no' must send it back to be re-sculpted wild: " + n.text()));

        JsonObject yes = unasked.deepCopy();
        yes.getAsJsonObject("params").addProperty("ALSO_WHEN_STILL", "yes");
        ToolResult y = new ApplyEffectTool().call(yes);
        helper.assertTrue(!y.isError(), Component.literal("'yes' (a guardian that also stomps) was refused: " + y.text()));
        helper.succeed();
    }

    /**
     * a landing aimed at the monsters with no COMMAND only lifts them, and the tool says so
     * where the agent is about to tell the child they get stomped (bh-n03). Red if it goes quiet.
     */
    @GameTest(template = "empty_test", timeoutTicks = 400, batch = "apply_effect_lifts")
    public static void aLandingWithNoCommandSaysItHurtsNobody(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            helper.fail(Component.literal("no CBScript compiler: this test would prove nothing"));
            return;
        }
        JsonObject land = args("on_land", "gt_lifts_only");
        JsonObject p = new JsonObject();
        p.addProperty("TARGET", "monsters");
        land.add("params", p);
        ToolResult r = new ApplyEffectTool().call(land);
        helper.assertTrue(!r.isError() && r.text().contains("HURTS NOBODY") && r.text().contains("on_step"),
                Component.literal("a landing that only lifts the monsters was installed without saying so: " + r.text()));
        helper.succeed();
    }

    /**
     * an effect that lands on others, given to a creature that hunts HIM, is a decision.
     * Spark built bh-r07 that way four panels running: no 'friendly', so it went for the child and
     * its on_hit poison never reached a monster. Red if the tool installs it unasked.
     */
    @GameTest(template = "empty_test", timeoutTicks = 400, batch = "apply_effect_hunts")
    public static void anEffectOnACreatureThatHuntsHimMustSaySo(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            helper.fail(Component.literal("no CBScript compiler: this test would prove nothing"));
            return;
        }
        var hunter = com.moddingfromamod.species.Species.fromJson(com.google.gson.JsonParser.parseString(
                "{\"id\":\"gt_hunts_him\",\"name\":\"Hunts Him\",\"powers\":[\"hostile\"]}").getAsJsonObject());
        com.moddingfromamod.testing.SpeciesForTest.put(helper, hunter);
        helper.assertTrue(com.moddingfromamod.vanilla.RigInstinct.of(hunter).huntsPlayers(),
                Component.literal("the fixture should hunt players"));
        JsonObject unasked = args("on_hit", "gt_hunts_him");
        JsonObject p = new JsonObject();
        p.addProperty("COMMAND", "effect give @s minecraft:poison 8 0");
        unasked.add("params", p);
        ToolResult r = new ApplyEffectTool().call(unasked);
        helper.assertTrue(r.isError() && r.text().contains("ATTACKS_HIM"),
                Component.literal("an on_hit on a creature that hunts him was installed unasked: " + r.text()));
        JsonObject yes = unasked.deepCopy();
        yes.getAsJsonObject("params").addProperty("ATTACKS_HIM", "yes");
        ToolResult y = new ApplyEffectTool().call(yes);
        helper.assertTrue(!y.isError(), Component.literal("'yes' (a monster meant to attack him) was refused: " + y.text()));
        helper.succeed();
    }
}
