package com.moddingfromamod.wishbuilder;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * <b>The Builder's changes on a creature the MAGICIAN made</b> (slice 1). A Magician creature has
 * no Builder spec, but some of what it is IS a Builder choice: its size, the Builder's powers, its temper. Those are
 * read from the creature's own record, shown on the Builder's own pages, and changed by sending ONLY the changed
 * fields to the tool's own edit form ({@code sculpt_creature start_from} itself, which keeps its parts and its record
 * under whatever the call names; {@code modify_mob} for "Follows you" / "Stays put"). Everything else, every part and
 * every power the Builder does not offer, stays exactly as the Magician made it.
 *
 * <p>What the Builder cannot change on it (its body: a new body is a new creature) is SHOWN, greyed, and says why;
 * pressing it changes nothing. The editor never does what the Builder cannot (docs/MODIFY_LANGUAGE.md §5).
 *
 * <p>Pure: no Minecraft.
 */
public final class MagicianCreature {

    private MagicianCreature() {
    }

    /** The Magician's mark (the wish box's "✪ Magician"), at the END of a name: a Pocket grid drops a leading symbol. */
    public static final String MARK = "✪";

    /** Said once, in chat, when the Builder opens on a Magician thing; also the tune dialog's first line. */
    public static final String SAID = "✪ The Magician made this! The Builder can change some things about it. "
            + "Everything else stays just as the Magician made it.";

    /**
     * The greyed body tile: a BLOCKED tile (an earlier issue's grey, {@code PickPage.BLOCKED_ITEM}), so on a phone its reason,
     * {@link #BODY_LINE}, is drawn as a second line under its name (a Pocket tile otherwise shows its name only: the
     * reason said in chat landed under the reopened form and was never seen) and it reads as "not this one"
     * rather than as the gray dye of "Hide from my watch". Pressing it also says {@link #BODY_SAY}.
     */
    public static final String BODY_ID = "magician:body";
    public static final String BODY_LABEL = "Body " + MARK;
    /** Its line, short like an earlier issue's others ("Can't with Breathes fire"): it is drawn under a Pocket grid tile. */
    public static final String BODY_LINE = "The Magician's, it stays";
    public static final String BODY_SAY = "The Magician made this body, so the Builder can't swap it.";

    /**
     * The thing page's verb on his Magician creature: short, so a Pocket grid draws it on one line like the
     * page's other verbs. "✦ Change it in the Builder ✪" wrapped to three, the ✪ alone on the last, against the tile below.
     */
    public static final String VERB_LABEL = "✦ Change it " + MARK;

    /**
     * The note in the tune dialog: at most two lines of at most 60 characters (TuneDialog), so it is said short. No ✪ in
     * it: on a phone a form label holding one draws the WHOLE line in Bedrock's smaller fallback font; the
     * dialog's title ("Change Lumi ✪") carries the mark.
     */
    public static final List<String> DIALOG_LINES = List.of("The Magician made this: the Builder changes some of it.",
            "The rest stays just as the Magician made it.");

    /** A Magician creature's size now is the Builder's middle step; the slider goes a step down or up from there. */
    public static final int NOW = 2;

    /** The Builder's temper words in a creature's own powers (BuilderLowering.temperPowers). */
    static final Set<String> TEMPER_WORDS = Set.of("friendly", "skittish", "hostile", "boss");

    /** What the Builder needs to know of the creature: read from its species record. */
    public record Thing(String id, String name, double scale, List<String> powers, JsonArray parts) {
        /** Without its parts: no colours can be read, so none are offered. */
        public Thing(String id, String name, double scale, List<String> powers) {
            this(id, name, scale, powers, new JsonArray());
        }

        public Thing {
            parts = parts == null ? new JsonArray() : parts;
            List<String> lower = new ArrayList<>();
            for (String p : powers == null ? List.<String>of() : powers) {
                lower.add(p.toLowerCase(Locale.ROOT));
            }
            powers = List.copyOf(lower);
            name = name == null ? "" : name;
        }
    }

    /** The Builder power tiles it already has, as catalogue ids ("power:flies"). */
    public static List<String> builderPowers(Thing t) {
        List<String> out = new ArrayList<>();
        for (String p : t.powers()) {
            if (BuilderFlow.POWER_WORDS.containsKey(p)) {
                out.add("power:" + p);
            }
        }
        return out;
    }

    /** The Builder temper its powers read as, or "" when they say none of the Builder's (then no temper tile is on). */
    public static String temper(Thing t) {
        if (t.powers().contains("boss")) {
            return "boss";
        }
        if (t.powers().contains("skittish")) {
            return "shy";
        }
        if (t.powers().contains("friendly")) {
            return "friendly";
        }
        if (t.powers().contains("hostile")) {
            return "wild";
        }
        return "";
    }

