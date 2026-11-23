package com.moddingfromamod.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.item.IconSprites;
import com.moddingfromamod.vanilla.AtomLibrary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>Does every number a stack carries resolve to the picture it names, in the one dialect the
 * pack still writes — and is the other dialect really gone?</b>
 *
 * <p>Was {@code UnionPackBindingTest}: it resolved every number through BOTH halves of
 * D11's union — the ≤1.21.3 {@code overrides} table and the ≥1.21.4 {@code range_dispatch} — and
 * required them to agree. step 3 (2026-10-16) ended the union: the server refuses clients
 * older than 1.21.4 at join, so nobody who reads {@code overrides} is ever pushed this pack, and the
 * pack stopped writing it. This file now proves the remaining half on its own, against the numbers
 * the mod issues, and pins the absence of the old one so it cannot come back silently.
 *
 * <h2>Why "the file is there" is the wrong question, measured rather than argued</h2>
 *
 * <p>{@code docs/VERSION_STRATEGY.md} §4 photographed a stock 1.21.4 client joining this server
 * through ViaProxy: the pack was offered, downloaded, applied — a second atlas rebuild six seconds
 * after the join proves it — and every wished thing, Moddy included, drew as a leather horse
 * armour. Nothing errored. <b>Every check in the tree at that moment answered "is the resource
 * present", and the thing that had gone was "does anything point at it"</b>; 1.21.4 stopped reading
 * {@code overrides} and started reading {@code assets/&lt;ns&gt;/items/}. That is why this file
 * resolves numbers instead of counting files.
 *
 * <h2>What it resolves them WITH</h2>
 *
 * <p>{@link #rangeDispatch} is 1.21.4's own algorithm, read out of {@code RangeSelectItemModel}'s
 * bytecode: the entry with the largest {@code threshold} that is {@code <=} the value, and the
 * {@code fallback} when the value is below every threshold. A test that merely checked "there is an
 * entry whose threshold equals N" would pass over an unsorted table, which is precisely the failure
 * the ascending rule exists to stop — and which is silent, because a binary search over an unsorted
 * array returns a real, valid, wrong answer.
 */
class ItemPackBindingTest {

    private static final String CARRIER = WishPackWriter.BASE_ITEM;

    /** The real pack the server publishes, built through the shipped writer. */
    private static Map<String, String> pack() {
        return VanillaItemPack.textFiles(new VanillaItemPack.Spec(
                CARRIER,
                WishPackWriter.icons("on"), WishPackWriter.atoms("on")));
    }

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    // ── the two resolvers, each its own version's real rule ──────────────────────────────────

    /**
     * ≥1.21.4: {@code RangeSelectItemModel} — the largest threshold {@code <=} the value, else the
     * fallback. Returns null for the fallback.
     *
     * <p>⚠ It walks the entries IN FILE ORDER and returns the last match: vanilla binary-searches
     * once there are sixteen entries or more, and a binary search assumes ascending. Reproducing the assumption rather than the
     * optimisation is what lets {@link #theEntriesAreAscending} be the thing that catches an
     * unsorted table, instead of this resolver quietly coping with one.
     */
    private static String rangeDispatch(JsonObject dispatch, float value) {
        JsonArray entries = dispatch.getAsJsonArray("entries");
        String hit = null;
        for (int i = 0; i < entries.size(); i++) {
            JsonObject e = entries.get(i).getAsJsonObject();
            if (e.get("threshold").getAsFloat() <= value) {
                // A wish icon's entry is a composite; what it DRAWS is its picture layer.
                hit = IconNodes.pictureModel(e.getAsJsonObject("model"));
            }
        }
        return hit;
    }

    private static JsonObject dispatchOf(Map<String, String> files) {
        JsonObject def = json(files.get(VanillaItemPack.itemDefinitionPath(CARRIER)));
        JsonObject model = def.getAsJsonObject("model");
        assertEquals("minecraft:range_dispatch", model.get("type").getAsString(),
                "the carrier must dispatch on a NUMBER: a stack a 1.21.1 jar minted carries one "
                        + "int, which arrives in the newer component's floats list — a `select` on "
                        + "strings would be well-formed, would load, and would match nothing ever");
        return model;
    }

    /** Every number anything in the mod issues onto the carrier, with the picture it means. */
    private static Map<Integer, String> everyIssuedNumber() {
        Map<Integer, String> want = new TreeMap<>();
        for (IconSprites.Sprite s : IconSprites.bindings()) {
            want.put(s.customModelData(), VanillaItemPack.NAMESPACE + ":item/wish/" + s.name());
        }
        for (AtomLibrary.Entry e : AtomLibrary.all()) {
            want.put(e.customModelData(), VanillaItemPack.extraModelId(e.modelName()));
        }
        return want;
    }

    // ── the gate ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("every icon and every atom resolves to the picture it names")
    void everyNumberResolvesToItsPicture() {
        JsonObject dispatch = dispatchOf(pack());
        Map<Integer, String> want = everyIssuedNumber();

        assertFalse(want.isEmpty(), "nothing issues a number — this gate would pass over an empty "
                + "pack, which is the 'reads 0 and passes' failure it exists to stop");

        List<String> wrong = new ArrayList<>();
        for (Map.Entry<Integer, String> e : want.entrySet()) {
            int n = e.getKey();
            String modern = rangeDispatch(dispatch, n);
            // 1.21.4 draws the VANILLA base for a mapped wish icon (items/icon-bases.json).
            if (!IconNodes.modernFor(e.getValue()).equals(modern)) {
                wrong.add("custom_model_data " + n + " means '" + e.getValue()
                        + "' — the pack resolves '" + modern + "'");
            }
        }
        assertTrue(wrong.isEmpty(),
                wrong.size() + " of " + want.size() + " numbers do not resolve to the picture they "
                        + "name:\n  " + String.join("\n  ", wrong));
    }

    @Test
    @DisplayName("the table binds exactly the numbers the mod issues, one entry each")
    void theTableBindsExactlyWhatIsIssued() {
        JsonArray entries = dispatchOf(pack()).getAsJsonArray("entries");
        Map<Integer, String> want = everyIssuedNumber();
        List<Integer> got = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            got.add(entries.get(i).getAsJsonObject().get("threshold").getAsInt());
        }
        assertEquals(new ArrayList<>(want.keySet()), got,
                "the range_dispatch table binds a different set of numbers than IconSprites and "
                        + "AtomLibrary issue. A missing entry resolves to the one BELOW it — some "
                        + "other wish's artwork — and an extra one shadows nothing on purpose");
    }

    /**
     * <b> step 3: the pre-1.21.4 dialect is gone and stays gone.</b>
     *
     * <p>Built through the real writer onto disk, so every file the server would zip is read —
     * not just the ones {@link VanillaItemPack#textFiles} names. An {@code overrides} array is
     * read by no client the server admits, and a file at {@code models/item/<carrier>.json} would
     * REPLACE vanilla's model for nothing, so either one coming back is a regression rather than
     * harmless dead weight: it is the D11 union half that nobody can see, and so nobody would
     * notice drifting.
     */
    @Test
    @DisplayName("the pack carries no `overrides` array anywhere, and no carrier model")
    void thePackCarriesNoOverridesAnywhere(@TempDir Path tmp) throws Exception {
        Path pack = WishPackWriter.write("on", tmp.resolve("pack"));
        List<String> withOverrides = new ArrayList<>();
        int read = 0;
        try (var walk = Files.walk(pack)) {
            for (Path f : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                String name = f.getFileName().toString();
                if (!name.endsWith(".json") && !name.endsWith(".mcmeta")) {
                    continue;
                }
                read++;
                if (hasKey(JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)),
                        "overrides")) {
                    withOverrides.add(pack.relativize(f).toString());
                }
            }
        }
        assertTrue(read > 100, "read only " + read + " text files — the walk is measuring nothing");
        assertTrue(withOverrides.isEmpty(),
                "the pack carries a pre-1.21.4 `overrides` table again, in " + withOverrides
                        + ". The server refuses clients older than 1.21.4 at join "
                        + "(net/ClientVersionPayload), so nobody who reads it is ever pushed it");
        assertFalse(Files.exists(pack.resolve(
                        "assets/minecraft/models/item/" + CARRIER + ".json")),
                "the pack replaces the carrier's MODEL again; the definition's fallback names "
                        + "vanilla's own, so this file can only be the old overrides half");
        try (var walk = Files.walk(pack)) {
            assertFalse(walk.anyMatch(f -> f.toString().contains("/wish/")
                            && f.getFileName().toString().matches("[a-z0-9_]+_flat\\.(json|png)")),
                    "the pack carries a #1052 `_flat` twin, which only the overrides table bound");
        }
    }

    @Test
    @DisplayName("the no-overrides check fires on a pack that carries one")
    void theNoOverridesCheckCanFire() {
        assertTrue(hasKey(JsonParser.parseString("{\"parent\":\"x\",\"overrides\":[]}"), "overrides"));
        assertTrue(hasKey(JsonParser.parseString(
                "{\"model\":{\"entries\":[{\"model\":{\"overrides\":[]}}]}}"), "overrides"),
                "a nested key must be found too, or a table tucked inside a node passes");
        assertFalse(hasKey(JsonParser.parseString(
                VanillaItemPack.textFiles(new VanillaItemPack.Spec(CARRIER,
                        WishPackWriter.icons("on"), WishPackWriter.atoms("on")))
                        .get(VanillaItemPack.itemDefinitionPath(CARRIER))), "overrides"));
    }

    /** True when {@code key} is a member name anywhere in the tree. */
    private static boolean hasKey(JsonElement node, String key) {
        if (node.isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray()) {
                if (hasKey(child, key)) {
                    return true;
                }
            }
        } else if (node.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : node.getAsJsonObject().entrySet()) {
                if (e.getKey().equals(key) || hasKey(e.getValue(), key)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    @DisplayName("the entries are ascending, or a 38-entry binary search answers the wrong one")
    void theEntriesAreAscending() {
        JsonArray entries = dispatchOf(pack()).getAsJsonArray("entries");
        assertTrue(entries.size() >= 16,
                "this pack has " + entries.size() + " entries. Below 16, RangeSelectItemModel "
                        + "scans linearly and an unsorted table is merely wrong; at 16 and above it "
                        + "calls Arrays.binarySearch, which over an unsorted array returns a real, "
                        + "valid, wrong index. If the library ever shrinks below 16 this assertion "
                        + "is the place to record that the algorithm changed under it.");
        float previous = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < entries.size(); i++) {
            float t = entries.get(i).getAsJsonObject().get("threshold").getAsFloat();
            assertTrue(t > previous, "entry " + i + " has threshold " + t + " after " + previous);
            previous = t;
        }
    }

    @Test
    @DisplayName("a plain leather horse armour still looks like one")
    void anUnwishedCarrierFallsBackOnBothHalves() {
        Map<String, String> files = pack();
        JsonObject dispatch = dispatchOf(files);

        // 0.0f is what the property answers for a stack with NO custom_model_data — read out of
        // 1.21.4's own getter, which falls through to 0.0F when the component is absent OR the
        // floats list is too short. NOT NaN, so the NaN branch never runs for us and the fallback
        // is reached the ordinary way: 0 is below every threshold.
        assertNull(rangeDispatch(dispatch, 0.0f),
                "a stack with no custom_model_data reads 0.0 and must resolve to the fallback. An "
                        + "entry at or below 0 would claim every plain carrier in the world — "
                        + "which is why Icon and Extra both refuse one");

        JsonObject fallback = dispatch.getAsJsonObject("fallback");
        assertNotNull(fallback, "no fallback: an un-wished horse armour from a dungeon draws "
                + "nothing at all, because a pack file REPLACES vanilla's");
        assertEquals("minecraft:item/" + CARRIER, fallback.get("model").getAsString(),
                "vanilla's own model: the pack no longer ships one of its own (#1585 step 3)");
    }

    @Test
    @DisplayName("every entry carries the dye tint, or every wished creature turns grey")
    void theTintSurvivesOnEveryEntry() {
        JsonObject dispatch = dispatchOf(pack());
        JsonArray entries = dispatch.getAsJsonArray("entries");
        List<JsonObject> nodes = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            // On a wish icon his colour is carried by the backing glow, the composite's
            // first layer — the picture itself may be an undyeable vanilla item.
            nodes.add(IconNodes.tinted(entries.get(i).getAsJsonObject().getAsJsonObject("model")));
        }
        nodes.add(dispatch.getAsJsonObject("fallback"));
        for (JsonObject node : nodes) {
            JsonArray tints = node.getAsJsonArray("tints");
            assertNotNull(tints, node.get("model").getAsString() + " carries no tints. From 1.21.4 "
                    + "tinting is DATA — the Java ItemColor handler that tints leather on 1.21.1 "
                    + "does not exist — and the atom rig's ONLY colour channel is "
                    + "minecraft:dyed_color, so an untinted entry is every wished creature "
                    + "rendering grey while the shapes and the placement stay perfect");
            assertEquals(1, tints.size(),
                    "one tint, matching layer0 / tintindex 0, which is what vanilla's own "
                            + "items/leather_horse_armor.json carries");
            JsonObject t = tints.get(0).getAsJsonObject();
            assertEquals("minecraft:dye", t.get("type").getAsString());
            assertTrue(t.get("default").getAsInt() == VanillaItemPack.LEATHER_DYE_DEFAULT
                    || t.get("default").getAsInt() == VanillaItemPack.BACKING_DEFAULT, t.toString());
        }
    }

    @Test
    @DisplayName("the dispatch reads the float ViaVersion actually delivers")
    void theDispatchNamesTheMeasuredProperty() {
        JsonObject dispatch = dispatchOf(pack());
        assertEquals("minecraft:custom_model_data", dispatch.get("property").getAsString());
        assertEquals(0, dispatch.get("index").getAsInt(),
                "index 0 of the floats list. MEASURED (docs/FORWARD_COMPAT.md): a 1.21.1 "
                        + "int custom_model_data arrives there on both 1.21.4 and 26.2, and "
                        + "Minecraft's data fixer upgrades a saved one to the same place. If that "
                        + "ever moves, VanillaItemPack.dispatchProperty and DISPATCH_INDEX are the "
                        + "whole fix — nothing else in the emitter knows the shape");
    }

    @Test
    @DisplayName("one pack.mcmeta that every tested version accepts (unchanged by #1585)")
    void theMetadataSpansEveryVersionTheGateCovers() {
        JsonObject pack = json(pack().get("pack.mcmeta")).getAsJsonObject("pack");
        assertEquals(VanillaItemPack.PACK_FORMAT, pack.get("pack_format").getAsInt(),
                "1.21.1's own format, and it stays: a pack_format 34 pack IS applied by a 26.2 "
                        + "client and its items/*.json ARE read (measured)");
        JsonObject supported = pack.getAsJsonObject("supported_formats");
        assertEquals(VanillaItemPack.PACK_FORMAT, supported.get("min_inclusive").getAsInt());
        assertEquals(VanillaItemPack.SUPPORTED_FORMATS_MAX, supported.get("max_inclusive").getAsInt(),
                "capped at 26.2's own lastPreMinorVersion. Claiming higher in THIS key is "
                        + "'Pack declares support for version newer than 64, but is missing "
                        + "mandatory fields min_format and max_format' — measured against 26.2's "
                        + "codec, not guessed");
        assertEquals(VanillaItemPack.MIN_FORMAT[0], pack.getAsJsonArray("min_format").get(0).getAsInt());
        assertEquals(VanillaItemPack.MAX_FORMAT[0], pack.getAsJsonArray("max_format").get(0).getAsInt());
        // 1.21.1 = 34, 1.21.4 = 46, 26.2 = 88.0 — the three arms of tools/forward-compat-gate.sh.
        for (int format : new int[]{34, 46}) {
            assertTrue(format >= supported.get("min_inclusive").getAsInt()
                            && format <= supported.get("max_inclusive").getAsInt(),
                    "resource format " + format + " is outside supported_formats, so that client "
                            + "would be warned the pack is for another version");
        }
        assertTrue(88 <= VanillaItemPack.MAX_FORMAT[0],
                "26.2's resource format is 88.0 and max_format must reach it");
    }

    // ── the self-check: prove the gate can fire ─────────────────────────────────────────────

    @Test
    @DisplayName("the gate fires when ONE entry is missing")
    void theGateFiresWhenAnEntryIsDropped() {
        Map<String, String> files = new LinkedHashMap<>(pack());
        JsonObject def = json(files.get(VanillaItemPack.itemDefinitionPath(CARRIER)));
        JsonObject dispatch = def.getAsJsonObject("model");
        JsonArray entries = dispatch.getAsJsonArray("entries");

        // Drop MODDY, because his is the one number whose absence the §4 photograph is OF: on a
        // 1.21.4 client the watch in a child's hand was a leather horse armour, and the run that
        // produced it was green everywhere else.
        int watch = com.moddingfromamod.datapack.VanillaCarrier.WATCH_MODEL_DATA;
        String expected = null;
        JsonArray kept = new JsonArray();
        for (int i = 0; i < entries.size(); i++) {
            JsonObject e = entries.get(i).getAsJsonObject();
            if (e.get("threshold").getAsInt() == watch) {
                expected = IconNodes.pictureModel(e.getAsJsonObject("model"));
                continue;
            }
            kept.add(e);
        }
        assertNotNull(expected, "the real pack has no entry for Moddy, so this self-check is "
                + "measuring nothing");
        dispatch.add("entries", kept);

        String got = rangeDispatch(dispatch, watch);
        assertFalse(expected.equals(got),
                "dropping Moddy's entry must change what " + watch + " resolves to, or the "
                        + "resolver is not looking at the entries at all");
        // …and what it resolves to instead is the entry BELOW it — a real, valid, wrong picture,
        // which is the whole reason this is checked by resolution rather than by counting.
        assertNotNull(got, "it falls to the neighbouring entry rather than to the fallback: a "
                + "missing binding here does not show up as a missing model, it shows up as some "
                + "other wish's artwork");
    }
}
