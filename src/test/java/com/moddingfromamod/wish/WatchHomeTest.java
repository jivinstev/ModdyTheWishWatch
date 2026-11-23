package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.moddingfromamod.ui.TuneDialog;
import com.moddingfromamod.ui.WishBoxDialog;
import java.util.List;
import org.junit.jupiter.api.Test;

/** the text-first watch's buttons, their order, their words, and the Java dialog. */
class WatchHomeTest {

    private static List<String> labels(WatchHome.Facts f) {
        return WatchHome.acts(f).stream().map(WatchHome.Act::label).toList();
    }

    @Test
    void aQuietWatchShowsOnlyWhatAlwaysApplies() {
        assertEquals(List.of("📚 My wishes", "📖 What do I do now?"), labels(new WatchHome.Facts(false, null, null, false)));
    }

    @Test
    void everyButtonInThePinnedOrderWhenEachApplies() {
        assertEquals(List.of("📚 My wishes", "✦ Build it myself", "✋ Stop the wish Moddy is making",
                        "🔍 Watch my wish being made", "↩ Undo my last wish: Sparky", "🏠 Take me back",
                        "📖 What do I do now?"),
                labels(new WatchHome.Facts(true, "ab12cd", "Sparky", true)));
    }

    @Test
    void eachButtonRunsItsExistingPath() {
        var acts = WatchHome.acts(new WatchHome.Facts(true, "ab12cd", "Sparky", true));
        assertEquals(List.of("moddy library", "moddy builder", "moddy stop", "moddy go ab12cd", "moddy no", "moddy back",
                "moddy now"), acts.stream().map(WatchHome.Act::command).toList());
    }

    @Test
    void theHostsMagicianTileSitsBeforeTheGuideAndOpensTheSetup() {
        // every button at once, the Magician tile included, still fits the wish box (at most 8)
        var acts = WatchHome.acts(new WatchHome.Facts(true, "ab12cd", "Sparky", true, WatchHome.MAGICIAN));
        assertEquals(WishBoxDialog.MAX_BUTTONS, acts.size());
        assertEquals("✪ Turn on the Magician", acts.get(acts.size() - 2).label());
        assertEquals("moddy magician", acts.get(acts.size() - 2).command());
        new WishBoxDialog(WishCompose.dialog("t", ""), acts.stream()
                .map(a -> new WishBoxDialog.Button(a.label(), a.command())).toList());
        // no tile when the player has none (a dedicated server, a locked setting)
        assertTrue(labels(new WatchHome.Facts(false, null, null, false, null)).stream()
                .noneMatch(l -> l.startsWith("✪")));
    }

    @Test
    void theUndoButtonNamesTheWishAndStaysOneLine() {
        assertEquals("↩ Undo my last wish: Sparky", WatchHome.undoLabel("Sparky"));
        assertEquals("↩ Undo my last wish: a flying pi…", WatchHome.undoLabel("a flying pig with a top hat"));
        assertEquals("↩ Undo my last wish", WatchHome.undoLabel(" "));
    }

    private static String text(com.google.gson.JsonElement action) {
        return action.getAsJsonObject().getAsJsonObject("label").get("text").getAsString();
    }

    @Test
    void theJavaBoxIsAMultiActionDialogWhoseEscapeRunsNothing() {
        TuneDialog box = WishCompose.dialog("✨ Make a Wish!", "");
        // ODD number of actions: Cancel fills the grid's last cell, Submit spans the last row alone.
        JsonObject j = WishCompose.home(box, List.of(new WishBoxDialog.Button("📚 My wishes", "moddy library"))).json();
        assertEquals("minecraft:multi_action", j.get("type").getAsString());
        assertEquals(2, j.get("columns").getAsInt());
        var actions = j.getAsJsonArray("actions");
        assertEquals(List.of("📚 My wishes", "Cancel", "✨ Make my wish"),
                List.of(text(actions.get(0)), text(actions.get(1)), text(actions.get(2))));
        assertEquals("moddy library", actions.get(0).getAsJsonObject().getAsJsonObject("action").get("command").getAsString());
        assertTrue(!actions.get(1).getAsJsonObject().has("action"), "Cancel runs nothing (#2000)");
        assertTrue(!j.has("exit_action"), "Cancel is in the grid, not the footer");
        // "Make my wish" is special — the last row alone, as wide as both columns, bold and green.
        var submit = actions.get(2).getAsJsonObject();
        assertEquals("minecraft:dynamic/run_command", submit.getAsJsonObject("action").get("type").getAsString());
        assertEquals(WishBoxDialog.SUBMIT_WIDTH, submit.get("width").getAsInt());
        assertTrue(submit.getAsJsonObject("label").get("bold").getAsBoolean());
        assertEquals("green", submit.getAsJsonObject("label").get("color").getAsString());
        assertEquals(WishBoxDialog.WIDTH, actions.get(0).getAsJsonObject().get("width").getAsInt());
        assertEquals(1, j.getAsJsonArray("inputs").size());

        // EVEN: the actions fill their rows, Submit alone last, Cancel in the footer with no action.
        JsonObject e = WishCompose.home(box, List.of(new WishBoxDialog.Button("📚 My wishes", "moddy library"),
                new WishBoxDialog.Button("📖 What do I do now?", "moddy now"))).json();
        var ea = e.getAsJsonArray("actions");
        assertEquals(3, ea.size());
        assertEquals("✨ Make my wish", text(ea.get(2)));
        assertTrue(!e.getAsJsonObject("exit_action").has("action"), "Escape must run nothing (#2000)");
    }

    @Test
    void aButtonRunsAPlainCommand() {
        assertThrows(IllegalArgumentException.class, () -> new WishBoxDialog.Button("x", "/moddy stop"));
        assertThrows(IllegalArgumentException.class, () -> new WishBoxDialog.Button("x", "moddy go $(id)"));
    }

    /** a box Moddy filled says so in one line above it, with a small red "✖ Clear" link that empties it. */
    @Test
    void aFilledBoxHasASmallClearLinkAboveIt() {
        TuneDialog box = WishCompose.dialog("Moddy", "Make Sparky follow me");
        var buttons = List.of(new WishBoxDialog.Button("📚 My wishes", "moddy library"),
                new WishBoxDialog.Button("📖 What do I do now?", "moddy now"));
        JsonObject j = WishCompose.home(box, buttons, true).json();
        String body = j.getAsJsonArray("body").toString();
        assertTrue(body.contains("✖ Clear") && body.contains("/moddy wishbox --clear"), body);
        var actions = j.getAsJsonArray("actions");
        assertEquals(WishBoxDialog.SUBMIT_WIDTH, actions.get(actions.size() - 1).getAsJsonObject().get("width").getAsInt(),
                "Submit keeps its full row");
        assertTrue(WishCompose.home(box, buttons, false).json().toString().indexOf("--clear") < 0, "an empty box has no ✖");
    }
}
