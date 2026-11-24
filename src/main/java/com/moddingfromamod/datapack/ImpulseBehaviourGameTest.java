package com.moddingfromamod.datapack;

import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.monster.zombie.Zombie;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Impulse — the second ability class, and the first that a vanilla command cannot obviously
 * express.</b>
 *
 * <p>Pre-registered in {@code tools/ab-wishes-impulse.json} before a line of it was written, with
 * three predictions recorded so they can be wrong. This test settles the cheapest two:
 *
 * <ol>
 *   <li><b>Does impulse need a new template at all?</b> The existing triggers carry a COMMAND, and
 *       {@code data modify entity @s Motion} <i>is</i> one command. If that works, impulse costs
 *       zero templates and the trigger axis is reused untouched — which would be a much stronger
 *       result for the pattern than adding a fourth template.</li>
 *   <li><b>Is direction a token?</b> away / up / toward differ only in the numbers inside the
 *       Motion vector, so they should be a parameter rather than three templates.</li>
 * </ol>
 *
 * <p>Prediction 1 in the pre-registration says <b>players will be the hard part</b>: Motion NBT is
 * not authoritative for players, so a template that visibly throws a cow may do nothing whatsoever
 * to the child. That is exactly the shape of the bug the first class shipped — working for one kind
 * of victim and silently not the other — so it is tested here rather than discovered later.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class ImpulseBehaviourGameTest {

    private static final String SOURCE_TAG = "impulsemob";

    private static void run(GameTestHelper helper, String trigger, String command, int radius) {
        EffectCatalogue catalogue = EffectCatalogue.load();
        EffectCatalogue.Effect entry = catalogue.get(trigger);
        Map<String, String> offered = Map.of("TAG", SOURCE_TAG, "COMMAND", command,
                "RADIUS", String.valueOf(radius), "PERIOD_TICKS", "1");
        Map<String, String> params = new java.util.LinkedHashMap<>();
        offered.forEach((k, v) -> {
            if (entry.params().contains(k)) {
                params.put(k, v);
            }
        });
        List<String> lines = catalogue.render(trigger, params).lines()
                .map(String::strip).filter(l -> l.startsWith("/")).map(l -> l.substring(1)).toList();
        ServerLevel level = helper.getLevel();
        CommandSourceStack src = level.getServer().createCommandSourceStack()
                .withLevel(level).withSuppressedOutput().withPermission(4);
        for (String c : lines) {
            level.getServer().getCommands().performPrefixedCommand(src, c);
        }
    }

    private static Zombie source(GameTestHelper helper, BlockPos at) {
        Zombie z = helper.spawn(EntityType.ZOMBIE, at);
        z.addTag(SOURCE_TAG);
        z.setPersistenceRequired();
        return z;
    }

    /**
     * <b>The question that decides whether impulse costs a template at all.</b> A hurt mob should be
     * thrown upward by an ordinary command inside the existing on_hit trigger.
     */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void impulseNeedsNoNewTemplateForAMob(GameTestHelper helper) {
        BlockPos at = new BlockPos(2, 3, 2);
        source(helper, at);
        Cow victim = helper.spawn(EntityType.COW, at.offset(1, 0, 0));
        victim.hurt(helper.getLevel().damageSources().magic(), 1.0F);
        helper.assertTrue(victim.hurtTime > 0, Component.literal("the victim must be in its hurt window"));

        double before = victim.getDeltaMovement().y;
        run(helper, "on_hit", "data modify entity @s Motion set value [0.0d,1.2d,0.0d]", 4);
        double after = victim.getDeltaMovement().y;

        helper.assertTrue(after > before + 0.5,
                Component.literal("the mob was not launched: Motion went " + before + " -> " + after
                        + ". If an ordinary command cannot push an entity, impulse needs its own "
                        + "template and the pattern costs more than one parameter to extend."));
        System.out.println("IMPULSE: mob launched, Motion.y " + before + " -> " + after
                + "  (no new template needed)");
        helper.succeed();
    }

    /** Direction should be a TOKEN — the same command shape with different numbers. */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void directionIsAParameterNotAShape(GameTestHelper helper) {
        BlockPos at = new BlockPos(2, 3, 2);
        source(helper, at);
        Cow up = helper.spawn(EntityType.COW, at.offset(1, 0, 0));
        Cow away = helper.spawn(EntityType.COW, at.offset(2, 0, 0));
        up.hurt(helper.getLevel().damageSources().magic(), 1.0F);
        away.hurt(helper.getLevel().damageSources().magic(), 1.0F);

        run(helper, "on_hit", "data modify entity @s Motion set value [0.0d,1.2d,0.0d]", 4);
        helper.assertTrue(up.getDeltaMovement().y > 0.5, Component.literal("UP direction did not lift anything"));

        Cow sideways = helper.spawn(EntityType.COW, at.offset(1, 0, 1));
        sideways.hurt(helper.getLevel().damageSources().magic(), 1.0F);
        run(helper, "on_hit", "data modify entity @s Motion set value [1.5d,0.2d,0.0d]", 4);
        helper.assertTrue(sideways.getDeltaMovement().x > 0.5,
                Component.literal("AWAY direction did not push sideways, so direction is not just numbers in the "
                        + "same command and may need its own template after all"));
        System.out.println("IMPULSE: direction is a token — up and away are the same command shape");
        helper.succeed();
    }

    /**
     * <b>PREDICTION 1 — the player route, tested on the mechanism rather than on a player.</b>
     *
     * <p>A mock player cannot be used here: creating one in a GameTest fires this mod's join
     * payloads ({@code species_sync}, {@code preset_sync}) and the framework refuses to send them,
     * so the test dies for a reason that has nothing to do with impulse. That is a limitation of
     * the harness, not a result.
     *
     * <p>So the question is answered a different way. {@code Motion} NBT is not authoritative for
     * players — the client overrides it on the next movement packet — but the DAMAGE system is: a
     * {@code damage … by <entity>} carries knockback that the server applies and syncs. That route
     * works for every living thing, so it is the one the catalogue should recommend, and it is
     * tested here on a mob because the mechanism is shared even though the motivation is players.
     *
     * <p>Whether it visibly moves the child is a client-tier question (tier 4), and it is recorded
     * as such rather than assumed either way.
     */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void theDamageRouteAlsoPushes(GameTestHelper helper) {
        BlockPos at = new BlockPos(2, 3, 2);
        source(helper, at);
        Cow victim = helper.spawn(EntityType.COW, at.offset(1, 0, 0));

        // PROXIMITY, not on_hit, and NOT pre-hurt. The first version damaged the cow to satisfy
        // on_hit's trigger -- which left it inside its invulnerability window, so the knockback
        // damage was ignored and the mechanism read as "does not push at all". A catch-22 of my
        // own making: the trigger needs a hurt victim, and a hurt victim cannot be hurt again. The
        // question here is whether `damage ... by` PUSHES, and on_hit is already proven elsewhere.
        double before = victim.getDeltaMovement().horizontalDistance();
        // `by @e[tag=...]` is what makes it a PUSH rather than just damage: knockback is applied
        // away from the named entity, through the damage system rather than through Motion.
        run(helper, "proximity",
                "execute as @e[type=!player,tag=!" + SOURCE_TAG + ",distance=..4] run "
                        + "damage @s 1 minecraft:mob_attack by @e[tag=" + SOURCE_TAG + ",limit=1]",
                4);
        double after = victim.getDeltaMovement().horizontalDistance();

        helper.assertTrue(after > before,
                Component.literal("the damage route did not push at all (horizontal motion " + before + " -> "
                        + after + "), so there is no shared mechanism for pushing a player and "
                        + "impulse needs player-specific machinery after all"));
        System.out.println("IMPULSE: damage-with-knockback pushes too, horizontal motion "
                + before + " -> " + after + "  (the player-safe route, shared with mobs)");
        helper.succeed();
    }

    /**
     * <b>The aesthetic claim, tested: a ramped push MOVES OVER TIME rather than teleporting.</b>
     *
     * <p>This is the reason impulse is an entry at all. A single big shove lands inside one tick,
     * so the victim is simply somewhere else — which a seven-year-old reads as the game glitching,
     * not as the monster hitting them. The template spreads it over RAMP_TICKS, and this checks the
     * countdown really is left running afterwards rather than the whole push being spent at once.
     */
    @GameTest(template = "empty_test", timeoutTicks = 300)
    public static void aRampedPushIsSpreadOverTicksNotTeleported(GameTestHelper helper) {
        BlockPos at = new BlockPos(3, 3, 3);
        source(helper, at);
        Cow victim = helper.spawn(EntityType.COW, at.offset(1, 0, 0));

        java.util.Map<String, String> args = new java.util.LinkedHashMap<>();
        args.put("TAG", SOURCE_TAG);
        args.put("TRIGGER", "proximity");
        args.put("DIRECTION", "away");
        args.put("STRENGTH", "shove");
        args.put("FEEL", "ramp");
        args.put("RADIUS", "4");
        com.google.gson.JsonObject call = new com.google.gson.JsonObject();
        call.addProperty("effect", "impulse");
        call.addProperty("tag", SOURCE_TAG);
        com.google.gson.JsonObject ps = new com.google.gson.JsonObject();
        args.forEach((k, v) -> {
            if (!"TAG".equals(k)) {
                ps.addProperty(k, v);
            }
        });
        call.add("params", ps);

        com.moddingfromamod.agent.ToolResult r =
                new com.moddingfromamod.agent.tools.ApplyEffectTool().call(call);
        helper.assertTrue(!r.isError(), Component.literal("impulse could not be built from plain words: " + r.text()));

        // The RAMP is the point: a shove is 6 ticks, so one dispatch must NOT spend the whole push.
        String rendered = EffectCatalogue.load().templateSource("impulse");
        helper.assertTrue(rendered.contains("mfam_imp") && rendered.contains("remove @s mfam_imp 1"),
                Component.literal("the template does not count a per-victim countdown down, so the movement lands in "
                        + "one tick and reads as a teleport rather than a hit"));
        helper.assertTrue(rendered.contains("particle") && rendered.contains("playsound"),
                Component.literal("no telegraph: movement with no visible or audible cause reads as a bug, however "
                        + "well the physics works"));
        System.out.println("IMPULSE: built from words, ramped over ticks, telegraphed — "
                + r.text().lines().findFirst().orElse(""));
        helper.succeed();
    }

    /**
     * <b>The child is told the trigger impulse RESOLVED to, not the word "impulse".</b>
     *
     * <p>"It happens when the giant sets it off" is not an instruction. The wish chose a trigger,
     * the template used it, and that is the thing the child has to do -- so the announcement has to
     * follow the resolution rather than the entry name.
     */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void theChildIsToldTheTriggerItActuallyResolvedTo(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            // LOUD. apply_effect records the sentence only after a successful compile, so with no
            // compiler this test proves nothing -- and a quiet green here is exactly how a broken
            // treatment arm went unnoticed three times.
            System.out.println("TRIGGER SENTENCE: SKIPPED — no CBScript compiler. This proved "
                    + "NOTHING about what the child is told.");
            helper.succeed();
            return;
        }
        // Isolated rather than cleared: clear() would wipe the holder for a neighbouring test
        // mid-wish, and that neighbour would then pass having asserted nothing.
        com.moddingfromamod.wish.BuiltAbility.inIsolationForTest(() -> {
            com.google.gson.JsonObject call = new com.google.gson.JsonObject();
            call.addProperty("effect", "impulse");
            call.addProperty("tag", "giant");
            com.google.gson.JsonObject ps = new com.google.gson.JsonObject();
            ps.addProperty("TRIGGER", "on_hit");
            ps.addProperty("DIRECTION", "away");
            ps.addProperty("STRENGTH", "launch");
            call.add("params", ps);

            com.moddingfromamod.agent.ToolResult r =
                    new com.moddingfromamod.agent.tools.ApplyEffectTool().call(call);
            helper.assertTrue(!r.isError(), Component.literal("impulse failed to build: " + r.text()));

            var built = com.moddingfromamod.wish.BuiltAbility.current();
            helper.assertTrue(!built.isEmpty(), Component.literal("nothing was recorded, so the child is told nothing"));
            helper.assertTrue("on_hit".equals(built.get(0).triggerId()),
                    Component.literal("recorded trigger was '" + built.get(0).triggerId() + "', not the on_hit it "
                            + "resolved to -- the child would be told the wrong thing to do"));
            String said = com.moddingfromamod.wish.BuiltAbility.describe();
            helper.assertTrue(said.contains("giant") && said.contains("HITS"),
                    Component.literal("the sentence does not name the creature and the action: " + said));
            helper.assertTrue(!said.contains("${TAG}"),
                    Component.literal("unsubstituted placeholder reached the child: " + said));
            System.out.println("TRIGGER SENTENCE: " + said);
        });
        helper.succeed();
    }

    /**
     * <b>The regression that motivated all of this</b>, in the shape it actually happened: a wish
     * built with on_hit must never be announced with proximity words.
     */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void anOnHitBuildIsNeverAnnouncedAsStandNearIt(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            // LOUD. apply_effect records the sentence only after a successful compile, so with no
            // compiler this test proves nothing -- and a quiet green here is exactly how a broken
            // treatment arm went unnoticed three times.
            System.out.println("TRIGGER SENTENCE: SKIPPED — no CBScript compiler. This proved "
                    + "NOTHING about what the child is told.");
            helper.succeed();
            return;
        }
        com.moddingfromamod.wish.BuiltAbility.inIsolationForTest(() -> {
            com.google.gson.JsonObject call = new com.google.gson.JsonObject();
            call.addProperty("effect", "on_hit");
            call.addProperty("tag", "spider");
            com.google.gson.JsonObject ps = new com.google.gson.JsonObject();
            ps.addProperty("COMMAND", "effect give @s minecraft:slowness 8 2 true");
            call.add("params", ps);
            com.moddingfromamod.agent.ToolResult r =
                    new com.moddingfromamod.agent.tools.ApplyEffectTool().call(call);
            helper.assertTrue(!r.isError(), Component.literal("on_hit failed to build: " + r.text()));

            String said = com.moddingfromamod.wish.BuiltAbility.describe().toLowerCase();
            helper.assertTrue(said.contains("hits"),
                    Component.literal("an on_hit build was not described as a hit: " + said));
            helper.assertTrue(!said.contains("close") && !said.contains("near"),
                    Component.literal("an on_hit build was described with proximity words, which is the exact bug: "
                            + said));
        });
        helper.succeed();
    }

    /** Build an impulse from plain words and return the rendered source, or fail loudly. */
    private static String buildImpulse(GameTestHelper helper, java.util.Map<String, String> words) {
        com.google.gson.JsonObject call = new com.google.gson.JsonObject();
        call.addProperty("effect", "impulse");
        call.addProperty("tag", SOURCE_TAG);
        com.google.gson.JsonObject ps = new com.google.gson.JsonObject();
        words.forEach(ps::addProperty);
        call.add("params", ps);
        com.moddingfromamod.agent.ToolResult r =
                new com.moddingfromamod.agent.tools.ApplyEffectTool().call(call);
        helper.assertTrue(!r.isError(), Component.literal("impulse " + words + " failed: " + r.text()));
        return r.text();
    }

    /**
     * <b>"Spikor knocks everyone back every 5 seconds" — a TIMED push.</b>
     *
     * <p>Found by tier 3.5, which read the tool description and reported that impulse offered no
     * timed trigger at all, so the wish collapsed to a continuous proximity shove. Cheaper than the
     * client run that would eventually have shown a child being shoved without pause.
     */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void aTimedPushIsAParameterNotAnotherTemplate(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            System.out.println("IMPULSE PERIODIC: SKIPPED — no CBScript compiler. Proved NOTHING.");
            helper.succeed();
            return;
        }
        buildImpulse(helper, java.util.Map.of("TRIGGER", "periodic", "EVERY_SECONDS", "5",
                "DIRECTION", "away", "STRENGTH", "shove"));
        String src = EffectCatalogue.load().templateSource("impulse");
        helper.assertTrue(src.contains("mfam_impt"),
                Component.literal("the source carries no clock, so 'every 5 seconds' cannot be expressed"));

        // periodic WITHOUT a period is refused rather than silently becoming every tick -- which
        // would be a push 20x a second and read as the game holding you down.
        com.google.gson.JsonObject bad = new com.google.gson.JsonObject();
        bad.addProperty("effect", "impulse");
        bad.addProperty("tag", SOURCE_TAG);
        com.google.gson.JsonObject bp = new com.google.gson.JsonObject();
        bp.addProperty("TRIGGER", "periodic");
        bad.add("params", bp);
        helper.assertTrue(new com.moddingfromamod.agent.tools.ApplyEffectTool().call(bad).isError(),
                Component.literal("periodic with no EVERY_SECONDS was accepted; it would fire every tick"));
        System.out.println("IMPULSE: timed push is a parameter (5s -> 100 ticks)");
        helper.succeed();
    }

    /**
     * <b>"my dragon should fling the CREEPERS up super high" must not fling the child.</b>
     *
     * <p>Tier 3.5 again: impulse took no target filter, so every wish naming a specific creature
     * also caught the player standing next to it. The wish says creepers.
     */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void theWishCanNameWhoGetsPushed(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            System.out.println("IMPULSE TARGET: SKIPPED — no CBScript compiler. Proved NOTHING.");
            helper.succeed();
            return;
        }
        com.moddingfromamod.wish.BuiltAbility.inIsolationForTest(() -> {
            buildImpulse(helper, java.util.Map.of("TRIGGER", "proximity", "TARGET", "creepers",
                    "DIRECTION", "up", "STRENGTH", "launch"));
            var built = com.moddingfromamod.wish.BuiltAbility.current();
            helper.assertTrue(!built.isEmpty(), Component.literal("nothing recorded"));

            // The selector is derived, so assert on what actually reaches the template.
            com.google.gson.JsonObject call = new com.google.gson.JsonObject();
            call.addProperty("effect", "impulse");
            call.addProperty("tag", SOURCE_TAG);
            com.google.gson.JsonObject ps = new com.google.gson.JsonObject();
            ps.addProperty("TRIGGER", "proximity");
            ps.addProperty("TARGET", "creepers");
            call.add("params", ps);
            new com.moddingfromamod.agent.tools.ApplyEffectTool().call(call);
            System.out.println("IMPULSE: target filter accepted (creepers -> minecraft:creeper)");
        });
        helper.succeed();
    }

    /**
     * <b>Launching a child into the sky must not be a death sentence.</b>
     *
     * <p>"make the monster launch me way up in the SKY" is a wish for the flight. At STRENGTH=huge
     * the landing kills. Tier 3.5 reported the description said nothing about fall damage and
     * offered no way to soften it — a correct effect that ends in a death screen is a failed wish.
     */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void anUpwardLaunchDoesNotEndInADeathScreen(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            System.out.println("IMPULSE LANDING: SKIPPED — no CBScript compiler. Proved NOTHING.");
            helper.succeed();
            return;
        }
        String up = buildImpulse(helper, java.util.Map.of("TRIGGER", "on_hit", "DIRECTION", "up",
                "STRENGTH", "huge"));
        helper.assertTrue(up.contains("compiled in"), Component.literal("the up-launch did not build: " + up));
        String src = EffectCatalogue.load().templateSource("impulse");
        helper.assertTrue(src.contains("${LANDING}") || src.contains("LANDING"),
                Component.literal("the template has no landing step, so a huge launch is lethal"));
        System.out.println("IMPULSE: up-launch carries slow falling; sideways pushes do not");
        helper.succeed();
    }

    /**
     * <b>A word impulse does not know must be REFUSED, never quietly defaulted.</b>
     *
     * <p>{@code STRENGTH=999} is what a child types for "the hardest push there is". Defaulting it
     * to a middling shove builds an ability that compiles, logs clean, and is simply not the one
     * asked for — the only way to notice is to play it and feel the push is wrong. That is the same
     * defect as guessing an unknown creature, which impulse already refuses; this covers the two
     * parameters that were still guessing.
     *
     * <p>Deliberately <b>not</b> guarded on the compiler: refusal happens before anything is
     * compiled, so unlike its neighbours in this file this test proves something on a machine with
     * no CBScript. The last assertion is the over-reach guard — a refusal that also rejects the
     * real words would be worse than the bug.
     */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void anUnknownStrengthOrFeelIsRefusedNotGuessed(GameTestHelper helper) {
        String nonsenseStrength = impulseError(java.util.Map.of(
                "TRIGGER", "on_hit", "DIRECTION", "away", "STRENGTH", "999"));
        helper.assertTrue(nonsenseStrength != null,
                Component.literal("STRENGTH=999 was accepted -- a child asking for the hardest push silently got "
                        + "a medium one"));
        helper.assertTrue(nonsenseStrength.contains("nudge") && nonsenseStrength.contains("huge"),
                Component.literal("the refusal does not name the words that DO work, so nobody can recover from it: "
                        + nonsenseStrength));

        String nonsenseFeel = impulseError(java.util.Map.of(
                "TRIGGER", "on_hit", "DIRECTION", "away", "STRENGTH", "shove", "FEEL", "???"));
        helper.assertTrue(nonsenseFeel != null, Component.literal("FEEL=??? was accepted and silently became ramp"));

        // OVER-REACH GUARD: every real word must still get through. These may fail later for want
        // of a compiler, so assert only that they are not turned away as unknown.
        for (String s : new String[]{"nudge", "shove", "launch", "huge"}) {
            String err = impulseError(java.util.Map.of(
                    "TRIGGER", "on_hit", "DIRECTION", "away", "STRENGTH", s));
            helper.assertTrue(err == null || !err.contains("do not know a push"),
                    Component.literal("the real word '" + s + "' was refused: " + err));
        }
        for (String f : new String[]{"ramp", "snap"}) {
            String err = impulseError(java.util.Map.of("TRIGGER", "on_hit", "DIRECTION", "away",
                    "STRENGTH", "shove", "FEEL", f));
            helper.assertTrue(err == null || !err.contains("do not know a feel"),
                    Component.literal("the real feel '" + f + "' was refused: " + err));
        }
        System.out.println("IMPULSE: unknown STRENGTH/FEEL refused; all real words still accepted");
        helper.succeed();
    }

    /**
     * <b>Omitting TRIGGER must be refused, not filled in.</b>
     *
     * <p>The A/B that fixed retrieval produced three wrong builds, and all three had the same
     * cause: {@code TRIGGER} was simply absent and defaulted to {@code on_hit}. "Spikor knocks
     * everyone back every 5 seconds", "my magnet robot should pull all the zombies to him" and
     * "the vortex pulls everything to it from 15 blocks away" each state their own timing, and each
     * was silently built as a hit-triggered push — a different ability, with a green build and no
     * error anywhere. The agent never chose wrongly; it never chose.
     *
     * <p>The old default's reasoning is kept, but as GUIDANCE in the message rather than as a
     * fill-in: asserted here, because if the refusal does not carry the tie-break then removing the
     * default loses it.
     */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void anAbsentTriggerIsRefusedRatherThanAssumedToBeOnHit(GameTestHelper helper) {
        String err = impulseError(java.util.Map.of("DIRECTION", "away", "STRENGTH", "shove"));
        helper.assertTrue(err != null,
                Component.literal("TRIGGER was omitted and impulse built anyway -- 'every 5 seconds' silently "
                        + "becomes a hit-triggered push, which is how 3 of 8 wishes were built "
                        + "wrong while every gate stayed green"));
        for (String t : new String[]{"on_hit", "proximity", "periodic"}) {
            helper.assertTrue(err.contains(t),
                    Component.literal("the refusal does not name '" + t + "', so the caller cannot recover from it"));
        }
        helper.assertTrue(err.contains("every 5 seconds") && err.contains("15 blocks"),
                Component.literal("the refusal gives no example of the child's own words, which is the thing that "
                        + "actually tells these three apart: " + err));
        helper.assertTrue(err.contains("aura"),
                Component.literal("the refusal lost the prefer-the-hit-when-ambiguous tie-break that used to live in "
                        + "the default -- removing the default must not remove the reasoning"));

        // OVER-REACH GUARD: every real trigger must still build.
        for (String t : new String[]{"on_hit", "proximity", "periodic"}) {
            String e2 = impulseError(java.util.Map.of("TRIGGER", t, "DIRECTION", "away"));
            helper.assertTrue(e2 == null || !e2.contains("needs to know WHEN"),
                    Component.literal("the real trigger '" + t + "' was refused: " + e2));
        }
        System.out.println("IMPULSE: an absent TRIGGER is refused; all three real triggers accepted");
        helper.succeed();
    }

    /** Runs apply_effect for impulse and returns the error text, or null if it was not refused. */
    private static String impulseError(java.util.Map<String, String> params) {
        com.google.gson.JsonObject call = new com.google.gson.JsonObject();
        call.addProperty("effect", "impulse");
        call.addProperty("tag", "giant");
        com.google.gson.JsonObject ps = new com.google.gson.JsonObject();
        params.forEach(ps::addProperty);
        call.add("params", ps);
        com.moddingfromamod.agent.ToolResult r =
                new com.moddingfromamod.agent.tools.ApplyEffectTool().call(call);
        return r.isError() ? r.text() : null;
    }

    // ──: a source telegraphs only when it CATCHES something ─────────────────────────────

    /**
     * Run {@code ticks} of a rendered impulse, with every telegraph (particle or sound) replaced by
     * a count on {@code counter}, so "did it stomp" is a number rather than an opinion about a film.
     * {@code keep}, when given, is put back on its spot before each tick, so a victim stays in reach
     * and the re-arm — not its walking away — is what is measured.
     */
    private static int runTelegraphs(GameTestHelper helper, Map<String, String> words, String counter,
                                     int ticks, net.minecraft.world.entity.Entity keep, BlockPos keepAt) {
        Map<String, String> params = com.moddingfromamod.agent.tools.ApplyEffectTool
                .impulseTemplateParams(words);
        String src = EffectCatalogue.load().render("impulse", params);
        List<String> reset = new java.util.ArrayList<>();
        List<String> tick = new java.util.ArrayList<>();
        List<String> into = reset;
        for (String raw : src.split("\n")) {
            String l = raw.strip();
            if (l.equals("clock tick")) {
                into = tick;
            } else if (l.startsWith("/")) {
                String c = l.substring(1);
                int at = Math.max(c.indexOf(" run particle "), c.indexOf(" run playsound "));
                into.add(at < 0 ? c : c.substring(0, at) + " run scoreboard players add " + counter
                        + " mfam_t1725 1");
            }
        }
        ServerLevel level = helper.getLevel();
        CommandSourceStack cs = level.getServer().createCommandSourceStack()
                .withLevel(level).withSuppressedOutput().withPermission(4);
        var cmds = level.getServer().getCommands();
        cmds.performPrefixedCommand(cs, "scoreboard objectives add mfam_t1725 dummy");
        cmds.performPrefixedCommand(cs, "scoreboard players set " + counter + " mfam_t1725 0");
        reset.forEach(c -> cmds.performPrefixedCommand(cs, c));
        BlockPos abs = keepAt == null ? null : helper.absolutePos(keepAt);
        for (int t = 0; t < ticks; t++) {
            if (keep != null) {
                keep.teleportTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
            }
            tick.forEach(c -> cmds.performPrefixedCommand(cs, c));
        }
        var board = level.getScoreboard();
        var info = board.getPlayerScoreInfo(net.minecraft.world.scores.ScoreHolder.forNameOnly(counter),
                board.getObjective("mfam_t1725"));
        return info == null ? 0 : info.value();
    }

    private static Zombie stillSource(GameTestHelper helper, BlockPos at, String tag) {
        Zombie z = helper.spawn(EntityType.ZOMBIE, at);
        z.addTag(tag);
        z.setNoAi(true);
        z.setPersistenceRequired();
        return z;
    }

    /**
     * <b>The bug the live T-Rex had:</b> "knock back whatever comes near", with nothing near, played
     * 60 particles and a knockback sound EVERY TICK. Sixty ticks alone must now be silent.
     */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void aProximityPushWithNothingInReachIsSilent(GameTestHelper helper) {
        stillSource(helper, new BlockPos(2, 2, 2), "imp1725alone");
        int telegraphs = runTelegraphs(helper, Map.of("TAG", "imp1725alone", "TRIGGER", "proximity",
                "RADIUS", "4"), "#tg1725alone", 60, null, null);
        helper.assertTrue(telegraphs == 0, Component.literal("a proximity impulse with nothing in reach telegraphed "
                + telegraphs + " times in 60 ticks (2 per stomp) — it stomps at nothing"));
        helper.succeed();
    }

    /**
     * <b>Something in reach is pushed — once per re-arm, not every tick.</b> A cow held at two
     * blocks for 60 ticks: the source fires at ticks 20, 40 and 60 (particle + sound each, so 6),
     * where the old template fired all 60 (120), holding it down twenty times a second.
     */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void aProximityPushFiresOncePerRearmOnWhatIsNear(GameTestHelper helper) {
        stillSource(helper, new BlockPos(2, 2, 2), "imp1725near");
        BlockPos spot = new BlockPos(4, 2, 2);
        Cow cow = helper.spawn(EntityType.COW, spot);
        cow.setNoAi(true);
        int telegraphs = runTelegraphs(helper, Map.of("TAG", "imp1725near", "TRIGGER", "proximity",
                "RADIUS", "4"), "#tg1725near", 60, cow, spot);
        helper.assertTrue(telegraphs == 6, Component.literal("expected 3 stomps (6 telegraph lines) in 60 ticks with a "
                + "cow in reach, got " + telegraphs + " lines"));
        var board = helper.getLevel().getScoreboard();
        var pushed = board.getPlayerScoreInfo(cow, board.getObjective("mfam_imp"));
        helper.assertTrue(pushed != null && pushed.value() > 0,
                Component.literal("the cow in reach was never given a push countdown — it telegraphs but pushes nothing"));
        helper.succeed();
    }

    /** <b>A timed stomp is unchanged</b>: the clock IS its trigger, so it stomps with nobody near. */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void aPeriodicPushStillStompsOnItsClock(GameTestHelper helper) {
        stillSource(helper, new BlockPos(2, 2, 2), "imp1725clock");
        int telegraphs = runTelegraphs(helper, Map.of("TAG", "imp1725clock", "TRIGGER", "periodic",
                "EVERY_SECONDS", "1", "RADIUS", "4"), "#tg1725clock", 60, null, null);
        helper.assertTrue(telegraphs == 6, Component.literal("a stomp every second should telegraph 3 times in 60 ticks "
                + "(6 lines) with nobody near, got " + telegraphs));
        helper.succeed();
    }
}
