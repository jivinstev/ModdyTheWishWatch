package com.moddingfromamod.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * <b>Precomputed morphology.</b> One line from the agent becomes a dozen tilted boxes.
 *
 * <h2>Why this is the precomputation that works</h2>
 *
 * <p>The obvious objection to precomputing anything here is that the space of creatures a child can
 * imagine is unbounded, so no library could cover it. That is true, and it is answering the wrong
 * question. <b>You do not precompute creatures. You precompute body parts, and that space is
 * small</b> — horns, spikes, claws, wings, shells, tails, fins, crests. Thirty kits cover most of
 * what gets asked for, across creatures that have nothing else in common.
 *
 * <h2>Why it is the only fidelity change that makes wishes FASTER</h2>
 *
 * <p>Every other idea here adds tokens to a call already sitting on the critical path of a 119-352
 * second wish. This one removes them:
 *
 * <pre>
 *   {kit: "spike_row", on: "shell", count: 7, size: "L", colour: "bone"}   ~14 tokens in
 *      -> 7 tapered boxes, each tilted along the spine                     ~500 tokens out
 * </pre>
 *
 * <p>The agent could never afford to hand-write seven rotated tapered boxes for one feature. It can
 * afford this. See {@code docs/CREATURE_FIDELITY.md}.
 *
 * <h2>The risk this carries, stated up front</h2>
 *
 * <p>This repo's most-repeated failure is a capability that was built, wired, committed, believed —
 * and never used, seven times over. A kit library the agent does not reach for is worth exactly
 * zero, and a test that asserts the kits <i>exist</i> would pass anyway. So the gate that matters is
 * <b>adoption</b> ({@code SculptKits#usedBy}), not existence.
 *
 * <p>The second risk is subtler: a parametric horn is one person's guess at a horn, so a bad kit
 * regresses every creature that uses it, <i>uniformly</i> — which is harder to notice than a
 * one-off. That is what the likeness graders are for.
 *
 * <p>Pure — no Minecraft — so every kit's geometry is unit-tested without a game.
 */
public final class SculptKits {

    private SculptKits() {
    }

    /**
     * The vocabulary. Bounded: this arrives from an agent, and an open name is a channel.
     *
     * <p><b>There is deliberately no {@code face} kit.</b> One was built and withdrawn:
     * mood is real, but a nine-box prefab makes every face in the game a member of a six-item
     * family and takes the most expressive part of a creature away from the thing that knows what
     * the character looks like. Correct-by-construction is right for questions with ONE right
     * answer — which way a creature faces, a box that draws nothing. A face has no right answer, so
     * the rung below applies: teach the vocabulary and let the agent build its own. The mood-to-shape
     * research survives as guidance in {@code lessons/creature.md}; {@link SculptFace} is kept for
     * the table it encodes and is no longer reachable as a kit.
     */
    public static final Set<String> NAMES = Set.of(
            "spike_row", "horn", "claw", "wing", "shell", "tail", "fin", "crest",
            // F1: legs from one point (an earlier issue's insects and spiders) and the head kits.
            "leg_row", "ears", "snout", "beak", "trunk", "tusk", "mane");

    /**
     * <b>Styles</b> (F1): one kit, several looks, chosen by {@code style}. The first of each list is
     * what a kit with no style builds (the pre-F1 shape, unchanged).
     */
    public static final java.util.Map<String, List<String>> STYLES = java.util.Map.of(
            "horn", List.of("curved", "antler", "unicorn", "ram"),
            "wing", List.of("swept", "feather", "bat", "butterfly", "dragon"),
            "tail", List.of("tapered", "fluffy", "fish", "peacock", "lion"),
            "ears", List.of("pointy", "round", "floppy", "long"));

    /** A style this kit has, or its first (default) style for anything else. */
    public static String style(String kit, String raw) {
        List<String> styles = STYLES.get(kit);
        if (styles == null) {
            return "";
        }
        String w = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        w = switch (w) {
            case "antlers", "deer", "moose" -> "antler";
            case "unicorn_horn", "single", "one" -> "unicorn";
            case "ram_horns", "rams", "curly", "curl", "goat" -> "ram";
            case "feathered", "feathers", "bird", "angel" -> "feather";
            case "bat_wings", "leathery" -> "bat";
            case "butterfly_wings", "fairy", "moth" -> "butterfly";
            case "dragon_wings", "wyvern" -> "dragon";
            case "fox", "squirrel", "bushy", "fluffy_tail" -> "fluffy";
            case "fish_tail", "mermaid", "fluke" -> "fish";
            case "peacock_tail", "fan", "plume" -> "peacock";
            case "lion_tail", "tuft", "tufted", "cow" -> "lion";
            case "cat", "fox_ears", "pointed" -> "pointy";
            case "bear", "mouse", "rounded" -> "round";
            case "dog", "puppy", "droopy", "floppy_ears" -> "floppy";
            case "bunny", "rabbit", "tall" -> "long";
            default -> w;
        };
        return styles.contains(w) ? w : styles.get(0);
    }

    /**
     * The kits an arm may actually use.
     *
     * <p>Currently every one of them. The A/B seam that lived here existed for the face kit, which
     * has been withdrawn; a flag guarding nothing is a flag that will be believed by the next
     * person to read it.
     */
    public static Set<String> available() {
        return NAMES;
    }

    /** One kit may not flood the part budget; a creature is more than its spikes. */
    public static final int MAX_PER_KIT = 12;

    /**
     * <b>Boxes</b> one kit may emit — not the same thing as its {@code count}.
     *
     * <p>A spike is two boxes (base + tip), so {@code count: 9} quietly became <b>18 parts</b> from a
     * single kit. Capping the count and not the output is how a 26-part lizard ended up with 30 of
     * its 56 parts prefabricated, which the human called "completely incoherent". The cap belongs on
     * what is produced.
     */
    public static final int MAX_BOXES_PER_KIT = 12;

    /** Size buckets, in sixteenths — the same S/M/L a child says out loud. */
    private static float sizeScale(String size) {
        return switch (size == null ? "" : size.trim().toUpperCase(Locale.ROOT)) {
            case "S", "SMALL" -> 0.6f;
            case "L", "LARGE", "BIG" -> 1.6f;
            case "XL", "HUGE", "GIANT" -> 2.4f;
            default -> 1.0f;
        };
    }

    /** Where a kit hangs off its anchor part. */
    private enum Along { BACK, TOP, FRONT, SIDES, TAIL }

    private static Along along(String raw) {
        return switch (raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)) {
            case "top", "head", "up" -> Along.TOP;
            case "front", "belly", "chest" -> Along.FRONT;
            case "side", "sides" -> Along.SIDES;
            case "tail" -> Along.TAIL;
            default -> Along.BACK;
        };
    }

    /** Anything unrecognised degrades to "" and the kit is skipped, never crashing the sculpt. */
    public static String sanitize(String raw) {
        return resolve(raw, available());
    }

    /**
     * The same near-miss resolution against the <b>whole</b> vocabulary, ignoring the A/B seam.
     *
     * <p>For anything PERSISTED. A reference is cached forever and an A/B flag is one afternoon's
     * state; filtering a stored reference by today's flag would bake that afternoon into Spikor
     * permanently, and turning the flag back on would not bring the feature back. The seam belongs
     * at {@link #expand}, where the kit is actually built, and it is there.
     */
    public static String sanitizeAnyKnown(String raw) {
        return resolve(raw, NAMES);
    }

    private static String resolve(String raw, Set<String> allowed) {
        if (raw == null) {
            return "";
        }
        String n = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        switch (n) {
            case "spikes", "spike", "spines", "spine_row", "spine" -> n = "spike_row";
            case "horns" -> n = "horn";
            case "claws", "talon", "talons" -> n = "claw";
            case "wings" -> n = "wing";
            case "carapace", "back_shell" -> n = "shell";
            case "fins" -> n = "fin";
            case "crown", "frill" -> n = "crest";
            case "antler", "antlers", "unicorn_horn", "ram_horn", "ram_horns" -> n = "horn";
            case "feather_wings", "bat_wings", "butterfly_wings", "dragon_wings" -> n = "wing";
            case "fluffy_tail", "fish_tail", "peacock_tail", "lion_tail" -> n = "tail";
            case "legs", "leg", "leg_rows", "insect_legs", "spider_legs" -> n = "leg_row";
            case "ear" -> n = "ears";
            case "nose", "muzzle" -> n = "snout";
            case "bill" -> n = "beak";
            case "tusks" -> n = "tusk";
            default -> { }
        }
        return allowed.contains(n) ? n : "";
    }

    /**
     * The marker that makes a kit-built bone identifiable on disk, forever.
     *
     * <p>It has to be a prefix a hand-sculpt would never write. The first cut simply asked whether a
     * bone's name started with a kit name, and scoring the historical corpus with it reported
     * <b>355 kit-built parts in a corpus recorded months before kits existed</b> — because children
     * ask for horns and tails, so the agent had always been hand-naming bones {@code horn},
     * {@code shell}, {@code tail}. That is the adoption number lying in the <i>flattering</i>
     * direction, which is the exact failure exists to prevent: it would have reported a kit as
     * widely adopted on the day it shipped and nobody would have checked again.
     */
    public static final String MARK = "kit_";

    /**
     * How many of a creature's parts came from a kit.
     *
     * <p>The adoption number — the only thing that distinguishes a kit that is <i>used</i>
     * from a kit that merely <i>exists</i>, which in this project is the difference that has been
     * got wrong seven times. Reads off the bone names, so it works on a sculpt loaded back from
     * disk with no side-channel.
     */
    public static int usedBy(List<CreatureSculpt.Part> parts) {
        if (parts == null) {
            return 0;
        }
        int n = 0;
        for (CreatureSculpt.Part p : parts) {
            if (p.name().startsWith(MARK)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Expand every kit in the list against the parts already sculpted.
     *
     * <p>Skips anything unusable rather than failing the creature — the same contract
     * {@link CreatureSculpt#parseParts} keeps, and for the same reason: one malformed entry must not
     * cost a child the whole thing.
     *
     * @param kits     the agent's {@code kits} array
     * @param existing the parts already parsed, so {@code on: "body"} can find its anchor
     * @return new parts to append
     */
    public static List<CreatureSculpt.Part> expand(
            JsonArray kits, List<CreatureSculpt.Part> existing) {
        List<CreatureSculpt.Part> out = new ArrayList<>();
        if (kits == null) {
            return out;
        }
        // WHICH WAY THIS CREATURE IS BUILT. Every kit below places "back" at the anchor's
        // minimum Z, which is only right for a creature built face-at-+Z -- and the agent is not
        // consistent: across the recorded corpus roughly 14 are built that way and 22 the other.
        // On the others a tail kit landed on the belly.
        //
        // Rather than thread a sign through eight kits, mirror the ANCHOR before building and
        // mirror the result back. The kit code keeps its one assumption, both conventions work, and
        // the mirroring reuses the transform that is already tested (handedness and all).
        boolean mirrored = SculptOrientation.frontSign(existing) < 0;
        int index = 0;
        for (JsonElement el : kits) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            String kit = sanitize(str(o, "kit", str(o, "name", "")));
            if (kit.isEmpty()) {
                continue;
            }
            CreatureSculpt.Part anchor = find(existing, str(o, "on", ""));
            float[] box = anchorBox(anchor);
            if (box == null) {
                continue;   // nothing to hang it off, and a kit floating at the origin is a bug
            }
            if (mirrored) {
                float minZ = box[2];
                box[2] = -box[5];
                box[5] = -minZ;
            }
            String parent = anchor != null ? anchor.name() : null;
            float scale = sizeScale(str(o, "size", "M"));
            int colour = com.moddingfromamod.species.Species.parseColour(
                    str(o, "colour", str(o, "color", "")));
            int count = clampCount(intOf(o, "count", defaultCount(kit)));
            String pattern = SculptPattern.sanitize(str(o, "pattern", ""));
            int patternColour = com.moddingfromamod.species.Species.parseColour(
                    str(o, "pattern_colour", str(o, "pattern_color", "")));
            String tag = kit + (index == 0 ? "" : String.valueOf(index));
            index++;
            // A style may come in its own field or folded into the kit's name ("antlers", "bat_wings").
            String style = style(kit, str(o, "style", styleFromName(str(o, "kit", str(o, "name", "")))));

            List<CreatureSculpt.Part> made = switch (kit) {
                case "spike_row" -> spikeRow(tag, parent, box, along(str(o, "along", "back")),
                        count, scale, colour, pattern, patternColour);
                case "horn" -> switch (style) {
                    case "antler" -> antlers(tag, parent, box, scale, colour, pattern, patternColour);
                    case "unicorn" -> unicornHorn(tag, parent, box, scale, colour, pattern, patternColour);
                    case "ram" -> ramHorns(tag, parent, box, scale, colour, pattern, patternColour);
                    default -> horns(tag, parent, box, count, scale, colour, pattern, patternColour);
                };
                case "claw" -> claws(tag, parent, box, count, scale, colour, pattern, patternColour);
                case "wing" -> switch (style) {
                    case "feather" -> featherWings(tag, parent, box, scale, colour, pattern, patternColour);
                    case "bat" -> batWings(tag, parent, box, scale, 1f, colour, pattern, patternColour);
                    case "dragon" -> batWings(tag, parent, box, scale, 1.5f, colour, pattern, patternColour);
                    case "butterfly" -> butterflyWings(tag, parent, box, scale, colour, pattern, patternColour);
                    default -> wings(tag, parent, box, scale, colour, pattern, patternColour);
                };
                case "shell" -> shell(tag, parent, box, scale, colour, pattern, patternColour);
                case "tail" -> switch (style) {
                    case "fluffy" -> fluffyTail(tag, parent, box, scale, colour, pattern, patternColour);
                    case "fish" -> fishTail(tag, parent, box, scale, colour, pattern, patternColour);
                    case "peacock" -> peacockTail(tag, parent, box, count, scale, colour, pattern, patternColour);
                    case "lion" -> lionTail(tag, parent, box, scale, colour, pattern, patternColour);
                    default -> tail(tag, parent, box, count, scale, colour, pattern, patternColour);
                };
                case "leg_row" -> legRow(tag, parent, box, count, scale, colour, pattern, patternColour);
                case "ears" -> ears(tag, parent, box, style, scale, colour, pattern, patternColour);
                case "snout" -> snout(tag, parent, box, scale, colour, pattern, patternColour);
                case "beak" -> beak(tag, parent, box, scale, colour, pattern, patternColour);
                case "trunk" -> trunk(tag, parent, box, count, scale, colour, pattern, patternColour);
                case "tusk" -> tusks(tag, parent, box, scale, colour, pattern, patternColour);
                case "mane" -> mane(tag, parent, box, scale, colour, pattern, patternColour);
                case "fin" -> fins(tag, parent, box, count, scale, colour, pattern, patternColour);
                case "crest" -> crest(tag, parent, box, count, scale, colour, pattern, patternColour);
                // MARK here, because SculptFace builds its own boxes rather than going through
                // part() -- without it a face is invisible to the adoption count.
                case "face" -> SculptFace.build(MARK + tag, parent, box,
                        str(o, "mood", str(o, "expression", "happy")),
                        colour,
                        com.moddingfromamod.species.Species.parseColour(
                                str(o, "eye_colour", str(o, "eye_color", "white"))));
                default -> List.<CreatureSculpt.Part>of();
            };
            // A face is a fixed, complete set of small boxes — clipping it at the generic per-kit
            // cap would lop the mouth off and leave a creature with half an expression, which is
            // worse than no face at all. Every other kit is a repeating row and clips harmlessly.
            // A spider's eight legs are sixteen boxes: a row of legs is anatomy, clipped it is a cripple.
            int cap = "face".equals(kit) ? made.size() : "leg_row".equals(kit) ? 16 : MAX_BOXES_PER_KIT;
            List<CreatureSculpt.Part> kept = made.size() > cap ? made.subList(0, cap) : made;
            if (mirrored) {
                List<CreatureSculpt.Part> back = new ArrayList<>(kept.size());
                for (CreatureSculpt.Part part : kept) {
                    back.add(SculptOrientation.mirrorZ(part));
                }
                kept = back;
            }
            out.addAll(kept);
        }
        return out;
    }

    // ===================== the kits =====================

    /**
     * A row of tapered spikes down the spine — the single most-requested thing this system could
     * not build. Each spike is two stacked boxes (a wide base and a narrow tip) tilted backward, and
     * the tilt increases along the row so the row reads as a ridge rather than a fence.
     */
    private static List<CreatureSculpt.Part> spikeRow(String tag, String parent, float[] b,
            Along along, int count, float s, int colour, String pattern, int patternColour) {
        List<CreatureSculpt.Part> out = new ArrayList<>();
        float w = Math.max(1.5f, 2.0f * s);
        float len = Math.max(2.5f, 5.0f * s);

        // WHICH WAY THE SPINE RUNS IS A PROPERTY OF THE BODY, NOT A CONSTANT.
        //
        // This originally ran a "top" row across the body's WIDTH and a "back" row DOWN the rear
        // face — both correct for an upright biped like Spikor, and both wrong for anything built
        // long. Asked for "a spiky orange lizard", the agent got nine large spikes in a line across
        // the shoulders, and the human called the result "completely incoherent".
        //
        // A long-bodied creature's spine runs front-to-back, so the row follows the anchor's
        // dominant horizontal axis. Correct by construction, which is better than a rule the agent
        // has to remember: +Z is the FRONT here (verified against real sculpts — every snout, nose
        // and eye sits at positive z; every shell, tail and back-of-hair at negative).
        boolean longBodied = (b[5] - b[2]) > (b[3] - b[0]) * 1.15f;

        for (int i = 0; i < count; i++) {
            float t = count == 1 ? 0.5f : (float) i / (count - 1);
            float cx;
            float cy;
            float cz;
            float[] rot;
            switch (along) {
                case SIDES -> {
                    cx = i % 2 == 0 ? b[0] : b[3];
                    cy = lerp(b[1], b[4], 0.3f + 0.4f * t);
                    cz = lerp(b[2], b[5], t);
                    rot = new float[]{0, 0, i % 2 == 0 ? 55 : -55};
                }
                case TOP -> {
                    // Along the spine on a long body; across the shoulders on an upright one.
                    cx = longBodied ? lerp(b[0], b[3], 0.5f) : lerp(b[0], b[3], t);
                    cy = b[4];
                    cz = longBodied ? lerp(b[5], b[2], t) : lerp(b[2], b[5], 0.5f);
                    // Rake backwards along the run, so the row reads as a ridge and not a fence.
                    rot = new float[]{longBodied ? -20f - 20f * t : -15f + 30f * t, 0, 0};
                }
                case FRONT -> {
                    cx = lerp(b[0], b[3], t);
                    cy = lerp(b[1], b[4], 0.35f + 0.4f * t);
                    cz = b[5];
                    rot = new float[]{60, 0, 0};
                }
                default -> {
                    // BACK / TAIL. On a long body the spine runs backwards along Z at roughly
                    // shoulder height; on an upright one it descends the rear face.
                    cx = lerp(b[0], b[3], 0.5f);
                    if (longBodied) {
                        cy = lerp(b[4], b[1], 0.15f);
                        cz = lerp(b[5], b[2], 0.15f + 0.8f * t);
                        rot = new float[]{-25f - 20f * t, 0, 0};
                    } else {
                        cy = lerp(b[4], b[1], t) - len * 0.2f;
                        cz = b[2];
                        rot = new float[]{-35f - 25f * t, 0, 0};
                    }
                }
            }
            // Base.
            out.add(part(tag + (i * 2), parent,
                    cx - w, cy, cz - w, cx + w, cy + len * 0.6f, cz + w * 0.6f,
                    colour, rot, pattern, patternColour));
            // Tip: narrower, and tilted a little further, which is what makes it taper.
            float tw = w * 0.5f;
            out.add(part(tag + (i * 2 + 1), parent,
                    cx - tw, cy + len * 0.5f, cz - tw, cx + tw, cy + len, cz + tw * 0.6f,
                    colour, new float[]{rot[0] * 1.25f, rot[1], rot[2] * 1.25f},
                    pattern, patternColour));
        }
        return out;
    }

    /** A curved horn per side, built from three segments each tilted a bit further round. */
    private static List<CreatureSculpt.Part> horns(String tag, String parent, float[] b,
            int count, float s, int colour, String pattern, int patternColour) {
        List<CreatureSculpt.Part> out = new ArrayList<>();
        int pairs = Math.max(1, Math.min(count, 4) / 2 + (count % 2));
        float seg = Math.max(2f, 3.5f * s);
        for (int side = 0; side < Math.min(2, Math.max(1, count)); side++) {
            float dir = side == 0 ? -1f : 1f;
            float x = lerp(b[0], b[3], side == 0 ? 0.22f : 0.78f);
            float y = b[4] - seg * 0.2f;
            float z = lerp(b[2], b[5], 0.45f);
            for (int i = 0; i < Math.min(3, pairs + 2); i++) {
                float t = i / 3f;
                float half = Math.max(0.8f, (1.6f - 0.4f * i) * s);
                out.add(part(tag + (side * 3 + i), parent,
                        x - half, y + seg * i * 0.75f, z - half,
                        x + half, y + seg * (i + 1) * 0.75f, z + half,
                        colour,
                        new float[]{-10f - 15f * i, 0, dir * (12f + 22f * t)},
                        pattern, patternColour));
            }
        }
        return out;
    }

    /** Splayed claws on the front of a limb. */
    private static List<CreatureSculpt.Part> claws(String tag, String parent, float[] b,
            int count, float s, int colour, String pattern, int patternColour) {
        List<CreatureSculpt.Part> out = new ArrayList<>();
        int n = Math.max(1, Math.min(count, 5));
        float len = Math.max(1.5f, 3f * s);
        float half = Math.max(0.6f, 0.9f * s);
        for (int i = 0; i < n; i++) {
            float t = n == 1 ? 0.5f : (float) i / (n - 1);
            float x = lerp(b[0], b[3], 0.15f + 0.7f * t);
            float y = b[1];
            float z = b[5];
            out.add(part(tag + i, parent,
                    x - half, y - len * 0.2f, z - half, x + half, y + len * 0.5f, z + len,
                    colour, new float[]{55, 0, (t - 0.5f) * 40f}, pattern, patternColour));
        }
        return out;
    }

    /** A swept wing per side: three panels, each raked back a little further. */
    private static List<CreatureSculpt.Part> wings(String tag, String parent, float[] b,
            float s, int colour, String pattern, int patternColour) {
        List<CreatureSculpt.Part> out = new ArrayList<>();
        float span = Math.max(6f, 12f * s);
        float chord = Math.max(4f, 9f * s);
        for (int side = 0; side < 2; side++) {
            float dir = side == 0 ? -1f : 1f;
            float x = side == 0 ? b[0] : b[3];
            float y = lerp(b[1], b[4], 0.62f);
            float z = lerp(b[2], b[5], 0.45f);
            for (int i = 0; i < 3; i++) {
                float t = i / 2f;
                float x0 = x + dir * span * (t * 0.66f);
                float x1 = x + dir * span * ((t + 0.5f) * 0.66f);
                out.add(part(tag + (side * 3 + i), parent,
                        Math.min(x0, x1), y - chord * (0.5f - 0.12f * i), z - 1f,
                        Math.max(x0, x1), y + chord * (0.35f - 0.08f * i), z + 1f,
                        colour,
                        new float[]{0, dir * (10f + 18f * t), dir * (-12f - 10f * t)},
                        pattern, patternColour));
            }
        }
        return out;
    }

    /** A domed shell across the back — concentric inflated rings, so the edge is not a cliff. */
    private static List<CreatureSculpt.Part> shell(String tag, String parent, float[] b,
            float s, int colour, String pattern, int patternColour) {
        List<CreatureSculpt.Part> out = new ArrayList<>();
        float cx = lerp(b[0], b[3], 0.5f);
        float cy = lerp(b[1], b[4], 0.55f);
        float cz = b[2];
        int rings = 3;
        for (int i = 0; i < rings; i++) {
            float t = (float) i / rings;
            float halfW = Math.max(2f, (b[3] - b[0]) * 0.5f * s * (1f - 0.22f * i));
            float halfH = Math.max(2f, (b[4] - b[1]) * 0.42f * s * (1f - 0.22f * i));
            float depth = Math.max(1.5f, 3f * s * (1f - 0.2f * i));
            out.add(new CreatureSculpt.Part(tag + i, parent,
                    new float[]{cx, cy, cz},
                    new float[]{cx - halfW, cy - halfH, cz - depth * (i + 1)},
                    new float[]{cx + halfW, cy + halfH, cz - depth * i},
                    colour, new float[]{-6f * t, 0, 0}, 0.5f, pattern, patternColour));
        }
        return out;
    }

    /** A tapered, drooping tail out of the back. */
    private static List<CreatureSculpt.Part> tail(String tag, String parent, float[] b,
            int count, float s, int colour, String pattern, int patternColour) {
        List<CreatureSculpt.Part> out = new ArrayList<>();
        int n = Math.max(2, Math.min(count, 8));
        float cx = lerp(b[0], b[3], 0.5f);
        float y = lerp(b[1], b[4], 0.35f);
        float z = b[2];
        float seg = Math.max(2f, 4f * s);
        for (int i = 0; i < n; i++) {
            float t = (float) i / n;
            float half = Math.max(0.8f, (2.6f - 1.9f * t) * s);
            out.add(part(tag + i, parent,
                    cx - half, y - seg * t * 0.9f - half, z - seg * (i + 1),
                    cx + half, y - seg * t * 0.9f + half, z - seg * i,
                    colour, new float[]{12f + 9f * i, 0, 0}, pattern, patternColour));
        }
        return out;
    }

    /** Fins along the sides — flat, angled panels. */
    private static List<CreatureSculpt.Part> fins(String tag, String parent, float[] b,
            int count, float s, int colour, String pattern, int patternColour) {
        List<CreatureSculpt.Part> out = new ArrayList<>();
        int n = Math.max(2, Math.min(count, 6));
        float len = Math.max(3f, 6f * s);
        for (int i = 0; i < n; i++) {
            boolean left = i % 2 == 0;
            float t = (float) (i / 2) / Math.max(1, (n - 1) / 2);
            float x = left ? b[0] + 0.8f : b[3] - 0.8f;   // rooted inside the body: turned, it stays on (KitLint)
            float y = lerp(b[1], b[4], 0.4f);
            float z = lerp(b[2], b[5], 0.25f + 0.5f * t);
            float dir = left ? -1f : 1f;
            out.add(part(tag + i, parent,
                    Math.min(x, x + dir * len), y - 1f, z - len * 0.35f,
                    Math.max(x, x + dir * len), y + 1f, z + len * 0.35f,
                    colour, new float[]{0, 0, dir * -35f}, pattern, patternColour));
        }
        return out;
    }

    /** A fan of plates on top of the head. */
    private static List<CreatureSculpt.Part> crest(String tag, String parent, float[] b,
            int count, float s, int colour, String pattern, int patternColour) {
        List<CreatureSculpt.Part> out = new ArrayList<>();
        int n = Math.max(3, Math.min(count, 9));
        float h = Math.max(3f, 6f * s);
        for (int i = 0; i < n; i++) {
            float t = n == 1 ? 0.5f : (float) i / (n - 1);
            float x = lerp(b[0], b[3], 0.1f + 0.8f * t);
            float y = b[4];
            float z = lerp(b[2], b[5], 0.5f);
            // Tallest in the middle, so the fan reads as a fan.
            float tall = h * (0.55f + 0.45f * (1f - Math.abs(t - 0.5f) * 2f));
            out.add(part(tag + i, parent,
                    x - 1f, y - 1f, z - 1f, x + 1f, y + tall, z + 1f,
                    colour, new float[]{-12f, 0, (t - 0.5f) * 46f}, pattern, patternColour));
        }
        return out;
    }


    /**
     * <b>One link of a chain</b> (F1 grading: "the parts were disjoint"): a box from point {@code a} to point {@code b},
     * {@code hx} by {@code hz} thick, standing on whichever end keeps its tilt inside the sculpt's ±90°, turned about
     * that end so it ends exactly at the other, and overlapping both joints by its own thickness. Neighbouring links
     * built from shared points therefore always meet, however the chain bends. Unparented (on the anchor).
     */
    private static CreatureSculpt.Part segment(String name, String parent, float[] a, float[] b, float hx, float hz,
            float inflate, int colour, String pattern, int patternColour) {
        float[] d = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
        float len = (float) Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        if (len < 0.01f) {
            d = new float[]{0, 1, 0};
            len = 1f;
        }
        float[] base = a;
        float[] u = {d[0] / len, d[1] / len, d[2] / len};
        if (u[1] < 0) {   // pointing down: stand on b and point up at a, so the tilt stays inside ±90°
            base = b;
            u = new float[]{-u[0], -u[1], -u[2]};
        }
        double beta = Math.asin(Math.max(-1, Math.min(1, u[2])));
        // The game turns Z the other way from the textbook (KitLint.rotate says why): +z leans the top toward +x.
        double gamma = Math.atan2(u[0], u[1]);
        float over = Math.min(hx, hz);
        float[] from = {base[0] - hx, base[1] - over, base[2] - hz};
        float[] to = {base[0] + hx, base[1] + len + over, base[2] + hz};
        return new CreatureSculpt.Part(CreatureSculpt.sanitize(MARK + name), parent, base.clone(), from, to, colour,
                CreatureSculpt.clampRotation(new float[]{(float) Math.toDegrees(beta), 0f, (float) Math.toDegrees(gamma)}),
                inflate, pattern, patternColour);
    }

    /** A chain of links through {@code pts}, thickness tapering from {@code h0} to {@code h1}. */
    private static void chain(List<CreatureSculpt.Part> out, String tag, int first, String parent, float[][] pts,
            float h0, float h1, float inflate, int colour, String pattern, int patternColour) {
        for (int i = 0; i + 1 < pts.length; i++) {
            float t = pts.length <= 2 ? 0f : (float) i / (pts.length - 2);
            float h = h0 + (h1 - h0) * t;
            out.add(segment(tag + (first + i), parent, pts[i], pts[i + 1], h, h, inflate, colour, pattern, patternColour));
        }
    }

    // ===================== F1: styles and the head kits =====================

    /** The style a kit's own name carries ("antlers" → antler), or "". */
    private static String styleFromName(String raw) {
        String n = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return switch (n) {
            case "antler", "antlers" -> "antler";
            case "unicorn_horn" -> "unicorn";
            case "ram_horn", "ram_horns" -> "ram";
            case "feather_wings" -> "feather";
            case "bat_wings" -> "bat";
            case "butterfly_wings" -> "butterfly";
            case "dragon_wings" -> "dragon";
            case "fluffy_tail" -> "fluffy";
            case "fish_tail" -> "fish";
            case "peacock_tail" -> "peacock";
            case "lion_tail" -> "lion";
            default -> "";
        };
    }

    /** A box with rounded edges ({@code inflate}), for the soft kits: a fluffy tail, a mane, a lion's tuft. */
    private static CreatureSculpt.Part soft(String name, String parent,
            float x0, float y0, float z0, float x1, float y1, float z1,
            int colour, float[] rotation, float inflate, String pattern, int patternColour) {
        CreatureSculpt.Part p = part(name, parent, x0, y0, z0, x1, y1, z1, colour, rotation, pattern, patternColour);
        return new CreatureSculpt.Part(p.name(), p.parent(), p.pivot(), p.from(), p.to(), p.colour(),
                p.rotation(), inflate, p.pattern(), p.patternColour());
    }

    /** Antlers: per side a beam that rises up and out in links, and two tines branching forward off it. */
    private static List<CreatureSculpt.Part> antlers(String tag, String parent, float[] b,
            float s, int colour, String pattern, int patternColour) {
        List<CreatureSculpt.Part> out = new ArrayList<>();
        float seg = Math.max(2.5f, 4f * s);
        float half = Math.max(0.6f, 0.8f * s);
        float cx = (b[0] + b[3]) / 2f;
        for (int side = 0; side < 2; side++) {
            float dir = side == 0 ? -1f : 1f;
            float x = cx + dir * (b[3] - b[0]) * 0.25f;
            float z = lerp(b[2], b[5], 0.4f);
            float y = b[4] - half;
            float[][] beam = {{x, y, z}, {x + dir * seg * 0.5f, y + seg, z - seg * 0.15f},
                    {x + dir * seg * 1.1f, y + seg * 1.9f, z - seg * 0.3f}, {x + dir * seg * 1.5f, y + seg * 2.7f, z - seg * 0.2f}};
            chain(out, tag, side * 5, parent, beam, half, half * 0.8f, 0f, colour, pattern, patternColour);
            for (int k = 0; k < 2; k++) {
                float[] at = beam[k + 1];
                out.add(segment(tag + (side * 5 + 3 + k), parent, at,
                        new float[]{at[0] + dir * seg * 0.15f, at[1] + seg * 0.75f, at[2] + seg * 0.6f},
                        half * 0.75f, half * 0.75f, 0f, colour, pattern, patternColour));
            }
        }
        return out;
    }

    /** One straight, tapering horn in the middle of the forehead, raked forward. */
    private static List<CreatureSculpt.Part> unicornHorn(String tag, String parent, float[] b,
            float s, int colour, String pattern, int patternColour) {
        List<CreatureSculpt.Part> out = new ArrayList<>();
        float seg = Math.max(2.5f, 4f * s);
        float x = lerp(b[0], b[3], 0.5f);
        float z = lerp(b[2], b[5], 0.75f);
        for (int i = 0; i < 4; i++) {
            float half = Math.max(0.5f, (1.6f - 0.35f * i) * s);
            out.add(part(tag + i, parent, x - half, b[4] + seg * i * 0.85f, z - half + i * seg * 0.25f,
                    x + half, b[4] + seg * (i + 1) * 0.85f, z + half + i * seg * 0.25f,
                    colour, new float[]{25f, 0, 0}, pattern, patternColour));
        }
        return out;
    }

    /**
     * Ram horns: per side a thick horn that leaves the top of the head and curls back, down and forward round the
     * ear, in links that meet (the first cut's boxes each turned on their own base, and the curl read as two lumps).
     */
    private static List<CreatureSculpt.Part> ramHorns(String tag, String parent, float[] b,
            float s, int colour, String pattern, int patternColour) {
        List<CreatureSculpt.Part> out = new ArrayList<>();
        float half = Math.max(1.1f, 1.4f * s);
        float r = Math.max(2.4f, (b[4] - b[1]) * 0.42f);
        float cx = (b[0] + b[3]) / 2f;
        float zc = lerp(b[2], b[5], 0.4f);
        double[] angles = {100, 150, 200, 250, 300, 340};   // round the side of the head, from the top, backwards then forwards
        for (int side = 0; side < 2; side++) {
            float dir = side == 0 ? -1f : 1f;
            float halfW = (b[3] - b[0]) / 2f;
            float yc = b[4] - r * 0.85f;
            float[][] pts = new float[angles.length][];
            for (int i = 0; i < angles.length; i++) {
                double a = Math.toRadians(angles[i]);
                float out1 = halfW + half * (0.2f + 0.5f * i / (angles.length - 1));   // the curl spirals outward a little
                pts[i] = new float[]{cx + dir * out1, yc + (float) Math.sin(a) * r, zc + (float) Math.cos(a) * r};
            }
            pts[0][0] = cx + dir * (halfW - half);   // the root sits ON the head
            chain(out, tag, side * 5, parent, pts, half, half * 0.6f, 0f, colour, pattern, patternColour);
        }
        return out;
    }

    /** Feathered wings: per side four long feathers fanned from the shoulder, longest at the tip. */
    private static List<CreatureSculpt.Part> featherWings(String tag, String parent, float[] b,
            float s, int colour, String pattern, int patternColour) {
        List<CreatureSculpt.Part> out = new ArrayList<>();
        float span = Math.max(7f, 13f * s);
        for (int side = 0; side < 2; side++) {
            float dir = side == 0 ? -1f : 1f;
            float x = side == 0 ? b[0] : b[3];
            float y = lerp(b[1], b[4], 0.7f);
            float z = lerp(b[2], b[5], 0.5f);
            for (int i = 0; i < 4; i++) {
                float len = span * (0.55f + 0.15f * i);
                float x0 = x;
                float x1 = x + dir * len;
                float zz = z - i * 1.6f * s;
                out.add(part(tag + (side * 4 + i), parent, Math.min(x0, x1), y - 1.2f * s - i * 0.6f, zz - 1.2f,
                        Math.max(x0, x1), y + 1.2f * s - i * 0.6f, zz + 1.2f,
                        colour, new float[]{0, dir * (8f + 9f * i), dir * (-14f - 7f * i)}, pattern, patternColour));
            }
        }
        return out;
    }

    /**
     * Bat (and, wider, dragon) wings: per side a bony arm along the leading edge and membrane panels hanging off it
     * to a scalloped trailing edge.
     */
    private static List<CreatureSculpt.Part> batWings(String tag, String parent, float[] b,
            float s, float wide, int colour, String pattern, int patternColour) {
        List<CreatureSculpt.Part> out = new ArrayList<>();
        float span = Math.max(7f, 12f * s * wide);
        float chord = Math.max(4f, 8f * s * wide);
        for (int side = 0; side < 2; side++) {
            float dir = side == 0 ? -1f : 1f;
            float x = side == 0 ? b[0] : b[3];
            float y = lerp(b[1], b[4], 0.75f);
            float z = lerp(b[2], b[5], 0.5f);
            // The arm: one thin strut along the top edge.
            // (deep enough front to back that the swept-back far panel still meets it)
            out.add(part(tag + (side * 5), parent, Math.min(x, x + dir * span), y, z - 1.3f,
                    Math.max(x, x + dir * span), y + 1.2f, z + 1.3f,
                    colour, new float[]{0, 0, dir * -10f}, pattern, patternColour));
            // Membranes: four panels, each a little shorter, so the trailing edge scallops.
            for (int i = 0; i < 4; i++) {
                float x0 = x + dir * span * (i / 4f);
                float x1 = x + dir * span * ((i + 1) / 4f);
                float drop = chord * (1f - 0.18f * i) * (i % 2 == 0 ? 1f : 0.82f);
                out.add(part(tag + (side * 5 + 1 + i), parent, Math.min(x0, x1), y - drop, z - 0.5f,
                        Math.max(x0, x1), y + 1.2f, z + 0.5f,   // up through the arm, so the far panel stays on it once both are turned
                        colour, new float[]{0, dir * 10f, dir * -10f}, pattern, patternColour));
            }
        }
        return out;
    }

    /** Butterfly wings: per side a big upper lobe and a smaller lower one, flat and wide. */
    private static List<CreatureSculpt.Part> butterflyWings(String tag, String parent, float[] b,
            float s, int colour, String pattern, int patternColour) {
        List<CreatureSculpt.Part> out = new ArrayList<>();
        float big = Math.max(5f, 9f * s);
        for (int side = 0; side < 2; side++) {
            float dir = side == 0 ? -1f : 1f;
            float x = side == 0 ? b[0] : b[3];
            float y = lerp(b[1], b[4], 0.6f);
            float z = lerp(b[2], b[5], 0.45f);
            out.add(soft(tag + (side * 2), parent, Math.min(x, x + dir * big), y, z - 0.5f,
                    Math.max(x, x + dir * big), y + big, z + 0.5f,
                    colour, new float[]{0, 0, dir * -20f}, 0.3f, pattern, patternColour));
            float small = big * 0.65f;
            out.add(soft(tag + (side * 2 + 1), parent, Math.min(x, x + dir * small), y - small, z - 0.5f,
                    Math.max(x, x + dir * small), y, z + 0.5f,
                    colour, new float[]{0, 0, dir * 15f}, 0.3f, pattern, patternColour));
        }
        return out;
    }

    /** A big, soft, upward-curling tail (a fox's, a squirrel's): fat links that meet, curving up and over. */
    private static List<CreatureSculpt.Part> fluffyTail(String tag, String parent, float[] b,
            float s, int colour, String pattern, int patternColour) {
        List<CreatureSculpt.Part> out = new ArrayList<>();
        float cx = lerp(b[0], b[3], 0.5f);
        float y = lerp(b[1], b[4], 0.45f);
        float z = b[2];
        float seg = Math.max(2.5f, 3.5f * s);
        float[][] pts = {{cx, y, z + 1f}, {cx, y + seg * 0.4f, z - seg}, {cx, y + seg * 1.4f, z - seg * 1.7f},
                {cx, y + seg * 2.5f, z - seg * 1.8f}, {cx, y + seg * 3.2f, z - seg * 1.3f}};
        chain(out, tag, 0, parent, pts, Math.max(1.2f, 1.8f * s), Math.max(1.6f, 2.6f * s), 0.7f, colour, pattern, patternColour);
        return out;
    }

    /** A fish's tail: a narrowing stalk straight out of the back, then a forked fluke, both blades from its end. */
    private static List<CreatureSculpt.Part> fishTail(String tag, String parent, float[] b,
            float s, int colour, String pattern, int patternColour) {
        List<CreatureSculpt.Part> out = new ArrayList<>();
        float cx = lerp(b[0], b[3], 0.5f);
        float y = lerp(b[1], b[4], 0.5f);
        float z = b[2];
        float seg = Math.max(2.5f, 4f * s);
        float[] root = {cx, y, z + 1f};
        float[] mid = {cx, y, z - seg};
        float[] end = {cx, y, z - seg * 2};
        out.add(segment(tag + 0, parent, root, mid, Math.max(0.9f, 1.4f * s), Math.max(1.2f, 2.0f * s), 0f, colour, pattern, patternColour));
        out.add(segment(tag + 1, parent, mid, end, Math.max(0.7f, 1.0f * s), Math.max(0.9f, 1.4f * s), 0f, colour, pattern, patternColour));
        float blade = Math.max(3f, 5f * s);
        out.add(segment(tag + 2, parent, end, new float[]{cx, y + blade, end[2] - blade * 0.7f}, 0.5f, Math.max(1f, 1.6f * s), 0f,
                colour, pattern, patternColour));
        out.add(segment(tag + 3, parent, end, new float[]{cx, y - blade, end[2] - blade * 0.7f}, 0.5f, Math.max(1f, 1.6f * s), 0f,
                colour, pattern, patternColour));
        return out;
    }

    /** A peacock's fan: tall flat feathers standing up behind it, spread like a hand of cards. */
    private static List<CreatureSculpt.Part> peacockTail(String tag, String parent, float[] b,
            int count, float s, int colour, String pattern, int patternColour) {
        List<CreatureSculpt.Part> out = new ArrayList<>();
        int n = Math.max(5, Math.min(count < 5 ? 7 : count, 9));
        float cx = lerp(b[0], b[3], 0.5f);
        float y = lerp(b[1], b[4], 0.6f);
        float z = b[2] + 0.5f;   // rooted in the back: the fan stands ON it (KitLint)
        float tall = Math.max(8f, 14f * s);
        float w = Math.max(1.5f, 2.2f * s);
        for (int i = 0; i < n; i++) {
            float t = (float) i / (n - 1);
            out.add(part(tag + i, parent, cx - w, y, z - 0.6f, cx + w, y + tall, z + 0.6f,
                    colour, new float[]{-15f, 0, (t - 0.5f) * 140f}, pattern, patternColour));
        }
        return out;
    }

    /** A lion's (or a cow's) tail: thin, out of the back and hanging in a curve, with a soft tuft on the end. */
    private static List<CreatureSculpt.Part> lionTail(String tag, String parent, float[] b,
            float s, int colour, String pattern, int patternColour) {
        List<CreatureSculpt.Part> out = new ArrayList<>();
        float cx = lerp(b[0], b[3], 0.5f);
        float y = lerp(b[1], b[4], 0.7f);
        float z = b[2];
        float seg = Math.max(2.5f, 3.2f * s);
        float half = Math.max(0.5f, 0.6f * s);
        float[][] pts = {{cx, y, z + 1f}, {cx, y + seg * 0.2f, z - seg * 0.9f}, {cx, y - seg * 0.4f, z - seg * 1.6f},
                {cx, y - seg * 1.4f, z - seg * 1.9f}};
        chain(out, tag, 0, parent, pts, half, half, 0f, colour, pattern, patternColour);
        float tuft = Math.max(1.4f, 1.9f * s);
        float[] tip = pts[pts.length - 1];
        out.add(soft(tag + 3, parent, tip[0] - tuft, tip[1] - tuft * 1.8f, tip[2] - tuft, tip[0] + tuft, tip[1] + tuft * 0.3f,
                tip[2] + tuft, colour, new float[]{0, 0, 0}, 0.6f, pattern, patternColour));
        return out;
    }

    /**
     * Legs from one point, in pairs down the body: an insect's six, a spider's eight ("legs from one point,
     * bees with two legs"). Each leg is an upper segment raked up and out to a knee and a lower one down to the
     * ground.
     */
    private static List<CreatureSculpt.Part> legRow(String tag, String parent, float[] b,
            int count, float s, int colour, String pattern, int patternColour) {
        List<CreatureSculpt.Part> out = new ArrayList<>();
        int pairs = Math.max(1, Math.min(count, 4));
        float reach = Math.max(4f, 7f * s);
        float half = Math.max(0.5f, 0.6f * s);
        float ground = Math.min(0f, b[1] - 2f);
        float hip = lerp(b[1], b[4], 0.4f);
        float knee = b[4] + reach * 0.2f;
        for (int i = 0; i < pairs; i++) {
            float t = pairs == 1 ? 0.5f : (float) i / (pairs - 1);
            float z = lerp(b[5], b[2], 0.15f + 0.7f * t);
            // Front legs reach forward, back legs back -- fanned enough that the feet of
            // neighbouring pairs stand apart and have room to step.
            float splay = (t - 0.5f) * reach * 1.3f;
            for (int side = 0; side < 2; side++) {
                float dir = side == 0 ? -1f : 1f;
                float x0 = side == 0 ? b[0] : b[3];
                // Each leg is two RODS, jointed: the thigh from the body's side up and out to
                // the knee, and the shin from the knee down to a foot on the ground. The shin is the
                // thigh's CHILD, so whatever turns the leg at the body carries the whole leg and the
                // knee never opens. (Two boxes each spanning its segment's bounding box, both on the
                // body, read as two slabs, and a step that moved one left the other behind.)
                float[] h = {x0 - dir * half, hip, z};
                float[] k = {x0 + dir * reach * 0.5f, knee, z - splay * 0.5f};
                float[] f = {x0 + dir * reach * 1.1f, ground + half, z - splay};
                String thigh = tag + (i * 4 + side * 2);
                CreatureSculpt.Part upper = rod(thigh, parent, h, k, dir, half, null, colour, pattern,
                        patternColour);
                out.add(upper);
                out.add(rod(tag + (i * 4 + side * 2 + 1), upper.name(), k, f, dir, half, upper, colour,
                        pattern, patternColour));
            }
        }
        return out;
    }

    /**
     * A box drawn as a rod from {@code a} to {@code b} (sculpt space), {@code half} thick either side of
     * the line, pivoted at {@code a} and tilted onto the line. The rod is built along {@code dir}·X (a
     * thigh reaching out) or, under a {@code parent} rod, straight down (a shin), and solved for the tilt
     * that lands its far end on {@code b}: the renderer turns a raw-space box by
     * {@code Rz(-rz)·Ry(-ry)·Rx(rx)} about its pivot ({@code RigTransform#mirrorX} has the derivation),
     * and a child under its parent's turn as well, so a shin's tilt is solved in its thigh's frame.
     */
    private static CreatureSculpt.Part rod(String name, String parentName, float[] a, float[] b,
            float dir, float half, CreatureSculpt.Part parentRod, int colour, String pattern,
            int patternColour) {
        float[] d = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
        float len = (float) Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        float[] u = {d[0] / len, d[1] / len, d[2] / len};
        // The joint is a little overlapped either end, so it stays closed however it turns.
        float over = half;
        float[] pivot;
        float[] from;
        float[] to;
        float[] rot;
        if (parentRod == null) {
            // Along dir·X, then Ry(α)·(dir,0,0) = (dir·cosα, 0, -dir·sinα) and Rz(ε) lifts it.
            double alpha = Math.asin(Math.max(-1, Math.min(1, -u[2] * dir)));
            double eps = Math.atan2(u[1] * dir, u[0] * dir);
            pivot = a.clone();
            float x1 = a[0] - dir * over;
            float x2 = a[0] + dir * (len + over * 0.5f);
            from = new float[]{Math.min(x1, x2), a[1] - half, a[2] - half};
            to = new float[]{Math.max(x1, x2), a[1] + half, a[2] + half};
            rot = new float[]{0f, (float) -Math.toDegrees(alpha), (float) -Math.toDegrees(eps)};
        } else {
            // In the thigh's own (unturned) frame: undo its turn, Rz(ε)·Ry(α), on the direction, and
            // put the knee where the thigh's far end is before it turns.
            float[] pr = parentRod.rotation();
            double alpha = Math.toRadians(-pr[1]);
            double eps = Math.toRadians(-pr[2]);
            float[] v = rotY(rotZ(u, -eps), -alpha);
            float[] pa = parentRod.pivot();
            float[] knee = rotY(rotZ(new float[]{a[0] - pa[0], a[1] - pa[1], a[2] - pa[2]}, -eps), -alpha);
            float[] k = {pa[0] + knee[0], pa[1] + knee[1], pa[2] + knee[2]};
            // Straight down, then Rx(β)·(0,-1,0) = (0, -cosβ, -sinβ) and Rz(γ) swings it out.
            double beta = Math.asin(Math.max(-1, Math.min(1, -v[2])));
            double gamma = Math.atan2(v[0], -v[1]);
            pivot = k;
            from = new float[]{k[0] - half, k[1] - len, k[2] - half};
            to = new float[]{k[0] + half, k[1] + over, k[2] + half};
            rot = new float[]{(float) Math.toDegrees(beta), 0f, (float) -Math.toDegrees(gamma)};
        }
        return new CreatureSculpt.Part(CreatureSculpt.sanitize(MARK + name), parentName == null ? null
                : parentName, pivot, from, to, colour, CreatureSculpt.clampRotation(rot), 0f, pattern,
                patternColour);
    }

    private static float[] rotZ(float[] v, double r) {
        double c = Math.cos(r);
        double s = Math.sin(r);
        return new float[]{(float) (v[0] * c - v[1] * s), (float) (v[0] * s + v[1] * c), v[2]};
    }

    private static float[] rotY(float[] v, double r) {
        double c = Math.cos(r);
        double s = Math.sin(r);
        return new float[]{(float) (v[0] * c + v[2] * s), v[1], (float) (-v[0] * s + v[2] * c)};
    }

    /** Ears on the head: pointy (a cat's), round (a bear's), floppy (a puppy's, hanging) or long (a bunny's). */
    private static List<CreatureSculpt.Part> ears(String tag, String parent, float[] b, String style,
            float s, int colour, String pattern, int patternColour) {
        List<CreatureSculpt.Part> out = new ArrayList<>();
        float w = Math.max(1.5f, (b[3] - b[0]) * 0.17f * s);
        float cx = (b[0] + b[3]) / 2f;
        float halfW = (b[3] - b[0]) / 2f;
        float z = lerp(b[2], b[5], 0.4f);
        for (int side = 0; side < 2; side++) {
            float dir = side == 0 ? -1f : 1f;
            float x = cx + dir * halfW * 0.55f;
            switch (style) {
                case "round" -> out.add(soft(tag + side, parent, x - w, b[4] - 0.5f, z - 0.8f, x + w, b[4] + w * 1.6f, z + 0.8f,
                        colour, new float[]{0, 0, 0}, 0.4f, pattern, patternColour));
                case "floppy" -> {
                    // A flap from the top edge of the head, out over its side and hanging down beside it: flat (thin
                    // across), as long as half the head, so it shows from the front and the side.
                    float[] top = {cx + dir * (halfW - 0.4f), b[4] - 0.3f, z};
                    float[] hang = {cx + dir * (halfW + 1.2f), b[4] - (b[4] - b[1]) * 0.6f, z + 0.5f};
                    out.add(segment(tag + side, parent, top, hang, 0.6f, w * 1.1f, 0f, colour, pattern, patternColour));
                }
                case "long" -> {
                    // Bunny ears: tall, flat, standing up and leaning out a little, in two links that meet.
                    float[][] pts = {{x, b[4] - 0.5f, z}, {x + dir * w * 0.5f, b[4] + w * 3.2f, z - 0.4f},
                            {x + dir * w * 1.3f, b[4] + w * 5.6f, z - 1f}};
                    for (int i = 0; i < 2; i++) {
                        out.add(segment(tag + (side * 2 + i), parent, pts[i], pts[i + 1], w * (0.75f - 0.15f * i), 0.6f, 0f,
                                colour, pattern, patternColour));
                    }
                }
                default -> {
                    float[][] pts = {{x, b[4] - 0.5f, z}, {x + dir * w * 0.3f, b[4] + w * 1.2f, z}, {x + dir * w * 0.6f, b[4] + w * 2.2f, z}};
                    for (int i = 0; i < 2; i++) {
                        out.add(segment(tag + (side * 2 + i), parent, pts[i], pts[i + 1], w * (1f - 0.45f * i), 0.7f, 0f,
                                colour, pattern, patternColour));
                    }
                }
            }
        }
        return out;
    }

    /** A snout out of the lower front of the head, with a nose on its tip. */
    private static List<CreatureSculpt.Part> snout(String tag, String parent, float[] b,
            float s, int colour, String pattern, int patternColour) {
        List<CreatureSculpt.Part> out = new ArrayList<>();
        float hw = Math.max(1.5f, (b[3] - b[0]) * 0.28f);
        float hh = Math.max(1.2f, (b[4] - b[1]) * 0.22f);
        float len = Math.max(2f, 3.5f * s);
        float cx = lerp(b[0], b[3], 0.5f);
        float cy = lerp(b[1], b[4], 0.3f);
        out.add(part(tag + 0, parent, cx - hw, cy - hh, b[5] - 0.5f, cx + hw, cy + hh, b[5] + len,
                colour, new float[]{8f, 0, 0}, pattern, patternColour));   // a snout droops a little
        out.add(part(tag + 1, parent, cx - hw * 0.45f, cy + hh * 0.2f, b[5] + len - 0.2f, cx + hw * 0.45f, cy + hh, b[5] + len + 1f,
                darker(colour), new float[]{8f, 0, 0}, "", 0));
        return out;
    }

    /** A beak: an upper half that tapers to a point and a lower half under it. */
    private static List<CreatureSculpt.Part> beak(String tag, String parent, float[] b,
            float s, int colour, String pattern, int patternColour) {
        List<CreatureSculpt.Part> out = new ArrayList<>();
        float hw = Math.max(1.2f, (b[3] - b[0]) * 0.2f);
        float len = Math.max(2.5f, 4f * s);
        float cx = lerp(b[0], b[3], 0.5f);
        float cy = lerp(b[1], b[4], 0.42f);
        out.add(part(tag + 0, parent, cx - hw, cy, b[5] - 0.5f, cx + hw, cy + hw * 1.1f, b[5] + len * 0.6f,
                colour, new float[]{0, 0, 0}, pattern, patternColour));
        out.add(part(tag + 1, parent, cx - hw * 0.55f, cy - 0.2f, b[5] + len * 0.55f, cx + hw * 0.55f, cy + hw * 0.7f, b[5] + len,
                colour, new float[]{12f, 0, 0}, pattern, patternColour));
        out.add(part(tag + 2, parent, cx - hw * 0.8f, cy - hw * 0.7f, b[5] - 0.5f, cx + hw * 0.8f, cy, b[5] + len * 0.5f,
                colour, new float[]{-6f, 0, 0}, pattern, patternColour));
        return out;
    }

    /** An elephant's trunk: hangs from the front of the head and curls forward at the tip, in links that meet. */
    private static List<CreatureSculpt.Part> trunk(String tag, String parent, float[] b,
            int count, float s, int colour, String pattern, int patternColour) {
        List<CreatureSculpt.Part> out = new ArrayList<>();
        // F1 regrade (owner: "sketchy"): it was thin, started low and hung flat against the chest in the body's own
        // colour, so from most angles it vanished. Now it is as thick as a fifth of the face, comes FORWARD out of the
        // middle of the face first, hangs clear of the body, and its tip curls forward and up.
        int n = Math.max(3, Math.min(count, 6));
        float cx = lerp(b[0], b[3], 0.5f);
        float y = lerp(b[1], b[4], 0.5f);
        float z = b[5];
        float headW = b[3] - b[0];
        float h0 = Math.max(1.2f, headW * 0.11f * Math.max(0.8f, s));
        float seg = Math.max(2.2f, Math.max(3.0f * s, headW * 0.35f));
        float[][] pts = new float[n + 2][];
        pts[0] = new float[]{cx, y, z - 1f};
        pts[1] = new float[]{cx, y - seg * 0.25f, z + seg * 0.8f};          // out of the face
        for (int i = 2; i <= n + 1; i++) {
            float[] prev = pts[i - 1];
            boolean curl = i == n + 1;
            pts[i] = curl ? new float[]{cx, prev[1] + seg * 0.25f, prev[2] + seg * 0.75f}   // the tip lifts
                    : new float[]{cx, prev[1] - seg * 0.9f, prev[2] + seg * 0.3f};          // hangs, a little forward
        }
        chain(out, tag, 0, parent, pts, h0, Math.max(0.6f, h0 * 0.55f), 0f, colour, pattern, patternColour);
        return out;
    }

    /** Tusks: per side, out of the lower front of the head, down and then up and forward. */
    private static List<CreatureSculpt.Part> tusks(String tag, String parent, float[] b,
            float s, int colour, String pattern, int patternColour) {
        List<CreatureSculpt.Part> out = new ArrayList<>();
        float seg = Math.max(2.5f, 3.5f * s);
        float half = Math.max(0.6f, 0.9f * s);
        for (int side = 0; side < 2; side++) {
            float x = lerp(b[0], b[3], side == 0 ? 0.28f : 0.72f);
            float y = lerp(b[1], b[4], 0.2f);
            float z = b[5];
            out.add(part(tag + (side * 2), parent, x - half, y - seg, z - half, x + half, y, z + half,
                    colour, new float[]{25f, 0, 0}, pattern, patternColour));
            out.add(part(tag + (side * 2 + 1), parent, x - half * 0.8f, y - seg * 1.3f, z + seg * 0.4f, x + half * 0.8f, y - seg * 0.5f, z + seg * 1.2f,
                    colour, new float[]{-40f, 0, 0}, pattern, patternColour));
        }
        return out;
    }

    /**
     * A lion's mane: a soft ring of tufts round the back and sides of the head, standing proud of it. Unturned, so the
     * ring is its own mirror image (the first cut turned each tuft by its angle round the ring, and one side of the
     * head came out heavier than the other).
     */
    private static List<CreatureSculpt.Part> mane(String tag, String parent, float[] b,
            float s, int colour, String pattern, int patternColour) {
        List<CreatureSculpt.Part> out = new ArrayList<>();
        float cx = lerp(b[0], b[3], 0.5f);
        float cy = lerp(b[1], b[4], 0.5f);
        float rx = (b[3] - b[0]) * 0.5f + 0.6f * s;
        float ry = (b[4] - b[1]) * 0.5f + 0.6f * s;
        float z = lerp(b[2], b[5], 0.35f);
        float tuft = Math.max(1.8f, 2.2f * s);
        int n = 8;
        for (int i = 0; i < n; i++) {
            double a = Math.PI / 2 + Math.PI * 2 * i / n;   // from the top, so the ring is even left and right
            float x = cx + (float) Math.cos(a) * rx;
            float y = cy + (float) Math.sin(a) * ry;
            out.add(soft(tag + i, parent, x - tuft, y - tuft, z - tuft * 1.4f, x + tuft, y + tuft, z + tuft * 0.6f,
                    colour, new float[]{-12f, 0, 0}, 0.6f, pattern, patternColour));   // leaning back: still its own mirror
        }
        return out;
    }

    /** A darker shade, for a nose on a snout of the same colour. */
    private static int darker(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int bl = argb & 0xFF;
        return (argb & 0xFF000000) | ((r * 45 / 100) << 16) | ((g * 45 / 100) << 8) | (bl * 45 / 100);
    }

    // ===================== helpers =====================

    private static CreatureSculpt.Part part(String name, String parent,
            float x0, float y0, float z0, float x1, float y1, float z1,
            int colour, float[] rotation, String pattern, int patternColour) {
        float[] from = {Math.min(x0, x1), Math.min(y0, y1), Math.min(z0, z1)};
        float[] to = {Math.max(x0, x1), Math.max(y0, y1), Math.max(z0, z1)};
        // A kit must never emit a degenerate box: parseParts would drop it and the child would get
        // six spikes where seven were asked for, with nothing said about the seventh.
        for (int i = 0; i < 3; i++) {
            if (to[i] - from[i] < 1f) {
                to[i] = from[i] + 1f;
            }
        }
        float[] pivot = {(from[0] + to[0]) / 2f, from[1], (from[2] + to[2]) / 2f};
        // MARK, not the bare kit name: see the note on MARK for the 355-false-positives story.
        return new CreatureSculpt.Part(CreatureSculpt.sanitize(MARK + name), parent, pivot, from, to,
                colour, CreatureSculpt.clampRotation(rotation), 0f, pattern, patternColour);
    }

    /** {@code [minX,minY,minZ,maxX,maxY,maxZ]} of the anchor, or null if there isn't one. */
    private static float[] anchorBox(CreatureSculpt.Part anchor) {
        if (anchor == null) {
            return null;
        }
        return new float[]{anchor.from()[0], anchor.from()[1], anchor.from()[2],
                anchor.to()[0], anchor.to()[1], anchor.to()[2]};
    }

    /** Loose bone match, the way UvRegions resolves "wing" to "wingL". */
    private static CreatureSculpt.Part find(List<CreatureSculpt.Part> parts, String query) {
        if (parts == null || parts.isEmpty()) {
            return null;
        }
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (!q.isEmpty()) {
            for (CreatureSculpt.Part p : parts) {
                if (p.name().equalsIgnoreCase(q)) {
                    return p;
                }
            }
            for (CreatureSculpt.Part p : parts) {
                if (p.name().toLowerCase(Locale.ROOT).startsWith(q)) {
                    return p;
                }
            }
        }
        // No usable anchor named: fall back to "body", then to the biggest part, which is what a
        // person would point at if you asked them where the spikes go.
        for (CreatureSculpt.Part p : parts) {
            if (p.name().equalsIgnoreCase("body")) {
                return p;
            }
        }
        CreatureSculpt.Part best = null;
        long bestVol = -1;
        for (CreatureSculpt.Part p : parts) {
            long v = (long) p.width() * p.height() * p.depth();
            if (v > bestVol) {
                bestVol = v;
                best = p;
            }
        }
        return best;
    }

    private static int defaultCount(String kit) {
        return switch (kit) {
            case "spike_row" -> 5;
            case "horn" -> 2;
            case "leg_row" -> 3;
            case "trunk" -> 5;
            case "claw" -> 3;
            case "tail" -> 4;
            case "fin" -> 4;
            case "crest" -> 5;
            default -> 2;
        };
    }

    private static int clampCount(int n) {
        return Math.max(1, Math.min(MAX_PER_KIT, n));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static String str(JsonObject o, String k, String d) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : d;
    }

    private static int intOf(JsonObject o, String k, int d) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : d;
        } catch (RuntimeException e) {
            return d;
        }
    }
}
