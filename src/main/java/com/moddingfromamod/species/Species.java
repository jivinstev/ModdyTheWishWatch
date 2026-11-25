package com.moddingfromamod.species;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A creature, described entirely as <b>data</b>.
 *
 * <p>This is the trick that makes custom creatures possible with <b>no game restart</b>: Minecraft
 * freezes its registries at load, so a genuinely new {@code EntityType} can never be added at
 * runtime. Instead the mod registers <b>one</b> entity type ({@code wish_mob}) whose <i>species</i>
 * is data attached to each individual — so "make a giant pink slime boss" is a JSON document, not a
 * new registry entry.
 *
 * <p>Appearance composes from things already shipped in the game:
 * <ul>
 *   <li>{@link Body} picks a <b>vanilla model</b> (they're generic over {@code Entity}, so they can
 *       be reused for a custom mob);</li>
 *   <li>{@link #texture} picks a <b>vanilla texture</b> from a curated palette — no new art, and
 *       therefore no client resource-pack reload;</li>
 *   <li>{@link #scale} rides the vanilla {@code SCALE} attribute, which resizes the model
 *       <i>and</i> the hitbox for free.</li>
 * </ul>
 *
 * <p>Pure — no Minecraft imports — so parsing, clamping and validation are unit-tested in
 * milliseconds. Every numeric field is clamped on construction, because these values come from an
 * AI agent and a 7-year-old, and a 10,000-health mob with 400 damage is not a fun surprise.
 */
public record Species(String id, String name, Body body, String texture, int tint, double scale,
                      double health, double speed, double damage, double armor,
                      double knockbackResistance, Set<Power> powers, String modelKey,
                      Base base, Set<com.moddingfromamod.runtime.Abilities.Kind> abilities,
                      Set<String> abilityPacks, String ownerId, Voice voice, String throwsItem,
                      String behaves) {

    /**
     * Every caller that predates the archetype: it lives as its carrier makes it, as it always did.
     */
    public Species(String id, String name, Body body, String texture, int tint, double scale,
                   double health, double speed, double damage, double armor,
                   double knockbackResistance, Set<Power> powers, String modelKey,
                   Base base, Set<com.moddingfromamod.runtime.Abilities.Kind> abilities,
                   Set<String> abilityPacks, String ownerId, Voice voice, String throwsItem) {
        this(id, name, body, texture, tint, scale, health, speed, damage, armor,
                knockbackResistance, powers, modelKey, base, abilities, abilityPacks, ownerId, voice, throwsItem, "");
    }

    /**
     * Every caller that predates what it throws: its ranged move throws a snowball, as it
     * always did.
     */
    public Species(String id, String name, Body body, String texture, int tint, double scale,
                   double health, double speed, double damage, double armor,
                   double knockbackResistance, Set<Power> powers, String modelKey,
                   Base base, Set<com.moddingfromamod.runtime.Abilities.Kind> abilities,
                   Set<String> abilityPacks, String ownerId, Voice voice) {
        this(id, name, body, texture, tint, scale, health, speed, damage, armor,
                knockbackResistance, powers, modelKey, base, abilities, abilityPacks, ownerId, voice, "");
    }

    /**
     * Every caller that predates the voice (chunk 4): {@link Voice#NONE}, as the creature
     * always was.
     */
    public Species(String id, String name, Body body, String texture, int tint, double scale,
                   double health, double speed, double damage, double armor,
                   double knockbackResistance, Set<Power> powers, String modelKey,
                   Base base, Set<com.moddingfromamod.runtime.Abilities.Kind> abilities,
                   Set<String> abilityPacks, String ownerId) {
        this(id, name, body, texture, tint, scale, health, speed, damage, armor,
                knockbackResistance, powers, modelKey, base, abilities, abilityPacks, ownerId, Voice.NONE);
    }

    /**
     * Every creature written before owner stamps, and every caller that does not know whose it is.
     *
     * <p>An extra constructor rather than a changed one, deliberately: {@code ownerId} is a stamp
     * that four write sites can supply and sixty read sites do not care about, and an empty stamp
     * means SHARED ({@link com.moddingfromamod.marshal.ArtifactOwner}) — which is what every
     * creature on every disk in the world is today.
     */
    public Species(String id, String name, Body body, String texture, int tint, double scale,
                   double health, double speed, double damage, double armor,
                   double knockbackResistance, Set<Power> powers, String modelKey,
                   Base base, Set<com.moddingfromamod.runtime.Abilities.Kind> abilities,
                   Set<String> abilityPacks) {
        this(id, name, body, texture, tint, scale, health, speed, damage, armor,
                knockbackResistance, powers, modelKey, base, abilities, abilityPacks, "");
    }

    /**
     * A creature with no custom art. Its appearance still composes from a vanilla {@link Body} +
     * texture + tint (the original Tier-2 path). When {@link #modelKey} is non-empty the client
     * instead draws real Blockbench geometry loaded from the model repository at runtime.
     */
    public boolean hasCustomModel() {
        return modelKey != null && !modelKey.isEmpty();
    }

    /** Which vanilla model shape the creature uses. */
    public enum Body {
        HUMANOID, SLIME, SPIDER, QUADRUPED, BIRD;

        public static Body parse(String s) {
            if (s != null) {
                for (Body b : values()) {
                    if (b.name().equalsIgnoreCase(s.trim())) {
                        return b;
                    }
                }
            }
            return HUMANOID;
        }
    }

    /**
     * Whose <b>instincts</b> the creature borrows.
     *
     * <p>Registries freeze at load, so a runtime creature can never literally {@code extend IronGolem}
     * the way a shipped mod mob does. This gets most of the way there: each profile composes the goal
     * set that makes a vanilla mob feel like itself — a guardian that hunts monsters but is gentle
     * with people, a coward that runs, a companion that follows you around.
     */
    public enum Base {
        /** The default: hostile creatures attack players, friendly ones wander. */
        WILD,
        /** Iron-golem-ish: hunts monsters, never turns on a player. */
        GUARDIAN,
        /** Hunts players AND animals — a proper predator. */
        HUNTER,
        /** Runs away from everything. */
        COWARD,
        /** Sticks close to the nearest player, like a pet. */
        COMPANION;

        public static Base parse(String s) {
            if (s != null) {
                for (Base b : values()) {
                    if (b.name().equalsIgnoreCase(s.trim())) {
                        return b;
                    }
                }
            }
            return WILD;
        }
    }

    /** Composable behaviours. Kept small and legible — an agent picks these from the tool schema. */
    public enum Power {
        /** Attacks players on sight, the child included (default when no powers are given). */
        HOSTILE,
        /** Never attacks; wanders and looks at players. */
        FRIENDLY,
        /** Pounces at its target. */
        LEAPS,
        /** Immune to fire and lava. */
        FIREPROOF,
        /** Runs away from players instead of fighting. */
        SKITTISH,
        /** Emits a glow so it's easy to find. */
        GLOWING,
        /** Doesn't despawn. */
        PERSISTENT,
        /** Stays airborne instead of walking. */
        FLIES,
        /** Shows a boss health bar — for the big ones. */
        BOSS,
        /** Bursts on death (harmless to the world; it never breaks blocks). */
        EXPLODES,
        // ── Immunities. Added by the tools, mechanically, when a creature is given an
        // ability that could hurt ITSELF (SelfHarm); an agent may name them too. FIREPROOF above
        // is the fire one.
        /** Explosions cost it nothing. */
        BLASTPROOF,
        /** Poison never takes hold of it. */
        POISONPROOF,
        /** The wither effect never takes hold of it, and wither damage costs nothing. */
        WITHERPROOF,
        /** Lightning costs it nothing. */
        LIGHTNINGPROOF,
        /** Freezing (powder snow, frost) costs it nothing. */
        FROSTPROOF,
        /** Falling costs it nothing. */
        FALLPROOF,
        // ── How it moves and rests. Read by the rig's gait, which plays them from
        // the sculpt's own legs: no keyframes, so every creature can be given them.
        /** Hops everywhere instead of walking — a bunny, a frog, a kangaroo. */
        HOPS,
        /** Lies down when it has stood still a long while, and curls up asleep at night. */
        NAPS,
        // ── What a child DOES with it (2026-10-11). Part of the design, so every spawn of it, from
        // his button or the library, has it; set on one animal later with modify_mob, it stays
        // that animal's.
        /** He climbs on with a right-click and steers where he looks. */
        RIDEABLE;

        public static Power parse(String s) {
            if (s != null) {
                for (Power p : values()) {
                    if (p.name().equalsIgnoreCase(s.trim())) {
                        return p;
                    }
                }
            }
            return null;
        }

        /**
         * <b>The power words this game does not know, and where each belongs instead</b>.
         * Parsing drops an unknown word, so "guardian" given as a power made a creature with no
         * instinct at all and the tool said nothing: across the archived runs, guardian, charge,
         * tough, fast and a dozen others were each silently thrown away. Empty when all are known.
         */
        public static String unknownWords(com.google.gson.JsonElement powers) {
            if (powers == null || !powers.isJsonArray()) {
                return "";
            }
            java.util.List<String> bad = new java.util.ArrayList<>();
            java.util.List<String> hints = new java.util.ArrayList<>();
            for (com.google.gson.JsonElement el : powers.getAsJsonArray()) {
                String w = el.isJsonPrimitive() ? el.getAsString().trim() : String.valueOf(el);
                if (parse(w) != null) {
                    continue;
                }
                bad.add("'" + w + "'");
                String lower = w.toLowerCase(java.util.Locale.ROOT);
                if (java.util.Arrays.stream(Base.values()).anyMatch(b -> b.name().equalsIgnoreCase(lower))) {
                    hints.add("'" + lower + "' is an instinct: pass base:\"" + lower + "\"");
                } else if (java.util.Arrays.stream(com.moddingfromamod.runtime.Abilities.Kind.values())
                        .anyMatch(k -> k.name().equalsIgnoreCase(lower))) {
                    hints.add("'" + lower + "' is a move: put it in abilities");
                }
            }
            if (bad.isEmpty()) {
                return "";
            }
            return "powers has words this game does not know: " + String.join(", ", bad) + ". "
                    + (hints.isEmpty() ? "" : String.join("; ", hints) + ". ")
                    + "Powers are: " + java.util.Arrays.stream(values())
                            .map(p -> p.name().toLowerCase(java.util.Locale.ROOT))
                            .collect(java.util.stream.Collectors.joining(", "))
                    + ". Speed, health, damage and size are their own fields.";
        }
    }

    // Bounds. Generous enough to be exciting, tight enough to stay playable.
    public static final double MIN_SCALE = 0.2, MAX_SCALE = 8.0;
    public static final double MIN_HEALTH = 1, MAX_HEALTH = 500;
    public static final double MIN_SPEED = 0.05, MAX_SPEED = 0.6;
    public static final double MAX_DAMAGE = 40;
    public static final double MAX_ARMOR = 30;

    /** Vanilla textures a species may use. Keys are what an agent writes; values are real paths. */
    public static final java.util.Map<String, String> TEXTURES = java.util.Map.ofEntries(
            java.util.Map.entry("zombie", "textures/entity/zombie/zombie.png"),
            java.util.Map.entry("husk", "textures/entity/zombie/husk.png"),
            java.util.Map.entry("drowned", "textures/entity/zombie/drowned.png"),
            java.util.Map.entry("skeleton", "textures/entity/skeleton/skeleton.png"),
            java.util.Map.entry("wither_skeleton", "textures/entity/skeleton/wither_skeleton.png"),
            java.util.Map.entry("stray", "textures/entity/skeleton/stray.png"),
            java.util.Map.entry("bogged", "textures/entity/skeleton/bogged.png"),
            java.util.Map.entry("piglin", "textures/entity/piglin/piglin.png"),
            java.util.Map.entry("piglin_brute", "textures/entity/piglin/piglin_brute.png"),
            java.util.Map.entry("slime", "textures/entity/slime/slime.png"),
            java.util.Map.entry("magma_cube", "textures/entity/slime/magmacube.png"),
            java.util.Map.entry("spider", "textures/entity/spider/spider.png"),
            java.util.Map.entry("cave_spider", "textures/entity/spider/cave_spider.png"),
            java.util.Map.entry("pig", "textures/entity/pig/pig.png"),
            java.util.Map.entry("cow", "textures/entity/cow/cow.png"),
            java.util.Map.entry("mooshroom", "textures/entity/cow/red_mooshroom.png"),
            java.util.Map.entry("brown_mooshroom", "textures/entity/cow/brown_mooshroom.png"),
            java.util.Map.entry("chicken", "textures/entity/chicken.png"),
            java.util.Map.entry("creeper", "textures/entity/creeper/creeper.png"));

    /** The texture used when a species names one we don't ship. */
    public static final String DEFAULT_TEXTURE = "zombie";

    public Species {
        id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        name = (name == null || name.isBlank()) ? "Wish Creature" : name.trim();
        if (name.length() > 48) {
            name = name.substring(0, 48);
        }
        body = body == null ? Body.HUMANOID : body;
        texture = (texture != null && TEXTURES.containsKey(texture.toLowerCase(Locale.ROOT)))
                ? texture.toLowerCase(Locale.ROOT) : DEFAULT_TEXTURE;
        tint = tint & 0xFFFFFF;   // RGB only; alpha is never meaningful here
        scale = clamp(scale, MIN_SCALE, MAX_SCALE);
        health = clamp(health, MIN_HEALTH, MAX_HEALTH);
        speed = clamp(speed, MIN_SPEED, MAX_SPEED);
        damage = clamp(damage, 0, MAX_DAMAGE);
        armor = clamp(armor, 0, MAX_ARMOR);
        knockbackResistance = clamp(knockbackResistance, 0, 1);
        // IN THE ORDER GIVEN, not Set.copyOf's: that order is salted PER JVM, so every save in a new
        // process reordered a creature's powers on disk — a CHANGED row in the upgrade census for
        // a creature nobody touched, the first time an earlier issue's backfill saved at load.
        powers = ordered(powers);
        // A model key is a repository folder name: same safe alphabet as an id, empty = no custom art.
        modelKey = sanitizeKey(modelKey);
        base = base == null ? Base.WILD : base;
        abilities = ordered(abilities);
        // AUTHORED abilities, bound to the species —.
        //
        // `abilities` above is the built-in enum: six fixed kinds with hardcoded cooldowns, so
        // "breathe fire every 15 seconds" is not expressible there and never will be without a mod
        // rebuild. An authored CBScript ability CAN say that, but until now it was a world rule that
        // merely MENTIONED a mob rather than a property OF it: it did not persist with the species,
        // was not listed with it, and — the visible bug — `remove_creature` killed the mob and left
        // its fire breath firing at nothing.
        abilityPacks = ordered(abilityPacks);
        ownerId = com.moddingfromamod.marshal.ArtifactOwner.normalise(ownerId);
        voice = voice == null ? Voice.NONE : voice;
        throwsItem = throwsItem(throwsItem);
        // the archetype it lives — a key Archetype knows, or nothing (its carrier's life, as before).
        Archetype arch = Archetype.byKey(behaves);
        behaves = arch == null ? "" : arch.key();
    }

    /**
     * What its {@code ranged} move throws, as the vanilla item the ball is drawn as: empty is
     * the snowball it always threw. Kept as an id here, because this class imports no Minecraft; the
     * tool refuses an item the game does not have and the rig falls back to the snowball.
     */
    public static String throwsItem(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String id = raw.trim().toLowerCase(Locale.ROOT);
        id = id.contains(":") ? id : "minecraft:" + id;
        return id.equals("minecraft:snowball") || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") ? "" : id;
    }

    /** An unmodifiable copy that iterates in the order it was given — the same in every JVM. */
    private static <T> Set<T> ordered(Set<T> in) {
        if (in == null || in.isEmpty()) {
            return Set.of();
        }
        Set<T> out = new LinkedHashSet<>();
        for (T t : in) {
            if (t != null) {
                out.add(t);
            }
        }
        return java.util.Collections.unmodifiableSet(out);
    }

    /** The same creature, stamped for a child. Empty clears the stamp back to shared. */
    public Species withOwner(String owner) {
        return new Species(id, name, body, texture, tint, scale, health, speed, damage, armor,
                knockbackResistance, powers, modelKey, base, abilities, abilityPacks, owner, voice, throwsItem, behaves);
    }

    /**
     * The same creature under a new id and model key — <b>the fork</b>.
     *
     * <p>Both move together on purpose. A fork that kept the base's {@code modelKey} would read
     * correctly (the repository falls back to the library for art the world does not have) right up
     * until the child re-sculpts it, at which point the sculpt writes the base's directory in THIS
     * world and shadows the curated art for everybody. The art is copied at fork time; this is the
     * record half of that.
     */
    public Species forkedAs(String newId, String newModelKey, String owner) {
        return new Species(newId, name, body, texture, tint, scale, health, speed, damage, armor,
                knockbackResistance, powers,
                modelKey == null || modelKey.isEmpty() ? "" : newModelKey,
                base, abilities, abilityPacks, owner, voice, throwsItem, behaves);
    }

    public boolean can(com.moddingfromamod.runtime.Abilities.Kind kind) {
        return abilities.contains(kind);
    }

    /**
     * Model keys are path segments — keep them to the id alphabet so they can never escape the repo.
     *
     * <p>⚠ It DROPS what it dislikes rather than refusing the key, which is why the fork
     * separator had to be let in here as well as into {@code ModelRepository.isValidKey}:
     * {@code spikor~1f2e3d4c} would otherwise have been stored as {@code spikor1f2e3d4c} — a valid,
     * unique key that nothing else in the system believes in.
     */
    private static String sanitizeKey(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : raw.trim().toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-'
                    || c == com.moddingfromamod.marshal.ArtifactOwner.FORK) {
                sb.append(c);
            }
            if (sb.length() >= 64) {
                break;
            }
        }
        return sb.toString();
    }

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v)) {
            return lo;
        }
        return Math.max(lo, Math.min(hi, v));
    }

    public boolean isValid() {
        return !id.isEmpty();
    }

    public boolean has(Power p) {
        return powers.contains(p);
    }

    /** Hostile unless explicitly friendly/skittish. */
    public boolean isHostile() {
        return !has(Power.FRIENDLY) && !has(Power.SKITTISH);
    }

    /**
     * A copy of this species with some values replaced — the persistent half of "make it bigger".
     *
     * <p>Without this, changing a wish creature would only ever change the ONE standing in front of
     * the child: the next one summoned would come back the original size, because the saved species
     * never learned anything. Nulls mean "leave alone", so a tweak that only mentions scale cannot
     * silently reset the health.
     */
    public Species with(Double newScale, Double newHealth, Double newDamage, Double newSpeed,
                        String newName, Boolean glowing) {
        java.util.Set<Power> nextPowers = new LinkedHashSet<>(powers);
        if (glowing != null) {
            if (glowing) {
                nextPowers.add(Power.GLOWING);
            } else {
                nextPowers.remove(Power.GLOWING);
            }
        }
        return new Species(id,
                newName == null || newName.isBlank() ? name : newName,
                body, texture, tint,
                newScale == null ? scale : newScale,
                newHealth == null ? health : newHealth,
                newSpeed == null ? speed : newSpeed,
                newDamage == null ? damage : newDamage,
                armor, knockbackResistance, nextPowers, modelKey, base, abilities, abilityPacks,
                ownerId, voice, throwsItem, behaves);
    }

    /** The real texture path for this species. */
    public String texturePath() {
        return TEXTURES.getOrDefault(texture, TEXTURES.get(DEFAULT_TEXTURE));
    }

    // ===================== JSON =====================

    /**
     * The version a species RECORD is written at (docs/STORE_MIGRATIONS.md), inline in each record as {@code "v"} so a
     * migration is applied to, and counted on, the records written before it. A record with none is v1. An older build
     * ignores the field and reads the record as before.
     * <ul>
     *   <li>v2 (#99): read under the library-copy rule ({@link LibraryCopies}).</li>
     * </ul>
     */
    public static final int RECORD_VERSION = 2;

    /** The version a stored record was written at: its {@code "v"}, or 1 when it has none. */
    public static int recordVersion(JsonObject o) {
        try {
            return o != null && o.has("v") && o.get("v").isJsonPrimitive() ? o.get("v").getAsInt() : 1;
        } catch (RuntimeException e) {
            return 1;
        }
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("v", RECORD_VERSION);
        o.addProperty("id", id);
        o.addProperty("name", name);
        o.addProperty("body", body.name().toLowerCase(Locale.ROOT));
        o.addProperty("texture", texture);
        o.addProperty("tint", String.format("#%06X", tint));
        o.addProperty("scale", scale);
        o.addProperty("health", health);
        o.addProperty("speed", speed);
        o.addProperty("damage", damage);
        o.addProperty("armor", armor);
        o.addProperty("knockbackResistance", knockbackResistance);
        JsonArray ps = new JsonArray();
        for (Power p : powers) {
            ps.add(p.name().toLowerCase(Locale.ROOT));
        }
        o.add("powers", ps);
        // Only written when set — keeps every existing Tier-2 creature's JSON byte-identical.
        if (hasCustomModel()) {
            o.addProperty("modelKey", modelKey);
        }
        if (base != Base.WILD) {
            o.addProperty("base", base.name().toLowerCase(Locale.ROOT));
        }
        if (!abilityPacks.isEmpty()) {
            var ap = new com.google.gson.JsonArray();
            abilityPacks.forEach(ap::add);
            o.add("abilityPacks", ap);
        }
        if (!abilities.isEmpty()) {
            JsonArray as = new JsonArray();
            for (com.moddingfromamod.runtime.Abilities.Kind k : abilities) {
                as.add(k.name().toLowerCase(Locale.ROOT));
            }
            o.add("abilities", as);
        }
        // Only when it has one, so every creature made before the voice stays byte-identical.
        var v = voice.toJson();
        if (v != null) {
            o.add("voice", v);
        }
        // The same rule for what it throws.
        if (!throwsItem.isEmpty()) {
            o.addProperty("throws", throwsItem);
        }
        // the archetype, only when it has one — every creature made before it stays byte-identical.
        if (!behaves.isEmpty()) {
            o.addProperty("behaves", behaves);
        }
        // Only written when there is one, so every creature made before owner stamps stays byte-identical
        // — the same rule modelKey follows one field up, and the same reason.
        com.moddingfromamod.marshal.ArtifactOwner.stamp(o, ownerId);
        return o;
    }

    /** Parse a species. Missing fields take sane defaults; bad ones are clamped, never fatal. */
    public static Species fromJson(JsonObject o) {
        if (o == null) {
            return null;
        }
        Set<Power> powers = new LinkedHashSet<>();
        if (o.has("powers") && o.get("powers").isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray("powers")) {
                if (el.isJsonPrimitive()) {
                    Power p = Power.parse(el.getAsString());
                    if (p != null) {
                        powers.add(p);
                    }
                }
            }
        }
        Species s = new Species(
                str(o, "id", ""),
                str(o, "name", "Wish Creature"),
                Body.parse(str(o, "body", "humanoid")),
                str(o, "texture", DEFAULT_TEXTURE),
                parseColour(str(o, "tint", "#FFFFFF")),
                dbl(o, "scale", 1.0),
                dbl(o, "health", 20),
                dbl(o, "speed", 0.25),
                dbl(o, "damage", 3),
                dbl(o, "armor", 0),
                dbl(o, "knockbackResistance", 0),
                powers,
                str(o, "modelKey", ""),
                Base.parse(str(o, "base", "wild")),
                parseAbilities(o),
                parseAbilityPacks(o),
                com.moddingfromamod.marshal.ArtifactOwner.of(o),
                parseVoice(o),
                str(o, "throws", ""),
                str(o, "behaves", ""));
        // A life that needs a power (a dragon's flight) brings it, however the file was written.
        if (s.archetype() != null) {
            s = s.withBehaves(s.archetype());
        }
        return s.isValid() ? s : null;
    }

    /**
     * Its voice (chunk 4); absent in every file written before it. A shape that cannot be
     * read is no voice rather than no creature: a species never fails to load over how it sounds.
     */
    private static Voice parseVoice(JsonObject o) {
        try {
            return Voice.parse(o.get("voice"));
        } catch (IllegalArgumentException e) {
            return Voice.NONE;
        }
    }

    /**
     * <b>The same creature with its shipped stat line</b> (#99): health, attack damage, armour and knockback resistance
     * from {@code shipped} (a library creature's own {@code species.json}), each only where the record states it.
     * Everything else (its look, name, powers, moves, instinct, owner) is kept. A world's copy of a library creature
     * is a pure copy (an edit of one always forks it, {@code ForkPolicy}), so reading its numbers again from the
     * shipped record changes nothing a player chose.
     */
    public Species withShippedStats(JsonObject shipped) {
        if (shipped == null) {
            return this;
        }
        return new Species(id, name, body, texture, tint, scale, dbl(shipped, "health", health), speed,
                dbl(shipped, "damage", damage), dbl(shipped, "armor", armor),
                dbl(shipped, "knockbackResistance", knockbackResistance), powers, modelKey, base, abilities,
                abilityPacks, ownerId, voice, throwsItem, behaves);
    }

    /** The same creature with this voice. */
    public Species withVoice(Voice v) {
        return new Species(id, name, body, texture, tint, scale, health, speed, damage, armor,
                knockbackResistance, powers, modelKey, base, abilities, abilityPacks, ownerId, v, throwsItem, behaves);
    }

    /** Authored ability packs bound to this species. Absent in every file written. */
    private static Set<String> parseAbilityPacks(JsonObject o) {
        Set<String> out = new LinkedHashSet<>();
        if (o.has("abilityPacks") && o.get("abilityPacks").isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray("abilityPacks")) {
                if (el.isJsonPrimitive() && !el.getAsString().isBlank()) {
                    out.add(el.getAsString());
                }
            }
        }
        return out;
    }

    /** The same species with these powers added (the immunities SelfHarm asks for). */
    public Species withPowersAdded(Set<Power> more) {
        if (more == null || powers.containsAll(more)) {
            return this;
        }
        Set<Power> next = new LinkedHashSet<>(powers);
        next.addAll(more);
        return new Species(id, name, body, texture, tint, scale, health, speed, damage, armor,
                knockbackResistance, next, modelKey, base, abilities, abilityPacks, ownerId, voice, throwsItem, behaves);
    }

    /** The same species without these powers (2026-10-11: "he can't ride it any more"). */
    public Species withPowersRemoved(Set<Power> less) {
        if (less == null || java.util.Collections.disjoint(powers, less)) {
            return this;
        }
        Set<Power> next = new LinkedHashSet<>(powers);
        next.removeAll(less);
        return new Species(id, name, body, texture, tint, scale, health, speed, damage, armor,
                knockbackResistance, next, modelKey, base, abilities, abilityPacks, ownerId, voice, throwsItem, behaves);
    }

    /**
     * <b>What a pet behaviour changes about the DESIGN</b> (2026-10-11). Riding and harmlessness
     * are what the creature IS — "a rideable T-Rex" is ridden every time his button brings it
     * back — so they are kept as powers; following, staying and finding stay that one animal's.
     * Pure; null leaves a power as it was.
     */
    public Species withDesignedBehaviour(Boolean rideable, Boolean friendly) {
        Species s = this;
        if (rideable != null) {
            s = rideable ? s.withPowersAdded(Set.of(Power.RIDEABLE)) : s.withPowersRemoved(Set.of(Power.RIDEABLE));
        }
        if (friendly != null) {
            s = friendly ? s.withPowersAdded(Set.of(Power.FRIENDLY)) : s.withPowersRemoved(Set.of(Power.FRIENDLY));
        }
        return s;
    }

    /**
     * <b>The same species with a temperament, more powers and more moves</b>: what
     * {@code modify_mob}'s {@code base}, {@code powers} and {@code abilities} mean on a sculpted
     * creature — the same words a real mob takes. A null base keeps the one it has; powers and
     * abilities are ADDED, so "make it fireproof" does not take its leap away.
     */
    public Species withBehaviour(Base newBase, Set<Power> addPowers,
                                 Set<com.moddingfromamod.runtime.Abilities.Kind> addAbilities) {
        Set<Power> p = new LinkedHashSet<>(powers);
        if (addPowers != null) {
            p.addAll(addPowers);
        }
        Set<com.moddingfromamod.runtime.Abilities.Kind> a = new LinkedHashSet<>(abilities);
        if (addAbilities != null) {
            a.addAll(addAbilities);
        }
        Base b = newBase == null ? base : newBase;
        if (b == base && p.equals(powers) && a.equals(abilities)) {
            return this;
        }
        return new Species(id, name, body, texture, tint, scale, health, speed, damage, armor,
                knockbackResistance, p, modelKey, b, a, abilityPacks, ownerId, voice, throwsItem, behaves);
    }

    /**
     * <b>The same species, living {@code a}</b> — with the powers its life cannot do without
     * (a dragon or a buzzer flies). Null takes the archetype off: it lives as its carrier makes it.
     */
    public Species withBehaves(Archetype a) {
        Set<Power> p = new LinkedHashSet<>(powers);
        if (a != null) {
            p.addAll(a.impliedPowers());
        }
        String k = a == null ? "" : a.key();
        if (k.equals(behaves) && p.equals(powers)) {
            return this;
        }
        return new Species(id, name, body, texture, tint, scale, health, speed, damage, armor,
                knockbackResistance, p, modelKey, base, abilities, abilityPacks, ownerId, voice, throwsItem, k);
    }

    /** Its archetype, or null when it lives as its carrier makes it. */
    public Archetype archetype() {
        return Archetype.byKey(behaves);
    }

    /** The same species with one more authored ability bound to it. */
    public Species withAbilityPack(String featureId) {
        if (featureId == null || featureId.isBlank() || abilityPacks.contains(featureId)) {
            return this;
        }
        Set<String> next = new LinkedHashSet<>(abilityPacks);
        next.add(featureId);
        // ⚠ `health, speed, damage` — the record's own order. This line read `health, damage,
        // speed` until the owner stamp went past it, which silently swapped a creature's speed and damage
        // every time an authored ability was bound to it.
        return new Species(id, name, body, texture, tint, scale, health, speed, damage, armor,
                knockbackResistance, powers, modelKey, base, abilities, next, ownerId, voice, throwsItem, behaves);
    }

    /** The {@code abilities} array, as every creature tool reads it (shared with {@code BehaviourProfile}). */
    public static Set<com.moddingfromamod.runtime.Abilities.Kind> parseAbilities(JsonObject o) {
        Set<com.moddingfromamod.runtime.Abilities.Kind> out = new LinkedHashSet<>();
        if (o.has("abilities") && o.get("abilities").isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray("abilities")) {
                if (el.isJsonPrimitive()) {
                    var k = com.moddingfromamod.runtime.Abilities.parse(el.getAsString());
                    if (k != null) {
                        out.add(k);
                    }
                }
            }
        }
        return out;
    }

    /** Every texture key an agent may choose (used to build the tool's JSON Schema). */
    public static List<String> textureKeys() {
        List<String> keys = new ArrayList<>(TEXTURES.keySet());
        java.util.Collections.sort(keys);
        return keys;
    }

    /** No tint. White multiplies to a no-op, so this means "leave the texture alone". */
    public static final int NO_TINT = 0xFFFFFF;

    /** Colours a child would name, so an agent can write "pink" instead of hex. */
    private static final java.util.Map<String, Integer> NAMED_COLOURS = java.util.Map.ofEntries(
            java.util.Map.entry("white", 0xFFFFFF), java.util.Map.entry("pink", 0xFF7FBF),
            java.util.Map.entry("hot_pink", 0xFF1493), java.util.Map.entry("red", 0xFF4040),
            java.util.Map.entry("orange", 0xFF9020), java.util.Map.entry("yellow", 0xFFE040),
            java.util.Map.entry("green", 0x50E050), java.util.Map.entry("lime", 0xA0FF40),
            java.util.Map.entry("cyan", 0x40E0E0), java.util.Map.entry("blue", 0x5080FF),
            java.util.Map.entry("purple", 0xA050FF), java.util.Map.entry("magenta", 0xFF50FF),
            java.util.Map.entry("brown", 0x8B5A2B), java.util.Map.entry("grey", 0x909090),
            java.util.Map.entry("gray", 0x909090), java.util.Map.entry("black", 0x404040),
            java.util.Map.entry("gold", 0xFFC030), java.util.Map.entry("rainbow", 0xFF80FF));

    /** Accept "#RRGGBB", "RRGGBB", or a colour name. Anything unreadable means no tint. */
    public static int parseColour(String raw) {
        if (raw == null || raw.isBlank()) {
            return NO_TINT;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        Integer named = NAMED_COLOURS.get(v.replace(' ', '_'));
        if (named != null) {
            return named;
        }
        if (v.startsWith("#")) {
            v = v.substring(1);
        }
        try {
            return Integer.parseInt(v, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return NO_TINT;   // an agent inventing "sparkly" shouldn't break the creature
        }
    }

    /** Colour names an agent may use (for the tool schema). */
    public static java.util.List<String> colourNames() {
        java.util.List<String> names = new ArrayList<>(NAMED_COLOURS.keySet());
        java.util.Collections.sort(names);
        return names;
    }

    private static String str(JsonObject o, String k, String fallback) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : fallback;
    }

    private static double dbl(JsonObject o, String k, double fallback) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
