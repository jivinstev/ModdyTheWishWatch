package com.moddingfromamod.datapack;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.wish.LearnedTemplates;
import com.moddingfromamod.wish.ReplayContract;
import com.moddingfromamod.wish.WishCatalog;
import com.moddingfromamod.wish.WishText;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Gate B — a datapack ACTION wish is a real remembered button, and the contract holds.</b>
 *
 * <p>Two things, deterministically and with no agent:
 * <ul>
 *   <li>The five obligations of {@link ReplayContract} still hold with datapacks added — i.e.
 *       {@code /wishdatapack} is registered and allowlisted and {@code list_datapacks} is reachable.
 *       (The rule-vs-action nuance relaxes only obligation 2, which this list-level check never
 *       required per-wish anyway.)</li>
 *   <li>A taught datapack ACTION template (row 2, "give me fireballs") becomes a button that serves
 *       its EXACT recipe by id — the same servability trains and creatures must have.</li>
 * </ul>
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class DatapackReuseGameTest {

    /** Datapacks did not break the replay contract for any category. */
    @GameTest(template = "empty_test")
    public static void replayContractStillHoldsWithDatapacks(GameTestHelper helper) {
        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
        List<String> problems = ReplayContract.violations(dispatcher);
        helper.assertTrue(problems.isEmpty(),
                Component.literal("replay contract broken:\n  - " + String.join("\n  - ", problems)));
        // And the datapack entry specifically is the flagged auto-load exception.
        boolean flagged = ReplayContract.ALL.stream()
                .anyMatch(r -> r.feature().equals("datapacks") && r.reuseByAutoLoad());
        helper.assertTrue(flagged, Component.literal("datapacks must be the reuseByAutoLoad exception"));
        helper.succeed();
    }

    /** Row 2: an ACTION datapack template is served from its own recipe by id (an instant button). */
    @GameTest(template = "empty_test")
    public static void datapackActionWishIsAServableRememberedButton(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        List<String> saved = new ArrayList<>(LearnedTemplates.savedJsonForTest());
        try {
            // Exactly what author_datapack teaches for "give me fireballs".
            JsonObject t = JsonParser.parseString(
                    "{\"id\":\"gd_dp_fireball\",\"all\":[\"gdfireball\"],"
                    + "\"commands\":[\"wishdatapack gd_dp_fireball give\"],\"say\":\"here\"}")
                    .getAsJsonObject();
            LearnedTemplates.addAndSave(server, t);

            String buttonText = null;
            String buttonId = null;
            for (WishCatalog.Preset p : LearnedTemplates.asPresets()) {
                if (p.wishText().contains("gdfireball")) {
                    buttonText = p.wishText();
                    buttonId = p.templateId();
                }
            }
            helper.assertTrue(buttonText != null, Component.literal("the taught datapack template produced no button"));
            helper.assertTrue(buttonId != null && !buttonId.isBlank(),
                    Component.literal("the button carries no recipe id, so a press could only be re-matched from its label"));

            String clean = WishText.sanitize(buttonText);
            helper.assertTrue(!WishCatalog.templates().byId(buttonId, clean).isEmpty(),
                    Component.literal("pressing the remembered datapack button must resolve to its own recipe, "
                    + "not escalate to the agent every time"));
        } finally {
            LearnedTemplates.restoreForTest(saved, server);
        }
        helper.succeed();
    }

    /**
     * The Gate D regression, pinned cheaply: with clean single-word keywords (what author_datapack
     * now normalises to), TYPING the button's own words matches its template offline — so pressing
     * or re-typing "sparkle bomb" replays instantly instead of waking the agent. Gate D found this by
     * failing when the agent had passed the phrases "sparkle bomb"/"sparklebomb" as terms.
     */
    @GameTest(template = "empty_test")
    public static void typingADatapackButtonsWordsServesItOffline(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        List<String> saved = new ArrayList<>(LearnedTemplates.savedJsonForTest());
        try {
            JsonObject t = JsonParser.parseString(
                    "{\"id\":\"gd_dp_sparkle\",\"all\":[\"sparkle\",\"bomb\"],"
                    + "\"commands\":[\"wishdatapack gd_dp_sparkle give\"],\"say\":\"here\"}")
                    .getAsJsonObject();
            LearnedTemplates.addAndSave(server, t);

            String clean = WishText.sanitize("sparkle bomb");
            helper.assertTrue(!WishCatalog.templates().match(clean).isEmpty(),
                    Component.literal("typing the datapack button's words must match its template offline — the exact "
                    + "case that escalated in Gate D when the terms were phrases"));
        } finally {
            LearnedTemplates.restoreForTest(saved, server);
        }
        helper.succeed();
    }
}
