package com.moddingfromamod.wishbuilder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.moddingfromamod.ui.PickPage;
import com.moddingfromamod.ui.TuneDialog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>The editor: the Builder's own edit mode, driven by change operators instead of clicks</b> (
 * docs/MODIFY_LANGUAGE.md: the Editor and the Builder are kept in sync, the editor a thin mode of the Builder).
 *
 * <p>It reopens a kept make exactly as "✦ Change it in the Builder" does ({@link BuilderFlow#editing}), walks the
 * same pages a child would, and presses the same tiles: a change is applied on whichever page OFFERS its tile id
 * (the Builder tree's entry ids ARE the flow's tile ids), and a tune change on the tune dialog's own control, within
 * that control's own range. A page it does not change it walks past by pressing the current choice again
 * ({@link BuilderFlow#keepPress}), or Next on a multi-pick page. Then {@link BuilderFlow#spec} writes the spec the
 * dialog's own "Change it!" would. So:
 * <ul>
 *   <li>the editor can do nothing the Builder cannot: there is no slot table here to drift (the
 *       one list of what can change is the Builder's pages);</li>
 *   <li>every refusal is the Builder's own (a tile not on this world's Builder, two moves that clash, past a slider's
 *       end), and a refused change is never half-made: {@link Result#refusal} says why, and the wish goes to the
 *       wish worker as he typed it;</li>
 *   <li>the result is an ordinary Builder edit, run by {@code BuilderMake} like any other.</li>
 * </ul>
 *
 * <p>This is the CRAFTER half: a make with a kept spec. A thing the Magician made has no spec to reopen; its changes
 * go to the wish worker until a deterministic path for them is built and tested (docs/MODIFY_LANGUAGE.md §3, §11).
 *
 * <p>Pure: no Minecraft.
 */
public final class BuilderEdit {

    private BuilderEdit() {
    }

    /** The version of a modification this build reads and writes ({@code {"modify":1,…}}). */
    public static final int VERSION = 1;

    /** What an operator does to its slot (docs/MODIFY_LANGUAGE.md §2). */
    public enum Verb {
        /** Choose this value (a single-choice page or a tune control). */
        SET,
        /** Move an ordered tune control by {@code by} (size, strength, fill, floors, frame). */
        STEP,
        /** Turn a multi-pick tile on (a power, a move, a part, something outside). */
        ADD,
        /** Turn a multi-pick tile off. */
        REMOVE
    }

    /**
     * One change. {@code slot} is the Builder tree's slot id ({@code powers}, {@code size}, …); {@code value} is the
     * tree's entry id ({@code power:flies}) or a tune value ({@code ocean}, {@code 3}); {@code by} is for a STEP.
     */
    public record Op(Verb verb, String slot, String value, int by) {
        public Op {
            slot = slot == null ? "" : slot.strip();
            value = value == null ? "" : value.strip();
        }

        public static Op set(String slot, String value) {
            return new Op(Verb.SET, slot, value, 0);
        }

        public static Op step(String slot, int by) {
            return new Op(Verb.STEP, slot, "", by);
        }

        public static Op add(String slot, String value) {
            return new Op(Verb.ADD, slot, value, 0);
        }

        public static Op remove(String slot, String value) {
            return new Op(Verb.REMOVE, slot, value, 0);
        }
    }

    /** The new spec, or why the Builder cannot make this change (then the wish goes to the wish worker). */
    public record Result(BuilderSpec spec, String refusal) {
        public boolean ok() {
            return refusal == null;
        }

        static Result no(String why) {
            return new Result(null, why);
        }
    }

    /** The name is the one free-text slot: set by name, swept like any wish text where it is used. */
    static final String NAME = "name";

    /**
     * Apply {@code ops} to the make {@code id} kept as {@code prior}, the way the Builder's own edit would.
     */
    public static Result apply(BuilderAllowlist allow, LibraryPresets presets, String id, BuilderSpec prior,
                               List<Op> ops) {
        if (prior == null) {
            return Result.no("there is no kept make to change");
        }
        if (ops == null || ops.isEmpty()) {
            return Result.no("there is nothing to change");
        }
        if (Catalogue.prefix(prior.from()).equals("mob") || prior.kind() == BuilderSpec.Kind.SUPPLIES) {
            return Result.no("the Builder keeps nothing of that to change");
        }
        BuilderFlow f;
        try {
            f = BuilderFlow.editing(allow, presets, id, prior);
        } catch (RuntimeException e) {
            return Result.no("the Builder cannot reopen it: " + e.getMessage());
        }
        List<Op> page = new ArrayList<>();
        List<Op> tune = new ArrayList<>();
        String name = null;
        for (Op op : ops) {
            if (op.slot().equals(NAME)) {
                if (op.verb() != Verb.SET || op.value().isEmpty()) {
                    return Result.no("a name can only be set");
                }
                name = op.value();
            } else if (op.verb() == Verb.STEP || !(op.value().contains(":") || op.value().contains("/"))) {
                // a tune control's value is plain ("ocean", "3"); a page tile's id has a prefix, or is a preset's
                // path ("buildings/barn", "pictures/duck":, a building's type and a picture's subject)
                tune.add(op);
            } else {
                page.add(op);
            }
        }
        String why = page.isEmpty() ? walkToTune(f, List.of(), prior) : walkPages(f, page, prior);
        if (why != null) {
            return Result.no(why);
        }
        Map<String, String> values = new LinkedHashMap<>();
        TuneDialog dialog = f.tune();
        for (TuneDialog.Input in : dialog.inputs()) {
            String start = initial(in);
            if (start != null) {
                values.put(in.key(), start);
            }
        }
        for (Op op : tune) {
            String said = tuneOp(dialog, values, op);
            if (said != null) {
                return Result.no(said);
            }
        }
        BuilderSpec spec;
        try {
            spec = f.spec(values);
        } catch (RuntimeException e) {
            return Result.no("the Builder refused it: " + e.getMessage());
        }
        if (name != null) {
            spec = spec.withName(name);
        }
        return new Result(spec, null);
    }

    /**
     * Walk back as far as the Builder's own edit lets him (an edit keeps its kind, and a kind that reopens at the
     * tune dialog has no page to go back to), then forward again, applying each page change where its tile is
     * offered and keeping every other choice. A change no page offered is not the Builder's to make.
     */
    private static String walkPages(BuilderFlow f, List<Op> page, BuilderSpec prior) {
        for (int guard = 0; guard < 32; guard++) {
            BuilderFlow.Step was = f.step();
            if (f.press(PickPage.BACK_ID).step() == was) {
                break;
            }
        }
        return walkToTune(f, page, prior);
    }

    /** Walk forward to the tune dialog, applying {@code page} changes on the pages that offer them. */
    private static String walkToTune(BuilderFlow f, List<Op> page, BuilderSpec prior) {
        List<Op> left = new ArrayList<>(page);
        for (int guard = 0; guard < 48 && f.step() != BuilderFlow.Step.TUNE; guard++) {
            BuilderFlow.Step here = f.step();
            String via = lookWay(f, left);
            if (via != null) {
                // a look's tile is two pages deep (🎨 More looks…, then its category): press the way to it
                BuilderFlow.Outcome o = f.press(via);
                if (!o.say().isEmpty()) {
                    return o.say();
                }
                continue;
            }
            String keep = f.keepPress();   // read BEFORE any press here: a press can clear what follows it
            List<Op> mine = new ArrayList<>();
            for (Op op : left) {
                if (offered(f, op.value())) {
                    mine.add(op);
                }
            }
            left.removeAll(mine);
            boolean moved = false;
            for (Op op : mine) {
                String said = pageOp(f, op);
                if (said != null) {
                    return said;
                }
                if (f.step() != here) {
                    moved = true;   // a single-choice press moves on by itself
                    break;
                }
            }
            if (moved) {
                continue;
            }
            String press = BuilderFlow.multiPick(here) ? PickPage.NEXT_ID : keep;
            if (press == null && here == BuilderFlow.Step.ITEM_POWER && !prior.opt("power").isEmpty()) {
                // a new LOOK on another stock item (a crown worn on the head → a backpack on the back) clears the
                // power the Builder had; the kept make's own power is pressed again where the new item offers it
                String had = "itempower:" + prior.opt("power");
                if (!offered(f, had)) {
                    BuilderItemPowers.Power w = BuilderItemPowers.power(prior.opt("power"));
                    return "that can't have " + (w == null ? prior.opt("power") : w.label().toLowerCase(Locale.ROOT));
                }
                press = had;
            }
            if (press == null) {
                return "the Builder's '" + here.name().toLowerCase(Locale.ROOT) + "' page has no choice to keep";
            }
            BuilderFlow.Outcome o = f.press(press);
            if (!o.say().isEmpty()) {
                return o.say();
            }
            if (f.step() == here && !BuilderFlow.multiPick(here)) {
                return "the Builder stayed on its '" + here.name().toLowerCase(Locale.ROOT) + "' page";
            }
        }
        if (f.step() != BuilderFlow.Step.TUNE) {
            return "the Builder's pages did not reach the end";
        }
        if (!left.isEmpty()) {
            return "the Builder offers no '" + left.get(0).value() + "' for this";
        }
        return null;
    }

    /**
     * the press that leads toward a waiting LOOK change, or null. A look's tile is on its category's page, behind
     * "🎨 More looks…" on "What is it?" and the category on the looks page; the Builder's own pages are walked to it,
     * exactly as a child would press, so a look this world does not offer is still refused by the Builder.
     */
    private static String lookWay(BuilderFlow f, List<Op> left) {
        for (Op op : left) {
            if (!op.slot().equals(BuilderLooks.SLOT) || offered(f, op.value())) {
                continue;
            }
            BuilderLooks.Look l = BuilderLooks.look(op.value());
            if (l == null) {
                continue;
            }
            if (offered(f, BuilderLooks.MORE_ID) && !selected(f, BuilderLooks.MORE_ID)) {
                return BuilderLooks.MORE_ID;
            }
            String group = BuilderLooks.GROUP_PREFIX + l.group();
            if (offered(f, group) && !selected(f, group)) {
                return group;
            }
        }
        return null;
    }

    /** Is {@code tile} on the page the flow is showing now? */
    private static boolean offered(BuilderFlow f, String tile) {
        try {
            return f.page().tiles().stream().anyMatch(t -> t.id().equals(tile));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean selected(BuilderFlow f, String tile) {
        return f.page().tiles().stream().anyMatch(t -> t.id().equals(tile) && t.selected());
    }

    /** One page change, as the press a child would make. */
    private static String pageOp(BuilderFlow f, Op op) {
        boolean on = selected(f, op.value());
        boolean press = switch (op.verb()) {
            case ADD, SET -> !on;
            case REMOVE -> on;
            case STEP -> false;
        };
        if (op.verb() == Verb.REMOVE && !BuilderFlow.multiPick(f.step())) {
            return "only a pick that can be taken off can be removed";
        }
        if (!press) {
            return null;   // already as asked: nothing to press
        }
        BuilderFlow.Outcome o = f.press(op.value());
        return o.say().isEmpty() ? null : o.say();
    }

    /** One tune change, within the dialog's own control and range. */
    private static String tuneOp(TuneDialog dialog, Map<String, String> values, Op op) {
        TuneDialog.Input in = dialog.inputs().stream().filter(i -> i.key().equals(op.slot())).findFirst().orElse(null);
        if (in == null && op.slot().equals("size")) {   //: a creature's size is its named size
            in = dialog.inputs().stream().filter(i -> i.key().equals("mobsize")).findFirst().orElse(null);
        }
        if (in == null) {
            return "the Builder does not ask '" + op.slot() + "' for this";
        }
        if (in instanceof TuneDialog.Slider s) {
            int now;
            try {
                now = Integer.parseInt(values.getOrDefault(s.key(), String.valueOf(s.initial())));
            } catch (NumberFormatException e) {
                now = s.initial();
            }
            int want;
            if (op.verb() == Verb.STEP) {
                want = now + op.by() * s.step();
            } else if (op.verb() == Verb.SET) {
                try {
                    want = Integer.parseInt(op.value());
                } catch (NumberFormatException e) {
                    return "'" + op.value() + "' is not a " + s.label().toLowerCase(Locale.ROOT);
                }
            } else {
                return "'" + s.key() + "' is set or stepped, not added to";
            }
            if (want < s.start() || want > s.end()) {
                return "the Builder's " + s.label().toLowerCase(Locale.ROOT) + " goes from " + s.start() + " to "
                        + s.end();
            }
            values.put(s.key(), String.valueOf(want));
            return null;
        }
        if (in instanceof TuneDialog.Choice c && op.verb() == Verb.STEP) {
            // an ordered choice (the named sizes) steps through its options, never past either end
            List<String> ids = c.options().stream().map(TuneDialog.Option::id).toList();
            int now = ids.indexOf(values.getOrDefault(c.key(), c.initial()));
            int want = now + op.by();
            if (now < 0 || want < 0 || want >= ids.size()) {
                return "the Builder's " + c.label().toLowerCase(Locale.ROOT) + " goes from " + c.options().get(0).display()
                        + " to " + c.options().get(ids.size() - 1).display();
            }
            values.put(c.key(), ids.get(want));
            return null;
        }
        if (in instanceof TuneDialog.Choice c) {
            String want = op.value().contains(":") ? op.value().substring(op.value().indexOf(':') + 1) : op.value();
            if (op.verb() != Verb.SET || c.options().stream().noneMatch(o -> o.id().equals(want))) {
                return "the Builder has no '" + op.value() + "' for " + c.label().toLowerCase(Locale.ROOT);
            }
            values.put(c.key(), want);
            return null;
        }
        return "the Builder's '" + op.slot() + "' is typed, not chosen";
    }

    private static String initial(TuneDialog.Input in) {
        if (in instanceof TuneDialog.Slider s) {
            return String.valueOf(s.initial());
        }
        if (in instanceof TuneDialog.Choice c) {
            return c.initial();
        }
        return null;
    }

    // ── the stored shape: {"modify":1,"target":{...},"ops":[...]} ─────────────────────────────────────────────

    /** A modification of one thing: what it is and what changes (docs/MODIFY_LANGUAGE.md §1). */
    public record Modification(String id, String kind, String origin, List<Op> ops) {
        public Modification {
            ops = ops == null ? List.of() : List.copyOf(ops);
        }

        public JsonObject toJson() {
            JsonObject j = new JsonObject();
            j.addProperty("modify", VERSION);
            JsonObject t = new JsonObject();
            t.addProperty("id", id);
            t.addProperty("kind", kind);
            t.addProperty("origin", origin);
            j.add("target", t);
            JsonArray a = new JsonArray();
            for (Op op : ops) {
                JsonObject o = new JsonObject();
                o.addProperty("op", op.verb().name().toLowerCase(Locale.ROOT));
                o.addProperty("slot", op.slot());
                if (!op.value().isEmpty()) {
                    o.addProperty("value", op.value());
                }
                if (op.verb() == Verb.STEP) {
                    o.addProperty("by", op.by());
                }
                a.add(o);
            }
            j.add("ops", a);
            return j;
        }

        /** Read a stored modification; a newer version or a malformed one is refused by name, never guessed at. */
        public static Modification read(JsonObject j) {
            if (j == null || !j.has("modify") || !j.get("modify").isJsonPrimitive()) {
                throw new IllegalArgumentException("not a modification");
            }
            int v = j.get("modify").getAsInt();
            if (v != VERSION) {
                throw new IllegalArgumentException("modification version " + v + " is not " + VERSION);
            }
            JsonObject t = j.has("target") && j.get("target").isJsonObject() ? j.getAsJsonObject("target") : new JsonObject();
            List<Op> ops = new ArrayList<>();
            if (j.has("ops") && j.get("ops").isJsonArray()) {
                for (JsonElement e : j.getAsJsonArray("ops")) {
                    JsonObject o = e.getAsJsonObject();
                    Verb verb;
                    try {
                        verb = Verb.valueOf(BuilderSpec.str(o, "op").toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException bad) {
                        throw new IllegalArgumentException("no operator '" + BuilderSpec.str(o, "op") + "'");
                    }
                    ops.add(new Op(verb, BuilderSpec.str(o, "slot"), BuilderSpec.str(o, "value"),
                            BuilderSpec.num(o, "by", 0)));
                }
            }
            return new Modification(BuilderSpec.str(t, "id"), BuilderSpec.str(t, "kind"), BuilderSpec.str(t, "origin"), ops);
        }
    }
}
