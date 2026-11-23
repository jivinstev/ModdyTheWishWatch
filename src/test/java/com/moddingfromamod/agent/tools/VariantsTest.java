package com.moddingfromamod.agent.tools;

import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>"20 flavors of ice cream!!" is ONE wish.</b>
 *
 * <p>Before this, the agent's own recorded answer to that wish was:
 * <i>"Nearest fit is one ice cream, count 20; I'd tell him it's one flavor."</i> He asked for twenty
 * flavours and got twenty of the same one — not a crash and not a refusal, an honest wrong answer,
 * which is the worst kind to leave in because nothing fails.
 */
class VariantsTest {

    private static JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static List<MakeItemTool.Variant> parse(String json) {
        return MakeItemTool.parseVariants(args(json), "Fallback", 0x112233);
    }

    private static String err(String json) {
        return assertThrows(IllegalArgumentException.class, () -> parse(json)).getMessage();
    }

    /**
     * <b>No variants is a family of one</b>, which is what makes every wish ever made take exactly
     * the path it always did.
     */
    @Test
    @DisplayName("a wish that asks for one thing is unchanged")
    void noVariantsIsAFamilyOfOne() {
        for (String json : new String[]{"{}", "{\"variants\":null}", "{\"variants\":[]}"}) {
            List<MakeItemTool.Variant> f = parse(json);
            assertEquals(1, f.size(), json);
            assertEquals("Fallback", f.get(0).name(), json);
            assertEquals(0x112233, f.get(0).rgb(), json);
        }
    }

    /** The driving wish, end to end through the parser. */
    @Test
    @DisplayName("twenty flavours are twenty members, each its own name and colour")
    void theDrivingWish() {
        StringBuilder b = new StringBuilder("{\"variants\":[");
        for (int i = 0; i < 20; i++) {
            b.append(i > 0 ? "," : "")
             .append("{\"name\":\"Flavour ").append(i).append("\",\"colour\":\"#")
             .append(String.format("%02X20A0", i * 10)).append("\"}");
        }
        List<MakeItemTool.Variant> f = parse(b.append("]}").toString());
        assertEquals(20, f.size());
        assertEquals(20, f.stream().map(MakeItemTool.Variant::name).distinct().count(),
                "every flavour has to be tellable from the others");
        assertEquals(20, f.stream().map(MakeItemTool.Variant::rgb).distinct().count(),
                "and has to LOOK different, since the icons are grey and tinted");
    }

    /** Colour words, not just hex — the child says "brown", never #5B3A1E. */
    @Test
    @DisplayName("colour words work, as they do everywhere else in this tool")
    void colourWords() {
        List<MakeItemTool.Variant> f = parse(
                "{\"variants\":[{\"name\":\"Vanilla\",\"colour\":\"white\"},"
                        + "{\"name\":\"Chocolate\",\"colour\":\"brown\"}]}");
        assertEquals(2, f.size());
        assertTrue(f.get(0).rgb() != f.get(1).rgb(), "white and brown are not the same colour");
    }

    /**
     * <b>Refuse, do not trim.</b> A wish for thirty answered with twenty-four is a wrong answer the
     * child finds by counting, and nothing in the transcript would say so — the same argument the
     * rest of this tool makes for refusing rather than defaulting.
     */
    @Test
    @DisplayName("too many is refused, not silently trimmed")
    void tooManyIsRefused() {
        StringBuilder b = new StringBuilder("{\"variants\":[");
        for (int i = 0; i < MakeItemTool.MAX_VARIANTS + 1; i++) {
            b.append(i > 0 ? "," : "").append("{\"name\":\"N").append(i).append("\",\"colour\":\"red\"}");
        }
        String m = err(b.append("]}").toString());
        assertTrue(m.contains(String.valueOf(MakeItemTool.MAX_VARIANTS)), m);
        assertTrue(m.contains("Ask him"), "the refusal must say what to do next: " + m);
    }

    /** Two members he cannot tell apart is the SAME failure "20 flavors" already had. */
    @Test
    @DisplayName("two members with one name is refused")
    void duplicateNamesRefused() {
        String m = err("{\"variants\":[{\"name\":\"Vanilla\",\"colour\":\"white\"},"
                + "{\"name\":\"vanilla\",\"colour\":\"red\"}]}");
        assertTrue(m.contains("cannot tell them apart"), m);
    }

    /** A member with no colour is a member that looks identical to every other one. */
    @Test
    @DisplayName("a member without a name or a usable colour is refused, by index")
    void incompleteMembersRefused() {
        assertTrue(err("{\"variants\":[{\"colour\":\"red\"}]}").contains("no name"));
        assertTrue(err("{\"variants\":[{\"name\":\"X\"}]}").contains("needs a colour"));
        assertTrue(err("{\"variants\":[{\"name\":\"X\",\"colour\":\"puce\"}]}").contains("needs a colour"));
        assertTrue(err("{\"variants\":[{\"name\":\"ok\",\"colour\":\"red\"},\"nope\"]}").contains("variants[1]"),
                "the refusal has to say WHICH one, or the agent resends all twenty");
    }

    /** The agent has to be told it exists, in the summary it actually reads. */
    @Test
    @DisplayName("the surface teaches variants with the child's own wishes")
    void theSurfaceTeachesIt() {
        String d = new MakeItemTool().description();
        assertTrue(d.contains("variants"), "the agent is never told variants exists");
        assertTrue(d.contains("20 flavors of ice cream"),
                "the example has to be a wish he really made, per playbook 4f");
        assertTrue(d.contains("twenty of the SAME one"),
                "the surface must say what the WRONG answer looks like — that is the mistake it "
                        + "made for a year, and naming it is what stops it");
    }
}
