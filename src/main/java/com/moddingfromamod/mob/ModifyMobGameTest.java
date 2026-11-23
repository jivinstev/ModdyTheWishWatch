package com.moddingfromamod.mob;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntitySpawnReason;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.FindContentTool;
import com.moddingfromamod.agent.tools.ModifyMobTool;
import net.minecraft.ChatFormatting;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.neoforged.fml.ModList;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * Gate B for the "summon the real thing, then adjust it" strategy.
 *
 * <p>Two claims are proven here that Gate A structurally cannot reach: that {@code find_content}
 * really does surface a <b>modded</b> boss from the live registry, and that {@code modify_mob}'s
 * size and glow really land on a live entity.
 *
 * <p>Note the shape of every tool call: it runs on a <b>background thread</b> and the assertions
 * are polled with {@code succeedWhen}. Tool bodies go through {@code AgentBridge}, which
 * deliberately refuses to be called from the server thread (it would deadlock) — and a
 * {@code @GameTest} body runs on exactly that thread.
 *
 * <p>The mobs are spawned at <b>world spawn</b>, not inside the test box, because {@code modify_mob}
 * works relative to the child — and with nobody online that means world spawn.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class ModifyMobGameTest {

    private static final String WITHER_STORM_MOD = "witherstormmod";

    /**
     * <b>The regression this whole strategy exists for.</b> A child asked for a wither storm; the
     * mod providing the real boss was installed the entire time, and he got a black spider in a
     * wither-skeleton costume instead. So: when that mod IS present, searching the child's words
     * must surface {@code witherstormmod:*}, and it must rank above {@code minecraft:wither}.
     *
     * <p>Skip-succeeds when the mod isn't installed, so a clean checkout stays green. Run it for
     * real with:
     * {@code ./gradlew runGameTestServer -Psmokejars="/abs/path/witherstormmod-1.21.1-4.2.1.jar"}
     */
    @GameTest(template = "empty_test", timeoutTicks = 300)
    public static void findContentSurfacesTheRealWitherStorm(GameTestHelper helper) {
        AtomicReference<ToolResult> reply = new AtomicReference<>();
        offThread(() -> reply.set(new FindContentTool().call(args("{\"query\":\"wither storm\","
                + "\"type\":\"mob\"}"))));

        helper.succeedWhen(() -> {
            ToolResult r = reply.get();
            helper.assertTrue(r != null, Component.literal("waiting for find_content"));
            helper.assertFalse(r.isError(), Component.literal("find_content should not error: " + r.text()));
            String text = r.text();

            if (!ModList.get().isLoaded(WITHER_STORM_MOD)) {
                // The mod isn't here, but the tool must still work — otherwise this test would pass
                // on a clean checkout while being completely broken.
                helper.assertTrue(text.contains("minecraft:wither"),
                        Component.literal("even without the mod, 'wither storm' should surface vanilla withers: " + text));
                return;
            }

            helper.assertTrue(text.contains(WITHER_STORM_MOD + ":"),
                    Component.literal("the REAL wither storm must be found when its mod is installed: " + text));
            int modded = text.indexOf(WITHER_STORM_MOD + ":");
            int vanilla = text.indexOf("minecraft:wither");
            helper.assertTrue(vanilla < 0 || modded < vanilla,
                    Component.literal("the modded boss must be listed BEFORE minecraft:wither — the agent acts on the "
                            + "first plausible hit: " + text));
        });
    }

    /** "Make it bigger!" on a real mob: the SCALE attribute lands, and the hitbox grows with it. */
    @GameTest(template = "empty_test", timeoutTicks = 300)
    public static void modifyMobResizesARealMob(GameTestHelper helper) {
        LivingEntity sheep = spawnAtWorldSpawn(helper, EntityType.SHEEP);
        float widthBefore = sheep.getBbWidth();

        AtomicReference<ToolResult> reply = new AtomicReference<>();
        offThread(() -> reply.set(new ModifyMobTool().call(
                args("{\"scope\":\"one\",\"target\":\"sheep\",\"scale\":4.0,\"name\":\"Big Sheep\"}"))));

        helper.succeedWhen(() -> {
            ToolResult r = reply.get();
            helper.assertTrue(r != null, Component.literal("waiting for modify_mob"));
            helper.assertFalse(r.isError(), Component.literal("modify_mob should have worked: " + r.text()));
            helper.assertTrue(sheep.getAttributeValue(Attributes.SCALE) == 4.0,
                    Component.literal("scale should be 4.0, was " + sheep.getAttributeValue(Attributes.SCALE)));
            helper.assertTrue(sheep.getBbWidth() > widthBefore * 2,
                    Component.literal("the hitbox must grow with the model (" + sheep.getBbWidth()
                            + " vs " + widthBefore + ")"));
            helper.assertTrue(sheep.getCustomName() != null
                            && sheep.getCustomName().getString().equals("Big Sheep"),
                    Component.literal("the name should have been applied"));
        });
    }

    /**
     * "Make it pink!" on a real mob. A modded texture lives in that mod's jar and can't be
     * repainted, so colour is delivered as a glow outline — which means the entity must end up
     * both glowing AND on a team carrying the right colour. Miss either half and it stays white.
     */
    @GameTest(template = "empty_test", timeoutTicks = 300)
    public static void modifyMobGlowsInAColour(GameTestHelper helper) {
        LivingEntity cow = spawnAtWorldSpawn(helper, EntityType.COW);

        AtomicReference<ToolResult> reply = new AtomicReference<>();
        offThread(() -> reply.set(new ModifyMobTool().call(
                args("{\"scope\":\"one\",\"target\":\"cow\",\"glow\":\"pink\"}"))));

        helper.succeedWhen(() -> {
            ToolResult r = reply.get();
            helper.assertTrue(r != null, Component.literal("waiting for modify_mob"));
            helper.assertFalse(r.isError(), Component.literal("modify_mob should have worked: " + r.text()));
            helper.assertTrue(cow.hasGlowingTag(), Component.literal("the cow should be glowing"));

            PlayerTeam team = helper.getLevel().getScoreboard()
                    .getPlayersTeam(cow.getScoreboardName());
            helper.assertTrue(team != null, Component.literal("a glowing mob needs a team for the outline colour"));
            helper.assertTrue(team.getColor() == ChatFormatting.LIGHT_PURPLE,
                    Component.literal("'pink' should snap to LIGHT_PURPLE, was " + team.getColor()));
        });
    }

    /**
     * <b>Regression.</b> Gate B was intermittently red with
     * <i>"The cow (@cow) he has been talking about is GONE"</i> — and the product was right. The
     * cow it meant belonged to <b>another test</b>: {@code EntityHandles.global()} is process-wide,
     * 50 tests run concurrently in {@code defaultBatch}, a targeted handle outlives its mob, and
     * {@code useTheOneHeIsTalkingAbout} correctly prefers an established referent over the nearest
     * animal. So a test that had never met that cow inherited it.
     *
     * <p>This poisons the registry on purpose, the way a concurrent test does by accident, and
     * asserts the poisoning no longer reaches us. It uses its own species for exactly the reason it
     * is testing: sharing one would recreate the bug between these two tests.
     *
     * <p><b>Verified to fail</b> with {@code StaleHandles.forgetEvery} removed from
     * {@code spawnAtWorldSpawn} — it reports the GONE message, which is the original failure.
     */
    @GameTest(template = "empty_test", timeoutTicks = 300)
    public static void aDeadHandleFromAnotherTestDoesNotHijackThisOne(GameTestHelper helper) {
        // A pig that no longer exists, already TARGETED: precisely what a finished test leaves in
        // the shared registry. No entity with this id has ever been in the world.
        EntityHandles.global().targeted(UUID.randomUUID(), "minecraft:pig", null);

        LivingEntity pig = spawnAtWorldSpawn(helper, EntityType.PIG);

        AtomicReference<ToolResult> reply = new AtomicReference<>();
        offThread(() -> reply.set(new ModifyMobTool().call(
                args("{\"scope\":\"one\",\"target\":\"pig\",\"scale\":2.0}"))));

        helper.succeedWhen(() -> {
            ToolResult r = reply.get();
            helper.assertTrue(r != null, Component.literal("waiting for modify_mob"));
            helper.assertFalse(r.isError(),
                    Component.literal("a dead pig left behind by another test must not become this wish's referent: "
                            + r.text()));
            helper.assertTrue(pig.getAttributeValue(Attributes.SCALE) == 2.0,
                    Component.literal("the pig standing here should have grown, was ×"
                            + pig.getAttributeValue(Attributes.SCALE)));
        });
    }

    /**
     * A miss must explain itself. Returning a bare "not found" sends the agent round a retry loop
     * with the same wrong word, which the child experiences as nothing happening.
     */
    @GameTest(template = "empty_test", timeoutTicks = 300)
    public static void anUnmatchedTargetExplainsWhatIsNearby(GameTestHelper helper) {
        spawnAtWorldSpawn(helper, EntityType.CHICKEN);

        AtomicReference<ToolResult> reply = new AtomicReference<>();
        offThread(() -> reply.set(new ModifyMobTool().call(
                args("{\"scope\":\"one\",\"target\":\"definitely_not_a_real_mob\",\"scale\":2}"))));

        helper.succeedWhen(() -> {
            ToolResult r = reply.get();
            helper.assertTrue(r != null, Component.literal("waiting for modify_mob"));
            helper.assertTrue(r.isError(), Component.literal("an unmatched target should be an error"));
            helper.assertTrue(r.text().contains("minecraft:"),
                    Component.literal("the error should name what IS nearby so the agent can self-correct: " + r.text()));
        });
    }

    /** Asking for no change at all is caught before anything touches the world. */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void anEmptyRequestIsRefused(GameTestHelper helper) {
        AtomicReference<ToolResult> reply = new AtomicReference<>();
        offThread(() -> reply.set(new ModifyMobTool().call(args("{\"scope\":\"one\",\"target\":\"cow\"}"))));

        helper.succeedWhen(() -> {
            ToolResult r = reply.get();
            helper.assertTrue(r != null, Component.literal("waiting for modify_mob"));
            helper.assertTrue(r.isError(), Component.literal("a tweak with nothing to change should be refused"));
        });
    }

    // ===================== helpers =====================

    private static LivingEntity spawnAtWorldSpawn(GameTestHelper helper, EntityType<?> type) {
        com.moddingfromamod.testing.MockPlayerNetwork.requireNobodyIsOnline(helper);
        ServerLevel overworld = helper.getLevel().getServer().overworld();
        Vec3 at = Vec3.atBottomCenterOf(overworld.getSharedSpawnPos());
        // Arrive clean, the same discipline MobIdentityGameTest keeps: world spawn is
        // outside every test plot and is therefore never reset, so a failed earlier test leaves its
        // animal standing here — and, worse, leaves a TARGETED handle for this species that
        // modify_mob will resolve in preference to the one we are about to spawn.
        for (LivingEntity stale : overworld.getEntitiesOfClass(LivingEntity.class,
                new AABB(at, at).inflate(64), e -> e.getType() == type)) {
            stale.discard();
        }
        StaleHandles.forgetEvery(type);
        LivingEntity entity = (LivingEntity) type.create(overworld, EntitySpawnReason.COMMAND);
        entity.snapTo(at.x, at.y, at.z, 0, 0);
        overworld.addFreshEntity(entity);
        return entity;
    }

    private static JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static void offThread(Runnable work) {
        Thread t = new Thread(work, "modify-mob-gametest");
        t.setDaemon(true);
        t.start();
    }
}
