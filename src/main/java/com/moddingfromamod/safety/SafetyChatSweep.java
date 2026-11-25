package com.moddingfromamod.safety;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.moddingfromamod.world.WorldPeople;

/**
 * <b>L0 on what PLAYERS say to each other</b> (D18) — the sixth choke point, and the first
 * whose words were typed by a stranger rather than by the child or by Moddy.
 *
 * <p>A shared world can open to people 13 and up who do not know each other. Every other layer reads a
 * WISH or what Moddy says about one; public chat, {@code /msg}, {@code /tell}, {@code /w},
 * {@code /me} and {@code /teammsg} reached every player on the world with nothing in between. This
 * puts the lexicon there: deterministic, instant, no model call (a chat line is a sentence a second,
 * and the server thread must never wait on a network round trip — PATTERNS.md).
 *
 * <h2>What happens, by verdict</h2>
 * <ul>
 *   <li><b>{@code CLEARLY}</b> on any axis → the message does not go out, and the sender alone is
 *       told {@link ChatWords#HELD}. {@link ChatStrikes#STRIKES_TO_MUTE} of those inside ten
 *       minutes start a break from chat ({@link ChatStrikes}); the message that starts it says how
 *       long. Logged {@code BLOCK} (or {@code MUTE} for the one that started a break).</li>
 *   <li>anything else → it goes out, untouched and unlogged (a count of clean lines would be one
 *       record per chat line, and the {@code safety} log is the file the weekly review reads).</li>
 * </ul>
 *
 * <p>{@code shadow} assesses and logs ({@code SHADOW_BLOCK}) and changes
 * nothing anybody sees — no strike, no break, no line. {@code off} (the default) does
 * nothing at all. The mode is the world's ({@link WorldPeople#chatSweep()}).
 *
 * <p><b>The words are never logged.</b> A {@code safety} record from here carries the bands, the
 * reasons, the player's id and the surface — the redaction docs/PRIVACY_REVIEW.md asks of anything
 * that leaves the machine.
 */
public final class SafetyChatSweep {

    private SafetyChatSweep() {
    }

    /** Where the words were said — the {@code tool} field of the {@code safety} record. */
    public enum Surface {
        CHAT, MSG, ME, TEAMMSG;

        public String label() {
            return "chat." + name().toLowerCase(Locale.ROOT);
        }
    }

    /** What the choke point does. */
    public enum Act {
        /** Send it; nothing to say. */
        PASS,
        /** Hold it back and tell the sender. */
        BLOCK,
        /** Hold it back; it started a break from chat. */
        MUTE,
        /** Hold it back; the sender's chat is resting (not re-graded, not logged). */
        MUTED
    }

    /**
     * The answer.
     *
     * @param act       what to do
     * @param deliver   whether the message goes out to anyone else
     * @param tell      the line the sender alone hears, or empty
     * @param logAction the {@code safety} record's {@code action}, or {@code null} for no record
     * @param verdict   the lexicon's verdict, or {@code null}
     */
    public record Decision(Act act, boolean deliver, String tell, String logAction,
                           SafetyVerdict verdict) {
        static final Decision PASS = new Decision(Act.PASS, true, "", null, null);
    }

    /** A chat command's spoken part: which surface, the words, and who it was sent to (or empty). */
    public record Spoken(Surface surface, String text, String to) { }

    /**
     * One message from one player.
     *
     * @param text    what he typed (the words only — for a command, {@link #spoken}'s text)
     * @param mode    the world's sweep
     * @param lexicon the list ({@link SafetyLexicon#shared()} in the game)
     * @param strikes this server's break book
     * @param who     the sender
     * @param nowMs   the clock
     */
    public static Decision decide(String text, WorldPeople.Sweep mode, SafetyLexicon lexicon,
                                  ChatStrikes strikes, UUID who, long nowMs) {
        if (mode == null || mode == WorldPeople.Sweep.OFF || text == null || text.isBlank()) {
            return Decision.PASS;
        }
        boolean on = mode == WorldPeople.Sweep.ON;
        Optional<SafetyVerdict> found = lexicon == null ? Optional.empty() : lexicon.check(text);
        SafetyVerdict v = found.orElse(null);
        boolean clearly = v != null && v.overall() == SafetyVerdict.Band.CLEARLY;
        long until = on ? strikes.mutedUntil(who, nowMs) : 0L;
        if (until > 0L) {
            return new Decision(Act.MUTED, false,
                    ChatWords.sayBreakContinues(ChatStrikes.minutesLeft(until, nowMs)), null, v);
        }
        if (!clearly) {
            return Decision.PASS;
        }
        if (!on) {
            return new Decision(Act.PASS, true, "", "SHADOW_BLOCK", v);
        }
        long started = strikes.strike(who, nowMs);
        if (started > 0L) {
            return new Decision(Act.MUTE, false,
                    ChatWords.sayBreakStarted(ChatStrikes.minutesLeft(started, nowMs)), "MUTE", v);
        }
        return new Decision(Act.BLOCK, false, ChatWords.sayHeld(), "BLOCK", v);
    }

    /**
     * The words a chat COMMAND says to other players, or empty for any other command. Covers
     * {@code msg}/{@code tell}/{@code w} (after the target), {@code me}, {@code teammsg}/{@code tm},
     * with or without a leading slash or the {@code minecraft:} namespace. A target selector with
     * spaces inside its brackets ({@code @a[name=x, limit=1]}) is skipped whole. Pure.
     */
    public static Optional<Spoken> spoken(String command) {
        String c = command == null ? "" : command.strip();
        if (c.startsWith("/")) {
            c = c.substring(1);
        }
        int sp = c.indexOf(' ');
        if (sp < 0) {
            return Optional.empty();
        }
        String verb = c.substring(0, sp).toLowerCase(Locale.ROOT);
        if (verb.startsWith("minecraft:")) {
            verb = verb.substring("minecraft:".length());
        }
        String rest = c.substring(sp + 1).strip();
        switch (verb) {
            case "me" -> {
                return rest.isEmpty() ? Optional.empty() : Optional.of(new Spoken(Surface.ME, rest, ""));
            }
            case "teammsg", "tm" -> {
                return rest.isEmpty() ? Optional.empty() : Optional.of(new Spoken(Surface.TEAMMSG, rest, ""));
            }
            case "msg", "tell", "w" -> {
                int end = targetEnd(rest);
                if (end < 0 || end >= rest.length()) {
                    return Optional.empty();
                }
                String to = rest.substring(0, end);
                String words = rest.substring(end).strip();
                return words.isEmpty() ? Optional.empty() : Optional.of(new Spoken(Surface.MSG, words, to));
            }
            default -> {
                return Optional.empty();
            }
        }
    }

    /** Where the first argument (a name, or a selector whose brackets may hold spaces) ends. */
    private static int targetEnd(String rest) {
        if (rest.isEmpty()) {
            return -1;
        }
        int depth = 0;
        for (int i = 0; i < rest.length(); i++) {
            char ch = rest.charAt(i);
            if (ch == '[') {
                depth++;
            } else if (ch == ']') {
                depth = Math.max(0, depth - 1);
            } else if (ch == ' ' && depth == 0) {
                return i;
            }
        }
        return rest.length();
    }
}
