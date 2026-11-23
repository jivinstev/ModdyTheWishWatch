package com.moddingfromamod.species;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * <b>A {@link Voice} as real sound ids</b> (chunk 4): which sound each moment plays, given
 * the sounds this game knows.
 *
 * <p>Vanilla's mobs do not all name their sounds alike: a dragon's idle is {@code growl}, a
 * sniffer's {@code idle}, an allay's {@code ambient_without_item}, and half of them have no step
 * of their own — those walk on the BLOCK's step sound, which is what {@link #resolve} leaves a
 * missing step to ({@code null}). A sound the game does not know is silence in the client however
 * the server logged it (Spark's baseline sent {@code entity.wolf_howl}, which 1.21.4 spells
 * {@code entity.wolf.howl}), so an unknown one is a problem the tool reports, never something
 * played.
 *
 * <p>Pure: the known ids come in as strings.
 */
public final class VoiceSounds {

    private VoiceSounds() {
    }

    /** Each moment's sound ({@code null}: none, or the block's step), and what could not be found. */
    public record Resolved(Map<Voice.Slot, String> sounds, boolean blockSteps, List<String> problems) {

        public String get(Voice.Slot s) {
            return sounds.get(s);
        }

        public boolean ok() {
            return problems.isEmpty();
        }
    }

    /** What a moment is called on the mobs that do not call it the obvious thing, in order. */
    private static final Map<Voice.Slot, List<String>> NAMES = Map.of(
            Voice.Slot.AMBIENT, List.of("ambient", "idle", "growl", "ambient_without_item", "idle_ground", "idle_air"),
            Voice.Slot.HURT, List.of("hurt"),
            Voice.Slot.DEATH, List.of("death"),
            Voice.Slot.STEP, List.of("step"));

    public static Resolved resolve(Voice v, Collection<String> known) {
        Map<Voice.Slot, String> out = new EnumMap<>(Voice.Slot.class);
        List<String> problems = new ArrayList<>();
        if (v == null || v.isNone() || v.silent()) {
            return new Resolved(out, false, problems);
        }
        if (!v.from().isEmpty() && !hasVoice(v.from(), known)) {
            problems.add("no mob called '" + v.from() + "' has a voice in this game"
                    + suggest(v.from(), mobs(known)));
        }
        for (Voice.Slot slot : Voice.Slot.values()) {
            String given = v.sounds().get(slot);
            if (given != null) {
                if (known.contains(given)) {
                    out.put(slot, given);
                } else {
                    problems.add("there is no sound '" + given + "' (" + slot.key() + ")" + suggestSound(given, known));
                }
                continue;
            }
            if (v.from().isEmpty()) {
                continue;
            }
            for (String n : NAMES.get(slot)) {
                String id = "minecraft:entity." + v.from() + "." + n;
                if (known.contains(id)) {
                    out.put(slot, id);
                    break;
                }
            }
        }
        return new Resolved(out, !out.containsKey(Voice.Slot.STEP), problems);
    }

    public static boolean hasVoice(String mob, Collection<String> known) {
        String p = "minecraft:entity." + mob + ".";
        for (String k : known) {
            if (k.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** Every mob with a sound of its own: {@code entity.<mob>.*}. */
    public static TreeSet<String> mobs(Collection<String> known) {
        TreeSet<String> out = new TreeSet<>();
        for (String k : known) {
            if (k.startsWith("minecraft:entity.")) {
                String rest = k.substring("minecraft:entity.".length());
                int dot = rest.indexOf('.');
                if (dot > 0) {
                    out.add(rest.substring(0, dot));
                }
            }
        }
        return out;
    }

    private static String suggest(String word, Collection<String> mobs) {
        List<String> close = new ArrayList<>();
        for (String m : mobs) {
            if (m.contains(word) || word.contains(m) || distance(m, word) <= 2) {
                close.add(m);
            }
        }
        return close.isEmpty() ? " (any vanilla mob works: wolf, ravager, polar_bear, parrot, fox, cat, "
                + "warden, ender_dragon, frog, goat, …)" : "; did you mean " + String.join(" or ", close.subList(0, Math.min(4, close.size()))) + "?";
    }

    private static String suggestSound(String id, Collection<String> known) {
        // entity.wolf_howl -> entity.wolf.howl: the same words, dotted differently.
        String flat = id.replace('_', '.');
        List<String> close = new ArrayList<>();
        for (String k : known) {
            if (k.replace('_', '.').equals(flat) || distance(k, id) <= 3) {
                close.add(k);
            }
        }
        return close.isEmpty() ? "" : "; did you mean " + String.join(" or ", close.subList(0, Math.min(3, close.size()))) + "?";
    }

    /** Levenshtein, for "did you mean". */
    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    // ── how it sounds for its size ─────────────────────────────────────────────────────────────

    /**
     * Bigger is deeper, as vanilla's own babies are higher: the borrowed voice pitched by size,
     * {@code jitter} in [-1, 1] for the variety vanilla gives every mob.
     */
    public static float pitch(double scale, double jitter) {
        double base = Math.pow(Math.max(0.1, scale), -0.3);
        return (float) Math.max(0.5, Math.min(2.0, base * (1 + 0.1 * jitter)));
    }

    /** Heard further when bigger (16 blocks × volume), never quieter than vanilla. */
    public static float volume(double scale) {
        return (float) Math.max(1.0, Math.min(2.5, 1.0 + (scale - 1.0) * 0.5));
    }

    /**
     * A footfall: vanilla's 0.15, louder on a big creature so a giant is heard coming — but in
     * proportion, never over its voice. At {@code 0.15·scale^1.5} a creature 2.2 times the size
     * stepped at three times vanilla and its own footfalls buried its grunts (the first ravager-voiced
     * film: every grunt scored below a decoy, with the steps cutting through it in the spectrogram).
     */
    public static float stepVolume(double scale) {
        return (float) Math.max(0.15, Math.min(0.5, 0.15 * Math.max(1.0, scale)));
    }

    /** Blocks walked between footfalls: a stride grows with the legs. */
    public static double stride(double scale) {
        return 1.2 * Math.max(1.0, Math.sqrt(scale));
    }

    /** Vanilla's {@code Mob.getAmbientSoundInterval}: the ticks of quiet after each idle sound. */
    public static final int AMBIENT_INTERVAL = 80;

    /**
     * Vanilla's ambient clock ({@code Mob.baseTick}): a counter that starts at minus the interval,
     * climbs one a tick, and fires when a roll of 0..999 falls under it — about every six seconds.
     */
    public static boolean ambientFires(int counter, int roll1000) {
        return roll1000 < counter;
    }
}
