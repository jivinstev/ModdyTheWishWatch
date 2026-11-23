package com.moddingfromamod.marshal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>Which of his things may {@code ask_which} offer him — decided on the server, not by the prompt
 *.</b>
 *
 * <p>Live, 2026-10-30: "kill my t rex". The agent offered every T-Rex DESIGN he had; he picked the
 * Hot Wheels one, which had not been out in the world for days; the removal found nothing; the
 * T-Rex he meant kept stomping and the wish was spent. The question was the wrong question: a wish
 * that acts on one of his things that is OUT there (kill, hit, ride, feed, take me to, make it
 * bigger…) can only mean one that is out there. A wish to make ANOTHER one from a design can mean
 * any design.
 *
 * <p><b>How the server knows which:</b> the agent says so, in {@code about} — {@code instance} (the
 * default) or {@code design}. Not inferred from the wish's words, because the verbs that act on an
 * instance are open-ended ("give him a hat", "make him dance", "let me ride him") and a word list
 * would be the brittle half; the agent has already read the words to choose the candidates. The one
 * thing the server does check is the case that costs most when wrong: a wish whose words REMOVE or
 * DESTROY ({@link WishVerb.Kind#DESTRUCTIVE}) cannot be about a design unless it also asks for
 * another one — "kill my t rex" with {@code about: design} is refused rather than honoured.
 *
 * <p>Then, for an instance:
 * <ul>
 *   <li>things not out in the world are dropped ({@link InWorld#out()} — a kind with nothing to be
 *       out, an item or a rule, is never dropped);</li>
 *   <li>one left → it is chosen and he is NOT asked: a question with one answer is no question;</li>
 *   <li>none left → refused, with the words that tell the agent not to act on a design.</li>
 * </ul>
 *
 * <p>Minecraft-free, so every row of that is unit-tested ({@code WhichPickTest}).
 */
public final class WhichPick {

    /** What the wish acts on: one that is out in the world, or a design to make another from. */
    public enum About {
        INSTANCE, DESIGN;

        /** {@code null}/blank → INSTANCE; an unknown word → null (the caller refuses it). */
        public static About parse(String s) {
            if (s == null || s.isBlank()) {
                return INSTANCE;
            }
            return switch (s.strip().toLowerCase(Locale.ROOT)) {
                case "instance", "live", "alive", "out", "existing" -> INSTANCE;
                case "design", "library", "another", "new_from_design" -> DESIGN;
                default -> null;
            };
        }
    }

    /** One thing the agent wants to offer, with whether it is out in the world. */
    public record Offer(String id, String name, InWorld inWorld) {
        public Offer {
            inWorld = inWorld == null ? InWorld.NOT_A_THING : inWorld;
        }
    }

    public enum Kind { ASK, CHOSEN, REFUSE }

    /**
     * What to do: ASK him between {@code ids}, take {@code ids.get(0)} without asking (CHOSEN), or
     * REFUSE with {@code message}. {@code message} is the agent-facing explanation in every case.
     */
    public record Decision(Kind kind, List<String> ids, String message) {
        public Decision {
            ids = ids == null ? List.of() : List.copyOf(ids);
            message = message == null ? "" : message;
        }
    }

    /** Words that ask for ANOTHER one: these let a destructive-sounding wish still be about a design. */
    private static final List<String> ANOTHER_WORDS = List.of(
            "another", "again", "one more", "new one", "a new", "copy", "second", "more of",
            "back");

    private WhichPick() {
    }

    /** True when the words ask for another one from a design ("make me another", "bring it back"). */
    public static boolean asksForAnother(String wishText) {
        String t = " " + (wishText == null ? "" : wishText.toLowerCase(Locale.ROOT))
                .replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").strip() + " ";
        for (String w : ANOTHER_WORDS) {
            if (t.contains(" " + w + " ")) {
                return true;
            }
        }
        return false;
    }

    public static Decision decide(About about, String wishText, List<Offer> offers) {
        List<Offer> all = offers == null ? List.of() : offers;
        if (about == null) {
            return new Decision(Kind.REFUSE, List.of(), "`about` must be 'instance' (the wish acts on "
                    + "one of his things that is out in the world — hit, ride, remove, change it) or "
                    + "'design' (he wants ANOTHER one made from a design).");
        }
        if (about == About.DESIGN) {
            if (WishVerb.of(wishText) == WishVerb.Kind.DESTRUCTIVE && !asksForAnother(wishText)) {
                return new Decision(Kind.REFUSE, List.of(), "His words remove or destroy something, "
                        + "so the wish acts on one that is OUT in the world, not on a design. Call "
                        + "ask_which again with about: 'instance'.");
            }
            return new Decision(Kind.ASK, ids(all), "");
        }
        List<Offer> out = new ArrayList<>();
        List<String> notOut = new ArrayList<>();
        for (Offer o : all) {
            if (o.inWorld().out()) {
                out.add(o);
            } else {
                notOut.add(label(o));
            }
        }
        if (out.isEmpty()) {
            return new Decision(Kind.REFUSE, List.of(), "None of those is out in the world right now ("
                    + String.join(", ", notOut) + " — design only), so there is nothing for this "
                    + "wish to act on. Do NOT act on a design as if it were out there. If the one he "
                    + "means is not in the catalogue as out in the world, tell him kindly you can't "
                    + "find it out in the world and change nothing; if he wants a NEW one from a "
                    + "design, that is a different wish — say so and ask.");
        }
        String dropped = notOut.isEmpty() ? "" : " (" + String.join(", ", notOut)
                + (notOut.size() == 1 ? " is" : " are") + " not out in the world, so "
                + (notOut.size() == 1 ? "it was" : "they were") + " not offered)";
        if (out.size() == 1) {
            Offer only = out.get(0);
            return new Decision(Kind.CHOSEN, List.of(only.id()), "Only '" + only.id() + "' of those is "
                    + "out in the world" + dropped + ", so I did not ask him — it is the one he means. "
                    + "Use plan_wish about: '" + only.id() + "' and do the wish to that one.");
        }
        return new Decision(Kind.ASK, ids(out), dropped.isEmpty() ? "" : "Asked between the ones "
                + "out in the world" + dropped + ".");
    }

    private static List<String> ids(List<Offer> offers) {
        List<String> out = new ArrayList<>();
        for (Offer o : offers) {
            out.add(o.id());
        }
        return out;
    }

    private static String label(Offer o) {
        return o.name() == null || o.name().isBlank() ? "'" + o.id() + "'" : o.name();
    }
}
