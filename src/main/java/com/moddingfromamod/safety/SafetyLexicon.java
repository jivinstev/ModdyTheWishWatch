package com.moddingfromamod.safety;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>The floor when the model is unavailable — not the filter.</b>
 *
 * <p>{@code docs/CHILD_SAFETY_PLAN.md} §3 L0. Deterministic, instant, and deliberately tiny: a
 * short list of terms so unambiguous that a 7-year-old could not have meant anything else. It runs
 * synchronously where a model call cannot (before the rate limiter, before a web prefetch, at the
 * tool choke point) and it is the whole answer in the two moments the grader has none: before the
 * grade comes back, and when there is no CLI at all.
 *
 * <p><b>Everything nuanced belongs to the model.</b> A list long enough to be a filter is a
 * denylist, and a denylist's false positives land on the child: the real corpus is full of killing
 * bosses, grinding mobs into meat and a liquid made of bottoms, and every one of those is a wish he
 * actually wanted. {@code SafetyLexiconTest} pins that — every wish in
 * {@code corpus/real-wishes.jsonl} must come back clean.
 *
 * <h2>Three properties that are load-bearing</h2>
 *
 * <ul>
 *   <li><b>Whole-word matching, never substring.</b> "assassin" contains a word this list would
 *       otherwise ban; so does "Scunthorpe", and "grape". The boundary is
 *       "no letter or digit either side", so a term can never fire from inside a longer word.</li>
 *   <li><b>NFKC folding first</b>, so a lookalike codepoint cannot dodge a term the way it cannot
 *       dodge {@link com.moddingfromamod.wish.WishText#sanitize}.</li>
 * </ul>
 *
 * <p>The list is DATA ({@code /safety/lexicon.txt} in the jar), overridable at
 * {@code <gameDir>/moddingfromamod/safety/lexicon.txt} exactly like
 * {@link com.moddingfromamod.wish.WishLessons} — so a term that turns out to fire on a real wish is
 * removed tonight rather than at the next release.
 */
public final class SafetyLexicon {

    /** Where an operator drops a replacement list, relative to the game directory. */
    public static final String OVERRIDE_DIR = "moddingfromamod/safety";

    /** The file name, in the jar and in the override directory alike. */
    public static final String FILE_NAME = "lexicon.txt";

    private static final String BUNDLED = "/safety/" + FILE_NAME;

    /**
     * Which axis an entry speaks to. A line naming any other axis (an override written for an older
     * release, say) is ignored, never guessed at.
     */
    public enum Axis { VIOLENCE, SEXUAL, HATE, DANGER }

    /** One term, compiled once. */
    private record Entry(Axis axis, String term, Pattern pattern, SafetyVerdict.Reason reason) { }

    private final List<Entry> entries;
    private final String source;

    private SafetyLexicon(List<Entry> entries, String source) {
        this.entries = List.copyOf(entries);
        this.source = source;
    }

    // ---- loading ----

    /**
     * The list this game is running with, compiled once.
     *
     * <p>Cached deliberately, unlike {@link com.moddingfromamod.wish.WishLessons}: that one is read
     * per wish on a path that already costs a model call, and this one runs inside
     * {@code WishService.submit} on the server thread, where a file read and 23 regex compilations
     * per wish would be exactly the kind of cost §7 promises is zero. {@link #reload()} is how an
     * edited override is picked up.
     */
    public static SafetyLexicon shared() {
        SafetyLexicon l = cached;
        if (l == null) {
            synchronized (SafetyLexicon.class) {
                l = cached;
                if (l == null) {
                    l = fromDiskOrJar(overrideFile());
                    cached = l;
                }
            }
        }
        return l;
    }

    private static volatile SafetyLexicon cached;

    /** Forget the compiled list, so an edited override (or a test's) is read next time. */
    public static void reload() {
        cached = null;
    }

    /**
     * The override if it is there, else the bundled list.
     *
     * <p>Takes the override path as an argument rather than reaching for the game directory itself,
     * for the reason {@link com.moddingfromamod.agent.ClaudeCli#fromWellKnown} does: otherwise the
     * precedence rule — the half most likely to be wrong — can only be tested by having a game.
     *
     * @param override where to look first; null or absent falls through to the jar
     */
    static SafetyLexicon fromDiskOrJar(Path override) {
        if (override != null) {
            try {
                if (Files.isRegularFile(override)) {
                    try (Reader r = Files.newBufferedReader(override, StandardCharsets.UTF_8)) {
                        return load(r, override.toString());
                    }
                }
            } catch (Exception e) {
                // An unreadable or half-written override must never stop a wish. Fall through to
                // the bundled list, which is the floor this class exists to be.
            }
        }
        try (InputStream in = SafetyLexicon.class.getResourceAsStream(BUNDLED)) {
            if (in == null) {
                return new SafetyLexicon(List.of(), "none");
            }
            return load(new InputStreamReader(in, StandardCharsets.UTF_8), "bundled");
        } catch (Exception e) {
            return new SafetyLexicon(List.of(), "none");
        }
    }

    /** Parse a list. The seam every test uses — no files, no game, no classpath. */
    public static SafetyLexicon load(Reader reader) {
        return load(reader, "reader");
    }

    private static SafetyLexicon load(Reader reader, String source) {
        List<Entry> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(reader)) {
            String line;
            while ((line = br.readLine()) != null) {
                Entry e = parseLine(line);
                if (e != null) {
                    out.add(e);
                }
            }
        } catch (Exception e) {
            // Whatever was read before the failure still stands. A truncated floor beats none.
        }
        return new SafetyLexicon(out, source);
    }

    private static Entry parseLine(String raw) {
        String line = raw == null ? "" : raw.strip();
        if (line.isEmpty() || line.startsWith("#")) {
            return null;
        }
        // Split on tabs only: a term may contain spaces ("have sex"), so whitespace is not a
        // separator here and treating it as one would silently truncate every multi-word entry.
        String[] parts = line.split("\t");
        if (parts.length < 2) {
            return null;
        }
        Axis axis;
        try {
            axis = Axis.valueOf(parts[0].strip().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return null;   // an axis nobody defined: ignored, never guessed at
        }
        String term = fold(parts[1]);
        if (term.isEmpty()) {
            return null;
        }
        SafetyVerdict.Reason reason = SafetyVerdict.Reason.OTHER;
        if (parts.length >= 3 && !parts[2].isBlank()) {
            // Strip a trailing comment before reading the reason, so a commented entry does not
            // silently lose it.
            //
            // ⚠ indexOf rather than split, and this is not a style preference: String.split drops
            // TRAILING empty strings, so "#".split("#") is a ZERO-length array and [0] threw
            // ArrayIndexOutOfBoundsException. That is not caught here — it is caught by `load`'s
            // "a truncated floor beats none" handler, which silently DROPS EVERY REMAINING TERM.
            // One operator typo in an override (a term with a bare `#` where its reason goes) and
            // the floor quietly ends at that line, which is the one failure mode this class must
            // not have: the whole point of the override is that it is edited by hand tonight.
            int hash = parts[2].indexOf('#');
            String r = (hash < 0 ? parts[2] : parts[2].substring(0, hash))
                    .strip().toUpperCase(Locale.ROOT);
            try {
                reason = SafetyVerdict.Reason.valueOf(r);
            } catch (RuntimeException ignored) {
                reason = SafetyVerdict.Reason.OTHER;
            }
        }
        return new Entry(axis, term, compile(term), reason);
    }

    /**
     * Whole words, and whitespace-tolerant between them.
     *
     * <p>{@code \b} is not used on purpose: it is defined against {@code \w}, which excludes most
     * letters outside ASCII, so a term beside an accented character would match where it should
     * not. An explicit "no letter or digit either side" says what the rule actually is.
     */
    private static Pattern compile(String term) {
        StringBuilder sb = new StringBuilder("(?<![\\p{L}\\p{N}])");
        String[] words = term.split("\\s+");
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                sb.append("\\s+");
            }
            sb.append(Pattern.quote(words[i]));
        }
        sb.append("(?![\\p{L}\\p{N}])");
        return Pattern.compile(sb.toString());
    }

    /** NFKC, lower-cased, whitespace collapsed. Applied to the haystack and the needle alike. */
    private static String fold(String s) {
        if (s == null) {
            return "";
        }
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return n.replaceAll("\\s+", " ").strip();
    }

    // ---- the question ----

    /**
     * Does this text trip the floor?
     *
     * <p>Empty is the answer for the overwhelming majority of wishes and is the answer this class
     * is tuned for. Ask {@link SafetyVerdict#overall()} what a present verdict means.
     */
    public Optional<SafetyVerdict> check(String text) {
        if (text == null || text.isBlank() || entries.isEmpty()) {
            return Optional.empty();
        }
        String hay = fold(text);
        SafetyVerdict.Band violence = SafetyVerdict.Band.FINE;
        SafetyVerdict.Band sexual = SafetyVerdict.Band.FINE;
        SafetyVerdict.Band hate = SafetyVerdict.Band.FINE;
        SafetyVerdict.Band danger = SafetyVerdict.Band.FINE;
        List<SafetyVerdict.Reason> reasons = new ArrayList<>();
        boolean hit = false;
        for (Entry e : entries) {
            Matcher m = e.pattern().matcher(hay);
            if (!m.find()) {
                continue;
            }
            hit = true;
            switch (e.axis()) {
                case VIOLENCE -> violence = SafetyVerdict.Band.CLEARLY;
                case SEXUAL -> sexual = SafetyVerdict.Band.CLEARLY;
                case HATE -> hate = SafetyVerdict.Band.CLEARLY;
                case DANGER -> danger = SafetyVerdict.Band.CLEARLY;
                default -> { }
            }
            if (!reasons.contains(e.reason())) {
                reasons.add(e.reason());
            }
        }
        if (!hit) {
            return Optional.empty();
        }
        // No redirect, ever. §2 gives a redirect to LIKELY alone, and this list only ever produces
        // CLEARLY.
        return Optional.of(new SafetyVerdict(violence, sexual, hate, danger, reasons,
                "", SafetyVerdict.Provenance.LEXICON, SafetyVerdict.NO_STAGE));
    }

    /** How many terms are loaded — a startup line, and the guard against an empty list. */
    public int size() {
        return entries.size();
    }

    /** Which file this came from, for a startup log line. */
    public String describe() {
        return "safety lexicon: " + entries.size() + " term(s) from " + source;
    }

    /** Where an override would live, or null outside a loaded game (a unit test). */
    static Path overrideFile() {
        try {
            return net.neoforged.fml.loading.FMLPaths.GAMEDIR.get()
                    .resolve(OVERRIDE_DIR).resolve(FILE_NAME);
        } catch (RuntimeException | NoClassDefFoundError e) {
            return null;
        }
    }
}
