package com.moddingfromamod.mob;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.runtime.Abilities;
import com.moddingfromamod.species.Species;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * <b>What a creature does on its own — its temperament, powers and moves — as ONE vocabulary for a
 * sculpted creature and a REAL mob alike</b> ("I really do like that I got
 * vanilla enderman, but I want the behavior updates to be consistent").
 *
 * <p>A sculpted creature has always carried these three on its {@link Species}: {@code base}
 * (wild / guardian / hunter / coward / companion), {@code powers} and {@code abilities}, read by
 * {@code RigInstincts}, {@code RigImmunities} and {@code RigAbilities}. A real mob — a vanilla
 * enderman staged as an army, a modded mob — could be made to follow and be friendly through
 * {@code modify_mob}, and nothing else: "an army of endermen that fights for me" could not be
 * granted, and the agent said so (wish {@code dd227c3c}, 2026-11-01). This is the same three fields,
 * parsed by the SAME parsers ({@link Species.Base#parse}, {@link Species.Power#parse},
 * {@link Species#parseAbilities}), so a word means one thing whichever kind of creature it lands on;
 * the runtime that acts on them is shared too ({@code RealMobBehaviours} hands a real mob's profile
 * to the very same instinct, immunity and ability code a rig carrier runs).
 *
 * <p><b>Null means "leave it alone"</b>, as everywhere in {@link MobTweak}: no {@code base} keeps the
 * one it has, and {@code powers}/{@code abilities} ADD to what it has rather than replacing it, so
 * "make it fireproof" does not take its leap away.
 *
 * <p>Pure — no Minecraft imports — so parsing and the real-mob rules are unit-tested.
 */
public record BehaviourProfile(Species.Base base, Set<Species.Power> powers, Set<Abilities.Kind> abilities,
                               com.moddingfromamod.species.Archetype behaves) {

    /** No behaviour at all. */
    public static final BehaviourProfile NONE = new BehaviourProfile(null, Set.of(), Set.of());

    /** With no archetype: how it LIVES stays as it is. */
    public BehaviourProfile(Species.Base base, Set<Species.Power> powers, Set<Abilities.Kind> abilities) {
        this(base, powers, abilities, null);
    }

    /**
     * <b>The powers a REAL mob can take</b>, because something already enforces each one on any mob:
     * the instincts ({@code leaps}, {@code skittish}), the immunities ({@code RigImmunities}), and the
     * pet behaviour a species power maps to ({@code friendly}, {@code rideable} — {@code SpeciesOnRig}).
     * The rest describe a sculpted BODY ({@code flies}, {@code hops}, {@code naps}: how its rig moves;
     * {@code boss}, {@code explodes}, {@code glowing}, {@code persistent}, {@code hostile}: its rig's
     * extras) — a real mob keeps its own body, and its own glow and persistence have their own
     * {@code modify_mob} fields. Refused by name, never silently dropped ({@link #forRealMob}).
     */
    public static final Set<Species.Power> REAL_MOB_POWERS = EnumSet.of(
            Species.Power.LEAPS, Species.Power.SKITTISH, Species.Power.FRIENDLY, Species.Power.RIDEABLE,
            Species.Power.FIREPROOF, Species.Power.BLASTPROOF, Species.Power.POISONPROOF,
            Species.Power.WITHERPROOF, Species.Power.LIGHTNINGPROOF, Species.Power.FROSTPROOF,
            Species.Power.FALLPROOF);

    public BehaviourProfile {
        powers = powers == null ? Set.of() : java.util.Collections.unmodifiableSet(new LinkedHashSet<>(powers));
        abilities = abilities == null ? Set.of()
                : java.util.Collections.unmodifiableSet(new LinkedHashSet<>(abilities));
    }

    public boolean isEmpty() {
        return base == null && powers.isEmpty() && abilities.isEmpty() && behaves == null;
    }

    /**
     * {@code this} laid over what the mob already has: a named base replaces the old one, and powers
     * and abilities are added to the old ones.
     */
    public BehaviourProfile over(BehaviourProfile was) {
        if (was == null || was.isEmpty()) {
            return this;
        }
        Set<Species.Power> p = new LinkedHashSet<>(was.powers);
        p.addAll(powers);
        Set<Abilities.Kind> a = new LinkedHashSet<>(was.abilities);
        a.addAll(abilities);
        return new BehaviourProfile(base != null ? base : was.base, p, a, behaves != null ? behaves : was.behaves);
    }

    /** What a REAL mob keeps of this profile, and the power words it had to refuse. */
    public record ForRealMob(BehaviourProfile kept, List<String> refused) {
    }

    /** Split off the powers a real mob cannot take ({@link #REAL_MOB_POWERS}). */
    public ForRealMob forRealMob() {
        Set<Species.Power> kept = new LinkedHashSet<>();
        List<String> refused = new ArrayList<>();
        for (Species.Power p : powers) {
            if (REAL_MOB_POWERS.contains(p)) {
                kept.add(p);
            } else {
                refused.add(p.name().toLowerCase(Locale.ROOT));
            }
        }
        // A real mob already lives its own mob's life: behaves_like is a sculpted creature's.
        return new ForRealMob(new BehaviourProfile(base, kept, abilities), refused);
    }

    /** In words, for the tool's reply: "guardian, leaps, fireproof, slam". */
    public List<String> summary() {
        List<String> out = new ArrayList<>();
        if (base != null) {
            out.add(base.name().toLowerCase(Locale.ROOT));
        }
        for (Species.Power p : powers) {
            out.add(p.name().toLowerCase(Locale.ROOT));
        }
        for (Abilities.Kind k : abilities) {
            out.add(k.name().toLowerCase(Locale.ROOT));
        }
        if (behaves != null) {
            out.add("lives like a " + behaves.key().replace('_', ' '));
        }
        return out;
    }

    // ===================== JSON =====================

    /**
     * Read {@code base}, {@code powers} and {@code abilities} off a tool's arguments — the keys and
     * the parsers {@code sculpt_creature} uses. An unknown word is skipped here and reported by the
     * caller ({@link #unknownWords}); an absent {@code base} is null, never wild.
     */
    public static BehaviourProfile fromJson(JsonObject o) {
        if (o == null) {
            return NONE;
        }
        Species.Base base = null;
        if (o.has("base") && o.get("base").isJsonPrimitive()) {
            String b = o.get("base").getAsString().trim();
            for (Species.Base v : Species.Base.values()) {
                if (v.name().equalsIgnoreCase(b)) {
                    base = v;
                }
            }
        }
        Set<Species.Power> powers = new LinkedHashSet<>();
        for (String w : words(o, "powers")) {
            Species.Power p = Species.Power.parse(w);
            if (p != null) {
                powers.add(p);
            }
        }
        // the tool resolved and checked behaves_like (BehavesLike) before this reads it.
        com.moddingfromamod.species.Archetype behaves = null;
        if (o.has(com.moddingfromamod.species.BehavesLike.ARG) && o.get(com.moddingfromamod.species.BehavesLike.ARG).isJsonPrimitive()) {
            behaves = com.moddingfromamod.species.Archetype.resolve(o.get(com.moddingfromamod.species.BehavesLike.ARG).getAsString());
        }
        return new BehaviourProfile(base, powers, Species.parseAbilities(o), behaves);
    }

    /** The words in {@code base}, {@code powers} and {@code abilities} that mean nothing. */
    public static List<String> unknownWords(JsonObject o) {
        List<String> out = new ArrayList<>();
        if (o == null) {
            return out;
        }
        if (o.has("base") && o.get("base").isJsonPrimitive()) {
            String b = o.get("base").getAsString().trim();
            boolean known = false;
            for (Species.Base v : Species.Base.values()) {
                known |= v.name().equalsIgnoreCase(b);
            }
            if (!known) {
                out.add("base '" + b + "'");
            }
        }
        for (String w : words(o, "powers")) {
            if (Species.Power.parse(w) == null) {
                out.add("power '" + w + "'");
            }
        }
        for (String w : words(o, "abilities")) {
            if (Abilities.parse(w) == null) {
                out.add("ability '" + w + "'");
            }
        }
        return out;
    }

    private static List<String> words(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray(key)) {
                if (el.isJsonPrimitive()) {
                    out.add(el.getAsString().trim());
                }
            }
        } else if (o.has(key) && o.get(key).isJsonPrimitive()) {
            out.add(o.get(key).getAsString().trim());
        }
        return out;
    }

    /** As stored on a real mob: the same three keys a tool sends. */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        if (base != null) {
            o.addProperty("base", base.name().toLowerCase(Locale.ROOT));
        }
        if (!powers.isEmpty()) {
            JsonArray a = new JsonArray();
            powers.forEach(p -> a.add(p.name().toLowerCase(Locale.ROOT)));
            o.add("powers", a);
        }
        if (!abilities.isEmpty()) {
            JsonArray a = new JsonArray();
            abilities.forEach(k -> a.add(k.name().toLowerCase(Locale.ROOT)));
            o.add("abilities", a);
        }
        return o;
    }

    public String toStored() {
        return isEmpty() ? "" : toJson().toString();
    }

    public static BehaviourProfile fromStored(String s) {
        if (s == null || s.isBlank()) {
            return NONE;
        }
        try {
            return fromJson(JsonParser.parseString(s).getAsJsonObject());
        } catch (RuntimeException bad) {
            return NONE;
        }
    }
}
