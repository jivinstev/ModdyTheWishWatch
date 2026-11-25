package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Every trap the GameTests measured must reach the AGENT, not just the corpus.</b>
 *
 * <p>These were all found by fixing test fixtures, and fixing a fixture teaches nobody: the agent
 * builds machines at wish time from the tool schema and {@code lessons/machine.md}, and neither is
 * updated by making a corpus case green. The gap was real and had a cost — the tool schema
 * documented a copycat's disguise as {@code {Material:{Name:...}}}, which is the form measured to
 * apply cleanly and then be <b>silently reverted</b>. An agent following the instruction it was
 * given produced a blank block and no check it could run would have said why.
 *
 * <p>So each rule is pinned here against the text the agent actually receives. The failure these
 * guard against is not "the rule was wrong" but "the rule was learned and never passed on", which
 * leaves the codebase knowing something its agent does not.
 */
class AgentKnowsTheTrapsTest {

    @Test
    void itIsToldToReadWhatHeWasLookingAtForAVagueFrom() {
        // §3: "turn these blocks into gold" while he faces andesite must be FROM andesite, read from the brief's
        // wish-time view, not a guess at stone.
        String item = WishLessons.text("item").replace("**", "").replaceAll("\\s+", " ");
        assertTrue(item.contains("\"This\", \"these blocks\", \"the ground\": read IN VIEW WHEN HE WISHED first")
                        && item.contains("FROM is the block at his crosshair")
                        && item.contains("`around: true`"),
                "lessons/item.md no longer says a vague FROM is what he was looking at when he wished");
    }

    @Test
    void itIsToldATickSelectorNeedsATag() {
        // an @e with no tag and no position walks every entity in the world each time it
        // runs; the tag index answers only tagged ones. The lesson is what makes new packs cheap.
        String dp = WishLessons.text("datapack").replace("**", "").replaceAll("\\s+", " ");
        assertTrue(dp.contains("On a clock or a tick, every `@e` needs a TAG (or a `distance=`).")
                        && dp.contains("tag it when you start (`tag @s add <pack>_on`)"),
                "lessons/datapack.md no longer says a tick selector needs a tag, or how to tag what you track");
    }

    @Test
    void itIsToldARealMobTakesTheSameTemperament() {
        // an army of real endermen "to fight for me" was granted as friendly followers and
        // the agent said, rightly then, that a real mob could not be made to fight. It can now, with
        // the same words a sculpted creature takes, and a staged crowd arrives with them.
        String creature = WishLessons.text("creature").replace("**", "").replaceAll("\\s+", " ");
        assertTrue(creature.contains("A REAL mob takes the same `base`, `powers` and `abilities` through `modify_mob`")
                        && creature.contains("`base: \"guardian\"`")
                        && creature.contains("`traits`")
                        && creature.contains("`@event:` handle"),
                "lessons/creature.md no longer says a real mob takes base/powers/abilities, or a crowd's traits");
    }

    @Test
    void itIsToldHowACreatureLivesIsBehavesLike() throws IOException {
        // a wished dragon on a bat carrier flitted about like a bat and flew away from him; its
        // life is now its archetype's, chosen by naming the vanilla mob it lives like.
        String creature = WishLessons.text("creature").replace("**", "").replaceAll("\\s+", " ");
        assertTrue(creature.contains("`behaves_like`, the vanilla mob it lives like")
                        && creature.contains("A dragon is `behaves_like: \"dragon\"` on any body")
                        && creature.contains("Name the mob whose LIFE it has, not the one it looks like"),
                "lessons/creature.md no longer says how a creature's life is chosen (behaves_like)");
        assertTrue(creature.contains("A VEHICLE is not a creature, and its life is `behaves_like: \"vehicle\"`")
                        && creature.contains("A living thing he rides (a dragon, a horse, a giant eagle) is NOT a vehicle"),
                "lessons/creature.md no longer says a vehicle lives a vehicle's life (parked, rigid)");
        String sculpt = Files.readString(Path.of("src/main/java/com/moddingfromamod/species/BehavesLike.java"));
        assertTrue(sculpt.contains("a dragon lives like 'dragon' on any body"),
                "behaves_like's schema no longer says a dragon lives like a dragon on any body");
    }

    @Test
    void itIsToldWhichTriggerIsTheMomentTheWishNames() {
        // chunk 3: "when it lands" and "when it walks" got a slam or an aura, which fire while it
        // stands still too; on_land and on_step are the moments, and his jump now makes a mount jump.
        String creature = WishLessons.text("creature").replace("**", "").replaceAll("\\s+", " ");
        assertTrue(creature.contains("WHEN it does it")
                        && creature.contains("`on_land`")
                        && creature.contains("`on_step`")
                        && creature.contains("his jump already makes a ridden creature jump"),
                "lessons/creature.md no longer says which trigger is which moment");
    }

    @Test
    void itIsToldWhatARangedMoveThrowsAndThatAShakingLandingNeedsNoCommand() {
        // "throws fireballs" threw snowballs (the move only knew one ball), and two T-rex grants
        // replaced on_land's jolt with a COMMAND of their own, so no monster moved when it landed.
        String creature = WishLessons.text("creature").replace("**", "").replaceAll("\\s+", " ");
        assertTrue(creature.contains("`\"throws\": \"minecraft:fire_charge\"`")
                        && creature.contains("\"The ground shakes when it lands\" is `on_land` with NO `COMMAND`"),
                "lessons/creature.md no longer says what `throws` is, or that a shaking landing has no COMMAND");
    }

    @Test
    void itIsToldThatAnEffectNeverHealsTheCreatureItself() {
        // routing arm: "heals itself when hurt" was on_hit + regeneration on @s, 8 of 8 on both
        // models — which heals the monsters it fights. The sculpt ability `heal` is the wish.
        String creature = WishLessons.text("creature").replace("**", "").replaceAll("\\s+", " ");
        assertTrue(creature.contains("never on the creature itself")
                        && creature.contains("`abilities: [\"heal\"]`"),
                "lessons/creature.md no longer says an effect cannot heal the creature itself");
    }

