package com.moddingfromamod.readymade;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * <b>What {@code find_ready_made} and {@code describe_ready_made} answer</b> — pure, so the game's
 * tools and {@code ReadyMadeCli} (the routing arm's stub) print the very same bytes.
 */
public final class ReadyMadeAnswers {

    private ReadyMadeAnswers() {
    }

    public static final int FIND_LIMIT = 5;

    /** The search tools' {@code kind} argument, as the schema spells it. */
    public static final String KIND_HELP = "creature (a body to start a new one from), effect (a "
            + "ready-made creature ability), item (a ready-made item ability), wish (something "
            + "this house already made). Leave it out to search them all.";

    /**
     * What {@code find_ready_made} answers. {@code semantic} is the Haiku matcher's reply over the
     * whole library (the shipped path); null, or one that did not answer, falls back to the keyword
     * ranking — and the reply SAYS it did, so a degraded search never passes for the real one.
     */
    public static String find(ReadyMadeIndex index, Set<ReadyMade.Kind> enabled, String need,
                              String kindArg, ReadyMadeNudge.Reply semantic) {
        Set<ReadyMade.Kind> kinds = scope(enabled, kindArg);
        if (kinds.isEmpty()) {
            return "There is no ready-made library of that kind to search. Search one of: "
                    + names(enabled) + ".";
        }
        if (need == null || need.isBlank()) {
            return "Say what it needs to DO, in his words — e.g. need: 'makes mobs fight each other'. "
                    + "Here is everything, to choose from:\n" + index.menu(kinds);
        }
        List<ReadyMade> found = new java.util.ArrayList<>();
        boolean keywords = semantic == null || !semantic.answered();
        if (keywords) {
            for (ReadyMadeSearch.Hit h : ReadyMadeSearch.rank(need, index.of(kinds), FIND_LIMIT)) {
                found.add(h.entry());
            }
        } else {
            found.addAll(semantic.picks());
        }
        if (found.isEmpty()) {
            return "Nothing ready-made fits \"" + need.strip() + "\"" + (keywords
                    ? " (keyword match only — the full search was unavailable, so try his other words once)"
                    : "") + ". If nothing fits, build it yourself — that is what the builders are for.";
        }
        StringBuilder sb = new StringBuilder("Ready-made, best match first — use one only if it "
                + "really does what he asked:\n");
        if (keywords) {
            sb.append("(keyword match only — the full search was unavailable)\n");
        }
        for (ReadyMade e : found) {
            sb.append("  • [").append(word(e.kind())).append("] ").append(e.line()).append('\n');
        }
        sb.append("describe_ready_made <id> shows one in full (its parameters, and what to read off "
                + "his words for each).");
        return sb.toString();
    }

    public static String describe(ReadyMadeIndex index, Set<ReadyMade.Kind> enabled, String id,
                                  String kindArg) {
        Set<ReadyMade.Kind> kinds = scope(enabled, kindArg);
        if (id == null || id.isBlank()) {
            return "Name the id to describe, e.g. id: 'aggro'.";
        }
        ReadyMade hit = null;
        for (ReadyMade.Kind k : kinds) {
            hit = index.get(id, k);
            if (hit != null) {
                break;
            }
        }
        if (hit == null) {
            return "No ready-made " + names(kinds) + " is called \"" + id.strip() + "\". "
                    + "find_ready_made lists what there is.";
        }
        StringBuilder sb = new StringBuilder("[").append(word(hit.kind())).append("] ");
        sb.append(hit.detail().isEmpty() ? hit.id() + " — " + hit.purpose() : hit.detail());
        if (!hit.examples().isEmpty() && hit.detail().isEmpty()) {
            sb.append("\n  made for: \"").append(hit.examples().get(0)).append('"');
        }
        if (!hit.use().isEmpty()) {
            sb.append("\nUSE IT WITH: ").append(hit.use());
        }
        return sb.toString();
    }

    public static Set<ReadyMade.Kind> scope(Set<ReadyMade.Kind> enabled, String kindArg) {
        if (kindArg == null || kindArg.isBlank() || "all".equalsIgnoreCase(kindArg.strip())) {
            return enabled.isEmpty() ? EnumSet.noneOf(ReadyMade.Kind.class) : EnumSet.copyOf(enabled);
        }
        ReadyMade.Kind k = ReadyMade.Kind.parse(kindArg);
        return k != null && enabled.contains(k) ? EnumSet.of(k) : EnumSet.noneOf(ReadyMade.Kind.class);
    }

    /** The kind as the schema's word for it. */
    public static String word(ReadyMade.Kind k) {
        return k.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String names(Set<ReadyMade.Kind> kinds) {
        StringBuilder sb = new StringBuilder();
        for (ReadyMade.Kind k : kinds) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(word(k));
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }
}