    /**
     * The calls that make the change he chose: {@code size} on the slider ({@link #NOW} is as it is), the Builder powers
     * he left ticked, and {@code temper} ("" or the one it already reads as: unchanged). Only what changed is sent; an
     * empty list is "nothing changed".
     */
    public static List<BuilderLowering.Call> calls(Thing t, int size, List<String> chosen, String temper) {
        return calls(t, size, chosen, temper, "");
    }

    /**
     * slice 2: …and {@code scheme}, a Builder colour scheme ("" or "as_is": as it is). The Builder's own
     * {@code Schemes.recolour} over its own parts (biggest colour to the scheme's main, then its accent and trim; the
     * face never), sent as {@code recolour} on the same edit: its parts are kept, only their colours change.
     */
    public static List<BuilderLowering.Call> calls(Thing t, int size, List<String> chosen, String temper, String scheme) {
        if (size < 1 || size > 4) {
            throw new IllegalArgumentException("size " + size + " is not 1–4");
        }
        String was = temper(t);
        boolean temperChanged = temper != null && !temper.isEmpty() && !temper.equals(was);
        Set<String> now = new LinkedHashSet<>();
        for (String p : t.powers()) {
            if (BuilderFlow.POWER_WORDS.containsKey(p)) {
                continue;   // a Builder power: it is on when he left its tile ticked (below)
            }
            if (temperChanged && TEMPER_WORDS.contains(p)) {
                continue;   // its old temper gives way to the new one
            }
            now.add(p);   // everything the Builder does not offer: kept exactly as the Magician made it
        }
        for (String id : chosen) {
            if (!Catalogue.prefix(id).equals("power") || !BuilderFlow.POWER_WORDS.containsKey(Catalogue.bare(id))) {
                throw new IllegalArgumentException("'" + id + "' is not a Builder power");
            }
            now.add(Catalogue.bare(id));
        }
        if (temperChanged) {
            now.addAll(BuilderLowering.temperPowers(temper));
        }
        boolean powersChanged = !now.equals(new LinkedHashSet<>(t.powers()));
        boolean guard = temperChanged && temper.equals("guard");
        List<BuilderLowering.Call> out = new ArrayList<>();
        Schemes.Scheme sc = scheme == null || scheme.isEmpty() || scheme.equals("as_is") ? null : Schemes.byId(scheme);
        if (scheme != null && !scheme.isEmpty() && !scheme.equals("as_is") && sc == null) {
            throw new IllegalArgumentException("no colours called '" + scheme + "'");
        }
        JsonObject recolour = sc == null ? new JsonObject() : Schemes.recolour(t.parts(), sc);
        if (size != NOW || powersChanged || guard || recolour.size() > 0) {
            JsonObject a = new JsonObject();
            a.addProperty("id", t.id());
            a.addProperty("start_from", t.id());   // itself: every part and field it has, under what this names
            a.add("parts", new JsonArray());
            a.addProperty("note", "Builder change on a Magician creature: only what the Builder can choose.");
            if (size != NOW) {
                a.addProperty("scale", t.scale() * BuilderLowering.CREATURE_SCALE[size - 1]
                        / BuilderLowering.CREATURE_SCALE[NOW - 1]);
            }
            if (powersChanged) {
                JsonArray pw = new JsonArray();
                now.forEach(pw::add);
                a.add("powers", pw);
            }
            if (guard) {
                a.addProperty("base", "guardian");
            }
            if (recolour.size() > 0) {
                a.add("recolour", recolour);
            }
            a.addProperty("spawn", false);
            out.add(new BuilderLowering.Call("sculpt_creature", a));
        }
        if (temperChanged && (temper.equals("pet") || temper.equals("stays"))) {
            JsonObject m = new JsonObject();
            m.addProperty("scope", "group");
            m.addProperty("target", t.id());
            m.addProperty("radius", (int) com.moddingfromamod.mob.MobTweak.MAX_RADIUS);
            m.addProperty("count", BuilderAllowlist.REVIEWED.maxCount());
            if (temper.equals("pet")) {
                m.addProperty("follow", true);
            } else {
                m.addProperty("stay", BuilderLowering.WITH_YOU_STAY);
            }
            out.add(new BuilderLowering.Call("modify_mob", m));
        }
        if (out.isEmpty()) {
            return out;
        }
        JsonObject plan = new JsonObject();
        JsonArray steps = new JsonArray();
        steps.add("change " + (t.name().isEmpty() ? t.id() : t.name()));
        plan.add("steps", steps);
        plan.addProperty("about", t.id());
        plan.addProperty("kind", "creature");
        out.add(0, new BuilderLowering.Call("plan_wish", plan));
        JsonObject fin = new JsonObject();
        fin.addProperty("label", t.name().isEmpty() ? t.id() : t.name());
        out.add(new BuilderLowering.Call("finish_building", fin));
        return out;
    }
}
