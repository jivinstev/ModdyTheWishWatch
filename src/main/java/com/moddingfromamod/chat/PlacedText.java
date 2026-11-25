package com.moddingfromamod.chat;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import com.moddingfromamod.safety.SafetyLexicon;
import com.moddingfromamod.safety.SafetyVerdict;

/**
 * <b>Text a player PLACES</b> — a sign, a book, an anvil name (step 4, docs/OPEN_DOOR_PLAN.md
 * §11.3). Once chat stops crossing between players on a world, these are the ways left to leave
 * words for a stranger, and such a world may be open to any age. So on a world whose {@code people.placedText}
 * is {@code sweep}, such text is refused when it:
 *
 * <ul>
 *   <li>holds a lexicon {@code CLEARLY} term (the same list chat is swept with — CHILD_SAFETY.md), or</li>
 *   <li>is a way to FIND someone elsewhere: a link, a web address, a handle ({@code @name},
 *       {@code name#1234}), a phone number, or the name of a messaging app. A child does not need to
 *       write any of these on a sign to play, and a stranger who wants to move a child off the server
 *       needs exactly one of them.</li>
 * </ul>
 *
 * <p>Pure, no Minecraft: {@code PlacedTextTest} is the Gate A surface; the three mixins only call it.
 */
public final class PlacedText {

    private PlacedText() {
    }

    /** What to do with one piece of placed text. */
    public enum Act { KEEP, REFUSE_WORDS, REFUSE_CONTACT }

    /** The answer: what to do. */
    public record Verdict(Act act) {
        public boolean refused() {
            return act != Act.KEEP;
        }
    }

    // A link or a web address (with or without the scheme), "dot com" spelled out included.
    private static final Pattern LINK = Pattern.compile(
            "(?i)(https?://|www\\.|\\b[a-z0-9-]{2,}\\s*(\\.|\\(dot\\)|\\bdot\\b)\\s*"
                    + "(com|net|org|gg|io|me|co|uk|tv|xyz|app|ly|be)\\b)");
    // A handle: @name, name#1234 (an old Discord tag).
    private static final Pattern HANDLE = Pattern.compile("(^|\\s)@[A-Za-z0-9_.]{2,}|[A-Za-z0-9_.]{2,}#\\d{4}\\b");
    // Seven or more digits, allowing the separators people type between them.
    private static final Pattern PHONE = Pattern.compile("(\\d[\\s().-]*){7,}");
    // Where to find someone else: the apps a stranger would name. Whole words only.
    private static final Pattern APPS = Pattern.compile("(?i)\\b(discord|snap ?chat|snap|insta(gram)?|"
            + "tik ?tok|whats ?app|telegram|kik|signal|skype|roblox|fortnite|twitch|youtube|yt|"
            + "facebook|fb|twitter|x\\.com|reddit|dm me|add me|text me|call me|my number)\\b");

    /** Whether this text names a way to find someone outside the game. Pure. */
    public static boolean contact(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.toLowerCase(Locale.ROOT);
        return LINK.matcher(t).find() || HANDLE.matcher(text).find() || PHONE.matcher(t).find()
                || APPS.matcher(t).find();
    }

    /** One piece of placed text, judged. Pure but for the list it is handed. */
    public static Verdict check(String text, SafetyLexicon lexicon) {
        if (text == null || text.isBlank()) {
            return new Verdict(Act.KEEP);
        }
        Optional<SafetyVerdict> v = lexicon == null ? Optional.empty() : lexicon.check(text);
        if (v.map(x -> x.overall() == SafetyVerdict.Band.CLEARLY).orElse(false)) {
            return new Verdict(Act.REFUSE_WORDS);
        }
        if (contact(text)) {
            return new Verdict(Act.REFUSE_CONTACT);
        }
        return new Verdict(Act.KEEP);
    }

    /** What the player is told, in Moddy's voice. Never repeats the words. */
    public static String say(Act act, String what) {
        return switch (act) {
            case REFUSE_CONTACT -> "Moddy kept that " + what + " blank — on this island, signs and books can't "
                    + "carry links, usernames or numbers. Write anything else!";
            case REFUSE_WORDS -> "Moddy kept that " + what + " blank — that's not a word for this island.";
            case KEEP -> "";
        };
    }
}
