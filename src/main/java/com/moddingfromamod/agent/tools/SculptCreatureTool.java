package com.moddingfromamod.agent.tools;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.LivingEntity;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.provenance.ProvenancePublisher;
import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.ModelRepository;
import com.moddingfromamod.runtime.SculptSpec;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * <b>Build a creature out of coloured boxes</b> — its own shape and its own skin, rather than a
 * vanilla mob in a costume.
 *
 * <p>This exists because {@code define_creature} cannot make most characters, for two structural
 * reasons that are invisible until you see the result. Every humanoid texture the game ships is a
 * <b>monster</b>; and its tint <b>multiplies</b>, so it can only darken. Asked for a cheerful clown,
 * that path produced a blood-red zombie and a pig-man — not because the description was
 * misunderstood, but because a white face is unreachable from a rotting one. Here a white face is
 * just a white box.
 *
 * <p>Reach for this whenever the child names <em>a specific character or thing</em> — Chucko
 * McBurger, Spikor, a troll with a club, their own invention with described colours. Keep
 * {@code define_creature} for vague creatures ("a big bouncy slime") where a vanilla look is fine and
 * instant.
 */
public final class SculptCreatureTool implements McpTool {

    @Override
    public String name() {
        return "sculpt_creature";
    }

    @Override
    public String description() {
        return "Build a creature from coloured BOXES — its own shape and its own colours, with no "
                + "game restart. USE THIS whenever the child names a specific character or a "
                + "specific look: a mascot, a cartoon character, a troll with a club, 'a purple "
                + "baby dragon'. THAT INCLUDES 'make me <character>' when he then calls that "
                + "character 'he', 'she' or 'them' — the pronoun means he is describing SOMEBODY "
                + "ELSE for you to build, not asking to become them. Build the character. "
                + "Do NOT use define_creature for those — every humanoid skin in the "
                + "game is a monster and its tint can only DARKEN, so asking it for a cheerful clown "
                + "gives you a blood-red zombie. Describe the creature as a list of parts, each a "
                + "box with a colour: e.g. a clown = white 'head', red 'hair' on top, yellow 'body', "
                + "red 'armL'/'armR', big red 'legL'/'legR'. Name parts head/body/armL/armR/legL/"
                + "legR where they fit and they animate automatically (idle, walk, attack); a part "
                + "named 'head' also looks at the player. Coordinates are in sixteenths of a block, "
                + "Y is up, feet at y=0 — a player-sized creature is about 32 tall. Re-sculpting the "
                + "same id updates every one already in the world." + " IF THE CHILD NAMED A REAL CHARACTER, decide its colours AND THEIR "
                + "PROPORTIONS before you start, and pass them as 'palette' — naming the colours "
                + "is not enough to stop you building an all-green frog knight; committing to the "
                + "proportions is. CALL list_creatures FIRST: it carries the rules that have cost "
                + "real wishes, and it is the ONLY place those rules live.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        // A STATUS THAT RIDES ALONG WITH THE WORK. Calling set_status separately costs a
        // full model round trip -- ~4s, and 12% of one measured session -- for a tool that
        // executes in under 8ms. Passed here it is shown the moment the call arrives, in the
        // agent's own words, for nothing.
        Schemas.prop(s, "status", "string",
                "A SHORT kid-facing line saying what you are doing, shown the instant this "
                        + "call arrives, e.g. 'Sculpting your frog knight out of blocks'. Pass it EVERY time: it is "
                        + "free here, whereas a separate set_status costs the child a wait.");
        Schemas.prop(s, "id", "string",
                "Short unique name, e.g. 'frog_knight'. Re-use it to re-sculpt.");
        Schemas.prop(s, "name", "string", "Display name shown above the creature.");
        // The A/B lever. With fidelity OFF the agent is not merely prevented from using
        // rotation/patterns/kits — it is never TOLD they exist. Advertising a field and then
        // discarding it would make arm A spend tokens on a no-op and be slower than the real
        // baseline while building the same geometry: unfair in both directions at once.
        boolean fidelity = com.moddingfromamod.runtime.FidelityFlags.enabled();
        String common = "'from'/'to' are opposite corners in sixteenths of a block (Y up, feet at "
                + "y=0). 'colour' is a name (" + String.join(", ", Species.colourNames())
                + ") or hex like '#FFD800'. 'parent' attaches a part to another so it moves "
                + "with it (hair -> head). Use names head/body/armL/armR/legL/legR to get "
                + "animation for free.";
        if (fidelity) {
            Schemas.prop(s, "parts", "array",
                    "The boxes, at most " + CreatureSculpt.MAX_PARTS + " of them — past that "
                            + "they are DROPPED, so build within it rather than over it. "
                            + "Each: {name, from:[x,y,z], to:[x,y,z], colour, parent?, pivot?, "
                            + "rotation?, inflate?, pattern?, pattern_colour?}. " + common + " "
                            + "'rotation' is [x,y,z] DEGREES (max "
                            + (int) com.moddingfromamod.runtime.CreatureSculpt.MAX_ROTATION
                            + " each) and is what makes a SPIKE a spike instead of a step — tilt "
                            + "horns, spines, fins, claws and swept wings rather than stacking "
                            + "smaller and smaller upright boxes, which just makes a staircase. "
                            + "'inflate' (0-"
                            + (int) com.moddingfromamod.runtime.CreatureSculpt.MAX_INFLATE
                            + ") grows a box on every face to round off a silhouette. "
                            + "'pattern' paints the part instead of leaving it flat — one of "
                            + String.join(", ", com.moddingfromamod.runtime.SculptPattern.NAMES)
                            + " — with 'pattern_colour' as the second colour. Use it for a grass "
                            + "skirt, tiger stripes, scales, a shell's rings. With start_from, a part "
                            + "sent by NAME with no from/to only re-skins that base part where it is: "
                            + "{\"name\":\"shell\",\"pattern\":\"scales\",\"pattern_colour\":\"#2E4A1A\"}.");
            Schemas.prop(s, "kits", "array",
                    "READY-MADE BODY PARTS — prefer these over hand-placing boxes, they are "
                            + "shorter to write AND come out better. Each: {kit, on, count?, "
                            + "size?, colour?, along?, pattern?}. 'kit' is one of "
                            + String.join(", ", com.moddingfromamod.runtime.SculptKits.available())
                            + ". THE FACE IS YOURS TO BUILD, deliberately — there is no face kit, "
                            + "because a prefab face would make every character in the game wear "
                            + "the same six expressions and you are the one who knows what this "
                            + "character looks like. But BUILD one: a mouth is three or more boxes "
                            + "stepped into a curve, not one flat box, and brows are a pair. "
                            + "'on' is the bone it attaches to ('body', 'head', 'legL'). 'size' "
                            + "is S/M/L/XL. 'along' (spike_row only) is back/top/front/sides. "
                            + "'style' picks a look: horn = curved|antler|unicorn|ram, wing = "
                            + "swept|feather|bat|butterfly|dragon, tail = tapered|fluffy|fish|peacock|lion, "
                            + "ears = pointy|round|floppy|long. An INSECT or SPIDER: build a small body and "
                            + "put its legs on with leg_row on 'body' (count = pairs: 3 for a bug or a bee, "
                            + "4 for a spider) -- legs from one row, never one leg per corner. Head kits go "
                            + "'on' the head: ears, snout, beak, trunk, tusk, mane. "
                            + "Example: {\"kit\":\"spike_row\",\"on\":\"shell\",\"count\":7,"
                            + "\"size\":\"L\",\"colour\":\"bone\"} builds seven tilted, tapered "
                            + "spikes down the spine — geometry you cannot afford to write out by "
                            + "hand. Kits are added ON TOP of 'parts', so build the body in "
                            + "'parts' and the horns/spikes/claws/wings/tail/fins in 'kits'.");
        } else {
            Schemas.prop(s, "parts", "array",
                    "The boxes. Each: {name, from:[x,y,z], to:[x,y,z], colour, parent?, pivot?}. "
                            + common);
        }
        Schemas.prop(s, "start_from", "string",
                "START FROM A CREATURE THAT ALREADY EXISTS instead of from nothing. Pass another "
                        + "creature's id (see list_creatures) and you inherit its whole body — then "
                        + "send only what CHANGED. This is the cheapest way to make a variant: 'a "
                        + "baby version of my dragon' is start_from the dragon plus a smaller "
                        + "scale, not thirty boxes again. Combine with 'remove' and 'recolour'. A "
                        + "part in 'parts' with the same name as one in the base REPLACES it, so "
                        + "'make his head bigger' is one box.");
        Schemas.prop(s, "reshape", "array",
                "With start_from: change a base part's PROPORTIONS instead of re-sending its box — "
                        + "this is how a near species starts from a starter (a tiger from the cat, a "
                        + "donkey from the horse). Each: {part, scale?, anchor?, move?}. 'part' is a "
                        + "base part's name, or a prefix ('leg' = every leg). 'scale' is a number or "
                        + "[x,y,z] (0.25-4); 'anchor' is the point that stays put: centre (default), "
                        + "bottom, top, front (the face end, -z) or back; 'move' is [dx,dy,dz]. Parts "
                        + "that hang on a reshaped part move with it. E.g. a longer-bodied cat: "
                        + "[{\"part\":\"body\",\"scale\":[1,1,1.5]}]; longer legs: scale 'leg' "
                        + "[1,1.5,1] with anchor bottom AND move 'body' up by the extra height.");
        Schemas.prop(s, "remove", "array",
                "With start_from: base part names to delete, e.g. [\"shell\",\"tail\"]. Matches "
                        + "loosely, so \"spike\" removes spike1..spike9.");
        Schemas.prop(s, "recolour", "object",
                "With start_from: base part names to repaint, e.g. {\"body\":\"red\","
                        + "\"armL\":\"blue\"}. Matches loosely, same as 'remove'.");
        Schemas.prop(s, "scale", "number", "Overall size multiplier, 0.2 to 8.0. 1.0 = as built.");
        Schemas.prop(s, "health", "number", "Hit points, 1 to 500.");
        Schemas.prop(s, "speed", "number", "Move speed, 0.05 to 0.6. 0.25 is normal.");
        Schemas.prop(s, "damage", "number", "Attack damage, 0 to 40: the damage its body would have, like a "
                + "vanilla mob its size (a wolf 4, a zombie 3, a polar bear 6, a ravager 12, an iron golem 15). A friendly "
                + "creature never attacks whatever this says, so never 0 to make it gentle: its moves, a guard and any "
                + "world that enlists it use this number. 0 lands nothing.");
        Schemas.prop(s, "armor", "number", "Armour points, 0 to 30 (a full iron suit is 15): a shell, scales, a robot's "
                + "plates. Leave out for none.");
        Schemas.prop(s, "knockback_resistance", "number", "0 to 1: how hard it is to push (1 = never pushed, for a huge or "
                + "very heavy body). Leave out for none.");
        Schemas.prop(s, "powers", "array",
                "Any of: hostile, friendly, leaps, fireproof, skittish, glowing, persistent, "
                        + "flies, hops, naps, boss, explodes, rideable. 'friendly' = HARMLESS: it never attacks anyone. "
                        + "'rideable' = he climbs on by using it and steers where he looks; put it HERE "
                        + "when the wish is something he rides, so every copy of it (his button too) can be ridden. "
                        + "Without 'friendly' or 'skittish' it ATTACKS players. 'hostile' = it attacks "
                        + "PLAYERS, him included: never for a creature that fights monsters FOR him, which "
                        + "is friendly + base 'guardian'. Immunities to its "
                        + "own abilities (fireproof, blastproof, poisonproof…) are added for you. "
                        + MOVES);
        Schemas.prop(s, "abilities", "array", ABILITIES);
        Schemas.prop(s, "throws", "string",
                "WHAT ITS 'ranged' MOVE THROWS, as the vanilla item the ball looks like: "
                        + "'minecraft:fire_charge' for fireballs (they set the monster alight too), "
                        + "'minecraft:egg', 'minecraft:slime_ball', 'minecraft:ender_pearl' (only the "
                        + "look: nobody is teleported). Left out it throws a snowball. Only matters "
                        + "with the ranged ability; a ball never breaks a block and hits only what the "
                        + "creature may hurt.");
        Schemas.prop(s, "voice", "string",
                "HOW IT SOUNDS — give EVERY creature one, or it makes no sound at all (no growl, no "
                        + "cry when hit, no footsteps). A vanilla mob whose voice it borrows: its idle "
                        + "sound, its hurt cry, its death cry and its footsteps, pitched deeper the "
                        + "bigger it is. Pick the mob that SOUNDS like the creature: a dog or a puppy "
                        + "barks like 'wolf', a cat 'cat', a bird 'parrot' or 'chicken', a bear "
                        + "'polar_bear', a big beast 'ravager', a dragon 'ender_dragon', a frog "
                        + "'frog', a goat 'goat', a horse 'horse', something eerie 'warden' or "
                        + "'ghast'. 'silent' when the wish asks for no sound (not even footsteps). "
                        + "With start_from it keeps the base's voice unless you name another.");
        Schemas.prop(s, "sounds", "object",
                "A MOMENT that should sound different from the voice, by vanilla sound id: "
                        + "{\"hurt\":\"minecraft:entity.ravager.roar\"} for 'it roars when you "
                        + "hit it', {\"ambient\":\"minecraft:entity.wolf.growl\"} for 'it "
                        + "growls'. Moments: ambient (now and then while it is about), hurt, death, "
                        + "step. Anything not named comes from 'voice'.");
        Schemas.prop(s, "base", "string", INSTINCTS);
        Schemas.prop(s, com.moddingfromamod.species.BehavesLike.ARG, "string",
                com.moddingfromamod.species.BehavesLike.SCULPT_DESCRIBE);
        Schemas.prop(s, "spawn", "boolean",
                "Put one in the world now. Pass FALSE when the creature still needs powers — "
                        + "sculpt it, measure it, write its datapack, and spawn it only at the end "
                        + "(run_command 'wishmob spawn <id>') so it arrives already able to do the "
                        + "thing the child asked for. A creature standing around with no powers is "
                        + "one he can kill before it ever works. Default true, which is right only "
                        + "when the creature IS the whole wish.");
        Schemas.prop(s, "at", "string", com.moddingfromamod.place.At.describe("the creature", "its front",
                "In a wish that built something and names no place, it stands at that building's front "
                        + "— never at its anchor, which is its foundations. A boss on \"castle:throne\", "
                        + "a dragon on \"castle:top\"."));
        Schemas.prop(s, "palette", "array",
                "WHAT YOU INTEND THIS CREATURE TO LOOK LIKE, before you build it. Each: "
                        + "{colour, percent} — e.g. a frog knight as [{green,45},{tan,35},{red,10}]. "
                        + "Percentages are of the visible body and need not total 100. "
                        + "They are BY VOLUME (width x height x depth of the boxes you send), so "
                        + "the body, head and legs carry nearly all of it, and an eye, nose, "
                        + "mouth, claw or earring is well under 1% each: list a colour only if "
                        + "a whole region wears it, and put eyes and other small details at 1-3% "
                        + "or leave them out. Three in four first sculpts were told a colour "
                        + "MISSED, often a small detail declared at 10% that covers 1%, and redid "
                        + "the whole sculpt for it. "
                        + "You are then told how the creature ACTUALLY came out, so you can fix a "
                        + "drift you would otherwise never notice: six consecutive frog-knight "
                        + "revisions each drifted further from the character because nothing ever "
                        + "compared the result against the plan. This is a promise you make to "
                        + "yourself, not a reference lookup — for an invented creature, use the "
                        + "child's own words.");
        Schemas.prop(s, "note", "string",
                com.moddingfromamod.provenance.ProvenanceContext.NOTE_HINT);
        // PALETTE IS REQUIRED, not optional.
        //
        // Required so the grader ALWAYS has a declaration to check against.
        //
        // (This was originally justified by "the agent used it in 0 of 30 sculpts". That figure
        // was wrong -- it came from a clipped action log and from sculpts predating the field.
        // The agent in fact used it in every call, unprompted, while it was optional. Required
        // costs nothing and removes the possibility of an ungraded sculpt, but it is not the
        // behaviour fix it was first written up as.)
        //
        // It applies to re-sculpts too, deliberately: re-declaring on a revision is what catches
        // the drift that took six consecutive Spikors further from Spikor.
        // BEHAVES_LIKE IS REQUIRED TOO: a creature that names no life lived its carrier's, which the
        // carrier was chosen for its hitbox, not for the wish (every flier fluttered like the bat it rides).
        // Left out it is still never refused: MagicianBehaviour names one, and the reply says which.
        return Schemas.required(s, "id", "parts", "palette", "note", com.moddingfromamod.species.BehavesLike.ARG);
    }

