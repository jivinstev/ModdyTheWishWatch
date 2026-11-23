package com.moddingfromamod.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <b>Start from a creature that already exists, and send only the difference.</b>
 *
 * <h2>Why</h2>
 *
 * <p>The agent builds every creature from zero boxes. {@code docs/RUNTIME_MOBS.md} says plainly that
 * <i>every good mob in the shipped mods started from a real one</i>, and calls "make a giant mutant
 * version of the illusioner" the most common shape of request — but there was no way to do it, so a
 * variant of an existing creature cost a full 30-box sculpt exactly like a new one.
 *
 * <p>This is the same economics as the part-kits, one level up. A kit turns twelve tokens into a
 * horn; a base turns eight tokens into a whole animal. The agent stops describing the body and
 * describes only what changed — which is both cheaper and, on a wish clock a child is watching, the
 * only kind of improvement that is free.
 *
 * <h2>What a base can be, and what it deliberately cannot</h2>
 *
 * <p>A base is <b>a creature already sculpted in this world</b>, read back from the {@code
 * sculpt.json} that {@code CreatureFiles} saves beside every model. That spec is the exact parts
 * list the agent originally passed, so the base is lossless — no geometry-to-parts conversion, no
 * sampling colours back out of a texture.
 *
 * <p>Vanilla mobs are <b>not</b> reachable here, and that is a deliberate limit rather than an
 * oversight. {@code tools/blockbench/import_vanilla.py} fetches them from Mojang's bedrock-samples
 * over the network and from the player's own client jar — the agent has no web access by design, and
 * bundling Mojang's assets into this jar is a licensing decision that is not the agent's to make. A
 * human who runs that script puts the model in the repository, after which it is a base like any
 * other.
 *
 * <h2>The edit language</h2>
 *
 * <p>Four operations, chosen because together they cover the requests that actually arrive and
 * nothing costs more than a few tokens:
 *
 * <ul>
 *   <li><b>add</b> — any part in {@code parts} that the base does not have</li>
 *   <li><b>replace</b> — a part whose name matches a base part wins, so "make the head bigger" is
 *       one box rather than a re-sculpt. It keeps the base part's {@code parent} unless it names
 *       one of its own </li>
 *   <li><b>remove</b> — drop base parts by name ("a Spikor with no shell")</li>
 *   <li><b>recolour</b> — retint base parts by name ("the same dragon but red")</li>
 * </ul>
 *
 * <p>Pure and Minecraft-free, so the merge is unit-tested without a game.
 */
public final class SculptBase {

    private SculptBase() {
    }

    /**
     * The base, edited.
     *
     * <p>Order is preserved: surviving base parts first, in their original order, then the
     * additions. That keeps a diff readable next to the creature it came from, and keeps the merge
     * deterministic.
     *
     * @param base     the parts of the creature being started from
     * @param added    the agent's own parts; one whose name matches a base part <b>replaces</b> it
     * @param remove   base part names to drop (loose match, so "spike" drops "spike1".."spike9")
     * @param recolour base part name → new colour (loose match, same rule)
     */
    public static List<CreatureSculpt.Part> merge(List<CreatureSculpt.Part> base,
                                                  List<CreatureSculpt.Part> added,
                                                  Set<String> remove,
                                                  Map<String, Integer> recolour) {
        List<CreatureSculpt.Part> safeBase = base == null ? List.of() : base;
        List<CreatureSculpt.Part> safeAdded = added == null ? List.of() : added;

        // Replacement is by name, so index the additions once rather than scanning per base part.
        Map<String, CreatureSculpt.Part> byName = new LinkedHashMap<>();
        for (CreatureSculpt.Part p : safeAdded) {
            byName.put(p.name().toLowerCase(Locale.ROOT), p);
        }

        Map<String, float[]> moves = carried(safeBase, safeAdded, remove);
        List<CreatureSculpt.Part> out = new ArrayList<>();
        Set<String> consumed = new java.util.HashSet<>();
        for (CreatureSculpt.Part p : safeBase) {
            String lower = p.name().toLowerCase(Locale.ROOT);
            if (matchesAny(lower, remove)) {
                continue;
            }
            CreatureSculpt.Part replacement = byName.get(lower);
            if (replacement != null) {
                out.add(keepingItsPlace(replacement, p));
                consumed.add(lower);
                continue;
            }
            Integer colour = colourFor(lower, recolour);
            CreatureSculpt.Part kept = colour == null ? p : recoloured(p, colour);
            float[] d = moves.get(p.name());
            out.add(d == null ? kept : moved(kept, d));
        }
        for (CreatureSculpt.Part p : safeAdded) {
            if (!consumed.contains(p.name().toLowerCase(Locale.ROOT))) {
                out.add(p);
            }
        }
        // The same budget a from-scratch creature obeys: a base must not be a way around it.
        return out.size() > CreatureSculpt.MAX_PARTS
                ? out.subList(0, CreatureSculpt.MAX_PARTS) : out;
    }

