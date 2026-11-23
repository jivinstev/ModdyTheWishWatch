package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.WatchRow;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.testing.MockPlayerNetwork;

import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: the watch's history is HIS — his creations and nobody-owned ones, never another
 * child's, and never the six retired built-ins.</b>
 *
 * <p>Driven through the real {@link WatchSnapshot#forPlayer} rather than the predicate, because the
 * defect was never the rule ({@code ArtifactOwner.visibleTo} already existed and the preset row
 * used it) — it was that this path did not call it. A rule tested where it is defined is not
 * tested where it runs.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class WatchOwnCreationsGameTest {

    private WatchOwnCreationsGameTest() {
    }

    private static Species species(String id, String name, String owner) {
        Species s = Species.fromJson(JsonParser.parseString("{\"id\":\"" + id + "\",\"name\":\"" + name
                + "\",\"body\":\"quadruped\",\"texture\":\"cow\",\"health\":20}").getAsJsonObject());
        return owner.isEmpty() ? s : s.withOwner(owner);
    }

    /** Its own batch: it mints a mock player, and extra mocks move where "the child" is looked for. */
    @GameTest(template = "empty_test", batch = "watchOwnCreations")
    public static void theWatchListsHisCreationsAndNeverAnotherChilds(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        String me = p.getStringUUID();
        String brother = "11111111-2222-3333-4444-555555555555";
        try {
            SpeciesRegistry.withSpeciesForTest(List.of(
                    species("watch911_mine", "Watch911 Mine", me),
                    species("watch911_brothers", "Watch911 Brothers", brother),
                    species("watch911_nobodys", "Watch911 Nobodys", "")), () -> {
                List<String> names = new ArrayList<>();
                List<String> ids = new ArrayList<>();
                for (String row : WatchSnapshot.forPlayer(p).creations()) {
                    WatchRow.Creation c = WatchRow.Creation.decode(row);
                    names.add(c.name());
                    ids.add(c.id());
                }
                helper.assertTrue(!names.contains("Watch911 Brothers"),
                        Component.literal("another child's creature is on this child's watch: " + names));
                for (String retired : List.of("Blorp the Giant", "Lava Beast", "MEGA CHICKEN",
                        "Guard Bot", "Pink Spider Queen", "Tiny Friend")) {
                    helper.assertTrue(!names.contains(retired),
                            Component.literal("a retired built-in is still on the watch: " + retired));
                }
                // what the LIBRARY serves is the library's — the starter treehouse and lava
                // castle were on every child's watch as if he had built them.
                //
                // asked by ID, never by NAME. A name is not an identity: StressCorpusGameTest's
                // gnd_library_treehouse_on_slope builds the treehouse's plan under its own id
                // (stress_gnd_library_treehouse_on_slope), nobody's, so it rightly shows — and it is
                // called "Your Secret Treehouse" too. Checked by name, this failed whenever that case
                // ran shortly before this test, while the stress record was still among the newest
                // MAX_CREATIONS rows of the reused GameTest world.
                for (String k : com.moddingfromamod.library.WishLibrary.keys("buildings")) {
                    if (!com.moddingfromamod.builder.BuildRegistry.ownerOf(k).isEmpty()) {
                        continue;   // a world record under that id belongs to somebody — not this rule
                    }
                    helper.assertTrue(!ids.contains(k),
                            Component.literal("the library's building '" + k + "' is on the watch as if he built it: " + ids));
                }
                // Presence is asserted only below the cap: the list is newest-first and CAPPED, and
                // a reused GameTest world can hold enough to push a fixture off it.
                if (names.size() < com.moddingfromamod.net.ModdedClient.MAX_WATCH_CREATIONS) {
                    helper.assertTrue(names.contains("Watch911 Mine"),
                            Component.literal("his own creature is missing from his watch: " + names));
                    helper.assertTrue(names.contains("Watch911 Nobodys"),
                            Component.literal("a creature nobody owns (made before shelves) is missing: " + names));
                }
            });
        } finally {
            MockPlayerNetwork.remove(p);
        }
        helper.succeed();
    }
}
