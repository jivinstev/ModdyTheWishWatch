package com.moddingfromamod.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * an earlier issue's generated creator list, as the mod reads it: only MAPPED rows with a structured claim
 * naming the account, by public name or alias, and never a name two rows give different accounts.
 */
class CreatorAccountsTest {

    static String row(String creator, String alias, String rule, String name, String uuid,
                      String claimName) {
        return "{\"v\":1,\"rank\":1,\"creator\":\"" + creator + "\",\"aliases\":[\"" + alias + "\"],"
                + "\"rule\":\"" + rule + "\"" + (name == null ? "" : ",\"name\":\"" + name + "\"")
                + (uuid == null ? "" : ",\"uuid\":\"" + uuid + "\"")
                + ",\"claims\":[{\"source\":\"mcc.fandom\",\"page\":\"" + creator + "\","
                + "\"url\":\"https://mcchampionship.fandom.com/wiki/" + creator + "\","
                + "\"revision\":99,\"field\":\"ign\",\"value\":\"" + claimName + "\","
                + "\"names\":[\"" + claimName + "\"]}]}";
    }

    static final String U1 = "0123456789abcdef0123456789abcdef";
    static final String U2 = "fedcba9876543210fedcba9876543210";

    @AfterEach
    void reset() {
        CreatorAccounts.reset();
    }

    @Test
    @DisplayName("a mapped row answers to its public name and its aliases, case and punctuation aside")
    void byNameAndAlias() {
        CreatorAccounts.useRows(List.of(row("Bloxo", "Charlie Blockman", "A1:one-curated-field",
                "Bloxo", U1, "Bloxo")));
        var r = CreatorAccounts.lookup("bloxo");
        assertNotNull(r);
        assertEquals("Bloxo", r.username());
        assertEquals("01234567-89ab-cdef-0123-456789abcdef", r.pinnedUuid().toString());
        assertEquals(r, CreatorAccounts.lookup("Charlie  blockman!"));
        assertEquals("mcc.fandom", r.toJson().get("claimSource").getAsString());
        assertNull(CreatorAccounts.lookup("Stampy"));
        assertNull(CreatorAccounts.lookup(""));
    }

    @Test
    @DisplayName("an A3 row (the MCC participation fingerprint) maps like any other, citing its source")
    void fingerprintRowMaps() {
        String a3 = row("Drifter", "Clem", "A3:mcc-fingerprint", "Drifter", U2, "Drifter")
                .replace("\"source\":\"mcc.fandom\"", "\"source\":\"mcc-fingerprint\"")
                .replace("\"field\":\"ign\"", "\"field\":\"tournament_history\"");
        CreatorAccounts.useRows(List.of(a3, row("Rule", "r", "A4:not-a-rule", "Rule", U1, "Rule")));
        var r = CreatorAccounts.lookup("Drifter");
        assertNotNull(r);
        assertEquals("Drifter", r.username());
        assertEquals("mcc-fingerprint", r.toJson().get("claimSource").getAsString());
        assertNull(CreatorAccounts.lookup("Rule"), "a rule the generator does not write maps nobody");
    }

    @Test
    @DisplayName("Y1, Y2 (a creator's own declaration) and O1 (a maintainer's confirmation) rows map")
    void declaredAndConfirmedRowsMap() {
        String y1 = row("JeromeASF", "j", "Y1:self-declared", "JeromeASF", U1, "JeromeASF")
                .replace("\"source\":\"mcc.fandom\"", "\"source\":\"youtube\"");
        String y2 = row("Coldified", "c", "Y2:self-declared-soft", "coldified", U2, "coldified")
                .replace("\"source\":\"mcc.fandom\"", "\"source\":\"youtube\"");
        String o1 = row("BlockyBob", "s", "O1:owner-confirmed", "BlockyBob", "55a2e72e01614c85b191a0b227ff758a",
                "blockybob").replace("\"source\":\"mcc.fandom\"", "\"source\":\"owner\"");
        CreatorAccounts.useRows(List.of(y1, y2, o1));
        assertEquals("youtube", CreatorAccounts.lookup("JeromeASF").toJson().get("claimSource").getAsString());
        assertEquals("coldified", CreatorAccounts.lookup("Coldified").username());
        var seth = CreatorAccounts.lookup("blockybob");
        assertNotNull(seth);
        assertEquals("O1:owner-confirmed", seth.toJson().get("rule").getAsString());
        assertEquals("owner", seth.toJson().get("claimSource").getAsString());
    }

