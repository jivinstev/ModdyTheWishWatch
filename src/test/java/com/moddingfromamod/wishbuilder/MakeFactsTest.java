package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.datapack.EffectCatalogue;
import com.moddingfromamod.datapack.ItemCatalogue;
import com.moddingfromamod.ui.PickPage;
import org.junit.jupiter.api.Test;

/**
 * <b>Every make has facts, derived, never hand-rated</b> ({@link MakeFacts}). Every reviewed Builder id and every
 * catalogue effect is rated by the rules (a new catalogue prefix fails here by name until a rule reads it), every
 * ready-made on every kind's menu yields known facts, the numbers are the knobs' own values in their units and absent
 * where nothing was measured, and the tag and number vocabularies are pinned and documented.
 */
class MakeFactsTest {

    private static BuilderSpec spec(BuilderSpec.Kind kind, String from, int size, List<String> powers, String temper,
                                    int count, Map<String, String> opts) {
        return new BuilderSpec(BuilderSpec.CURRENT, kind, from, "", size, powers, temper, count, "", "", opts);
    }

    @Test
    void theVocabularyIsPinnedAndDocumented() throws Exception {
        assertEquals(List.of("fights", "hostile", "boss", "changes_blocks", "builds", "summons", "gives_items",
                "world_rule", "decorative", "harmless"), MakeFacts.TAGS,
                "a tag added, renamed or dropped is a change every world jar reads: update docs/EXTENDING.md with it");
        String doc = Files.readString(Path.of("docs/EXTENDING.md"));
        for (String t : MakeFacts.TAGS) {
            assertTrue(doc.contains("`" + t + "`"), "docs/EXTENDING.md does not describe the tag " + t);
        }
        // the numbers' names are a shipped vocabulary: additive only, never renamed or given another unit
        assertEquals(List.of("healthHalfHearts", "attackDamageHalfHearts", "movementSpeedAttribute", "scaleTimes",
                "builderSizeStep", "builderStrengthStep", "countMade", "amountItems", "radiusBlocks", "durationTicks",
                "extentBlocks", "armorPoints", "knockbackResistanceFraction"), MakeFacts.NUMBERS.subList(0, 13),
                "a shipped number was renamed, removed or reordered: add new ones at the end, never change these");
        for (String n : MakeFacts.NUMBERS) {
            assertTrue(doc.contains("`" + n + "`"), "docs/EXTENDING.md does not describe the number " + n);
        }
        for (String k : MakeFacts.KINDS) {
            assertTrue(doc.contains("`" + k + "`"), "docs/EXTENDING.md does not name the kind " + k);
        }
    }

    @Test
    void everyCataloguePrefixIsReadOrDeclaredSilent() {
        Set<String> covered = new LinkedHashSet<>(MakeFacts.READS);
        covered.addAll(MakeFacts.SAYS_NOTHING);
        assertEquals(new LinkedHashSet<>(Catalogue.PREFIXES), covered,
                "every catalogue prefix is in MakeFacts.READS or MakeFacts.SAYS_NOTHING");
    }

    @Test
    void everyReviewedEntryIsRated() {
        List<String> unrated = new ArrayList<>();
        for (String id : BuilderAllowlist.REVIEWED.ids()) {
            String why = MakeFacts.problem(id);
            if (why != null) {
                unrated.add(why);
            }
        }
        assertTrue(unrated.isEmpty(), String.join("\n", unrated));
    }

