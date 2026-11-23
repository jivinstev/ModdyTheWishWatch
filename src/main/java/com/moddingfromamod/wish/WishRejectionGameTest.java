package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.Optional;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * Gate B for un-teaching a wish.
 *
 * <p>The pure policy is covered by {@code WishRejectionTest}; what only a running server can prove
 * is the part that would fail <i>silently</i>: that forgetting a template actually rebuilds the
 * active set (so the bad answer stops being served), and that
 * it's really gone from disk rather than returning on the next world load.
 *
 * <p>Deliberately driven without a mock player. {@code makeMockServerPlayerInLevel} places a player
 * into the player list, which fires our species-sync payload at an embedded channel — a known way
 * to make a GameTest fail for reasons unrelated to what it's testing.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class WishRejectionGameTest {

    /**
     * A learned template that answers a wish badly.
     *
     * <p>Was a learned copy hijacking the built-in "treasure" deleted the built-ins.
     * The library outranks a learned template, so the word is one no starter uses: there is
     * nothing underneath it, and forgetting it must leave the wish to the agent.
     */
    private static final String BAD_CHICKENS = """
        {"id":"bad_chickens","all":["gtspidertreasure"],
         "commands":["summon minecraft:cave_spider ~ ~2 ~"],
         "say":"Here is your treasure! (it is spiders)"}
        """;

    private static final String WISH = "give me gtspidertreasure";

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static String matchedId(String wish) {
        Optional<WishTemplates.Match> m =
                WishCatalog.templates().matchDetailed(WishText.sanitize(wish));
        return m.map(WishTemplates.Match::templateId).orElse("<no match>");
    }

    /**
     * <b>The headline.</b> Forgetting a learned template must take it out of the LIVE set, not only
     * off disk — or "that's not right" would keep serving the same wrong answer until a restart.
     */
    @GameTest(template = "empty_test")
    public static void forgettingALearnedTemplateTakesItOutOfTheLiveSet(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        LearnedTemplates.load(server);

        LearnedTemplates.addAndSave(server, json(BAD_CHICKENS));
        helper.assertTrue(LearnedTemplates.isLearned("bad_chickens"), Component.literal("it should be learned"));
        helper.assertTrue("bad_chickens".equals(matchedId(WISH)),
                Component.literal("the learned template must serve its own words, got " + matchedId(WISH)));

        helper.assertTrue(LearnedTemplates.forget(server, "bad_chickens"),
                Component.literal("forgetting a learned template should report success"));

        helper.assertFalse(LearnedTemplates.isLearned("bad_chickens"), Component.literal("it should be gone"));
        helper.assertTrue("<no match>".equals(matchedId(WISH)),
                Component.literal("nothing may answer the forgotten words offline, got " + matchedId(WISH)));
        helper.succeed();
    }

    /** Forgetting must survive a world reload, or the bad answer returns tomorrow. */
    @GameTest(template = "empty_test")
    public static void aForgottenTemplateStaysForgottenAfterReload(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        LearnedTemplates.load(server);
        LearnedTemplates.addAndSave(server, json(BAD_CHICKENS));
        LearnedTemplates.forget(server, "bad_chickens");

        LearnedTemplates.load(server); // exactly what happens on the next world load

        helper.assertFalse(LearnedTemplates.isLearned("bad_chickens"),
                Component.literal("a forgotten template must not come back from disk"));
        helper.assertTrue("<no match>".equals(matchedId(WISH)),
                Component.literal("and it must not be served after the reload, got " + matchedId(WISH)));
        helper.succeed();
    }

    /**
     * A library template (the starter set) is the server's, changed by a library push, so it cannot
     * be forgotten from a game. Rejecting one undoes the wish — it must never
     * delete the recipe from under every other child.
     */
    @GameTest(template = "empty_test")
    public static void libraryTemplatesCannotBeForgotten(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        LearnedTemplates.load(server);
        String starter = "starter_pet_dragon";
        helper.assertTrue(WishCatalog.templates().byId(starter, "").isPresent(),
                Component.literal("the fixture starter is not in the live set"));

        helper.assertFalse(LearnedTemplates.forget(server, starter),
                Component.literal("a library template is not a learned template and must not be removable"));
        helper.assertFalse(LearnedTemplates.forget(server, ""), Component.literal("an empty id is not a template"));
        helper.assertFalse(LearnedTemplates.forget(server, null), Component.literal("null must be survivable"));
        helper.assertTrue(WishCatalog.templates().byId(starter, "").isPresent(),
                Component.literal("the starter must still be served"));
        helper.succeed();
    }
}
