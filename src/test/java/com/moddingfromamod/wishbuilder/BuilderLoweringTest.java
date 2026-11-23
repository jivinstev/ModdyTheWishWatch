package com.moddingfromamod.wishbuilder;

import static com.moddingfromamod.wishbuilder.BuilderSpecTest.j;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.moddingfromamod.testing.GateTier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * every kind lowers to the tools the wish worker calls, starting at plan_wish and ending at
 * finish_building; argument names against the REAL tool schemas are checked in BuilderMakeGameTest.
 */
class BuilderLoweringTest {

    static List<BuilderLowering.Call> lower(String spec) {
        return BuilderLowering.lower(BuilderSpec.read(j(spec)), "mine_1", LibraryPresets.SHIPPED);
    }

    static List<String> tools(List<BuilderLowering.Call> calls) {
        List<String> out = new ArrayList<>();
        calls.forEach(c -> out.add(c.tool()));
        return out;
    }

    static final List<String> ONE_PER_KIND = BuilderExamples.ONE_PER_KIND;

    @Test
    void everyKindIsBracketedByPlanAndFinish() {
        for (String spec : ONE_PER_KIND) {
            List<String> t = tools(lower(spec));
            assertEquals("plan_wish", t.get(0), spec);
            assertEquals("finish_building", t.get(t.size() - 1), spec);
            assertTrue(t.size() >= 3, spec);
        }
    }

    @Test
    void aCreatureStartsFromItsReadyMadeAndTranslatesEveryPick() {
        var calls = lower(ONE_PER_KIND.get(0));
        assertEquals(List.of("plan_wish", "sculpt_creature", "run_command", "finish_building"), tools(calls));
        JsonObject a = calls.get(1).args();
        assertEquals("cat", a.get("start_from").getAsString());
        assertEquals("Blueberry", a.get("name").getAsString());
        assertTrue(a.has("recolour"), "the scheme became a recolour");
        assertEquals(List.of("skittish", "glowing"), strings(a.get("powers").getAsJsonArray()));
        assertEquals("wishmob spawn mine_1 1", calls.get(2).args().getAsJsonArray("command").get(0).getAsString(),
                "count 2 = the sculpt's one plus one more");
    }

    /** A dragon ride: what a dragon does by itself, then rideable. */
    @Test
    void aRideIsARideableSculpt() {
        JsonObject a = lower(ONE_PER_KIND.get(1)).get(1).args();
        assertEquals(List.of("friendly", "flies", "fireproof", "rideable"), strings(a.get("powers").getAsJsonArray()));
    }

    @Test
    void aRealMobIsSummonedInThe1218Spelling() {
        var calls = lower(ONE_PER_KIND.get(2));
        String cmd = calls.get(1).args().getAsJsonArray("command").get(0).getAsString();
        assertEquals("summon minecraft:cow ~ ~ ~ {CustomName:\"Daisy\",PersistenceRequired:1b}", cmd);
        assertEquals(2, calls.get(1).args().getAsJsonArray("command").size());
    }

    @Test
    void aMachineIsTheFillMachineItemShape() {
        assertEquals("machine", lower(ONE_PER_KIND.get(4)).get(1).args().get("shape").getAsString());
    }

    @Test
    void anEventTakesItsSizeStep() {
        assertEquals("little", lower(ONE_PER_KIND.get(8)).get(1).args().get("size").getAsString());
    }

    @Test
    void aRuleIsATriggerAndAReviewedEffect() {
        JsonObject a = lower(ONE_PER_KIND.get(9)).get(1).args();
        assertEquals("jumps", a.get("when").getAsString());
        assertEquals("player", a.get("who").getAsString());
        assertEquals(3, a.get("grow").getAsInt(), "rule:flowers is set_rule's own grow");
        assertEquals("mine_1", a.get("name").getAsString(), "the rule's id, never his words");
    }

    @Test
    void everyRuleEffectLowersToSetRulesOwnArguments() {
        for (RuleEffects.Effect e : RuleEffects.ALL) {
            for (String k : e.args().keySet()) {
                assertTrue(List.of("push", "grow", "do").contains(k), e.id() + ": " + k);
            }
        }
    }