    /**
     * <b>A base part re-skinned by name</b> (2026-10-10): each base part named in {@code skins} keeps
     * its box and takes the {@code pattern}, {@code pattern_colour} and {@code colour} that entry
     * sends; everything it does not send stays as it was. See
     * {@code SculptCreatureTool.skins} for why. Pure.
     */
    public static List<CreatureSculpt.Part> reskinned(List<CreatureSculpt.Part> base,
                                                      Map<String, com.google.gson.JsonObject> skins) {
        if (base == null || skins == null || skins.isEmpty()) {
            return base;
        }
        List<CreatureSculpt.Part> out = new ArrayList<>(base.size());
        for (CreatureSculpt.Part p : base) {
            com.google.gson.JsonObject o = skins.get(p.name().toLowerCase(Locale.ROOT));
            if (o == null) {
                out.add(p);
                continue;
            }
            String pattern = p.pattern();
            if (o.has("pattern") && o.get("pattern").isJsonPrimitive()) {
                pattern = SculptPattern.sanitize(o.get("pattern").getAsString());
            }
            int patternColour = p.patternColour();
            for (String k : new String[] {"pattern_colour", "pattern_color", "patternColour"}) {
                if (o.has(k) && o.get(k).isJsonPrimitive()) {
                    patternColour = com.moddingfromamod.species.Species.parseColour(o.get(k).getAsString());
                }
            }
            int colour = p.colour();
            for (String k : new String[] {"colour", "color"}) {
                if (o.has(k) && o.get(k).isJsonPrimitive()) {
                    colour = com.moddingfromamod.species.Species.parseColour(o.get(k).getAsString());
                }
            }
            out.add(new CreatureSculpt.Part(p.name(), p.parent(), p.pivot(), p.from(), p.to(), colour,
                    p.rotation(), p.inflate(), pattern, patternColour));
        }
        return out;
    }

    /**
     * <b>A replaced part carries what hangs on it.</b>
     *
     * <p>"start_from the cat, body and head like this" re-sends two boxes, often in a frame of the
     * agent's own (the starter cat is centred on x=0; Muse sent a body at [0,0,0]-[8,8,8]). Every
     * base part it did not mention stayed where the OLD body and head were, so the ears and legs
     * hung in the air beside the new creature. Measured over the archived wishes: every
     * {@code start_from} sculpt that replaced a body part and came back FLOATING was this, and the
     * agent finished the wish anyway.
     *
     * <p>Each surviving base part hangs on one CARRIER: its declared {@code parent}, else the
     * bigger part it touches most. When a carrier is replaced, the point where the part met the
     * old box is mapped to the same relative spot on the new box, and the part moves by that much,
     * size unchanged. A part riding on a carried part moves with it. Pure; the result names the
     * parts and how far each moved, so the tool can say so out loud.
     *
     * @return base part name → [dx, dy, dz], only for parts that actually moved
     */
    public static Map<String, float[]> carried(List<CreatureSculpt.Part> base,
                                               List<CreatureSculpt.Part> added,
                                               Set<String> remove) {
        Map<String, float[]> out = new LinkedHashMap<>();
        if (base == null || base.isEmpty() || added == null || added.isEmpty()) {
            return out;
        }
        Map<String, CreatureSculpt.Part> replacedBy = new LinkedHashMap<>();
        for (CreatureSculpt.Part a : added) {
            replacedBy.put(a.name().toLowerCase(Locale.ROOT), a);
        }
        Map<String, CreatureSculpt.Part> byName = new LinkedHashMap<>();
        for (CreatureSculpt.Part p : base) {
            byName.put(p.name().toLowerCase(Locale.ROOT), p);
        }
        Map<String, float[]> memo = new java.util.HashMap<>();
        for (CreatureSculpt.Part p : base) {
            String lower = p.name().toLowerCase(Locale.ROOT);
            if (replacedBy.containsKey(lower) || matchesAny(lower, remove)) {
                continue;
            }
            float[] d = delta(p, base, byName, replacedBy, remove, memo, 0);
            if (Math.abs(d[0]) > 1e-4f || Math.abs(d[1]) > 1e-4f || Math.abs(d[2]) > 1e-4f) {
                out.put(p.name(), d);
            }
        }
        return out;
    }

