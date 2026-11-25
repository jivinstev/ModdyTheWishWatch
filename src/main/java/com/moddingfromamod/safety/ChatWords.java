package com.moddingfromamod.safety;

import java.util.List;

/**
 * <b>Every word a player hears from a shared world's rules for people</b>: the chat sweep, a break
 * from chat, and reporting a player. Pure, beside {@link SafetyWords} and held to the same rules
 * ({@code ChatWordsTest}): never blame the player, never name what was wrong, never a question about
 * what he said, and always point at something he can still do. The audience here is 13+ strangers,
 * not a seven-year-old, but the voice is the same — the world is still Moddy's.
 */
public final class ChatWords {

    private ChatWords() {
    }

    /** A message the lexicon held back. The world is the subject, not him. */
    public static final String HELD =
            "🪄 That message didn't go out — chat here stays friendly for everyone. "
                    + "Try saying it another way!";

    /** The message that started a break. {@code %d} = minutes. */
    static final String BREAK_STARTED =
            "🪄 Chat is taking a little break for you — back in %d minute%s. "
                    + "You can still play and make wishes!";

    /** A message sent during a break. */
    static final String BREAK_CONTINUES =
            "🪄 Chat is still resting for you — back in %d minute%s. Your wishes still work!";

    /** A report was saved. {@code %s} = the reported name. Nothing happens to that player by itself. */
    static final String REPORT_THANKS =
            "💛 Thank you for telling us about %s. The people who run Moddy read every report — "
                    + "keep playing, and report again if anything else happens.";

    /** Too many reports in a short time. */
    public static final String REPORT_SLOW_DOWN =
            "💛 Thank you — your reports are on their way. You can send another in a little while; "
                    + "for now, keep playing!";

    /** Reporting oneself, or a name that cannot be a Minecraft name. */
    public static final String REPORT_HOW =
            "✨ To tell the people who run Moddy about someone, type /moddy report <their name> "
                    + "and what happened.";

    /** The record could not be written. */
    public static final String REPORT_FAILED =
            "😴 That report didn't save. Please try again in a moment!";

    public static String sayHeld() {
        return HELD;
    }

    public static String sayBreakStarted(int minutes) {
        return String.format(BREAK_STARTED, minutes, minutes == 1 ? "" : "s");
    }

    public static String sayBreakContinues(int minutes) {
        return String.format(BREAK_CONTINUES, minutes, minutes == 1 ? "" : "s");
    }

    public static String sayReportThanks(String name) {
        return String.format(REPORT_THANKS, name == null || name.isBlank() ? "them" : name.strip());
    }

    /** Every string, for the test that checks the whole set at once. */
    public static List<String> all() {
        return List.of(HELD, sayBreakStarted(10), sayBreakStarted(1), sayBreakContinues(7),
                sayReportThanks("Some_Player"), REPORT_SLOW_DOWN, REPORT_HOW, REPORT_FAILED, sayChatIsPrivate(), sayNoMessages());
    }

    /** step 4: said once, the first time a player chats on a world where chat stays with him. */
    public static String sayChatIsPrivate() {
        return "Only you can see your chat here — every island on this world is private. Keep playing, or ask Moddy for a wish!";
    }

    /** step 4: a /msg, /tell, /w, /teammsg or /me where chat stays with its sender. */
    public static String sayNoMessages() {
        return "Messages don't travel between islands here — every island on this world is private. Try a wish instead!";
    }
}