    @Test
    void wrongPicksAreRefusedNotGuessed() {
        for (String bad : List.of(
                "{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:buildings/secret_treehouse\"}",
                "{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\",\"powers\":[\"power:a\",\"power:b\",\"power:c\"]}",
                "{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\",\"size\":9}",
                "{\"builder\":1,\"kind\":\"creature\",\"from\":\"mob:minecraft:cow\",\"powers\":[\"power:flies\"]}",
                "{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\",\"temper\":\"grumpy\"}",
                "{\"builder\":1,\"kind\":\"rule\",\"opts\":{\"when\":\"when:jumps\",\"does\":\"rule:flowers\",\"op\":\"1\"}}",
                "{\"builder\":1,\"kind\":\"rule\",\"opts\":{\"when\":\"when:jumps\",\"does\":\"rule:explode\"}}",
                "{\"builder\":1,\"kind\":\"picture\",\"from\":\"preset:pictures/nope\"}")) {
            assertThrows(IllegalArgumentException.class, () -> lower(bad), bad);
        }
    }

    @Test
    void aStrangeNameCannotBreakOutOfTheSummonTag() {
        String cmd = lower("{\"builder\":1,\"kind\":\"creature\",\"from\":\"mob:minecraft:cow\","
                + "\"name\":\"a\\\"}] run op @a\"}").get(1).args().getAsJsonArray("command").get(0).getAsString();
        assertFalse(cmd.contains("\"}]"), cmd);
        assertTrue(cmd.endsWith(",PersistenceRequired:1b}"), cmd);
    }

    static List<String> strings(com.google.gson.JsonArray a) {
        List<String> out = new ArrayList<>();
        a.forEach(e -> out.add(e.getAsString()));
        return out;
    }

    // ------------------------------------------------------------------: an edit changes the one he has

    static List<BuilderLowering.Call> edit(String spec) {
        return BuilderLowering.lower(BuilderSpec.read(j(spec)), "mine_1", LibraryPresets.SHIPPED,
                new BuilderLowering.Edit("mine_1"));
    }

    @Test
    void anEditUsesEachToolsOwnChangeFormAndSpawnsPlacesOrHandsOverNothing() {
        for (String spec : ONE_PER_KIND) {
            if (spec.contains("\"mob:") || spec.contains("\"supplies\"")) {
                continue;   // a real mob and supplies are not kept: there is nothing to change (below)
            }
            List<BuilderLowering.Call> calls = edit(spec);
            assertFalse(tools(calls).contains("run_command"), "no extra spawns: " + spec);
            assertEquals("mine_1", calls.get(0).args().get("about").getAsString(), spec);
            for (BuilderLowering.Call c : calls) {
                JsonObject a = c.args();
                switch (c.tool()) {
                    case "sculpt_creature" -> {
                        assertEquals("mine_1", a.get("id").getAsString());
                        assertFalse(a.get("spawn").getAsBoolean(), "the ones in the world change; none is added");
                        assertFalse(a.has("at"));
                    }
                    case "make_item" -> {
                        if (BuilderSnacks.composed(BuilderSpec.read(j(spec))) && c != calls.get(calls.size() - 2)) {   //: every machine is composed now
                            // a composed machine's SNACK is made again (its id is its name and power, so a
                            // change is a new snack), and one is handed over to taste. The machine keeps the rule.
                            assertTrue(a.get("as_new").getAsBoolean());
                            continue;
                        }
                        assertEquals("mine_1", a.get("start_from").getAsString());
                        assertFalse(a.has("as_new"), "a change, never a second one");
                        assertFalse(a.has("count"), "nothing more is handed over");
                    }
                    case "build_structure" -> {
                        assertEquals("mine_1", a.get("id").getAsString(), "the same id rebuilds it where it stands");
                        assertFalse(a.has("at"));
                    }
                    case "paint_surface" -> assertEquals("mine_1", a.get("start_from").getAsString());
                    case "set_rule" -> assertEquals("mine_1", a.get("name").getAsString());
                    case "stage_event" -> assertEquals("mine_1", a.get("name").getAsString());
                    default -> {
                    }
                }
            }
        }
    }

    @Test
    void aRealMobIsNotKeptSoItCannotBeEdited() {
        assertThrows(IllegalArgumentException.class, () -> edit(ONE_PER_KIND.get(2)));
    }

    @Test
    void anEditTellsPlanWishWhatTheCatalogueCallsIt() {
        var calls = BuilderLowering.lower(BuilderSpec.read(j(ONE_PER_KIND.get(3))), "mine_1", LibraryPresets.SHIPPED,
                new BuilderLowering.Edit("Zappy", "new"));
        assertEquals("new", calls.get(0).args().get("about").getAsString());
        assertEquals("Zappy", calls.get(1).args().get("start_from").getAsString());
    }

    @Test
    void anItemIsKnownByItsNameEverythingElseByItsId() {
        assertEquals("Zappy", BuilderLowering.refOf(lower(ONE_PER_KIND.get(3)), "mine_1"));
        assertEquals("mine_1", BuilderLowering.refOf(lower(ONE_PER_KIND.get(0)), "mine_1"));
    }

