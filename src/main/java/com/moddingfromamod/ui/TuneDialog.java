package com.moddingfromamod.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>A TUNE screen: one dialog</b> — the second of the two Moddy menu forms (docs/BEDROCK_COMPAT.md
 * "Menu mapping"). Pure: it writes the vanilla 1.21.6+ dialog JSON that {@link MenuKit} decodes with
 * vanilla's own codec, and Geyser turns into a Bedrock form.
 *
 * <p>Every rule below was MEASURED on Java 1.21.8 and a real Bedrock phone and is enforced
 * here, so a new menu cannot break one by accident:
 * <ul>
 *   <li><b>{@code minecraft:notice}, one action.</b> A {@code multi_action} dialog reaches Bedrock with
 *       a "Please select an option" dropdown above Submit — with two actions, with Back as its exit
 *       action, and with ONE action.</li>
 *   <li><b>At most {@link #MAX_INPUTS} inputs.</b> At 854×480 seven scrolled on both editions, and Java
 *       pins its button over the bottom of the scroll.</li>
 *   <li><b>No toggles</b> ({@code minecraft:boolean}): a Bedrock toggle flips only on a touch at its
 *       right end. An on/off choice is a {@link PickPage.Tile} instead.</li>
 *   <li><b>No item body:</b> Geyser drops it. The preview is the picked tile and the title.</li>
 *   <li><b>A text box is last and starts filled in:</b> its value is the command's greedy tail, and a
 *       phone keyboard covers Submit (Back closes it, Enter does not), so leaving it as it is must be
 *       a fine answer.</li>
 *   <li><b>One exception, {@link Words}: the wish box</b> (D2) — the only box whose whole point
 *       is that he types in it, so it may open empty. A blank send is a CANCEL (Java's
 *       Escape and a closed phone form both run the button with the box empty), so it just closes; on a
 *       phone the box stays open while he types and Submit sends it (an emulated HARDWARE Enter closed it).</li>
 * </ul>
 */
public record TuneDialog(String title, List<Line> body, List<Input> inputs, String actionLabel, String command) {

    /** A dialog with no lines of its own above the inputs: every menu but the wish box. */
    public TuneDialog(String title, List<Input> inputs, String actionLabel, String command) {
        this(title, List.of(), inputs, actionLabel, command);
    }

    /** At most this many {@link Line}s: each one pushes the inputs and the button down a phone's screen. */
    public static final int MAX_LINES = 2;

    /**
     * One plain line of words above the inputs, optionally ending in an address. On Java the address is a
     * link that opens {@code url}; a Bedrock form cannot carry a link, so there the same words arrive as a label
     * and the address is read and typed. So {@code shown} must be an address a person can type.
     *
     * <p>A {@code url} that starts with {@code /} is a COMMAND link instead (the wish box's "✪ Magician: off.
     * Turn it on" opens the Magician setup): Java runs it on a click, as a dialog button would; on Bedrock the words
     * are only words, so the same action must be reachable another way (the watch's own tile).
     */
    public record Line(String words, String shown, String url) {
        public Line(String words) {
            this(words, "", "");
        }

        public Line {
            shown = shown == null ? "" : shown;
            url = url == null ? "" : url;
            if (words == null || words.isBlank() || words.length() + shown.length() > 60) {
                throw new IllegalArgumentException("a dialog line is 1–60 characters, the address included: '" + words + shown + "'");
            }
            if (shown.isEmpty() != url.isEmpty() || !url.isEmpty() && !url.startsWith("https://")
                    && !(url.startsWith("/") && url.length() > 1 && !url.contains("$("))) {
                throw new IllegalArgumentException("a line's link is shown AND opens an https:// URL or runs a /command,"
                        + " or neither");
            }
        }

        JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("type", "minecraft:plain_message");
            JsonObject t = text(words);
            if (!shown.isEmpty()) {
                JsonObject link = text(shown);
                link.addProperty("color", "aqua");
                link.addProperty("underlined", true);
                JsonObject click = new JsonObject();
                if (url.startsWith("/")) {
                    click.addProperty("action", "run_command");
                    click.addProperty("command", url);
                } else {
                    click.addProperty("action", "open_url");
                    click.addProperty("url", url);
                }
                link.add("click_event", click);
                JsonArray extra = new JsonArray();
                extra.add(link);
                t.add("extra", extra);
            }
            o.add("contents", t);
            o.addProperty("width", Words.WIDTH);
            return o;
        }
    }

    public static final int MAX_INPUTS = 4;

    /** One control. Its {@code key} becomes a {@code $(key)} in the action's command. */
    public sealed interface Input permits Slider, Choice, Text, Words {
        String key();

        JsonObject json();
    }

    /** A number on a track: one touch on Bedrock. */
    public record Slider(String key, String label, int start, int end, int step, int initial) implements Input {
        public Slider {
            if (start >= end || step < 1 || initial < start || initial > end) {
                throw new IllegalArgumentException("slider " + key + " " + start + ".." + end + " @" + initial);
            }
        }

        @Override
        public JsonObject json() {
            JsonObject o = base("minecraft:number_range", key, label);
            o.addProperty("start", start);
            o.addProperty("end", end);
            o.addProperty("step", step);
            o.addProperty("initial", initial);
            return o;
        }
    }

    /** One option of a {@link Choice}: the id sent, the words shown. */
    public record Option(String id, String display) {
    }

    /** A dropdown on both editions (Java: a cycle button). */
    public record Choice(String key, String label, List<Option> options, String initial, int width,
                         boolean labelOnButton) implements Input {
        /** Vanilla's own width for the cycle button. */
        public static final int DEFAULT_WIDTH = 200;

        public Choice(String key, String label, List<Option> options, String initial) {
            this(key, label, options, initial, DEFAULT_WIDTH, true);
        }

        /**
         * A choice whose options say everything: as wide as asked, and on Java the button shows the
         * option alone, not "Label: option". Geyser then sends Bedrock's dropdown with NO label (measured,
         *), so the options must say everything on both editions.
         */
        public static Choice wide(String key, String label, List<Option> options, String initial, int width) {
            return new Choice(key, label, options, initial, width, false);
        }

        public Choice {
            if (width < 1 || width > 1024) {
                throw new IllegalArgumentException("choice " + key + ": width 1–1024");
            }
            options = List.copyOf(options);
            if (options.isEmpty()) {
                throw new IllegalArgumentException("choice " + key + " has no options");
            }
            for (Option o : options) {
                if (!o.id().matches("[A-Za-z0-9_.:-]+")) {
                    throw new IllegalArgumentException("choice " + key + ": option id '" + o.id()
                            + "' must be one word, it is a command argument");
                }
            }
        }

        @Override
        public JsonObject json() {
            JsonObject o = base("minecraft:single_option", key, label);
            if (width != DEFAULT_WIDTH) {
                o.addProperty("width", width);   // Java only: Bedrock's dropdown is as wide as the form
            }
            if (!labelOnButton) {
                o.addProperty("label_visible", false);   // and Geyser sends an empty label: measured,
            }
            JsonArray arr = new JsonArray();
            for (Option opt : options) {
                JsonObject e = new JsonObject();
                e.addProperty("id", opt.id());
                e.add("display", text(opt.display()));
                if (opt.id().equals(initial)) {
                    e.addProperty("initial", true);
                }
                arr.add(e);
            }
            o.add("options", arr);
            return o;
        }
    }

    /** A text box. Always last, always pre-filled. */
    public record Text(String key, String label, String initial, int maxLength) implements Input {
        public Text {
            if (initial == null || initial.isBlank()) {
                throw new IllegalArgumentException("text " + key + " starts filled in (a phone keyboard hides Submit)");
            }
            if (maxLength < 1 || maxLength > 64) {
                throw new IllegalArgumentException("text " + key + " max length 1–64");
            }
        }

        @Override
        public JsonObject json() {
            JsonObject o = base("minecraft:text", key, label);
            o.addProperty("initial", initial);
            o.addProperty("max_length", maxLength);
            return o;
        }
    }

    /**
     * <b>The wish box</b> (D2): a long, several-line text box that MAY open
     * empty. Every other rule of a text box stands — it is last, it is the command's greedy tail.
     *
     * <p>Why it alone may start blank: {@link Text} starts filled in because leaving a NAME as it is
     * must be a fine answer when a phone keyboard hides Submit. A wish has no "as it is" — he must type
     * anyway, so the keyboard is up while he types and its Back reveals Submit after. A blank send is
     * a cancel and the box simply closes.
     *
     * <p>Lines are a HEIGHT, never vanilla's {@code max_lines}: a line limit silently refuses the next
     * keystroke once the words wrap past it, at a point that depends on the letters' widths. The cap is
     * {@code maxLength} characters alone, which a stock client enforces and the server re-applies.
     */
    public record Words(String key, String label, String initial, int maxLength, int lines) implements Input {
        /** Vanilla's line height (9) plus the box's padding (8), as its own default height is computed. */
        static final int LINE_PX = 9;
        public static final int WIDTH = 300;

        /**
         * The one character the template puts in front of the words. An EMPTY box otherwise
         * runs {@code moddy wishbox } with a trailing space, which Brigadier refuses before any code of
         * ours runs ("Incorrect argument for command") — so a blank Submit, and on Bedrock a CLOSED
         * form, showed a child a command error instead of closing.
         * With the mark the greedy tail is never empty; {@link #unmark} takes it off again.
         */
        public static final String MARK = "|";

        /** The words without the template's {@link #MARK} (a command typed by hand has none). */
        public static String unmark(String raw) {
            return raw != null && raw.startsWith(MARK) ? raw.substring(MARK.length()) : raw;
        }

        public Words {
            initial = initial == null ? "" : initial;
            if (maxLength < 1 || maxLength > 240) {
                throw new IllegalArgumentException("words " + key + " max length 1–240 (it rides a command line)");
            }
            if (initial.length() > maxLength) {
                throw new IllegalArgumentException("words " + key + ": the initial text is longer than the box");
            }
            if (lines < 1 || lines > 8) {
                throw new IllegalArgumentException("words " + key + ": 1–8 lines");
            }
        }

        @Override
        public JsonObject json() {
            JsonObject o = base("minecraft:text", key, label);
            o.addProperty("width", WIDTH);
            o.addProperty("initial", initial);
            o.addProperty("max_length", maxLength);
            JsonObject m = new JsonObject();
            m.addProperty("height", LINE_PX * lines + 8);
            o.add("multiline", m);
            return o;
        }
    }

    public TuneDialog {
        body = List.copyOf(body == null ? List.of() : body);
        if (body.size() > MAX_LINES) {
            throw new IllegalArgumentException(body.size() + " lines; a tune dialog holds at most " + MAX_LINES);
        }
        inputs = List.copyOf(inputs);
        if (inputs.size() > MAX_INPUTS) {
            throw new IllegalArgumentException(inputs.size() + " inputs; a tune dialog holds at most " + MAX_INPUTS);
        }
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < inputs.size(); i++) {
            Input in = inputs.get(i);
            if (!in.key().matches("[a-z][a-z0-9_]*") || !keys.add(in.key())) {
                throw new IllegalArgumentException("input key '" + in.key() + "' is not a unique plain word");
            }
            if ((in instanceof Text || in instanceof Words) && i != inputs.size() - 1) {
                throw new IllegalArgumentException("a text box is the LAST input (it is the command's greedy tail)");
            }
        }
        if (command == null || command.isBlank() || command.startsWith("/") || command.contains("$(")) {
            throw new IllegalArgumentException("the command is a plain prefix with no leading slash: '" + command + "'");
        }
        if (actionLabel == null || actionLabel.isBlank()) {
            throw new IllegalArgumentException("a tune dialog has one action, with words on it");
        }
    }

    /** The Java dialog's second button: closes it and runs nothing, as Escape does. */
    public static final String CANCEL = "Cancel";

    /** The command the action runs: the prefix, then every input's value in order. */
    public String template() {
        StringBuilder sb = new StringBuilder(command);
        for (Input in : inputs) {
            sb.append(in instanceof Words ? " " + Words.MARK + "$(" : " $(").append(in.key()).append(")");
        }
        return sb.toString();
    }

    /** The dialog, as vanilla's codec reads it. */
    public JsonObject json() {
        JsonObject d = new JsonObject();
        // A CONFIRMATION, not a notice: vanilla's NoticeDialog.onCancel() returns its one button, so
        // Escape on Java SUBMITTED the box (an empty wish, a Builder make with the defaults). A confirmation's
        // Escape runs its "no", which here has no action: Cancel cancels. On Bedrock the Moddy extension
        // (ModdyDialogs) turns it back into the one-button form Geyser drew before, whose close runs nothing.
        d.addProperty("type", "minecraft:confirmation");
        d.add("title", text(title));
        d.addProperty("can_close_with_escape", true);
        d.addProperty("pause", false);
        d.addProperty("after_action", "close");
        if (!body.isEmpty()) {
            JsonArray lines = new JsonArray();
            body.forEach(l -> lines.add(l.json()));
            d.add("body", lines);
        }
        JsonArray in = new JsonArray();
        inputs.forEach(i -> in.add(i.json()));
        d.add("inputs", in);
        JsonObject act = new JsonObject();
        if (inputs.isEmpty()) {
            // Vanilla refuses a dynamic template with no $(…) in it ("No variables in macro"): a dialog
            // that only confirms runs its command as it is (found by BuilderMenusGameTest, a rule's dialog).
            act.addProperty("type", "minecraft:run_command");
            act.addProperty("command", command);
        } else {
            act.addProperty("type", "minecraft:dynamic/run_command");
            act.addProperty("template", template());
        }
        JsonObject button = new JsonObject();
        button.add("label", text(actionLabel));
        button.add("action", act);
        d.add("yes", button);
        JsonObject no = new JsonObject();
        no.add("label", text(CANCEL));
        d.add("no", no);   // no action: pressing it, or Escape, closes and runs nothing
        return d;
    }

    static JsonObject base(String type, String key, String label) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.addProperty("key", key);
        o.add("label", text(label));
        return o;
    }

    static JsonObject text(String s) {
        JsonObject t = new JsonObject();
        t.addProperty("text", s);
        return t;
    }

    /** The input keys, in order — what the command's arguments are. */
    public List<String> keys() {
        List<String> out = new ArrayList<>();
        inputs.forEach(i -> out.add(i.key()));
        return out;
    }
}
