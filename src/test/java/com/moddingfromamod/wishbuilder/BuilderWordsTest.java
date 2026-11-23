package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** every line Moddy says about a make reads as English, for every shipped ready-made. */
class BuilderWordsTest {

    private static BuilderSpec spec(String json) {
        return BuilderSpec.read(JsonParser.parseString(json).getAsJsonObject());
    }

    private static String kindOf(String preset) {
        if (preset.startsWith("events/")) {
            return "event";
        }
        if (preset.startsWith("buildings/")) {
            return "building";
        }
        if (preset.startsWith("pictures/")) {
            return "picture";
        }
        if (preset.startsWith("items/")) {
            return "item";
        }
        return "creature";
    }

    @Test
    void theEventThatSaidYourItsRainingChickensNowSaysItsOwnWords() {
        // As the tune dialog sends it: the title, punctuation stripped, as the name.
        BuilderSpec s = spec("{\"builder\":1,\"kind\":\"event\",\"from\":\"preset:events/chicken_rain\",\"name\":\"Its raining chickens\"}");
        assertEquals("It's raining chickens!", BuilderWords.ready(s));
        assertEquals("Here it comes: It's raining chickens!", BuilderWords.making(s, false));
    }

    @Test
    void aMadeUpEventKeepsItsApostropheToo() {
        // The tour, 2026-11-09: the tune dialog sent "Its raining pigs" and his screen said it without the apostrophe.
        BuilderSpec s = spec("{\"builder\":1,\"kind\":\"event\",\"from\":\"\",\"name\":\"Its raining pigs\","
                + "\"opts\":{\"motion\":\"motion:from_the_sky\",\"event\":\"rain\",\"what\":\"minecraft:pig\"}}");
        assertEquals("It's raining pigs!", BuilderWords.ready(s));
    }

    @Test
    void aTypedEventNameIsUsedAsTyped() {
        BuilderSpec s = spec("{\"builder\":1,\"kind\":\"event\",\"from\":\"preset:events/chicken_rain\",\"name\":\"Feather storm\"}");
        assertEquals("Feather storm!", BuilderWords.ready(s));
    }

    @Test
    void aRuleIsSwitchedOn() {
        BuilderSpec s = spec("{\"builder\":1,\"kind\":\"rule\",\"from\":\"\",\"opts\":{\"when\":\"when:jumps\",\"does\":\"rule:bounce\"}}");
        assertEquals("Your new rule is on!", BuilderWords.ready(s));
    }

    @Test
    void noShippedReadyMadeReadsBadly() {
        List<String> bad = new ArrayList<>();
        for (String p : Catalogue.presets()) {
            if (p.startsWith("outputs/") || p.equals("events/meteor_crater") || p.equals("events/meteor_splash")) {
                continue;
            }
            BuilderSpec s;
            try {
                s = spec("{\"builder\":1,\"kind\":\"" + kindOf(p) + "\",\"from\":\"preset:" + p + "\"}");
            } catch (RuntimeException e) {
                continue;   // a preset no Builder kind starts from
            }
            for (String line : List.of(BuilderWords.making(s, false), BuilderWords.ready(s), BuilderWords.failed(s), BuilderWords.step(s, false))) {
                String l = line.toLowerCase(Locale.ROOT);
                if (l.contains("your a ") || l.contains("your an ") || l.contains("your its ") || l.contains("your it's ")
                        || l.contains("your the ") || l.matches(".*!\\s+(is|didn't).*") || l.contains("  ")) {
                    bad.add(p + ": " + line);
                }
            }
        }
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }
}
