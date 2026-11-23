package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildRegistry;
import com.moddingfromamod.builder.BuildShape;
import com.moddingfromamod.marshal.WorldCatalogue;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * Gate B for <b>the world reaching the agent at all</b> (items 2 and 3).
 *
 * <p>{@code WorldCatalogueTest} covers how the list reads. This covers the two things a unit test
 * cannot see and that were the whole defect: that the catalogue is built from the <b>stores</b>
 * rather than from the ladder's already-narrowed candidate list, and that it is actually
 * <b>attached</b> to what a wish carries.
 *
 * <p>Driven through {@link WishService#withCatalogue} and {@link ArtifactIndex#everything()} — the
 * two methods production calls — rather than through a re-implementation. Before this change the
 * agent was handed the ladder's PICK on the one branch where the ladder had an opinion, and nothing
 * at all on the other three; every piece of that was a wiring fact, invisible to Gate A.
 *
 * <p>Its own batch: {@link BuildRegistry} is process-wide, and while the assertions below only ever
 * ask about this test's own uniquely-named build, the catalogue it renders is the whole registry.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class WishCatalogueGameTest {

    /**
     * <b>The narrowing that motivated this, measured rather than argued.</b>
     *
     * <p>{@code ArtifactIndex.forPlayer} drops anything past {@code CONSIDER_RADIUS} — 512 blocks,
     * <i>"not one of the things he might mean; it is somewhere else entirely"</i>. Correct for the
     * ladder, which is choosing between things. Wrong for a note whose job is to let the agent tell
     * a refinement from a brand-new wish: a castle he built yesterday and walked away from is
     * exactly the thing "make the castle taller" means, and it is exactly the thing 512 blocks
     * removes.
     */
    @GameTest(template = "empty_test", batch = "wishCatalogue")
    public static void theAgentIsHandedThingsThatAreNowhereNearHim(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        String id = "gt_cat_far_" + helper.getLevel().getGameTime();
        BuildRegistry.put(server, new BuildPlan(id, id, List.of(
                new BuildShape(BuildShape.Kind.BOX, 0, 0, 0, 2, 2, 2, "minecraft:stone", false))));
        BlockPos far = new BlockPos(5000, 64, 5000);
        BuildRegistry.rememberAnchor(id, far.asLong());

        double fromOrigin = Math.sqrt(far.distSqr(BlockPos.ZERO));
        helper.assertTrue(fromOrigin > ArtifactIndex.CONSIDER_RADIUS,
                Component.literal("this fixture is pointless unless the build really is outside the ladder's reach: "
                        + Math.round(fromOrigin) + " blocks vs a radius of "
                        + ArtifactIndex.CONSIDER_RADIUS));

        List<WorldCatalogue.Entry> world = ArtifactIndex.everything();
        helper.assertTrue(world.stream().anyMatch(e -> id.equals(e.id())),
                Component.literal("everything() must list '" + id + "'; it saw " + world.size() + " thing(s)"));

        String note = WishService.withCatalogue("");
        helper.assertTrue(note.contains(id),
                Component.literal("the note a wish carries must name '" + id + "' — this is the wiring, and it is "
                        + "what was missing. Note was:\n" + note));
        helper.succeed();
    }

    /**
     * <b>Every wish, not just the ones the ladder had an opinion about.</b>
     *
     * <p>Three of the (since deleted) {@code WishResolver.resolve}'s four exits returned {@code ""}: a wish that did not
     * look contextual, a contextual one that did not resolve, and a question. On all three the
     * agent woke up knowing nothing about what the child owns and had to spend turns on {@code
     * list_*} calls to find out — turns being the unit exist to remove. item 2 is
     * "always hand the agent the candidates, <b>whether or not the ladder had an opinion</b>", and
     * the empty-note case is precisely where "whether or not" bites.
     */
    @GameTest(template = "empty_test", batch = "wishCatalogue")
    public static void theWorldRidesAlongEvenWhenTheLocalSideHadNothingToSay(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        String id = "gt_cat_any_" + helper.getLevel().getGameTime();
        BuildRegistry.put(server, new BuildPlan(id, id, List.of(
                new BuildShape(BuildShape.Kind.BOX, 0, 0, 0, 2, 2, 2, "minecraft:stone", false))));

        String fromNothing = WishService.withCatalogue("");
        helper.assertTrue(fromNothing.contains(id),
                Component.literal("a wish with no local note at all still has to carry the world:\n" + fromNothing));

        // And it ADDS rather than replaces: the local note says why the cache was skipped, and a catalogue that overwrote it would undo an earlier issue's first half to deliver its
        // second.
        String prior = WishService.CONTEXTUAL_NOTE;
        String both = WishService.withCatalogue(prior);
        helper.assertTrue(both.startsWith(prior),
                Component.literal("the local note must survive, first, unmodified:\n" + both));
        helper.assertTrue(both.contains(id), Component.literal("and the world must be appended to it:\n" + both));
        helper.succeed();
    }
}
