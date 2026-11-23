package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.testing.GateShards;

/**
 * <b>A taught wish must be servable by its own button.</b>
 *
 * <p>{@link LearnedTemplates#addAndSave} already claims to verify this — it probes the template with
 * its own {@code all} terms joined and reports "Verified: the watch now grants it instantly." <b>The
 * probe checks the wrong thing.</b> It asks the matcher directly, but a button press goes through
 * {@link WishService#submit}, which consults two gates <i>before</i> the cache:
 *
 * <pre>
 *   boolean question   = WishIntent.isQuestion(text);
 *   boolean contextual = WishReference.isContextual(text);
 *   matched = (question || contextual) ? empty : route(text, source);
 * </pre>
 *
 * <p>So a template whose terms happen to include a back-reference — {@code again}, {@code another},
 * {@code more}, {@code same}, {@code instead}, {@code back}, {@code undo}, any one of which makes a
 * wish contextual <b>on its own</b> — is reported as verified, appears as a button, and then
 * escalates to the agent every single time it is pressed. From the outside that is indistinguishable
 * from an agent that forgot to teach it: the recipe is there, the button is there, and the child
 * still waits.
 *
 * <p>This is exactly the shape Gate D caught on a train: {@code source=PRESET status=DONE} with an
 * <b>empty templateId</b> — granted, but not from its own recipe.
 *
 * <p>Cheap, deterministic, and needs no agent, so the invariant is checked on every build rather
 * than once every ten-minute client run.
 */
@GameTestHolder(GateShards.CI)
public class TemplateServabilityGameTest {

    /**
     * Every shipped button must work offline. Since the hard-coded built-ins were deleted
     * the shipped buttons ARE the library's — the starter set every install has — and a library
     * button names its recipe, so a press is served by id ({@link WishService#submit}'s first
     * branch), never re-matched from its label. If these break, the watch's floor is gone.
     */
    @GameTest(template = "empty_test")
    public static void everyLibraryButtonIsServableFromTheCache(GameTestHelper helper) {
        List<String> broken = new ArrayList<>();
        var buttons = com.moddingfromamod.library.WishLibrary.asPresets();
        if (buttons.isEmpty()) {
            helper.fail(Component.literal("the library has no buttons at all — the starter library did not load"));
            return;
        }
        for (WishCatalog.Preset p : buttons) {
            String clean = WishText.sanitize(p.wishText());
            if (p.templateId() == null || p.templateId().isBlank()) {
                broken.add("'" + p.label() + "': carries no recipe id");
            } else if (WishCatalog.templates().byId(p.templateId(), clean).isEmpty()) {
                broken.add("'" + p.label() + "' (" + p.templateId() + "): its recipe is not in "
                        + "the live template set");
            }
        }
        if (!broken.isEmpty()) {
            helper.fail(Component.literal("shipped buttons that would escalate to the agent instead of being "
                    + "instant:\n  - " + String.join("\n  - ", broken)));
            return;
        }
        helper.succeed();
    }

    /**
     * <b>THE FIX, and its guardrail.</b>
     *
     * <p>A template is taught whose terms read as contextual — the exact case that used to make a
     * button escalate to the agent on every press. Two things must now be true at once, and they
     * pull in opposite directions:
     *
     * <ul>
     *   <li><b>Pressing the button works</b>, because the button names its recipe by id and a
     *       remembered button is not a guess at what the child meant.</li>
     *   <li><b>Typing those same words still does not match</b>, because loosening the matcher is
     *       how "Create a Spikor's airship" ends up granting "you can fly". The fix must not buy
     *       reliability with permissiveness.</li>
     * </ul>
     */
    @GameTest(template = "empty_test")
    public static void aRememberedButtonServesItsExactRecipeWithoutLooseningMatching(
            GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        List<String> saved = new ArrayList<>(LearnedTemplates.savedJsonForTest());
        try {
            JsonObject t = JsonParser.parseString("""
                    {"id":"gd_probe_train",
                     "all":["mcburgers","train","again"],
                     "commands":["wishbuild gd_probe_train"]}
                    """).getAsJsonObject();
            LearnedTemplates.addAndSave(server, t);

            String buttonText = null;
            String buttonId = null;
            for (WishCatalog.Preset p : LearnedTemplates.asPresets()) {
                if (p.wishText().contains("mcburgers")) {
                    buttonText = p.wishText();
                    buttonId = p.templateId();
                }
            }
            if (buttonText == null) {
                helper.fail(Component.literal("the taught template produced no button at all"));
                return;
            }
            if (buttonId == null || buttonId.isBlank()) {
                helper.fail(Component.literal("the button carries no recipe id, so a press can only be re-matched "
                        + "from its label — which is the bug this test exists for"));
                return;
            }

            String clean = WishText.sanitize(buttonText);
            // 1. The press serves the exact recipe.
            if (WishCatalog.templates().byId(buttonId, clean).isEmpty()) {
                helper.fail(Component.literal("pressing the remembered button '" + buttonId + "' does not resolve to "
                        + "its own recipe, so it would escalate to the agent every time"));
                return;
            }
            // 2. And we did NOT get there by making matching sloppier: these words are still
            //    contextual, and typing them still refuses to hit the cache.
            boolean stillGated = WishIntent.isQuestion(clean) || WishReference.isContextual(clean);
            if (!stillGated) {
                helper.fail(Component.literal("the contextual gate no longer fires on \"" + clean + "\" — matching "
                        + "was loosened, which is exactly the trade this fix was supposed to avoid"));
                return;
            }
            System.out.println("TEMPLATE SERVABILITY: button '" + buttonId + "' serves its exact "
                    + "recipe, while typing its words is still (correctly) refused by the cache");
            helper.succeed();
        } finally {
            LearnedTemplates.restoreForTest(saved, server);
        }
    }
}
