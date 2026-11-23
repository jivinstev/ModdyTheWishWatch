package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Every line {@code WishFeedback} sends a child is written down, and says who sent it.
 */
class WishFeedbackSaidTest {

    @Test
    void theSenderIsTheFeedbackMethodAndItsCaller() {
        assertEquals("WishFeedback.planned ← PlanWishTool.call", SaidSite.describe(List.of(
                "StackWalker.walk", "WishFeedback.record", "WishFeedback.tell", "WishFeedback.planned",
                "PlanWishTool.call", "McpProtocol.dispatch")));
    }

    @Test
    void aNestedSendNamesTheMethodTheCallerAskedFor() {
        assertEquals("WishFeedback.verdictDone ← FinishWishTool.lambda$call$2", SaidSite.describe(List.of(
                "WishFeedback.record", "WishFeedback.tell", "WishFeedback.granted",
                "WishFeedback.verdictDone", "FinishWishTool.lambda$call$2")),
                "the OUTERMOST feedback method, the one the caller asked for, names every line it sends");
    }

    @Test
    void noFeedbackFrameIsAQuestionMarkNotAGuess() {
        assertEquals("?", SaidSite.describe(List.of("Somewhere.else")));
    }

    /**
     * The guard: a direct send anywhere in WishFeedback but its two plumbing methods is a line no
     * trace can see. Read as SOURCE, because the question is what the file does, not what a test
     * happens to call.
     */
    @Test
    void everySendGoesThroughTheLoggedPair() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/moddingfromamod/wish/WishFeedback.java"));
        Matcher m = Pattern.compile("\\.(sendSystemMessage|displayClientMessage)\\(").matcher(src);
        int direct = 0;
        while (m.find()) {
            direct++;
        }
        assertEquals(2, direct, "WishFeedback may send directly only inside tell() and show(); "
                + "anything else must call tell(player, …) / show(player, …, overlay) so it is logged");
    }

    /** the line a new wish's submit sends is about the NEW wish, not the one still running. */
    @Test
    void aSendersOwnScopeBeatsTheSingleLiveRun() {
        assertEquals("new00001", WishFeedback.choose("new00001", null, List.of("old00001")),
                "submit's 'let me think' belongs to the wish being submitted, not the one before it");
        assertEquals("bound001", WishFeedback.choose(null, "bound001", List.of("old00001", "other001")));
        assertEquals("old00001", WishFeedback.choose(null, null, List.of("old00001")));
        assertEquals("", WishFeedback.choose(null, null, List.of("a0000001", "b0000001")),
                "two live wishes and no scope: empty, never a guess");
    }

    /** The scope is opened by submit once the wish exists, and closed whatever happens. */
    @Test
    void submitOpensTheScopeAfterTheWishExistsAndAlwaysClosesIt() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/moddingfromamod/wish/WishService.java"));
        int made = src.indexOf("Wish wish = WishBook.get(player.getServer()).add(Wish.create(");
        int scoped = src.indexOf("WishFeedback.about(wish.shortId());");
        org.junit.jupiter.api.Assertions.assertTrue(made > 0 && scoped > made, "about() after the wish is minted");
        org.junit.jupiter.api.Assertions.assertTrue(src.contains("} finally {\n            WishFeedback.aboutNone();"),
                "submit closes the scope in a finally, so it never leaks into the next line on this thread");
    }
}
