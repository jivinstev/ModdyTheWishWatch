package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.WatchPanel;
import com.moddingfromamod.marshal.WatchRow;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.store.ArtifactTimes;
import com.moddingfromamod.testing.MockPlayerNetwork;

import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: the watch is newest first.</b> Page one starts with the thing he made (or changed)
 * last, an edit moves a thing to the front, and the library's starters come after all of his own
 * things — driven through the real {@link WatchSnapshot#forPlayer} and {@link WatchMenu#planFor},
 * the two places the order is decided, rather than through the rule ({@code NewestFirstTest}).
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class WatchNewestFirstGameTest {

    private WatchNewestFirstGameTest() {
    }

    private static Species species(String id, String owner) {
        return Species.fromJson(JsonParser.parseString("{\"id\":\"" + id + "\",\"name\":\"" + id
                + "\",\"body\":\"quadruped\",\"texture\":\"cow\",\"health\":20}").getAsJsonObject())
                .withOwner(owner);
    }

    /** Its own batch: it mints a mock player, and extra mocks move where "the child" is looked for. */
    @GameTest(template = "empty_test", batch = "watchNewestFirst")
    public static void pageOneStartsWithHisNewestThingAndTheStartersComeLast(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        String me = p.getStringUUID();
        // A day ahead of now, so nothing a reused gate world already holds can be newer.
        long ahead = System.currentTimeMillis() + 86_400_000L;
        String older = "watch1352_older";
        String newer = "watch1352_newer";
        try {
            SpeciesRegistry.withSpeciesForTest(List.of(species(older, me), species(newer, me)), () -> {
                SpeciesRegistry.timesForTest(older, new ArtifactTimes.Times(ahead, ahead));
                SpeciesRegistry.timesForTest(newer, new ArtifactTimes.Times(ahead + 1_000,
                        ahead + 1_000));

                helper.assertTrue(firstCreation(p).equals(newer),
                        Component.literal("his newest creature is not first on the watch: " + firstCreation(p)));
                WatchPanel.Cell first = WatchMenu.planFor(p, 0).at(0);
                helper.assertTrue(first != null && first.family() == WatchPanel.Family.CREATION
                                && newer.equals(first.arg()),
                        Component.literal("page one does not start with his newest thing: " + first));

                // "Give the older one wings": an edit is an update, and moves it to the front.
                SpeciesRegistry.timesForTest(older, new ArtifactTimes.Times(ahead, ahead + 2_000));
                helper.assertTrue(firstCreation(p).equals(older),
                        Component.literal("an edited creature did not move to the front: " + firstCreation(p)));

                // The starters come after ALL of his own things, across every page.
                Set<String> starters = new HashSet<>();
                for (var preset : com.moddingfromamod.library.WishLibrary.asPresets()) {
                    starters.add(preset.wishText());
                }
                helper.assertTrue(!starters.isEmpty(),
                        Component.literal("no library starters in this world — the 'starters last' half proves nothing"));
                List<WatchPanel.Cell> flow = new ArrayList<>();
                int pages = WatchMenu.planFor(p, 0).pageCount();
                for (int page = 0; page < pages; page++) {
                    WatchMenu.planFor(p, page).cells().stream()
                            .filter(c -> c.family() != WatchPanel.Family.CONTROL)
                            .sorted(java.util.Comparator.comparingInt(WatchPanel.Cell::slot))
                            .forEach(flow::add);
                }
                int lastOwn = -1;
                int firstStarter = Integer.MAX_VALUE;
                for (int i = 0; i < flow.size(); i++) {
                    WatchPanel.Cell c = flow.get(i);
                    boolean starter = c.family() == WatchPanel.Family.PRESET
                            && starters.contains(c.arg());
                    if (starter) {
                        firstStarter = Math.min(firstStarter, i);
                    } else {
                        lastOwn = i;
                    }
                }
                helper.assertTrue(firstStarter != Integer.MAX_VALUE,
                        Component.literal("no starter reached the panel at all"));
                helper.assertTrue(lastOwn < firstStarter, Component.literal("a starter (at " + firstStarter
                        + ") sits before one of his own things (at " + lastOwn + ")"));
            });
        } finally {
            SpeciesRegistry.timesForTest(older, ArtifactTimes.Times.NONE);
            SpeciesRegistry.timesForTest(newer, ArtifactTimes.Times.NONE);
            MockPlayerNetwork.remove(p);
        }
        helper.succeed();
    }

    private static String firstCreation(ServerPlayer p) {
        List<String> rows = WatchSnapshot.forPlayer(p).creations();
        return rows.isEmpty() ? "" : WatchRow.Creation.decode(rows.get(0)).id();
    }
}
