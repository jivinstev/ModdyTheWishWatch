package com.moddingfromamod.world;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.wish.WishText;

/**
 * <b>Everything the world-rules grader is allowed to say</b> — the typed boundary
 * {@code docs/PATTERNS.md} §2 asks of any model's output that crosses back: an enum, a closed set
 * of {@link WorldEffect}s, and two short free-text lines, each sanitised, bounded and — before a
 * child reads either — passed through the text sweep ({@link WorldRulesGate}). Nothing here throws;
 * every field degrades on its own, and a reply nobody can read is {@link Kind#UNSURE}, never a
 * refusal: this grader fails OPEN, because the reach backstop still judges what the agent does.
 *
 * @param kind        the verdict
 * @param effects     what the wish needs beyond the child's base (at most four, closed vocabulary)
 * @param allowedPart for {@link Kind#MIXED}: the part that can be granted, as a short wish
 * @param suggestion  for a refusal or a split: something he could wish for here instead
 * @param provenance  who decided — the model, nobody, or a test
 */
public record WorldRulesVerdict(Kind kind, Set<WorldEffect> effects, String allowedPart, String suggestion,
                                Provenance provenance) {

    /** The five answers. Only {@link #WORLD_WIDE} can refuse, and only for an effect the world denies. */
    public enum Kind { ALLOWED, UNSURE, WORLD_WIDE, MIXED, HYPERBOLE }

    /** Who produced it. */
    public enum Provenance { HAIKU, UNAVAILABLE, TEST }

    /** The longest free-text line kept. */
    public static final int MAX_TEXT = 160;
    static final int MAX_EFFECTS = 4;

    public WorldRulesVerdict {
        effects = effects == null || effects.isEmpty() ? Set.of() : Set.copyOf(effects);
        allowedPart = clean(allowedPart);
        suggestion = clean(suggestion);
    }

    /** The grader could not be asked, or did not answer: let it through. */
    public static WorldRulesVerdict unavailable() {
        return new WorldRulesVerdict(Kind.UNSURE, Set.of(), "", "", Provenance.UNAVAILABLE);
    }

    public boolean isUnavailable() {
        return provenance == Provenance.UNAVAILABLE;
    }

    /** The wire name the log and the eval use: {@code world_wide}. */
    public String label() {
        return kind.name().toLowerCase(Locale.ROOT);
    }

    /** Read the model's structured output. Null or garbage → {@link #unavailable()}. */
    public static WorldRulesVerdict parse(String json) {
        if (json == null || json.isBlank()) {
            return unavailable();
        }
        JsonObject o;
        try {
            JsonElement el = JsonParser.parseString(json);
            if (!el.isJsonObject()) {
                return unavailable();
            }
            o = el.getAsJsonObject();
        } catch (RuntimeException e) {
            return unavailable();
        }
        Kind kind = Kind.UNSURE;
        try {
            String v = o.has("verdict") ? o.get("verdict").getAsString().strip().toUpperCase(Locale.ROOT) : "";
            kind = Kind.valueOf(v);
        } catch (RuntimeException e) {
            kind = Kind.UNSURE;                    // an answer we cannot read is not a refusal
        }
        Set<WorldEffect> effects = EnumSet.noneOf(WorldEffect.class);
        try {
            if (o.has("effects") && o.get("effects").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("effects")) {
                    WorldEffect eff = e.isJsonPrimitive() ? WorldEffect.ofKey(e.getAsString()) : null;
                    if (eff != null && effects.size() < MAX_EFFECTS) {
                        effects.add(eff);
                    }
                }
            }
        } catch (RuntimeException e) {
            effects.clear();
        }
        return new WorldRulesVerdict(kind, effects, text(o, "allowedPart"), text(o, "suggestion"),
                Provenance.HAIKU);
    }

    private static String text(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** One line, no control characters, bounded — the treatment the wish itself gets. */
    static String clean(String s) {
        if (s == null) {
            return "";
        }
        String t = WishText.sanitize(s).strip();
        if (t.length() > MAX_TEXT) {
            t = t.substring(0, MAX_TEXT).strip();
        }
        return t;
    }
}