    /**
     * Append the parts any {@code kits} expand into.
     *
     * <p>Kits run <b>after</b> the hand-written parts because a kit hangs off one of them — a spike
     * row needs a back to sit on. Capped at the same {@link CreatureSculpt#MAX_PARTS} budget the
     * hand-written parts obey, so a seven-spike row can never crowd the body out of the creature.
     *
     * <p>Static and pure so the expansion is gated without a server.
     */
    /**
     * The species fields for a sculpt: the {@code start_from} creature's own record (name, powers,
     * stats, owner…) underneath, this call's arguments on top. Without a base, the arguments alone.
     */
    static JsonObject inheritSpecies(JsonObject args) {
        if (args == null || !args.has("start_from") || !args.get("start_from").isJsonPrimitive()) {
            return args == null ? new JsonObject() : args.deepCopy();
        }
        Species base = com.moddingfromamod.species.SpeciesRegistry.get(
                ModelRepository.sanitizeKey(args.get("start_from").getAsString()));
        return inherit(base == null ? null : base.toJson(), args);
    }

    /**
     * {@code base}'s fields under {@code args}'s; pure. A field the call names always wins. Never
     * the base's OWNER: a fork of another child's creature is his, and the registry stamps a new
     * species with the wisher only when it arrives with no owner (SpeciesRegistry.put).
     */
    static JsonObject inherit(JsonObject base, JsonObject args) {
        JsonObject out = base == null ? new JsonObject() : base.deepCopy();
        out.remove(com.moddingfromamod.marshal.ArtifactOwner.FIELD);
        for (var e : args.entrySet()) {
            out.add(e.getKey(), e.getValue().deepCopy());
        }
        return out;
    }

    /**
     * Fold a {@code start_from} base under the agent's own parts.
     *
     * <p>Silently no-ops when no base is named or the base has no readable spec, so an unknown id
     * costs a plainer creature rather than a failed wish.
     */
    public static List<CreatureSculpt.Part> withBase(List<CreatureSculpt.Part> parts,
                                                     JsonObject args) {
        if (args == null || !args.has("start_from") || !args.get("start_from").isJsonPrimitive()) {
            return parts;
        }
        String baseKey = ModelRepository.sanitizeKey(args.get("start_from").getAsString());
        if (baseKey.isEmpty()) {
            return parts;
        }
        List<CreatureSculpt.Part> base = ModelRepository.sculptedParts(baseKey);
        if (base.isEmpty()) {
            return parts;
        }
        java.util.Set<String> remove = new java.util.LinkedHashSet<>();
        if (args.has("remove") && args.get("remove").isJsonArray()) {
            for (var el : args.getAsJsonArray("remove")) {
                if (el.isJsonPrimitive()) {
                    remove.add(el.getAsString());
                }
            }
        }
        java.util.Map<String, Integer> recolour = new java.util.LinkedHashMap<>();
        for (String key : new String[]{"recolour", "recolor"}) {
            if (args.has(key) && args.get(key).isJsonObject()) {
                for (var e : args.getAsJsonObject(key).entrySet()) {
                    if (e.getValue().isJsonPrimitive()) {
                        recolour.put(e.getKey(), Species.parseColour(e.getValue().getAsString()));
                    }
                }
            }
        }
        // RESHAPE (G): a base part stretched or moved, built from the base part itself, so it
        // replaces it in the merge and whatever hangs on it is carried along. A part the agent also
        // sent in `parts` is its own box and wins.
        parts = com.moddingfromamod.runtime.SculptBase.keepSkin(base, parts);
        List<CreatureSculpt.Part> added = parts;
        var reshape = com.moddingfromamod.runtime.SculptReshape.apply(base, reshapeArg(args));
        if (!reshape.replacements().isEmpty()) {
            java.util.Set<String> sent = new java.util.HashSet<>();
            parts.forEach(p -> sent.add(p.name().toLowerCase(java.util.Locale.ROOT)));
            added = new java.util.ArrayList<>();
            for (CreatureSculpt.Part r : reshape.replacements()) {
                if (!sent.contains(r.name().toLowerCase(java.util.Locale.ROOT))) {
                    added.add(r);
                }
            }
            added.addAll(parts);
        }
        return com.moddingfromamod.runtime.SculptBase.merge(
                com.moddingfromamod.runtime.SculptBase.reskinned(base, skins(args)), added, remove, recolour);
    }

    /** The edit's {@code reshape} list, or null. */
    static com.google.gson.JsonArray reshapeArg(JsonObject args) {
        return args != null && args.has("reshape") && args.get("reshape").isJsonArray()
                ? args.getAsJsonArray("reshape") : null;
    }

    /** What the reshape did, said to the agent — empty when there was none. */
    static String reshapeNote(JsonObject args) {
        if (reshapeArg(args) == null || !args.has("start_from") || !args.get("start_from").isJsonPrimitive()) {
            return "";
        }
        return com.moddingfromamod.runtime.SculptReshape.apply(ModelRepository.sculptedParts(
                ModelRepository.sanitizeKey(args.get("start_from").getAsString())), reshapeArg(args)).note();
    }

    /**
     * <b>A part in {@code parts} that names a base part and sends no box CHANGES only what it sends</b>
     * (2026-10-10). "Give my Snapper a patterned shell" came back as {@code {"name":"shell",
     * "pattern":"scales"}} — the natural edit — and a part without from/to is not a part, so it was
     * dropped without a word and the creature stayed flat (the agent re-sent it, gave up, and said
     * so). Read here from the raw arguments: name → the fields it carries (pattern, pattern_colour,
     * colour). Pure.
     */
    /** Drops {@code from}/{@code to} from every part whose box is empty (from equals to). Pure. */
    static void emptyBoxesAreNameOnly(com.google.gson.JsonArray parts) {
        for (var el : parts) {
            if (el.isJsonObject() && el.getAsJsonObject().has("from")
                    && el.getAsJsonObject().get("from").equals(el.getAsJsonObject().get("to"))) {
                el.getAsJsonObject().remove("from");
                el.getAsJsonObject().remove("to");
            }
        }
    }

