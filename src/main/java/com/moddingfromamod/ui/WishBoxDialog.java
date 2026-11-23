package com.moddingfromamod.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;

/**
 * <b>The wish box with its buttons</b>: the text-first watch on JAVA. One {@link TuneDialog}'s
 * text box and Submit, then one plain button per thing he can do now (stop the wish being made, undo his last wish, his
 * wishes, the Builder, "What do I do now?"). Pure: it writes the vanilla {@code minecraft:multi_action} dialog JSON
 * {@link MenuKit#wishBox} decodes.
 *
 * <p><b>Java only, by construction.</b> A Bedrock form cannot hold a text field AND several buttons: Geyser turns a
 * multi-action dialog into a "Please select an option" dropdown above Submit ({@link TuneDialog}'s first rule). So a
 * Bedrock player never gets this dialog: his watch opens a button page instead ({@code WatchHome}, design B2), and
 * {@link MenuKit#wishBox} refuses a Bedrock player. The difference is a design deviation in the register
 * (docs/BEDROCK_COMPAT.md, D-row).
 *
 * <p>Escape, and the exit button, run nothing: a closed box never submits.
 */
public record WishBoxDialog(TuneDialog box, List<Button> buttons, Button clear) {

    /** The box with no ✖ (it holds nothing of Moddy's). */
    public WishBoxDialog(TuneDialog box, List<Button> buttons) {
        this(box, buttons, null);
    }

    /**
     * (a quick, small button on the side of the wish box to clear it, not taking half the
     * row): the ✖ that empties a box Moddy filled. Vanilla puts no button beside a text field, and a narrow button in
     * the action grid widens its whole COLUMN (vanilla sizes a column by its widest cell: photographed, the dialog ran
     * off the screen). So it is a small red link in the box's own line of words, just above the box: "✖ Clear".
     */
    public static final String CLEAR_LABEL = "✖ Clear";

    /** One button under the box: its words, and the plain {@code moddy …} command it runs (no slash, no {@code $(…)}). */
    public record Button(String label, String command) {
        public Button {
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("a button has words on it");
            }
            if (command == null || command.isBlank() || command.startsWith("/") || command.contains("$(")) {
                throw new IllegalArgumentException("a button runs a plain command: '" + command + "'");
            }
        }
    }

    /** At most this many buttons besides Submit: each is a line of the screen. */
    public static final int MAX_BUTTONS = 8;
    /**
     * Two columns of actions, then "✨ Make my wish" ALONE on the last row, as wide as both columns (it
     * must not look like another button). Vanilla packs a multi-action dialog's buttons into full rows of
     * {@code columns} and gives only the LAST row a span ({@code DialogScreen.packControlsIntoColumns}), so Submit goes
     * last. One column with Submit first was tried and photographed: at 854×480 (GUI scale 2) five buttons scrolled
     * past the screen, "What do I do now?" below the fold.
     */
    public static final int COLUMNS = 2;
    /**
     * Each button's width on Java. Vanilla's default, 150, cut "✋ Stop the wish Moddy is making" and a named undo, which
     * then scroll (photographed, step 3); two of these side by side still fit a 427-wide (854 px at scale 2) screen.
     */
    public static final int WIDTH = 200;
    /** Submit's width: both columns and the gap between them, so it is the bar under the grid. */
    public static final int SUBMIT_WIDTH = 2 * WIDTH + 2;

    public WishBoxDialog {
        buttons = List.copyOf(buttons);
        if (buttons.size() > MAX_BUTTONS) {
            throw new IllegalArgumentException(buttons.size() + " buttons; the wish box holds at most " + MAX_BUTTONS);
        }
    }

    /** The dialog, as vanilla's codec reads it. */
    public JsonObject json() {
        JsonObject one = box.json();   // the box as a TUNE: title, body, inputs and its Submit
        JsonObject d = new JsonObject();
        d.addProperty("type", "minecraft:multi_action");
        for (String k : List.of("title", "can_close_with_escape", "pause", "after_action", "body", "inputs")) {
            if (one.has(k)) {
                d.add(k, one.get(k));
            }
        }
        if (clear != null) {   //: "💡 Moddy's idea  ✖ Clear", the ✖ a small red link that empties the box
            JsonObject line = new JsonObject();
            line.addProperty("type", "minecraft:plain_message");
            JsonObject t = TuneDialog.text("💡 Moddy's idea is in the box   ");
            t.addProperty("color", "gray");
            JsonObject x = TuneDialog.text(clear.label());
            x.addProperty("color", "red");
            x.addProperty("bold", true);
            JsonObject click = new JsonObject();
            click.addProperty("action", "run_command");
            click.addProperty("command", "/" + clear.command());
            x.add("click_event", click);
            JsonArray extra = new JsonArray();
            extra.add(x);
            t.add("extra", extra);
            line.add("contents", t);
            line.addProperty("width", TuneDialog.Words.WIDTH);
            JsonArray body = d.has("body") ? d.getAsJsonArray("body").deepCopy() : new JsonArray();
            body.add(line);
            d.add("body", body);
        }
        JsonArray actions = new JsonArray();
        for (Button b : buttons) {
            JsonObject act = new JsonObject();
            act.addProperty("type", "minecraft:run_command");
            act.addProperty("command", b.command());
            JsonObject o = new JsonObject();
            o.add("label", TuneDialog.text(b.label()));
            o.add("action", act);
            o.addProperty("width", WIDTH);
            actions.add(o);
        }
        JsonObject cancel = new JsonObject();
        cancel.add("label", TuneDialog.text(TuneDialog.CANCEL));   // no action: it closes and runs nothing
        // An ODD number of actions would leave the last one beside Submit on the spanning row: Cancel fills the grid's
        // last cell instead, so Submit always has its row to itself. With an even number Cancel stays in the footer.
        boolean cancelInGrid = buttons.size() % 2 == 1;
        if (cancelInGrid) {
            JsonObject c = cancel.deepCopy();
            c.addProperty("width", WIDTH);
            actions.add(c);
        }
        JsonObject submit = one.getAsJsonObject("yes").deepCopy();
        submit.addProperty("width", SUBMIT_WIDTH);
        JsonObject label = submit.getAsJsonObject("label");
        label.addProperty("bold", true);
        label.addProperty("color", "green");
        actions.add(submit);   // LAST: the only row vanilla lets span
        d.add("actions", actions);
        d.addProperty("columns", COLUMNS);
        if (!cancelInGrid) {
            d.add("exit_action", cancel);   // Escape or Cancel closes and runs nothing
        }
        return d;
    }
}