    /** How many of a base's biggest top-level parts {@link #outline} names. */
    static final int OUTLINE_PARTS = 6;

    /**
     * <b>Where a base's parts ARE, before anything is sculpted on it.</b>
     *
     * <p>With {@code start_from} the agent sculpts blind: it never sees the base's boxes, so it
     * invents its own. Measured on three fresh cat wishes: two sent a part far from where the cat's
     * really is (a head at y 20 where the cat's is at y 9, a hat 14 px above the head), and the
     * original diamond cat sent a body in a frame of its own. Every one cost a send-back, and one
     * still shipped a hat hanging off an ear. Said up front, a hat is a subtraction, not a guess.
     *
     * <p>The biggest top-level parts with their exact boxes, then the head's top centre and front
     * face (front is −z). Pure.
     */
    public static String outline(String key, List<CreatureSculpt.Part> parts) {
        if (parts == null || parts.isEmpty()) {
            return "";
        }
        List<CreatureSculpt.Part> roots = new ArrayList<>();
        for (CreatureSculpt.Part p : parts) {
            if (p.parent() == null) {
                roots.add(p);
            }
        }
        roots.sort((a, b) -> Double.compare(volume(b), volume(a)));
        StringBuilder sb = new StringBuilder("'" + key + "' is built from (from → to, in sculpt units):");
        for (CreatureSculpt.Part p : roots.subList(0, Math.min(OUTLINE_PARTS, roots.size()))) {
            float[] b = bounds(p);
            sb.append("\n    ").append(p.name()).append(" [").append(n(b[0])).append(',').append(n(b[1]))
              .append(',').append(n(b[2])).append("] → [").append(n(b[3])).append(',').append(n(b[4]))
              .append(',').append(n(b[5])).append(']');
        }
        for (CreatureSculpt.Part p : parts) {
            if (SculptHeadAdoption.isHeadBone(p.name()) && !p.name().equalsIgnoreCase("neck")) {
                float[] b = bounds(p);
                sb.append("\n    its head's TOP is y ").append(n(b[4])).append(", centred on x ")
                  .append(n((b[0] + b[3]) / 2)).append(", z ").append(n((b[2] + b[5]) / 2))
                  .append("; its FACE is the z ").append(n(b[2])).append(" side");
                break;
            }
        }
        return sb.toString();
    }

    private static String n(float v) {
        return v == Math.round(v) ? Integer.toString(Math.round(v)) : String.format(Locale.ROOT, "%.1f", v);
    }

    /** A replacement within this factor of the base part's volume is an edit; outside it, a new shape. */
    static final double RESHAPE_FACTOR = 1.5;

    /** A replaced part that grew or shrank a lot while other parts hang on it. */
    public record Reshape(String name, float[] was, float[] now, double ratio, List<String> carries) {

        public String describe(String base) {
            return "'" + name + "': " + base + "'s is " + size(was) + " at " + corner(was) + "; yours is "
                    + size(now) + " at " + corner(now) + String.format(Locale.ROOT, " (%.2f× the size)", ratio)
                    + ". On it: " + String.join(", ", carries);
        }

        /**
         * The {@code reshape} entry that turns the base's box into the one sent: a scale per
         * axis about the centre and the move of the centre, or null when a scale falls outside what
         * {@link SculptReshape} accepts. Computed, never guessed — it is the box the agent sent.
         */
        public String asReshape() {
            float[] scale = new float[3];
            float[] move = new float[3];
            for (int i = 0; i < 3; i++) {
                float w = was[i + 3] - was[i];
                float n = now[i + 3] - now[i];
                scale[i] = w <= 0 ? 1f : Math.round(n / w * 100f) / 100f;
                if (scale[i] < SculptReshape.MIN_SCALE || scale[i] > SculptReshape.MAX_SCALE) {
                    return null;
                }
                move[i] = Math.round(((now[i] + now[i + 3]) - (was[i] + was[i + 3])) / 2f * 2f) / 2f;
            }
            String line = "{\"part\":\"" + name + "\",\"scale\":[" + fmt(scale[0]) + "," + fmt(scale[1]) + ","
                    + fmt(scale[2]) + "],\"anchor\":\"centre\"";
            if (move[0] != 0 || move[1] != 0 || move[2] != 0) {
                line += ",\"move\":[" + fmt(move[0]) + "," + fmt(move[1]) + "," + fmt(move[2]) + "]";
            }
            return line + "}";
        }

