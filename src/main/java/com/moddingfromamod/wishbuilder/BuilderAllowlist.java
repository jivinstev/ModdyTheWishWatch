package com.moddingfromamod.wishbuilder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>What a world lets a Crafter pick</b> (docs/WISH_BUILDER_PLAN.md §2–§3): a reviewed list of
 * catalogue ids, the temperaments, and the kinds. A new agent effect therefore never appears in front
 * of a Crafter unreviewed — it must be added here. {@code BuilderAllowlistTest} fails if any listed id
 * names something no tool takes ({@link Catalogue#problem}).
 *
 * <p>B0a ships {@link #REVIEWED}; B0b reads a world's {@code crafters.builder} block over it
 * ({@link #fromJson}). Minecraft-free.
 */
public record BuilderAllowlist(Set<BuilderSpec.Kind> kinds, Set<String> ids, Set<String> tempers, int maxCount,
                               String itemTier, BuilderSupplies.Limits supplies) {

    public BuilderAllowlist {
        kinds = Set.copyOf(kinds);
        ids = Set.copyOf(ids);
        tempers = Set.copyOf(tempers);
        itemTier = itemTier == null || itemTier.isEmpty() ? BuilderItemPowers.TIER_ANY : itemTier;
        supplies = supplies == null ? BuilderSupplies.Limits.DEFAULT : supplies;
    }

    /** the default supplies limits ({@link BuilderSupplies.Limits#DEFAULT}). */
    public BuilderAllowlist(Set<BuilderSpec.Kind> kinds, Set<String> ids, Set<String> tempers, int maxCount,
                            String itemTier) {
        this(kinds, ids, tempers, maxCount, itemTier, BuilderSupplies.Limits.DEFAULT);
    }

    /** The same list with other supplies limits. */
    public BuilderAllowlist withSupplies(BuilderSupplies.Limits limits) {
        return new BuilderAllowlist(kinds, ids, tempers, maxCount, itemTier, limits);
    }

    /** is this item power offered here (its effect on the list; lava only where the world allows it)? */
    public boolean offers(BuilderItemPowers.Power w) {
        return w != null && (w.effect().isEmpty() || ids.contains("effect:" + w.effect()));   //: no power is anywhere
    }

    /** is this supply offered here (on the list, its category on, lava only where allowed)? */
    public boolean offers(BuilderSupplies.Supply s) {
        return s != null && (ids.contains(s.catalogueId()) || BuilderSupplies.found(s.id())) && supplies.offers(s);
    }

    /** Every stock item as listed (the full tier). */
    public BuilderAllowlist(Set<BuilderSpec.Kind> kinds, Set<String> ids, Set<String> tempers, int maxCount) {
        this(kinds, ids, tempers, maxCount, BuilderItemPowers.TIER_ANY);
    }

    /** The same list at another item tier. */
    public BuilderAllowlist withItemTier(String tier) {
        return new BuilderAllowlist(kinds, ids, tempers, maxCount, tier, supplies);
    }

    /**
     * {@code spec} as THIS world makes it. A made-up item takes the world's item tier, so a Lightning Sword saved
     * in a full-tier world, or one the Apprentice offers, is a stone sword in a basic-tier world; every other spec is as it is.
     */
    public BuilderSpec capped(BuilderSpec spec) {
        if (BuilderSupplies.is(spec)) {
            // supplies at most this world's most per make (a saved spec, the Apprentice).
            BuilderSupplies.Supply s = BuilderSupplies.supply(spec.from());
            int n = s == null ? 0 : BuilderSupplies.amount(spec);
            int top = s == null ? n : BuilderSupplies.top(s, supplies);
            if (s == null || n <= top) {
                return spec;
            }
            java.util.Map<String, String> o = new java.util.LinkedHashMap<>(spec.opts());
            o.put("amount", String.valueOf(top));
            return new BuilderSpec(spec.version(), spec.kind(), spec.from(), spec.scheme(), spec.size(), spec.powers(),
                    spec.temper(), spec.count(), spec.at(), spec.name(), o);
        }
        boolean tiered = spec.kind() == BuilderSpec.Kind.ITEM && BuilderItemPowers.power(spec.opt("power")) != null
                || BuilderBlocks.composed(spec);   //: a magic block's lucky surprise has the tier's presents
        if (!tiered || itemTier.equals(spec.opt("tier").isEmpty() ? BuilderItemPowers.TIER_ANY : spec.opt("tier"))) {
            return spec;
        }
        java.util.Map<String, String> opts = new java.util.LinkedHashMap<>(spec.opts());
        if (itemTier.equals(BuilderItemPowers.TIER_ANY)) {
            opts.remove("tier");
        } else {
            opts.put("tier", itemTier);
        }
        return new BuilderSpec(spec.version(), spec.kind(), spec.from(), spec.scheme(), spec.size(), spec.powers(),
                spec.temper(), spec.count(), spec.at(), spec.name(), opts);
    }

    /**
     * The reviewed default: every ready-made in the starter library, the creature powers that are
     * safe for any child to give (no {@code hostile}, {@code boss} or {@code explodes}), and the
     * reviewed temperaments.
     */
    public static final BuilderAllowlist REVIEWED = reviewed();

    private static BuilderAllowlist reviewed() {
        Set<String> ids = new LinkedHashSet<>();
        for (String p : Catalogue.presets()) {
            ids.add("preset:" + p);
        }
        for (String p : List.of("flies", "glowing", "rideable", "hops", "leaps", "fireproof", "naps", "persistent")) {
            ids.add("power:" + p);
        }
        for (Schemes.Scheme s : Schemes.ALL) {
            ids.add("scheme:" + s.id());
        }
        // a made-up item's shapes and powers — the reviewed slice of the item catalogue (BuilderItemPowers,
        // which keeps every power that could land on someone else on the sword alone).
        // the motions a made-up event may use.
        for (BuilderEvents.Motion m : BuilderEvents.ALL) {
            ids.add("motion:" + m.motion());
        }
        // the parts a creature may be given.
        for (BuilderParts.Part pt : BuilderParts.ALL) {
            ids.add("kit:" + pt.id());
        }
        // the special moves, each touching only monsters (BuilderAbilities, §6.2).
        for (BuilderAbilities.Ability a : BuilderAbilities.ALL) {
            ids.add("ability:" + a.id());
        }
        for (BuilderItemPowers.Stock st : BuilderItemPowers.STOCK) {
            ids.add("shape:" + st.shape());   //: a world allows a TRIGGER, and every stock item that has it
        }
        for (BuilderItemPowers.Power w : BuilderItemPowers.POWERS) {
            if (!w.effect().isEmpty()) {   //: "Just for fun" is no catalogue effect
                ids.add("effect:" + w.effect());
            }
        }
        // a composed magic block's effects (its looks and triggers are BuilderBlocks' own, not catalogue ids).
        ids.add("shape:magic_block");
        for (BuilderBlocks.Does d : BuilderBlocks.DOES) {
            ids.add("effect:" + d.effect());
        }
        // a composed snack machine's looks and what may go in it (its powers are the effect: ids above).
        for (BuilderSnacks.Look l : BuilderSnacks.LOOKS) {
            ids.add("look:" + l.id());
        }
        for (String i : BuilderSnacks.INGREDIENTS) {
            ids.add("ingredient:" + i);
        }
        for (String w : RuleEffects.TRIGGERS) {
            ids.add("when:" + w);
        }
        for (RuleEffects.Effect e : RuleEffects.ALL) {
            ids.add("rule:" + e.id());
        }
        // every supply (a world's supplies knob limits them: categories, the most per make, lava).
        ids.addAll(BuilderSupplies.catalogueIds());
        for (String m : RuleEffects.RULE_MOBS) {
            ids.add("mob:minecraft:" + m);   // what a rule may be about, and a real mob to summon
        }
        return new BuilderAllowlist(Set.of(BuilderSpec.Kind.values()), ids,
                Set.of("friendly", "shy", "wild", "guard", "pet", "stays"), BuilderCounts.TOP);   //: the world's spawn rules cap the rest
    }

    /**
     * an installed thing found by name, not on the reviewed list: any creature as a real animal (where the world
     * offers real animals at all), any item as a supply (where it offers supplies at all).
     */
    boolean foundOk(BuilderSpec spec, String id) {
        if (spec.kind() == BuilderSpec.Kind.CREATURE && id.equals(spec.from()) && Catalogue.prefix(id).equals("mob")) {
            String mob = id.substring(4);
            return !mob.startsWith(com.moddingfromamod.content.Found.OWN)
                    && ids.stream().anyMatch(i -> i.startsWith("mob:minecraft:") && BuilderFlow.FARM_ANIMALS.contains(i.substring(14)))
                    && com.moddingfromamod.content.Found.name(com.moddingfromamod.content.ContentIndex.Kind.MOB, mob) != null;
        }
        return spec.kind() == BuilderSpec.Kind.SUPPLIES && id.equals(spec.from()) && BuilderSupplies.found(id)
                && supplies.offers(BuilderSupplies.supply(id));
    }

    /** Every problem with {@code spec} on this world; empty when it may be made. */
    public List<String> problems(BuilderSpec spec) {
        List<String> out = new ArrayList<>();
        if (!kinds.contains(spec.kind())) {
            out.add("this world's Builder does not make a " + spec.kind().id());
        }
        for (String id : BuilderLowering.ids(spec)) {
            if (!ids.contains(id) && !foundOk(spec, id)) {
                out.add("'" + id + "' is not on this world's Builder list");
            }
        }
        if (!spec.temper().isEmpty() && !tempers.contains(spec.temper())) {
            out.add("the '" + spec.temper() + "' temperament is not on this world's Builder list");
        }
        if (spec.kind() == BuilderSpec.Kind.ITEM && BuilderItemPowers.power(spec.opt("power")) != null) {
            BuilderItemPowers.Stock stock = BuilderItemPowers.of(spec);
            if (stock != null && BuilderItemPowers.tiered(stock, itemTier) == null) {
                out.add("this world's Builder does not make a " + stock.noun().toLowerCase(java.util.Locale.ROOT));
            }
        }
        if ((spec.kind() == BuilderSpec.Kind.CREATURE || spec.kind() == BuilderSpec.Kind.RIDE)
                && Catalogue.prefix(spec.from()).equals("mob")
                && (spec.kind() == BuilderSpec.Kind.RIDE || !foundOk(spec, spec.from()))
                && !ids.contains(spec.from())) {
            // then: a real mob is any installed creature, on a world that offers real animals at
            // all; never a ride.
            out.add("this world's Builder makes no real " + Catalogue.bare(spec.from()));
        }
        int most = BuilderCounts.most(this, spec.kind(), spec.from());   //
        if (spec.count() > most) {
            out.add("at most " + most + " at once here");
        }
        if (BuilderSupplies.is(spec)) {   //: the world's supplies limits
            BuilderSupplies.Supply s = BuilderSupplies.supply(spec.from());
            if (s != null && !supplies.offers(s)) {
                out.add("this world's Builder offers no " + BuilderSupplies.category(s.category()).label().toLowerCase(java.util.Locale.ROOT));
            } else if (s != null && BuilderSupplies.amount(spec) > BuilderSupplies.top(s, supplies)) {
                out.add("at most " + BuilderSupplies.top(s, supplies) + " " + s.label().toLowerCase(java.util.Locale.ROOT) + " at once here");
            }
        }
        return out;
    }

    /**
     * A world's {@code crafters.builder} block: {@code {"kinds":[…],"ids":[…],"temperaments":[…],"maxCount":n,
     * "itemTier":…,"supplies":{"categories":[…],"most":n}}} ({@code lava} is Builder-wide).
     */
    public static BuilderAllowlist fromJson(JsonObject j, BuilderAllowlist fallback) {
        if (j == null) {
            return fallback;
        }
        Set<BuilderSpec.Kind> kinds = new LinkedHashSet<>(fallback.kinds());
        if (j.has("kinds")) {
            kinds.clear();
            for (String k : strings(j.getAsJsonArray("kinds"))) {
                BuilderSpec.Kind kind = BuilderSpec.Kind.of(k);
                if (kind == null) {
                    throw new IllegalArgumentException("crafters.builder.kinds: no kind '" + k + "'");
                }
                kinds.add(kind);
            }
        }
        Set<String> ids = j.has("ids") ? new LinkedHashSet<>(strings(j.getAsJsonArray("ids"))) : fallback.ids();
        for (String id : ids) {
            String problem = Catalogue.problem(id);
            if (problem != null) {
                throw new IllegalArgumentException("crafters.builder.ids: " + problem);
            }
        }
        Set<String> tempers = j.has("temperaments")
                ? new LinkedHashSet<>(strings(j.getAsJsonArray("temperaments"))) : fallback.tempers();
        for (String t : tempers) {
            if (!BuilderLowering.tempers().contains(t)) {
                throw new IllegalArgumentException("crafters.builder.temperaments: no '" + t + "'");
            }
        }
        int max = j.has("maxCount") ? j.get("maxCount").getAsInt() : fallback.maxCount();
        String tier = j.has("itemTier") ? j.get("itemTier").getAsString() : fallback.itemTier();
        if (!BuilderItemPowers.TIERS.contains(tier)) {
            throw new IllegalArgumentException("crafters.builder.itemTier is one of " + BuilderItemPowers.TIERS + ", not '" + tier + "'");
        }
        BuilderSupplies.Limits supplies = j.has("supplies") && j.get("supplies").isJsonObject()
                ? BuilderSupplies.Limits.fromJson(j.getAsJsonObject("supplies"), fallback.supplies()) : fallback.supplies();
        if (j.has("lava")) {
            // The lava knob is gone: lava is offered in every world. The worlds' files are
            // ours and were migrated in the same change, so an old key is refused loudly rather than read as nothing.
            throw new IllegalArgumentException("crafters.builder.lava is gone: lava is offered in every world");
        }
        return new BuilderAllowlist(kinds, ids, tempers, max, tier, supplies);
    }

    private static List<String> strings(JsonArray a) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : a) {
            out.add(e.getAsString());
        }
        return out;
    }
}
