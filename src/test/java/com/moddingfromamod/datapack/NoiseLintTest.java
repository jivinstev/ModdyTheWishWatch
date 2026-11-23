package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** a sound on every tick, forever, is refused — and nothing the real corpus holds is. */
class NoiseLintTest {

    @Test
    void aRoarOnEveryTickFromTheCreatureIsRefused() {
        String src = """
                clock tick
                    as @e[tag=wishmob_rex] at @s
                        /playsound minecraft:entity.ravager.roar hostile @a ~ ~ ~ 1 1
                    end
                end
                """;
        List<NoiseLint.Finding> f = NoiseLint.findings(src);
        assertEquals(1, f.size());
        assertEquals(3, f.get(0).line());
        String msg = NoiseLint.check(src);
        assertNotNull(msg);
        assertTrue(msg.contains("EVERY tick") && msg.contains("TIMER"), msg);
    }

    @Test
    void aSoundInAFunctionTheClockCallsIsRefusedToo() {
        assertEquals(1, NoiseLint.findings("""
                function roar()
                    /execute as @e[tag=wishmob_rex] at @s run playsound minecraft:entity.ravager.roar hostile @a
                end
                clock tick
                    roar()
                end
                """).size());
    }

    @Test
    void anythingBehindATimerAnEventOrANearbyPlayerPasses() {
        assertNull(NoiseLint.check("""
                clock tick
                    t = t + 1
                    if t > 100
                        t = 0
                        /playsound minecraft:entity.ravager.roar hostile @a ~ ~ ~ 1 1
                    end
                end
                """), "a timer");
        assertNull(NoiseLint.check("""
                clock tick
                    /execute as @e[tag=wishmob_rex,nbt={HurtTime:10s}] at @s run playsound minecraft:x hostile @a
                end
                """), "an event (hurt)");
        assertNull(NoiseLint.check("""
                clock tick
                    /execute as @e[tag=wishmob_rex] at @s if entity @a[distance=..6] run playsound minecraft:x hostile @a
                end
                """), "someone near");
        assertNull(NoiseLint.check("""
                clock tick
                    as @NewMachine at @s
                        /playsound minecraft:block.copper.place block @a[distance=..32] ~ ~ ~ 1 1.2
                    end
                end
                """), "a named selector (a freshly placed machine)");
        assertNull(NoiseLint.check("""
                clock tick
                    /execute as @e[tag=egg_fly] at @s run playsound minecraft:x hostile @a
                end
                """), "a projectile's tag");
    }

    @Test
    void particlesEveryTickAreALookAndPass() {
        assertNull(NoiseLint.check("""
                clock tick
                    as @e[tag=wishmob_ghost] at @s
                        /particle minecraft:flame ~ ~ ~ 1.6 1.6 1.6 0.03 240 force
                    end
                end
                """));
    }

    @Test
    void soundsOutsideAClockPass() {
        assertNull(NoiseLint.check("""
                function on_use()
                    /playsound minecraft:x hostile @a
                end
                """));
    }

    /** The shipped effect and item templates, every one: none may be refused. */
    @Test
    void noShippedTemplateIsRefused() throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> s = Files.walk(Path.of("src/main/resources"))) {
            for (Path p : s.filter(x -> x.toString().endsWith(".cbs")).toList()) {
                if (!NoiseLint.findings(Files.readString(p)).isEmpty()) {
                    hits.add(p.toString());
                }
            }
        }
        assertTrue(hits.isEmpty(), "shipped templates the lint refuses: " + hits);
    }

    /** Every real compile in the corpus that compiled: none may be refused (measured 0 of 141). */
    @Test
    void noRealCorpusSourceIsRefused() throws IOException {
        List<String> hits = new ArrayList<>();
        int n = 0;
        for (String line : Files.readAllLines(Path.of("src/main/resources/corpus/datapacks.jsonl"))) {
            line = line.strip();
            if (!line.startsWith("{")) {
                continue;
            }
            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            if (!"compiles".equals(o.has("observed") ? o.get("observed").getAsString() : "")) {
                continue;
            }
            n++;
            if (!NoiseLint.findings(o.get("source").getAsString()).isEmpty()) {
                hits.add(o.get("id").getAsString());
            }
        }
        assertTrue(n > 100, "the corpus shrank to " + n);
        assertTrue(hits.isEmpty(), "real compiles the lint would now refuse: " + hits);
    }
}
