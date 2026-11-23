package com.moddingfromamod.place;

import java.util.Locale;
import java.util.Set;

import com.moddingfromamod.builder.BuildSpot;

/**
 * <b>{@code at: "<thing>[:<where>]"} — the one way every placing tool says WHERE</b>.
 *
 * <p>{@code thing} is a building's id, a creature's id, or {@code me} (the child). {@code where} is
 * one of that thing's spots: a building's own named spot (a throne, a counter), one of the
 * automatic ones every building has ({@code front}, {@code top}, {@code inside}, {@code north} …
 * {@code around}, {@code beside}, {@code behind}), or for a creature or the child {@code front},
 * {@code behind}, {@code beside}, {@code top}, {@code around} or a compass side. Left off, the
 * calling tool picks the one its kind of thing means: a creature or a chest goes to a building's
 * front, an event to its top, a picture on its front wall, a building beside it.
 *
 * <p>Creatures had this ({@code BuildSpots.parseAt}); this is the same grammar
 * widened from "a building" to "anything with a place", parsed in one place so no tool grows its
 * own dialect.
 *
 * <p>Pure — no Minecraft imports — so every form is a row in {@code AtTest}.
 *
 * @param thing normalised: lower case, spaces as {@code _}, a fork's {@code ~owner} kept; {@link #ME}
 *              for the child however he was named
 * @param where a {@link BuildSpot#normalise normalised} spot name, or {@code ""} for "the tool's
 *              default"
 */
public record At(String thing, String where) {

    /** The child, however the agent spelt him. */
    public static final String ME = "me";

    private static final Set<String> MEANS_ME = Set.of("me", "him", "her", "them", "player", "the_player",
            "myself", "child", "the_child", "@s", "@p");

    /**
     * <b>The one description of {@code at} every placing tool's schema carries</b>, so the agent
     * reads the same grammar on a creature, a thing, an event, a building and a picture.
     *
     * @param what   what this tool puts there ("it", "the thing", "the event")
     * @param dflt   the spot it takes when only the thing is named, and why
     * @param extra  one more sentence for this tool, or ""
     */
    public static String describe(String what, String dflt, String extra) {
        return "WHERE " + what + " goes when his words name a PLACE — \"on my roof\", \"in the garage\", "
                + "\"next to my house\", \"over my castle\", \"around my base\", \"in front of me\": "
                + "\"<thing>:<spot>\". The thing is a building's id (list_builds / inspect_build), a "
                + "creature's id, or me. EVERY building has these spots without marking any: front "
                + "(outside its entrance), top (on its roof), inside (the floor of its main room; "
                + "inside_2… for more rooms), north / east / south / west (outside that wall), beside "
                + "(next to it, not in its doorway), behind, around (a ring just outside it) — plus any "
                + "it marked with build_structure's spots (a throne, a counter). A creature or me has "
                + "front, behind, beside, top, around and the compass sides. Just the thing (\"castle\") "
                + "means " + dflt + ". It is placed AFTER a building that is still going up is finished. "
                + "Leave it out when he named no place: then it goes where he aimed, as always. "
                + "NEVER work out coordinates for a named thing yourself."
                + (extra == null || extra.isEmpty() ? "" : " " + extra);
    }

    /** True when this names the child. */
    public boolean isMe() {
        return ME.equals(thing);
    }

    /**
     * The names to look the thing up by, in order: as written, then without a leading {@code my_} /
     * {@code the_} ("my_castle" is his castle) — never the other way round, so a building really
     * called {@code the_shire} is found first.
     */
    public java.util.List<String> lookups() {
        String b = bare(thing);
        return b.equals(thing) ? java.util.List.of(thing) : java.util.List.of(thing, b);
    }

    static String bare(String thing) {
        for (String p : new String[]{"my_", "his_", "her_", "the_", "our_"}) {
            if (thing.startsWith(p) && thing.length() > p.length()) {
                return thing.substring(p.length());
            }
        }
        return thing;
    }

    /**
     * <b>The buildings his word could mean</b>: those whose id or display name has the thing's last
     * word as a whole word, singular or plural — "my barn" for {@code red_barn} ("Little Red Barn"). A
     * caller takes it only when there is exactly one, and asks when there are more.
     *
     * @param idToName standing building ids to their display names
     */
    public java.util.List<String> wordMatches(java.util.Map<String, String> idToName) {
        java.util.List<String> hits = new java.util.ArrayList<>();
        java.util.List<String> l = lookups();
        String bare = l.get(l.size() - 1);
        String word = bare.substring(bare.lastIndexOf('_') + 1);
        if (word.length() < 3) {
            return hits;
        }
        for (var e : idToName.entrySet()) {
            String name = e.getValue() == null ? "" : e.getValue().trim().toLowerCase(Locale.ROOT)
                    .replace(' ', '_').replace('-', '_').replace("'", "");
            String words = "_" + e.getKey().replace('~', '_') + "_" + name + "_";
            String one = word.endsWith("s") ? word.substring(0, word.length() - 1) : word;
            if (words.contains("_" + word + "_") || words.contains("_" + word + "s_")
                    || words.contains("_" + one + "_")) {
                hits.add(e.getKey());
            }
        }
        return hits;
    }

    /** {@code "castle:roof"} as it reads back. */
    @Override
    public String toString() {
        return where.isEmpty() ? thing : thing + ":" + where;
    }

    /** The same thing at {@code where} when it named none. */
    public At orElse(String where) {
        return this.where.isEmpty() ? new At(thing, BuildSpot.normalise(where)) : this;
    }

    /**
     * {@code "castle:throne"} → (castle, throne); {@code "castle"} → (castle, ""); {@code "Me:
     * Front"} → (me, front); {@code "him"} → (me, ""). Null for nothing at all, or for a where with
     * no thing ({@code ":roof"}).
     */
    public static At parse(String at) {
        if (at == null) {
            return null;
        }
        String t = at.trim();
        if (t.length() >= 2 && (t.startsWith("\"") && t.endsWith("\"") || t.startsWith("'") && t.endsWith("'"))) {
            t = t.substring(1, t.length() - 1).trim();
        }
        t = t.toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            return null;
        }
        int colon = t.lastIndexOf(':');
        // "minecraft:…" is an id, not a thing and a spot: nothing we place is keyed by a namespace.
        if (colon >= 0 && t.startsWith("minecraft:") && t.indexOf(':') == colon) {
            colon = -1;
        }
        String thing = colon < 0 ? t : t.substring(0, colon).trim();
        String where = colon < 0 ? "" : BuildSpot.normalise(t.substring(colon + 1));
        thing = thing.replace(' ', '_');
        if (thing.isEmpty()) {
            return null;
        }
        if (MEANS_ME.contains(thing) || MEANS_ME.contains(bare(thing))) {
            thing = ME;
        }
        return new At(thing, where);
    }
}
