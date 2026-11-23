package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.moddingfromamod.reference.CreatorAccounts;
import com.moddingfromamod.reference.PublicFigure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * a real person the public-figure gate closed, but whom the GENERATED
 * creator list links to an account, is told to paint that creator's own skin — and nothing else.
 */
class LookUpCharacterToolTest {

    private static final String ROW = "{\"v\":1,\"rank\":182,\"creator\":\"Block Facts\","
            + "\"aliases\":[\"Block Facts\"],\"rule\":\"A1:one-curated-field\","
            + "\"name\":\"Block_Facts\",\"uuid\":\"0123456789abcdef0123456789abcdef\","
            + "\"claims\":[{\"source\":\"mcc.fandom\",\"page\":\"Block Facts\","
            + "\"url\":\"https://mcchampionship.fandom.com/wiki/Block_Facts\",\"revision\":1,"
            + "\"field\":\"ign\",\"value\":\"Block_Facts\",\"names\":[\"Block_Facts\"]}]}";

    @AfterEach
    void reset() {
        CreatorAccounts.reset();
        PublicFigure.reset();
    }

    @Test
    @DisplayName("a creator only the list maps is told to paint their own skin with from_skin")
    void aListedCreatorIsToldToPaintTheirSkin() {
        CreatorAccounts.useRows(List.of(ROW));
        AtomicInteger asked = new AtomicInteger();
        PublicFigure.useOracle(n -> {
            asked.incrementAndGet();
            return PublicFigure.Verdict.NO_ARTICLE;
        });
        String a = LookUpCharacterTool.privatePersonAnswer("block facts");
        assertTrue(a.contains("from_skin = \"block facts\""), a);
        assertTrue(a.contains("OWN SKIN") && a.contains("NEVER a username"), a);
        assertTrue(a.contains("leave 'picture' out"), a);
        assertFalse(a.contains("not a public figure"), a);
        assertFalse(a.contains("Block_Facts"), "the agent is never handed the username: " + a);
        assertEquals(0, asked.get(), "the list licenses the skin; it asks nobody whether he is public");
    }

    @Test
    @DisplayName("a private person the list does not map still gets nothing looked up and no skin")
    void anUnlistedPrivatePersonIsUnchanged() {
        CreatorAccounts.useRows(List.of(ROW));
        String a = LookUpCharacterTool.privatePersonAnswer("Jake");
        assertTrue(a.contains("not a public figure") && a.contains("no skin is fetched"), a);
        assertFalse(a.contains("from_skin"), a);
    }

    @Test
    @DisplayName("the listed-creator answer offers only the skin: no lookup, no photograph, no 'public figure'")
    void theListLicensesOnlyTheSkin() {
        String a = LookUpCharacterTool.listedCreatorAnswer("Block Facts");
        assertFalse(a.contains("public figure"), a);
        assertTrue(a.contains("nothing else about them is looked up"), a);
        assertTrue(a.contains("never draw their face"), a);
    }
}
