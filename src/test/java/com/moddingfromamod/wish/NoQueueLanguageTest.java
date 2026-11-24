package com.moddingfromamod.wish;

import com.moddingfromamod.testing.SourceRoots;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * §1.9 — <b>a seven-year-old never sees a queue.</b>
 *
 * <p>A source sweep in the spirit of {@code SourceRuleSweepTest}: cheap, and it fails by file and by
 * line so the rule cannot erode as messages are added. It reads the SOURCE rather than calling the
 * methods, because most of these strings are only produced with a live player attached and the ones
 * that matter most are the ones nobody thought to test.
 *
 * <p>Two lines used to fail this and were deleted: <i>"🪄 I heard your next wish! I'll grant it the
 * moment I finish this one"</i> and <i>"…and I've got your next wish ready — right after this one!"</i>.
 * Both were queue management wearing a friendly voice. They were kept for a while on the argument
 * that a waiting wish would otherwise have nothing to show — which stopped being true the moment a
 * wish raised its own bar and beam within a tick of being made.
 *
 * <p><b>Found in a screenshot, not by reasoning.</b> The Gate-C picture of a two-wish sequence had
 * the queue notice sitting in the middle of it, while every assertion in that run was green.
 */
class NoQueueLanguageTest {

    /** Everything that talks to the child. */
    private static final List<String> SPEAKS_TO_HIM = List.of(
            "src/main/java/com/moddingfromamod/wish/WishProgress.java",
            "src/main/java/com/moddingfromamod/wish/WishFeedback.java",
            "src/main/java/com/moddingfromamod/agent/tools/AskWhichTool.java",
            "src/main/java/com/moddingfromamod/wish/WishGo.java",
            "src/main/java/com/moddingfromamod/wish/GuidePages.java",
            "src/main/java/com/moddingfromamod/wish/ThingPage.java",
            "src/main/java/com/moddingfromamod/wish/WishHotbar.java",   //: the hotbar proxy's lines
            "src/main/java/com/moddingfromamod/wish/WishCancel.java",
            "src/main/java/com/moddingfromamod/marshal/CancelMode.java",
            "src/main/java/com/moddingfromamod/marshal/CancelOffer.java",
            "src/main/java/com/moddingfromamod/wish/WishService.java",
            // step 5: a Wish Crafter's welcome, nudges, rank-ups and promotion (its words are Crafters')
            "src/main/java/com/moddingfromamod/wish/CrafterService.java",
            // the Apprentice says its own lines (its words are ApprenticeWords'), and /moddy apprentice one.
            "src/main/java/com/moddingfromamod/wish/ApprenticeService.java",
            "src/main/java/com/moddingfromamod/wishbuilder/ApprenticeWords.java",
            "src/main/java/com/moddingfromamod/wish/WishCommands.java",
            "src/main/java/com/moddingfromamod/wish/WishExecutor.java",
            "src/main/java/com/moddingfromamod/wish/WishCapture.java",
            "src/main/java/com/moddingfromamod/wish/LearnedTemplates.java",
            // The vanilla-client panel speaks to him too -- in chat, and in the names on its own
            // slots. This is exactly the failure the test below guards: "the messages move
            // somewhere it is not looking".
            "src/main/java/com/moddingfromamod/wish/WatchMenu.java",
            "src/main/java/com/moddingfromamod/wish/WishCompose.java",
            "src/main/java/com/moddingfromamod/wish/Rewish.java",
            "src/main/java/com/moddingfromamod/wish/WishGiveAgain.java",
            // `/moddy try <wish id>`: says "not a wish id" / "can't find a wish" to him.
            "src/main/java/com/moddingfromamod/wish/WishTry.java",
            // The Wish Bag: the pin tells him where slot 2's thing went, the bag tells him
            // why an ordinary item will not go in, and every hand-over says where it landed.
            "src/main/java/com/moddingfromamod/wish/WatchPinService.java",
            "src/main/java/com/moddingfromamod/bag/WishBagMenu.java",
            "src/main/java/com/moddingfromamod/bag/WishBagItem.java",
            "src/main/java/com/moddingfromamod/item/delivery/WishDelivery.java",
            "src/main/java/com/moddingfromamod/item/delivery/DeliveryWords.java",
            // THE AGENT'S OWN VOICES. Each of these puts words in his chat — the plan, the
            // conjure message, the one celebration, the verdict, a narration line — and none was
            // swept, so the list above covered the watch's sentences and missed the agent's.
            "src/main/java/com/moddingfromamod/agent/tools/PlanWishTool.java",
            "src/main/java/com/moddingfromamod/agent/tools/ConjureAtTool.java",
            "src/main/java/com/moddingfromamod/agent/tools/FinishBuildingTool.java",
            "src/main/java/com/moddingfromamod/agent/tools/FinishWishTool.java",
            "src/main/java/com/moddingfromamod/agent/tools/NotifyPlayerTool.java",
            "src/main/java/com/moddingfromamod/agent/tools/SetStatusTool.java");

