package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** what his wishes last made, and his last wish's words, survive a restart — on his shelf. */
class WishTopicTest {

    @TempDir
    Path shelves;

    private final UUID kid = UUID.fromString("00000000-0000-4000-8000-000000001464");

    @BeforeEach
    void pointAtATempShelf() {
        WishTopic.useRoot(() -> shelves);
    }

    @AfterEach
    void restore() {
        WishTopic.useRoot(null);
    }

    @Test
    void theLastThingAndHisWordsSurviveARestart() {
        WishTopic.wished(kid, "a cloud block that bounces me up");
        WishTopic.remember(kid, "bouncy_cloud", "rule", "a cloud block that bounces me up");
        WishTopic.clear();     // the server stops; memory is gone, the shelf is not

        WishTopic.Last last = WishTopic.last(kid);
        assertEquals("bouncy_cloud", last.artifactId());
        assertEquals("rule", last.kind());
        assertEquals("a cloud block that bounces me up", last.words());
        assertEquals("a cloud block that bounces me up", WishTopic.previousWish(kid).words());
        assertTrue(WishTopic.ageMs(kid) >= 0);
        assertTrue(Files.isRegularFile(shelves.resolve(kid.toString()).resolve(WishTopic.FILE)),
                "kept on HIS shelf");
    }

    @Test
    void theFileIsTheVersionedEnvelope() throws Exception {
        WishTopic.remember(kid, "pet_dragon", "creature", "a pet dragon");
        String text = Files.readString(shelves.resolve(kid.toString()).resolve(WishTopic.FILE));
        assertTrue(text.contains("\"store\":\"wish-topic\"") && text.contains("\"v\":1"), text);
    }

    @Test
    void aNewerFileFromAnotherServerIsNeverWrittenOver() throws Exception {
        Path f = shelves.resolve(kid.toString()).resolve(WishTopic.FILE);
        Files.createDirectories(f.getParent());
        String newer = "{\"store\":\"wish-topic\",\"v\":99,\"future\":true}";
        Files.writeString(f, newer);
        WishTopic.remember(kid, "pet_dragon", "creature", "a pet dragon");
        assertEquals(newer, Files.readString(f), "a schema this build cannot read is left alone");
        assertEquals("pet_dragon", WishTopic.lastOf(kid), "memory still serves this session");
    }

    @Test
    void twoServersMergeByTimeNotByWhoWroteLast() throws Exception {
        WishTopic.remember(kid, "pet_dragon", "creature", "a pet dragon");
        Path f = shelves.resolve(kid.toString()).resolve(WishTopic.FILE);
        // The other server made something LATER and wrote it.
        long later = System.currentTimeMillis() + 60_000;
        Files.writeString(f, "{\"store\":\"wish-topic\",\"v\":1,\"last\":{\"id\":\"robo_pup\",\"kind\":"
                + "\"creature\",\"at\":" + later + ",\"words\":\"a robot puppy\"}}");
        WishTopic.wished(kid, "make it bigger");    // this server saves its own field
        WishTopic.clear();
        assertEquals("robo_pup", WishTopic.lastOf(kid), "the later 'last' was kept");
        assertEquals("make it bigger", WishTopic.previousWish(kid).words());
    }

    @Test
    void forgetRemovesItFromTheShelf() {
        WishTopic.remember(kid, "pet_dragon", "creature", "a pet dragon");
        WishTopic.forget(kid);
        WishTopic.clear();
        assertNull(WishTopic.lastOf(kid));
    }

    @Test
    void wordsAreClipped() {
        WishTopic.wished(kid, "x".repeat(500));
        assertEquals(WishTopic.MAX_WORDS, WishTopic.previousWish(kid).words().length());
    }
}
