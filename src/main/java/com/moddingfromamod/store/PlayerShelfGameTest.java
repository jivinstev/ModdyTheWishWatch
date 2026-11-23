package com.moddingfromamod.store;

import net.minecraft.network.chat.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildRegistry;
import com.moddingfromamod.item.ItemSpecStore;
import com.moddingfromamod.item.WishItemCatalogue;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.runtime.ModelRepository;
import com.moddingfromamod.runtime.RuntimeConfig;
import com.moddingfromamod.runtime.WorldPaths;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.wish.LearnedTemplates;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Gate B for shelves — a child's things on his shelf, where the rule RUNS.</b>
 *
 * <p>{@code PlayerShelfTest} proves the split and the merge on a temp folder. This proves the six
 * real stores are wired to it: a wish made by a bound child lands in {@code <players>/<uuid>/},
 * not in the world, for every store the shelf covers (creatures and their models, item
 * designs and icons, the handed-over items, learned buttons, buildings) — and a shelf written by
 * ANOTHER server is picked up when that child joins this one, which is D6 end to end.
 *
 * <p>The binding is {@code PerChildForkGameTest}'s: every ownership rule reads
 * {@code AgentContext.currentPlayerId()}, which is {@code ""} for an unbound caller, and a fixture
 * that forgot to bind would pass on a dead implementation — every record would simply stay in the
 * world. So every positive assertion here is paired with the world file NOT holding the record.
 *
 * <p>Its own batch; every id is unique per run, because the stores are process-wide and a test
 * run's shelf root ({@code <world>/moddy-players}, {@code RuntimeConfig.playersDir}) outlives a
 * run just as the world does. Nothing is cleared.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class PlayerShelfGameTest {

    private static final String BATCH = "playershelf";

    private static String startWishing(UUID child, UUID wishId) {
        Marshal m = Marshal.global();
        m.submit(wishId, child, "t9.1b shelf fixture");
        String worker = "gt-shelf-" + wishId;
        m.assign(worker, wishId);
        return worker;
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 200)
    public static void aChildsThingsGoOnHisShelfNotTheWorld(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        UUID child = UUID.randomUUID();
        UUID wish = UUID.randomUUID();
        String tag = Long.toString(System.nanoTime(), 36);
        String creature = "shelf_beast_" + tag;
        String building = "shelf_tower_" + tag;
        String recipe = "shelf_recipe_" + tag;
        String design = "shelf_wand_" + tag;
        String worker = startWishing(child, wish);
        try {
            // A sculpt writes its model BEFORE the record naming its owner exists — so the files
            // land in the world folder, and the put is what must carry them onto his shelf.
            Path early = ModelRepository.dir(creature);
            Files.createDirectories(early);
            Files.writeString(early.resolve("geometry.json"), "{\"format_version\":\"1.12.0\"}",
                    StandardCharsets.UTF_8);

            AgentContext.bind(worker);
            try {
                com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, Species.fromJson(JsonParser.parseString(
                        "{\"id\":\"" + creature + "\",\"name\":\"Shelf Beast\",\"body\":\"humanoid\","
                                + "\"texture\":\"zombie\",\"scale\":1.0,\"health\":20,"
                                + "\"speed\":0.25,\"damage\":2,\"modelKey\":\"" + creature + "\"}")
                        .getAsJsonObject()));
                BuildRegistry.put(server, BuildPlan.fromJson(JsonParser.parseString(
                        "{\"id\":\"" + building + "\",\"name\":\"Shelf Tower\",\"shapes\":[{\"kind\":"
                                + "\"box\",\"width\":3,\"height\":3,\"depth\":3,"
                                + "\"block\":\"minecraft:stone\"}]}").getAsJsonObject()));
                LearnedTemplates.addAndSave(server, JsonParser.parseString(
                        "{\"id\":\"" + recipe + "\",\"all\":[\"" + recipe + "\"],"
                                + "\"commands\":[\"say shelf\"],\"say\":\"Shelf!\"}")
                        .getAsJsonObject());
                ItemSpecStore.put(spec(design));
                WishItemCatalogue.remember(server, named(Items.STICK, "Shelf Stick " + tag));
            } finally {
                AgentContext.unbind();
            }

            Path shelf = RuntimeConfig.playersDir().resolve(child.toString());
            Path world = WorldPaths.wishesDir();
            String evidence = "\n    shelf=" + shelf.toAbsolutePath() + "\n    world=" + world;
            for (String[] row : new String[][] {
                    {"species.json", creature}, {"buildings.json", building},
                    {"learned.json", recipe}, {"item-specs.json", design},
                    {"item-icons.json", design}, {"items.json", "Shelf Stick " + tag}}) {
                String mine = read(shelf.resolve(row[0]));
                String shared = read(world.resolve(row[0]));
                helper.assertTrue(mine.contains(row[1]),
                        Component.literal(row[0] + " on his shelf holds '" + row[1] + "'" + evidence
                                + "\n    shelf bytes=" + clip(mine)));
                helper.assertFalse(shared.contains(row[1]),
                        Component.literal(row[0] + " in the WORLD must not hold his '" + row[1] + "' — a record in both"
                                + " places would come back as two" + evidence));
            }
            helper.assertTrue(Files.isRegularFile(
                            shelf.resolve("models").resolve(creature).resolve("geometry.json")),
                    Component.literal("his creature's model moved onto his shelf when its record was saved"));
            helper.assertFalse(Files.exists(WorldPaths.modelDir().resolve(creature)),
                    Component.literal("and is no longer in the world's model folder"));
            helper.assertTrue(ModelRepository.has(creature),
                    Component.literal("and the repository still finds it to draw"));
        } catch (Exception e) {
            helper.fail(Component.literal("could not stage the fixture: " + e));
            return;
        } finally {
            Marshal.global().finish(wish);
        }
        helper.succeed();
    }

    /**
     * <b>: which model made it rides on his shelf</b>, through the publish call the sculpt tool
     * really makes — whose spec is parts and names nobody, so the owner has to come from the wish
     * being granted, exactly as {@code SpeciesRegistry} decides it.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 200)
    public static void aGrantsModelStampGoesOnTheChildsShelf(GameTestHelper helper) {
        UUID child = UUID.randomUUID();
        UUID wish = UUID.randomUUID();
        String creature = "stamped_beast_" + Long.toString(System.nanoTime(), 36);
        String worker = startWishing(child, wish);
        try {
            AgentContext.bind(worker);
            try {
                com.moddingfromamod.provenance.ProvenancePublisher.publish("species", creature,
                        "sculpt.json", "{\"parts\":[]}", "make me a beast", "fixture");
            } finally {
                AgentContext.unbind();
            }
            java.util.List<com.moddingfromamod.provenance.ModelStamp> grants =
                    com.moddingfromamod.provenance.ShelfProvenance.read(RuntimeConfig.playersDir(),
                            child.toString(), "species", creature);
            helper.assertTrue(grants.size() == 1, Component.literal("one grant on his shelf's provenance.json, got "
                    + grants.size() + " under " + RuntimeConfig.playersDir().resolve(child.toString())));
        } finally {
            Marshal.global().finish(wish);
        }
        helper.succeed();
    }

    /**
     * ⭐ <b>D6, end to end.</b> Another server (Creative) writes this child's shelf behind this one's
     * back; he then joins here (Survival). The join must reload what changed, so the creature he
     * made there is summonable here, and still his.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 200)
    public static void whatHeMadeOnTheOtherServerIsHereWhenHeJoins(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        UUID child = UUID.randomUUID();
        String creature = "creative_dragon_" + Long.toString(System.nanoTime(), 36);
        Path shelf = RuntimeConfig.playersDir().resolve(child.toString());
        try {
            Files.createDirectories(shelf);
            Files.writeString(shelf.resolve("species.json"),
                    "{\"store\":\"species\",\"v\":1,\"species\":[{\"id\":\"" + creature + "\","
                            + "\"name\":\"Creative Dragon\",\"body\":\"humanoid\","
                            + "\"texture\":\"zombie\",\"scale\":1.0,\"health\":20,\"speed\":0.25,"
                            + "\"damage\":2,\"ownerId\":\"" + child + "\"}]}",
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            helper.fail(Component.literal("could not stage the other server's shelf: " + e));
            return;
        }
        helper.assertTrue(SpeciesRegistry.get(creature) == null,
                Component.literal("precondition: this server has not seen it yet"));

        var reloaded = PlayerShelves.refreshFor(server, child.toString());

        helper.assertTrue(reloaded.contains("species"),
                Component.literal("a shelf changed behind this server's back must be reloaded on join: " + reloaded));
        Species got = SpeciesRegistry.get(creature);
        helper.assertTrue(got != null, Component.literal("the creature he made on the other server is here"));
        helper.assertTrue(got != null && child.toString().equals(got.ownerId()),
                Component.literal("and it is still his: " + (got == null ? null : got.ownerId())));
        helper.assertTrue(PlayerShelves.refreshFor(server, child.toString()).isEmpty(),
                Component.literal("a second join with nothing new reloads nothing"));
        helper.succeed();
    }

    private static ItemSpecStore.Spec spec(String id) {
        return new ItemSpecStore.Spec(id, "Shelf Wand", "stick", "#ff00ff", "wand", 1,
                java.util.List.of("sharpness"), new com.google.gson.JsonObject(),
                new com.google.gson.JsonArray(), new com.google.gson.JsonObject(),
                new com.google.gson.JsonArray(), "minecraft:stick");
    }

    private static ItemStack named(net.minecraft.world.item.Item item, String name) {
        ItemStack s = new ItemStack(item);
        s.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal(name));
        return s;
    }

    private static String read(Path p) {
        try {
            return p != null && Files.isRegularFile(p) ? Files.readString(p) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String clip(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