    /**
     * The banned vocabulary.
     *
     * <p>Deliberately phrases rather than bare words: "next" is in "call await_wish again", and
     * "waiting" is in a dozen honest sentences about the agent. What is banned is telling him that
     * his wish is <b>behind another one</b>.
     */
    private static final List<String> QUEUE_PHRASES = List.of(
            "your next wish",
            "right after this one",
            "after this one",
            "i'll get to it",
            "in line",
            "your turn",
            "position in",
            "still held",
            "waiting behind",
            "queued");

    /** Only what is inside double quotes — a comment may discuss the rule it is enforcing. */
    private static final Pattern LITERAL = Pattern.compile("\"([^\"\\\\]|\\\\.)*\"");

    @Test
    void nothingHeIsShownTellsHimHisWishIsBehindAnother() throws IOException {
        List<String> offences = new ArrayList<>();
        for (String file : SPEAKS_TO_HIM) {
            Path path = Path.of(file);
            if (!Files.isReadable(path)) {
                offences.add(file + " — NOT FOUND; the sweep is silently covering nothing");
                continue;
            }
            List<String> lines = Files.readAllLines(path);
            boolean inBanList = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.strip();
                // The list of banned phrases is itself made of banned phrases. Skipping it by NAME
                // rather than by excluding the whole file keeps every real message in CancelOffer
                // under the sweep.
                if (trimmed.contains("QUEUE_WORDS =") || trimmed.contains("QUEUE_PHRASES =")) {
                    inBanList = true;
                }
                if (inBanList) {
                    if (trimmed.endsWith(");")) {
                        inBanList = false;
                    }
                    continue;
                }
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) {
                    continue;   // a comment is allowed to name the thing it forbids
                }
                Matcher m = LITERAL.matcher(line);
                while (m.find()) {
                    String text = m.group().toLowerCase(Locale.ROOT);
                    for (String banned : QUEUE_PHRASES) {
                        if (text.contains(banned)) {
                            offences.add(file + ":" + (i + 1) + " says \"" + banned + "\"");
                        }
                    }
                }
            }
        }
        assertTrue(offences.isEmpty(),
                "a seven-year-old never sees a queue, and these tell him he is in one:\n  "
                        + String.join("\n  ", offences));
    }

    /** The sweep must actually be looking at something. */
    @Test
    void theSweepReadsRealFiles() throws IOException {
        int total = 0;
        for (String file : SPEAKS_TO_HIM) {
            total += Files.readAllLines(Path.of(file)).size();
        }
        assertTrue(total > 800,
                "only " + total + " lines swept — a check that reads nothing passes for the wrong "
                        + "reason");
    }

    /** And it must be able to see an offence when there is one. */
    @Test
    void theSweepCanActuallyFire() {
        String offending = "\"🪄 I heard your next wish! I'll grant it the moment I finish this one\"";
        boolean caught = false;
        for (String banned : QUEUE_PHRASES) {
            if (offending.toLowerCase(Locale.ROOT).contains(banned)) {
                caught = true;
            }
        }
        assertTrue(caught, "the exact line this test was written to delete would not be caught");
    }

    /**
     * Nothing NEW may quietly join the list of things that speak to him without being swept.
     *
     * <p>The failure mode a sweep like this really has: it keeps passing while the messages move
     * somewhere it is not looking.
     */
    @Test
    void everyClassThatSendsHimAMessageIsOnTheList() throws IOException {
        List<String> unswept = new ArrayList<>();
        try (Stream<Path> files = SourceRoots.walk(Path.of("src/main/java/com/moddingfromamod/wish"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(p);
                boolean talks = src.contains("sendSystemMessage(")
                        || src.contains("sendOverlayMessage(")
                        || src.contains("displayClientMessage(");
                boolean listed = SPEAKS_TO_HIM.stream().anyMatch(f -> p.toString().endsWith(
                        f.substring(f.lastIndexOf('/') + 1)));
                boolean isTest = p.toString().contains("GameTest");
                if (talks && !listed && !isTest) {
                    unswept.add(p.toString());
                }
            }
        }
        assertTrue(unswept.isEmpty(),
                "these talk to the child but are not swept for queue language: " + unswept);
    }
}