    @Test
    void everyCatalogueEffectHasTagsFromItsOwnData() {
        for (ItemCatalogue.Entry e : ItemCatalogue.load().all()) {
            assertTrue(MakeFacts.itemEffectTags(e.id()) != null, e.id());
        }
        for (EffectCatalogue.Effect e : EffectCatalogue.load().all()) {
            assertTrue(MakeFacts.abilityTags(e.id()) != null, e.id());
        }
        for (BuilderAbilities.Ability a : BuilderAbilities.ALL) {
            assertTrue(MakeFacts.abilityTags(a.id()) != null, a.id());
        }
        // what the catalogue's own fields say, checked on a few a world cares about
        assertEquals(Set.of(MakeFacts.FIGHTS), MakeFacts.itemEffectTags("hurt"));
        assertEquals(Set.of(MakeFacts.FIGHTS), MakeFacts.itemEffectTags("lightning"));
        assertEquals(Set.of(MakeFacts.CHANGES_BLOCKS), MakeFacts.itemEffectTags("place"));
        assertEquals(Set.of(MakeFacts.CHANGES_BLOCKS), MakeFacts.itemEffectTags("turn_into"));
        assertEquals(Set.of(MakeFacts.SUMMONS), MakeFacts.itemEffectTags("summon"));
        assertEquals(Set.of(MakeFacts.GIVES_ITEMS), MakeFacts.itemEffectTags("rich"));
        assertEquals(Set.of(), MakeFacts.itemEffectTags("heal"));
        assertEquals(Set.of(), MakeFacts.itemEffectTags("teleport"));
        assertEquals(Set.of(MakeFacts.FIGHTS), MakeFacts.abilityTags("fire_breath"));
        assertEquals(Set.of(MakeFacts.CHANGES_BLOCKS), MakeFacts.abilityTags("flower_trail"));
        assertEquals(Set.of(MakeFacts.GIVES_ITEMS), MakeFacts.abilityTags("treats"));
        assertEquals(Set.of(), MakeFacts.abilityTags("buff_allies"));
    }

