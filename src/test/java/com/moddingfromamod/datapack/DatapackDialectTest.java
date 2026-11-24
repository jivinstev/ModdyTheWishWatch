package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

/**
 * The zip half of {@link DatapackDialect}, which needs no server: which entries it rewrites,
 * which it copies, and that a pack already in this build's dialect comes back as the SAME bytes. The
 * dialect itself is {@code CommandDialect}'s and has its own tests; whether the rewritten starter
 * packs really parse is {@code StarterDatapackDialectGameTest}'s job, on each target.
 */
class DatapackDialectTest {

    private static byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static Map<String, String> unzip(byte[] bytes) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                out.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static Map<String, String> pack(int format, String give) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("pack.mcmeta", "{\"pack\":{\"pack_format\":" + format + ",\"description\":\"x\"}}");
        m.put("data/cookie/function/give.mcfunction", give);
        m.put("data/minecraft/tags/function/tick.json", "{\"values\":[\"cookie:tick\"]}");
        return m;
    }

    @Test
    void functionsAndThePackFormatAreRewrittenAndNothingElse() throws IOException {
        Map<String, String> in = pack(48, "give @s stick[custom_model_data=8]\n");
        List<String> notes = new ArrayList<>();
        DatapackDialect.Retargeted r = DatapackDialect.retarget(zip(in),
                t -> t.replace("custom_model_data=8", "custom_model_data={floats:[8f]}"), 61, notes);

        Map<String, String> out = unzip(r.zip());
        assertEquals(in.keySet(), out.keySet(), "every entry survives, in order");
        assertEquals("give @s stick[custom_model_data={floats:[8f]}]\n",
                out.get("data/cookie/function/give.mcfunction"));
        assertTrue(out.get("pack.mcmeta").contains("\"pack_format\":61"), out.get("pack.mcmeta"));
        assertTrue(out.get("pack.mcmeta").contains("\"description\":\"x\""), "the rest of mcmeta stays");
        assertEquals(in.get("data/minecraft/tags/function/tick.json"),
                out.get("data/minecraft/tags/function/tick.json"), "a non-function file is copied");
        assertEquals(2, notes.size(), "one note per rewritten file: " + notes);
        assertTrue(r.changed());
    }

    @Test
    void aPackAlreadyInThisDialectComesBackAsTheSameBytes() throws IOException {
        byte[] already = zip(pack(61, "give @s stick\n"));
        DatapackDialect.Retargeted r = DatapackDialect.retarget(already, t -> t, 61, new ArrayList<>());
        assertSame(already, r.zip(), "nothing to change must mean the caller's own array back");
        assertTrue(!r.changed());
    }

    @Test
    void aFormatAboveTheLastBareOneAlsoDeclaresMinAndMaxFormat() {
        // 1.21.9+ refuses a pack_format above 81 without min_format and max_format (PackFormat.packCodec),
        // and refuses supported_formats beside a min_format above 81.
        assertEquals(81, DatapackDialect.LAST_BARE_FORMAT);
        byte[] old = "{\"pack\":{\"pack_format\":48,\"supported_formats\":[48,61],\"description\":\"x\"}}"
                .getBytes(StandardCharsets.UTF_8);
        com.google.gson.JsonObject pack = com.google.gson.JsonParser.parseString(new String(
                DatapackDialect.withPackFormat(old, 88, 0), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("pack");
        assertEquals(88, pack.get("pack_format").getAsInt());
        assertEquals("[88,0]", pack.get("min_format").toString());
        assertEquals("[88,0]", pack.get("max_format").toString());
        assertTrue(!pack.has("supported_formats"), pack.toString());
        assertEquals("x", pack.get("description").getAsString());
        byte[] wrapped = ("{\"pack\":" + pack + "}").getBytes(StandardCharsets.UTF_8);
        assertSame(wrapped, DatapackDialect.withPackFormat(wrapped, 88, 0), "already declared: the same array");
    }

    @Test
    void aBareFormatStaysBare() {
        byte[] in = "{\"pack\":{\"pack_format\":48,\"description\":\"x\"}}".getBytes(StandardCharsets.UTF_8);
        String out = new String(DatapackDialect.withPackFormat(in, 81, 0), StandardCharsets.UTF_8);
        assertTrue(out.contains("\"pack_format\":81") && !out.contains("min_format"), out);
    }

    @Test
    void anUnreadableMcmetaIsLeftForMinecraftToReport() {
        byte[] junk = "{not json".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(junk, DatapackDialect.withPackFormat(junk, 61));
    }

    @Test
    void aMachinesHandlePredicateMatchesTheSignIn1215sSpelling() {
        String old = "{\"criteria\":{\"pulled\":{\"conditions\":{\"location\":[{\"predicate\":{\"block\":"
                + "{\"nbt\":\"{back_text:{messages:['\\\"slushie\\\"']}}\"}}}]}}}}";
        String fixed = DatapackDialect.correctNbtPredicates(old);
        assertTrue(fixed.contains("{back_text:{messages:[\\\"slushie\\\"]}}"), fixed);
        assertEquals(fixed, DatapackDialect.correctNbtPredicates(fixed), "already new comes back as it was");
        assertEquals("{not json", DatapackDialect.correctNbtPredicates("{not json"));
    }
}
