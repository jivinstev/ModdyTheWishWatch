package com.moddingfromamod.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.vanilla.ItemPackService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>Does the artwork a stock child needs ever reach him?</b> — the gate exists for.
 *
 * <h2>Why the two drift tests next door could not have caught this</h2>
 *
 * <p>{@code CarrierModelDriftTest} and {@code ItemBaseModelDriftTest} are correct and they ask a
 * different question: they compare the MOD'S OWN shipped
 * {@code assets/minecraft/models/item/leather_horse_armor.json} against the mod's own
 * {@code wish_item.json}, i.e. that the two copies a MODDED client can reach agree with each
 * other. A client with no mod reads neither file. It reads whatever is inside a pack the server
 * <b>pushed</b> to it, and nothing on any shipped server path wrote one — so every
 * wished item, and Moddy himself, rendered as a leather horse armour for the only audience the
 * vanilla-lite path exists for.
 *
 * <p>That is this repo's recurring shape one more time: <i>"there is no check" and "the check is
 * not wired to this path" look identical from outside.</i> The check existed. It was pointed at
 * the other route.
 *
 * <h2>What this asks instead</h2>
 *
 * <p>It builds every pack the shipped SERVER path publishes to a client with no mod, unions their
 * {@code range_dispatch} tables for the carrier item, and requires that union to cover every
 * binding the mod ships. (Until step 3 it unioned the pre-1.21.4 {@code overrides} tables;
 * the pack no longer writes one, so it reads the item definition both sides now carry.) Pure JSON — no world, no client, no server — and it went red the day it was written:
 *
 * <pre>
 * a client with NO MOD is pushed 1 pack(s) [mfam-vanilla-rig], of which NONE carries a
 * model for leather_horse_armor, so 28 of the mod's 28 wish icons render as a plain
 * leather horse armour. Missing custom_model_data: [1, 2, ... 27, 1000] -- including
 * 1000, MODDY HIMSELF, which is the button the whole vanilla-lite path exists for
 * </pre>
 *
 * <p>It is a necessary condition and not a sufficient one. It proves the BYTES contain the
 * bindings; it cannot prove a child was offered the pack, that he accepted it, or that the result
 * looks like a wand rather than horse armour. Those are {@code ItemPackWiredTest} and Gate C
 * ({@code tools/stock-icons.sh}) respectively, per <i>"a rule tested only where it is DEFINED is
 * not tested where it RUNS"</i>.
 */
class StockClientIconTest {

    /** The mod's own copy — what a client WITH the mod resolves, and the list of what it needs. */
    private static final Path SHIPPED =
            Path.of("src/main/resources/assets/minecraft/items/leather_horse_armor.json");

    /** The carrier's item-definition path inside any pack that binds it. */
    private static final String CARRIER_MODEL =
            VanillaItemPack.itemDefinitionPath(WishPackWriter.BASE_ITEM);

    /**
     * <b>Every pack the shipped server path pushes to a client with NO MOD.</b>
     *
     * <p>One entry per service that publishes, built through that service's own writer rather than
     * a stand-in, so what is measured is what ships. The list is short on purpose and it is the
     * thing that has to grow when another pack appears — a pack nobody adds here is a pack this
     * gate cannot see, which is exactly the hole being closed.
     *
     * <p>There used to be a second entry, the per-creature RIG pack. It went with the pack rig
     *: the atom rig's cube library rides in the item pack below, and {@code RigReveal}
     * writes and pushes nothing, so the item pack is the only pack a stock client is sent.
     */
    private static Map<String, Path> packsPushedToStockClients(Path tmp) throws Exception {
        Map<String, Path> out = new LinkedHashMap<>();

        // The ITEM pack — ItemPackService.publish, once at server start. Built through the service
        // rather than through WishPackWriter directly, so what is measured is the pack the PUSH
        // actually names, not a second call that happens to agree with it today.
        Path items = tmp.resolve(ItemPackService.PACK_DIR);
        ItemPackService.buildPack(items);
        out.put(ItemPackService.PACK_DIR, items);

        return out;
    }

