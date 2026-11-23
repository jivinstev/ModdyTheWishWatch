package com.moddingfromamod.wishbuilder;

import static com.moddingfromamod.wishbuilder.BuilderSpecTest.j;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** a kept Builder make is a recipe, "builder:<make id>", resolved on his own shelf to its thing's replay command. */
class BuilderAgainTest {

    static final String KID = BuilderSpecStoreTest.KID;
    static final String BROTHER = "00000000-0000-0000-0000-000000000def";

    @Test
    void theRecipeIdRoundTripsAndATaughtIdIsNotOne() {
        assertEquals("builder:cat_ab12cd", BuilderAgain.recipeId("cat_ab12cd"));
        assertEquals("cat_ab12cd", BuilderAgain.makeIdOf("builder:cat_ab12cd"));
        assertTrue(BuilderAgain.is("builder:cat_ab12cd"));
        assertFalse(BuilderAgain.is("builder:"));
        assertFalse(BuilderAgain.is("castle_wish"));
        assertFalse(BuilderAgain.is("oasis@bar~1f2e3d4c"));
        assertFalse(BuilderAgain.is(null));
        assertNull(BuilderAgain.makeIdOf("castle_wish"));
    }

    @Test
    void everyKindIsMadeAgainByTheReplayCommandOfWhatItSaved() {
        assertEquals("wishmob spawn cat_1", BuilderAgain.command("creature", "cat_1", ""));
        assertEquals("wishmob spawn horse_1", BuilderAgain.command("ride", "horse_1", ""));
        assertEquals("wishdatapack zap_wand give", BuilderAgain.command("item", "zap_1", "zap_wand"));
        assertEquals("wishdatapack zap_1 give", BuilderAgain.command("item", "zap_1", ""));
        assertEquals("wishdatapack pizza give", BuilderAgain.command("machine", "pz_1", "pizza"));
        assertEquals("wishdatapack jump give", BuilderAgain.command("magic_block", "jb_1", "jump"));
        assertEquals("wishbuild fort_1", BuilderAgain.command("building", "fort_1", ""));
        assertEquals("wishpaint pic_1", BuilderAgain.command("picture", "pic_1", ""));
        assertEquals("moddyevent stage rain_1", BuilderAgain.command("event", "rain_1", ""));
        // a rule is on for the whole world already; supplies are used up (and never kept)
        assertNull(BuilderAgain.command("rule", "r_1", ""));
        assertNull(BuilderAgain.command("supplies", "s_1", ""));
        assertNull(BuilderAgain.command("nonsense", "x", ""));
        assertNull(BuilderAgain.command("creature", "", ""));
    }

    @Test
    void everyReplayCommandIsOneTheReplayContractKnows() {
        for (BuilderSpec.Kind k : BuilderSpec.Kind.values()) {
            String c = BuilderAgain.command(k.id(), "demo_id", "");
            if (c == null) {
                continue;
            }
            String root = c.substring(0, c.indexOf(' '));
            assertTrue(com.moddingfromamod.wish.ReplayContract.ALL.stream().anyMatch(r -> r.command().equals(root)),
                    k + " replays with '" + root + "', which ReplayContract.ALL does not list");
        }
    }

    @Test
    void eachExampleKindResolvesToItsNameAndOneCommand() {
        for (String spec : BuilderExamples.ONE_PER_KIND) {
            BuilderSpec s = BuilderSpec.read(j(spec));
            String id = s.kind().id() + "_ab12cd";
            Map<String, BuilderSpecStore.Made> mine = Map.of(id, made(id, id, s));
            BuilderAgain.Resolved r = BuilderAgain.resolve(mine, BuilderAgain.recipeId(id));
            if (BuilderAgain.command(s.kind().id(), id, "") == null) {
                assertFalse(r.ok(), s.kind() + " is not made again");
                assertEquals("", BuilderAgain.recipeFor(mine, id, ""), s.kind() + " carries no recipe");
                continue;
            }
            assertTrue(r.ok(), s.kind() + ": " + r.refusal());
            assertEquals(1, r.commands().size());
            assertEquals(BuilderLowering.label(s), r.words());
            assertFalse(r.words().startsWith(BuilderMake.TEXT_PREFIX), "a replay never reads as a Builder make");
            assertEquals(BuilderWords.ready(s), r.ready());
            assertEquals(BuilderAgain.recipeId(id), BuilderAgain.recipeFor(mine, id, ""));
        }
    }

