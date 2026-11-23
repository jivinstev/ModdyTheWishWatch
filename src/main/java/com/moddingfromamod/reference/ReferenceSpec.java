package com.moddingfromamod.reference;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * <b>The only thing a web lookup is allowed to say.</b>
 *
 * <p>Reference lookup for a named character ("make SPIKOR", "make ZIPPY") means reading pages
 * nobody vetted, which puts attacker-controlled text one hop from an agent that can write
 * <b>unguarded datapacks</b> into a child's live world: {@code author_datapack} has no
 * {@code CommandGuard} call site, so its functions carry the full vanilla command set. Treating
 * that as "we'll filter the bad stuff out" is the wrong shape — filters are a denylist and lose.
 *
 * <p>So this is not a filter. It is a <b>type system</b>, and it is the entire security property:
 * the researcher agent runs with web search and page fetch (TEXT ONLY — no vision) and
 * <i>no</i> wish tools, and the only thing that
 * can cross back is an instance of this record. Every field is an enum, a bounded number, or a URL.
 * <b>There is no free-text field anywhere — deliberately, including no "notes"</b>, because one
 * string is all an injected instruction needs. Prose cannot be smuggled through
 * {@code {colour: green, percent: 45}}.
 *
 * <p>This is stronger than the obvious "return images only" idea. An image is the highest-bandwidth
 * arbitrary payload there is, and a vision model reads text painted into pixels. Here the vision
 * happens <i>inside</i> the sandbox and only the numbers come out.
 *
 * <p>Pure and Minecraft-free, so the parsing is unit-tested — including against hostile input.
 */