        private static String size(float[] b) {
            return fmt(b[3] - b[0]) + "×" + fmt(b[4] - b[1]) + "×" + fmt(b[5] - b[2]);
        }

        private static String corner(float[] b) {
            return "[" + fmt(b[0]) + "," + fmt(b[1]) + "," + fmt(b[2]) + "]";
        }

        private static String fmt(float v) {
            return v == Math.round(v) ? Integer.toString(Math.round(v))
                    : String.format(Locale.ROOT, "%.2f", v).replaceAll("0$", "");
        }
    }

    /**
     * <b>A base part re-sent as a box in its own colour keeps its skin</b>. Spark resizes a
     * base part by re-sending its box (61 of 76 routing edits on the base-family holdout) and leaves
     * the pattern off, so the fur or scales the starter was made with came off with the resize. A
     * replacement in the base part's own colour, with no pattern of its own, is the same part at a
     * new size: it takes the base part's pattern. A new colour is a new look and is left alone.
     */
    public static List<CreatureSculpt.Part> keepSkin(List<CreatureSculpt.Part> base, List<CreatureSculpt.Part> sent) {
        if (base == null || base.isEmpty() || sent == null) {
            return sent;
        }
        List<CreatureSculpt.Part> out = new ArrayList<>(sent.size());
        for (CreatureSculpt.Part p : sent) {
            CreatureSculpt.Part kept = p;
            if (!p.patterned()) {
                for (CreatureSculpt.Part b : base) {
                    if (b.name().equalsIgnoreCase(p.name()) && b.patterned() && b.colour() == p.colour()) {
                        kept = new CreatureSculpt.Part(p.name(), p.parent(), p.pivot(), p.from(), p.to(), p.colour(),
                                p.rotation(), p.inflate(), b.pattern(), b.patternColour());
                        break;
                    }
                }
            }
            out.add(kept);
        }
        return out;
    }

    /**
     * Drops {@code from}/{@code to} from a sent part whose box is a 1×1×1 placeholder standing in for
     * a base part at least 4 across: Spark sends {@code head [1,1,1]} to mean "the head",
     * 8–10 times per routing arm, and it would shrink an 8×6×12 head to a cube. Read as name-only.
     *
     * @return the names it changed
     */
    public static List<String> placeholdersAreNameOnly(List<CreatureSculpt.Part> base, com.google.gson.JsonArray parts) {
        List<String> out = new ArrayList<>();
        if (base == null || parts == null) {
            return out;
        }
        for (var el : parts) {
            if (!el.isJsonObject()) {
                continue;
            }
            var o = el.getAsJsonObject();
            if (!o.has("name") || !o.get("name").isJsonPrimitive() || !o.has("from") || !o.has("to")
                    || !o.get("from").isJsonArray() || !o.get("to").isJsonArray()) {
                continue;
            }
            String name = o.get("name").getAsString();
            var f = o.getAsJsonArray("from");
            var t = o.getAsJsonArray("to");
            boolean tiny = f.size() == 3 && t.size() == 3;
            for (int i = 0; tiny && i < 3; i++) {
                try {
                    tiny = Math.abs(t.get(i).getAsFloat() - f.get(i).getAsFloat()) <= 1f;
                } catch (RuntimeException e) {
                    tiny = false;
                }
            }
            if (!tiny) {
                continue;
            }
            for (CreatureSculpt.Part b : base) {
                if (b.name().equalsIgnoreCase(name)) {
                    float big = 0;
                    for (int i = 0; i < 3; i++) {
                        big = Math.max(big, Math.abs(b.to()[i] - b.from()[i]));
                    }
                    if (big >= 4f) {
                        o.remove("from");
                        o.remove("to");
                        out.add(name);
                    }
                    break;
                }
            }
        }
        return out;
    }