    @Test
    void aMakeAWishChangedSinceIsStillMadeAgainAsItIsNow() {
        BuilderSpec s = BuilderSpec.read(j(BuilderExamples.ONE_PER_KIND.get(0)));
        BuilderSpecStore.Made touched = new BuilderSpecStore.Made("cat_1", "cat_1", s.kind().id(), s.toJson(), "w", 1L,
                "", "wish-9");
        Map<String, BuilderSpecStore.Made> mine = Map.of("cat_1", touched);
        assertTrue(BuilderAgain.resolve(mine, "builder:cat_1").ok());
        assertEquals("builder:cat_1", BuilderAgain.recipeFor(mine, "cat_1", "Cat"));
    }

    @Test
    void anotherChildsIdAndAGoneOneAreRefused(@TempDir Path root) throws Exception {
        BuilderSpec s = BuilderSpec.read(j(BuilderExamples.ONE_PER_KIND.get(0)));
        BuilderSpecStore.put(root, BROTHER, made("his_cat", "his_cat", s));
        Map<String, BuilderSpecStore.Made> mine = BuilderSpecStore.all(root, KID);
        assertFalse(BuilderAgain.resolve(mine, "builder:his_cat").ok(), "his brother's make is not his recipe");
        assertTrue(BuilderAgain.resolve(BuilderSpecStore.all(root, BROTHER), "builder:his_cat").ok());
        assertFalse(BuilderAgain.resolve(mine, "builder:never_made").ok());
        assertFalse(BuilderAgain.resolve(mine, "castle_wish").ok());
    }

    @Test
    void aSpecOrAShelfANewerModdyWroteIsRefusedNotReadAsEmpty(@TempDir Path root) throws Exception {
        BuilderSpec s = BuilderSpec.read(j(BuilderExamples.ONE_PER_KIND.get(0)));
        JsonObject newer = s.toJson().deepCopy();
        newer.addProperty("builder", BuilderSpec.CURRENT + 1);
        Map<String, BuilderSpecStore.Made> mine = Map.of("cat_1",
                new BuilderSpecStore.Made("cat_1", "cat_1", "creature", newer, "w", 1L));
        BuilderAgain.Resolved r = BuilderAgain.resolve(mine, "builder:cat_1");
        assertFalse(r.ok());
        assertTrue(r.refusal().contains("newer"), r.refusal());

        // a shelf file a newer Moddy wrote: the store refuses it, so there is nothing to make again
        BuilderSpecStore.put(root, KID, made("cat_1", "cat_1", s));
        Path f = BuilderSpecStore.file(root, KID);
        Files.writeString(f, Files.readString(f).replaceFirst("\"v\"\\s*:\\s*1", "\"v\": 99"));
        assertTrue(BuilderSpecStore.all(root, KID).isEmpty());
        assertFalse(BuilderAgain.resolve(BuilderSpecStore.all(root, KID), "builder:cat_1").ok());
    }

    @Test
    void aTileFindsItsMakeByIdByItemIdOrByTheNameItWasMadeUnder() {
        BuilderSpec s = BuilderSpec.read(j(BuilderExamples.ONE_PER_KIND.get(0)));
        Map<String, BuilderSpecStore.Made> mine = new LinkedHashMap<>();
        mine.put("cat_1", made("cat_1", "cat_1", s));
        mine.put("zap_1", new BuilderSpecStore.Made("zap_1", "Zappy Wand", "item", s.toJson(), "w", 1L, "zappy_wand", ""));
        assertEquals("cat_1", BuilderAgain.keptFor(mine, "cat_1", "anything").id());
        assertEquals("zap_1", BuilderAgain.keptFor(mine, "id:zappy_wand", "").id());
        assertEquals("zap_1", BuilderAgain.keptFor(mine, "zappy_wand", "").id());
        assertEquals("zap_1", BuilderAgain.keptFor(mine, "something_else", "Zappy Wand").id());
        assertNull(BuilderAgain.keptFor(mine, "dog_1", "Dog"));
        assertNotNull(BuilderAgain.keptFor(mine, "cat_1", ""));
        assertEquals(List.of(), BuilderAgain.resolve(Map.of(), "builder:x").commands());
    }

    private static BuilderSpecStore.Made made(String id, String ref, BuilderSpec s) {
        return new BuilderSpecStore.Made(id, ref, s.kind().id(), s.toJson(), "wish-1", 1000L);
    }
}