    /** Every ready-made each kind's menu offers, pressed as a child would, with the dialog's defaults. */
    private static Map<String, BuilderSpec> readyMades() {
        Map<String, BuilderSpec> out = new LinkedHashMap<>();
        for (PickPage.Tile kt : new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED).kindTiles()) {
            BuilderSpec.Kind kind = BuilderSpec.Kind.of(kt.id());
            BuilderFlow at = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
            at.press(kind.id());
            for (String id : at.fromIds(kind)) {
                BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
                f.press(kind.id());
                f.press(id);
                out.put(kind.id() + " " + id, f.spec(Map.of()));
            }
        }
        return out;
    }

    @Test
    void everyReadyMadeOnEveryMenuYieldsKnownFacts() {
        Map<String, BuilderSpec> all = readyMades();
        assertTrue(all.size() > 30, "the walk found only " + all.size() + " ready-mades");
        List<String> bad = new ArrayList<>();
        for (var e : all.entrySet()) {
            MakeFacts f = MakeFacts.of(e.getValue());
            if (!f.known() || !f.kind().equals(e.getValue().kind().id()) || !MakeFacts.TAGS.containsAll(f.tags())) {
                bad.add(e.getKey() + " → " + f);
            }
        }
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }

    /** Every ready-made in the libraries, read from its stored file as a replay of it would read it. */
    @Test
    void everyLibraryPresetYieldsKnownFacts() {
        Map<String, String> kindOfDir = Map.of("species", "creature", "bodies", "creature", "items", "item",
                "buildings", "building", "pictures", "picture", "events", "event");
        List<String> bad = new ArrayList<>();
        int n = 0;
        for (String p : Catalogue.presets()) {
            String dir = p.substring(0, p.indexOf('/'));
            JsonObject stored = LibraryPresets.SHIPPED.read(p);
            if (stored == null) {
                bad.add(p + ": the library has no file for it");
                continue;
            }
            MakeFacts f = MakeFacts.ofArtifact(kindOfDir.get(dir), stored);
            n++;
            if (!f.known()) {
                bad.add(p + " → " + f);
            }
        }
        assertTrue(n > 40, "only " + n + " presets were read");
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }

    @Test
    void everyMadeUpItemYieldsKnownFacts() {
        for (BuilderItemPowers.Stock s : BuilderItemPowers.STOCK) {
            for (BuilderItemPowers.Power w : BuilderItemPowers.fitting(s)) {
                if (w.id().equals(BuilderTurnInto.POWER)) {
                    continue;   // needs its own two picks; rated through its effect below
                }
                MakeFacts f = MakeFacts.of(item(s.id(), w.id(), 2));
                assertTrue(f.known(), s.id() + "/" + w.id() + " → " + f);
                if (w.effect().isEmpty()) {
                    assertTrue(f.has(MakeFacts.DECORATIVE), s.id() + "/" + w.id() + " → " + f);
                } else {
                    assertFalse(f.has(MakeFacts.DECORATIVE), s.id() + "/" + w.id() + " → " + f);
                }
                assertEquals(2.0, f.number(MakeFacts.BUILDER_STRENGTH_STEP).orElse(-1), s.id() + "/" + w.id());
            }
        }
    }

    private static BuilderSpec item(String stock, String power, int strength) {
        BuilderItemPowers.Power w = BuilderItemPowers.power(power);
        Map<String, String> opts = new LinkedHashMap<>();
        opts.put("stock", stock);
        opts.put("shape", "shape:" + BuilderItemPowers.stock(stock).shape());
        if (!w.effect().isEmpty()) {
            opts.put("effect", "effect:" + w.effect());
        }
        opts.put("power", power);
        opts.put("strength", String.valueOf(strength));
        return spec(BuilderSpec.Kind.ITEM, "", 2, List.of(), "", 1, opts);
    }

    @Test
    void theNumbersAreTheKnobsOwnValuesAndOnlyGrowWithThem() {
        List<String> bad = new ArrayList<>();
        for (var e : readyMades().entrySet()) {
            BuilderSpec s = e.getValue();
            double lastScale = -1;
            for (int size = 1; size <= 4; size++) {
                MakeFacts f = MakeFacts.of(new BuilderSpec(s.version(), s.kind(), s.from(), s.scheme(), size, s.powers(),
                        s.temper(), s.count(), s.at(), s.name(), s.opts()));
                if (f.number(MakeFacts.BUILDER_SIZE_STEP).orElse(-1) != size) {
                    bad.add(e.getKey() + ": size " + size + " reads " + f.number(MakeFacts.BUILDER_SIZE_STEP));
                }
                double scale = f.number(MakeFacts.SCALE_TIMES).orElse(Double.NaN);
                if (scale < lastScale) {
                    bad.add(e.getKey() + ": size " + size + " is scale " + scale + " < " + lastScale);
                }
                lastScale = Double.isNaN(scale) ? lastScale : scale;
            }
            for (int n : new int[] {1, 2, 5, 10}) {
                MakeFacts f = MakeFacts.of(new BuilderSpec(s.version(), s.kind(), s.from(), s.scheme(), s.size(),
                        s.powers(), s.temper(), n, s.at(), s.name(), s.opts()));
                if (f.number(MakeFacts.COUNT_MADE).orElse(-1) != n) {
                    bad.add(e.getKey() + ": count " + n + " reads " + f.number(MakeFacts.COUNT_MADE));
                }
            }
        }
        for (int strength = 1; strength <= 3; strength++) {
            MakeFacts f = MakeFacts.of(item("sword", "freeze", strength));
            assertEquals(strength, f.number(MakeFacts.BUILDER_STRENGTH_STEP).orElse(-1));
            assertTrue(f.number(MakeFacts.DURATION_TICKS).isPresent(), "freeze lasts: " + f);
        }
        assertTrue(MakeFacts.of(item("sword", "freeze", 3)).number(MakeFacts.DURATION_TICKS).orElse(0)
                > MakeFacts.of(item("sword", "freeze", 1)).number(MakeFacts.DURATION_TICKS).orElse(0));
        assertTrue(bad.isEmpty(), String.join("\n", bad));
        // a stored creature's numbers are its own, in their units
        JsonObject sp = JsonParser.parseString("{\"id\":\"gt_beast\",\"name\":\"Beast\",\"powers\":[\"friendly\"],"
                + "\"health\":40,\"damage\":6,\"scale\":1.5,\"speed\":0.3}").getAsJsonObject();
        MakeFacts beast = MakeFacts.ofArtifact("creature", sp);
        assertEquals(40.0, beast.number(MakeFacts.HEALTH_HALF_HEARTS).orElse(-1));
        assertEquals(6.0, beast.number(MakeFacts.ATTACK_DAMAGE_HALF_HEARTS).orElse(-1));
        assertEquals(1.5, beast.number(MakeFacts.SCALE_TIMES).orElse(-1));
        assertEquals(0.3, beast.number(MakeFacts.MOVEMENT_SPEED_ATTRIBUTE).orElse(-1), 1e-9);
    }

    @Test
    void aNumberNothingMeasuredIsAbsentNeverZero() {
        MakeFacts picture = MakeFacts.ofArtifact("picture", new JsonObject());
        for (String n : MakeFacts.NUMBERS) {
            assertTrue(picture.number(n).isEmpty(), "a picture has no " + n);
        }
        MakeFacts boots = MakeFacts.of(item("boots", "jump", 3));
        assertTrue(boots.number(MakeFacts.HEALTH_HALF_HEARTS).isEmpty(), "an item has no health: " + boots);
        for (String k : MakeFacts.KINDS) {
            assertTrue(MakeFacts.cautious(k).numbers().isEmpty(), "nothing was measured of a cautious " + k);
        }
        // an unknown name is simply absent
        assertTrue(boots.number("powerLevel").isEmpty());
        // no rating of any kind is part of the API
        for (var m : MakeFacts.class.getMethods()) {
            String name = m.getName().toLowerCase(java.util.Locale.ROOT);
            assertFalse(name.contains("tier") || name.contains("score") || name.contains("rank") || name.contains("cost"),
                    "the core rates nothing: " + m.getName());
        }
    }

    @Test
    void factsAreStableAcrossARoundTripAndAReadAgain() {
        for (var e : readyMades().entrySet()) {
            MakeFacts a = MakeFacts.of(e.getValue());
            assertEquals(a, MakeFacts.of(e.getValue()), e.getKey());
            assertEquals(a, MakeFacts.ofStoredSpec(e.getValue().toJson()), e.getKey());
        }
    }

    @Test
    void whatAWorldWouldReadIsWhatTheMakeIs() {
        MakeFacts sword = MakeFacts.of(item("sword", "lightning", 1));
        assertEquals("item", sword.kind());
        assertTrue(sword.has(MakeFacts.FIGHTS) && !sword.has(MakeFacts.HARMLESS), sword.toString());
        MakeFacts boots = MakeFacts.of(item("boots", "jump", 3));
        assertTrue(boots.has(MakeFacts.HARMLESS), boots.toString());
        MakeFacts water = MakeFacts.of(item("wand", "place_water", 2));
        assertTrue(water.has(MakeFacts.CHANGES_BLOCKS), water.toString());
        MakeFacts picture = MakeFacts.ofArtifact("picture", new JsonObject());
        assertTrue(picture.has(MakeFacts.DECORATIVE) && picture.has(MakeFacts.HARMLESS), picture.toString());
        // a creature: friendly by default, hostile as a boss, a fighter as a guard
        String cat = "preset:species/cat";
        assertTrue(MakeFacts.of(spec(BuilderSpec.Kind.CREATURE, cat, 2, List.of(), "friendly", 1, Map.of()))
                .has(MakeFacts.HARMLESS));
        MakeFacts boss = MakeFacts.of(spec(BuilderSpec.Kind.CREATURE, cat, 2, List.of(), "boss", 1, Map.of()));
        assertTrue(boss.has(MakeFacts.HOSTILE) && boss.has(MakeFacts.BOSS) && boss.has(MakeFacts.FIGHTS), boss.toString());
        assertTrue(MakeFacts.of(spec(BuilderSpec.Kind.CREATURE, cat, 2, List.of(), "guard", 1, Map.of()))
                .has(MakeFacts.FIGHTS));
        MakeFacts breath = MakeFacts.of(spec(BuilderSpec.Kind.CREATURE, cat, 2, List.of("ability:fire_breath"),
                "friendly", 1, Map.of()));
        assertTrue(breath.has(MakeFacts.FIGHTS) && !breath.has(MakeFacts.HOSTILE), breath.toString());
        // a real animal is friendly; any other real creature is rated as the monster it may be
        assertTrue(MakeFacts.of(spec(BuilderSpec.Kind.CREATURE, "mob:minecraft:cow", 2, List.of(), "", 1, Map.of()))
                .has(MakeFacts.HARMLESS));
        assertTrue(MakeFacts.of(spec(BuilderSpec.Kind.CREATURE, "mob:minecraft:zombie", 2, List.of(), "", 1, Map.of()))
                .has(MakeFacts.HOSTILE));
        // a rule changes the world; supplies give
        assertTrue(MakeFacts.of(spec(BuilderSpec.Kind.RULE, "", 2, List.of(), "", 1, Map.of())).has(MakeFacts.WORLD_RULE));
    }

    @Test
    void aSpecStillBeingPickedIsRatedByItsPicksNotCautiously() {
        // a kind tile pressed, nothing else: the menu's draft
        for (BuilderSpec.Kind k : BuilderSpec.Kind.values()) {
            MakeFacts f = MakeFacts.of(spec(k, "", 2, List.of(), k == BuilderSpec.Kind.CREATURE
                    || k == BuilderSpec.Kind.RIDE ? "friendly" : "", 1, Map.of()));
            assertTrue(f.known(), k + " → " + f);
            assertEquals(k.id(), f.kind());
        }
    }

    @Test
    void aRecipeIsRatedByWhatItsCommandsMake() {
        JsonObject dragon = JsonParser.parseString("{\"id\":\"d\",\"name\":\"Dragon\",\"powers\":[\"boss\",\"flies\"],"
                + "\"health\":200,\"damage\":12,\"scale\":2.6}").getAsJsonObject();
        MakeFacts.Artifacts store = (kind, id) -> kind.equals("creature") && id.equals("d") ? dragon : null;
        MakeFacts f = MakeFacts.ofCommands(List.of("wishmob spawn d"), store);
        assertTrue(f.known() && f.has(MakeFacts.BOSS) && f.has(MakeFacts.HOSTILE), f.toString());
        assertEquals(200.0, f.number(MakeFacts.HEALTH_HALF_HEARTS).orElse(-1));
        assertEquals(1.0, f.number(MakeFacts.COUNT_MADE).orElse(-1));
        assertEquals(3.0, MakeFacts.ofCommands(List.of("wishmob spawn d 3"), store).number(MakeFacts.COUNT_MADE).orElse(-1));
        // gone from the store: the cautious facts of a creature
        MakeFacts gone = MakeFacts.ofCommands(List.of("wishmob spawn nobody"), store);
        assertFalse(gone.known());
        assertEquals(MakeFacts.cautious("creature"), gone);
        // nothing the core knows: cautious other
        assertEquals(MakeFacts.cautious(MakeFacts.OTHER), MakeFacts.ofCommands(List.of("say hi"), store));
        // the replay commands, each read as what it makes
        assertEquals(new MakeFacts.Ref("item", "lightning_sword_lightning", 1),
                MakeFacts.ref("wishdatapack lightning_sword_lightning give"));
        assertEquals(new MakeFacts.Ref("creature", "pet_dragon", 3), MakeFacts.ref("wishmob spawn pet_dragon 3"));
        assertEquals(new MakeFacts.Ref("creature", "pet_dragon", 1), MakeFacts.ref("wishmob spawn pet_dragon at castle:door"));
        assertEquals(new MakeFacts.Ref("event", "meteor", 1), MakeFacts.ref("moddyevent stage meteor"));
        assertEquals(new MakeFacts.Ref("building", "castle", 1), MakeFacts.ref("wishbuild castle"));
        assertEquals(new MakeFacts.Ref("picture", "cat", 1), MakeFacts.ref("wishpaint cat"));
    }

    @Test
    void aStoredEventIsRatedByWhatItLeavesAndCatches() {
        MakeFacts rain = MakeFacts.ofArtifact("event", JsonParser.parseString(
                "{\"name\":\"chicken_rain\",\"motion\":\"from_the_sky\",\"size\":\"little\",\"drop\":\"minecraft:chicken\","
                        + "\"afterwards\":\"until_undo\"}").getAsJsonObject());
        assertTrue(rain.known() && rain.has(MakeFacts.SUMMONS) && rain.has(MakeFacts.CHANGES_BLOCKS), rain.toString());
        MakeFacts safe = MakeFacts.ofArtifact("event", JsonParser.parseString(
                "{\"name\":\"t\",\"motion\":\"vortex\",\"spares\":[\"builds\",\"creatures\"]}").getAsJsonObject());
        assertTrue(safe.has(MakeFacts.HARMLESS), safe.toString());
        MakeFacts wild = MakeFacts.ofArtifact("event", JsonParser.parseString(
                "{\"name\":\"t\",\"motion\":\"vortex\",\"size\":\"gigantic\"}").getAsJsonObject());
        assertTrue(wild.has(MakeFacts.FIGHTS) && wild.has(MakeFacts.CHANGES_BLOCKS), wild.toString());
    }

    @Test
    void combinedFactsTakeTheMostOfEach() {
        MakeFacts a = new MakeFacts("machine", Map.of(MakeFacts.COUNT_MADE, 1.0),
                Set.of(MakeFacts.GIVES_ITEMS, MakeFacts.HARMLESS), true);
        MakeFacts b = new MakeFacts("item", Map.of(MakeFacts.COUNT_MADE, 3.0, MakeFacts.DURATION_TICKS, 60.0),
                Set.of(MakeFacts.FIGHTS), true);
        MakeFacts c = MakeFacts.combine(List.of(a, b));
        assertEquals("machine", c.kind());
        assertEquals(Map.of(MakeFacts.COUNT_MADE, 3.0, MakeFacts.DURATION_TICKS, 60.0), c.numbers());
        assertEquals(Set.of(MakeFacts.GIVES_ITEMS, MakeFacts.FIGHTS), c.tags());
    }
}