    /**
     * <b>When carrying is the wrong fix.</b> (a maintainer's grades)
     *
     * <p>Carrying is right for an edit: a head moved or made a little bigger takes its ears along.
     * It is wrong when the agent replaced a part with a box of a very different SIZE — the starter
     * cat's 8×7×16 body sent as 8×8×8, a dragon's head shrunk to a third. A maintainer graded both: the
     * carried result was one piece but "clearly not the intent", because the mistake was the shape,
     * and moving the ears onto it only hid that. So these go back to the agent, once, with both
     * boxes named; the parts that hang on it are listed so it can see what an unchanged resend
     * would drag along. Measured over the archive: 2 of 29 inheriting sculpts, exactly those two.
     *
     * @return replaced parts outside {@link #RESHAPE_FACTOR} of their base volume that carry something
     */
    public static List<Reshape> reshaped(List<CreatureSculpt.Part> base, List<CreatureSculpt.Part> added,
                                         Set<String> remove) {
        List<Reshape> out = new ArrayList<>();
        if (base == null || added == null) {
            return out;
        }
        for (CreatureSculpt.Part now : added) {
            for (CreatureSculpt.Part was : base) {
                if (!was.name().equalsIgnoreCase(now.name())) {
                    continue;
                }
                double ratio = volume(now) / Math.max(1e-6, volume(was));
                if (ratio >= 1 / RESHAPE_FACTOR && ratio <= RESHAPE_FACTOR) {
                    break;
                }
                // what would ride on THIS replacement alone, not on the others sent with it
                List<String> carries = new ArrayList<>(carried(base, List.of(now), remove).keySet());
                Set<String> sent = new java.util.HashSet<>();
                added.forEach(a -> sent.add(a.name().toLowerCase(Locale.ROOT)));
                carries.removeIf(n -> sent.contains(n.toLowerCase(Locale.ROOT)));
                if (!carries.isEmpty()) {
                    out.add(new Reshape(now.name(), bounds(was), bounds(now), ratio, carries));
                }
                break;
            }
        }
        return out;
    }

    private static float[] bounds(CreatureSculpt.Part p) {
        return new float[]{lo(p, 0), lo(p, 1), lo(p, 2), hi(p, 0), hi(p, 1), hi(p, 2)};
    }

    private static final float[] STILL = {0f, 0f, 0f};

    private static float[] delta(CreatureSculpt.Part p, List<CreatureSculpt.Part> base,
                                 Map<String, CreatureSculpt.Part> byName,
                                 Map<String, CreatureSculpt.Part> replacedBy, Set<String> remove,
                                 Map<String, float[]> memo, int depth) {
        String lower = p.name().toLowerCase(Locale.ROOT);
        float[] known = memo.get(lower);
        if (known != null) {
            return known;
        }
        memo.put(lower, STILL);   // a parent cycle resolves to "does not move"
        float[] d = STILL;
        CreatureSculpt.Part c = carrier(p, base, byName);
        if (c != null && depth < 16) {
            String cl = c.name().toLowerCase(Locale.ROOT);
            CreatureSculpt.Part now = replacedBy.get(cl);
            if (now != null) {
                d = remapped(p, c, now);
            } else if (!matchesAny(cl, remove)) {
                d = delta(c, base, byName, replacedBy, remove, memo, depth + 1);
            }
        }
        memo.put(lower, d);
        return d;
    }

    /** The declared parent, else the BIGGER part this one touches most (so a body never rides a leg). */
    static CreatureSculpt.Part carrier(CreatureSculpt.Part p, List<CreatureSculpt.Part> base,
                                       Map<String, CreatureSculpt.Part> byName) {
        if (p.parent() != null) {
            CreatureSculpt.Part parent = byName.get(p.parent().toLowerCase(Locale.ROOT));
            if (parent != null && parent != p) {
                return parent;
            }
        }
        double mine = volume(p);
        int myIndex = base.indexOf(p);
        CreatureSculpt.Part best = null;
        double bestContact = 0;
        for (int i = 0; i < base.size(); i++) {
            CreatureSculpt.Part o = base.get(i);
            double v = volume(o);
            if (o == p || v < mine || (v == mine && i > myIndex)) {
                continue;
            }
            double contact = contact(p, o);
            if (contact > bestContact) {
                bestContact = contact;
                best = o;
            }
        }
        return best;
    }

    /** Where {@code p} met {@code was}, moved to the same relative spot on {@code now}. */
    private static float[] remapped(CreatureSculpt.Part p, CreatureSculpt.Part was,
                                    CreatureSculpt.Part now) {
        float[] d = new float[3];
        for (int a = 0; a < 3; a++) {
            float pLo = lo(p, a), pHi = hi(p, a), oLo = lo(was, a), oHi = hi(was, a);
            // the middle of where they meet; a part that never touched it is clamped onto it
            float iLo = Math.max(pLo, oLo), iHi = Math.min(pHi, oHi);
            float anchor = iLo <= iHi ? (iLo + iHi) / 2f
                    : Math.max(oLo, Math.min(oHi, (pLo + pHi) / 2f));
            float span = oHi - oLo;
            float t = span <= 0f ? 0.5f : (anchor - oLo) / span;
            d[a] = lo(now, a) + t * (hi(now, a) - lo(now, a)) - anchor;
        }
        return d;
    }