    // ── the gate ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("every icon a stock child needs is in a pack the server actually publishes")
    void everyIconReachesAClientWithNoMod(@TempDir Path tmp) throws Exception {
        Map<String, Path> packs = packsPushedToStockClients(tmp);
        Map<Integer, String> shipped = entriesOf(read(SHIPPED));
        Map<Integer, String> pushed = new TreeMap<>();
        List<String> carriedBy = new ArrayList<>();
        for (Map.Entry<String, Path> e : packs.entrySet()) {
            Path model = e.getValue().resolve(CARRIER_MODEL);
            if (Files.exists(model)) {
                carriedBy.add(e.getKey());
                pushed.putAll(entriesOf(read(model)));
            }
        }

        List<Integer> missing = new ArrayList<>();
        for (int cmd : shipped.keySet()) {
            if (!pushed.containsKey(cmd)) {
                missing.add(cmd);
            }
        }
        assertTrue(missing.isEmpty(),
                "a client with NO MOD is pushed " + packs.size() + " pack(s) " + packs.keySet()
                        + ", of which " + (carriedBy.isEmpty() ? "NONE" : carriedBy.toString())
                        + " carries a definition for " + WishPackWriter.BASE_ITEM + ", so "
                        + missing.size() + " of the mod's " + shipped.size()
                        + " wish icons render as a plain leather horse armour. Missing "
                        + "custom_model_data: " + missing
                        + (missing.contains(com.moddingfromamod.datapack.VanillaCarrier
                                .WATCH_MODEL_DATA)
                                ? " -- including " + com.moddingfromamod.datapack.VanillaCarrier
                                        .WATCH_MODEL_DATA + ", MODDY HIMSELF, which is the button "
                                        + "the whole vanilla-lite path exists for"
                                : ""));
    }

    @Test
    @DisplayName("every model a pushed entry draws is in the pushed pack (or is vanilla's own)")
    void everyPushedOverrideResolvesInsideItsOwnPack(@TempDir Path tmp) throws Exception {
        // An entry naming a model nobody ships is the SAME symptom as no entry at all, and it is
        // the one a union of two packs can produce that neither could alone. Checked inside each
        // pack rather than across them: a pack is applied on its own. Every model in the node is
        // checked — backing, picture and overlays — because any one missing draws the magenta cube.
        for (Map.Entry<String, Path> e : packsPushedToStockClients(tmp).entrySet()) {
            Path model = e.getValue().resolve(CARRIER_MODEL);
            if (!Files.exists(model)) {
                continue;
            }
            JsonArray entries = read(model).getAsJsonObject("model").getAsJsonArray("entries");
            for (int i = 0; i < entries.size(); i++) {
                JsonObject entry = entries.get(i).getAsJsonObject();
                List<String> ids = new ArrayList<>();
                modelIds(entry.getAsJsonObject("model"), ids);
                for (String id : ids) {
                    int colon = id.indexOf(':');
                    String ns = colon < 0 ? "minecraft" : id.substring(0, colon);
                    if ("minecraft".equals(ns)) {
                        continue;   // a vanilla base (items/icon-bases.json): the client has it
                    }
                    String path = id.substring(colon + 1);
                    assertTrue(Files.exists(e.getValue().resolve(
                                    "assets/" + ns + "/models/" + path + ".json")),
                            "pack '" + e.getKey() + "' binds custom_model_data "
                                    + entry.get("threshold").getAsInt() + " to " + id
                                    + ", which is not in that pack — the client resolves a missing "
                                    + "model to the magenta cube, which is bright and passes every "
                                    + "check that only looks for a dark frame");
                }
            }
        }
    }

    @Test
    @DisplayName("a plain leather horse armour from a dungeon still looks like one")
    void theCarriersOwnLayersSurviveInThePushedPack(@TempDir Path tmp) throws Exception {
        for (Map.Entry<String, Path> e : packsPushedToStockClients(tmp).entrySet()) {
            Path def = e.getValue().resolve(CARRIER_MODEL);
            if (!Files.exists(def)) {
                continue;
            }
            JsonObject fallback = read(def).getAsJsonObject("model").getAsJsonObject("fallback");
            assertTrue(fallback != null && fallback.has("model"),
                    "pack '" + e.getKey() + "' replaces " + WishPackWriter.BASE_ITEM
                            + "'s definition without a fallback. A pack definition REPLACES the "
                            + "vanilla one, so an un-wished horse armour a child already owns would "
                            + "render as nothing at all");
            assertEquals("minecraft:item/" + WishPackWriter.BASE_ITEM,
                    fallback.get("model").getAsString(),
                    "the fallback must be vanilla's own model: the pack ships no "
                            + "models/item/" + WishPackWriter.BASE_ITEM + ".json since #1585 step 3");
            assertFalse(Files.exists(e.getValue().resolve(
                            "assets/minecraft/models/item/" + WishPackWriter.BASE_ITEM + ".json")),
                    "pack '" + e.getKey() + "' replaces the carrier's MODEL again — that file was "
                            + "the pre-1.21.4 overrides table, and no admitted client reads it");
        }
    }

