package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code /moddy credits} names the SkyBlock packs exactly when the world runs them. */
class CreditsTest {

    @Test
    void aWorldWithTheSkyBlockPacksCreditsThemWithTheirLicence() {
        List<String> lines = Credits.lines(List.of("vanilla", "mod_data",
                "file/skyvoid_standard_skyblock_v2_0_4-MC_1_21.zip"));
        assertEquals(2, lines.size(), lines.toString());
        String sky = lines.get(1);
        assertTrue(sky.contains("BluePsychoRanger") && sky.contains("MIT")
                && sky.contains("github.com/BPR02/SkyBlock_Collection"), sky);
    }

    @Test
    void aWorldDoesNotCreditPacksItDoesNotHave() {
        assertEquals(List.of(Credits.MODDY_LINE), Credits.lines(List.of("vanilla", "mod_data",
                "file/tiny_cookie_grow.zip")));
        assertEquals(List.of(Credits.MODDY_LINE), Credits.lines(null));
    }

    @Test
    void creditsIsAVerbTheHelpLineSays() {
        assertTrue(ChildCommands.VERBS.contains(ChildCommands.CREDITS));
        assertTrue(ChildCommands.helpLine().contains("credits"), ChildCommands.helpLine());
    }
}