    @Test
    @DisplayName("an unmapped, dropped, malformed or other-schema row maps nobody")
    void onlyMappedRowsMap() {
        CreatorAccounts.useRows(List.of(
                row("Stampy", "Joseph Garrett", "unmapped:no-java-name", null, null, "x"),
                row("Old", "o", "dropped:uuid-changed", "OldName", null, "OldName"),
                row("NoUuid", "n", "A1:one-curated-field", "NoUuid", null, "NoUuid"),
                row("BadName", "b", "A1:one-curated-field", "has space", U1, "has space"),
                row("Liar", "l", "A1:one-curated-field", "Liar", U1, "SomeoneElse"),
                row("Future", "f", "A1:one-curated-field", "Future", U2, "Future")
                        .replace("\"v\":1", "\"v\":2"),
                "{not json"));
        for (String n : List.of("Stampy", "Old", "NoUuid", "BadName", "Liar", "Future")) {
            assertNull(CreatorAccounts.lookup(n), n);
        }
    }

    @Test
    @DisplayName("a name two rows give DIFFERENT accounts answers to neither")
    void anAmbiguousNameAnswersToNobody() {
        CreatorAccounts.useRows(List.of(
                row("Jen", "GamingWithJen", "A1:one-curated-field", "Jen1", U1, "Jen1"),
                row("Kenny", "GamingWithJen", "A1:one-curated-field", "Ken2", U2, "Ken2")));
        assertNull(CreatorAccounts.lookup("GamingWithJen"));
        assertEquals("Jen1", CreatorAccounts.lookup("Jen").username());
    }

    @Test
    @DisplayName("an override file replaces the bundled table, and is re-read when it changes")
    void theOverrideReplacesTheBundledTable(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("creator-accounts.jsonl");
        Files.writeString(f, row("Bloxo", "g", "A1:one-curated-field", "Bloxo", U1, "Bloxo") + "\n");
        CreatorAccounts.useOverride(f);
        assertEquals("Bloxo", CreatorAccounts.lookup("Bloxo").username());
        Files.writeString(f, row("Bloxo", "g", "A1:one-curated-field", "BloxoMC", U2, "BloxoMC")
                + "\n" + row("Mumbo", "m", "A1:one-curated-field", "Mumbo", U1, "Mumbo") + "\n");
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() + 5000));
        assertEquals("BloxoMC", CreatorAccounts.lookup("Bloxo").username());
        assertTrue(CreatorAccounts.describe().contains("override"), CreatorAccounts.describe());
    }

    @Test
    @DisplayName("D33: with no bundled table (the public jar) nobody is mapped, and nothing throws")
    void noBundledTableMapsNobody() {
        var t = CreatorAccounts.bundled("/creators/no-such-table.jsonl");
        assertEquals(0, t.rows());
        assertTrue(t.byName().isEmpty());
        assertTrue(t.origin().startsWith("nowhere"), t.origin());
    }

    @Test
    @DisplayName("SkinLink: minecraft.wiki first, then the list — and the list answers when the wiki cannot")
    void skinLinkOrder() {
        CreatorAccounts.useRows(List.of(row("Bloxo", "g", "A1:one-curated-field", "Bloxo", U1,
                "Bloxo"), row("Smajor", "s", "A1:one-curated-field", "OtherName", U2, "OtherName")));
        try {
            MinecraftWikiPerson.useOracle(n -> n.equalsIgnoreCase("Smajor")
                    ? new MinecraftWikiPerson.Linked(n, "Smajor", 1L, "minecraft_name", "Smajor1995")
                    : n.equalsIgnoreCase("Bloxo")
                            ? new MinecraftWikiPerson.Unreachable(n)
                            : new MinecraftWikiPerson.Unmapped(n, MinecraftWikiPerson.Why.NO_PAGE, null));
            var smajor = assertInstanceOf(SkinLink.Found.class, SkinLink.resolve("Smajor"));
            assertEquals("Smajor1995", smajor.link().username(), "the wiki wins");
            var bloxo = assertInstanceOf(SkinLink.Found.class, SkinLink.resolve("Bloxo"));
            assertEquals("Bloxo", bloxo.link().username(), "the local list answers a down wiki");
            assertInstanceOf(SkinLink.Unlinked.class, SkinLink.resolve("Stampy"));
            MinecraftWikiPerson.useOracle(MinecraftWikiPerson.Unreachable::new);
            assertInstanceOf(SkinLink.Unreachable.class, SkinLink.resolve("Stampy"));
        } finally {
            MinecraftWikiPerson.reset();
        }
    }

    @Test
    @DisplayName("being in the list does NOT make anyone a public figure")
    void theListIsNotAPublicFigureSource() throws Exception {
        String src = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/reference/PublicFigure.java"));
        assertTrue(!src.contains("CreatorAccounts") && !src.contains("SkinLink"),
                "PublicFigure must not consult the generated creator list");
    }
}
