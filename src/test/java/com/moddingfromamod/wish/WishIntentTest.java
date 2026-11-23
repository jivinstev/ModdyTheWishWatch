package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The three-turn conversation this exists to support, and the trap in the middle of it.
 *
 * <pre>
 *   "Can you build me a rock grinder?"       → ACTION   (build it)
 *   "How do I use it?"                       → QUESTION (explain it)
 *   "Can you give me the items to use it?"   → ACTION   (hand over rocks)
 * </pre>
 *
 * All three are questions by grammar. Only the middle one is a question by intent.
 */
class WishIntentTest {

    private static void action(String wish) {
        assertEquals(WishIntent.Intent.ACTION, WishIntent.of(wish),
                "should be something to DO: \"" + wish + "\"");
    }

    private static void question(String wish) {
        assertEquals(WishIntent.Intent.QUESTION, WishIntent.of(wish),
                "should be something to EXPLAIN: \"" + wish + "\"");
    }

    @Test
    @DisplayName("the whole conversation classifies correctly, turn by turn")
    void theThreeTurnConversation() {
        action("Can you build me a rock grinder?");
        question("How do I use it?");
        action("Can you give me the items to use it?");
    }

    @Test
    @DisplayName("a polite request is still a request, however it is phrased")
    void politeRequestsAreActions() {
        action("can you make me a castle please");
        action("could you build a windmill?");
        action("will you give me some diamonds?");
        action("i want a rock crusher");
        action("please put a lighthouse on top of it");
        action("show me a dragon");           // he means summon one, not describe one
        action("can you make it go faster?");
    }

    @Test
    @DisplayName("a real question is answered, not built")
    void realQuestionsAreQuestions() {
        question("how do I use it?");
        question("what does this do");
        question("what is a crushing wheel");
        question("why does it not work?");
        question("where do the rocks go?");
        question("how many rocks do I need");
        question("tell me about the windmill");
        question("explain how the crusher works");
        question("is it broken?");
        question("does it need water?");
    }

    @Test
    @DisplayName("an action verb beats a question word — the rule the whole thing rests on")
    void actionVerbsWin() {
        // Each of these OPENS like a question and is unambiguously a request.
        action("what if you build me a giant castle");
        action("how about you make it bigger");
        action("can you give me 10 diamonds?");
        action("do I get a sword?");   // "get" — he is asking for one
    }

    @Test
    @DisplayName("a bare statement with no verb and no question word is treated as a request")
    void ambiguousDefaultsToAction() {
        // The safe default: a wasted build he can reject beats silence when he wanted something.
        action("a big red dragon");
        action("rock crusher");
        action("chickens");
    }

    @Test
    @DisplayName("the agent is told plainly not to build in response to a question")
    void theNoteForbidsBuilding() {
        String note = WishIntent.noteForAgent("how do I use it?");
        assertEquals(true, note.contains("QUESTION"));
        assertEquals(true, note.toLowerCase().contains("do not build"));
        // It must also point at the machine-listing tools, or the agent explains in generalities
        // instead of describing the actual thing standing in front of the child.
        assertEquals(true, note.contains("list_items"));
    }
}
