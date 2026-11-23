package com.moddingfromamod.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/**
 * <b>: a username comes only from a page that LINKS it to the person.</b> The table is real
 * minecraft.wiki API answers ({@code src/test/resources/minecraft-wiki/README.md}), one per infobox
 * shape the parser has to survive.
 */
class MinecraftWikiPersonTest {

    @AfterEach
    void reset() {
        MinecraftWikiPerson.reset();
        PublicFigure.reset();
    }

    static String fixture(String page) {
        try (InputStream in = MinecraftWikiPersonTest.class
                .getResourceAsStream("/minecraft-wiki/" + page + ".json")) {
            if (in == null) {
                throw new IllegalStateException("no fixture " + page);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @ParameterizedTest(name = "{0} -> {2} ({3})")
    @CsvSource({
            // fixture,      asked as,        username,      field,            page landed on
            "BigB,           BigB,            bigbst4tz2,    minecraft_name,   BigB",
            "Smajor,         Smajor,          Smajor1995,    minecraft_name,   Smajor",
            "Shubble,        Shubble,         ShubbleYT,     minecraft_name,   Shubble",
            // the whole infobox on one line
            "Tubbo,          Tubbo,           Tubbo_,        minecraft_name,   Tubbo",
            // two namemc links on two lines: the label, case kept, of the FIRST
            "Camman18,       Camman18,        camman18YT,    minecraft_name,   Camman18",
            // minecraft_names: '[namemc LDShadowLady] ({{JE}})' then 'PrincessWifi ({{BE}})'
            "LDShadowLady,   LDShadowLady,    LDShadowLady,  minecraft_names,  LDShadowLady",
            // six unmarked mine.ly links, comma-separated: the first
            "Technoblade,    Technoblade,     Technoblade,   minecraft_names,  Technoblade",
    })
    @DisplayName("a linked Java name is read from every real infobox shape")
    void readsALinkedJavaName(String fixture, String asked, String username, String field,
                              String page) {
        var r = MinecraftWikiPerson.parse(asked, fixture(fixture));
        var linked = assertInstanceOf(MinecraftWikiPerson.Linked.class, r, String.valueOf(r));
        assertEquals(username, linked.username());
        assertEquals(field, linked.field());
        assertEquals(page, linked.page());
        assertTrue(linked.revision() > 3_000_000, "the revision id is kept: " + linked.revision());
        assertEquals(asked, linked.publicName());
    }

    @Test
    @DisplayName("Stampy lists only an Xbox gamertag, so he is UNMAPPED — never 'Stampy', never a guess")
    void aGamertagIsNotAJavaProfile() {
        var r = MinecraftWikiPerson.parse("Stampy", fixture("Stampy"));
        var u = assertInstanceOf(MinecraftWikiPerson.Unmapped.class, r, String.valueOf(r));
        assertEquals(MinecraftWikiPerson.Why.NO_JAVA_NAME, u.why());
        assertEquals("Stampy", u.page());
    }

    @Test
    @DisplayName("a redirect is followed: 'Joseph Garrett' lands on Stampy's page and reads it")
    void aRedirectLandsOnThePersonsPage() {
        var r = MinecraftWikiPerson.parse("Joseph Garrett", fixture("Joseph_Garrett"));
        var u = assertInstanceOf(MinecraftWikiPerson.Unmapped.class, r, String.valueOf(r));
        assertEquals("Stampy", u.page(), "read the page the redirect lands on");
        assertEquals(MinecraftWikiPerson.Why.NO_JAVA_NAME, u.why());
    }

    @Test
    @DisplayName("a person page with no name field, no page, and a page that is not a person: all unmapped")
    void noFieldNoPageNotAPerson() {
        assertEquals(MinecraftWikiPerson.Why.NO_JAVA_NAME,
                ((MinecraftWikiPerson.Unmapped) MinecraftWikiPerson.parse("Grian",
                        fixture("Grian"))).why());
        assertEquals(MinecraftWikiPerson.Why.NO_PAGE,
                ((MinecraftWikiPerson.Unmapped) MinecraftWikiPerson.parse("SethBling",
                        fixture("SethBling"))).why());
        assertEquals(MinecraftWikiPerson.Why.NOT_A_PERSON,
                ((MinecraftWikiPerson.Unmapped) MinecraftWikiPerson.parse("Creeper",
                        fixture("Creeper"))).why());
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(delimiter = '§', quoteCharacter = '"', value = {
            "stampylongnose (Xbox Gamertag)§",
            "PrincessWifi ({{BE}})§",
            "SomeName (Bedrock Edition)§",
            "[https://namemc.com/profile/ldshadowlady LDShadowLady] ({{JE}})§LDShadowLady",
            "[https://mine.ly/NoHaxJustCanFly NoHaxJustCanFly]§NoHaxJustCanFly",
            "[https://namemc.com/profile/Dinnerbone]§Dinnerbone",
            "'''jeb_'''§jeb_",
            "Notch ({{JE}})§Notch",
            "not a username at all§",
            "{{SMCharacterLink|Anthony}}§",
    })
    @DisplayName("one entry: another edition is dropped, link markup is read, junk is no name")
    void oneEntry(String entry, String expected) {
        assertEquals(expected, MinecraftWikiPerson.javaUsername(entry));
    }

    @Test
    @DisplayName("#1302 item 3: a minecraft.wiki LINK makes a name public; a person page without one does not")
    void aLinkIsAnOutsideFact() {
        var linked = new MinecraftWikiPerson.Linked("Smajor", "Smajor", 1L, "minecraft_name",
                "Smajor1995");
        var unmapped = new MinecraftWikiPerson.Unmapped("Grian", MinecraftWikiPerson.Why.NO_JAVA_NAME,
                "Grian");
        assertEquals(PublicFigure.Verdict.PUBLIC,
                PublicFigure.combine(PublicFigure.Verdict.NO_ARTICLE, linked));
        assertEquals(PublicFigure.Verdict.NO_ARTICLE,
                PublicFigure.combine(PublicFigure.Verdict.NO_ARTICLE, unmapped));
        assertEquals(PublicFigure.Verdict.UNREACHABLE, PublicFigure.combine(
                PublicFigure.Verdict.NO_ARTICLE, new MinecraftWikiPerson.Unreachable("X")),
                "a wiki that cannot be asked fails closed");
        assertEquals(PublicFigure.Verdict.PUBLIC,
                PublicFigure.combine(PublicFigure.Verdict.PUBLIC, unmapped));
    }

    @Test
    @DisplayName("an answer that is not an answer is null (asked again), never 'unmapped'")
    void notAnAnswer() {
        assertNull(MinecraftWikiPerson.parse("X", "You are making too many requests."));
        assertNull(MinecraftWikiPerson.parse("X", "<html>"));
        assertNull(MinecraftWikiPerson.parse("X", "{\"error\":{}}"));
        assertNull(MinecraftWikiPerson.parse("X", null));
    }

    @Test
    @DisplayName("the request asks for content AND the revision id, both title spellings, redirects followed")
    void theRequest() {
        String url = MinecraftWikiPerson.queryUrl("joseph garrett");
        assertTrue(url.startsWith("https://minecraft.wiki/api.php?"), url);
        assertTrue(url.contains("rvprop=content%7Cids") && url.contains("redirects=1")
                && url.contains("titles=joseph+garrett%7CJoseph+Garrett"), url);
    }

    @Test
    @DisplayName("answers are cached case-blind; a failed ask is asked again")
    void caches() {
        AtomicInteger asked = new AtomicInteger();
        MinecraftWikiPerson.useOracle(n -> {
            asked.incrementAndGet();
            return n.equalsIgnoreCase("Smajor")
                    ? new MinecraftWikiPerson.Linked(n, "Smajor", 1L, "minecraft_name", "Smajor1995")
                    : new MinecraftWikiPerson.Unreachable(n);
        });
        MinecraftWikiPerson.resolve("Smajor");
        MinecraftWikiPerson.resolve("smajor");
        assertEquals(1, asked.get());
        MinecraftWikiPerson.resolve("Nobody");
        MinecraftWikiPerson.resolve("Nobody");
        assertEquals(3, asked.get(), "UNREACHABLE is not an answer");
    }

    @Test
    @DisplayName("provenance names the page, the revision and the field the link came from")
    void provenance() {
        var l = new MinecraftWikiPerson.Linked("Smajor", "Smajor", 3774932L, "minecraft_name",
                "Smajor1995");
        var j = l.toJson();
        assertEquals("minecraft.wiki", j.get("source").getAsString());
        assertEquals(3774932L, j.get("revision").getAsLong());
        assertEquals("https://minecraft.wiki/w/Smajor?oldid=3774932", j.get("url").getAsString());
    }
}