    @Test
    void itIsToldTheBurnThatSetsThingsAlight() throws IOException {
        // item 2: the catalogue taught "make it BURN them -> damage @s 4 minecraft:on_fire",
        // which hurts and lights nothing; both fire-breath grants used it and nothing burned.
        // BurnCommandGameTest pins which command really does it.
        String cat;
        try (var in = AgentKnowsTheTrapsTest.class.getResourceAsStream("/effects/catalogue.json")) {
            cat = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertTrue(cat.contains("data modify entity @s Fire set value 100s"),
                "effects/catalogue.json no longer teaches the command that sets a mob alight");
        assertFalse(cat.contains("BURN them\\\" -> damage"),
                "effects/catalogue.json teaches damage on_fire for BURN again; it lights nothing");
    }

    @Test
    void itIsToldEveryCreatureNeedsAVoice() {
        // chunk 4: every wished creature was silent — the carrier is muted and nothing replaced
        // it — and the agents who did give one a sound built it from a periodic playsound, which
        // plays on a clock rather than when it is hit.
        String creature = WishLessons.text("creature").replace("**", "").replaceAll("\\s+", " ");
        assertTrue(creature.contains("HOW it sounds: `voice`")
                        && creature.contains("makes NO sound at all")
                        && creature.contains("`voice: \"silent\"`")
                        && creature.contains("`entity.wolf.growl`, never `entity.wolf_growl`"),
                "lessons/creature.md no longer tells the agent to give a creature a voice");
    }

    @Test
    void itIsToldWhoItsEffectsLandOn() {
        // chunk 2: a breath burned the child, a bite meant for the monsters poisoned him, a
        // knock-back threw him 33 blocks — every time the agent had tried to say who and had no
        // word for it. The word is TARGET, and the default leaves players out.
        String creature = WishLessons.text("creature").replace("**", "").replaceAll("\\s+", " ");
        assertTrue(creature.contains("WHO it hurts: say it with `TARGET`")
                        && creature.contains("`TARGET: players`")
                        && creature.contains("`TARGET: monsters`")
                        && creature.contains("everything EXCEPT players"),
                "lessons/creature.md no longer tells the agent how to say who an effect lands on");
    }

    @Test
    @DisplayName("2026-10-10: a creature sculpted from nothing gets a skin (pattern) on its big parts")
    void aSculptGetsASkin() {
        String creature = WishLessons.text("creature").replace("**", "").replaceAll("\\s+", " ");
        assertTrue(creature.contains("Give it a SKIN")
                        && creature.contains("\"pattern\": \"fur\"")
                        && creature.contains("pattern_colour"),
                "the creature lesson must teach `pattern` on the body and head");
    }

    @Test
    @DisplayName("#1659: a near species changes a starter's proportions with reshape, not a re-sent box")
    void aStarterIsReshapedNotReBoxed() {
        String creature = WishLessons.text("creature").replace("**", "").replaceAll("\\s+", " ");
        assertTrue(creature.contains("To change a base part's SIZE, `reshape` it")
                        && creature.contains("\"reshape\": [{\"part\": \"body\""),
                "the creature lesson must teach `reshape` for a start_from edit (Spark re-boxed 37 of 62)");
    }

    @Test
    @DisplayName("2026-10-10: the shape comes from what the thing is, not the verb ('shoots')")
    void theShapeComesFromTheThingNotTheVerb() throws Exception {
        String item = WishLessons.text("item").replace("**", "").replaceAll("\\s+", " ");
        assertTrue(item.contains("The shape comes from what the thing IS, not from the verb he used")
                        && item.contains("Anything he swings is `on_hit` even when he says it shoots"),
                "the item lesson must say a swung thing is on_hit whatever verb he used");
        String schema = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/MakeItemTool.java"));
        assertTrue(schema.contains("The shape comes from what the thing "),
                "make_item's on_hit description must carry the same rule");
    }

    @Test
    @DisplayName("#1693 item 3: the noun decides a wish's kind, and a made-up name is a creature")
    void theNounDecidesTheKind() throws Exception {
        // A made-up creature that did something to him when touched was built as a magic block on
        // every Opus run: what it DID sounded like an item effect.
        String schema = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/PlanWishTool.java"));
        assertTrue(schema.contains("THE NOUN DECIDES THE KIND, NOT WHAT IT DOES")
                        && schema.contains("A name he MADE UP is a "),
                "plan_wish's kind must say the noun decides it and a made-up name is a creature");
    }

    @Test
    @DisplayName("2026-10-11: a creature he rides gets the rideable power in its sculpt")
    void aRiddenCreatureIsRideableInItsDesign() throws Exception {
        String creature = WishLessons.text("creature").replace("**", "").replaceAll("\\s+", " ");
        assertTrue(creature.contains("gets `rideable` in its `powers` when you sculpt it"),
                "the creature lesson must put riding into the sculpt's powers");
        String schema = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/SculptCreatureTool.java"));
        assertTrue(schema.contains("'rideable' = he climbs on"),
                "sculpt_creature's powers must offer rideable");
        String modify = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/ModifyMobTool.java"));
        assertTrue(modify.contains("Never on a BOSS"),
                "modify_mob's rideable must say a boss cannot be a mount (the summoned ender dragon, 2026-10-11)");
        assertTrue(creature.contains("One that also has `flies` flies with him"),
                "the creature lesson must say a ridden flier flies with him (rides fly since 2026-10-11)");
    }

    @Test
    @DisplayName("2026-10-10: \"to meny bloop\" means fewer, never more")
    void tooManyMeansFewer() {
        String creature = WishLessons.text("creature").replace("**", "").replaceAll("\\s+", " ");
        assertTrue(creature.contains("\"TOO MANY\" means FEWER")
                        && creature.contains("asks you to take some AWAY, never to add more")
                        && creature.contains("to meny"),
                "the creature lesson must say 'too many' (however spelled) means remove some");
    }

    @Test
    @DisplayName("#1351: a timer rule that gives items is rarely what he means — offer a button")
    void aTimerRuleThatGivesIsOfferedAsAButton() {
        String rules = WishLessons.text("datapack").replace("**", "").replace("`", "")
                .replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
        assertTrue(rules.contains("a timer rule that gives items")
                        && rules.contains("rarely what a child means")
                        && rules.contains("offer a button instead") && rules.contains("reuse_action: give"),
                "the rules lesson must say a timer rule that gives items is rarely what a child means, "
                        + "and to offer a button (reuse_action: give) instead");
        String item = WishLessons.text("item").replace("**", "").replaceAll("\\s+", " ")
                .toLowerCase(java.util.Locale.ROOT);
        assertTrue(item.contains("is not a hand-over")
                        && item.contains("then the floor at his feet, never the wish bag"),
                "the item lesson must say an item's own later gives never go in the Wish Bag");
    }

    @Test
    @DisplayName("#1397: a wish that goes DOWN is built at negative dy, below the spot — never a tower")
    void goingDownIsTaughtAsNegativeDy() throws IOException {
        String building = WishLessons.text("building").replace("**", "").replaceAll("\\s+", " ");
        assertTrue(building.contains("DOWN means below the spot: a negative `dy`")
                        && building.contains("the dug part goes at negative `dy`, below the spot")
                        && building.contains("underground, a mine or a shaft, a basement"),
                "lessons/building.md must say that down / underground / a shaft / a basement means "
                        + "negative dy below the spot (#1397 — 'a mine shaft going down' was built as a tower)");
        assertTrue(building.contains("A hollow shape does not dig"),
                "lessons/building.md must say an underground room needs an air shape to clear it");
    }

    @Test
    @DisplayName("#1947: the building lesson says a hollow box's bottom face is a floor, and how doors behave")
    void theBuildingLessonTeachesAWayInHeCanWalk() {
        String building = WishLessons.text("building").replace("**", "").replaceAll("\\s+", " ");
        assertTrue(building.contains("two blocks tall from the floor he stands on"),
                "lessons/building.md must say a doorway is measured from the floor he stands on");
        assertTrue(building.contains("a hollow: true box has a bottom face") || building.contains("box has a bottom face"),
                "lessons/building.md must name the hollow box's bottom face as the second floor");
        assertTrue(building.contains("Never `iron_door`"), "lessons/building.md must say iron doors are not opened by hand");
        assertTrue(building.contains("WALK-THROUGH"), "lessons/building.md must name the WALK-THROUGH note");
    }

    @Test
    @DisplayName("#1374: a ladder goes AGAINST a wall, faced away from it — in the lesson and in STACKED")
    void aLadderIsTaughtAgainstAWall() throws IOException {
        String building = WishLessons.text("building").replace("**", "").replaceAll("\\s+", " ");
        assertTrue(building.contains("put the ladder against the wall of the hole")
                        && building.contains("minecraft:ladder[facing=")
                        && building.contains("pointing AWAY from that wall"),
                "lessons/building.md must say where a ladder goes: against the wall of the hole, "
                        + "faced away from it (#1374) — 'put a ladder in it' builds one with no wall");
        assertTrue(building.contains("Never climb on scaffolding"),
                "lessons/building.md must say scaffolding/sand is not a ladder (it falls)");
        assertTrue(building.contains("So centring never fixes the HEIGHT")
                        && building.contains("goes at `dy:-22`"),
                "lessons/building.md must say a shape inside a shape is offset vertically too: the "
                        + "Underwater Dome's air sphere shared its glass sphere's dy and it flooded (#1374)");
        assertFalse(building.contains("put a `minecraft:ladder` in it"),
                "the old instruction (a ladder in the middle of a hole) is back in the lesson");
        String tool = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/BuildStructureTool.java"))
                .replaceAll("\"\\s*\\+\\s*\"", "");
        assertTrue(tool.contains("ladder AGAINST THE WALL OF THE HOLE, minecraft:ladder[facing=…] "
                        + "pointing away from that wall"),
                "build_structure's STACKED note must say where the ladder goes (#1374)");
        assertTrue(tool.contains("minecraft:ladder[facing=south]"),
                "build_structure's shape schema must show that a block can carry its state");
    }

    @Test
    @DisplayName("the agent knows a hand-over lands in the hotbar, then the Wish Bag in slot 2")
    void theAgentKnowsWhereAHandOverLands() throws IOException {
        String item = WishLessons.text("item").replace("**", "").replaceAll("\\s+", " ")
                .toLowerCase(java.util.Locale.ROOT);
        assertTrue(item.contains("wish bag") && item.contains("hotbar slot 2")
                        && item.contains("never his main inventory") && item.contains("check your inventory"),
                "the item lesson must say where a hand-over lands (hotbar, then the Wish Bag in slot 2) "
                        + "and that the agent must not send him to his inventory");
        String make = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/MakeItemTool.java"));
        String handOver = make.substring(make.indexOf("private static String handOverLine"));
        handOver = handOver.substring(0, handOver.indexOf("\n    }"));
        assertTrue(handOver.contains("WISH BAG") && handOver.contains("hotbar slot 2"),
                "make_item's hand-over line must say the item may land in his Wish Bag, slot 2");
        String give = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/GiveItemsTool.java"));
        String desc = give.substring(give.indexOf("public String description()"),
                give.indexOf("public JsonObject inputSchema()"));
        assertTrue(desc.contains("WISH BAG") && desc.contains("never his main"),
                "give_items' description must say where things land");
    }

    @Test
    @DisplayName("#1342: name only what you PLACED — described after the photo, in finish_wish's say")
    void theAgentDescribesWhatThePhotoShowsNotThePlan() throws IOException {
        // A garage announced with a spiral track and a T-Rex whose photo had neither; "3 MASSIVE
        // loops" that were pillars; a carwash with brushes standing underwater. Every one was
        // written in finish_building's say, before the photo existed. The rule has to reach the
        // agent in each place it decides what to tell him.
        String norm = WishLessons.text("building").replace("**", "").replaceAll("\\s+", " ");
        assertTrue(norm.contains("Name only what you PLACED") && norm.contains("not what you planned")
                        && norm.contains("look at the photograph first") && norm.contains("underwater"),
                "the building lesson must say: name only what you placed, from the photograph");
        String prompt = com.moddingfromamod.worker.WorkerPrompt.text().replaceAll("\\s+", " ");
        assertTrue(prompt.contains("NAME ONLY WHAT YOU PLACED AND CAN SEE")
                        && prompt.contains("call finish_wish with `say` — what the PHOTO shows")
                        && prompt.contains("Do not describe it yet — you have not seen it"),
                "the brief must move the description to finish_wish's say, after the photo");
        assertFalse(prompt.contains("in finish_building's say"),
                "the brief still tells the agent to describe the thing in finish_building's say");
        // The schema is Java string concatenation; join the pieces so a re-wrap cannot hide the rule.
        String wish = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/FinishWishTool.java"));
        String wishSchema = wish.substring(wish.indexOf("public JsonObject inputSchema()"))
                .replaceAll("\"\\s*\\+\\s*\"", "");
        assertTrue(wishSchema.contains("Schemas.prop(s, \"say\"")
                        && wishSchema.contains("NAME ONLY WHAT YOU PLACED AND CAN SEE IN THE PHOTO"),
                "finish_wish must offer `say` and say: name only what you placed and can see in the photo");
        String building = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/FinishBuildingTool.java"));
        String buildingSchema = building.substring(building.indexOf("public JsonObject inputSchema()"),
                building.indexOf("Schemas.prop(s, \"subject\"")).replaceAll("\"\\s*\\+\\s*\"", "");
        assertTrue(buildingSchema.contains("LEAVE THIS OUT") && buildingSchema.contains("finish_wish's `say`")
                        && buildingSchema.contains("naming only what you PLACED"),
                "finish_building's say must send the description to finish_wish");
        assertFalse(buildingSchema.contains("say it all here"),
                "finish_building's say still invites the whole description before the photo");
    }

    @Test
    @DisplayName("#572: a mob granted with modify_mob is taught as `wishsummon`, where the choice is MADE")
    void aModifiedRealMobIsTaughtAsATypedReplayNotSummonNbt() throws IOException {
        // Steve's Great Big Shark: the agent granted it with modify_mob, then translated that into a
        // summon whose attributes Minecraft ignores. The replay command only helps if the agent is
        // told about it in the two places it decides how to teach: the add_template SUMMARY (read
        // before the call) and the creature lesson (where the reasoning lives).
        String source = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/AddTemplateTool.java"));
        String summary = source.substring(source.indexOf("public String description()"),
                source.indexOf("public JsonObject inputSchema()"));
        assertTrue(summary.contains("wishsummon") && summary.contains("modify_mob"),
                "add_template's description never says how to teach a modify_mob grant, so the agent "
                        + "translates it into summon NBT it has never run — #572, exactly");
        String creature = WishLessons.text("creature");
        assertTrue(creature.contains("wishsummon") && creature.contains("same JSON"),
                "the creature lesson must teach `wishsummon <mob> <the same JSON>` for a modified real mob");
    }

    @Test
    @DisplayName("#1344: the agent is told a check asks about THIS wish's things by identity")
    void aCheckAsksAboutThisWishsOwnThings() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/FinishBuildingTool.java"));
        assertTrue(source.contains("WHEREVER they are") && source.contains("named by its wish key")
                        && source.contains("an older wish's, never counts")
                        && source.contains("ClaimCheck.MAX_RADIUS"),
                "finish_building's claims schema must say creature checks count this wish's own "
                        + "creatures anywhere, items count what THIS wish handed over by key (bag "
                        + "included), and block_present scans the radius asked for (#1344)");
        String world = Files.readString(Path.of("src/main/java/com/moddingfromamod/wish/ClaimWorld.java"));
        assertTrue(!world.contains("BLOCK_RADIUS_CAP") && !world.contains("Math.min(c.radius()"),
                "no silent block-radius cap may come back: a radius the agent asked for and the "
                        + "parser accepted is the radius scanned (#1344)");
        String item = WishLessons.text("item");
        assertTrue(item.contains("\"what\":\"boomerang_plain\"") && item.contains("Wish Bag")
                        && item.contains("an older wish's, does not"),
                "the item lesson must teach item_in_inventory by wish key, bag included, this wish only");
        String creature = WishLessons.text("creature");
        assertTrue(creature.contains("own creatures wherever they are"),
                "the creature lesson must say a creature check counts this wish's own creatures anywhere");
    }

