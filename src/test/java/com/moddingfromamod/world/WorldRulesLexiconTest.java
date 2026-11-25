package com.moddingfromamod.world;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.moddingfromamod.safety.SafetyLexicon;
import com.moddingfromamod.safety.SafetyVerdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The words a world's rules file has a child read pass the safety lexicon when the file loads</b> (#43): the
 * {@code workshop} name and each refusing rule's {@code message.rule} and {@code message.why}. A flagged file is refused
 * naming the field, like any other bad field; the {@code brief} lines are the agent's and are not judged here. The
 * lexicon is a made-up one, so this file says no bad word; the last test runs the real one over every rules file in the
 * repository, so the check refuses no innocent world.
 */
class WorldRulesLexiconTest {

    /** A list that flags one made-up word. */
    private static final SafetyLexicon LEXICON = SafetyLexicon.load(new StringReader(
            "VIOLENCE\tzorblax\tOTHER\n"));

    private static String file(String workshop, String rule, String why, String brief) {
        return "{\"store\":\"world-rules\",\"v\":1,\"world\":\"isles\",\"workshop\":\"" + workshop + "\","
                + "\"brief\":[\"" + brief + "\"],\"rules\":["
                + "{\"id\":\"same-time\",\"type\":\"deny_world_effect\",\"effects\":[\"time\"],"
                + "\"message\":{\"rule\":\"" + rule + "\",\"why\":\"" + why + "\"}}]}";
    }

    private static WorldRules.Parsed parse(String text) {
        return WorldRules.parse(text, "isles", List.of(), LEXICON);
    }

    @Test
    void anInnocentFileLoads() {
        WorldRules.Parsed p = parse(file("Moddy's Workshop", "Time is the same for every island",
                "so the sun rises for everyone", "grant it on his island"));
        assertTrue(p.ok(), p.errors().toString());
    }

    @Test
    void aFlaggedRuleRefusesTheFileNamingTheRule() {
        WorldRules.Parsed p = parse(file("Moddy's Workshop", "Zorblax is the same for every island",
                "so the sun rises for everyone", "grant it"));
        assertFalse(p.ok());
        assertTrue(p.errors().stream().anyMatch(e -> e.contains("'same-time'") && e.contains("message.rule")
                && e.contains("lexicon")), p.errors().toString());
        assertFalse(p.errors().toString().toLowerCase(java.util.Locale.ROOT).contains("zorblax"),
                "the error names the field, not the flagged words: " + p.errors());
    }

    @Test
    void aFlaggedWhyRefusesTheFile() {
        WorldRules.Parsed p = parse(file("Moddy's Workshop", "Time is the same for every island",
                "so the zorblax rises", "grant it"));
        assertFalse(p.ok());
        assertTrue(p.errors().stream().anyMatch(e -> e.contains("'same-time'") && e.contains("message.why")),
                p.errors().toString());
    }

    @Test
    void aFlaggedWorkshopRefusesTheFile() {
        WorldRules.Parsed p = parse(file("Zorblax Hall", "Time is the same for every island",
                "so the sun rises for everyone", "grant it"));
        assertFalse(p.ok());
        assertTrue(p.errors().stream().anyMatch(e -> e.startsWith("workshop")), p.errors().toString());
    }

    @Test
    void theBriefIsTheAgentsAndIsNotJudgedHere() {
        assertTrue(parse(file("Moddy's Workshop", "Time is the same for every island", "so the sun rises",
                "never zorblax anything")).ok(), "the agent's brief is not a line he reads");
    }

    /**
     * The real lexicon over every rules file this repository writes: each {@code workshop}, {@code rule} and
     * {@code why} in a test's or GameTest's inline file. None may be flagged, or the check would refuse a world that
     * was fine.
     */
    @Test
    void noRulesFileInThisRepositoryIsFlagged() throws IOException {
        SafetyLexicon real = SafetyLexicon.shared();
        assertTrue(real.size() > 0, "the bundled lexicon did not load: " + real.describe());
        Pattern field = Pattern.compile("\\\\?\"(workshop|rule|why)\\\\?\"\\s*:\\s*\\\\?\"([^\"\\\\]{2,})\\\\?\"");
        List<String> flagged = new ArrayList<>();
        int seen = 0;
        for (String root : List.of("src/main/java", "src/test/java", "src/main/resources", "docs")) {
            try (Stream<Path> files = Files.walk(Path.of(root))) {
                for (Path f : files.filter(Files::isRegularFile).filter(x -> x.toString().matches(".*\\.(java|json|md)"))
                        .toList()) {
                    String text = Files.readString(f);
                    if (!text.contains("world-rules")) {
                        continue;
                    }
                    Matcher m = field.matcher(text);
                    while (m.find()) {
                        seen++;
                        if (real.check(m.group(2)).map(v -> v.overall() == SafetyVerdict.Band.CLEARLY).orElse(false)) {
                            flagged.add(f + ": " + m.group(1));
                        }
                    }
                }
            }
        }
        assertTrue(seen > 20, "the scan found only " + seen + " fields — it is reading nothing");
        assertTrue(flagged.isEmpty(), "rules files the lexicon would now refuse: " + flagged);
    }
}
