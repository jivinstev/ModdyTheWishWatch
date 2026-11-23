package com.moddingfromamod.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.datapack.VanillaCarrier;
import com.moddingfromamod.vanilla.AtomLibrary;
import com.moddingfromamod.vanilla.ItemPackService;
import com.moddingfromamod.vanilla.RigCarrier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>Does the atom rig's library ever reach a client with no mod?</b> — Phase 1's wiring, at Gate A.
 *
 * <p>{@code StockClientIconTest} is the model for this and its lesson is the whole reason this file
 * exists: for the life of the vanilla-lite route the pack WRITER worked and nothing on any shipped
 * server path called it, so a stock child got a leather horse armour for every wished thing while
 * every gate stayed green — because every gate asked about the mod's own copy. The atom rig hangs
 * its entire rendering on a library in that same pack, so "the writer can emit it" and "the pack a
 * server publishes contains it" have to be asked separately.
 *
 * <p>What this cannot answer is how it LOOKS. That is Phase 1's Gate C run, which has not happened.
 */
class AtomPackWiringTest {

    private static JsonObject read(Path p) throws Exception {
        return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /**
     * The one that would have caught if it had existed then: build the pack the SHIPPED server
     * path publishes and look inside it, rather than asking the writer what it would have written.
     */
    @Test
    @DisplayName("the pack a server actually publishes carries the cube, its sprite and its binding")
    void theLibraryIsInThePackAStockClientIsPushed(@TempDir Path tmp) throws Exception {
        Path root = ItemPackService.buildPack(tmp.resolve("pack"));

        JsonObject def = read(root.resolve(VanillaItemPack.itemDefinitionPath(WishPackWriter.BASE_ITEM)));
        var entries = def.getAsJsonObject("model").getAsJsonArray("entries");

        for (AtomLibrary.Entry e : AtomLibrary.all()) {
            String model = null;
            for (int i = 0; i < entries.size(); i++) {
                JsonObject o = entries.get(i).getAsJsonObject();
                if (o.get("threshold").getAsInt() == e.customModelData()) {
                    model = o.getAsJsonObject("model").get("model").getAsString();
                }
            }
            assertEquals(VanillaItemPack.extraModelId(e.modelName()), model,
                    "the published pack has no range_dispatch entry at " + e.customModelData()
                            + ", so a client with NO MOD draws every box of every atom-rig creature "
                            + "as a leather horse armour — and it is the only copy that client can "
                            + "ever read");

            Path m = root.resolve(VanillaItemPack.extraModelPath(e.modelName()));
            assertTrue(Files.isRegularFile(m), "the entry points at " + m + ", which is not in "
                    + "the pack — a model id that does not resolve is the magenta missing cube");
            assertEquals(6, read(m).getAsJsonArray("elements").get(0).getAsJsonObject()
                    .getAsJsonObject("faces").size(), "the packed cube lost faces");
        }

        Path sprite = root.resolve(VanillaItemPack.texturePath(VanillaCarrier.ATOM_SPRITE));
        assertTrue(Files.isRegularFile(sprite),
                "the pack carries the atom cube's MODEL and not its TEXTURE, so every creature "
                        + "renders as the missing texture. The sprite is not a 'layer' of any icon, "
                        + "which is exactly how it comes to be left out of the copy loop");
        assertTrue(Files.size(sprite) > 0);
    }

    @Test
    @DisplayName("the packed cube points at the PACK's texture, never at the mod's namespace")
    void thePackedCubeIsSelfContained(@TempDir Path tmp) throws Exception {
        Path root = ItemPackService.buildPack(tmp.resolve("pack"));
        for (AtomLibrary.Entry e : AtomLibrary.all()) {
            JsonObject m = read(root.resolve(VanillaItemPack.extraModelPath(e.modelName())));
            String tex = m.getAsJsonObject("textures").get("0").getAsString();
            // The entry's OWN sprite: the two plain cubes share the white one, and each pattern
            // cube has its own. Asserted per entry rather than against ATOM_SPRITE, or eight
            // pattern cubes could all point at the white square and nothing would say so.
            assertEquals(VanillaItemPack.textureId(e.spriteName()), tex,
                    "the packed model names " + tex + " — a client with no mod has no "
                            + "moddingfromamod namespace at all, so the texture would never "
                            + "resolve. Same reason VanillaItemPack.NAMESPACE exists.");
            assertTrue(Files.isRegularFile(
                            root.resolve(VanillaItemPack.texturePath(e.spriteName()))),
                    e.modelName() + " names a sprite the pack does not carry — the model loads and "
                            + "the cube renders as the missing texture, which on this arm is every "
                            + "patterned box of every creature");
        }
    }

    @Test
    @DisplayName("the shipped copy and the packed copy are the same cube")
    void theModdedAndStockCopiesAgree(@TempDir Path tmp) throws Exception {
        Path root = ItemPackService.buildPack(tmp.resolve("pack"));
        for (AtomLibrary.Entry e : AtomLibrary.all()) {
            JsonObject packed = read(root.resolve(VanillaItemPack.extraModelPath(e.modelName())));
            JsonObject mod = read(Path.of("src/main/resources/assets/moddingfromamod/models/item")
                    .resolve(e.modelName() + ".json"));
            assertEquals(mod.getAsJsonArray("elements"), packed.getAsJsonArray("elements"),
                    e.modelName() + " differs between the copy a modded client reads and the copy "
                            + "a stock client reads — so an A/B photographed on one is not evidence "
                            + "about the other");
        }
    }

    // ── the shape of the extras mechanism ────────────────────────────────────────────────────

    @Test
    @DisplayName("an icon and an extra sharing a number is refused, not silently shadowed")
    void oneNumberSpaceIsCheckedAcrossBoth() {
        var icon = new VanillaItemPack.Icon(7, "bag", List.of("wish/bag"));
        var clash = new VanillaItemPack.Extra(7, "rig_atom", "{}");
        var spec = new VanillaItemPack.Spec(WishPackWriter.BASE_ITEM, List.of(icon), List.of(clash));
        assertThrows(IllegalArgumentException.class, () -> VanillaItemPack.textFiles(spec),
                "icons and extras share ONE number table, so checking them separately is the "
                        + "version of this that passes while a cube shadows a wish icon");
    }

    @Test
    @DisplayName("the merged range_dispatch table is still sorted ascending")
    void theMergedTableIsSorted() {
        var spec = new VanillaItemPack.Spec(WishPackWriter.BASE_ITEM,
                WishPackWriter.icons("on"), WishPackWriter.atoms("on"));
        String json = VanillaItemPack.textFiles(spec)
                .get(VanillaItemPack.itemDefinitionPath(WishPackWriter.BASE_ITEM));
        var entries = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("model")
                .getAsJsonArray("entries");
        int previous = Integer.MIN_VALUE;
        for (int i = 0; i < entries.size(); i++) {
            int cmd = entries.get(i).getAsJsonObject().get("threshold").getAsInt();
            assertTrue(cmd > previous,
                    "entry " + i + " is " + cmd + " after " + previous
                            + ". RangeSelectItemModel binary-searches at 16 entries or more, so an "
                            + "unsorted list silently renders the wrong model");
            previous = cmd;
        }
    }

    /**
     * {@code shift} is {@link WishPackWriter}'s own verify-to-fail mode — every binding one
     * higher, so nothing is missing and everything wears its neighbour's artwork. The atom cubes
     * have to move with it or the break mode leaves a working library behind, which is a control
     * that quietly passes.
     */
    @Test
    @DisplayName("the break modes reach the atom library too, or the oracle has a hole in it")
    void theBreakModeShiftsTheCubesAsWell() {
        var real = WishPackWriter.atoms("on");
        var shifted = WishPackWriter.atoms("shift");
        assertEquals(real.size(), shifted.size());
        for (int i = 0; i < real.size(); i++) {
            assertEquals(real.get(i).customModelData() + 1, shifted.get(i).customModelData());
        }
    }

    @Test
    @DisplayName("the atom sprite rides along, and only when there is a cube to draw")
    void theSpriteIsCarriedWithTheExtras() {
        var icons = WishPackWriter.icons("on");
        assertTrue(!WishPackWriter.sprites(icons).contains(VanillaCarrier.ATOM_SPRITE),
                "the icon layers alone must not contain it — the atom sprite is not a layer of any "
                        + "wish icon, which is exactly why it needs saying separately");
        assertTrue(WishPackWriter.sprites(icons, WishPackWriter.atoms("on"))
                        .contains(VanillaCarrier.ATOM_SPRITE));
        assertEquals(WishPackWriter.sprites(icons),
                WishPackWriter.sprites(icons, List.of()),
                "no extras, no extra sprite");
    }

    // ── the flag ─────────────────────────────────────────────────────────────────────────────

    /**
     * <b>The rig's carrier is the DYEABLE one.</b>
     *
     * <p>What is left of the old default-arm pin. That test asserted the
     * {@code rigatoms} flag's default and off-switch, that the carrier followed the arm, and — read
     * out of the source — that the pack writer's {@code TINTABLE} ORed the flag in. The flag, the
     * arm and {@code TINTABLE} are all deleted: the atom rig is the only rig and its carrier is a
     * constant. The one claim that survives is the one a child would see: the atom rig's only
     * colour channel is {@code dyed_color}, and vanilla registers that handler only for
     * {@code #minecraft:dyeable}, so the carrier has to be one.
     */
    @Test
    @DisplayName("the atom rig's carrier is a dyeable item, or every creature renders white")
    void theRigCarrierIsDyeable() {
        assertEquals("leather_horse_armor", RigCarrier.CARRIER,
                "the rig's carrier must be the DYEABLE one. On anything else (paper was the pack "
                        + "rig's) every tintindex is inert and every creature renders pure WHITE "
                        + "-- bright, plausible, and past any 'the frame is not dark' check");
    }

    @Test
    @DisplayName("Spec's two-argument form still means 'no extras', so every old caller is inert")
    void theOldSpecShapeCarriesNothingExtra() {
        var spec = new VanillaItemPack.Spec("leather_horse_armor", List.of());
        assertEquals(List.of(), spec.extras());
    }
}
