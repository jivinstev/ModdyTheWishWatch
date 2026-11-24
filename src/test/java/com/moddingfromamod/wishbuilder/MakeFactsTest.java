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
 * ready-made on every kind's menu yields known facts, tiers only grow with their knobs, and the tag vocabulary is
 * pinned and documented.
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
                    assertTrue(f.has(MakeFacts.DECORATIVE) && f.tier() == 0, s.id() + "/" + w.id() + " → " + f);
                } else {
                    assertTrue(f.tier() >= 1, s.id() + "/" + w.id() + " → " + f);
                }
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
    void tiersNeverFallAsTheKnobsGrow() {
        List<String> bad = new ArrayList<>();
        for (var e : readyMades().entrySet()) {
            BuilderSpec s = e.getValue();
            int last = -1;
            for (int size = 1; size <= 4; size++) {
                int t = MakeFacts.of(new BuilderSpec(s.version(), s.kind(), s.from(), s.scheme(), size, s.powers(),
                        s.temper(), s.count(), s.at(), s.name(), s.opts())).tier();
                if (t < last) {
                    bad.add(e.getKey() + ": size " + size + " is tier " + t + " < " + last);
                }
                last = t;
            }
            if (s.kind() == BuilderSpec.Kind.CREATURE || s.kind() == BuilderSpec.Kind.RIDE) {
                last = -1;
                for (int n : new int[] {1, 2, 5, 10, 20}) {
                    int t = MakeFacts.of(new BuilderSpec(s.version(), s.kind(), s.from(), s.scheme(), s.size(), s.powers(),
                            s.temper(), n, s.at(), s.name(), s.opts())).tier();
                    if (t < last) {
                        bad.add(e.getKey() + ": count " + n + " is tier " + t + " < " + last);
                    }
                    last = t;
                }
            }
        }
        for (BuilderItemPowers.Stock st : BuilderItemPowers.STOCK) {
            for (BuilderItemPowers.Power w : BuilderItemPowers.fitting(st)) {
                if (w.id().equals(BuilderTurnInto.POWER)) {
                    continue;
                }
                int last = -1;
                for (int strength = 1; strength <= 3; strength++) {
                    int t = MakeFacts.of(item(st.id(), w.id(), strength)).tier();
                    if (t < last) {
                        bad.add(st.id() + "/" + w.id() + ": strength " + strength + " is tier " + t + " < " + last);
                    }
                    last = t;
                }
            }
        }
        assertTrue(bad.isEmpty(), String.join("\n", bad));
        // and a stored creature: more health, damage or size never lowers it
        int last = -1;
        for (int step = 0; step < 8; step++) {
            JsonObject sp = JsonParser.parseString("{\"id\":\"gt_beast\",\"name\":\"Beast\",\"powers\":[\"friendly\"]}")
                    .getAsJsonObject();
            sp.addProperty("health", 10 + step * 30);
            sp.addProperty("damage", 1 + step * 4);
            sp.addProperty("scale", 0.5 + step * 0.4);
            int t = MakeFacts.ofArtifact("creature", sp).tier();
            assertTrue(t >= last, "step " + step + ": tier " + t + " < " + last);
            last = t;
        }
        assertTrue(last > 1, "a huge strong creature still rates " + last);
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
        assertEquals(0, picture.tier());
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
        assertTrue(MakeFacts.of(spec(BuilderSpec.Kind.RULE, "", 2, List.of(), "", 1, Map.of())).tier() >= 3);
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
        assertEquals(MakeFacts.TOP_TIER, f.tier());
        // gone from the store: the cautious facts of a creature
        MakeFacts gone = MakeFacts.ofCommands(List.of("wishmob spawn nobody"), store);
        assertFalse(gone.known());
        assertEquals(MakeFacts.cautious("creature"), gone);
        // nothing the core knows: cautious other
        assertEquals(MakeFacts.cautious(MakeFacts.OTHER), MakeFacts.ofCommands(List.of("say hi"), store));
        // the replay commands, each read as what it makes
        assertEquals(new MakeFacts.Ref("item", "lightning_sword_lightning", 1),
                MakeFacts.ref("wishdatapack lightning_sword_lightning give"));
        assertEquals(new MakeFacts.Ref("creature", "pet_dragon", 4), MakeFacts.ref("wishmob spawn pet_dragon 3"));
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
        assertTrue(wild.tier() > safe.tier(), wild + " vs " + safe);
    }

    @Test
    void combinedFactsTakeTheMostOfEach() {
        MakeFacts a = new MakeFacts("machine", 2, Set.of(MakeFacts.GIVES_ITEMS, MakeFacts.HARMLESS), true);
        MakeFacts b = new MakeFacts("item", 3, Set.of(MakeFacts.FIGHTS), true);
        MakeFacts c = MakeFacts.combine(List.of(a, b));
        assertEquals("machine", c.kind());
        assertEquals(3, c.tier());
        assertEquals(Set.of(MakeFacts.GIVES_ITEMS, MakeFacts.FIGHTS), c.tags());
    }
}