    // ── the self-check: prove the gate can fire ─────────────────────────────────────────────

    @Test
    @DisplayName("the gate fires on a pack that dropped its bindings")
    void theGateFiresWhenThePackIsMissingIcons(@TempDir Path tmp) throws Exception {
        // WishPackWriter's own break mode, reused rather than a hand-mangled file: `drop` emits
        // the carrier's definition with no entries at all — which draws exactly what a client
        // without the item pack draws for a stack carrying a number.
        Path dropped = WishPackWriter.write("drop", tmp.resolve("dropped"));
        Map<Integer, String> got = entriesOf(read(dropped.resolve(CARRIER_MODEL)));
        assertTrue(got.isEmpty(), "the `drop` fixture should carry no entries");

        Map<Integer, String> shipped = entriesOf(read(SHIPPED));
        assertFalse(shipped.isEmpty(), "the mod ships no carrier bindings at all");
        // i.e. the comparison the gate above makes would report every one of them missing.
        for (int cmd : shipped.keySet()) {
            assertFalse(got.containsKey(cmd), "icon " + cmd + " should be absent from `drop`");
        }
    }

    // ── plumbing ────────────────────────────────────────────────────────────────────────────

    private static JsonObject read(Path p) throws Exception {
        return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /** {@code threshold -> picture model id}, from an item definition's {@code range_dispatch}. */
    private static Map<Integer, String> entriesOf(JsonObject definition) {
        Map<Integer, String> out = new TreeMap<>();
        JsonArray entries = definition.getAsJsonObject("model").getAsJsonArray("entries");
        if (entries == null) {
            return out;
        }
        for (int i = 0; i < entries.size(); i++) {
            JsonObject e = entries.get(i).getAsJsonObject();
            out.put(e.get("threshold").getAsInt(), IconNodes.pictureModel(e.getAsJsonObject("model")));
        }
        return out;
    }

    /** Every {@code minecraft:model} id anywhere in an item-model node. */
    private static void modelIds(com.google.gson.JsonElement node, List<String> out) {
        if (node.isJsonArray()) {
            for (var child : node.getAsJsonArray()) {
                modelIds(child, out);
            }
        } else if (node.isJsonObject()) {
            JsonObject o = node.getAsJsonObject();
            if (o.has("type") && "minecraft:model".equals(o.get("type").getAsString())) {
                out.add(o.get("model").getAsString());
            }
            for (var e : o.entrySet()) {
                if (e.getValue().isJsonObject() || e.getValue().isJsonArray()) {
                    modelIds(e.getValue(), out);
                }
            }
        }
    }

    /** Guard against the gate quietly measuring nothing. */
    @Test
    @DisplayName("the shipped carrier model is really there and really has icons")
    void theSubjectIsNotEmpty() throws Exception {
        assertTrue(Files.exists(SHIPPED), "no shipped carrier model — run tools/gen-carrier-model.py");
        // The wish icons, plus Moddy, plus the atom rig's constant cube library. DERIVED rather
        // than typed: the previous literal 28 had to be edited by hand every time the library grew,
        // and a number somebody has to remember to bump is a number that eventually gets bumped to
        // whatever the run happened to produce — which is exactly the "reads 0 and passes" failure
        // this test exists to stop, wearing the other hat.
        // +5: Moddy, the wish anvil's wand, the Wish Bag, the menus' back arrow and the idea bulb.
        int want = com.moddingfromamod.item.WishIcons.all().size() + 5
                + com.moddingfromamod.vanilla.AtomLibrary.all().size();
        assertEquals(want, entriesOf(read(SHIPPED)).size(),
                want + " = " + com.moddingfromamod.item.WishIcons.all().size() + " wish icons + "
                        + "Moddy + the anvil's wand + the Wish Bag + the back arrow + the idea bulb + "
                        + com.moddingfromamod.vanilla.AtomLibrary.all().size()
                        + " atom cube(s). A gate that reads 0 and passes is the failure this line "
                        + "exists to stop");
    }
}
