package com.moddingfromamod.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** stance and tail read off the built boxes, against the reference's. */
class ReferenceBodyCheckTest {

    static List<ReferenceBodyCheck.Box> body(String path) throws Exception {
        JsonObject o = JsonParser.parseString(Files.readString(Path.of(path))).getAsJsonObject();
        List<ReferenceBodyCheck.Box> out = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("parts")) {
            JsonObject p = e.getAsJsonObject();
            out.add(new ReferenceBodyCheck.Box(p.get("name").getAsString(), xyz(p, "from"), xyz(p, "to")));
        }
        return out;
    }

    static float[] xyz(JsonObject p, String k) {
        var a = p.getAsJsonArray(k);
        return new float[] {a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
    }

    static ReferenceBodyCheck.Box box(String n, float x0, float y0, float z0, float x1, float y1, float z1) {
        return new ReferenceBodyCheck.Box(n, new float[] {x0, y0, z0}, new float[] {x1, y1, z1});
    }

    /** A person in boxes: two legs with shoes under them, a tall body, arms, a head. */
    static List<ReferenceBodyCheck.Box> person() {
        return List.of(box("legL", -4, 0, -2, -1, 12, 2), box("legR", 1, 0, -2, 4, 12, 2),
                box("shoeL", -4, 0, -3, -1, 2, 2), box("shoeR", 1, 0, -3, 4, 2, 2),
                box("body", -4, 12, -2, 4, 24, 2), box("armL", -7, 13, -2, -4, 23, 2),
                box("armR", 4, 13, -2, 7, 23, 2), box("head", -4, 24, -4, 4, 32, 4));
    }

    @Test
    void theShippedCatBodyStandsOnFourLegsAndAPersonUpright() throws Exception {
        assertEquals(ReferenceBodyCheck.Built.FOUR_LEGS,
                ReferenceBodyCheck.stance(body("src/main/resources/starter-library/species/cat/sculpt.json")));
        assertEquals(ReferenceBodyCheck.Built.FOUR_LEGS,
                ReferenceBodyCheck.stance(body("src/main/resources/starter-library/species/wolf/sculpt.json")));
        assertEquals(ReferenceBodyCheck.Built.UPRIGHT, ReferenceBodyCheck.stance(person()));
    }

    @Test
    void anUprightCharacterBuiltOnTheCatBodyIsTold() throws Exception {
        String r = ReferenceBodyCheck.report(ReferenceSpec.Stance.UPRIGHT_BIPED, ReferenceSpec.Tail.UNKNOWN,
                body("src/main/resources/starter-library/species/cat/sculpt.json"), "Tabbycat");
        assertTrue(r.contains("STANCE") && r.contains("upright"), r);
        assertEquals("", ReferenceBodyCheck.report(ReferenceSpec.Stance.UPRIGHT_BIPED, ReferenceSpec.Tail.UNKNOWN,
                person(), "Tabbycat"), "an upright build of an upright character says nothing");
        String q = ReferenceBodyCheck.report(ReferenceSpec.Stance.QUADRUPED, ReferenceSpec.Tail.UNKNOWN, person(), "Blueberry");
        assertTrue(q.contains("four legs"), q);
        assertEquals("", ReferenceBodyCheck.report(ReferenceSpec.Stance.UNKNOWN, ReferenceSpec.Tail.UNKNOWN,
                body("src/main/resources/starter-library/species/cat/sculpt.json"), "X"), "an unknown stance judges nothing");
    }

    @Test
    void aTailThatContradictsTheReferenceIsTold() {
        List<ReferenceBodyCheck.Box> longTail = new ArrayList<>(person());
        longTail.add(box("tail", -1, 6, 2, 1, 8, 20));
        assertTrue(ReferenceBodyCheck.report(ReferenceSpec.Stance.UNKNOWN, ReferenceSpec.Tail.NONE, longTail, "Squeak")
                .contains("no tail"));
        assertTrue(ReferenceBodyCheck.report(ReferenceSpec.Stance.UNKNOWN, ReferenceSpec.Tail.SHORT, longTail, "Pippo Rabbit")
                .contains("short tail"), "a long tail on a short-tailed rabbit");
        List<ReferenceBodyCheck.Box> puff = new ArrayList<>(person());
        puff.add(box("tail", -1, 10, 2, 1, 12, 4));
        assertEquals("", ReferenceBodyCheck.report(ReferenceSpec.Stance.UPRIGHT_BIPED, ReferenceSpec.Tail.SHORT, puff, "Pippo Rabbit"));
        assertTrue(ReferenceBodyCheck.report(ReferenceSpec.Stance.UNKNOWN, ReferenceSpec.Tail.LONG, person(), "Tabbycat")
                .contains("long tail"));
        List<ReferenceBodyCheck.Box> pony = new ArrayList<>(person());
        pony.add(box("ponytail", -1, 20, 4, 1, 30, 6));
        assertEquals("", ReferenceBodyCheck.report(ReferenceSpec.Stance.UNKNOWN, ReferenceSpec.Tail.NONE, pony, "Frya"),
                "hair is not a tail");
    }

    @Test
    void theReferenceSaysItsStanceAndTailAndKeepsThemThroughTheCache() {
        ReferenceSpec s = ReferenceSpec.parse("{\"colours\":[{\"colour\":\"tan/orange\",\"percent\":80}],\"parts\":[\"ear\",\"tail\"],"
                + "\"confidence\":\"HIGH\",\"stance\":\"UPRIGHT_BIPED\",\"tail\":\"LONG\"}");
        assertEquals(ReferenceSpec.Stance.UPRIGHT_BIPED, s.stance());
        assertEquals(ReferenceSpec.Tail.LONG, s.tail());
        assertTrue(s.describe().contains("upright on two legs"), s.describe());
        assertTrue(WishBrief.structuredLook(s).contains("It stands and walks upright on two legs, like a person, even if its kind of animal usually walks on four."));
        ReferenceSpec rabbit = ReferenceSpec.parse("{\"colours\":[{\"colour\":\"tan/orange\",\"percent\":80}],"
                + "\"parts\":[\"ear\",\"tail\"],\"confidence\":\"HIGH\",\"stance\":\"UPRIGHT_BIPED\",\"tail\":\"NONE\"}");
        String look = WishBrief.structuredLook(rabbit);
        assertTrue(look.contains("It has no tail.") && !look.contains(", tail"), look);
        assertTrue(ReferenceResearcher.promptForTest("Tabbycat").contains("\"stance\"")
                && ReferenceResearcher.promptForTest("Tabbycat").contains("\"tail\""), "the researcher is asked for both");
        assertEquals(ReferenceSpec.Stance.UNKNOWN, ReferenceSpec.parse("{\"colours\":[{\"colour\":\"red\",\"percent\":9}],"
                + "\"parts\":[\"ear\"],\"confidence\":\"HIGH\",\"stance\":\"WALKS_ON_HANDS\"}").stance(), "a word outside the set is dropped");
    }

    @Test
    void aDescriptionSaysItsStanceAndTailAndAHiddenTailIsPutBack() {
        String scruff = "It stands upright on two legs like a person and has a long tail curving behind it.";
        assertEquals(ReferenceSpec.Stance.UPRIGHT_BIPED, WishBrief.stanceIn(scruff));
        assertEquals(ReferenceSpec.Tail.LONG, WishBrief.tailIn(scruff));
        assertEquals(ReferenceSpec.Stance.QUADRUPED, WishBrief.stanceIn("A dog that walks on all fours."));
        assertEquals(ReferenceSpec.Tail.SHORT, WishBrief.tailIn("It has a short navy tail."));
        assertEquals(ReferenceSpec.Tail.UNKNOWN, WishBrief.tailIn("It stands upright, and no tail is visible."),
                "a tail the pictures do not show is not a missing tail");
        ReferenceSpec tabbycat = ReferenceSpec.parse("{\"colours\":[{\"colour\":\"tan/orange\",\"percent\":80}],"
                + "\"parts\":[\"ear\",\"tail\"],\"confidence\":\"HIGH\",\"stance\":\"UPRIGHT_BIPED\",\"tail\":\"LONG\"}");
        String said = "It stands upright on two legs like a person and has no tail. Orange covers about 90% of it.";
        String fixed = WishBrief.reconcileTail(said, tabbycat);
        assertTrue(fixed.endsWith("it has a long tail."), fixed);
        assertEquals(ReferenceSpec.Tail.LONG, WishBrief.tailTold(fixed, tabbycat));
        assertEquals(said, WishBrief.reconcileTail(said, tabbycat.withBody(ReferenceSpec.Stance.UPRIGHT_BIPED, ReferenceSpec.Tail.NONE)));
        // the pictures' stance is kept even where the written reference differs (Scruff-Pup)
        ReferenceSpec fours = tabbycat.withBody(ReferenceSpec.Stance.QUADRUPED, ReferenceSpec.Tail.LONG);
        assertEquals(ReferenceSpec.Stance.UPRIGHT_BIPED, WishBrief.stanceTold(scruff, fours));
        assertEquals(ReferenceSpec.Stance.QUADRUPED, WishBrief.stanceTold("Brown covers about 85% of it.", fours),
                "a look silent on stance falls back to the reference");
    }

    @Test
    void emberIsAskedWithThePicturesInlineAtMinimalAndItsTextIsRead() throws Exception {
        var img = new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var body = EmberVision.body("sys", "Describe it.", List.of(img, img), "minimal");
        assertEquals(com.moddingfromamod.config.SupportedModels.EMBER, body.get("model").getAsString());
        assertEquals("minimal", body.getAsJsonObject("reasoning").get("effort").getAsString(),
                "the Responses API's minimal: low missed 1 call in 5");
        assertEquals("sys", body.get("instructions").getAsString());
        var content = body.getAsJsonArray("input").get(0).getAsJsonObject().getAsJsonArray("content");
        assertEquals(3, content.size(), "two pictures, then the ask");
        assertEquals("input_image", content.get(0).getAsJsonObject().get("type").getAsString());
        assertTrue(content.get(0).getAsJsonObject().get("image_url").getAsString().startsWith("data:image/png;base64,"));
        assertEquals("Describe it.", content.get(2).getAsJsonObject().get("text").getAsString());
        assertEquals("Orange", EmberVision.text("{\"output\":[{\"type\":\"reasoning\",\"summary\":[]},"
                + "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Orange\"}]}]}"));
        assertEquals(null, EmberVision.text("{\"output\":[{\"type\":\"reasoning\",\"summary\":[]}]}"),
                "all thinking and no answer is no answer, and the Haiku describer goes next");
        assertEquals(null, EmberVision.text("{\"error\":{\"message\":\"x\"}}"));
    }
}