    @Test
    @DisplayName("#941/#1261: the agent is told 'works' needs a server-run `check`, and the child sees no checklist")
    void aTickNeedsACheckTheServerRuns() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/FinishBuildingTool.java"));
        assertTrue(source.contains("creature_count") && source.contains("ClaimCheck.kinds()"),
                "finish_building's claims schema must teach the `check` vocabulary");
        String creature = WishLessons.text("creature");
        assertTrue(creature.contains("\"kind\":\"follows\"") && creature.contains("recorded as \"tried\""),
                "the creature lesson must teach a follows check and that an unchecked claim is only 'tried'");
        // the child is shown no checklist, so the agent must not be told he sees ✔ / "I tried".
        assertTrue(creature.contains("NO checklist") && !creature.contains("I tried"),
                "the creature lesson must say the child sees no checklist, only a broken bit");
        assertTrue(source.contains("NOT shown a checklist") && !source.contains("'I tried'"),
                "finish_building's claims schema must not promise the child a ✔ or 'I tried'");
    }

    @Test
    @DisplayName("#1271: a command-granted wish is told to THEN TEACH IT, with repeat and wishvisit")
    void commandWishesAreToldToTeach() {
        String prompt = com.moddingfromamod.worker.WorkerPrompt.text();
        assertTrue(prompt.contains("GRANTED IT WITH COMMANDS? THEN TEACH IT")
                        && prompt.contains("\"countable\":true") && prompt.contains("wishvisit")
                        && prompt.contains("followup:true"),
                "the worker prompt must tell a command-granted wish to teach itself (repeat/countable "
                        + "for identical summons, wishvisit for take_me_to, followup for help)");
    }

    @Test
    @DisplayName("#1345: the agent knows a building's footing is done for it, and how to float one on purpose")
    void theFootingIsDoneForItAndFloatingIsOnPurpose() {
        String building = WishLessons.text("building");
        assertTrue(building.contains("never add your own foundation, pillars or raft"),
                "the building lesson must say the footing/island/set-down is automatic, or the agent "
                        + "hand-builds a second foundation under the one the tool already laid");
        assertTrue(building.contains("little island") && building.contains("set down on the")
                        && building.contains("ground below"),
                "the lesson must name what it does over water and over a long drop, so the agent can "
                        + "tell him where his building went");
        assertTrue(building.contains("every part above dy 0") && building.contains("`offsetY` above 0")
                        && building.contains("`up`"),
                "the lesson must teach the two ways to float a building on purpose — otherwise a sky "
                        + "castle aimed above the ground is set down on it");
    }

    @Test
    @DisplayName("#1037: the agent is taught the TNT budget before it builds, not only refused after")
    void theTntBudgetIsTaught() {
        String building = WishLessons.text("building");
        assertTrue(building.contains("2,000 TNT") && building.contains("HOLLOW"),
                "the building lesson must teach the 2,000-TNT limit and the hollow-shell way round it — "
                        + "a solid TNT volcano crash-looped the live server (2026-09-27)");
    }

    @Test
    @DisplayName("#1265: creatures come in waves, in true words — never an invented limit or a crash")
    void creatureCountsAreWavesNotCrashes() throws IOException {
        String creature = WishLessons.text("creature").replaceAll("\\s+", " ");
        assertTrue(creature.contains("WAVES") && creature.contains("Wish 'more'"),
                "the creature lesson must teach waves and the 'wish more' words");
        assertTrue(creature.contains("Never tell him the game would crash"),
                "the creature lesson must forbid the invented crash warning (#1265)");
        for (com.moddingfromamod.mob.SpawnBudget.Kind k : com.moddingfromamod.mob.SpawnBudget.Kind.values()) {
            if (k != com.moddingfromamod.mob.SpawnBudget.Kind.SCULPTED) {
                assertTrue(creature.contains("**" + com.moddingfromamod.mob.SpawnBudget.defaultFor(k, false) + "**"),
                        "the creature lesson must state the " + k + " wave the game enforces ("
                                + com.moddingfromamod.mob.SpawnBudget.defaultFor(k, false) + ") — a lesson that"
                                + " drifts from SpawnBudget teaches a number the world will contradict");
            }
        }
        String tool = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/RunCommandTool.java"));
        assertTrue(tool.contains("CREATURES COME IN WAVES") && tool.contains("never say the game would")
                        && tool.contains("SpawnBudget.perWish(SpawnBudget.Kind.VANILLA)"),
                "run_command's description must carry the waves rule with SpawnBudget's own numbers");
        String said = com.moddingfromamod.mob.SpawnBudget.forTheAgent(
                com.moddingfromamod.mob.SpawnBudget.waveLine(
                        com.moddingfromamod.mob.SpawnBudget.Kind.EXPLOSIVE, "minecraft:creeper", 50));
        assertTrue(said.contains("Wish 'more'") && said.contains("never say the game would crash"),
                "the summon result must hand the agent the wave words and forbid the crash claim: " + said);
    }

    @Test
    @DisplayName("#942: a question is answered after LOOKING — in the note, the prompt and the lesson")
    void aQuestionLooksFirst() {
        String note = WishIntent.noteForAgent("spikor stopped following me, why?");
        assertTrue(note.contains("LOOK FIRST") && note.contains("list_creatures")
                        && note.contains("refused"),
                "the question note must say to look first, name the read tools, and that it is enforced");
        assertTrue(com.moddingfromamod.worker.WorkerPrompt.text().contains("list_creatures, inspect_creature"),
                "the worker prompt's question paragraph must name the creature read tools");
        assertTrue(WishLessons.text("creature").contains("look before you answer"),
                "the creature lesson must carry the look-first rule");
    }

    @Test
    @DisplayName("#766: everything that sits on a creature's head is parented to the head")
    void headFeaturesAreParentedToTheHead() {
        // A live "copy of me" turned its face to look at the child and left its hair box behind,
        // because the hair was not under `head` and only the head's children inherit head-look.
        String creature = WishLessons.text("creature");
        assertTrue(creature.contains("\"parent\":\"head\"") && creature.contains("Hair"),
                "the creature lesson must tell the agent to parent hair and face parts to the head");
    }

    @Test
    @DisplayName("#1481-#1485: flying, swimming, hopping, sitting and sleeping are chosen by power and NAME")
    void howItMovesIsChosenByPowerAndByName() throws IOException {
        // The rig draws all five from the sculpt's own boxes, so the agent's whole part in it is a
        // power (flies, hops, naps), a stay, and calling a wing a wing -- a box that is not named
        // one never flaps, and nothing tells the child why.
        String creature = WishLessons.text("creature");
        for (String must : new String[]{"`flies`", "`hops`", "`naps`", "`stay: 0`", "`wing`",
                "`tail…`", "`fin…`"}) {
            assertTrue(creature.contains(must), "the creature lesson must teach " + must);
        }
        String schema = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/SculptCreatureTool.java"));
        assertTrue(schema.contains("flies, hops, naps") && schema.contains("WINGS BEAT"),
                "sculpt_creature's powers must offer hops and naps and say what flies does");
        assertTrue(com.moddingfromamod.species.Species.Power.parse("hops") != null
                && com.moddingfromamod.species.Species.Power.parse("naps") != null,
                "a power the schema offers must parse");
    }

    /**
     * <b>The agent must be TOLD that wish_item exists, and be told the traps around it.</b>
     *
     * <p>Pinned because of §4e: a capability reachable only through an enumeration is only as general
     * as that enumeration. Impulse scored 4/8 with zero wrong picks — the agent was never wrong when
     * it reached for the catalogue, it simply did not reach. An item wish has exactly the same
     * failure available to it, and a worse one: a datapack really cannot change an icon, so
     * "the best I can do is rename a snowball" is a <i>correct</i> inference with a wrong conclusion.
     */
    @Test
    void theAgentIsToldHowToGiveAChildAThing() {
        String primer = com.moddingfromamod.datapack.CbScriptPrimer.FULL;
        assertTrue(primer.contains("moddingfromamod:wish_item"),
                "cbscript_help must name the item, or the agent will renamed-snowball every item wish");
        for (String must : new String[] {"WishOnUse", "WishThrowTag", "WishPlaceTag",
                                         "custom_model_data", "dyed_color"}) {
            assertTrue(primer.contains(must), "the primer must document " + must);
        }
        for (String icon : new String[] {"machine:1", "wand:10", "orb:9", "sword:17", "key:24"}) {
            assertTrue(primer.contains(icon),
                    "the icon vocabulary must be in the primer; without a number the agent guesses "
                            + "one and the item silently wears the default artwork (missing " + icon + ")");
        }
        assertTrue(primer.contains("NO minecraft:item_model component in 1.21.1"),
                "the primer previously taught a component that does not exist in this version — a "
                        + "give using it fails at runtime, so the correction must stay");
        assertFalse(primer.contains("swap its model to another vanilla"),
                "the old, wrong item_model advice must not come back");
    }

    /**
     * a thing's SUPPLIES ride it as its `kit`, never as a raw give beside it. A wish is saved
     * as a wish and its parts, and a part re-wishes only what the part's own commands make — so a
     * smoothie bar whose melon came from a separate `give` came back from a re-wish with nothing
     * to make smoothies from. Pinned in both places the agent decides: the lesson and the field.
     */
    @Test
    void suppliesRideTheThingAsItsKitNeverARawGive() throws IOException {
        String item = WishLessons.text("item");
        assertTrue(item.contains("`kit`") && item.contains("never a raw `give`"),
                "the item lesson must teach supplies as the thing's `kit`, never a raw give");
        assertTrue(item.contains("WITHOUT its kit"),
                "and that a machine handed back after breaking it does not mint a second kit");
        String source = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/MakeItemTool.java"));
        int at = source.indexOf("Schemas.prop(s, \"kit\"");
        assertTrue(at > 0, "make_item must offer a `kit` field");
        String field = source.substring(at, source.indexOf(");", at));
        assertTrue(field.contains("NEVER a") && field.contains("raw `give`"),
                "make_item's `kit` field must say a raw give is the wrong way to hand supplies over");
    }

    /**
     * one wish leaves ONE of a thing. A rebuild under a new id left two volcanoes, and a
     * refused item edit became a second grappling hook. The tools refuse both (NoDuplicate); the
     * agent must also be TOLD, in the lesson and on the field, or every grant pays a refusal.
     */
    @Test
    void aRebuildUsesTheSameIdAndARefusedEditIsNeverANewItem() throws IOException {
        String building = WishLessons.text("building");
        assertTrue(building.contains("Use the SAME id") && building.contains("`as_new: true`"),
                "the building lesson must say a redo in the same wish uses the same id, and as_new is "
                        + "for a separate building only");
        String item = WishLessons.text("item");
        assertTrue(item.contains("A refused change is not a reason to make a new one")
                        && item.contains("Tell him what cannot change") && item.contains("`as_new: true`"),
                "the item lesson must say a refused edit is told to him, never made into a second item");
        String build = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/BuildStructureTool.java"));
        int at = build.indexOf("Schemas.prop(s, \"as_new\"");
        assertTrue(at > 0, "build_structure must offer `as_new` for a deliberate second building");
        assertTrue(build.substring(at, build.indexOf(");", at)).contains("SAME"),
                "build_structure's `as_new` must say a redo sends the SAME id");
        String make = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/MakeItemTool.java"));
        at = make.indexOf("Schemas.prop(s, \"as_new\"");
        assertTrue(at > 0 && make.substring(at, make.indexOf(");", at)).contains("A REFUSED EDIT IS NOT A REASON"),
                "make_item's `as_new` must say a refused edit is not a reason to make a new item");
    }

    /** And it must be REMINDED mid-wish, which is a different failure from not knowing. */
    @Test
    void theAgentIsRemindedThatThingsHaveAnItem() throws Exception {
        String session = session();
        assertTrue(session.contains("NEVER RENAME A SNOWBALL"),
                "the retrieval paragraph must name the item-wish case; knowing a capability exists "
                        + "and remembering it mid-wish are different problems (playbook §4e)");
        assertTrue(session.contains("rocket") || session.contains("slushie machine"),
                "and it must use the child's own words, from real wishes, not abstract categories");
    }

    /**
     * <b>make_item must be named in the retrieval paragraph, in the same words the child uses.</b>
     *
     * <p>This is §4e applied to the item catalogue, and it is the one assertion standing between it
     * and impulse's failure — 4/8 with <i>zero wrong picks</i>, an agent that was never wrong when
     * it reached for the catalogue and simply never reached. That happened because the paragraph
     * listed only class 1's vocabulary. So the pin is not "make_item is mentioned somewhere" but
     * "the wishes it is FOR are quoted where the agent decides what to do".
     */
    @Test
    void theAgentIsRemindedThatReadyMadeThingsExist() throws Exception {
        String session = session();
        assertTrue(session.contains("make_item"),
                "AgentSession never names make_item, so the catalogue is unreachable in practice "
                        + "however green every other gate is. This is exactly how a quarter of the "
                        + "ability catalogue stayed unused behind four passing gates (§4e).");
        // The child's own words, from the pre-registered set. An enumeration is only as general as
        // its examples, and the agent generalises from THEM rather than from the category name.
        for (String words : new String[] {"wand that shoots lightning", "throw that explodes",
                                          "freezes whatever it hits", "makes me GIANT"}) {
            assertTrue(session.contains(words),
                    "the retrieval paragraph must quote '" + words + "' — a category documented "
                            + "only by its definition is one the agent will not reach for (§4f)");
        }
        // Prediction 3 of the pre-registration: a wish phrased as a BEHAVIOUR ('make my sword set
        // things on fire') reads like an ability and goes to apply_effect or straight to authoring.
        // If that prediction is right, this sentence is the fix; if it is wrong, this sentence is
        // what makes the miss attributable to something other than never having been told.
        assertTrue(session.contains("PHRASED AS A BEHAVIOUR"),
                "the paragraph must say that 'make my sword set things on fire' is an ITEM wish. "
                        + "That is pre-registered prediction 3, and it is the likeliest miss.");
        // And it must not have quietly regained a default anywhere.
        // a compound wish must be ONE call. The javelin proved the cost of not saying so —
        // the agent built the half it could and authored the other half separately, which is the
        // round trip the catalogue exists to remove.
        assertTrue(session.contains("TWO PARTS IS ONE CALL"),
                "the paragraph must say a two-part wish is one make_item call with '+', or the "
                        + "agent keeps splitting compound wishes across two builds");
        // the one thing only this catalogue can do, named in the child's words.
        assertTrue(session.contains("fly") && session.contains("rocket"),
                "nothing tells the agent that `fly` is the only effect that moves the CHILD, so a "
                        + "rocket wish gets reinterpreted as a bomb — measured in tier 3.5");
        assertTrue(session.contains("no ready-made effect fits"),
                "it must also say when NOT to use the catalogue, or 'nearly fits' becomes 'fits' — "
                        + "pre-registered prediction 4, the faster-and-worse grant");
    }

    /** The tool itself must ask for every value rather than choosing any of them. */
    @Test
    void makeItemDefaultsNothingAndTeachesEveryChoice() throws Exception {
        String tool = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/MakeItemTool.java"));
        String description = tool.substring(tool.indexOf("public String description()"),
                tool.indexOf("public JsonObject inputSchema()"));
        assertTrue(description.contains("EVERY parameter is REQUIRED"),
                "the DESCRIPTION must say so, not only the schema: a model picks its approach from "
                        + "the summary and may never read a parameter doc for an option it does "
                        + "not know it has to supply");
        // Every parameter of every entry carries the child's-words prompt, and the tool prints it.
        // each entry's text is ReadyMadeSources.itemDetail — ONE definition, printed here in
        // full by default and by describe_ready_made when the item search slims this list — so the
        // ask must be in that method, and the description must print it.
        String sources = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/moddingfromamod/readymade/ReadyMadeSources.java"));
        String itemDetail = sources.substring(sources.indexOf("public static String itemDetail("),
                sources.indexOf("// ---- (b) creature templates"));
        assertTrue(description.contains("ReadyMadeSources.itemDetail(e)"),
                "make_item's description no longer prints each entry's full text");
        assertTrue(itemDetail.contains("from the wish:"),
                "the tool must show each parameter's 'ask' — TRIGGER's doc explained its categories "
                        + "abstractly and the agent omitted the parameter anyway; adding the "
                        + "child's phrasings is what fixed those exact wishes (§4f rule 2)");
        var catalogue = com.moddingfromamod.datapack.ItemCatalogue.load();
        for (var e : catalogue.all()) {
            for (String p : e.params()) {
                assertFalse(e.paramAsks().get(p).isBlank(),
                        e.id() + "." + p + " has no 'ask', so the agent is offered a choice with "
                                + "no way to make it from the wish text");
            }
        }
    }

    /**
     * The agent's brief, read as SOURCE — there is no other way to check what it is told.
     *
     * <p>⚠ <b>BOTH files, and that is the point.</b> HO-6 moved the prompt text out of
     * {@code AgentSession} into {@code WorkerPrompt} so a worker container could build the same
     * command line without Minecraft on its classpath. Every rule in here went red the moment it
     * did — correctly: the brief was no longer where this test looks. Concatenating both is what
     * keeps the question "is the agent told X" answerable however the text is split, so the next
     * move does not silently pass by making all of these assert against an empty string.
     */
    private static String session() throws IOException {
        return Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/AgentSession.java"))
                + Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/worker/WorkerPrompt.java"));
    }

    /**
     * <b>The reuse-first rule must cover ITEMS, not only mobs.</b>
     *
     * <p>The rule existed and the agent obeyed it, and it still faked an item this instance ships. It
     * was written entirely in mob language — "prefer a REAL mob", "summon THAT real mob", "NEVER
     * delete a real mob" — while {@code find_content} answers with an ITEMS section too. A rule that
     * names only one of the two kinds it applies to is a rule the agent will correctly apply to one
     * kind.
     */
    @Test
    @DisplayName("the reuse-first rule names items, not only mobs")
    void reuseFirstAppliesToItemsToo() throws IOException {
        String session = session();
        int rule = session.indexOf("THE MOST IMPORTANT RULE when granting");
        assertTrue(rule >= 0, "the reuse-first rule is gone entirely");
        // Bounded to the rule itself: the word "item" appears all over this file, so searching the
        // whole of it would pass on an unrelated paragraph and prove nothing.
        String text = session.substring(rule, Math.min(session.length(), rule + 4000));
        assertTrue(text.contains("ITEMS exactly as much as"),
                "the reuse-first rule still speaks only of mobs, so an item wish reads it as being "
                        + "about something else — which is exactly what happened: the agent obeyed "
                        + "it, searched, and invented anyway");
        assertTrue(text.contains("GIVE THE REAL"),
                "the rule tells the agent to prefer a real item but never what to DO with it; "
                        + "'summon THAT real mob' has no item equivalent without this");
    }

    /**
     * <b>And it must be told WHAT to search for.</b>
     *
     * <p>The failure was not a missing search. The agent searched, with the category noun — "sword" —
     * and reasonably concluded nothing matched. {@code ContentSearch.rank} requires every query word
     * to be present, so the odd specific phrase is the one that discriminates and the category noun
     * is the one that cannot.
     */
    @Test
    @DisplayName("the agent is told to search the child's own odd words, not the category noun")
    void theSearchTermIsTheNamedNoun() throws IOException {
        String session = session();
        int rule = session.indexOf("SEARCH THE WORDS HE SAID");
        assertTrue(rule >= 0,
                "nothing tells the agent WHICH words to search. It searched \"sword\" for a BUTT "
                        + "CHEEK WATER sword, found other people's swords, and invented a fake of an "
                        + "item that was already installed");
        String text = session.substring(rule, Math.min(session.length(), rule + 1600));
        assertTrue(text.contains("category noun"),
                "the rule must say what NOT to search, or 'search the key words' reads as satisfied "
                        + "by the one word that matches everything");
        assertTrue(text.contains("butt_cheek_water_bucket"),
                "the rule must carry the real case that produced it — an abstract instruction about "
                        + "'specific words' is the same instruction that already failed");
    }

    /**
     * <b>The child must be TOLD his thing is findable again.</b>
     *
     * <p>Pinned because it was already lost once. The sentence went in, and a merge that
     * rewrote the surrounding paragraph for {@code make_item} dropped it — no conflict, no test, no
     * way to notice. Nothing broke, which is the problem: the capability stayed built and the child
     * stayed unaware, and "I couldn't find it" was the report that started all of this.
     */
    @Test
    @DisplayName("the agent is told to tell the child a wished thing is searchable")
    void theChildIsToldHisThingIsFindableAgain() throws IOException {
        String session = session();
        assertTrue(session.contains("TELL HIM HE CAN FIND IT AGAIN"),
                "nothing tells the agent to mention that a wished thing is in the creative menu. "
                        + "The catalogue can be working perfectly and the child still believes his "
                        + "sword is gone, which is the exact report #114 came from");
        assertTrue(session.contains("creative menu"),
                "and it must name WHERE, or 'you can find it again' is not actionable for a child");
    }

    /**
     * <b>And in the tool's own description, which is where the CALL is shaped.</b>
     *
     * <p>Same argument as {@code compositionIsOfferedWhereTheApproachIsCHOSEN} above: a model reads the
     * description when deciding how to call a tool. This one used to say <i>"Search by a plain word
     * ('wither', 'dragon', 'shark')"</i> — every example a mob, and "a plain word" is an instruction to
     * do the exact thing that failed. A correct rule in {@code AgentSession} and a contradicting hint
     * on the tool is worse than either alone.
     */
    @Test
    @DisplayName("find_content's own description does not advise searching the category noun")
    void theToolDescriptionDoesNotTeachTheBadSearch() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/FindContentTool.java"));
        String summary = source.substring(source.indexOf("public String description()"),
                source.indexOf("public JsonObject inputSchema()"));
        assertFalse(summary.contains("Search by a plain word"),
                "the tool still advises searching 'a plain word', which is how a BUTT CHEEK WATER "
                        + "sword got searched as 'sword'");
        assertTrue(summary.contains("ODD WORDS HE ACTUALLY SAID"),
                "the description must say WHICH words to search — it is read when the call is being "
                        + "shaped, which is earlier than any rule in the session prompt");
        assertTrue(summary.contains("GIVE him that one"),
                "the description must say what to do with a found ITEM; 'summon the REAL one' covers "
                        + "only mobs, which is the whole shape of this bug");
    }

    /**
     * <b>Do not offer to rebuild what the found thing already does.</b>
     *
     * <p>The compounding half of the same wish: having faked the water, it closed by offering to
     * "make it DO something when you drink it" — which the real item had done for weeks. So the child
     * was offered, as a next wish, a feature he already owned.
     */
    @Test
    @DisplayName("the agent is told to check what a found item already does before offering to add it")
    void doNotOfferToRebuildWhatAlreadyExists() throws IOException {
        String session = session();
        assertTrue(session.contains("check whether it already does"),
                "nothing warns against offering a feature the real item already has. This is not "
                        + "hypothetical: the suggestion left on the wish was to build behaviour "
                        + "the mod repo had shipped for weeks");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("the agent is told that a FACE is painted, not built out of blocks")
    void theAgentKnowsBlockArtIsNinePixels() throws Exception {
        // asked for six Kroakling faces, this system delivered six differently-coloured 3x3
        // squares and honestly reported success — structurally that IS what was asked for. The tool
        // now exists, but a tool the agent has not been TOLD about loses to the answer it already
        // knows. Pinned here because a rule that is only in a doc reaches nobody: the agent builds
        // from lessons/<kind>.md and the tool schemas, and neither is touched by fixing a fixture.
        for (String kind : new String[] {"building"}) {
            String lesson = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "src/main/resources/lessons/" + kind + ".md"));
            org.junit.jupiter.api.Assertions.assertTrue(lesson.contains("paint_surface"),
                    "lessons/" + kind + ".md never mentions paint_surface, so a wish for a face on "
                            + "one will still be answered with coloured blocks");
            org.junit.jupiter.api.Assertions.assertTrue(
                    lesson.matches("(?s).*\\b(nine|9)\\b[^.]*pixel.*"),
                    "lessons/" + kind + ".md does not say HOW SMALL block art is. An instruction "
                            + "can be overridden by a wish that sounds different; a number cannot.");
        }
    }

    /**
     * <b>A substitution may change the MATERIAL and must not change what the machine DOES.</b>
     *
     * <p>Measured, and it reached the child: a wish asked for a machine that turns logs into a drink
     * and bottles it. The drink was not in that world, so the substitute chosen was wheat → flour,
     * and what stood at the end was a <b>millstone dropping into a chest</b> — a flour mill wearing
     * the name the child had asked for. It scored {@code working: true, delivered: 4}, because the
     * verdict counts items and could not tell which machine it was looking at.
     *
     * <p>The existing rule ("pick something close and say so") was followed, and it was not enough,
     * because it says nothing about how far "close" reaches. See docs/MACHINE_CLAIM_AUDIT.md.
     */
    /**
     * <b>A refinement changes the thing in his hand, so the agent must not hand over a
     * second one.</b> {@code make_item} restamps what he holds and drops its "NOW PUT IT IN HIS
     * HAND" line on an edit; an agent that gives anyway, from habit, still leaves him two
     * ({@code InventoryCountGameTest}). The tool says it per call; this is the first-draft half.
     */
    @Test
    void anEditIsNotHandedOverAgain() throws IOException {
        String lesson = Files.readString(Path.of("src/main/resources/lessons/item.md"));
        assertTrue(lesson.contains("do not hand him another"),
                "lessons/item.md no longer says an edit changes the one in his hand, so the "
                        + "agent's habit of running the give after every make_item returns — and "
                        + "a refinement leaves him holding the old one AND the new one");
    }

    /**
     * <b>: a wearable is a SHAPE, and "double jump" is said to be impossible.</b>
     *
     * <p>Before the worn shapes existed, "boots that let me DOUBLE JUMP!!!" came out as a
     * right-click levitation charm and was dropped from the starter set. Two traps survive the
     * shape existing: answering a wearable with {@code held} (which works well enough that nobody
     * notices), and promising a double jump the game cannot detect. Both are pinned here, and the
     * tool's own description must name the worn shapes, or the lesson teaches a word the tool
     * never offers.
     */
    /**
     * <b>: a real item's own use stays; the wish rides on it.</b> The laser bow: a
     * {@code held} wish on {@code base_item minecraft:bow} cancelled the draw, and the schema's own
     * example for {@code base_item} was a bow that shoots lightning — which pulled the agent to
     * {@code held}. Pinned in three places: the lesson, the schema's example, and the per-base
     * guidance make_item's schema carries (generated from ItemTriggers, so it names the table's
     * rides rather than a hand-typed list).
     */
    @Test
    void aRealItemKeepsItsOwnUseAndTheWishRidesOnIt() throws IOException {
        String lesson = Files.readString(Path.of("src/main/resources/lessons/item.md"));
        assertTrue(lesson.contains("A real item's own use stays; the wish rides on it"),
                "lessons/item.md lost the rule that a wish on a real item rides its own use, so a "
                        + "bow is built `held` again and cannot be drawn (#952)");
        String tool = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/MakeItemTool.java"));
        assertFalse(tool.contains("\\\"a bow that shoots lightning\\\""),
                "make_item's base_item example is a bow that shoots lightning again — the example "
                        + "that pulled the agent to `held` on a bow (#952)");
        assertTrue(tool.contains("ItemTriggers.guidance(ItemCatalogue.SHAPES)"),
                "make_item's schema no longer carries the per-base guidance from ItemTriggers");
        String guidance = com.moddingfromamod.datapack.ItemTriggers.guidance(
                com.moddingfromamod.datapack.ItemCatalogue.SHAPES);
        assertTrue(guidance.contains("bow") && guidance.contains("`arrow`"),
                "the guidance must send a bow to `arrow`: " + guidance);
        // The laser. Both moments of a shot are named where the agent chooses.
        assertTrue(lesson.contains("`on_shot` is THE MOMENT HE"),
                "lessons/item.md lost the difference between `arrow` (where it lands) and `on_shot` "
                        + "(the moment he fires) — the laser bow is `on_shot`");
        // Food and drink ride the eating.
        assertTrue(lesson.contains("Food and drink are `on_consume`"),
                "lessons/item.md lost that food and drink ride `on_consume`, so a magic cookie is "
                        + "built `held` and cannot be eaten");
        // Wishes the game already grants are built as the real thing, and he is told.
        assertTrue(lesson.contains("## Some wishes are already in the game"),
                "lessons/item.md lost the vanilla-native section ");
        assertTrue(lesson.contains("SAY SO in his words"),
                "lessons/item.md lost the rule to tell him when a wish cannot be made to do the thing");
        assertTrue(tool.contains("VanillaNative.guidance()"),
                "make_item's description no longer carries the vanilla-native ways");
        // Throwables ride the landing; a horn, brush or spyglass rides the end of the use.
        assertTrue(lesson.contains("is `on_throw_land`: vanilla throws it"),
                "lessons/item.md lost that a real throwable is `on_throw_land`");
        assertTrue(lesson.contains("is `on_use_done`"),
                "lessons/item.md lost that a horn, brush or spyglass is `on_use_done`");
        assertTrue(tool.contains("• on_throw_land he THROWS") && tool.contains("• on_use_done he USES"),
                "make_item's description no longer offers `on_throw_land` and `on_use_done`");
        // A shield rides the block.
        assertTrue(lesson.contains("A shield is `on_block`"),
                "lessons/item.md lost that a shield is `on_block`, so a wished shield cannot block");
        assertTrue(tool.contains("• on_block he raises a SHIELD"),
                "make_item's description no longer offers `on_block`");
        assertTrue(tool.contains("• on_consume he EATS or DRINKS it"),
                "make_item's description no longer offers `on_consume`");
        assertFalse(tool.contains("\\\"a potion that makes me GIANT\\\""),
                "make_item's `held` example is a potion again — measured, 3 of 8 potion wishes "
                        + "then came back as a right-click potion he can never drink (#952)");
        assertTrue(tool.contains("• on_shot he SHOOTS it"),
                "make_item's description no longer offers `on_shot`, so a laser goes to `arrow` or `held`");
    }

    @Test
    void aWearableIsWornAndDoubleJumpIsSaidToBeImpossible() throws IOException {
        String lesson = Files.readString(Path.of("src/main/resources/lessons/item.md"));
        assertTrue(lesson.contains("A thing he WEARS is a shape too"),
                "lessons/item.md no longer tells the agent a wearable is a worn_* shape, so boots "
                        + "come back as a charm he has to right-click (#685)");
        assertTrue(lesson.contains("A true double jump is not possible"),
                "lessons/item.md no longer says a double jump cannot be detected, so the agent "
                        + "promises one and hands over something else without saying so");
        assertTrue(lesson.contains("built on `minecraft:elytra`"),
                "the lesson lost how a cape that flies is built, so it is built as leather that "
                        + "does not glide");
        String tool = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/MakeItemTool.java"));
        String described = tool.substring(tool.indexOf("description()"),
                tool.indexOf("inputSchema()"));
        assertTrue(described.contains("worn_feet"),
                "make_item's description never names the worn shapes, so the lesson teaches a "
                        + "word the tool does not offer");
    }

    /**
     * <b>A taught wish must NAME its button's picture.</b>
     *
     * <p>The measured defect (plan §10, "why the icons look random"): {@code WatchIcons.forWish} is
     * a first-match keyword scan over ~50 rules, and a recipe id the agent invented —
     * {@code sparkly_cheese_island} — matches none of them, so it and every other unmatched recipe
     * draw the same {@code minecraft:amethyst_shard}. The mechanism to fix it has existed since
     * The curated library ({@code add_template}'s {@code icon} field, {@code LibrarySnapshot.iconOf}, the explicit
     * branch in {@code forWish}) and the agent was told about it in a SCHEMA field description
     * only — which is read after the call has been decided, if at all.
     *
     * <p>So this pins both ends, for the reason the copycat cost this repo: a rule can be measured,
     * implemented and pinned and still never reach the thing that builds for a child. And it is
     * pinned in EVERY kind's lesson rather than one, because {@code add_template} is not a
     * kind-specific tool — a creature wish, a building wish and a datapack wish all end at it.
     */
    @Test
    @DisplayName("the agent is told to name its button's icon, in every kind's lesson and the schema")
    void aTaughtWishNamesItsOwnIcon() throws IOException {
        String schema = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/AddTemplateTool.java"));
        assertTrue(schema.contains("\"icon\""),
                "add_template no longer offers an `icon` at all, so a taught wish cannot name its "
                        + "own button and every unmatched recipe falls back to one shared shard");
        assertTrue(schema.contains("minecraft:"),
                "the icon field never shows the agent what an item id LOOKS like, and a bare "
                        + "`cake` resolves to AIR — an empty slot where a button should be");

        for (String kind : new String[]{"building", "creature", "datapack", "item"}) {
            String lesson = WishLessons.text(kind);
            assertFalse(lesson.isBlank(), "lessons/" + kind + ".md is empty or unreadable");
            assertTrue(lesson.contains("`icon`"),
                    "lessons/" + kind + ".md never mentions `icon`, so an agent granting a " + kind
                            + " wish teaches a template with no picture. add_template is reached "
                            + "from EVERY kind; a rule that lives in one lesson is a rule five "
                            + "sixths of the wishes never meet.");
            assertTrue(lesson.contains("amethyst"),
                    "lessons/" + kind + ".md asks for an icon without saying what happens without "
                            + "one. A rule with no consequence attached is the first thing dropped "
                            + "when the brief is trimmed — name the shard.");
        }
    }


    @Test
    @DisplayName("'make me <character> who …' is a creature to build, not a power for the child")
    void aNamedCharacterIsNeverAPlayerPower() throws IOException {
        // Measured 2026-09-22 (the Opus 5 vs 5.5 panel): on a "make me <character> who runs super
        // fast" wish, Opus 5 used modify_player + a speed template 3 times in 3 and built no
        // creature. The rule was already here, but gated on the child calling the character
        // "he/she/them" -- a "who …" clause slipped past it. The rule must hold however the
        // sentence continues.
        String source = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/ModifyPlayerTool.java"));
        assertTrue(source.contains("names ANY person or character"),
                "modify_player must refuse any wish that names a character, unconditionally");
        assertTrue(source.contains("however the sentence goes on"),
                "the rule must not depend on how the child continues the sentence");
    }

    @Test
    @DisplayName("a forever power is one call, and the agent is told it survives death")
    void aForeverPowerIsOneCallBecauseItSurvivesDeath() throws IOException {
        // SurviveDeath carries every infinite effect across a respawn. The kit only saves time if the
        // agent KNOWS that: an agent that still believes an effect ends on death writes a tick pack
        // that re-applies it, and the kit is measured as worth nothing (E3).
        String source = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/ModifyPlayerTool.java"));
        String summary = source.substring(source.indexOf("public String description()"),
                source.indexOf("public JsonObject inputSchema()"));
        assertTrue(summary.contains("forever includes after") && summary.contains("DIES"),
                "modify_player's description must say its powers come back after he dies");
        String datapack = WishLessons.text("datapack");
        assertTrue(datapack.contains("is not a datapack") && datapack.contains("survive his death"),
                "the datapack lesson must say a forever power on the child is one call, not a pack");
        assertTrue(datapack.contains("effect give @s <effect> infinite"),
                "the lesson must give the run_command form that SurviveDeath carries");
    }

    @Test
    @DisplayName("an extra drop is change_drops, and a loot table would REPLACE the vanilla one")
    void anExtraDropIsOneCallAndNeverAReplacedLootTable() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/ChangeDropsTool.java"));
        String summary = source.substring(source.indexOf("public String description()"),
                source.indexOf("public JsonObject inputSchema()"));
        assertTrue(summary.contains("ONE CALL") && summary.contains("REPLACES"),
                "change_drops must say it is one call, and why a hand-written loot table is wrong");
        String datapack = WishLessons.text("datapack");
        assertTrue(datapack.contains("change_drops") && datapack.contains("REPLACES the vanilla one"),
                "the datapack lesson must send a drop wish to change_drops, with the reason");
    }

    @Test
    @DisplayName("'when X, do Y' is one set_rule call, never a hand-written tick loop")
    void whenThisHappensIsOneSetRuleCall() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/SetRuleTool.java"));
        String summary = source.substring(source.indexOf("public String description()"),
                source.indexOf("public JsonObject inputSchema()"));
        assertTrue(summary.contains("ONE CALL") && summary.contains("Do NOT write a datapack or a tick loop"),
                "set_rule must say it is one call, and that a tick-loop datapack is the wrong route");
        // The two things commands cannot do must be offered in the summary, or the agent reaches
        // for a datapack to fake a launch or a bone meal.
        assertTrue(summary.contains("`push`") && summary.contains("`grow`") && summary.contains("`harmless`"),
                "set_rule's description must offer push, grow and harmless");
        String datapack = WishLessons.text("datapack");
        assertTrue(datapack.contains("`set_rule`") && datapack.contains("not a tick loop"),
                "the datapack lesson must send a 'when X, do Y' wish to set_rule");
        // The SYSTEM PROMPT decides before any lesson is read, and it used to name "a per-tick
        // rule" as author_datapack's job: real grants then wrote datapacks 4/4 (Gate D, 2026-09-25).
        String prompt = com.moddingfromamod.worker.WorkerPrompt.text();
        assertTrue(prompt.contains("READY-MADE RULES COME FIRST") && !prompt.contains("a per-tick rule"),
                "the system prompt must send rules to set_rule before it offers author_datapack");
    }

    @Test
    @DisplayName("a pet is a real mob plus modify_mob's pet fields, taught where the choice is made")
    void aPetIsARealMobPlusModifyMob() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/ModifyMobTool.java"));
        String summary = source.substring(source.indexOf("public String description()"),
                source.indexOf("public JsonObject inputSchema()"));
        for (String field : java.util.List.of("`follow`", "`friendly`", "`rideable`", "`finds`")) {
            assertTrue(summary.contains(field), "modify_mob's DESCRIPTION must offer " + field
                    + ": the approach is picked from the summary, before any schema is read");
        }
        // Measured 2026-09-24 (routing-arm t6c-wave1, 8 runs each): with only modify_mob offering
        // the fields, "follows me" went to apply_effect 8/8 and "finds diamonds" to apply_effect or
        // author_datapack 8/8. The choice is made in apply_effect's description, so it says no there.
        String effect = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/ApplyEffectTool.java"));
        String effectSummary = effect.substring(effect.indexOf("public String description()"),
                effect.indexOf("public JsonObject inputSchema()"));
        assertTrue(effectSummary.contains("NOT FOR A PET") && effectSummary.contains("modify_mob"),
                "apply_effect must send a pet to modify_mob where the agent picks an ability");
        assertTrue(summary.contains("cannot be ridden until modify_mob says rideable"),
                "summoning a dolphin is not making it rideable (ride went to a bare summon 7/8)");
        String creature = WishLessons.text("creature");
        assertTrue(creature.contains("A PET is a real mob plus `modify_mob`")
                        && creature.contains("follow: true") && creature.contains("friendly: true"),
                "the creature lesson must teach a pet as summon + one modify_mob");
    }

    @Test
    @DisplayName("the CBScript the agent ALWAYS reads never teaches a syntax error")
    void thePrimerInsideAuthorDatapackTeachesOnlyFormsThatParse() {
        // CbScriptPrimer.SHORT rides inside author_datapack's description, which is the only CBScript
        // reference an agent reliably reads: across the 36 runs of the 2026-09-22 Opus 5 vs 5.5
        // A/B, cbscript_help (where CBSCRIPT_LESSONS.md is appended) was called ONCE. And SHORT
        // said "Conditions: ... `unless entity @e[...]`" -- a form the compiler rejects
        // ("Unexpected ATID"). Every form it now shows was compiled against the bundled compiler.
        String s = com.moddingfromamod.datapack.CbScriptPrimer.SHORT;
        assertFalse(s.contains("unless entity @") || s.contains("if entity @"),
                "SHORT must not show `if/unless entity @...`, which does not parse");
        assertTrue(s.contains("`unless @e[tag=foo]`") && s.contains("`if @a[distance=..8]`"),
                "SHORT shows the selector-condition form that parses");
        assertTrue(s.contains("`@a[cd >= 1]`, NOT vanilla `scores={cd=1..}`"),
                "the commonest first-draft error on both models is named where it is always read");
        assertTrue(s.contains("`type=sheep`, NOT `type=minecraft:sheep`"),
                "namespaced types in a CBScript selector are named as a trap");
        assertTrue(s.contains("never `then`") && s.contains("there is no `!=`"),
                "the condition-syntax traps seen in live runs are named");
        // It also showed `define @Foo = @e[...] end` on ONE line, which does not parse; with only
        // this primer, Opus 5 copied it into 20 of 38 bench drafts.
        assertFalse(s.contains("@e[...] end "),
                "SHORT must not show a one-line `define ... end`, which does not parse");
        assertTrue(s.contains("Its `end` goes ALONE on the NEXT line"),
                "the two-line define form is spelled out");
    }

    @Test
    @DisplayName("something that spins is a vortex, sized by what it picks up, and only his words set its path")
    void aSpinningThingIsAVortexSizedByWhatItPicksUp() throws IOException {
        String lesson = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/lessons/event.md"));
        for (String must : new String[]{"**Something SPINS** → `vortex`", "Do NOT fake one with a `moving_front`",
                "pick the size by what he wants it to PICK UP", "`path` only when he says how it moves",
                "A plain tornado or a whirlpool needs none", "`drop: cat`",
                "giant, huge, enormous, massive and ginormous are all `gigantic`", "A storm that spins is a hurricane",
                "A wave or a vortex WRECKS, unless his words ask for a safe one", "`spares: [\"builds\"]`",
                "never the whole world's sky",
                "How a vortex LOOKS comes from its material — set `look` only when his words describe it"}) {
            org.junit.jupiter.api.Assertions.assertTrue(lesson.contains(must),
                    "lessons/event.md lost '" + must + "' — a tornado goes back to a wave with lift (#1166), or every "
                            + "vortex is staged with a ton of fields that override its defaults");
        }
        String tool = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/StageEventTool.java"));
        for (String must : new String[]{"vortex (something that SPINS", "\"shape\"", "\"path\"",
                "On a vortex the size is what it CAN do", "Left out it is chosen for you", "\"look\"",
                "Left out, its material"}) {
            org.junit.jupiter.api.Assertions.assertTrue(tool.contains(must), "stage_event's schema lost '" + must + "'");
        }
    }

    @Test
    @DisplayName("#1047: an event is ONE stage_event, a follow-on is staged first, a re-wish keeps the name")
    void anEventIsOneStageEventNeverCommands() throws IOException {
        String lesson = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/lessons/event.md"));
        for (String must : new String[]{"ONE `stage_event`", "never commands", "`now: false`",
                "SAME name", "`remove_event` the OLD design", "moddyevent stage <name>", "remove_event", "What it LEAVES stays",
                "never TNT", "The picture comes first", "not a `fill ... air`",
                "for each id under EVENTS", "Only his words make it repeat", "a rock of `material` with lumps of `core`", "meteor always falls"}) {
            org.junit.jupiter.api.Assertions.assertTrue(lesson.contains(must),
                    "lessons/event.md lost '" + must + "' — an event goes back to hand-written fill "
                            + "commands that cannot move and cannot be given back");
        }
        String prompt = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/moddingfromamod/worker/WorkerPrompt.java"));
        for (String must : new String[]{"a sinkhole, a pit", "remove_event with the id from EVENTS"}) {
            org.junit.jupiter.api.Assertions.assertTrue(prompt.contains(must),
                    "WorkerPrompt lost '" + must + "' — the events smoke dug a sinkhole with fill and "
                            + "then could not put it back (2026-09-29)");
        }
        for (String tool : new String[]{"ListEventsTool", "StageEventTool"}) {
            String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "src/main/java/com/moddingfromamod/agent/tools/" + tool + ".java"));
            org.junit.jupiter.api.Assertions.assertTrue(src.contains("appendTo(\"event\")"),
                    "the event lesson must ride on " + tool);
        }
    }

    @Test
    @DisplayName("#680: pictures are CHANGED with start_from, forked when not his, and re-hung from another world")
    void thePictureLessonTeachesEditDontRedraw() throws IOException {
        String lesson = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/lessons/painting.md"));
        for (String must : new String[]{"list_paintings", "start_from", "as_new", "own copy",
                "no x/y/z"}) {
            org.junit.jupiter.api.Assertions.assertTrue(lesson.contains(must),
                    "lessons/painting.md lost '" + must + "' — a picture change becomes a redraw "
                            + "again, which loses everything not described twice");
        }
        String list = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/ListPaintingsTool.java"));
        org.junit.jupiter.api.Assertions.assertTrue(list.contains("appendTo(\"painting\")"),
                "the picture lesson must ride on list_paintings, the call made before any change");
    }


    @Test
    @DisplayName("#1302: from_skin takes the NAME a person is known by, never a username; no link means the lookup picture")
    void fromSkinTakesThePublicNameNeverAUsername() throws IOException {
        String lesson = Files.readString(Path.of("src/main/resources/lessons/painting.md"));
        for (String must : new String[]{"name they are known by", "Never a username",
                "plays under a different username", "No linked account → no skin", "look_up_character`'s colours"}) {
            assertTrue(lesson.contains(must), "lessons/painting.md lost '" + must + "' — a "
                    + "username guessed from the public name paints a stranger's skin: the account "
                    + "named like a famous creator is often a stranger's (#1302)");
        }
        var tool = new com.moddingfromamod.agent.tools.PaintSurfaceTool();
        String paint = tool.description();
        assertTrue(paint.contains("THEY ARE KNOWN BY") && paint.contains("never a username")
                        && paint.contains("look_up_character's colours"),
                "paint_surface's description must say from_skin is the public name, never a "
                        + "username, and that an unlinked person is painted from the lookup");
        String schema = tool.inputSchema().toString();
        assertTrue(schema.contains("NEVER a username") && schema.contains("fetches NOTHING"),
                "from_skin's schema text is what the model reads when it fills the argument");
        String worker = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/worker/WorkerPrompt.java"));
        assertTrue(worker.contains("name they are known by> — never a username"),
                "the worker prompt taught 'from_skin <their Minecraft username>'");
        String lookUp = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/LookUpCharacterTool.java"));
        assertTrue(lookUp.contains("NEVER a username") && lookUp.contains("do NOT call paint_surface with from_skin"),
                "look_up_character's person answers must carry the rule in both branches");
    }

    @Test
    @DisplayName("#1302: a creator only the generated list maps is told to paint their own skin, and only that")
    void aListedCreatorIsPaintedFromTheirSkin() throws IOException {
        String lookUp = new com.moddingfromamod.agent.tools.LookUpCharacterTool().description();
        assertTrue(lookUp.contains("generated creator list") && lookUp.contains("own skin"),
                "look_up_character's description must say a listed creator gets their own skin — "
                        + "before #1302's decision they were told 'not a public figure' and the "
                        + "agent rarely painted the skin");
        String src = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/LookUpCharacterTool.java"));
        assertTrue(src.contains("CreatorAccounts.lookup(name)") && src.contains("paint THIS CREATOR'S OWN SKIN"),
                "the private-person branch must consult the creator list and name from_skin");
        String lesson = Files.readString(Path.of("src/main/resources/lessons/painting.md"));
        assertTrue(lesson.contains("Except a creator on the generated list")
                        && lesson.contains("skin and nothing else about them"),
                "lessons/painting.md must carry the exception to 'only public figures' (#1302)");
    }

    @Test
    @DisplayName("#1264: a picture OF a named real thing is looked up first, and a real person is painted from his skin")
    void aPictureOfARealThingIsLookedUpFirst() throws IOException {
        String lesson = Files.readString(Path.of("src/main/resources/lessons/painting.md"));
        for (String must : new String[]{"look_up_character", "from_skin", "Wikipedia article",
                "Never invent a skin", "square board", "never paint from memory"}) {
            assertTrue(lesson.contains(must), "lessons/painting.md lost '" + must + "' — BlockyBob "
                    + "was painted from memory because nothing on the picture path said this (#1264)");
        }
        String paint = new com.moddingfromamod.agent.tools.PaintSurfaceTool().description();
        assertTrue(paint.contains("look_up_character FIRST") && paint.contains("from_skin")
                        && paint.contains("STRETCHED"),
                "paint_surface's description is read on every picture wish; it must say: look up "
                        + "first, a person is their skin, and the picture is stretched over the board");
        String lookUp = new com.moddingfromamod.agent.tools.LookUpCharacterTool().description();
        assertTrue(lookUp.contains("sculpting OR PAINTING") && lookUp.contains("paint_surface"),
                "look_up_character said 'BEFORE sculpt_creature' only, so a picture never called it");
        String worker = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/worker/WorkerPrompt.java"));
        assertTrue(worker.contains("look_up_character FIRST") && worker.contains("from_skin"),
                "the worker prompt (the contributor that painted BlockyBob) must carry it too");
        String resolver = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/reference/CharacterResolver.java"));
        // (D26): the picture phrasing is pinned with a made-up character, which is also the
        // resolver's own rule — decide from how the child refers to it, not from recognising the name.
        assertTrue(resolver.contains("a painting of Captain Comet") && resolver.contains("PERSON: BlockyBob"),
                "the resolver must have picture phrasings and the PERSON kind, or 'a painting of X' "
                        + "reads as an object and nothing is looked up");
    }

    /**
     * (D26): the lessons are public, so their examples name made-up
     * characters, never another company's. What each example steered is pinned instead: a REAL
     * named character still means look it up first, and colours are stated in PROPORTION.
     */
    @Test
    void theLessonsTeachTheLookupWithoutNamingAnotherCompanysCharacter() throws Exception {
        String creature = WishLessons.text("creature").replace("**", "").replaceAll("\\s+", " ");
        String painting = WishLessons.text("painting").replace("**", "").replaceAll("\\s+", " ");
        assertTrue(creature.contains("Call it BEFORE sculpting anything named after a REAL character")
                        && creature.contains("a brand's mascot"),
                "creature.md must still tell the agent a REAL named character is looked up first");
        assertTrue(creature.contains("PROPORTION") && creature.contains("all-green frog knight"),
                "creature.md must still carry the proportions rule and its worked example");
        assertTrue(creature.contains("A mascot's mood is part of who it is"),
                "creature.md must still carry the mascot-mood rule");
        assertTrue(painting.contains("a brand's mascot, or a real person") && painting.contains("even one you are sure you know"),
                "painting.md must still name the kinds of real thing that need the lookup");
        for (String kind : new String[]{"creature", "painting", "building", "datapack"}) {
            String text = WishLessons.text(kind).toLowerCase(java.util.Locale.ROOT);
            // The real names this guard keeps out, spelled in pieces so the guard itself is not a
            // match for the public tree's name scan (tools/leak-scan.py).
            for (String name : new String[]{"bow" + "ser", "son" + "ic", "ron" + "ald", "mcdon" + "ald",
                    "bar" + "ney", "ninten" + "do", "se" + "ga", "mar" + "io", "yo" + "shi"}) {
                assertFalse(text.contains(name), "lessons/" + kind + ".md names '" + name
                        + "' — use a made-up character that teaches the same thing (#2429)");
            }
        }
    }

    @Test
    @DisplayName("the picture lesson names all three limits before the agent can hit them")
    void thePictureLimitsAreToldUpFront() throws java.io.IOException {
        String lesson = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/lessons/painting.md"));
        for (String must : new String[]{"1024 rectangles", "fewer, bigger rectangles",
                "16 by 16 blocks", "hangs on something", "solid backdrop",
                "[\"wishpaint <its id>\"]"}) {
            org.junit.jupiter.api.Assertions.assertTrue(lesson.contains(must),
                    "lessons/painting.md lost '" + must + "' — the agent meets that limit only as a "
                            + "refusal, after it has spent a turn drawing the picture");
        }
    }

    @Test
    @DisplayName("#944: every instinct is explained, companion FOLLOWS, and harmless is 'friendly'")
    void theAgentKnowsCompanionMeansFollowsAndFriendlyMeansHarmless() {
        // Two pickleball players were sculpted "companion, so they don't attack" and walked off
        // their court after the child: the schema explained one instinct of five, as a mascot.
        String instincts = com.moddingfromamod.agent.tools.SculptCreatureTool.INSTINCTS;
        for (String base : new String[]{"wild", "guardian", "hunter", "coward", "companion"}) {
            assertTrue(instincts.contains(base + " "), "the base schema never says what '" + base
                    + "' does");
        }
        assertTrue(instincts.contains("FOLLOWS") && instincts.contains("ONLY when"),
                "the base schema must say companion follows him, and only when he asked");
        assertTrue(instincts.contains("HARMLESS use the 'friendly' power, NOT companion"),
                "the base schema must say harmless is the friendly power, not companion");
        String creature = WishLessons.text("creature");
        assertTrue(creature.contains("HARMLESS is `friendly`; FOLLOWING is `companion`")
                        && creature.contains("`wild` + `friendly`"),
                "the creature lesson must teach friendly-vs-companion (#944)");
        assertTrue(creature.contains("made immune automatically"),
                "the creature lesson must say immunity to its own ability is automatic (#943)");
    }

    @Test
    @DisplayName("#944: define_species shares the same instinct text as sculpt_creature")
    void defineSpeciesExplainsInstinctsToo() throws IOException {
        String define = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/DefineSpeciesTool.java"));
        assertTrue(define.contains("SculptCreatureTool.INSTINCTS"),
                "define_species must explain the instincts with the same words as sculpt_creature");
    }

    @Test
    @DisplayName("#1043: a machine he fills is load_and_pour, taught in the lesson AND the tool")
    void aMachineHeFillsIsLoadAndPour() throws IOException {
        String item = WishLessons.text("item");
        assertTrue(item.contains("`load_and_pour`") && item.contains("MAX") && item.contains("chest"),
                "the item lesson must say a machine he fills is load_and_pour, with its sizes and chest");
        String tool = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/MakeItemTool.java"));
        assertTrue(tool.contains("`load_and_pour`, not turn_into"),
                "make_item's own description must steer a filled machine away from turn_into (#2216: was convert)");
    }

    @Test
    @DisplayName("#2216: turning one thing into another is turn_into, and the SHAPE picks bag or blocks")
    void turnIntoIsOneEffectAndTheShapePicks() throws IOException {
        String item = WishLessons.text("item");
        assertTrue(item.contains("`turn_into`") && item.contains("`FROM`") && item.contains("`TO`"),
                "the item lesson must name turn_into with FROM and TO");
        assertTrue(item.contains("machine` takes one") && item.contains("HELD one changes the block he points at"),
                "the lesson must say the shape decides: a machine is his bag, held aims");
        assertTrue(item.contains("`furnace`") && item.contains("gold ingot"),
                "the lesson must teach furnace and a material's two forms");
        assertTrue(!item.contains("`change_block`") && !item.contains("is `convert`"),
                "the lesson must not teach the two old names as effects to choose between");
    }

    @Test
    @DisplayName("#1044: a block that does something is magic_block, built from its dials")
    void aBlockThatDoesSomethingIsAMagicBlock() throws IOException {
        String item = WishLessons.text("item");
        assertTrue(item.contains("`magic_block`") && item.contains("`stepped_on`")
                        && item.contains("`broken`") && item.contains("`hostile`"),
                "the item lesson must teach magic_block's when/who dials, and that hurting defaults to hostile");
        for (String effect : new String[] {"`pull`", "`fill_blocks`", "`outcome_table`"}) {
            assertTrue(item.contains(effect), "the item lesson never names " + effect);
        }
        assertTrue(item.contains("walk_through") && item.contains("unbreakable"),
                "the item lesson must say what a body can never be");
        String tool = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/MakeItemTool.java"));
        assertTrue(tool.contains("ONLY with shape magic_block"),
                "make_item's schema must describe the `block` dials");
        // a new block with no power is a magic_block with no effect, not a keepsake.
        assertTrue(item.contains("NO effect") && tool.contains("magic_block with NO effect"),
                "the lesson and the schema must say a plain new BLOCK is magic_block with no effect");
    }

    @Test
    @DisplayName("#1052: the agent is told to pick the THING's picture, how `*` and `accent` work, and to log a miss")
    void theIconLibraryIsTaughtWhereTheChoiceIsMade() throws IOException {
        String lesson = WishLessons.text("item");
        String tool = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/MakeItemTool.java"));
        for (String must : java.util.List.of("icon_wanted", "accent", "`*`")) {
            assertTrue(lesson.contains(must), "lessons/item.md no longer teaches " + must);
        }
        assertTrue(tool.contains("\"icon_wanted\"") && tool.contains("\"accent\"")
                        && tool.contains("ItemCatalogue.iconMenu()"),
                "make_item's schema must offer the grouped menu, accent and icon_wanted");
    }

    @Test
    @DisplayName("#1262/#1263: another player is a place to go, never a subject — and no way round a no")
    void anotherPlayerIsAPlaceToGoNeverASubject() {
        String datapack = WishLessons.text("datapack");
        assertTrue(datapack.contains("tp @s <name>") && datapack.contains("execute at <name> run tp @s ~ ~ ~")
                        && datapack.contains("player:<name>"),
                "the datapack lesson must teach the three allowed shapes, verbatim");
        assertTrue(datapack.contains("never build something else") && datapack.contains("I can't reach another player here"),
                "the lesson must say never to build a substitute round a refusal — the live orb was one (#1262)");
        assertTrue(datapack.contains("Teleport them to me\" is refused everywhere")
                        || datapack.contains("\"Teleport them to me\" is refused everywhere"),
                "bringing another player to you stays refused");
        // "Make a map to <player>" is an ITEM wish, so the item lesson is the one it reads.
        String item = WishLessons.text("item");
        assertTrue(item.contains("compass, never a map") && item.contains("player:<name>"),
                "lessons/item.md must turn a map-to-a-player wish into a player compass");
    }

    @Test
    @DisplayName("#1379: a new building and a picture take at — beside/around a building, on its wall")
    void aBuildingAndAPictureTakeAt() throws IOException {
        String building = WishLessons.text("building");
        String painting = WishLessons.text("painting");
        assertTrue(building.contains("`build_structure` with `at`") && building.contains("OUTSIDE that side")
                        && building.contains("CENTRE it there"),
                "lessons/building.md must teach build_structure's at: beside stands outside, around centres");
        assertTrue(painting.contains("`at: \"<building id>:<wall>\"`") && painting.contains("flat patch"),
                "lessons/painting.md must teach a picture on a named building's wall");
        for (String tool : java.util.List.of("BuildStructureTool", "PaintSurfaceTool")) {
            String src = Files.readString(Path.of("src/main/java/com/moddingfromamod/agent/tools/" + tool + ".java"));
            assertTrue(src.contains("Schemas.prop(s, \"at\"") && src.contains("place.At.describe("),
                    tool + " must offer `at` with the one shared description");
        }
    }

    @Test
    @DisplayName("#1379: an event aimed at a named place takes at — top by default, around for the ground beside")
    void anEventAimedAtANamedPlaceTakesAt() throws IOException {
        String event = WishLessons.text("event");
        assertTrue(event.contains("`at: \"<thing>:<spot>\"`") && event.contains("`top`") && event.contains("`around`")
                        && event.contains("camera keeps that building"),
                "lessons/event.md must teach stage_event's at: top lands on it, around beside it");
        String src = Files.readString(Path.of("src/main/java/com/moddingfromamod/agent/tools/StageEventTool.java"));
        assertTrue(src.contains("Schemas.prop(s, \"at\"") && src.contains("place.At.describe("),
                "stage_event must offer `at` with the one shared description");
    }

    @Test
    @DisplayName("#1379: a thing that belongs somewhere takes at — make_item puts it there, run_command runs on it")
    void aThingThatBelongsSomewhereTakesAt() throws IOException {
        String item = WishLessons.text("item");
        assertTrue(item.contains("`at: \"<thing>:<spot>\"`") && item.contains("DO NOT also hand it over")
                        && item.contains("never tidied away"),
                "lessons/item.md must teach make_item's at, not handing it over too, and run_command's at");
        for (String tool : java.util.List.of("MakeItemTool", "RunCommandTool")) {
            String src = Files.readString(Path.of("src/main/java/com/moddingfromamod/agent/tools/" + tool + ".java"));
            assertTrue(src.contains("Schemas.prop(s, \"at\"") && src.contains("place.At.describe("),
                    tool + " must offer `at` with the one shared description");
        }
    }

    @Test
    @DisplayName("#1479: a creature is one piece — parts overlap, and the refusal names the move")
    void aCreatureIsOnePiece() throws IOException {
        String creature = WishLessons.text("creature");
        assertTrue(creature.contains("**overlap**") && creature.contains("NOT SCULPTED YET")
                        && creature.contains("send the same sculpt again unchanged")
                        && creature.contains("carries what hangs on it")
                        && creature.contains("is a new shape"),
                "lessons/creature.md must teach overlapping joints, the refusal, the way past it "
                        + "and that a replaced part carries its attachments");
        String tool = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/SculptCreatureTool.java"));
        assertTrue(tool.contains("NOT SCULPTED YET") && tool.contains("SculptConnectivity.check("),
                "the lesson's words are the tool's words, and the tool runs the check");
    }

    @Test
    @DisplayName("#1379: every building has automatic spots, and every placing tool takes the one shared at")
    void everyBuildingHasSpotsAndEveryPlacingToolTakesAt() throws IOException {
        String building = WishLessons.text("building");
        String creature = WishLessons.text("creature");
        for (String spot : java.util.List.of("`front`", "`top`", "`inside`", "`beside`", "`behind`", "`around`")) {
            assertTrue(building.contains(spot), "lessons/building.md must name the automatic spot " + spot);
        }
        assertTrue(building.contains("at: \"<thing>:<spot>\"") && building.contains("`me`"),
                "lessons/building.md must teach the shared at: a building, a creature or me");
        assertTrue(creature.contains("at: \"me:beside\"") && creature.contains("at: \"castle:top\""),
                "lessons/creature.md must teach the automatic spots and a creature/me as the thing");
        for (String tool : java.util.List.of("SculptCreatureTool", "DefineSpeciesTool")) {
            String src = Files.readString(Path.of("src/main/java/com/moddingfromamod/agent/tools/" + tool + ".java"));
            assertTrue(src.contains("place.At.describe("), tool + " must describe `at` with the one shared line");
        }
    }

    @Test
    @DisplayName("#1355: a creature in a building goes on a named SPOT — marked by build_structure, taken with at")
    void aBossGoesOnHisThroneNeverOnTheAnchor() throws IOException {
        String building = WishLessons.text("building");
        String creature = WishLessons.text("creature");
        assertTrue(building.contains("\"spots\": [{\"name\":\"throne\"") && building.contains("one ABOVE")
                        && building.contains("`front`") && building.contains("anchor is its foundations"),
                "lessons/building.md must teach marking a spot (the form that works: feet one above the "
                        + "seat), the default front, and why never the anchor");
        assertTrue(building.contains("wishspot <building id>:<spot> run") && creature.contains("wishspot castle:roof run summon"),
                "a REAL mob goes on a spot with wishspot, never at hand-worked coordinates — both lessons say so");
        assertTrue(creature.contains("at: \"<building id>:<spot>\"") && creature.contains("wishmob spawn <id> at castle:throne"),
                "lessons/creature.md must teach at:\"<building>:<spot>\" on the tools and the command");
        for (String tool : java.util.List.of("SculptCreatureTool", "DefineSpeciesTool")) {
            String src = Files.readString(Path.of("src/main/java/com/moddingfromamod/agent/tools/" + tool + ".java"));
            assertTrue(src.contains("Schemas.prop(s, \"at\""), tool + " must offer `at`");
        }
        String build = Files.readString(Path.of("src/main/java/com/moddingfromamod/agent/tools/BuildStructureTool.java"));
        assertTrue(build.contains("Schemas.prop(s, \"spots\"") && build.contains("FEET stand in"),
                "build_structure's schema must offer `spots` and say a spot is where the feet go");
    }

    @Test
    @DisplayName("#1378: a roofed room gets lights IN it, and build_structure reports a dark one")
    void aRoofedRoomGetsItsOwnLights() throws IOException {
        String building = WishLessons.text("building");
        String creature = WishLessons.text("creature");
        assertTrue(building.contains("A roofed room gets lights of its own")
                        && building.contains("candle[lit=true]") && building.contains("DARK INSIDE")
                        && building.contains("one level per block"),
                "lessons/building.md must say a roofed room gets lights IN it, the lit-candle form, why "
                        + "a light upstairs does not reach, and what the DARK INSIDE note means");
        assertTrue(building.contains("A spot under a roof needs a light near it"),
                "the building lesson's spots section must say a spot under a roof needs a light");
        assertTrue(creature.contains("A roofed room he sits in gets lights IN it")
                        && creature.contains("DARK INSIDE"),
                "lessons/creature.md's spots section must say his room gets lights");
        String build = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/BuildStructureTool.java"));
        assertTrue(build.contains("DarkInterior.note("),
                "build_structure must report a dark interior back to the agent (#1378)");
    }

    @Test
    @DisplayName("#1340: the AGENT names what a wish is about (plan_wish `about`), and asks only for a destructive pick")
    void theAgentDecidesTheReferent() throws IOException {
        String plan = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/PlanWishTool.java"));
        assertTrue(plan.contains("Schemas.prop(s, \"about\"") && plan.contains("required(s, \"steps\", \"about\")"),
                "plan_wish must take a REQUIRED `about`: the agent names the referent");
        assertTrue(plan.contains("'new'") && plan.contains("a shield") && plan.contains("ask_which"),
                "plan_wish must say a wish naming a new thing is 'new', and when to ask_which");
        String ask = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/AskWhichTool.java"));
        assertTrue(ask.contains("\"ask_which\"") && ask.contains("DESTROY") && ask.contains("never for a wish that makes something NEW"),
                "ask_which must exist and be scoped to a destructive pick");
        String prompt = com.moddingfromamod.worker.WorkerPrompt.text();
        assertTrue(prompt.contains("SAY WHAT IT IS ABOUT") && prompt.contains("facts are not his meaning"),
                "the worker prompt must say the referent is the agent's to decide, from his words");
        assertTrue(WishLessons.text("item").contains("`about: 'new'`"),
                "the item lesson must carry the measured 4-of-4: a new item is 'new'");
        assertTrue(WishLessons.text("building").contains("`ask_which` first"),
                "the building lesson must say a destructive pick between two buildings asks");
        assertTrue(WishLessons.text("creature").contains("`plan_wish`'s `about`"),
                "the creature lesson must say the agent names which creature");
        // a wish that acts on one of his things means one that is OUT in the world.
        assertTrue(WishLessons.text("creature").contains("Only one that is OUT IN THE WORLD")
                        && WishLessons.text("creature").contains("design only"),
                "the creature lesson must say only a creature out in the world can be hit/ridden/removed");
        assertTrue(WishLessons.text("building").contains("marks **standing**"),
                "the building lesson must say only a standing building can be knocked down");
        assertTrue(ask.contains("OUT IN THE WORLD") && ask.contains("'instance'") && ask.contains("'design'")
                        && ask.contains("WITHOUT asking"),
                "ask_which must say it offers only things out in the world for a wish acting on one");
        assertTrue(prompt.contains("OUT IN THE WORLD") && plan.contains("OUT IN THE WORLD"),
                "the worker prompt and plan_wish must say a wish acting on a thing means one out in the world");
        assertTrue(com.moddingfromamod.marshal.WorldCatalogue.OUT_RULE.contains("design only"),
                "the catalogue must say what its out-in-the-world marks mean");
        // And the deleted voice stays deleted: nothing tells the agent the child "SAID SO".
        String service = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/wish/WishService.java"));
        assertFalse(service.contains("He SAID SO") || service.contains("modify_mob with one of them"),
                "the submit note must not resolve the wish for the agent (#1340)");
        assertFalse(Files.exists(Path.of("src/main/java/com/moddingfromamod/wish/WishResolver.java")),
                "the local resolver was deleted (#1340); a decider coming back needs a maintainer's decision");
    }

    @Test
    @DisplayName("#1343: what a wish summons to KEEP passes keep:true, and a test prop never does")
    void keptThingsAreMarkedAndTestPropsAreNot() throws IOException {
        for (String kind : new String[] {"item", "building"}) {
            String lesson = WishLessons.text(kind);
            assertTrue(lesson.contains("`keep: true`") && lesson.contains("test prop")
                            && lesson.contains("leaves it out"),
                    "the " + kind + " lesson must say a kept thing passes keep:true and a test prop does not");
            assertTrue(lesson.contains("TRY the wish on") && lesson.contains("frozen with")
                            && lesson.contains("`CustomName`"),
                    "the " + kind + " lesson must say a thing summoned to try the wish on is a test prop, "
                            + "and that keep cannot keep a frozen mob (a statue stays by its name)");
        }
        var run = new com.moddingfromamod.agent.tools.RunCommandTool();
        var keep = run.inputSchema().getAsJsonObject("properties").getAsJsonObject("keep");
        assertTrue(keep != null && "boolean".equals(keep.get("type").getAsString())
                        && keep.get("description").getAsString().contains("Leave it out for test props"),
                "run_command's schema must offer `keep` and say test props leave it out");
        assertTrue(run.description().contains("unless that call passed keep:true")
                        && run.description().contains("never for a test prop")
                        && run.description().contains("TRY the wish on")
                        && run.description().contains("frozen with NoAI"),
                "run_command's description must say the tidy takes what was not kept, and never to keep a probe");
    }

    @Test
    @DisplayName("#1380: plan_wish rides in the SAME turn as the first lookup, and a teleport needs no plan")
    void thePlanRidesWithTheFirstLookup() throws IOException {
        // Measured on a live server (2026-10-04): a lookup turn before the plan costs ~12 s of the
        // child's wait for "here's my plan". The words have to be where the agent reads them BEFORE
        // its first turn — the worker prompt, the wish brief and plan_wish's own description; the
        // kind lessons ride on the lookup's RESULT, which is a turn too late to say this.
        String prompt = com.moddingfromamod.worker.WorkerPrompt.text();
        assertTrue(prompt.contains("CALL plan_wish IN THE SAME TURN AS YOUR FIRST LOOKUP")
                        && prompt.contains("parallel tool calls"),
                "the worker prompt must say to plan in the same turn as the first lookup");
        // The description is a chain of concatenated literals; join them to read it as the agent does.
        String plan = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/PlanWishTool.java"))
                .replaceAll("\"\\s*\\+\\s*\"", "");
        assertTrue(plan.contains("CALL plan_wish IN THE SAME TURN AS YOUR FIRST LOOKUP")
                        && plan.contains("parallel tool calls"),
                "plan_wish's description must say to call it in the same turn as the first lookup");
        String brief = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/AwaitWishTool.java"));
        assertTrue(brief.contains("IN THE SAME TURN as plan_wish"),
                "the wish brief's conjure_at line must say it goes beside plan_wish, not before it");
        assertFalse(prompt.contains("BEFORE YOU CALL plan_wish, OPEN THE CATALOGUE"),
                "the prompt must not ask for a lookup turn BEFORE the plan any more (#1380)");
        assertTrue(com.moddingfromamod.marshal.PlanFirst.EXEMPT.contains("take_me_to")
                        && !com.moddingfromamod.marshal.PlanFirst.NEEDS_PLAN.contains("take_me_to"),
                "take_me_to moves him, not the world: it is exempt from PlanFirst (#1380)");
    }

    @Test
    @DisplayName("2026-11-10 (#1783): the agent never names a mouse button or a key to a child")
    void itIsToldToNameNoMouseButtonOrKey() {
        // Children play on Java AND on Bedrock phones, tablets and controllers: "right-click" or "press L" means nothing to half of them, and the agent writes
        // item lore and Moddy's lines itself.
        for (String kind : java.util.List.of("item", "creature")) {
            String lesson = WishLessons.text(kind).replace("**", "").replaceAll("\\s+", " ");
            assertTrue(lesson.contains("Name no mouse button and no key")
                            && lesson.contains("Bedrock")
                            && lesson.contains("\"use it\"")
                            && lesson.contains("\"open your wish watch\""),
                    "lessons/" + kind + ".md lost the rule to name no mouse button or key (#1783)");
        }
    }

    /**
     * F1: the creature lesson teaches the kit STYLES and that a bug or a spider gets its legs from
     * ONE leg_row, and sculpt_creature's own schema says the same — so neither can be dropped by a later trim.
     */
    @org.junit.jupiter.api.Test
    void theAgentKnowsKitStylesAndLegRowsForBugs() throws Exception {
        String lesson = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/lessons/creature.md"));
        org.junit.jupiter.api.Assertions.assertTrue(lesson.contains("\"kit\":\"leg_row\""), "the lesson shows leg_row");
        org.junit.jupiter.api.Assertions.assertTrue(lesson.contains("never one leg per corner"), "and why");
        for (String style : java.util.List.of("antler", "unicorn", "ram", "feather", "bat", "butterfly", "dragon",
                "fluffy", "fish", "peacock", "lion")) {
            org.junit.jupiter.api.Assertions.assertTrue(lesson.contains("\"" + style + "\""), "the lesson names style " + style);
        }
        String tool = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/SculptCreatureTool.java"));
        org.junit.jupiter.api.Assertions.assertTrue(tool.contains("'style' picks a look"), "the schema offers style");
        org.junit.jupiter.api.Assertions.assertTrue(tool.contains("leg_row on 'body'"), "the schema teaches leg rows");
    }

    @Test
    void itIsToldNeverToPutAClickableLinkInChat() {
        // a chat link runs as the child who clicks it, and he is permission 0, so the primer's old
        // "[Click for Fireballs]" was dead on Java and a failing pop-up on Bedrock. Hand him the item instead.
        String dp = WishLessons.text("datapack").replace("**", "").replaceAll("\\s+", " ");
        assertTrue(dp.contains("Never put a clickable link in chat")
                        && dp.contains("Hand him the item, or say it in words"),
                "lessons/datapack.md no longer says never to put a clickable link in chat");
    }

    /** idea 2: the main-colour check reaches the agent, and the lessons say what to do with it. */
    @Test
    void theMainColourCheckIsObeyedBeforeTheReveal() throws Exception {
        String creature = WishLessons.text("creature").replace("**", "").replaceAll("\\s+", " ");
        String painting = WishLessons.text("painting").replace("**", "").replaceAll("\\s+", " ");
        assertTrue(creature.contains("`MAIN COLOUR WRONG` from `sculpt_creature` means fix it before he sees it"),
                "creature.md must say what to do when the main-colour check fires");
        assertTrue(painting.contains("says `MAIN COLOUR WRONG`, repaint the same id"),
                "painting.md must say what to do when the main-colour check fires");
        String sculpt = Files.readString(Path.of("src/main/java/com/moddingfromamod/agent/tools/SculptCreatureTool.java"));
        String paint = Files.readString(Path.of("src/main/java/com/moddingfromamod/agent/tools/PaintSurfaceTool.java"));
        assertTrue(sculpt.contains("MainColourCheck.forAgent") && paint.contains("MainColourCheck.forAgent"),
                "both build tools must run the main-colour check");
        assertTrue(sculpt.contains("StandInColour.plan") && paint.contains("StandInColour.plan"),
                "both build tools must keep a named character's main colour themselves (#2526 lever A)");
    }

    @Test
    @DisplayName("#2539: every eye gets a pupil, and BLANK EYES says when one did not")
    void everyEyeGetsAPupil() throws Exception {
        String creature = WishLessons.text("creature").replace("**", "").replaceAll("\\s+", " ");
        assertTrue(creature.contains("Every eye gets a pupil, and a detail stands in front of the face, not in it."),
                "creature.md must say that an eye needs a pupil");
        assertTrue(creature.contains("`⚠ BLANK EYES` from `sculpt_creature` means you forgot"),
                "creature.md must name the critique's blank-eyes line");
        String critique = Files.readString(Path.of("src/main/java/com/moddingfromamod/runtime/SculptCritique.java"));
        assertTrue(critique.contains("⚠ BLANK EYES"), "the sculpt critique must still report blank eyes");
    }

    @Test
    void aCharacterStandsTheWayItIsDrawn() throws Exception {
        // Tabbycat, Snuffy and Scruff-Pup came out on all fours; Pippo Rabbit with a long tail.
        String creature = WishLessons.text("creature").replace("**", "").replaceAll("\\s+", " ");
        assertTrue(creature.contains("A character stands the way it is DRAWN, not the way its animal does."),
                "creature.md must say a character stands as drawn");
        assertTrue(creature.contains("`⚠ STANCE` or `⚠ TAIL` from `sculpt_creature`"),
                "creature.md must name the stance and tail lines");
        String check = Files.readString(Path.of("src/main/java/com/moddingfromamod/reference/ReferenceBodyCheck.java"));
        assertTrue(check.contains("⚠ STANCE") && check.contains("⚠ TAIL"), "the body check must still report stance and tail");
    }

    @Test
    @DisplayName("#101: damage is what its body would have, never 0 to make it gentle; a guard that cannot hit is caught")
    void theCreatureToolsGuideDamageAndCatchAGuardThatCannotHit() throws IOException {
        // In archived sculpts damage was sent 79% of the time and was 0 in 58%: the tool said "Use 0 for a friendly
        // character", and a friendly creature never attacks anyway, so the 0 only took the power from guards and moves.
        for (String tool : new String[] {"SculptCreatureTool", "ModifyMobTool", "DefineSpeciesTool"}) {
            String src = Files.readString(Path.of("src/main/java/com/moddingfromamod/agent/tools/" + tool + ".java"));
            String schema = src.substring(src.indexOf("public JsonObject inputSchema()")).replaceAll("\"\\s*\\+\\s*\"", "");
            assertTrue(!schema.contains("Use 0 for a friendly") && schema.contains("never 0 to make it gentle")
                            && schema.contains("0 lands nothing"),
                    tool + "'s damage line must say a friendly creature never attacks, so never send 0 to make it gentle");
            assertTrue(src.contains("PowerLine.of(") && src.contains("PowerLine.zeroHitFlag("),
                    tool + "'s result must carry the power line and flag a fighter with a hit of 0");
        }
        for (String tool : new String[] {"SculptCreatureTool", "ModifyMobTool"}) {
            String src = Files.readString(Path.of("src/main/java/com/moddingfromamod/agent/tools/" + tool + ".java"));
            assertTrue(src.contains("\"armor\"") && src.contains("\"knockback_resistance\""),
                    tool + " must take armor and knockback_resistance (a shell, a heavy body), as define_species does");
        }
        // the lesson keeps no line telling it to zero a friendly creature's damage
        String creature = WishLessons.text("creature").replace("**", "").replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
        assertTrue(!creature.contains("damage 0") && !creature.contains("0 damage") && !creature.contains("damage: 0"),
                "lessons/creature.md must not tell it to give a creature 0 damage");
    }
}
