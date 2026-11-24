package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildRegistry;
import com.moddingfromamod.builder.BuildShape;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>{@code wishvisit <id>} — "take me to my castle" as a button</b>.
 *
 * <p>A wish granted with {@code take_me_to} could never be taught: a template is a list of
 * commands and there was no command able to carry it out, so it was told "I can't save it". This
 * drives the replay command through the real dispatcher, at the executor's permission, as the
 * button would: it must FIND the build by id (here, an anchor that moved after the button would
 * have been taught) and put him beside it, and an id nothing has must move him nowhere.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class WishVisitGameTest {

    private static final String BATCH = "wishvisit";

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 200)
    public static void theButtonFindsTheBuildWhereItIsNow(GameTestHelper helper) {
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "mfam-visit");
        var server = helper.getLevel().getServer();
        String id = "gt_visit_castle_" + Long.toHexString(System.nanoTime());
        BuildRegistry.putInMemoryForProbe(new BuildPlan(id, "Visit Castle", List.of(
                new BuildShape(BuildShape.Kind.BOX, 0, 0, 0, 1, 1, 1, "minecraft:stone", false))));
        BlockPos start = child.blockPosition();
        // Taught when the castle stood HERE; then it was rebuilt 40 blocks east. The button must
        // follow the build, never the remembered spot.
        BuildRegistry.rememberAnchor(id, start.offset(10, 0, 0).asLong(), helper.getLevel().dimension());
        BuildRegistry.rememberAnchor(id, start.offset(40, 0, 0).asLong(), helper.getLevel().dimension());
        helper.assertTrue(CommandGuard.isAllowed("wishvisit " + id), Component.literal("a template may hold wishvisit"));

        var src = child.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER).withSuppressedOutput();
        server.getCommands().performPrefixedCommand(src, "wishvisit " + id);
        double dx = child.getX() - (start.getX() + 40.5);
        double dz = child.getZ() - (start.getZ() + 0.5);
        helper.assertTrue(Math.abs(dx) < 1.0 && Math.abs(dz) < 1.0,
                Component.literal("wishvisit must take him to where the build stands NOW (+40 x); he is at "
                        + child.blockPosition().toShortString() + " from " + start.toShortString()));

        BlockPos there = child.blockPosition();
        server.getCommands().performPrefixedCommand(src, "wishvisit no_such_build_anywhere");
        helper.assertTrue(child.blockPosition().equals(there),
                Component.literal("an id nothing has must move him nowhere"));
        // Gone at once, not at the sweep: a mock left standing 40 blocks off keeps its chunks
        // ticking, and a later batch that waits on chunk generation (IslandReachGameTest) timed
        // out behind it 3 runs in 5 with this test present and 0 in 4 without it.
        MockPlayerNetwork.remove(child);
        helper.succeed();
    }
}