    public static java.util.Map<String, JsonObject> skins(JsonObject args) {
        java.util.Map<String, JsonObject> out = new java.util.LinkedHashMap<>();
        if (args == null || !args.has("parts") || !args.get("parts").isJsonArray()) {
            return out;
        }
        for (var el : args.getAsJsonArray("parts")) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            if (o.has("from") || o.has("to") || !o.has("name") || !o.get("name").isJsonPrimitive()) {
                continue;
            }
            out.put(o.get("name").getAsString().toLowerCase(java.util.Locale.ROOT), o);
        }
        return out;
    }

    /** What this creature was made from: his words, and the look of a brief when there is one. */
    private static String madeFromWords() {
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        if (run == null) {
            return "";
        }
        return run.text() + " " + com.moddingfromamod.reference.WishBriefs.lookFor(run.wishId());
    }

    /** The main-colour lever's outcome for a sculpt: the parts to build, a note, or a refusal. */
    record Kept(List<CreatureSculpt.Part> parts, String note, String refusal) {
    }

    /** -Ptestprops standinrefuse=on: lever B, the comparison arm (refuse once instead of recolouring). */
    static final String REFUSE_PROPERTY = "moddingfromamod.standinrefuse";

    static Kept standInColour(String key, List<CreatureSculpt.Part> parts) {
        try {
            String intent = com.moddingfromamod.provenance.ProvenanceContext.currentIntent();
            var character = com.moddingfromamod.reference.CharacterLineage.resolve(intent, key);
            if (character.isEmpty()) {
                return new Kept(parts, "", null);
            }
            var spec = com.moddingfromamod.reference.ReferenceResearcher.lookUp(character.get());
            var run = com.moddingfromamod.agent.AgentContext.currentRun();
            String words = run == null ? "" : run.text();
            var built = com.moddingfromamod.runtime.SculptCritique.composition(parts);
            if ("on".equalsIgnoreCase(System.getProperty(REFUSE_PROPERTY, "off"))) {
                var why = com.moddingfromamod.reference.MainColourCheck.forAgent(built, spec, character.get(), words,
                        "Re-sculpt the same id");
                return why.isPresent() && Pieces.firstTime(key, "maincolour")
                        ? new Kept(parts, "", "Not built yet. " + why.get()) : new Kept(parts, "", null);
            }
            if (!com.moddingfromamod.reference.StandInColour.on()) {
                return new Kept(parts, "", null);
            }
            var shift = com.moddingfromamod.reference.StandInColour.plan(built, spec, words);
            var turned = shift.isEmpty() ? parts
                    : com.moddingfromamod.reference.StandInColour.parts(parts, shift.get());
            return turned.equals(parts) ? new Kept(parts, "", null)   // nothing it may turn: the check advises instead
                    : new Kept(turned,
                            "\n" + com.moddingfromamod.reference.StandInColour.note(character.get(), shift.get()) + "\n",
                            null);
        } catch (RuntimeException e) {
            return new Kept(parts, "", null);   // a colour lever must never cost him the creature
        }
    }

    public static List<CreatureSculpt.Part> withKits(List<CreatureSculpt.Part> parts, JsonObject args) {
        if (args == null || !args.has("kits") || !args.get("kits").isJsonArray()
                || !com.moddingfromamod.runtime.FidelityFlags.enabled()) {
            return com.moddingfromamod.runtime.SculptLimbAdoption.adopt(
                    com.moddingfromamod.runtime.SculptHeadAdoption.adopt(
                            com.moddingfromamod.runtime.SculptOrientation.faceForward(parts)));
        }
        // A KIT IS DECORATION, NOT ANATOMY.
        //
        // Kits may not supply more than MAX_KIT_SHARE of the finished creature. Without this, a
        // 26-part lizard came back with 30 of its 56 parts prefabricated -- more than half the
        // animal was library output -- and the human called it "completely incoherent". Budgeting
        // against the hand-built parts keeps the body the thing the kits hang off.
        // ...but never zero. A pure share rule gives a small creature no budget at all -- a
        // six-part blob asking for horns got none, which is not the failure we were fixing. The
        // failure was kit SCALE (thirty parts), not ratio, so a small floor cannot reintroduce it.
        int budget = Math.max(MIN_KIT_PARTS, (int) Math.floor(parts.size()
                * (MAX_KIT_SHARE / (1.0 - MAX_KIT_SHARE))));
        List<CreatureSculpt.Part> grown = new java.util.ArrayList<>(parts);
        int added = 0;
        String faceMark = com.moddingfromamod.runtime.SculptKits.MARK + "face";
        // KITS ARE IDEMPOTENT ON AN EDIT. Measured 2026-08-30: the agent asked for
        // `spike_row count:7` on its first sculpt and asked for the identical row again on an
        // inheriting one, and Spikor shipped with FIFTEEN kit_spike_row parts -- on a creature
        // already at MAX_PARTS. It is also the main palette error, because spikes/horns/claws are
        // bone-coloured, and the duplicate row is what pushed white/grey to double its target.
        for (CreatureSculpt.Part p : com.moddingfromamod.runtime.SculptKits.expand(
                withoutDuplicateKits(args, parts), parts)) {
            if (grown.size() >= CreatureSculpt.MAX_PARTS) {
                break;
            }
            // A FACE IS EXEMPT FROM THE BUDGET. It is a small fixed set, not repeating decoration,
            // and truncating it half way removes the MOUTH -- which is exactly what happened: a
            // one-part head gave a budget of 6, a face is 9, and six moods rendered as six pairs
            // of eyebrows with nothing underneath. Half an expression is worse than none.
            // A ROW OF LEGS IS ANATOMY TOO (F1): a spider is a small body and eight legs, and a budget sized
            // off a six-box body handed it three of them -- an earlier issue's "legs from one point" spider, again.
            boolean isFace = p.name().startsWith(faceMark)
                    || p.name().startsWith(com.moddingfromamod.runtime.SculptKits.MARK + "leg_row");
            if (!isFace && added >= budget) {
                continue;
            }
            grown.add(p);
            if (!isFace) {
                added++;
            }
        }
        // LAST STEP, ON THE COMPLETE CREATURE: turn it the right way round if its own anatomy says
        // it was built backwards. After the kits, so the body and its spikes are mirrored
        // together and stay consistent with each other. Then hang anything sitting ON the head
        // from the head, so it turns when the head looks at him, and anything riding on a
        // swinging arm or leg from that limb, so a stride does not leave it in the air.
        return com.moddingfromamod.runtime.SculptLimbAdoption.adopt(
                com.moddingfromamod.runtime.SculptHeadAdoption.adopt(
                        com.moddingfromamod.runtime.SculptOrientation.faceForward(grown)));
    }

    /**
     * <b>Parts that were dropped at the ceiling</b>, said out loud.
     *
     * <p>{@code MAX_PARTS} truncates in three places — {@link CreatureSculpt#parseParts} stops
     * reading, the kit loop breaks, and {@link com.moddingfromamod.runtime.SculptBase} sublists an
     * inherited base — and none of them said anything. The cap was not in this tool's schema
     * either, so the agent could not know it existed, could not know it had hit it, and got no
     * error when it did.
     *
     * <p>The edit case is the one that bites: inherit a 60-part creature, add ten, and parts vanish
     * from the BODY with no signal — which is exactly the failure {@code REFINEMENT_LADDERS}
     * measures. Reported, never auto-fixed: something was lost, and losing it quietly is the bug
     * (docs/WISH_LINTERS.md P4).
     */
    /**
     * <b>Parts that sat on the head without hanging from it, re-parented and named back.</b>
     *
     * <p>{@link com.moddingfromamod.runtime.SculptHeadAdoption} fixes the creature either way; this
     * says so, because a silent fix leaves the agent believing it built what it sent (P4) and it
     * will send the same thing next time. Reported from the live server: a "copy of me" whose
     * face turned to look at the child while the hair box stayed
     * behind, until the back of the hair was over the face.
     *
     * <p>Pure: args in, message out. Reads the parts as SENT, since the creature it returns has
     * already been fixed and would report nothing.
     */
    static String headAdoptionNote(JsonObject args) {
        if (args == null || !args.has("parts") || !args.get("parts").isJsonArray()) {
            return "";
        }
        java.util.Map<String, String> moved = com.moddingfromamod.runtime.SculptHeadAdoption
                .adoptions(CreatureSculpt.parseParts(args.getAsJsonArray("parts")));
        if (moved.isEmpty()) {
            return "";
        }
        List<String> said = new java.util.ArrayList<>();
        for (var e : moved.entrySet()) {
            said.add("'" + e.getKey() + "' -> '" + e.getValue() + "'");
        }
        return "\n⚠ ATTACHED TO THE HEAD — " + String.join(", ", said) + ": these sit on the head "
                + "but had no 'parent' under it, so when the head turned to look at him they would "
                + "have stayed behind (hair ending up over the face). They now hang from the head. "
                + "Next time give every part that sits on the head (hair, eyes, mouth, ears, "
                + "horns, a hat) \"parent\":\"head\".\n";
    }

    /** what {@link com.moddingfromamod.runtime.SculptLimbAdoption} hung from a limb, said out loud. */
    static String limbAdoptionNote(JsonObject args) {
        if (args == null || !args.has("parts") || !args.get("parts").isJsonArray()) {
            return "";
        }
        java.util.Map<String, String> moved = com.moddingfromamod.runtime.SculptLimbAdoption
                .adoptions(CreatureSculpt.parseParts(args.getAsJsonArray("parts")));
        if (moved.isEmpty()) {
            return "";
        }
        List<String> said = new java.util.ArrayList<>();
        for (var e : moved.entrySet()) {
            said.add("'" + e.getKey() + "' -> '" + e.getValue() + "'");
        }
        return "\n⚠ ATTACHED TO A LIMB — " + String.join(", ", said) + ": these sit on an arm or "
                + "leg that swings when he walks or swipes, but had no 'parent', so they would "
                + "have stayed in the air. They now hang from it. Next time give a hand, claw, "
                + "glove or paw \"parent\" set to the limb it is on.\n";
    }

    /**
     * <b>Parts the sculpt threw away for being too thin to draw, named back to the agent.</b>
     *
     * <p>{@link CreatureSculpt#parseParts} drops any box under one unit on any axis — a flat box
     * draws nothing, so the filter is right. What was wrong is that it was <b>silent</b>: measured
     * on a real Spikor (v3_fixed6), {@code pupilL} and {@code pupilR} were authored 1.6 x 2.4 x
     * <b>0.7</b> deep and vanished. The agent had drawn Spikor's pupils, was told 41 parts went in,
     * and got a creature with 39 and no pupils — and nothing anywhere said so. It is the same
     * failure shape as the {@code MAX_PARTS} ceiling next door, which is why the fix is the same
     * one: report it, and say what to change.
     *
     * <p>Detail matters here more than anywhere else on the model, because the parts that come out
     * thinnest are eyes, pupils, teeth and claws — exactly the small features a face is read from.
     *
     * <p>Pure and unit-tested: args in, message out.
     */
    static String thinPartsNote(JsonObject args) {
        if (args == null || !args.has("parts") || !args.get("parts").isJsonArray()) {
            return "";
        }
        List<String> thin = new java.util.ArrayList<>();
        for (var el : args.getAsJsonArray("parts")) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            float[] from = CreatureSculpt.vec3(o, "from");
            float[] to = CreatureSculpt.vec3(o, "to");
            if (from == null || to == null) {
                continue;
            }
            // ABS: parseParts normalises a box sent high corner first and keeps it, so measuring
            // it signed called a 12-deep horn "under 1 deep" and told the agent it was dropped
            // (three such false alarms on one 30-run panel).
            String axis = Math.abs(to[0] - from[0]) < 1 ? "wide"
                    : Math.abs(to[1] - from[1]) < 1 ? "tall"
                    : Math.abs(to[2] - from[2]) < 1 ? "deep" : null;
            if (axis == null) {
                continue;
            }
            String name = o.has("name") && o.get("name").isJsonPrimitive()
                    ? o.get("name").getAsString() : "(unnamed)";
            thin.add(name + " (under 1 " + axis + ")");
            if (thin.size() >= 8) {
                break;
            }
        }
        if (thin.isEmpty()) {
            return "";
        }
        return "\n⚠ DROPPED FOR BEING TOO THIN TO DRAW: " + String.join(", ", thin)
                + ". A box under 1 unit on any axis is invisible, so these are NOT on the creature "
                + "even though you sent them. Make each at least 1 unit on every axis — a pupil at "
                + "0.7 deep becomes a pupil at 1 deep, sitting 0.1 proud of the eye — or drop them "
                + "on purpose and say so.\n";
    }

    static String truncationNote(JsonObject args, List<CreatureSculpt.Part> parts) {
        if (parts == null || parts.size() < CreatureSculpt.MAX_PARTS) {
            return "";
        }
        int sent = args != null && args.has("parts") && args.get("parts").isJsonArray()
                ? args.getAsJsonArray("parts").size() : 0;
        StringBuilder sb = new StringBuilder("\n⚠ AT THE " + CreatureSculpt.MAX_PARTS
                + "-PART CEILING — this creature is full");
        if (sent > CreatureSculpt.MAX_PARTS) {
            sb.append(", and you sent ").append(sent)
              .append(" parts, so the ones past the ceiling were DROPPED");
        } else {
            sb.append(", so any kit or inherited part past the ceiling was DROPPED");
        }
        sb.append(". Check the parts list below is the creature you meant. To add anything now, "
                + "remove something first — more boxes past this point are silently lost.\n");
        return sb.toString();
    }

    /**
     * <b>Kit families the creature already has</b>, when this sculpt inherits a base.
     *
     * <p>Gated on {@code start_from} deliberately. Asking for the same kit twice in ONE call is an
     * explicit choice — "two rows of spikes" is a real thing to want — but asking again on an EDIT
     * means the agent is re-describing the creature it already built, and every kit in that list
     * lands a second time. Only the second case is unambiguous, so only the second case is fixed.
     *
     * <p>Pure and unit-tested: it takes the args and the merged base, and returns names.
     */
    public static List<String> duplicateKits(JsonObject args, List<CreatureSculpt.Part> parts) {
        List<String> dup = new java.util.ArrayList<>();
        if (args == null || !args.has("start_from") || !args.get("start_from").isJsonPrimitive()
                || args.get("start_from").getAsString().isBlank()
                || !args.has("kits") || !args.get("kits").isJsonArray() || parts == null) {
            return dup;
        }
        java.util.Set<String> present = new java.util.HashSet<>();
        for (CreatureSculpt.Part p : parts) {
            String n = p.name() == null ? "" : p.name();
            if (n.startsWith(com.moddingfromamod.runtime.SculptKits.MARK)) {
                // kit_spike_row12 -> spike_row. The index is per-part; the FAMILY is what repeats.
                present.add(n.substring(com.moddingfromamod.runtime.SculptKits.MARK.length())
                        .replaceAll("\\d+$", ""));
            }
        }
        for (com.google.gson.JsonElement el : args.getAsJsonArray("kits")) {
            if (!el.isJsonObject()) {
                continue;
            }
            com.google.gson.JsonObject o = el.getAsJsonObject();
            String kit = o.has("kit") && o.get("kit").isJsonPrimitive()
                    ? o.get("kit").getAsString() : "";
            if (!kit.isBlank() && present.contains(kit) && !dup.contains(kit)) {
                dup.add(kit);
            }
        }
        return dup;
    }

    /** The kits array with the already-present families removed. */
    static com.google.gson.JsonArray withoutDuplicateKits(JsonObject args,
                                                          List<CreatureSculpt.Part> parts) {
        com.google.gson.JsonArray kits = args != null && args.has("kits")
                && args.get("kits").isJsonArray() ? args.getAsJsonArray("kits")
                : new com.google.gson.JsonArray();
        List<String> dup = duplicateKits(args, parts);
        if (dup.isEmpty()) {
            return kits;
        }
        com.google.gson.JsonArray kept = new com.google.gson.JsonArray();
        for (com.google.gson.JsonElement el : kits) {
            String kit = el.isJsonObject() && el.getAsJsonObject().has("kit")
                    && el.getAsJsonObject().get("kit").isJsonPrimitive()
                    ? el.getAsJsonObject().get("kit").getAsString() : "";
            if (!dup.contains(kit)) {
                kept.add(el);
            }
        }
        return kept;
    }

    /**
     * The most of a finished creature that may come from part-kits.
     *
     * <p>Deliberately a share rather than a count: ten kit spikes on a forty-part dragon is
     * detailing, and the same ten on a six-part blob is the whole creature.
     */
    public static final double MAX_KIT_SHARE = 0.40;

    /** Enough for a pair of horns or a set of claws, so kits stay usable on a small creature. */
    public static final int MIN_KIT_PARTS = 6;

    /**
     * <b>The durable save + publish for a sculpt</b> — extracted so it runs headless (no server, no
     * player) and is covered by a gate. Writes the baked model AND the editable {@code sculpt.json}
     * source beside it ({@link CreatureFiles#write}), then publishes the creature into the Mod Repo
     * as the {@code species} wish kind: {@code wishes/species/<id>/sculpt.json} + a provenance
     * manifest carrying the wish that produced it (and, with git-sync on, committed + pushed).
     *
     * <p>The publish is best-effort (no-op with no repo configured) and never blocks the sculpt.
     *
     * @return the model folder written, or null if the parts produced nothing drawable
     */
    /** The wish in the child's own words, or "" outside a run (a direct call, a gate). */
    static String childWordsFor() {
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        return run == null ? "" : run.text();
    }

    /**
     * <b>Which parts the declared palette is a claim about.</b>
     *
     * <p>The agent declares what it is SENDING. On an inheriting sculpt that is the two wings it is
     * adding, so grading it against the merged body reports a correct diff as a catastrophic
     * failure — and the instruction that follows ("Fix the ones marked MISSED by re-sculpting the
     * same id") can only be satisfied by rebuilding the whole creature. Measured in thirteen of
     * thirteen ladder runs.
     *
     * <p>A creation grades against everything it built, because there the two lists are the same.
     *
     * <p>Extracted as a named function so the decision is testable without a server or a network:
     * driving the whole tool pulled in a reference fetch, which returned in one run and timed out
     * in the next — a flaky ratchet test is worse than no test, and this repo already carries one.
     */
    /**
     * <b>Everything the agent is told about what it just built, assembled in one place.</b>
     *
     * <p>Extracted for the same reason as {@link #partsToGrade}: so it is testable without a server
     * or a network. But also for a reason that is worth stating, because it was nearly missed —
     * <b>a gate is not wired just because its class exists and its own unit tests pass.</b>
     * {@link com.moddingfromamod.runtime.gate.ShapeDrift} is silent whenever nothing was lost, which
     * is every healthy run, so a live run proves nothing about whether it is connected. The obvious
     * check — grepping a real client's {@code run.log} for its headline — returns zero either way,
     * because {@code run.log} carries no tool results at all. That is a dead instrument, and a
     * negative from a dead instrument is not evidence.
     *
     * <p>So the assembly is a function, and a test asserts the warning actually reaches this string.
     *
     * @param partsBefore the creature as it stood before this sculpt; empty for a creation
     * @param parts       the RESOLVED creature, base and diff merged
     * @param childWords  the wish being granted, so a change he asked for is not reported as drift
     * @param checked     the reference verdict, already rendered, or ""
     */
    static String critiqueFor(JsonObject args, List<CreatureSculpt.Part> partsBefore,
                              List<CreatureSculpt.Part> parts, String childWords, String checked) {
        List<CreatureSculpt.Part> graded = partsToGrade(args, parts);
        // THE SHAPE FIRST, then the colour. Colour continuity read 1.00 on runs where part survival
        // read 0.36 — the creature was being replaced by a different creature of the same colour,
        // and the colour gate was green the whole time it happened.
        String shape = com.moddingfromamod.runtime.gate.ShapeDrift
                .between(partsBefore, parts, childWords);
        String drift = com.moddingfromamod.runtime.gate.PaletteDrift
                .between(partsBefore, parts, childWords);
        String promised = checked + (shape.isEmpty() ? "" : shape + "\n")
                + (drift.isEmpty() ? "" : drift + "\n")
                + declaredPaletteNote(args, graded, parts);
        // AUTO-FIXED, AND SAID OUT LOUD (docs/WISH_LINTERS.md P4). A silent fix leaves the agent
        // reasoning about a creature that does not exist, which is the failure REFINEMENT_LADDERS
        // measures -- so the duplicate is dropped AND reported.
        List<String> dup = duplicateKits(args, partsBefore);
        String kits = dup.isEmpty() ? "" : "\n⚠ ALREADY THERE — you asked again for "
                + String.join(", ", dup) + ", which this creature already has, so it was NOT added "
                + "a second time. (A frog knight once shipped with fifteen back spikes this way.) If you "
                + "really want more, say so with a bigger 'count' on a fresh sculpt rather than "
                + "repeating the kit.\n";
        return promised + kits + truncationNote(args, parts) + thinPartsNote(args)
                + headAdoptionNote(args) + limbAdoptionNote(args)
                + com.moddingfromamod.runtime.SculptSides.note(parts)
                + com.moddingfromamod.runtime.SculptCritique.critique(parts, madeFromWords()).describe()
                + com.moddingfromamod.wish.WishLessons.appendOnce("creature");
    }

    /**
     * <b>An edit's palette is met if it fits what was SENT or the WHOLE creature</b>.
     *
     * <p>{@link #partsToGrade} grades an edit on its diff, because a palette describing two added
     * wings graded against the whole body read as MISSED in thirteen of thirteen ladder runs. The
     * opposite declaration happens too: an Opus big cat edited onto the cat starter declared the
     * whole creature (tan 95%) while adding two white fangs, and was told "tan: said 95%, built 0%
     * MISSED" about a creature that was 95% tan. Neither reading is the agent's drift, so an edit is
     * judged by whichever reading it fits better. A creation has one reading, its whole self.
     */
    static String declaredPaletteNote(JsonObject args, List<CreatureSculpt.Part> graded,
                                      List<CreatureSculpt.Part> whole) {
        var report = com.moddingfromamod.runtime.gate.DeclaredPalette.check(args, graded);
        List<CreatureSculpt.Part> shown = graded;
        if (inheriting(args) && !report.allMet() && whole != null) {
            var asWhole = com.moddingfromamod.runtime.gate.DeclaredPalette.check(args, whole);
            if (asWhole.missed().size() < report.missed().size()) {
                report = asWhole;
                shown = whole;
            }
        }
        return report.describe(shown);
    }

    static List<CreatureSculpt.Part> partsToGrade(JsonObject args,
                                                  List<CreatureSculpt.Part> merged) {
        return inheriting(args)
                ? CreatureSculpt.parseParts(args.getAsJsonArray("parts"))
                : merged;
    }

    /** Did this sculpt inherit a body, rather than declare one from nothing? */
    static boolean inheriting(JsonObject args) {
        return args != null && args.has("start_from") && args.get("start_from").isJsonPrimitive()
                && !args.get("start_from").getAsString().isBlank()
                && args.has("parts") && args.get("parts").isJsonArray();
    }

    public static Path persistAndPublish(String key, List<CreatureSculpt.Part> parts, JsonObject args)
            throws IOException {
        return persistAndPublish(key, parts, args, false);
    }

    /** {@code serverChanged}: the tool turned the parts after the agent sent them, so save those. */
    static Path persistAndPublish(String key, List<CreatureSculpt.Part> parts, JsonObject args,
                                  boolean serverChanged) throws IOException {
        // THE RESOLVED CREATURE, not the diff that produced it. An inheriting sculpt that saves
        // its own arguments overwrites the editable source with just the wings, and the next edit
        // takes that as its base — see SculptSpec.editable(args, id, resolved).
        String spec = serverChanged ? SculptSpec.editableResolved(args, key, parts)
                : SculptSpec.editable(args, key, parts);
        // KEEP THE ONE THIS REPLACES --, and it has to happen HERE, before the write, because
        // CreatureFiles.write overwrites the folder in place. A re-sculpt used to leave nothing to
        // go back to, so undoing "give him tiny black wings" discarded the mobs and kept the WINGS.
        // A no-op on a first sculpt, which is what keeps undoing a creation a removal.
        com.moddingfromamod.runtime.CreatureHistory.push(key,
                com.moddingfromamod.species.SpeciesRegistry.get(key));
        Path dir = CreatureFiles.write(key, parts, spec);
        if (dir == null) {
            return null;
        }
        // Capture the agent's design reasoning for the manifest (best-effort; blank if omitted).
        com.moddingfromamod.provenance.ProvenanceContext.note(
                args != null && args.has("note") && args.get("note").isJsonPrimitive()
                        ? args.get("note").getAsString() : "");
        // Publish the editable source + provenance so the creature can be upgraded to a real mob
        // later, WITHOUT having to recover the spec from truncated agent logs. Species is a
        // PUBLISHING_KIND in the Provenance Contract.
        ProvenancePublisher.publish("species", key, "sculpt.json", spec);
        return dir;
    }

    /**
     * How a creature MOVES and RESTS, said once for both creature tools (
     *). The rig draws all of it from the sculpt's own parts; what the agent decides is which
     * powers the creature gets and what its boxes are CALLED, because a wing that is not called a
     * wing does not flap.
     */
    /** Its moves — shared with {@code modify_mob}, which gives them to a REAL mob too. */
    public static final String ABILITIES = "Any of: ranged, slam, charge, summon, teleport, heal. Leave empty for a harmless "
            + "character.";

    public static final String MOVES = "HOW IT MOVES is drawn for you from its parts, no keyframes: "
            + "'flies' = it lives in the air and its WINGS BEAT while it flies (a box only flaps if its "
            + "name has 'wing' in it, or it came from the wing kit). A swimmer's TAIL, FINS and "
            + "TENTACLES sway in water and it tips nose-up or nose-down the way it swims (name them "
            + "tail / fin / tentacle). 'hops' = it HOPS everywhere instead of walking (a bunny, a "
            + "frog, a kangaroo). 'naps' = it lies down when idle and curls up ASLEEP at night (a "
            + "kitten, a sleepy puppy). Any creature told to stay (modify_mob stay) SITS while it "
            + "stays.";

    /**
     * What each instinct DOES, in the child's terms. The old text explained one of the five
     * ("a friendly mascot … should be 'companion'"), so an agent wanting HARMLESS picked companion,
     * and two pickleball players walked off their court to follow the child. Shared with
     * {@code define_species}; pinned by {@code AgentKnowsTheTrapsTest}.
     */
    public static final String INSTINCTS =
            "What it does on its own. wild (the default) = roams and minds its own business. "
                    + "guardian = fights monsters near it, never a player. hunter = hunts players "
                    + "and animals. coward = runs away from players. companion = FOLLOWS the "
                    + "nearest player EVERYWHERE. Use companion ONLY when he asked for it to follow "
                    + "him or asked for his pet or sidekick (\"my pet\", \"follows me\", \"a buddy "
                    + "that comes with me\"). To make it HARMLESS use the 'friendly' power, NOT "
                    + "companion: creatures that belong to a PLACE (a band on a stage, fish in "
                    + "a pond, shopkeepers in a market) are wild + friendly, so they stay where you "
                    + "put them.";

    @Override
    public ToolResult call(JsonObject args) {
        boolean edit = args != null && args.has("start_from") && args.get("start_from").isJsonPrimitive()
                && !args.get("start_from").getAsString().isBlank();
        // AN EMPTY BOX ON AN EDIT MEANS "KEEP ITS BOX": Spark re-skins a base part as
        // {"name":"body","from":[0,0,0],"to":[0,0,0],"pattern":…} in 6 of 76 routing edits, and
        // that box would replace the body. Read it as the name-only re-skin it means.
        if (edit && args.has("parts") && args.get("parts").isJsonArray()) {
            emptyBoxesAreNameOnly(args.getAsJsonArray("parts"));
            com.moddingfromamod.runtime.SculptBase.placeholdersAreNameOnly(ModelRepository.sculptedParts(
                    ModelRepository.sanitizeKey(args.get("start_from").getAsString())), args.getAsJsonArray("parts"));
        }
        // TOO THIN TO DRAW IS FIXED HERE, not sent back (SculptThickness): the fix was
        // mechanical and cost a re-sculpt in 8 of 11 Opus redos on the baseline panel.
        List<String> thickened = args != null && args.has("parts") && args.get("parts").isJsonArray()
                ? com.moddingfromamod.runtime.SculptThickness.thicken(args.getAsJsonArray("parts"))
                : List.of();
        // A FACE SUNK JUST INSIDE THE HEAD IS BROUGHT FORWARD (SculptFaceFront): 14 of 16
        // Spark redos on the same panel followed a BURIED face. A creation only: on an edit the
        // parts sent are a diff, and the face they would be judged against is the base's.
        boolean creation = args != null && !(args.has("start_from") && args.get("start_from").isJsonPrimitive()
                && !args.get("start_from").getAsString().isBlank());
        var faced = creation && args.has("parts") && args.get("parts").isJsonArray()
                ? com.moddingfromamod.runtime.SculptFaceFront.apply(args.getAsJsonArray("parts"))
                : new com.moddingfromamod.runtime.SculptFaceFront.Result(List.of(), 0f);
        // FACE PARTS AN EDIT SENT TO THE FAR END OF ITS BASE go to the face end (SculptSides):
        // "the teeth are on the tail", a reviewer's grade of an Opus big cat built on the cat starter.
        List<String> toFace = !creation && args.has("parts") && args.get("parts").isJsonArray()
                ? com.moddingfromamod.runtime.SculptSides.onTheFace(args.getAsJsonArray("parts"),
                        ModelRepository.sculptedParts(ModelRepository.sanitizeKey(
                                args.get("start_from").getAsString())))
                : List.of();
        // A PAIR A UNIT OFF ITS MIRROR is mirrored, not sent back (SculptSymmetry).
        List<String> mirrored = args != null && args.has("parts") && args.get("parts").isJsonArray()
                ? com.moddingfromamod.runtime.SculptSymmetry.apply(args.getAsJsonArray("parts"))
                : List.of();
        ToolResult r = callChecked(args);
        String said = com.moddingfromamod.runtime.SculptThickness.note(thickened) + faced.note()
                + com.moddingfromamod.runtime.SculptSymmetry.note(mirrored)
                + com.moddingfromamod.runtime.SculptSides.movedNote(toFace)
                + (creation ? "" : reshapeNote(args));
        return said.isEmpty() || r.isError() ? r
                : new ToolResult(r.text() + said, false, r.imageBase64(), r.imageMime());
    }

    private ToolResult callChecked(JsonObject args) {
        // ── COPY-ON-REFINE, BEFORE ANYTHING IS WRITTEN. Re-sculpting reuses the id,
        // and SpeciesRegistry.put keeps the owner's stamp while it REPLACES the body — so a child
        // re-sculpting his brother's Spikor, or the curated one, rewrote it for everyone. modify_mob
        // has asked ForkPolicy for this from the start; this path never did (ChildForkGameTest, red:
        // "Bob's wings are on ALICE's Spikor"). The decision is ForkPolicy's; this only moves the
        // write to the key it names, so the model files under the original are never touched.
        String rawId = args.has("id") && args.get("id").isJsonPrimitive()
                ? args.get("id").getAsString() : "";
        String key = ModelRepository.sanitizeKey(rawId);
        Species existing = key.isEmpty() ? null : SpeciesRegistry.get(key);
        if (existing == null) {
            return callResolved(args, "");
        }
        String asker = com.moddingfromamod.agent.AgentContext.currentPlayerId();
        var decision = com.moddingfromamod.species.CreatureFork.decide(existing, asker);
        if (decision == com.moddingfromamod.marshal.ForkPolicy.Decision.REFUSE) {
            return ToolResult.error("'" + key + "' is not this child's to change, and with no child "
                    + "bound to this wish there is nobody to make a copy for. Nothing was written.");
        }
        if (decision != com.moddingfromamod.marshal.ForkPolicy.Decision.FORK) {
            ToolResult edited = callResolved(args, "");
            if (!edited.isError()) {
                // a re-sculpt of a creature an earlier wish made is an EDIT of his creature.
                com.moddingfromamod.agent.CreatureEdits.record(new com.moddingfromamod.agent
                        .CreatureEdits.Edit(key, key, existing.name(), nameIn(args, existing.name()),
                        false, true));
            }
            return edited;
        }
        String forkKey = com.moddingfromamod.marshal.ForkPolicy.keyFor(decision, key, asker);
        JsonObject forked = args.deepCopy();
        boolean hasBase = forked.has("start_from") && forked.get("start_from").isJsonPrimitive();
        boolean forkExists = SpeciesRegistry.has(forkKey);
        if (!forkExists && !hasBase) {
            // Inherit the body, or an edit that names one part leaves him a lone wing.
            forked.addProperty("start_from", key);
        } else if (forkExists && hasBase && key.equals(
                ModelRepository.sanitizeKey(forked.get("start_from").getAsString()))) {
            // His SECOND refinement builds on his own copy, not on the original again.
            forked.addProperty("start_from", forkKey);
        }
        if (!forked.has("name") && existing.name() != null) {
            forked.addProperty("name", existing.name());
        }
        forked.addProperty("id", forkKey);
        // THE ONE IN FRONT OF HIM BECOMES HIS COPY. A first fork is a new id, so the
        // sculpt below would treat it as a creation and spawn it: "give my dragon horns" left him
        // two dragons, the old one unchanged. So, as modify_mob and paint_creature do, the
        // creature he means — his own nearest, else the nearest (CreaturePaint.nearestTo) — is
        // moved onto the fork instead, and nothing is spawned. With none near, or when the agent
        // explicitly asked to spawn (spawn:true), the fork is spawned as before; spawn:false means
        // "no NEW one", so the one he means still becomes his.
        LivingEntity his = null;
        boolean askedToSpawn = forked.has("spawn") && forked.get("spawn").isJsonPrimitive()
                && forked.get("spawn").getAsBoolean();
        if (!forkExists && !askedToSpawn) {
            try {
                his = AgentBridge.onServer(() -> {
                    var server = AgentBridge.serverOrNull();
                    return server == null ? null : com.moddingfromamod.runtime.CreaturePaint
                            .nearestTo(key, com.moddingfromamod.agent.ActingChild.person(server));
                });
            } catch (AgentBridge.Unavailable e) {
                his = null;
            }
        }
        if (his != null) {
            forked.addProperty("spawn", false);
        }
        String note = "\n\n'" + key + "' is not this child's — it belongs to "
                + (existing.ownerId().isEmpty() ? "the shared library" : "another child")
                + ", so this change made HIS OWN copy, '" + forkKey + "', and the original is "
                + "untouched."
                + (his == null ? "" : " The " + existing.name() + " standing nearest him is now "
                        + "his copy and changes in a moment; nothing new was spawned.")
                + " Use '" + forkKey + "' for his next change.";
        ToolResult out = callResolved(forked, note);
        if (his != null && !out.isError()) {
            LivingEntity moving = his;
            AgentBridge.onServer(() -> {
                com.moddingfromamod.mob.Creatures.wearSpecies(moving, forkKey);
                return null;
            });
        }
        if (!out.isError()) {
            com.moddingfromamod.agent.CreatureEdits.record(new com.moddingfromamod.agent.CreatureEdits
                    .Edit(forkKey, key, existing.name(), nameIn(forked, existing.name()), true, true));
        }
        return out;
    }

    /** The sculpt's {@code name}, or {@code fallback} when it names none. */
    static String nameIn(JsonObject args, String fallback) {
        return args != null && args.has("name") && args.get("name").isJsonPrimitive()
                && !args.get("name").getAsString().isBlank()
                ? args.get("name").getAsString().trim() : fallback;
    }

    /**
     * <b>Refused once, per wish, per creature, per set of loose parts.</b>
     *
     * <p>The second call with the same pieces still apart goes through: a gap the agent keeps is
     * one it meant (a halo, a floating crown), and a wish must never be trapped by a check.
     */
    static final class Pieces {
        private static final java.util.Set<String> SEEN =
                java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());
        private static final int KEEP = 2000;

        static boolean firstTime(String creature, String fingerprint) {
            var run = com.moddingfromamod.agent.AgentContext.currentRun();
            String k = (run == null ? "-" : run.wishId()) + "|" + creature + "|" + fingerprint;
            synchronized (SEEN) {
                if (SEEN.size() > KEEP) {
                    SEEN.remove(SEEN.iterator().next());
                }
                return SEEN.add(k);
            }
        }
    }

    static String piecesRefusal(com.moddingfromamod.runtime.SculptConnectivity.Result r) {
        StringBuilder sb = new StringBuilder("NOT SCULPTED YET — this creature is in "
                + (r.islands().size() + 1) + " pieces. A child would see these hanging in the "
                + "air beside it:\n");
        for (var i : r.islands()) {
            sb.append("  - ").append(i.describe()).append('\n');
        }
        sb.append("Make each one OVERLAP the part it belongs to by a pixel or two (touching edges "
                + "can open up when the creature moves), then sculpt again. If a gap is "
                + "deliberate — something meant to float — send the same sculpt again unchanged "
                + "and it will go through.");
        return sb.toString();
    }

    /**
     * F1 grading (2026-11-09: "in many cases the parts were disjoint"): a TURNED part that passes the one-piece
     * check on its bounds but stands apart once turned. Advice only — the sculpt is kept as sent, so a part meant to
     * float, or a lopsided design, is never corrected into something duller.
     */
    static String looksApartNote(List<CreatureSculpt.Part> parts) {
        List<String> apart = com.moddingfromamod.runtime.KitLint.looksApart(parts, 1.0, 3);
        if (apart.isEmpty()) {
            return "";
        }
        return "\nLOOKS APART (advice, kept as sent): " + String.join("; ", apart)
                + ". A turned box swings about its pivot; if it should be joined, move it a pixel or two into the "
                + "part it belongs to or turn it about the joint.\n";
    }

    static String snappedNote(com.moddingfromamod.runtime.SculptConnectivity.Result r) {
        String warned = r.onePiece() ? "" : "\n⚠ STILL IN PIECES (you sent it again, so it was "
                + "kept):\n" + r.islands().stream().map(i -> "  - " + i.describe())
                .collect(java.util.stream.Collectors.joining("\n")) + "\n";
        if (r.snapped().isEmpty()) {
            return warned;
        }
        return warned + "\nAUTO-FIXED — closed a hairline gap so the creature is one piece: "
                + r.snapped().stream().map(i -> String.join(", ", i.parts()) + " onto "
                        + i.nearest()).collect(java.util.stream.Collectors.joining("; "))
                + ". If that gap was meant, say so and move it further apart.\n";
    }

    /** The replaced parts of a start_from edit that changed size a lot while others hang on them. */
    static List<com.moddingfromamod.runtime.SculptBase.Reshape> reshapes(JsonObject args) {
        if (args == null || !args.has("start_from") || !args.get("start_from").isJsonPrimitive()
                || !args.has("parts") || !args.get("parts").isJsonArray()) {
            return List.of();
        }
        List<CreatureSculpt.Part> base = ModelRepository.sculptedParts(
                ModelRepository.sanitizeKey(args.get("start_from").getAsString()));
        return com.moddingfromamod.runtime.SculptBase.reshaped(base,
                CreatureSculpt.parseParts(args.getAsJsonArray("parts")), removeSet(args));
    }

    static String reshapeRefusal(String base, List<com.moddingfromamod.runtime.SculptBase.Reshape> rs) {
        StringBuilder sb = new StringBuilder("NOT SCULPTED YET — this is a new SHAPE, not an edit of "
                + base + ". You replaced parts with boxes of a very different size:\n");
        for (var r : rs) {
            sb.append("  - ").append(r.describe(base)).append('\n');
        }
        sb.append("start_from keeps every part you do not send, where it was, so those parts would "
                + "have to be dragged onto your new box. Decide what you meant: keep the base's box and "
                + "only adjust it (its size and place are above), or send the parts that hang on it "
                + "too, placed on your new box. If this size is really what you want, send the same "
                + "sculpt again unchanged and they will be moved onto it as they are.");
        // THE RESHAPE THAT SAYS IT: computed from the two boxes, so the agent sees the edit
        // form of what it just did — and a reshape keeps the part's skin, which a new box drops.
        List<String> lines = new java.util.ArrayList<>();
        for (var r : rs) {
            String line = r.asReshape();
            if (line != null) {
                lines.add(line);
            }
        }
        if (!lines.isEmpty()) {
            sb.append("\nIf you meant the base's part at a new size, leave its box out of 'parts' and send "
                    + "\"reshape\": [").append(String.join(", ", lines)).append("] — it keeps the part's "
                    + "colour and skin, and what hangs on it moves with it.");
        }
        return sb.toString();
    }

    private static java.util.Set<String> removeSet(JsonObject args) {
        java.util.Set<String> remove = new java.util.LinkedHashSet<>();
        if (args.has("remove") && args.get("remove").isJsonArray()) {
            for (var el : args.getAsJsonArray("remove")) {
                if (el.isJsonPrimitive()) {
                    remove.add(el.getAsString());
                }
            }
        }
        return remove;
    }

    /** "Moved with what they hang on" — said out loud, because a silent fix is a creature the agent cannot see. */
    static String carriedNote(JsonObject args) {
        if (args == null || !args.has("start_from") || !args.get("start_from").isJsonPrimitive()
                || !args.has("parts") || !args.get("parts").isJsonArray()) {
            return "";
        }
        List<CreatureSculpt.Part> base = ModelRepository.sculptedParts(
                ModelRepository.sanitizeKey(args.get("start_from").getAsString()));
        java.util.Set<String> remove = new java.util.LinkedHashSet<>();
        if (args.has("remove") && args.get("remove").isJsonArray()) {
            for (var el : args.getAsJsonArray("remove")) {
                if (el.isJsonPrimitive()) {
                    remove.add(el.getAsString());
                }
            }
        }
        var moved = com.moddingfromamod.runtime.SculptBase.carried(base,
                CreatureSculpt.parseParts(args.getAsJsonArray("parts")), remove);
        if (moved.isEmpty()) {
            return "";
        }
        return "\nAUTO-FIXED — you replaced parts that others hang on, so " + moved.size()
                + " part(s) moved with them and still attach: "
                + String.join(", ", moved.keySet()) + ". If you wanted any of them somewhere "
                + "else, send them too.\n";
    }

    /**
     * The voice this call gives it (chunk 4): {@code voice} names the mob, {@code sounds}
     * replaces moments; neither named keeps the {@code start_from} base's, which {@code inherited}
     * carries. Pure but for the parse.
     */
    static com.moddingfromamod.species.Voice voiceOf(JsonObject args, JsonObject inherited) {
        com.moddingfromamod.species.Voice base = com.moddingfromamod.species.Voice.parse(
                args.has("voice") ? args.get("voice") : inherited.get("voice"));
        if (!args.has("sounds") || !args.get("sounds").isJsonObject() || base.silent()) {
            return base;
        }
        java.util.Map<com.moddingfromamod.species.Voice.Slot, String> sounds =
                new java.util.EnumMap<>(com.moddingfromamod.species.Voice.Slot.class);
        sounds.putAll(base.sounds());
        JsonObject over = new JsonObject();
        over.addProperty("from", base.from());
        for (var e : args.getAsJsonObject("sounds").entrySet()) {
            over.add(e.getKey(), e.getValue());
        }
        var more = com.moddingfromamod.species.Voice.parse(over);
        sounds.putAll(more.sounds());
        return new com.moddingfromamod.species.Voice(base.from(), sounds, false);
    }

    /** What it sounds like, said while the agent can still change it — or that it is silent. */
    static String voiceNote(Species sp) {
        var v = sp.voice();
        if (v.silent()) {
            return "VOICE: silent, as asked — no sound at all, not even footsteps.";
        }
        if (v.isNone()) {
            return "⚠ IT IS SILENT: you gave it no voice, so it makes no sound at all — no growl, no cry "
                    + "when it is hit, no footsteps. Unless the wish asks for a quiet creature, give it "
                    + "one: sculpt_creature start_from '" + sp.id() + "' voice '<a mob that sounds "
                    + "right, e.g. wolf, ravager, parrot>' (and next time, in the same call).";
        }
        var r = com.moddingfromamod.vanilla.RigVoice.resolve(v);
        StringBuilder b = new StringBuilder("VOICE: ");
        b.append(v.from().isEmpty() ? "its own sounds" : "a " + v.from().replace('_', ' ') + "'s")
                .append(" — ");
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (var slot : com.moddingfromamod.species.Voice.Slot.values()) {
            String id = r.get(slot);
            if (id != null) {
                parts.add(slot.key() + " " + id.replace("minecraft:", ""));
            } else if (slot == com.moddingfromamod.species.Voice.Slot.STEP && r.blockSteps()) {
                parts.add("steps: the ground's own");
            } else {
                parts.add(slot.key() + ": none");
            }
        }
        b.append(String.join(", ", parts));
        if (sp.scale() >= 1.5 || sp.scale() <= 0.7) {
            b.append(". At its size it is pitched ")
                    .append(sp.scale() >= 1.5 ? "deeper" : "higher").append(" than the mob's own");
        }
        return b.append('.').toString();
    }

    private ToolResult callResolved(JsonObject args, String forkNote) {
        String rawId = args.has("id") && args.get("id").isJsonPrimitive()
                ? args.get("id").getAsString() : "";
        String key = ModelRepository.sanitizeKey(rawId);
        if (key.isEmpty()) {
            return ToolResult.error("A creature needs an 'id' made of letters, numbers or _.");
        }
        boolean hasBase = args.has("start_from") && args.get("start_from").isJsonPrimitive();
        // HOW IT SOUNDS (chunk 4), checked before anything is saved: a voice the game does
        // not have is silence in the client however it was logged, so it is refused by name.
        com.moddingfromamod.species.Voice voice;
        try {
            voice = voiceOf(args, inheritSpecies(args));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        if (!voice.isNone()) {
            var heard = com.moddingfromamod.vanilla.RigVoice.resolve(voice);
            if (!heard.ok()) {
                return ToolResult.error("Nothing was sculpted: its voice is not one this game has — "
                        + String.join("; ", heard.problems()) + ".");
            }
        }

        // HOW IT LIVES: a mob this game does not have is refused by name, like a voice.
        String behavesRefused = resolveBehavesLike(args);
        if (!behavesRefused.isEmpty()) {
            return ToolResult.error("Nothing was sculpted: " + behavesRefused);
        }

        // POWERS IT DOES NOT HAVE: an unknown word was dropped in silence, so "guardian"
        // as a power made a creature with no instinct, reported as sculpted.
        String unknownPowers = Species.Power.unknownWords(args.get("powers"));
        if (!unknownPowers.isEmpty()) {
            return ToolResult.error("Nothing was sculpted: " + unknownPowers);
        }

        // WHAT IT THROWS: an item the game does not have would be a snowball however it was
        // logged, so it is refused by name, like a voice.
        if (args.has("throws") && args.get("throws").isJsonPrimitive()) {
            String t = Species.throwsItem(args.get("throws").getAsString());
            var rl = t.isEmpty() ? null : net.minecraft.resources.ResourceLocation.tryParse(t);
            if (!t.isEmpty() && (rl == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(rl))) {
                return ToolResult.error("Nothing was sculpted: 'throws' names '" + args.get("throws").getAsString()
                        + "', which is not an item this game has — try 'minecraft:fire_charge' for fireballs.");
            }
        }

        if ((!args.has("parts") || !args.get("parts").isJsonArray()) && !hasBase) {
            return ToolResult.error("A sculpted creature needs a 'parts' list of boxes — or a "
                    + "'start_from' id to inherit a body from.");
        }

        // Base first, so a kit can hang off a bone the creature INHERITED ("spikes on my dragon's
        // back" works without re-declaring the back).
        List<CreatureSculpt.Part> assembled = withKits(
                withBase(CreatureSculpt.parseParts(args.getAsJsonArray("parts")), args), args);
        if (assembled.isEmpty()) {
            return ToolResult.error("None of those parts were usable. Each needs a 'name' and "
                    + "'from'/'to' corners at least 1 apart, e.g. "
                    + "{\"name\":\"body\",\"from\":[-4,12,-2],\"to\":[4,24,2],\"colour\":\"yellow\"}.");
        }
        // A NEW SHAPE, NOT AN EDIT (a reviewer's grades): a replaced part that shrank or grew a
        // lot is sent back once rather than having its ears and legs carried onto it, because the
        // mistake is the shape and carrying only hides it. The same sculpt again goes through.
        var reshaped = reshapes(args);
        if (!reshaped.isEmpty() && Pieces.firstTime(key, "reshape:" + reshaped.stream()
                .map(com.moddingfromamod.runtime.SculptBase.Reshape::name).sorted().toList())) {
            return ToolResult.error(reshapeRefusal(args.get("start_from").getAsString(), reshaped));
        }
        // ONE PIECE. Hairline gaps are closed here and reported; a real gap is sent back
        // once, so the agent fixes it before a child sees ears hanging beside the head. The same
        // creature sent again goes through with the warning, so a wish is never trapped.
        var connected = com.moddingfromamod.runtime.SculptConnectivity.check(assembled);
        if (!connected.onePiece() && Pieces.firstTime(key, connected.fingerprint())) {
            return ToolResult.error(piecesRefusal(connected));
        }
        // a named character keeps its MAIN colour. Lever A turns it server-side (default);
        // lever B, the comparison arm, refuses the first such build once instead.
        var keep = standInColour(key, connected.parts());
        if (keep.refusal() != null) {
            return ToolResult.error(keep.refusal());
        }
        final List<CreatureSculpt.Part> parts = keep.parts();
        forkNote = carriedNote(args) + snappedNote(connected) + looksApartNote(parts) + keep.note() + forkNote;
        // EVERY CREATURE LIVES A CHOSEN LIFE: none named is chosen for it, a flier never lives on the ground.
        // Before the species is built from args below, so the settled word is the one it keeps.
        String behavesNote = settleBehaviour(args, parts);

        // WHAT IT LOOKED LIKE BEFORE, captured before the write overwrites it. This is the whole-
        // body check that survives grading the edit on the edit — see PaletteDrift.
        List<CreatureSculpt.Part> partsBefore = ModelRepository.sculptedParts(key);

        // Write the shape and skin BEFORE registering the species: a creature that resolves to a
        // model which isn't on disk yet renders as nothing, and the child sees an empty patch of air.
        // This also saves the editable source spec and publishes the creature as a 'species' wish.
        Path dir;
        try {
            dir = persistAndPublish(key, parts, args, !keep.note().isEmpty());
        } catch (IOException e) {
            return ToolResult.error("Couldn't save the creature's model: " + e);
        }
        if (dir == null) {
            return ToolResult.error("Those parts didn't make a drawable shape — are the boxes "
                    + "too big? Keep coordinates between " + CreatureSculpt.MIN_COORD + " and "
                    + CreatureSculpt.MAX_COORD + ".");
        }

        // Build the species on top of whatever stats were given, pointing at the sculpted model.
        // START_FROM INHERITS THE CREATURE, NOT ONLY ITS BODY (an edit film, 2026-10-22): the
        // schema says "you inherit its whole body — then send only what CHANGED", and the species
        // was rebuilt from this call's arguments alone, so "make my Spikor bigger" sent no name
        // and no powers and he became "Wish Creature" with none, until the agent noticed and sent
        // them again. The base's own record goes underneath; anything this call names wins.
        JsonObject speciesJson = inheritSpecies(args);
        speciesJson.addProperty("id", key);
        speciesJson.addProperty("modelKey", key);
        speciesJson.remove("parts");
        if (speciesJson.has("knockback_resistance")) {   // the tool's word for the record's field
            speciesJson.add("knockbackResistance", speciesJson.remove("knockback_resistance"));
        }
        speciesJson.remove("spawn");
        speciesJson.remove("sounds");
        speciesJson.remove("voice");
        if (voice.toJson() != null) {
            speciesJson.add("voice", voice.toJson());
        }
        if (args.has(com.moddingfromamod.species.BehavesLike.ARG)) {
            // Resolved above to an archetype's key; with start_from and none named, the base's life stays.
            speciesJson.addProperty("behaves", args.get(com.moddingfromamod.species.BehavesLike.ARG).getAsString());
        }
        // a number it was not given comes from its size (#100): measured on the body it was just given
        Species species = Species.fromJson(speciesJson, com.moddingfromamod.runtime.CreatureSculpt.volume(parts));
        if (species == null) {
            return ToolResult.error("Couldn't build the creature's stats.");
        }

        // Whether the agent EXPLICITLY asked to spawn (true/false), or left it to us (null).
        Boolean explicitSpawn = (args.has("spawn") && args.get("spawn").isJsonPrimitive())
                ? args.get("spawn").getAsBoolean() : null;
        int partCount = parts.size();
        // a spot in a building — wait, off the server thread, until the building is up.
        String seatAt = args.has("at") && args.get("at").isJsonPrimitive() ? args.get("at").getAsString() : "";
        com.moddingfromamod.vanilla.SpotSeat.awaitBuilt(seatAt, 15_000L);

        try {
            // The declared palette is compared FIRST, because it is the only feedback that says
            // anything about a named character. The child's own words carry no colours for
            // "Make SPIKOR king of the Kroaks!", so without this the whole named tier gets built
            // with no check of any kind on whether it came out as planned.
            // REMEMBER WHICH CHARACTER THIS IS, and check the build against the real thing.
            //
            // The wish names the character; the follow-up ("make him bigger") will not, so it is
            // recorded here rather than re-derived later -- the cache would otherwise be missed at
            // exactly the moment it is finally warm.
            String intent = com.moddingfromamod.provenance.ProvenanceContext.currentIntent();
            var character = com.moddingfromamod.reference.CharacterLineage.resolve(intent, key);
            character.ifPresent(c ->
                    com.moddingfromamod.reference.CharacterLineage.remember(key, c));

            // If the reference has landed, say plainly whether this looks like the real thing.
            // Arithmetic, not an opinion: the sourced palette and the built one use the same
            // buckets, so the comparison is a subtraction.
            String checked = "";
            if (character.isPresent()) {
                var spec = com.moddingfromamod.reference.ReferenceResearcher
                        .lookUp(character.get());
                if (!spec.isEmpty()) {
                    // THE CHILD'S OWN WORDS, so a sourced palette cannot argue with the person
                    // who asked. Taken from the run this sculpt belongs to; "" when there is no
                    // run (a direct call, a gate), which simply restores the old behaviour.
                    var run = com.moddingfromamod.agent.AgentContext.currentRun();
                    String childWords = run == null ? "" : run.text();
                    var verdict = com.moddingfromamod.reference.ReferenceVerdict
                            .of(parts, spec, character.get(), childWords);
                    // the MAIN colour, to the agent and before the reveal. The verdict below
                    // speaks to the child; a lavender Puffo shipped with it in hand.
                    String mainColour = com.moddingfromamod.reference.MainColourCheck.forAgent(
                            com.moddingfromamod.runtime.SculptCritique.composition(parts), spec, character.get(),
                            childWords, "Re-sculpt the same id").map(m -> m + "\n").orElse("");
                    checked = mainColour + verdict.childFriendly() + "\n"
                            + (verdict.followUpWish().isBlank() ? ""
                                    : "Suggest this to the child as the next wish: \""
                                            + verdict.followUpWish() + "\"\n");
                }
                // SHAPE GETS THE SAME TREATMENT COLOUR ALREADY HAD, and it took two failed runs
                // to earn. Telling the agent "every part named must exist" and then "in
                // particular, this character has a beak" changed nothing: Omega Lad's reference
                // was delivered on four cache hits and the agent built shades and a collar twice.
                // Colour was followed exactly in both, and colour is the axis that had a CHECK
                // rather than an instruction.
                if (!spec.isEmpty()
                        && spec.confidence() != com.moddingfromamod.reference.ReferenceSpec
                                .Confidence.LOW) {
                    // `parts` here is the EXPANDED list (withKits, line ~585) and a kit part is
                    // named `kit_<kit>` -- so a spike row the agent asked for as a kit rather than
                    // drawing box by box still puts "spike" in the haystack. Checked on a real
                    // Spikor carrying {kit:horn} and three {kit:spike_row}: without this the check
                    // would report "horn, spike missing" about a creature that has both, and a
                    // warning that fires on a good sculpt is a warning nobody reads.
                    List<String> names = new java.util.ArrayList<>();
                    for (var pt : parts) {
                        names.add(pt.name());
                    }
                    String gap = com.moddingfromamod.reference.ReferencePartCheck.report(
                            spec.parts(), names, character.get());
                    if (!gap.isBlank()) {
                        checked = checked + gap + "\n";
                    }
                    // (2026-11-19): HOW IT STANDS AND ITS TAIL. Tabbycat and Snuffy came out on all
                    // fours from "a cat", "a dog"; Pippo Rabbit with a long tail.
                    List<com.moddingfromamod.reference.ReferenceBodyCheck.Box> boxes = new java.util.ArrayList<>();
                    for (var pt : parts) {
                        boxes.add(new com.moddingfromamod.reference.ReferenceBodyCheck.Box(pt.name(), pt.from(), pt.to()));
                    }
                    // A worker on a brief is judged against what its brief told it (the pictures' stance can
                    // differ from the researcher's text: Scruff-Pup).
                    var curRun = com.moddingfromamod.agent.AgentContext.currentRun();
                    var told = com.moddingfromamod.reference.WishBriefs.bodyFor(curRun == null ? null : curRun.wishId());
                    String stance = com.moddingfromamod.reference.ReferenceBodyCheck.report(
                            told != null ? told.stance() : spec.stance(), told != null ? told.tail() : spec.tail(),
                            boxes, character.get());
                    if (!stance.isBlank()) {
                        checked = checked + stance + "\n";
                    }
                }
            }

            // describe(parts), not describe(): the report NAMES the boxes to repaint. A
            // percentage on its own is a true sentence that does not say what to do, and four
            // creatures were told their colours had missed without one of them correcting it.
            // GRADE THE EDIT, NOT THE WHOLE.
            //
            // The declared palette describes what the agent SENT. On an inheriting sculpt that is
            // the two wings it is adding — so grading it against the merged body reports a correct
            // diff as a catastrophic failure, and tells the agent to "fix the ones marked MISSED by
            // re-sculpting the same id". Measured: it did exactly that, in every ladder run, and
            // rebuilding the whole body from scratch is the only action that satisfies the check.
            //
            // A creation still grades against everything it built, because there the two are the
            // same list.
            String critique = critiqueFor(args, partsBefore, parts, childWordsFor(), checked);
            return ToolResult.ok(critique + forkNote + (behavesNote.isEmpty() ? "" : "\n\n" + behavesNote)
                    + "\n\n" + AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                // A re-sculpt of an id that already exists is a FIX iteration: the put updates every
                // instance already in the world in place, so we must NOT spawn ANOTHER copy by
                // default — that is how tweaking a look once stacked up 3-4 identical creatures. First
                // sculpt spawns; a re-edit doesn't, unless the agent explicitly says spawn:true.
                boolean reEdit = SpeciesRegistry.has(species.id());
                boolean spawn = explicitSpawn != null ? explicitSpawn : !reEdit;

                // "MAKE IT BIGGER" HAS A CEILING. A real follow-up took a shark from 2.75
                // to 12.00 blocks in one step -- with the part count unchanged at 37, so nothing
                // was added, the scale was simply multiplied. Twelve blocks does not fit in a room
                // and the camera cannot frame it, so the agent's own photograph comes back as a
                // wall of colour it cannot judge.
                Species previous = SpeciesRegistry.get(species.id());
                var growth = com.moddingfromamod.runtime.CreatureGrowth.decide(
                        com.moddingfromamod.runtime.Framing.modelHeightUnits(parts),
                        species.scale(), previous == null ? 0 : previous.scale());
                Species sized = growth.capped()
                        ? species.with(growth.scale(), null, null, null, null, null) : species;
                Species stored = SpeciesRegistry.put(server, sized);
                // record WHICH PATH made this creature. The flag is read here, at the mint,
                // rather than at world load -- a world opened once on the rig path and never
                // wished in contains no rigs and must not claim to.
                // Always the vanilla path retired the legacy renderer: there is
                // one implementation now, so the mark records WHICH it was rather than which of
                // two it was. It still matters -- a world opened by an older jar reads it.
                com.moddingfromamod.lite.MintStore.stamp(server, "species", true);
                if (stored == null) {
                    return "Couldn't save that creature (too many already?).";
                }
                // Tell every client, so the renderer and the spawn-egg menu both learn about it.
                com.moddingfromamod.net.ModdedClient.broadcastSpecies();

                // Say how to REFER to it, immediately. Sculpting used to hand back a name and
                // nothing else, so the next tool call had to guess at a selector — four minutes of
                // a live wish went that way, and a datapack compile died deriving a scoreboard
                // objective from "§6SPIKOR, King of the Kroaks". See WishMobTags.
                StringBuilder sb = new StringBuilder("Sculpted '" + stored.id() + "' ("
                        + stored.name() + ") from " + partCount + " coloured parts, scale " + stored.scale() + "."
                        + " Select it with "
                        + com.moddingfromamod.entity.WishMobTags.selector(stored.id())
                        + " — NEVER by name. Call inspect_creature '" + stored.id()
                        + "' for its real height and where its mouth and eyes are.");
                // #101: what its numbers mean, and a guard that cannot hit is caught before the reveal
                double bodyVolume = com.moddingfromamod.runtime.CreatureSculpt.volume(parts);
                sb.append(' ').append(com.moddingfromamod.species.PowerLine.of(stored, bodyVolume));
                String zeroHit = com.moddingfromamod.species.PowerLine.zeroHitFlag(stored, bodyVolume);
                if (!zeroHit.isEmpty()) {
                    sb.append(' ').append(zeroHit);
                }
                String farAbove = com.moddingfromamod.species.PowerLine.farAbove(stored, bodyVolume);
                if (!farAbove.isEmpty()) {
                    sb.append(' ').append(farAbove);
                }

                ServerPlayer player = com.moddingfromamod.agent.ActingChild.of(server);
                if (spawn && player != null) {
                    // A creature standing in the world is visible — the build boundary. See BuildGate.
                    com.moddingfromamod.wish.BuildGate.current().markWorldTouched();
                    {
                        // Stand it back far enough to be SEEN. Derived from the camera and the
                        // creature's REAL height (its tallest box times its scale) — guessing from
                        // the scale multiplier alone treats a creature built 80 units tall at scale
                        // 1.0 as if it were knee-high, and the agent's own photo comes back as a
                        // wall of colour it cannot judge.
                        // At the wish-time LOCKED spot the child aimed at, or a framed distance in
                        // front of him — and now never so close that his eyes are inside it:
                        // the aimed spot used to win however near it was, and a giant aimed at from
                        // two blocks away was spawned around him. CreatureSpawn.spotFor is the rule.
                        // or on a building's named spot (SpotSeat) — the throne, the roof,
                        // or this wish's own building's front, never its foundations.
                        var seat = com.moddingfromamod.vanilla.SpotSeat.choose(server, player,
                                seatAt, stored.id(), 0);
                        ServerLevel level = seat.level();
                        double sx = seat.x();
                        double sy = seat.y();
                        double sz = seat.z();
                        // WHAT IS STANDING THERE, not a line that is always true. It
                        // said "Spawned one" without looking; now it is the rig's own carrier, and
                        // the handle is minted from it.
                        var carrier = com.moddingfromamod.vanilla.CreatureSpawn.spawn(level,
                                stored.id(), sx, sy, sz, seat.yaw(),
                                EntitySpawnReason.COMMAND);
                        if (carrier != null) {
                            com.moddingfromamod.vanilla.SpotSeat.remember(carrier, stored.id(), seat);
                            // SAFE WHERE IT STANDS: a footing over water or lava, out of
                            // rock if it was conjured inside it — this tick, before it can die.
                            var settled = com.moddingfromamod.vanilla.SafePlacement.settleSpawn(
                                    player, carrier, true);
                            sb.append(seat.onSpot() ? " Spawned one on " + seat.link() + " ("
                                    : " Spawned one next to the child (")
                              .append(com.moddingfromamod.vanilla.CreatureSpawn.remember(carrier))
                              .append(").").append(settled.forTheAgent()).append(seat.note());
                        } else {
                            sb.append(" ⚠ The spawn FAILED — nothing is standing in the world "
                                    + "(no room there?). The design is saved; spawn it again "
                                    + "somewhere open with run_command `wishmob spawn ")
                              .append(stored.id()).append("`, and do not tell him it is here "
                                    + "until a spawn reports it.");
                        }
                    }
                } else if (spawn) {
                    sb.append(" (Nobody is in the world, so I didn't spawn one.)");
                }
                sb.append(" It has a spawn egg too. If it doesn't look right, call sculpt_creature "
                        + "again with the same id and fixed parts — every one already in the world "
                        + "updates.");
                if (growth.capped()) {
                    // TOLD, not silently clamped. A size instruction that appears to have had no
                    // effect is usually answered with a bigger number.
                    sb.append(' ').append(growth.because());
                }
                // what this creature will DO, said while the agent can still change it.
                for (String line : com.moddingfromamod.species.InstinctNotes.forSpecies(stored,
                        spawn ? 1 : 0)) {
                    sb.append('\n').append(line);
                }
                sb.append('\n').append(voiceNote(stored));
                return sb.toString();
            }));
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
    }

    /**
     * check {@code behaves_like} against the archetypes and this game's mobs, and rewrite it in
     * {@code args} to the archetype it resolved to — so every reader after this one (Species.fromJson,
     * BehaviourProfile.fromJson) reads a word it knows. "" when it is absent or fine; else the refusal.
     */
    /**
     * settle {@code behaves_like} after {@link #resolveBehavesLike} (so it is an archetype's key or absent):
     * {@link com.moddingfromamod.species.MagicianBehaviour} keeps it, gives a flier a flying life, or names one when the
     * agent did not. Rewrites {@code args}; answers the line for the reply ("" when the agent's choice stood).
     */
    static String settleBehaviour(JsonObject args, List<CreatureSculpt.Part> parts) {
        String arg = com.moddingfromamod.species.BehavesLike.ARG;
        String named = args.has(arg) ? args.get(arg).getAsString() : null;
        java.util.Set<String> powers = new java.util.HashSet<>();
        if (args.has("powers") && args.get("powers").isJsonArray()) {
            for (var e : args.getAsJsonArray("powers")) {
                if (e.isJsonPrimitive()) {
                    powers.add(e.getAsString());
                }
            }
        }
        if (named != null) {
            com.moddingfromamod.species.Archetype a = com.moddingfromamod.species.Archetype.byKey(named);
            if (a != null) {
                a.impliedPowers().forEach(pw -> powers.add(pw.name().toLowerCase(java.util.Locale.ROOT)));
            }
        }
        String base = args.has("base") && args.get("base").isJsonPrimitive() ? args.get("base").getAsString() : null;
        Double scale = null;
        try {
            scale = args.has("scale") ? args.get("scale").getAsDouble() : null;
        } catch (RuntimeException ignored) {
            scale = null;
        }
        String name = args.has("name") && args.get("name").isJsonPrimitive() ? args.get("name").getAsString() : "";
        boolean swims = com.moddingfromamod.vanilla.CarrierPool.movementOf(powers, parts, name)
                == com.moddingfromamod.vanilla.CarrierPool.Move.SWIM;
        var o = com.moddingfromamod.species.MagicianBehaviour.settle(named, args.has("start_from"), powers, base, scale, swims);
        if (o.key() != null) {
            args.addProperty(arg, o.key());
        }
        return o.note();
    }

    static String resolveBehavesLike(JsonObject args) {
        String arg = com.moddingfromamod.species.BehavesLike.ARG;
        if (args == null || !args.has(arg)) {
            return "";
        }
        if (!args.get(arg).isJsonPrimitive()) {
            return "behaves_like is one mob's name, e.g. 'wolf'.";
        }
        boolean flies = Species.Power.parse("flies") != null && args.has("powers")
                && args.get("powers").toString().toLowerCase(java.util.Locale.ROOT).contains("flies");
        var r = com.moddingfromamod.species.BehavesLike.resolve(args.get(arg).getAsString(), id -> {
            var rl = net.minecraft.resources.ResourceLocation.tryParse(id);
            if (rl == null || !net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.containsKey(rl)) {
                return null;
            }
            var cat = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(rl).getCategory();
            if (cat == net.minecraft.world.entity.MobCategory.MISC) {
                return null;   // an arrow, a boat, an item frame: not a mob, so no life to live
            }
            boolean water = cat == net.minecraft.world.entity.MobCategory.WATER_CREATURE
                    || cat == net.minecraft.world.entity.MobCategory.WATER_AMBIENT
                    || cat == net.minecraft.world.entity.MobCategory.UNDERGROUND_WATER_CREATURE
                    || cat == net.minecraft.world.entity.MobCategory.AXOLOTLS;
            return new com.moddingfromamod.species.BehavesLike.Traits(flies, water,
                    cat == net.minecraft.world.entity.MobCategory.MONSTER);
        });
        if (r.refused()) {
            return r.error();
        }
        if (r.archetype() == null) {
            args.remove(arg);
        } else {
            args.addProperty(arg, r.archetype().key());
        }
        return "";
    }
}