public record ReferenceSpec(
        List<ColourShare> colours,
        List<String> parts,
        Size size,
        Confidence confidence,
        List<String> sources,
        Expression expression,
        List<String> images,
        Confidence colourConfidence,
        ColourSource colourSource,
        List<RegionColour> regions,
        java.util.Map<String, Integer> counts,
        Stance stance,
        Tail tail) {

    /**
     * <b>How it stands</b>: Tabbycat, Snuffy and Scruff-Pup were built on all fours because
     * "a cat", "a dog" said nothing about a character who walks upright like a person. A closed word, like
     * {@link Expression}, so it can cross from the researcher to the builder.
     */
    public enum Stance { UPRIGHT_BIPED, QUADRUPED, NO_LEGS, FLYING, UNKNOWN }

    /** Whether it has a tail, and roughly how long (a long-tailed Pippo Rabbit). */
    public enum Tail { NONE, SHORT, LONG, UNKNOWN }

    /** Without stance and tail — the shape. */
    public ReferenceSpec(List<ColourShare> colours, List<String> parts, Size size,
                         Confidence confidence, List<String> sources, Expression expression,
                         List<String> images, Confidence colourConfidence,
                         ColourSource colourSource, List<RegionColour> regions,
                         java.util.Map<String, Integer> counts) {
        this(colours, parts, size, confidence, sources, expression, images, colourConfidence,
                colourSource, regions, counts, Stance.UNKNOWN, Tail.UNKNOWN);
    }

    /** The same reference with its stance and tail. */
    public ReferenceSpec withBody(Stance st, Tail tl) {
        return new ReferenceSpec(colours, parts, size, confidence, sources, expression, images,
                colourConfidence, colourSource, regions, counts, st, tl);
    }

    /**
     * <b>Where on the character a colour is, coarsely.</b> Named by POSITION, not by anatomy: the
     * measurement knows where the pixels are and nothing about what they depict, and "the top band
     * is the head" is wrong for a fish, a snake and half the brainrot menagerie.
     */
    public enum Region { UPPER, MIDDLE, LOWER }

    /** One band's leading colour. */
    public record RegionColour(Region region, String colour, int percent) {
    }

    /**
     * <b>Where the palette came from, so the guidance can say WHY it is uncertain.</b>
     *
     * <p>{@link Confidence} alone could not: a MEDIUM palette might mean "I am not sure this is the
     * right character" or "the renders disagreed on the proportions", and those want completely
     * different advice. Without this the agent gets one hedge for two situations.
     */
    public enum ColourSource {
        /** Counted from the character's own artwork. The only one that is not an opinion. */
        MEASURED,
        /** Read off pages or search results — a person's description of the colours. */
        SOURCED,
        /** The researcher's own recall. What this whole subsystem exists to replace. */
        RECALLED
    }

    /**
     * Without part counts — the shape round 2. A part named once and not counted is
     * whatever the usual number is (two eyes, four legs); a count says only that it is NOT.
     */
    public ReferenceSpec(List<ColourShare> colours, List<String> parts, Size size,
                         Confidence confidence, List<String> sources, Expression expression,
                         List<String> images, Confidence colourConfidence,
                         ColourSource colourSource, List<RegionColour> regions) {
        this(colours, parts, size, confidence, sources, expression, images, colourConfidence,
                colourSource, regions, java.util.Map.of());
    }

    /** Without an expression — the shape this had. */
    public ReferenceSpec(List<ColourShare> colours, List<String> parts, Size size,
                         Confidence confidence, List<String> sources) {
        this(colours, parts, size, confidence, sources, Expression.UNKNOWN, List.of());
    }

    /** Without images — the shape this had before the palette was measured from pixels. */
    public ReferenceSpec(List<ColourShare> colours, List<String> parts, Size size,
                         Confidence confidence, List<String> sources, Expression expression) {
        this(colours, parts, size, confidence, sources, expression, List.of());
    }

    /** Before the colour axis was split off from the shape axis. */
    public ReferenceSpec(List<ColourShare> colours, List<String> parts, Size size,
                         Confidence confidence, List<String> sources, Expression expression,
                         List<String> images) {
        // A palette that arrived with the researcher's prose is its RECALL until something counts
        // it, and it can be no surer than the researcher's grip on the subject.
        this(colours, parts, size, confidence, sources, expression, images,
                confidence, ColourSource.RECALLED, List.of());
        // The 10-arg form is the real one; this compat ctor cannot know what the researcher
        // claimed, so it assumes the weaker thing. parse() reads the claim properly.
    }

    /** Hard caps. An unbounded list is a channel; a 40-entry palette is not a palette. */
    public static final int MAX_COLOURS = 8;
    public static final int MAX_PARTS = 16;
    public static final int MAX_SOURCES = 5;

    /**
     * Image URLs the researcher names for {@link ReferencePalette} to count. Slightly more than
     * {@link ReferencePalette#MIN_IMAGES} so a few dead links still leave a usable median.
     */
    public static final int MAX_IMAGES = 6;

    /**
     * The colour vocabulary. Exactly the buckets {@code SculptCritique} reports, so a returned
     * reference is directly comparable with what gets built — and so a colour name outside this
     * set is dropped rather than passed along as a string.
     */
    public static final Set<String> COLOURS = Set.of(
            "red", "tan/orange", "yellow", "green", "cyan", "blue", "purple/pink",
            "white", "grey", "black");

    /** The body-part vocabulary. Same reasoning: an enum, not a description. */
    public static final Set<String> PARTS = Set.of(
            "head", "body", "shell", "arm", "leg", "tail", "wing", "horn", "ear", "eye",
            "spike", "claw", "tooth", "hair", "hat", "shoe", "nose", "beak", "fin", "snout",
            // round 2: measured on 36 subjects, 24 had a signature feature none of the 20
            // words above could say -- the axolotl's gills, the octopus's tentacles, a giraffe's
            // neck, a pegasus's hooves -- so it reached the builder as nothing at all. These are
            // the STRUCTURAL ones; markings (stripe, patch) are a colour-by-region question and
            // deliberately stay out.
            "mouth", "neck", "hoof", "whisker", "tentacle", "cheek", "gill", "antenna");

    /**
     * The most of one part a reference may claim. Three heads and eight tentacles are real;
     * a count is bounded like every other number that crosses this boundary.
     */
    public static final int MAX_COUNT = 12;

    public enum Size { SMALL, MEDIUM, LARGE, HUGE, UNKNOWN }

    /**
     * <b>What the character's face is DOING.</b>
     *
     * <p>For a mascot this is not decoration, it is the character: a scowling Chucko McBurger is
     * wrong in the same way an all-green Spikor is wrong. Nothing in this pipeline represented it —
     * not {@code Species}, not {@code NamedProperties}, not the sculpt vocabulary — so nothing could
     * notice.
     *
     * <p><b>An enum, because the researcher is the only thing that sees a picture</b> and this is
     * the one channel through which what it saw can safely reach the builder. Free text could not
     * cross that wall; a bounded word can, exactly as {@link Size} and {@link Confidence} do.
     *
     * <p>{@code UNKNOWN} is the honest answer for most creatures and stays the default. A troll has
     * no documented expression, and inventing one would be the "Spikor is mostly tan" mistake in a
     * new field — a confident lesson, obeyed precisely.
     */
    public enum Expression { HAPPY, ANGRY, SAD, SURPRISED, SLEEPY, FIERCE, NEUTRAL, UNKNOWN }

    /**
     * How much the researcher actually found. {@code NONE} is a legitimate, useful answer: "Sigma
     * Boy" is a song with no canonical character design, and saying so beats inventing one.
     */
    public enum Confidence { NONE, LOW, MEDIUM, HIGH }

    /** One colour and roughly how much of the character it covers. */
    public record ColourShare(String colour, int percent) {
        public ColourShare {
            if (!COLOURS.contains(colour)) {
                throw new IllegalArgumentException("colour outside the vocabulary: " + colour);
            }
            percent = Math.max(0, Math.min(100, percent));
        }
    }

    public ReferenceSpec {
        colours = List.copyOf(colours == null ? List.of() : colours);
        parts = List.copyOf(parts == null ? List.of() : parts);
        sources = List.copyOf(sources == null ? List.of() : sources);
        images = List.copyOf(images == null ? List.of() : images);
        regions = List.copyOf(regions == null ? List.of() : regions);
        // Only counts for parts the spec actually names, in the vocabulary, within bounds.
        java.util.Map<String, Integer> kept = new java.util.LinkedHashMap<>();
        if (counts != null) {
            for (var e : counts.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && parts.contains(e.getKey())
                        && e.getValue() >= 1 && e.getValue() <= MAX_COUNT) {
                    kept.put(e.getKey(), e.getValue());
                }
            }
        }
        counts = java.util.Collections.unmodifiableMap(kept);
        colourConfidence = colourConfidence == null ? confidence : colourConfidence;
        colourSource = colourSource == null ? ColourSource.RECALLED : colourSource;
        size = size == null ? Size.UNKNOWN : size;
        confidence = confidence == null ? Confidence.NONE : confidence;
        expression = expression == null ? Expression.UNKNOWN : expression;
        stance = stance == null ? Stance.UNKNOWN : stance;
        tail = tail == null ? Tail.UNKNOWN : tail;
    }

    /**
     * <b>Nothing usable was found — the caller should build from the child's words instead.</b>
     *
     * <p><b>{@code NONE} confidence counts as nothing, and that is a bug fix.</b> It used to mean
     * only "no colours and no parts", so a researcher that answered "I am not confident" while
     * still filling in the schema produced a spec every caller treated as a real reference. The
     * measured case is <b>Omega Lad</b>: the researcher answered from the SONG's Wikipedia article
     * and returned {@code head, body, arm, leg, eye, hair} in black/tan/grey — the generic
     * humanoid default, zero bits of information — at confidence {@code NONE}. Three call sites
     * then pushed it at the builder, the loudest being {@code WishContext}: <i>"what X ACTUALLY
     * looks like, already looked up from real sources — use it, do not go from memory"</i>.
     *
     * <p>It measurably built worse than no reference at all: 25 parts with none, 23 with this. A
     * confidently-shaped wrong answer is not a weak signal, it is an anchor, and the fix is to stop
     * calling it a reference rather than to caveat it harder.
     *
     * <p>This also puts the two halves of the same signal back in agreement.
     * {@link ReferenceVerdict} already refused to grade below {@code MEDIUM} — so the half that
     * CHECKED the build respected the confidence while the half that DIRECTED it ignored the
     * confidence, which is exactly the wrong way round.
     */
    public boolean isEmpty() {
        return confidence == Confidence.NONE || (colours.isEmpty() && parts.isEmpty());
    }

    /** Its stance and tail in plain words, or "" when neither is known. */
    public String bodyLine() {
        StringBuilder b = new StringBuilder();
        switch (stance) {
            case UPRIGHT_BIPED -> b.append("It stands and walks upright on two legs, like a person, even if its kind of animal usually walks on four.");
            case QUADRUPED -> b.append("It stands and walks on four legs.");
            case NO_LEGS -> b.append("It has no legs.");
            case FLYING -> b.append("It flies.");
            default -> { }
        }
        String t = switch (tail) {
            case NONE -> "It has no tail.";
            case SHORT -> "It has a short tail.";
            case LONG -> "It has a long tail.";
            default -> "";
        };
        if (!t.isEmpty()) {
            b.append(b.length() > 0 ? " " : "").append(t);
        }
        return b.toString();
    }

    public static ReferenceSpec none() {
        return new ReferenceSpec(List.of(), List.of(), Size.UNKNOWN, Confidence.NONE, List.of(),
                Expression.UNKNOWN, List.of(), Confidence.NONE, ColourSource.RECALLED, List.of());
    }

    /**
     * <b>The same reference, with the palette replaced by one MEASURED from the artwork.</b>
     *
     * <p>Confidence can only be lowered by this, never raised: the pixels know what a character
     * LOOKS like and nothing whatever about whether it is the right character. Omega Lad's lookup
     * would have measured a very confident picture of the wrong penguin.
     */
    public ReferenceSpec withMeasuredColours(List<ColourShare> measured, Confidence ceiling,
                                             List<RegionColour> bands) {
        if (measured == null || measured.isEmpty()) {
            return this;
        }
        // THE SHAPE CONFIDENCE IS UNTOUCHED. Counting pixels says nothing about whether this is the
        // right character -- Omega Lad's old lookup would have measured a very confident picture of
        // the wrong subject -- so a measurement may set the COLOUR axis and must never move the
        // other one.
        Confidence cc = ceiling == null ? confidence : ceiling;
        return new ReferenceSpec(measured, parts, size, confidence, sources, expression, images,
                cc, ColourSource.MEASURED, bands == null ? List.of() : bands, counts, stance, tail);
    }

    /**
     * <b>The boundary.</b> Parse a researcher's reply into a spec, discarding everything that is
     * not in the vocabulary.
     *
     * <p>Deliberately total: it never throws and never propagates an unrecognised value. A hostile
     * or malformed reply degrades to {@link #none()}, which makes the wish continue without a
     * reference rather than fail — a child should never see a wish break because a web page was
     * strange.
     */
    public static ReferenceSpec parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return none();
        }
        JsonObject o;
        try {
            JsonElement el = JsonParser.parseString(extractJson(raw));
            if (!el.isJsonObject()) {
                return none();
            }
            o = el.getAsJsonObject();
        } catch (RuntimeException e) {
            return none();
        }

        List<ColourShare> colours = new ArrayList<>();
        for (JsonElement el : arr(o, "colours")) {
            if (colours.size() >= MAX_COLOURS || !el.isJsonObject()) {
                break;
            }
            JsonObject c = el.getAsJsonObject();
            String name = str(c, "colour", "color").toLowerCase(Locale.ROOT).trim();
            if (!COLOURS.contains(name)) {
                continue;   // outside the vocabulary: dropped, never passed on as a string
            }
            int pct;
            try {
                pct = (int) Math.round(Double.parseDouble(
                        str(c, "percent", "pct").replace("%", "").trim()));
            } catch (RuntimeException e) {
                continue;
            }
            colours.add(new ColourShare(name, pct));
        }

        Set<String> parts = new LinkedHashSet<>();
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (JsonElement el : arr(o, "parts")) {
            if (parts.size() >= MAX_PARTS) {
                break;
            }
            try {
                // A bare word, or {"part": "head", "count": 3} for a part there is an unusual
                // number of (round 2). Either way only the vocabulary crosses.
                String p;
                if (el.isJsonObject()) {
                    p = str(el.getAsJsonObject(), "part").toLowerCase(Locale.ROOT).trim();
                    JsonElement c = el.getAsJsonObject().get("count");
                    if (PARTS.contains(p) && c != null && c.isJsonPrimitive()) {
                        counts.put(p, c.getAsInt());
                    }
                } else {
                    p = el.getAsString().toLowerCase(Locale.ROOT).trim();
                }
                if (PARTS.contains(p)) {
                    parts.add(p);
                }
            } catch (RuntimeException ignored) {
                // a non-string in a string list is simply not a part
            }
        }
        if (o.has("counts") && o.get("counts").isJsonObject()) {
            for (var e : o.getAsJsonObject("counts").entrySet()) {
                try {
                    counts.putIfAbsent(e.getKey().toLowerCase(Locale.ROOT).trim(),
                            e.getValue().getAsInt());
                } catch (RuntimeException ignored) {
                    // not a number, not a count
                }
            }
        }

        List<String> sources = new ArrayList<>();
        for (JsonElement el : arr(o, "sources")) {
            if (sources.size() >= MAX_SOURCES) {
                break;
            }
            try {
                String u = el.getAsString().trim();
                // Recorded for provenance ONLY, and shape-checked so the field cannot be used to
                // carry a sentence. Never re-read by the wish agent as instructions.
                if ((u.startsWith("https://") || u.startsWith("http://"))
                        && u.length() <= 300 && !u.contains(" ")) {
                    sources.add(u);
                }
            } catch (RuntimeException ignored) {
                // not a URL, not recorded
            }
        }

        // SAME SHAPE CHECK AS sources, for the same reason: this is a URL and nothing else. It
        // is never read back as instructions -- it is handed to a socket and then to a histogram.
        List<String> images = new ArrayList<>();
        for (JsonElement el : arr(o, "images")) {
            if (images.size() >= MAX_IMAGES) {
                break;
            }
            try {
                String u = el.getAsString().trim();
                if (u.startsWith("https://") && u.length() <= 300 && !u.contains(" ")) {
                    images.add(u);
                }
            } catch (RuntimeException ignored) {
                // not a URL, not recorded
            }
        }

        List<RegionColour> regions = new ArrayList<>();
        for (JsonElement el : arr(o, "regions")) {
            try {
                JsonObject r = el.getAsJsonObject();
                Region reg = enumOf(Region.class, str(r, "region"), null);
                String col = str(r, "colour");
                if (reg != null && COLOURS.contains(col)) {
                    regions.add(new RegionColour(reg, col,
                            Math.max(0, Math.min(100, r.get("percent").getAsInt()))));
                }
            } catch (RuntimeException ignored) {
                // outside the vocabulary, so not recorded
            }
        }
        Confidence cc = enumOf(Confidence.class, str(o, "colourConfidence"), null);
        ColourSource cs = enumOf(ColourSource.class, str(o, "colourSource"), ColourSource.RECALLED);

        return new ReferenceSpec(colours, List.copyOf(parts), enumOf(Size.class,
                str(o, "size"), Size.UNKNOWN),
                enumOf(Confidence.class, str(o, "confidence"), Confidence.NONE), sources,
                enumOf(Expression.class, str(o, "expression"), Expression.UNKNOWN), images,
                cc, cs, regions, counts,
                enumOf(Stance.class, str(o, "stance"), Stance.UNKNOWN),
                enumOf(Tail.class, str(o, "tail"), Tail.UNKNOWN));
    }

    /**
     * Is {@code raw} an ANSWER at all — a JSON object somewhere in it — rather than nothing, junk or an error message?
     * A genuine "nothing found" is an answer ({@code confidence NONE}); an unparseable reply is not, and must never be
     * cached as one (smoke).
     */
    public static boolean isAnswer(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            return JsonParser.parseString(extractJson(raw)).isJsonObject();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * A model asked for JSON often wraps it in prose or a fenced block. Taking the outermost
     * braces is what makes the boundary usable in practice; anything outside them is discarded
     * unread, which is the point.
     */
    private static String extractJson(String raw) {
        int a = raw.indexOf('{');
        int b = raw.lastIndexOf('}');
        return a >= 0 && b > a ? raw.substring(a, b + 1) : raw;
    }

    private static JsonArray arr(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }

    private static String str(JsonObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k) && o.get(k).isJsonPrimitive()) {
                return o.get(k).getAsString();
            }
        }
        return "";
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String v, E fallback) {
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, v.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /**
     * Render for the wish agent. Built entirely from validated fields — never by echoing the
     * researcher's text — so what the agent reads is assembled from enums and integers on this
     * side of the boundary.
     */
    public String describe() {
        if (isEmpty()) {
            return "No usable reference was found. Build from the child's own words, say so in "
                    + "your note, and do not imply an official version exists.";
        }
        StringBuilder sb = new StringBuilder("Reference:\n");

        // ---- SHAPE -------------------------------------------------------------------------
        // Its confidence answers ONE question: is this the right character? That is the axis the
        // researcher can be wrong about, and it is separate from whether the colours are precise.
        sb.append("\n  SHAPE — ").append(shapeLine()).append('\n');
        if (!parts.isEmpty()) {
            // A BARE LIST GETS CHERRY-PICKED. Measured: handed "head, body, beak, eye, hair, wing,
            // leg" for Omega Lad, the agent took `hair` -- which fitted its idea of a guy in
            // shades -- and silently dropped `beak` and `wing`, then built a man in a suit with a
            // gold chain. The colour section had an instruction attached and was followed exactly;
            // this one did not and was not. So the parts get the same force.
            sb.append("  notable parts: ").append(partsLine()).append('\n');
            if (!counts.isEmpty()) {
                sb.append("  COUNT THEM: a part marked ×N has exactly that many, which is not the "
                        + "usual number — the count is part of what this creature IS.\n");
            }
            sb.append("  EVERY PART NAMED THERE MUST EXIST IN WHAT YOU BUILD. They are what "
                    + "makes this character recognisable, and they are not a menu to pick from. "
                    + "Build what is listed; anything you add on top is yours.\n");
            // NAME THE PART MOST LIKELY TO BE DROPPED. The measured failure was cherry-picking:
            // handed head/body/beak/eye/hair/wing/leg, the agent kept `hair` (which fitted its
            // idea of a man in shades) and quietly lost `beak` and `wing`. A generic example --
            // "if this says leg" -- carries no force, because everything has legs. The part that
            // CONTRADICTS a humanoid reading is the one worth pointing at.
            String tell = tellingPart();
            if (tell != null) {
                sb.append("  In particular: this character has a ").append(tell)
                  .append(". If the ").append(tell).append(" does not survive into your sculpt, "
                          + "you have built something else and called it by this name.\n");
            }
        }
        if (size != Size.UNKNOWN) {
            sb.append("  size: ").append(size.name().toLowerCase(Locale.ROOT)).append('\n');
        }
        if (expression != Expression.UNKNOWN) {
            // MOOD IS PART OF WHO A MASCOT IS. The measured failure is not a wrong
            // expression but NO expression: 52 of 59 archived faces built their mouth as a single
            // box, and one box cannot curve.
            sb.append("  expression: ").append(expression.name().toLowerCase(Locale.ROOT))
              .append(" — this is part of WHO THEY ARE, not decoration. Build it: a mouth is "
                      + "three or more boxes stepped into a curve (corners above the middle for a "
                      + "smile, below it for a frown), and brows are a PAIR.\n");
        }
        String body = bodyLine();
        if (!body.isEmpty()) {
            // HOW IT STANDS. "a cat" built Tabbycat on all fours; the reference knows he walks upright.
            sb.append("  body: ").append(body).append(" Build it that way: this is part of who it is, and a ")
              .append("ready-made body that stands differently is the wrong starting point.\n");
        }
        if (confidence == Confidence.LOW) {
            sb.append("  ⚠ This may not be the right character — the name may be shared with a "
                    + "song, a game or another character. Check it against what the child asked "
                    + "for: if it describes something they would NOT recognise, say so and build "
                    + "from their words instead.\n");
        }

        // ---- COLOUR ------------------------------------------------------------------------
        if (!colours.isEmpty()) {
            sb.append("\n  COLOUR — ").append(colourLine()).append('\n');
            sb.append("  overall: ");
            for (int i = 0; i < colours.size(); i++) {
                sb.append(i > 0 ? ", " : "").append(colours.get(i).colour())
                  .append(' ').append(colours.get(i).percent()).append('%');
            }
            sb.append('\n');
            // WHERE the colour is, which a single percentage cannot say. "tan/orange 57%" is true
            // of Spikor and does not tell anyone the shell is green and on his BACK.
            if (!regions.isEmpty()) {
                sb.append("  by height:");
                for (RegionColour r : regions) {
                    sb.append(' ').append(r.region().name().toLowerCase(Locale.ROOT))
                      .append(" third mostly ").append(r.colour())
                      .append(" (").append(r.percent()).append("%);");
                }
                sb.append("\n  Put the colours where they belong — a character that is the right "
                        + "colours in the wrong places is still the wrong character.\n");
            }
            // WHAT THE MEASUREMENT CANNOT SEE, said out loud. Official artwork is overwhelmingly
            // front-facing, so a feature on the character's BACK is barely in frame and its colour
            // comes out as a floor rather than an estimate. Measured: Spikor's shell put green at
            // 4%, which no honest number is. The agent partially discounted it and landed near
            // 45-53% green, which was luck -- a more obedient one would have built a Kroak with no
            // shell, and that is the failure this note exists to prevent.
            String hidden = backFacingPart();
            if (colourSource == ColourSource.MEASURED && hidden != null) {
                sb.append("  ⚠ These were counted from front-facing artwork, so anything on this "
                        + "character's BACK is barely in frame — it has a ").append(hidden)
                  .append(", and that part's colour is a FLOOR, not an estimate. Build the ")
                  .append(hidden).append(" properly and let it take the share it needs.\n");
            }
            sb.append(colourInstruction());
        }

        // ---- THE RANKING, STATED ONCE ------------------------------------------------------
        // This used to say "weigh it against your own knowledge", and the agent duly weighed a
        // sourced penguin against a remembered man in a suit and picked the suit. Recall is LEAST
        // reliable exactly where a character is obscure, which is where a lookup happens at all.
        sb.append("\n  THIS OUTRANKS YOUR MEMORY of the character, at every confidence level — "
                + "correcting recall is what the lookup is FOR. The only thing that outranks it "
                + "is the CHILD'S OWN WORDS: if they asked for a green one, build a green one and "
                + "say why.\n");
        return sb.toString();
    }

    /** The parts, with a ×N after any the reference counted. */
    String partsLine() {
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            Integer n = counts.get(p);
            out.add(n == null ? p : p + " ×" + n);
        }
        return String.join(", ", out);
    }

    /**
     * Parts that any humanoid has, so naming one as "the telling detail" says nothing. Everything
     * outside this set is a claim about what KIND of thing the character is.
     */
    private static final Set<String> UNREMARKABLE = Set.of(
            "head", "body", "arm", "leg", "eye", "hair", "nose", "hat", "shoe",
            "mouth", "neck", "cheek");

    /**
     * Parts that live on a character's back, where front-facing artwork cannot see them. Not a
     * general anatomy claim — just the ones whose colour a front render systematically loses.
     */
    private static final Set<String> BEHIND = Set.of("shell", "wing", "tail", "spike");

    /** A named part the measurement could not have seen properly, or null. */
    private String backFacingPart() {
        for (String p : parts) {
            if (BEHIND.contains(p)) {
                return p;
            }
        }
        return null;
    }

    /** The first part that contradicts a plain humanoid, or null when there is no such part. */
    private String tellingPart() {
        for (String p : parts) {
            if (!UNREMARKABLE.contains(p)) {
                return p;
            }
        }
        return null;
    }

    /** How sure the researcher is that it found the right character. */
    private String shapeLine() {
        return switch (confidence) {
            case HIGH -> (sources.isEmpty() ? "confident this is the right character"
                    : "sourced from " + sources.size() + " page(s), confident this is the "
                            + "right character");
            case MEDIUM -> "probably the right character, details uncertain";
            case LOW -> "UNSURE this is the right character";
            case NONE -> "nothing found";
        };
    }

    /** Where the palette came from and how precise it is — a different question from the shape. */
    private String colourLine() {
        boolean sole = colourSource == ColourSource.MEASURED && colourConfidence == Confidence.LOW;
        String how = switch (colourSource) {
            case MEASURED -> sole ? "COUNTED from the only picture of this character there is"
                    : "COUNTED from real pictures of the character";
            case SOURCED -> "read from pages and search results, not counted";
            case RECALLED -> "the researcher's recollection, not sourced";
        };
        String sure = switch (colourConfidence) {
            case HIGH -> "the pictures agreed";
            case MEDIUM -> "the pictures disagreed somewhat on the proportions";
            // MEASURED + LOW can only be the SOLE-ARTWORK case: an unstable multi-render reading
            // never reaches here at all, because measurePalette discards it and keeps the model's
            // colours. So this is not a vague answer -- it is an exact count of the only picture
            // of this character in existence, with nothing to corroborate it.
            case LOW, NONE -> sole ? "exact, but nothing corroborates it"
                    : "treat as a rough impression";
        };
        return how + " — " + sure;
    }

    private String colourInstruction() {
        if (colourSource == ColourSource.MEASURED && colourConfidence == Confidence.HIGH) {
            return "  Pass these as your 'palette'. They were counted, so they are not an "
                    + "opinion and they are not a memory.\n";
        }
        if (colourConfidence == Confidence.HIGH || colourConfidence == Confidence.MEDIUM) {
            // The distinction that matters below HIGH is PRECISION, not authority. Which colour
            // leads, and which band it sits in, survive; the exact percentages do not.
            return "  Follow the ORDER of these colours and which part of the body each belongs "
                    + "to. Do NOT treat the exact percentages as precise — get the ranking right "
                    + "and you have the character.\n";
        }
        if (colourSource == ColourSource.MEASURED) {
            // Do NOT tell the agent to ignore the proportions here. They were counted off real
            // pixels and are the most accurate thing in this whole brief -- measured, the
            // remembered palette for this very case missed the character's signature colour
            // outright. What is unknown is only whether a SECOND drawing would have agreed, and
            // for a character with one drawing there is no second to have.
            return "  Pass these as your 'palette'. They were counted, so they are not a memory "
                    + "— but they come from a single picture, which is all that exists of this "
                    + "character, so nothing corroborates them. Build them, and say so in your "
                    + "note.\n";
        }
        return "  Rough. Use it for which colours are present at all, not for how much of each, "
                + "and say in your note that you could not check it properly.\n";
    }
}
