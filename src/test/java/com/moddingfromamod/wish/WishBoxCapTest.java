package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>A wish typed on the shipped route fits the wish box, and comes back as he typed it</b> (D2
 *).
 *
 * <p>The watch's "Wish for something new" opens a vanilla dialog ({@link WishCompose}) whose text box
 * carries {@code max_length} = {@link WishText#BOX_MAX}. A stock client discards the keystroke past it,
 * so a harness typing a longer wish would submit a shorter one than it was asked to measure — measured
 * on the anvil that came before (57 typed, 49 recorded). The driver refuses such a wish before typing.
 */
class WishBoxCapTest {

    @Test
    @DisplayName("the cap fits under /wish's own with the box's command in front, and the budget sits under the cap")
    void theCapAndTheBudget() {
        String prefix = "/" + ChildCommands.MODDY + " " + ChildCommands.WISHBOX + " ";
        assertTrue(prefix.length() + WishText.BOX_MAX <= WishText.MAX_LENGTH,
                "a full box must still be a whole wish after the server's " + WishText.MAX_LENGTH + " clamp");
        assertTrue(WishText.BOX_BUDGET < WishText.BOX_MAX && WishText.BOX_MAX - WishText.BOX_BUDGET <= 4,
                "the budget is a couple of characters under the cap");
    }

    @Test
    @DisplayName("a wish at the cap fits; one over it is refused, saying the cap")
    void refusesOverTheCap() {
        assertNull(WishText.tooLongForBox("x".repeat(WishText.BOX_MAX)));
        String why = WishText.tooLongForBox("x".repeat(WishText.BOX_MAX + 1));
        assertNotNull(why);
        assertTrue(why.contains(String.valueOf(WishText.BOX_MAX)), why);
    }

    @Test
    @DisplayName("…and it measures what gets submitted, not what was typed into it")
    void measuresTheSanitizedForm() {
        String padded = "   " + "y".repeat(WishText.BOX_MAX) + "   ";
        assertNull(WishText.tooLongForBox(padded), "sanitizing trims it to the cap; refusing it refuses a wish that fits");
    }

    /**
     * A dialog escapes the box's value into its command ({@code StringTag.escapeWithoutQuotes}); without
     * the undo, "Spikor's castle" would reach the agent as {@code Spikor\'s castle}.
     */
    @Test
    @DisplayName("the box's escaped words come back as he typed them; a newline is a space")
    void undoesTheDialogsEscaping() {
        assertEquals("Spikor's \"big\" castle \\ ok", WishText.fromDialog("Spikor\\'s \\\"big\\\" castle \\\\ ok"));
        assertEquals("line one line two", WishText.fromDialog("line one\\nline two"));
        assertEquals("tab here", WishText.fromDialog("tab\\there"));
        assertEquals("a b", WishText.fromDialog("a\\x01b"));
        assertEquals("a\\nb", WishText.fromDialog("a\\\\nb"), "a typed backslash then n stays a backslash and n");
        assertEquals("no escapes at all", WishText.fromDialog("no escapes at all"));
        assertEquals("ends with \\", WishText.fromDialog("ends with \\"));
        assertEquals("", WishText.fromDialog(null));
    }

    @Test
    @DisplayName("the dialog: one text box that may open empty, its Submit the wishbox command")
    void theDialog() {
        var d = WishCompose.dialog(WishCompose.TITLE, "");
        assertEquals(ChildCommands.MODDY + " " + ChildCommands.WISHBOX + " " + com.moddingfromamod.ui.TuneDialog.Words.MARK + "$(" + WishCompose.KEY + ")", d.template());
        var json = d.json();
        var box = json.getAsJsonArray("inputs").get(0).getAsJsonObject();
        assertEquals("minecraft:text", box.get("type").getAsString());
        assertEquals("", box.get("initial").getAsString());
        assertEquals(WishText.BOX_MAX, box.get("max_length").getAsInt());
        assertTrue(box.has("multiline"));
        assertEquals("I want my castle to ", WishCompose.dialog("t", "I want my castle to ").inputs().get(0)
                        .json().get("initial").getAsString(),
                "a thing's 'wish about this' keeps its trailing space, so he types straight on");
    }

    /** a Magician's box. One dropdown, then the words LAST; the choice rides ahead of the words. */
    @Test
    void aMagiciansBoxCarriesTheChoiceOfMagicAheadOfTheWords() {
        for (boolean apprentice : new boolean[] {false, true}) {
            for (boolean priced : new boolean[] {false, true}) {
                var d = WishCompose.dialog(WishCompose.TITLE, "", apprentice, priced);
                assertEquals(2, d.inputs().size());
                assertTrue(d.body().isEmpty(), "the dropdown says it all: no lines above it");
                var magic = (com.moddingfromamod.ui.TuneDialog.Choice) d.inputs().get(0);
                assertEquals(apprentice ? WishCompose.MAGIC_APPRENTICE : WishCompose.MAGIC_MAGICIAN, magic.initial(),
                        "it starts on what he chose last");
                assertEquals(java.util.List.of(WishCompose.MAGIC_MAGICIAN, WishCompose.MAGIC_APPRENTICE),
                        magic.options().stream().map(com.moddingfromamod.ui.TuneDialog.Option::id).toList());
                assertEquals(priced ? "✪ Magician: 1 wish, but infinite opportunities." : "✪ Magician: infinite opportunities.",
                        magic.options().get(0).display());
                assertEquals(priced ? "✨ Apprentice: ½ wish. Faster, but more limited." : "✨ Apprentice: faster, but more limited.",
                        magic.options().get(1).display());
                assertTrue(d.inputs().get(1) instanceof com.moddingfromamod.ui.TuneDialog.Words, "the words are last");
                assertEquals(WishCompose.LABEL_EITHER, ((com.moddingfromamod.ui.TuneDialog.Words) d.inputs().get(1)).label());
                assertEquals("moddy wishbox magic $(magic) |$(wish)", d.template());
                String json = d.json().toString();
                assertTrue(json.contains("\"width\":" + com.moddingfromamod.ui.TuneDialog.Words.WIDTH)
                        && json.contains("\"label_visible\":false"), "Java's button is wide and shows the option alone");
            }
        }
    }

    /** a Crafter's box. No input but the words; two lines say the magic he has and where a Magician signs up. */
    @Test
    void aCraftersBoxSaysBothMagicsAndOffersNoChoice() {
        for (boolean priced : new boolean[] {false, true}) {
            var d = WishCompose.crafterDialog(WishCompose.TITLE, "", priced, CrafterService.MAGICIAN_ADDRESS,
                    "https://account.example/signin?join=closed");
            assertEquals(1, d.inputs().size(), "the words alone: there is nothing he can choose");
            assertEquals("moddy wishbox |$(wish)", d.template(), "and Submit is the plain box's");
            assertEquals(2, d.body().size());
            assertEquals(priced ? "✨ Apprentice: ½ wish. Faster, but more limited." : "✨ Apprentice: faster, but more limited.",
                    d.body().get(0).words());
            assertEquals("✪ Magician: sign up (18+ only) at ", d.body().get(1).words());
            assertEquals("playmoddy.com/magician", d.body().get(1).shown(), "one short address a child can read out");
            String json = d.json().toString();
            assertTrue(json.contains("\"open_url\"") && json.contains("https://account.example/signin?join=closed"),
                    "on Java the address is a link to the world's own sign-up");
            assertEquals(priced, json.contains("wish."), "no price, and no word of the bank, on a world without one");
            for (var line : d.body()) {
                assertTrue((line.words() + line.shown()).length() <= 60, "one line on a phone: " + line);
            }
        }
    }

    /** with the Magician off there is nothing to choose; the host's ✪ line opens the setup. */
    @Test
    void withTheMagicianOffTheMagicianLineOpensTheSetup() {
        var host = WishCompose.magicianOffDialog(WishCompose.TITLE, "", true);
        assertEquals(1, host.inputs().size(), "no choice of magic: only the Apprentice can answer");
        assertEquals("moddy wishbox |$(wish)", host.template());
        assertEquals(1, host.body().size(), "one line, so Make my wish stays on the screen");
        assertEquals("✨ The Apprentice answers. ✪ Magician: off. ", host.body().get(0).words());
        assertEquals("Turn it on", host.body().get(0).shown());
        var link = host.json().getAsJsonArray("body").get(0).getAsJsonObject().getAsJsonObject("contents")
                .getAsJsonArray("extra").get(0).getAsJsonObject().getAsJsonObject("click_event");
        assertEquals("run_command", link.get("action").getAsString());
        assertEquals("/moddy magician", link.get("command").getAsString());
        var guest = WishCompose.magicianOffDialog(WishCompose.TITLE, "", false);
        assertEquals("✨ Apprentice answers. ✪ Magician: off (the host sets it up).", guest.body().get(0).words());
        assertTrue(!guest.json().toString().contains("click_event"), "a guest's line opens nothing");
    }
}