    // ------------------------------------------------------------------: the lowering refuses a rule that cannot be

    @Test
    void aRuleThatCannotHappenIsRefusedNamingWhy() {
        assertThrows(IllegalArgumentException.class, () -> lower(
                "{\"builder\":1,\"kind\":\"rule\",\"opts\":{\"when\":\"when:jumps\",\"does\":\"rule:cake\"}}"),
                "a drop cannot follow his jump");
        assertThrows(IllegalArgumentException.class, () -> lower(
                "{\"builder\":1,\"kind\":\"rule\",\"opts\":{\"when\":\"when:mob_spawns\",\"does\":\"rule:huge\"}}"),
                "a mob trigger needs a mob");
        assertThrows(IllegalArgumentException.class, () -> lower(
                "{\"builder\":1,\"kind\":\"rule\",\"opts\":{\"when\":\"when:mob_spawns\",\"who\":\"mob:minecraft:cow\",\"does\":\"rule:hat\"}}"),
                "a cow draws no helmet");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("#2043: every make the menus allow lowers, and a page greys exactly what a press refuses — "
            + "500 walks (3000 nightly), every kind, Back included, as a Java chest and as a Bedrock form")
    void everyMakeTheMenusAllowLowers() {
        // 500 walks in Gate A, the same seed; the nightly (-Pgate.nightly) walks the first 3000 of it, as Gate A did.
        int walks = GateTier.nightly() ? 3000 : 500;
        java.util.Random rnd = new java.util.Random(2043);
        List<String> wrong = new ArrayList<>();
        java.util.Map<String, Integer> madeByKind = new java.util.TreeMap<>();
        int greyPresses = 0;
        int maxPicks = 0;
        for (int walk = 0; walk < walks; walk++) {
            BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
            boolean form = walk % 2 == 1;          // the Bedrock form: the picks row is text, not buttons
            boolean greedy = walk % 3 == 0;         // a child who picks everything he can: the one limits are for
            String kind = "";
            for (int press = 0; press < 80 && f.step() != BuilderFlow.Step.TUNE; press++) {
                var p = f.page();
                java.util.Map<Integer, String> slots = new java.util.LinkedHashMap<>(p.slots());
                slots.values().remove(com.moddingfromamod.ui.PickPage.CLOSE_ID);
                if (form && p.multi()) {
                    for (int s = p.picksRowSlot(); s < p.picksRowSlot() + com.moddingfromamod.ui.PickPage.COLS; s++) {
                        slots.remove(s);
                    }
                }
                List<String> ids = new ArrayList<>(slots.values());
                if (ids.isEmpty()) {
                    break;
                }
                String pick = ids.get(rnd.nextInt(ids.size()));
                if (greedy && p.multi()) {
                    List<String> open = p.tiles().stream().filter(t -> !t.selected() && p.picks().counted().contains(t.id())
                            && p.blocked(t) == null).map(com.moddingfromamod.ui.PickPage.Tile::id).toList();
                    pick = !open.isEmpty() && rnd.nextInt(10) > 0 ? open.get(rnd.nextInt(open.size())) : com.moddingfromamod.ui.PickPage.NEXT_ID;
                }
                final String id = pick;
                if (f.step() == BuilderFlow.Step.KIND) {
                    kind = id;
                }
                com.moddingfromamod.ui.PickPage.Tile t = p.tiles().stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
                String grey = t == null ? null : p.blocked(t);
                boolean counted = t != null && p.multi() && p.picks().counted().contains(t.id()) && !t.selected();
                int before = p.chosen().size();
                BuilderFlow.Step at = f.step();
                String said = f.press(id).say();
                if (grey != null) {
                    greyPresses++;
                    if (!said.contains(grey) || f.page().chosen().size() != before) {
                        wrong.add(at + ": grey '" + id + "' (" + grey + ") was answered '" + said + "'");
                    }
                } else if (counted && f.step() == at && !said.isEmpty()) {
                    wrong.add(at + ": '" + id + "' was not grey but was refused: " + said);
                }
                if (f.step() == at && f.page().multi()) {
                    maxPicks = Math.max(maxPicks, f.page().chosen().size());
                }
            }
            if (f.step() != BuilderFlow.Step.TUNE) {
                continue;
            }
            BuilderSpec spec = f.spec(java.util.Map.of());
            try {
                BuilderLowering.lower(spec, "walk_" + walk, LibraryPresets.SHIPPED);
                madeByKind.merge(kind, 1, Integer::sum);
            } catch (IllegalArgumentException e) {
                wrong.add(e.getMessage() + ": " + spec.toJson());
            }
        }
        assertEquals(List.of(), wrong.stream().distinct().limit(20).toList(), "a page and a press disagree, or a make the menus allowed is refused at Make");
        for (BuilderSpec.Kind k : BuilderSpec.Kind.values()) {
            if (BuilderAllowlist.REVIEWED.kinds().contains(k) && k != BuilderSpec.Kind.PICTURE) {
                assertTrue(madeByKind.getOrDefault(k.id(), 0) >= 10, k + " was made through the menus: " + madeByKind);
            }
        }
        assertTrue(greyPresses > 20, "the walks pressed grey tiles " + greyPresses + " times");
        assertTrue(maxPicks > 3, "the walks went past the old typed limits: " + maxPicks + " picks on one page");
        // ...and the makes the old numbers refused: four powers and three moves, and four parts on a cat
        BuilderLowering.lower(BuilderSpec.read(j("{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\","
                + "\"powers\":[\"power:fireproof\",\"power:flies\",\"power:glowing\",\"power:naps\",\"ability:fire_breath\","
                + "\"ability:flower_trail\",\"ability:treats\"]}")), "cat_1", LibraryPresets.SHIPPED);
        assertThrows(IllegalArgumentException.class, () -> BuilderLowering.lower(BuilderSpec.read(j("{\"builder\":1,"
                + "\"kind\":\"creature\",\"from\":\"preset:species/cat\",\"powers\":[\"ability:fire_breath\","
                + "\"ability:frost_breath\"]}")), "cat_1", LibraryPresets.SHIPPED), "two breaths: apply_effect would keep one");
    }

    // ── step 6: "Follows you", "Stays put" and "How fast" ─────────────────────────────────────────────

    static BuilderLowering.Call call(List<BuilderLowering.Call> calls, String tool) {
        return calls.stream().filter(c -> c.tool().equals(tool)).findFirst().orElse(null);
    }

    @Test
    void followsYouIsFriendlyPlusModifyMobsFollowOnEveryCopyAfterTheSpawn() {
        List<BuilderLowering.Call> calls = lower("{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\","
                + "\"temper\":\"pet\",\"count\":3}");
        JsonObject sculpt = call(calls, "sculpt_creature").args();
        assertTrue(sculpt.get("powers").toString().contains("friendly"), sculpt.toString());
        BuilderLowering.Call follow = call(calls, "modify_mob");
        assertTrue(follow != null && follow.args().get("follow").getAsBoolean(), String.valueOf(follow));
        assertEquals(3, follow.args().get("count").getAsInt(), "every copy he has");
        List<String> order = calls.stream().map(BuilderLowering.Call::tool).toList();
        assertTrue(order.indexOf("modify_mob") > order.indexOf("run_command"), "after the copies are spawned: " + order);
    }

    @Test
    void staysPutSetsAStayAndAnEditKeepsTheCall() {
        BuilderSpec spec = BuilderSpec.read(j("{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\",\"temper\":\"stays\"}"));
        List<BuilderLowering.Call> edit = BuilderLowering.lower(spec, "cat_1", LibraryPresets.SHIPPED, new BuilderLowering.Edit("cat_1"));
        BuilderLowering.Call stay = call(edit, "modify_mob");
        assertTrue(stay != null && stay.args().get("stay").getAsInt() == BuilderLowering.WITH_YOU_STAY, String.valueOf(stay));
        assertFalse(stay.args().has("follow"));
    }

    @Test
    void anOrdinaryTemperSendsNoModifyMob() {
        assertTrue(call(lower("{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\",\"temper\":\"shy\"}"),
                "modify_mob") == null);
    }

    @Test
    void howFastIsSculptsSpeedAndItsOwnPaceSendsNone() {
        JsonObject fast = call(lower("{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\","
                + "\"opts\":{\"speed\":\"3\"}}"), "sculpt_creature").args();
        assertEquals(0.4, fast.get("speed").getAsDouble(), 1e-9);
        JsonObject slow = call(lower("{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\","
                + "\"opts\":{\"speed\":\"1\"}}"), "sculpt_creature").args();
        assertEquals(0.15, slow.get("speed").getAsDouble(), 1e-9);
        assertFalse(call(lower("{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\"}"), "sculpt_creature")
                .args().has("speed"), "its own pace: the body's speed, untouched");
    }

    @Test
    void theEditorStepsHowFastWithinTheDialog() {
        BuilderEdit.Result r = BuilderEdit.apply(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, "cat_1",
                BuilderSpec.read(j("{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\"}")),
                List.of(BuilderEdit.Op.step("speed", 1), BuilderEdit.Op.set("temper", "temper:pet")));
        assertTrue(r.ok(), r.refusal());
        assertEquals("3", r.spec().opt("speed"));
        assertEquals("pet", r.spec().temper());
    }
}