    /** How much two boxes share, touching counted as a sliver; 0 when they are apart. */
    private static double contact(CreatureSculpt.Part a, CreatureSculpt.Part b) {
        final float eps = 0.01f;
        double v = 1;
        for (int axis = 0; axis < 3; axis++) {
            float s = Math.min(hi(a, axis), hi(b, axis)) - Math.max(lo(a, axis), lo(b, axis));
            if (s < -eps) {
                return 0;
            }
            v *= Math.max(s, 0f) + 2 * eps;
        }
        return v;
    }

    private static double volume(CreatureSculpt.Part p) {
        return (double) (hi(p, 0) - lo(p, 0)) * (hi(p, 1) - lo(p, 1)) * (hi(p, 2) - lo(p, 2));
    }

    private static float lo(CreatureSculpt.Part p, int a) {
        return Math.min(p.from()[a], p.to()[a]);
    }

    private static float hi(CreatureSculpt.Part p, int a) {
        return Math.max(p.from()[a], p.to()[a]);
    }

    static CreatureSculpt.Part moved(CreatureSculpt.Part p, float[] d) {
        float[] pivot = p.pivot() == null ? null
                : new float[]{p.pivot()[0] + d[0], p.pivot()[1] + d[1], p.pivot()[2] + d[2]};
        return new CreatureSculpt.Part(p.name(), p.parent(), pivot,
                new float[]{p.from()[0] + d[0], p.from()[1] + d[1], p.from()[2] + d[2]},
                new float[]{p.to()[0] + d[0], p.to()[1] + d[1], p.to()[2] + d[2]},
                p.colour(), p.rotation(), p.inflate(), p.pattern(), p.patternColour());
    }

    /**
     * <b>A replaced part stays hung where it was, unless the replacement says otherwise.</b>
     *
     * <p>"Make his hair brown" arrives as {@code {"name":"hair","from":…,"to":…,"colour":"brown"}}
     * — the box and the colour, because that is what changed. Swapping it in wholesale dropped the
     * base part's {@code parent}, so the hair became a ROOT: correct standing still and left behind
     * the first time the head turned to look at the child, the back of it ending up over the face.
     * The same edit detaches a claw from its arm or a horn from a head. An edit describes what
     * CHANGED, so a parent it does not mention is a parent it did not change.
     */
    static CreatureSculpt.Part keepingItsPlace(CreatureSculpt.Part replacement,
                                               CreatureSculpt.Part was) {
        if (replacement.parent() != null || was.parent() == null) {
            return replacement;
        }
        return new CreatureSculpt.Part(replacement.name(), was.parent(), replacement.pivot(),
                replacement.from(), replacement.to(), replacement.colour(), replacement.rotation(),
                replacement.inflate(), replacement.pattern(), replacement.patternColour());
    }

    /**
     * Loose name matching, the same rule the rest of the sculptor uses: a query is a prefix or a
     * contained word. "spike" removes every spike; "shell" removes "shellRimTop" with it, which is
     * what someone asking for a Spikor without a shell means.
     */
    static boolean matchesAny(String boneLower, Set<String> queries) {
        if (queries == null || queries.isEmpty()) {
            return false;
        }
        for (String q : queries) {
            if (q != null && !q.isBlank() && boneLower.contains(q.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static Integer colourFor(String boneLower, Map<String, Integer> recolour) {
        if (recolour == null || recolour.isEmpty()) {
            return null;
        }
        // Longest query wins, so "shellRim" beats "shell" when both are asked for.
        String best = null;
        for (String q : recolour.keySet()) {
            if (q != null && !q.isBlank() && boneLower.contains(q.toLowerCase(Locale.ROOT))
                    && (best == null || q.length() > best.length())) {
                best = q;
            }
        }
        return best == null ? null : recolour.get(best);
    }

    private static CreatureSculpt.Part recoloured(CreatureSculpt.Part p, int colour) {
        return new CreatureSculpt.Part(p.name(), p.parent(), p.pivot(), p.from(), p.to(), colour,
                p.rotation(), p.inflate(), p.pattern(), p.patternColour());
    }
}
