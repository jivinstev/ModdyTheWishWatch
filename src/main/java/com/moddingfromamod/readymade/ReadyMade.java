package com.moddingfromamod.readymade;

import java.util.List;
import java.util.Locale;

/**
 * <b>One ready-made thing the wish worker could reuse instead of building from nothing</b>.
 *
 * <p>Four kinds, one shape. What makes an entry worth offering is the last field: {@code use} is
 * the exact call that puts it to work ({@code make_item effect:"aggro"},
 * {@code sculpt_creature start_from:"pet_dragon"}), because a suggestion the worker then has to
 * translate into a tool call is a suggestion that costs a turn and can be translated wrong.
 *
 * <p>{@code detail} is what {@code describe_ready_made} answers — the parameters and their "from the
 * wish" hints that used to ride in every turn's tool list. Empty when there is nothing past the
 * one-liner. {@code examples} are wishes it was made for, which the search reads and the menu
 * does not print.
 */
public record ReadyMade(Kind kind, String id, String name, List<String> aliases,
                        List<String> tags, String purpose, String use, String detail,
                        List<String> examples) {

    /** The four kinds of the matrix, by their letters. */
    public enum Kind {
        /** (a) something this server already made — a creature, an item, a building, a button. */
        WISH('a', "a related prior wish"),
        /** (b) a starter creature to start a new one from. */
        CREATURE('b', "a creature template"),
        /** (c) a ready-made creature ability: a catalogue effect or a sculpt ability. */
        EFFECT('c', "an effect template"),
        /** (d) a ready-made item ability: one make_item effect. */
        ITEM('d', "an item ability");

        public final char letter;
        public final String noun;

        Kind(char letter, String noun) {
            this.letter = letter;
            this.noun = noun;
        }

        /** The kind for a letter or a name ({@code b}, {@code creature}); null when neither. */
        public static Kind parse(String s) {
            if (s == null || s.isBlank()) {
                return null;
            }
            String t = s.strip().toLowerCase(Locale.ROOT);
            for (Kind k : values()) {
                if (t.length() == 1 && t.charAt(0) == k.letter) {
                    return k;
                }
                if (k.name().toLowerCase(Locale.ROOT).equals(t)
                        || (k.name().toLowerCase(Locale.ROOT) + "s").equals(t)) {
                    return k;
                }
            }
            return null;
        }
    }

    public ReadyMade {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        tags = tags == null ? List.of() : List.copyOf(tags);
        examples = examples == null ? List.of() : List.copyOf(examples);
        name = name == null ? id : name;
        purpose = oneLine(purpose);
        use = use == null ? "" : use;
        detail = detail == null ? "" : detail;
    }

    /** The menu line: {@code id — purpose  → use}. What a slim tool list and the nudger both read. */
    public String line() {
        return id + " — " + purpose + (use.isEmpty() ? "" : "  → " + use);
    }

    /**
     * The first sentence of a catalogue summary, capped. A catalogue summary is written for a
     * reader who already chose the entry; the menu is for choosing, and one sentence is what
     * choosing needs.
     */
    public static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').strip();
        int stop = -1;
        for (int i = 0; i < t.length() - 1; i++) {
            char c = t.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && t.charAt(i + 1) == ' ' && i > 12) {
                stop = i + 1;
                break;
            }
        }
        if (stop > 0) {
            t = t.substring(0, stop);
        }
        return t.length() > 160 ? t.substring(0, 157).strip() + "…" : t;
    }
}
