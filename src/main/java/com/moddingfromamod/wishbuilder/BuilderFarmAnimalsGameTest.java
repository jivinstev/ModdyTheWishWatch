package com.moddingfromamod.wishbuilder;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.McpProtocol;
import com.moddingfromamod.agent.McpSessions;
import com.moddingfromamod.agent.WishAgent;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

/**
 * <b>Real farm animals from the Builder breed</b>, tested where they run: a shipped world's own menus ("A creature",
 * then the creature page's "Real cows": no parts, powers, mood, size or colours, only how many), two of them, made by
 * {@link BuilderMake#run} through the REAL {@code run_command summon}. Then the world: two grown cows stand there, he
 * gives each one wheat the way vanilla does (holding it, using it on the cow), and a calf is born. The control is the
 * moment before the wheat: no calf.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class BuilderFarmAnimalsGameTest {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    private BuilderFarmAnimalsGameTest() {
    }

    @GameTest(template = "empty_test", batch = "builderFarmAnimals", timeoutTicks = 6000)
    public static void aBuilderPairOfCowsBreeds(GameTestHelper helper) {
        BuilderAllowlist allow = BuilderSuppliesGameTest.shippedWorldBuilder();
        BuilderFlow f = new BuilderFlow(allow, LibraryPresets.SHIPPED);
        f.press("creature");
        helper.assertTrue(f.press("mob:minecraft:cow").say().isEmpty() && f.step() == BuilderFlow.Step.TUNE,
                Component.literal("the creature page offers no real cows (at " + f.step() + ")"));
        helper.assertTrue(f.tune().keys().equals(List.of("count", "mobsize", BuilderActs.SLOT)),
                Component.literal("a real cow asks only how many, how big (#2258) and how it acts (#2402), not " + f.tune().keys()));
        BuilderSpec spec = f.spec(Map.of("count", "2"));
        helper.assertTrue(allow.problems(spec).isEmpty(), Component.literal("the world refuses two cows: " + allow.problems(spec)));

        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "gtcows");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        child.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        MockPlayerNetwork.actingChildFor(helper, child);
        long rise = BuilderMake.riseMillis;
        BuilderMake.riseMillis = 0;   // the pillar's rise is BuilderMakeGameTest's
        MockPlayerNetwork.afterTest(helper, () -> BuilderMake.riseMillis = rise);
        MinecraftServer server = helper.getLevel().getServer();
        // HIS cows are the ones his make brought, never every cow round him: each batch's tests stand on the
        // same ground (the grid starts again at its first corner), so in a long run a cow an earlier test made and
        // left stands within 48 blocks of him — and was counted as his. A stranger's cow is staged right beside him
        // as the control: it is there before the make, so it is not his, and it must not be fed or counted.
        Cow stranger = net.minecraft.world.entity.EntityTypes.COW.create(helper.getLevel(),
                net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        stranger.snapTo(at.getX() + 3.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        helper.getLevel().addFreshEntity(stranger);
        Set<UUID> before = new HashSet<>();
        cows(helper, child).forEach(c -> before.add(c.getUUID()));
        helper.assertTrue(before.contains(stranger.getUUID()), Component.literal("the stranger's cow is not standing by him"));
        MockPlayerNetwork.afterTest(helper, stranger::discard);
        CompletableFuture<BuilderMake.Result> made = new CompletableFuture<>();
        server.execute(() -> BuilderMake.run(server, BuilderMake.Who.of(child), spec, allow, protocol(), false, false)
                .whenComplete((res, err) -> {
                    if (err != null) {
                        made.completeExceptionally(err);
                    } else {
                        made.complete(res);
                    }
                }));
        boolean[] fed = {false};
        MockPlayerNetwork.afterTest(helper, () -> his(helper, child, before).forEach(c -> c.discard()));
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(made.isDone(), Component.literal("still making"));
            BuilderMake.Result res = made.join();
            helper.assertTrue(res.outcome() == BuilderMake.Outcome.MADE && res.calls().contains("run_command"),
                    Component.literal("two cows: " + res.outcome() + " " + res.calls() + " " + res.detail()));
            List<Cow> cows = his(helper, child, before);
            List<Cow> grown = cows.stream().filter(c -> !c.isBaby()).toList();
            if (!fed[0]) {
                helper.assertValueEqual(grown.size(), 2, Component.literal("grown real cows by him"));
                helper.assertTrue(cows.stream().noneMatch(Cow::isBaby), Component.literal("control: a calf before any wheat"));
                for (Cow c : grown) {
                    // Vanilla's way: wheat in his hand, used on the cow (Animal.mobInteract puts it in love).
                    child.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WHEAT, 1));
                    c.mobInteract(child, InteractionHand.MAIN_HAND);
                }
                fed[0] = true;
                helper.assertTrue(grown.stream().allMatch(Cow::isInLove), Component.literal("given wheat, and not in love"));
            }
            helper.assertTrue(cows.stream().anyMatch(Cow::isBaby),
                    Component.literal("two Builder cows given wheat, and no calf yet (" + cows.size() + " cows)"));
            LOGGER.info("builder farm animals GT: two Builder cows given wheat, and a calf was born ({} cows)", cows.size());
        });
    }

    /** The real cows within 48 blocks of him that were not there before his make: the two it brought, and their calf. */
    static List<Cow> his(GameTestHelper helper, ServerPlayer child, Set<UUID> before) {
        return cows(helper, child).stream().filter(c -> !before.contains(c.getUUID())).toList();
    }

    /** Every real cow within 48 blocks of him. */
    static List<Cow> cows(GameTestHelper helper, ServerPlayer child) {
        return helper.getLevel().getEntitiesOfClass(Cow.class, new AABB(child.blockPosition()).inflate(48));
    }

    private static volatile McpProtocol protocol;

    private static McpProtocol protocol() {
        if (protocol == null) {
            synchronized (BuilderFarmAnimalsGameTest.class) {
                if (protocol == null) {
                    protocol = new McpProtocol(WishAgent.buildTools(), new McpSessions());
                }
            }
        }
        return protocol;
    }
}
