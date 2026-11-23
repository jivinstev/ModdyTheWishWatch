package com.moddingfromamod.datapack;

import net.minecraft.network.chat.Component;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.monster.Zombie;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Tier 2 for the four new ability classes: does the WORLD change?</b>
 *
 * <p>The compile matrix (tier 1) proves 336 combinations render and compile. It cannot tell a
 * template that works from one that runs happily and does nothing — and the whole promise of a
 * precomputed ability is that it is known to WORK, not known to parse.
 *
 * <p>These four entries were outside the matrix entirely for one run: 205 tests passed while
 * on_death, on_low_health, lingering_zone and breath_cone had never been compiled by anything. That
 * is the shape of green that means nothing, and it is why each new entry gets both tiers.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class NewClassBehaviourGameTest {

    /**
     * The creature tag, ONE PER TEST. It was one tag for the whole class, and every entry
     * here runs as `@e[tag=…]` across the LEVEL — so in a full run another test's zombie, running
     * concurrently in the same batch, breathed on this test's "behind" cow: 10.0 → 7.0, exactly the
     * breath's own `damage @s 3`. Each test now names its own creatures.
     */
    private static final String TAG = "newclassmob";

    private static String tag(String test) {
        return TAG + "_" + test;
    }

    /** Render an entry with the given words and run its commands once, as the server. */
    private static void runOnce(GameTestHelper helper, String tag, String effect, Map<String, String> words) {
        EffectCatalogue catalogue = EffectCatalogue.load();
        Map<String, String> params = new LinkedHashMap<>(words);
        params.put("TAG", tag);
        // Derived params are the tool's job, not the agent's; supply the ones these templates need.
        for (String d : catalogue.get(effect).derived()) {
            params.putIfAbsent(d, switch (d) {
                case "PERIOD_TICKS" -> "1";
                case "LASTS_TICKS" -> "40";
                case "BREATH_TICKS" -> "20";
                default -> "1";
            });
        }
        List<String> lines = catalogue.render(effect, params).lines()
                .map(String::strip).filter(l -> l.startsWith("/")).map(l -> l.substring(1)).toList();
        ServerLevel level = helper.getLevel();
        CommandSourceStack src = level.getServer().createCommandSourceStack()
                .withLevel(level).withSuppressedOutput().withPermission(4);
        for (String c : lines) {
            level.getServer().getCommands().performPrefixedCommand(src, c);
        }
    }

    private static Zombie source(GameTestHelper helper, String tag, BlockPos at) {
        Zombie z = helper.spawn(EntityType.ZOMBIE, at);
        z.addTag(tag);
        z.setPersistenceRequired();
        return z;
    }

    /**
     * <b>BREATH IS DIRECTIONAL — the whole reason this class exists.</b>
     *
     * <p>Steve asked twice: "make him BREATHE FIRE" and then, immediately, "make sure his fire breath
     * SHOOTS FROM HIS MOUTH!". The second wish is the real one — an aura centred on the creature
     * satisfies the first and a child can tell instantly that it is wrong.
     *
     * <p>So this is the assertion that matters: someone IN FRONT is caught, someone BEHIND is not.
     * A test that only checked "the victim was affected" would pass on an aura.
     */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void breathCatchesWhatIsInFrontAndNotWhatIsBehind(GameTestHelper helper) {
        String tag = tag("breath");
        BlockPos at = new BlockPos(3, 3, 3);
        Zombie dragon = source(helper, tag, at);
        dragon.setYRot(0.0F);           // facing +Z
        dragon.setYHeadRot(0.0F);
        dragon.yRotO = 0.0F;

        Cow inFront = helper.spawn(EntityType.COW, at.offset(0, 0, 3));
        Cow behind = helper.spawn(EntityType.COW, at.offset(0, 0, -3));
        float frontBefore = inFront.getHealth();
        float behindBefore = behind.getHealth();

        runOnce(helper, tag, "breath_cone", Map.of("COMMAND", "damage @s 3 minecraft:magic"));

        helper.assertTrue(inFront.getHealth() < frontBefore,
                Component.literal("the breath missed what was directly in front of the creature's face, so it is not "
                        + "a breath at all"));
        helper.assertTrue(behind.getHealth() == behindBefore,
                Component.literal("something BEHIND the creature was caught by its breath (" + behindBefore + " -> "
                        + behind.getHealth() + "). That is an aura wearing a cone's name, and it is "
                        + "exactly the wish Steve had to ask for twice."));
        System.out.println("BREATH: in-front hurt " + frontBefore + "->" + inFront.getHealth()
                + ", behind untouched at " + behind.getHealth());
        helper.succeed();
    }

    /**
     * <b>A lingering zone is left BEHIND — it does not follow the creature.</b>
     *
     * <p>That is the entire difference between this and an aura, and the reason it needed a template
     * rather than a parameter: the danger belongs to a place, not to the mob.
     */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void aZoneIsLeftBehindAtAPlace(GameTestHelper helper) {
        String tag = tag("zone");
        BlockPos at = new BlockPos(3, 3, 3);
        Zombie mob = source(helper, tag, at);
        runOnce(helper, tag, "lingering_zone", Map.of("COMMAND", "damage @s 2 minecraft:magic",
                "PARTICLE", "minecraft:snowflake", "SOUND", "minecraft:block.powder_snow.place"));

        long markers = helper.getLevel().getEntities(EntityType.ARMOR_STAND,
                        e -> e.getTags().contains("mfam_zone_" + tag)).size();
        helper.assertTrue(markers > 0,
                Component.literal("no zone marker was dropped, so nothing is left behind and this is just an aura"));

        // The mob walks away; the zone must stay where it was dropped.
        BlockPos moved = at.offset(0, 0, 8);
        mob.teleportTo(helper.absolutePos(moved).getX() + 0.5,
                helper.absolutePos(moved).getY(), helper.absolutePos(moved).getZ() + 0.5);
        var stand = helper.getLevel().getEntities(EntityType.ARMOR_STAND,
                e -> e.getTags().contains("mfam_zone_" + tag)).get(0);
        helper.assertTrue(stand.distanceTo(mob) > 4.0,
                Component.literal("the zone followed the creature, which makes it an aura with extra steps"));
        System.out.println("ZONE: marker stayed put, " + String.format("%.1f", stand.distanceTo(mob))
                + " blocks from the creature that dropped it");
        helper.succeed();
    }

    /**
     * <b>on_death fires at the place the creature FELL.</b>
     *
     * <p><b>The obvious implementation cannot work, and fails silently.</b> A dying mob counts
     * DeathTime up from 1, so {@code @e[tag=X,nbt={DeathTime:1s}]} reads like the exact tick to fire
     * on. It compiles, and it never runs: Minecraft's {@code @e} only matches entities that are
     * ALIVE, and a mob in its death animation is not. A probe confirmed DeathTime really is 1 and
     * really does serialise as {@code 1s} while the selector matches nobody — so the template was
     * correct about the game and wrong about the selector, which is the hardest kind to spot.
     *
     * <p>That version passed all 336 compile-matrix cells. This test is the only thing that caught
     * it, which is the argument for tier 2 in a single bug.
     *
     * <p>What works instead is a shadow marker: every living tagged creature keeps an invisible
     * marker on it, replaced each tick. When it dies it stops being selectable, no fresh marker is
     * placed, and last tick's marker is left standing where it fell — so a marker with no living
     * creature beside it MEANS "it just died here".
     */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void deathFiresOnceWhereTheCreatureFell(GameTestHelper helper) {
        String tag = tag("death");
        BlockPos at = new BlockPos(3, 3, 3);
        Zombie mob = source(helper, tag, at);

        // RUN IT EVERY TICK, like the real thing does: the shadow marker has to be placed while the
        // creature is alive for its absence to mean anything a tick later.
        Map<String, String> words = Map.of("COMMAND",
                "summon minecraft:armor_stand ~ ~ ~ {Tags:[\"deathmark\"],Invisible:1b,Marker:1b}");
        for (int tick = 1; tick <= 10; tick++) {
            helper.runAfterDelay(tick, () -> runOnce(helper, tag, "on_death", words));
        }
        // ALIVE for the first few ticks: nothing may fire. Without this half, a template that
        // fired constantly would pass — and "his death explosion goes off every tick while he is
        // walking around" is a worse bug than one that never fires.
        helper.runAfterDelay(4, () -> {
            long early = helper.getLevel().getEntities(EntityType.ARMOR_STAND,
                    e -> e.getTags().contains("deathmark")).size();
            helper.assertTrue(early == 0,
                    Component.literal("the death act fired " + early + " time(s) while the creature was still ALIVE"));
            mob.hurt(helper.getLevel().damageSources().magic(), 1000.0F);
        });
        helper.runAfterDelay(12, () -> {
            long marks = helper.getLevel().getEntities(EntityType.ARMOR_STAND,
                    e -> e.getTags().contains("deathmark")).size();
            helper.assertTrue(marks > 0,
                    Component.literal("nothing ran as the creature died. NOTE: the obvious DeathTime selector cannot "
                            + "work at all — @e never matches a dead entity — so this is the "
                            + "shadow-marker path failing, not a timing guess."));
            helper.assertTrue(marks == 1,
                    Component.literal("a death sequence fired " + marks + " times. DeathTime climbs 1..20, so a rule "
                            + "matching more than the first tick repeats the whole death scene "
                            + "twenty times over."));
            System.out.println("ON DEATH: fired exactly once, at the spot it fell");
            helper.succeed();
        });
    }

    /**
     * <b>on_low_health fires when hurt past the line, and NOT while healthy.</b>
     *
     * <p>The negative half is the real assertion. A phase change that fires at full health is not a
     * phase change, and every template here reads health through the scoreboard because a selector
     * cannot compare a number in NBT at all.
     */
    @GameTest(template = "empty_test", timeoutTicks = 300)
    public static void phaseTwoWaitsUntilTheCreatureIsActuallyHurt(GameTestHelper helper) {
        String tag = tag("phase");
        BlockPos at = new BlockPos(3, 3, 3);
        Zombie boss = source(helper, tag, at);

        Map<String, String> words = Map.of("PERCENT", "50", "COMMAND",
                "summon minecraft:armor_stand ~ ~ ~ {Tags:[\"phase2\"],Invisible:1b,Marker:1b}");

        // HEALTHY: several ticks of the rule must produce nothing.
        for (int i = 0; i < 3; i++) {
            runOnce(helper, tag, "on_low_health", words);
        }
        long early = helper.getLevel().getEntities(EntityType.ARMOR_STAND,
                e -> e.getTags().contains("phase2")).size();
        helper.assertTrue(early == 0,
                Component.literal("phase two fired at FULL health (" + early + " marker(s)), so it is not a phase "
                        + "change, it is a spawn effect"));

        // HURT past the line.
        boss.setHealth(boss.getMaxHealth() * 0.25F);
        for (int i = 0; i < 3; i++) {
            runOnce(helper, tag, "on_low_health", words);
        }
        long late = helper.getLevel().getEntities(EntityType.ARMOR_STAND,
                e -> e.getTags().contains("phase2")).size();
        helper.assertTrue(late > 0,
                Component.literal("the creature was at a quarter health and phase two never fired"));
        System.out.println("PHASE 2: silent at full health, fired at 25% (" + late + " marker(s))");
        helper.succeed();
    }

    /**
     * <b>A child's plural must resolve to a REAL entity id, checked against the running game.</b>
     *
     * <p>The first version guessed with string rules and produced {@code minecraft:zomby} for
     * "zombies" (the -ies rule fired before the -s rule), {@code minecraft:wolve} for "wolves" and
     * {@code minecraft:endermen} for "endermen". An invalid entity type makes the WHOLE command
     * fail to parse, so the ability silently did nothing.
     *
     * <p>Nothing upstream could catch that. Tier 3.5 checks which EFFECT was chosen, not what the
     * selector resolved to; the compile matrix only ever sees the selector as text. It needed a
     * test with a real registry — and "zombies" is one of the most common words in the corpus.
     */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void aChildsPluralResolvesToARealCreature(GameTestHelper helper) {
        String tag = tag("plural");
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("zombies", "minecraft:zombie");
        expected.put("creepers", "minecraft:creeper");
        expected.put("wolves", "minecraft:wolf");
        expected.put("endermen", "minecraft:enderman");
        expected.put("skeletons", "minecraft:skeleton");
        expected.put("sheep", "minecraft:sheep");
        expected.put("foxes", "minecraft:fox");

        for (var e : expected.entrySet()) {
            com.google.gson.JsonObject call = new com.google.gson.JsonObject();
            call.addProperty("effect", "impulse");
            call.addProperty("tag", tag);
            com.google.gson.JsonObject ps = new com.google.gson.JsonObject();
            ps.addProperty("TRIGGER", "proximity");
            ps.addProperty("TARGET", e.getKey());
            call.add("params", ps);
            var r = new com.moddingfromamod.agent.tools.ApplyEffectTool().call(call);
            helper.assertTrue(!r.isError() || !CbScriptCompiler.available(),
                    Component.literal("impulse TARGET=" + e.getKey() + " failed: " + r.text()));
        }

        // A word the game has no creature for is REFUSED, not guessed. "bunnies" has no entity id,
        // and minecraft:bunny in a selector fails to parse -- an ability that builds cleanly and
        // does nothing, which the child experiences as the toy being broken.
        {
            com.google.gson.JsonObject call = new com.google.gson.JsonObject();
            call.addProperty("effect", "impulse");
            call.addProperty("tag", tag);
            com.google.gson.JsonObject ps = new com.google.gson.JsonObject();
            ps.addProperty("TRIGGER", "proximity");
            ps.addProperty("TARGET", "bunnies");
            call.add("params", ps);
            var bad = new com.moddingfromamod.agent.tools.ApplyEffectTool().call(call);
            helper.assertTrue(bad.isError(),
                    Component.literal("TARGET=bunnies was accepted; it resolves to no real entity, so the selector "
                            + "fails to parse and the push silently never happens"));
        }

        // Every resolvable plural must name a type the game actually has.
        for (String word : List.of("zombies", "creepers", "wolves", "endermen", "skeletons",
                "sheep", "foxes")) {
            String id = com.moddingfromamod.agent.tools.ApplyEffectTool.resolveCreatureId(word);
            helper.assertTrue(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                            .containsKey(net.minecraft.resources.ResourceLocation.parse(id)),
                    Component.literal("'" + word + "' resolved to '" + id + "', which is not a real entity type — "
                            + "the selector will fail to parse and the ability will silently do "
                            + "nothing"));
            helper.assertTrue(id.equals(expected.get(word)),
                    Component.literal("'" + word + "' resolved to '" + id + "', expected " + expected.get(word)));
        }
        System.out.println("TARGET: child plurals resolve against the live registry");
        helper.succeed();
    }

    /**
     * <b>An eruption raises blocks, takes them back, and CANNOT eat what the child built.</b>
     *
     * <p>The third assertion is the one that matters. A creature ability that can delete a
     * seven-year-old's house is not a feature however good the rest of it is — so the eruption only
     * ever fills AIR, and only ever removes its OWN block type. Both halves are checked, because
     * either one alone still loses something: filling non-air overwrites the build, and removing
     * indiscriminately deletes whatever the child put among the spikes.
     */
    @GameTest(template = "empty_test", timeoutTicks = 300)
    public static void anEruptionRaisesGroundWithoutEatingTheChildsBuild(GameTestHelper helper) {
        String tag = tag("erupt");
        BlockPos at = new BlockPos(3, 3, 3);
        source(helper, tag, at);

        // Something precious, standing exactly where the eruption will happen.
        BlockPos treasure = at.offset(1, 0, 0);
        helper.setBlock(treasure, net.minecraft.world.level.block.Blocks.DIAMOND_BLOCK);

        Map<String, String> words = Map.of("BLOCK", "minecraft:amethyst_block",
                "COMMAND", "damage @s 1 minecraft:magic", "RADIUS", "2", "HEIGHT", "2",
                "PARTICLE", "minecraft:crit", "SOUND", "minecraft:block.deepslate.break");

        runOnce(helper, tag, "erupt_blocks", words);   // marker dropped
        runOnce(helper, tag, "erupt_blocks", words);   // marker erupts

        boolean raised = false;
        for (int dx = -2; dx <= 2 && !raised; dx++) {
            for (int dy = 0; dy <= 2 && !raised; dy++) {
                for (int dz = -2; dz <= 2 && !raised; dz++) {
                    if (helper.getBlockState(at.offset(dx, dy, dz)).is(
                            net.minecraft.world.level.block.Blocks.AMETHYST_BLOCK)) {
                        raised = true;
                    }
                }
            }
        }
        helper.assertTrue(raised, Component.literal("nothing came out of the ground, so this changes no blocks at all"));
        helper.assertBlockPresent(net.minecraft.world.level.block.Blocks.DIAMOND_BLOCK, treasure);

        // Run the clock out; the ground must take back ONLY its own blocks.
        Map<String, String> expiring = new LinkedHashMap<>(words);
        expiring.put("LASTS_TICKS", "1");
        for (int i = 0; i < 4; i++) {
            runOnce(helper, tag, "erupt_blocks", expiring);
        }
        helper.assertBlockPresent(net.minecraft.world.level.block.Blocks.DIAMOND_BLOCK, treasure);
        System.out.println("ERUPT: ground rose, and the diamond block survived both the eruption "
                + "and the clean-up");
        helper.succeed();
    }

    /**
     * a trail is left where it WALKED, only into air, and fades taking back only its own block
     * — a flower he put on the old mark stays. And the tool refuses a full block before any of this,
     * because a trail is put round the creature's own feet.
     */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void aTrailIsLeftWhereItWalkedAndFadesTakingOnlyItsOwn(GameTestHelper helper) {
        String tag = tag("trail");
        BlockPos at = new BlockPos(3, 3, 3), next = new BlockPos(3, 3, 7);
        helper.setBlock(at.below(), net.minecraft.world.level.block.Blocks.STONE);
        helper.setBlock(next.below(), net.minecraft.world.level.block.Blocks.STONE);
        Zombie mob = source(helper, tag, at);
        mob.setNoAi(true);
        helper.assertTrue(com.moddingfromamod.agent.tools.ApplyEffectTool.fillLeaveBehind("trail",
                new LinkedHashMap<>(Map.of("BLOCK", "minecraft:stone")), null) != null,
                Component.literal("a full block is refused: it would wall the creature's feet in"));
        helper.assertTrue(com.moddingfromamod.agent.tools.ApplyEffectTool.fillLeaveBehind("trail",
                new LinkedHashMap<>(Map.of("BLOCK", "minecraft:snow")), null) == null, Component.literal("a snow layer is fine"));
        Map<String, String> words = Map.of("BLOCK", "minecraft:snow", "PARTICLE", "minecraft:snowflake",
                "MOST", "60", "LASTS_TICKS", "2", "STEP_TICKS", "1");
        runOnce(helper, tag, "trail", words);
        helper.assertBlockPresent(net.minecraft.world.level.block.Blocks.SNOW, at);
        // It walks on; he plants a poppy where the first mark was.
        BlockPos abs = helper.absolutePos(next);
        mob.teleportTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
        helper.setBlock(at, net.minecraft.world.level.block.Blocks.POPPY);
        runOnce(helper, tag, "trail", words);
        helper.assertBlockPresent(net.minecraft.world.level.block.Blocks.SNOW, next);
        helper.assertBlockPresent(net.minecraft.world.level.block.Blocks.POPPY, at);
        // It wanders off (standing on a faded mark, it would rightly leave a new one).
        mob.teleportTo(mob.getX(), mob.getY() + 40, mob.getZ());
        runOnce(helper, tag, "trail", words);
        runOnce(helper, tag, "trail", words);
        helper.assertBlockNotPresent(net.minecraft.world.level.block.Blocks.SNOW, next);
        helper.assertTrue(helper.getLevel().getEntities(EntityType.ARMOR_STAND,
                e -> e.getTags().contains("mfam_tr_" + tag)).size() <= 1, Component.literal("faded marks are gone"));
        mob.discard();
        helper.succeed();
    }

    /** drops land at its feet on its timer and stop while MOST lie unclaimed; picked up, they start again. */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void dropsStopAtAPileAndStartAgainWhenPickedUp(GameTestHelper helper) {
        String tag = tag("drops");
        BlockPos at = new BlockPos(3, 3, 3);
        helper.setBlock(at.below(), net.minecraft.world.level.block.Blocks.STONE);
        Zombie mob = source(helper, tag, at);
        mob.setNoAi(true);
        Map<String, String> words = Map.of("ITEM", "minecraft:honey_bottle", "SOUND", "minecraft:entity.chicken.egg",
                "MOST", "3", "PERIOD_TICKS", "1");
        java.util.function.Supplier<java.util.List<? extends net.minecraft.world.entity.item.ItemEntity>> lying = () ->
                helper.getLevel().getEntities(EntityType.ITEM, e -> e.getTags().contains("mfam_drop_" + tag));
        for (int i = 0; i < 6; i++) {
            runOnce(helper, tag, "drops", words);
        }
        helper.assertTrue(lying.get().size() == 3, Component.literal("it stops at the pile: " + lying.get().size()));
        helper.assertTrue(lying.get().get(0).getItem().is(net.minecraft.world.item.Items.HONEY_BOTTLE), Component.literal("honey"));
        lying.get().get(0).discard();   // he picked one up
        runOnce(helper, tag, "drops", words);
        helper.assertTrue(lying.get().size() == 3, Component.literal("and it drops again once he takes one: " + lying.get().size()));
        lying.get().forEach(e -> e.discard());
        mob.discard();
        helper.succeed();
    }
}
