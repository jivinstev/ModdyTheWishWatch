package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.wishbuilder.MakeFacts;
import com.moddingfromamod.wishbuilder.MakeFit;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>A creature fitted to a world's limits on the LIVE entity</b> (#14): the last step for health and attack damage,
 * when no size or strength step of its make lowers them ({@link MakeFit#LIVE}). One attribute modifier per stat, each
 * with its own FIXED id ({@link #MAX_HEALTH_ID}, {@link #ATTACK_DAMAGE_ID}), worked out to bring the value to the limit.
 * It is never stacked: fitting again removes it first and works it out afresh from what the creature has without it.
 *
 * <p>Put on at spawn, on the entity that holds the stats (a wish creature, or a rig's carrier; never a display part),
 * after the species has set its numbers. It is the entity's own: its save keeps it in this world, and nothing else does.
 * It is NEVER written into the spec, the shelf or any wish store, so the same make comes out at its own strength in
 * another world. A world module's own modifiers have their own ids and are left alone; the fit is worked out from the
 * value with them, so it brings the total to the limit. After it is put on the value is read again, as every fit is
 * re-derived. With no world answering, nothing is asked and nothing is put on.
 */
public final class LiveFit {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    private LiveFit() {
    }

    /** The modifier a world's health limit puts on a creature. Documented in {@code docs/EXTENDING.md}. */
    public static final Identifier MAX_HEALTH_ID =
            Identifier.fromNamespaceAndPath(ModdingFromAModMod.MODID, "world_fit/max_health");
    /** The modifier a world's attack damage limit puts on a creature. */
    public static final Identifier ATTACK_DAMAGE_ID =
            Identifier.fromNamespaceAndPath(ModdingFromAModMod.MODID, "world_fit/attack_damage");
    /** The modifier a world's armour limit puts on a creature. */
    public static final Identifier ARMOR_ID =
            Identifier.fromNamespaceAndPath(ModdingFromAModMod.MODID, "world_fit/armor");
    /** The modifier a world's knockback resistance limit puts on a creature. */
    public static final Identifier KNOCKBACK_RESISTANCE_ID =
            Identifier.fromNamespaceAndPath(ModdingFromAModMod.MODID, "world_fit/knockback_resistance");
    /** The modifier a world's movement speed limit puts on a creature (#174 C3). */
    public static final Identifier MOVEMENT_SPEED_ID =
            Identifier.fromNamespaceAndPath(ModdingFromAModMod.MODID, "world_fit/movement_speed");

    /**
     * Whose limits a wished body WITHOUT a species is held to (#174 C1): a vanilla mob a wish summoned or tweaked
     * ({@code wishsummon}), tagged with its wisher's UUID after this prefix so a later re-fit finds it again.
     */
    public static final String FIT_OWNER_PREFIX = "moddy_fit_owner:";

    /**
     * On a body a wish BROUGHT into the world by a way that leaves no other mark (a spawn egg a wish handed over): it is
     * held to the world's limits like any wished body (#174). Documented in {@code docs/EXTENDING.md}.
     */
    public static final String HELD_TAG = "moddy_held";

    /**
     * On a body the WORLD made that a wish then changed (a wild wolf {@code modify_mob} or a wish's {@code attribute}
     * or {@code effect} command made stronger): held to the world's limits too, but never below what its kind is
     * without any wish (its type's default), so a wish can never make the world's own creature weaker (#174).
     */
    public static final String TOUCHED_TAG = "moddy_touched";

    /** One stat fitted live: its fact, the attribute that holds it, and the fixed id of its modifier. */
    record Stat(String fact, Holder<Attribute> attribute, Identifier id) {
    }

    /** Every stat fitted live, one per {@code MakeFit.LIVE} fact. */
    static final List<Stat> STATS = List.of(
            new Stat(MakeFacts.HEALTH_HALF_HEARTS, Attributes.MAX_HEALTH, MAX_HEALTH_ID),
            new Stat(MakeFacts.ATTACK_DAMAGE_HALF_HEARTS, Attributes.ATTACK_DAMAGE, ATTACK_DAMAGE_ID),
            new Stat(MakeFacts.ARMOR_POINTS, Attributes.ARMOR, ARMOR_ID),
            new Stat(MakeFacts.KNOCKBACK_RESISTANCE_FRACTION, Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_RESISTANCE_ID),
            new Stat(MakeFacts.MOVEMENT_SPEED_ATTRIBUTE, Attributes.MOVEMENT_SPEED, MOVEMENT_SPEED_ID));

    /**
     * Fit the creature {@code mob}, of species {@code speciesId}, to this world's limits: the numbers it was fitted in
     * (empty when none). Server thread. Call after the species' own numbers are on it, at spawn or reload.
     */
    public static List<String> apply(LivingEntity mob, String speciesId) {
        return apply(mob, speciesId, false);
    }

    /**
     * …and, with {@code tell}, its owner told once when this is its first fit (a creature newly made: a Builder or
     * Apprentice make, "Make another one", the Magician's), unless a fitted line was said to him a moment ago for the
     * make it is. Only a fresh spawn tells (a creature loaded or rebuilt is never told again), and a rig that replaces
     * a wish creature does not.
     */
    public static List<String> apply(LivingEntity mob, String speciesId, boolean tell) {
        if (mob == null || speciesId == null || com.moddingfromamod.world.Wished.isWorldWished(mob)) {
            return List.of();   // a world's own creature: held by WorldWishes at the ceiling it named (holdWorld)
        }
        if (!MakeGate.anyWorld()) {
            // nobody answers here (now): its own numbers. A fit an answer put on before (the last region going away,
            // #182) comes off, so a held-down creature comes back; with none on, this changes nothing
            for (Stat st : STATS) {
                fit(mob, st.attribute(), st.id(), null);
            }
            mob.setHealth(Math.min(mob.getHealth(), mob.getMaxHealth()));
            return List.of();
        }
        var species = com.moddingfromamod.species.SpeciesRegistry.get(speciesId);
        UUID owner;
        try {
            owner = species == null || species.ownerId() == null || species.ownerId().isBlank()
                    ? new UUID(0L, 0L) : UUID.fromString(species.ownerId());
        } catch (IllegalArgumentException notAUuid) {
            owner = new UUID(0L, 0L);
        }
        MakeFacts facts = MakeFactsLookup.forThing(mob.level().getServer(), "creature", speciesId);
        // #182: a creature is fitted by the place it stands, so a region answers for the creatures inside it (whoever
        // owns them, wherever he is); LiveFitWatch fits it again when it crosses into or out of one
        WishAsk ask = WishAsk.replay(owner, "").withText(species == null ? "" : species.name());
        if (com.moddingfromamod.world.Regions.any()) {
            ask = ask.withWhere(new com.moddingfromamod.world.Bases.Pos(
                    mob.level().dimension().identifier().toString(), mob.getX(), mob.getY(), mob.getZ()));
        }
        LiveFitWatch.track(mob);   // indexed: fitted again when it crosses an edge or stands above its limits
        MakeVerdict v = MakeGate.ask(facts, ask);
        // A creature that already stands here was made: a verdict that would lock or refuse it NOW (a step that locks
        // its kind, a number it cannot be fitted to) must not take its fit away and leave it at full strength (#174
        // C2). It is held to the world's own limits where it stands instead.
        Map<String, Double> limits = v.makes() ? v.limits() : limitsAt(mob, owner);
        boolean had = false;
        for (Stat st : STATS) {
            had |= modifierOn(mob, st.attribute(), st.id()) != null;
        }
        List<String> fitted = new ArrayList<>();
        for (Stat st : STATS) {
            if (fit(mob, st.attribute(), st.id(), limits.get(st.fact()))) {
                fitted.add(st.fact());
            }
        }
        mob.setHealth(Math.min(mob.getHealth(), mob.getMaxHealth()));
        if (tell && !had && !fitted.isEmpty() && mob.level().getServer() != null && !MakeGate.toldLately(owner)) {
            MakeGate.sayFitted(mob.level().getServer().getPlayerList().getPlayer(owner), fitted);
        }
        return fitted;
    }

    /** One stat: its fit removed, then put back worked out from the value without it. True when one is on. */
    static boolean fit(LivingEntity mob, Holder<Attribute> attribute, Identifier id, Double limit) {
        var inst = mob.getAttribute(attribute);
        if (inst == null) {
            return false;   // the carrier does not hold this stat (a horse holds no attack damage)
        }
        // the fit's own change is not a wish changing the body (LiveFitWatch would only fit it again)
        LiveFitWatch.fitting(true);
        try {
            return fitInstance(inst, id, limit);
        } finally {
            LiveFitWatch.fitting(false);
        }
    }

    private static boolean fitInstance(net.minecraft.world.entity.ai.attributes.AttributeInstance inst,
                                       Identifier id, Double limit) {
        inst.removeModifier(id);
        if (limit == null) {
            return false;
        }
        double value = inst.getValue();
        if (value <= limit + 1e-9) {
            return false;
        }
        double add = limit - value;
        inst.addPermanentModifier(new AttributeModifier(id, add, AttributeModifier.Operation.ADD_VALUE));
        double after = inst.getValue();   // re-derived, as every fit is
        if (Math.abs(after - limit) > 1e-6 && Math.abs(add) > 1e-12) {
            // another modifier multiplies what is added (a wish's add_multiplied_total): the value is linear in the
            // fit's own amount, so one measured step finds the amount that lands on the limit
            double times = (after - value) / add;
            if (times > 1e-9) {
                inst.removeModifier(id);
                inst.addPermanentModifier(new AttributeModifier(id, (limit - value) / times,
                        AttributeModifier.Operation.ADD_VALUE));
                after = inst.getValue();
            }
        }
        if (after > limit + 1e-6) {
            LOGGER.warn("live fit of {} to {} left {} (another modifier multiplies it)", id, limit, after);
        }
        return true;
    }

    /**
     * <b>A world's own creature held at limits it names</b> (#178, {@code WorldWishes}): the same modifiers, with the
     * same fixed ids, as every live fit, so a world's monster is held by exactly the code that holds a child's. Server
     * thread. @return the facts it was fitted in.
     */
    public static List<String> holdWorld(LivingEntity mob, Map<String, Double> limits) {
        if (mob == null || limits == null || limits.isEmpty()) {
            return List.of();
        }
        List<String> fitted = new ArrayList<>();
        for (Stat st : STATS) {
            if (fit(mob, st.attribute(), st.id(), limits.get(st.fact()))) {
                fitted.add(st.fact());
            }
        }
        mob.setHealth(Math.min(mob.getHealth(), mob.getMaxHealth()));
        return fitted;
    }

    /**
     * <b>A wished body with no species, fitted to the world's limits</b> (#174 C1): a vanilla mob a wish made strong
     * with {@code wishsummon} (a hotbar fire of a learned recipe, {@code modify_mob}'s numbers on a real wolf). Those
     * never reach {@link #apply}, which fits by species, so a Super Wolf at attack 100 stood untouched in an arena
     * capped at 5. Its limits are its wisher's ({@code owner}, remembered on it as {@link #FIT_OWNER_PREFIX} so a
     * re-fit finds it); with no wisher known, the tightest any player here is held to, never none. Call after its
     * numbers are on it. Server thread. @return the facts it was fitted in.
     */
    public static List<String> applyWished(LivingEntity mob, UUID owner) {
        if (mob == null || mob.level().getServer() == null || speciesOf(mob) != null
                || mob instanceof net.minecraft.world.entity.player.Player
                || com.moddingfromamod.world.Wished.isWorldWished(mob)) {
            return List.of();
        }
        if (!MakeGate.anyWorld()) {
            // nobody answers here now (the last region went): a fit put on before comes off, as for a species creature
            for (Stat st : STATS) {
                fit(mob, st.attribute(), st.id(), null);
            }
            mob.setHealth(Math.min(mob.getHealth(), mob.getMaxHealth()));
            return List.of();
        }
        LiveFitWatch.track(mob);
        if (owner != null && !owner.equals(fitOwnerOf(mob))) {
            mob.getTags().removeIf(t -> t.startsWith(FIT_OWNER_PREFIX));
            mob.addTag(FIT_OWNER_PREFIX + owner);
        } else {
            owner = fitOwnerOf(mob);
        }
        Map<String, Double> limits = heldTo(mob, limitsAt(mob, owner));
        List<String> fitted = new ArrayList<>();
        for (Stat st : STATS) {
            if (fit(mob, st.attribute(), st.id(), limits.get(st.fact()))) {
                fitted.add(st.fact());
            }
        }
        mob.setHealth(Math.min(mob.getHealth(), mob.getMaxHealth()));
        return fitted;
    }

    /**
     * <b>Hold this body to the world's limits from now on</b> (#174): the one call for every route by which a wish
     * makes or changes a body with no species. A body no wish made ({@link #madeByAWish} false: a wild mob a wish then
     * changed) is marked {@link #TOUCHED_TAG}, so it is held only down to what its kind is without a wish; then it is
     * fitted ({@link #applyWished}) and, being marked, {@code LiveFitWatch} keeps it held whatever changes it later.
     * Server thread. @return the facts it was fitted in.
     */
    public static List<String> hold(LivingEntity mob, UUID owner) {
        if (mob == null || mob instanceof net.minecraft.world.entity.player.Player || speciesOf(mob) != null) {
            return List.of();
        }
        if (!madeByAWish(mob)) {
            mob.addTag(TOUCHED_TAG);
        }
        return applyWished(mob, owner);
    }

    /**
     * A spawn egg a wish hands over hatches a body held to the world's limits (#174): its {@code entity_data} carries
     * {@link #HELD_TAG} and the wisher, which the game puts on the mob it spawns, so {@code LiveFitWatch} holds it
     * wherever and whenever the child uses it. Only while a world answers (a plain game's eggs are left as vanilla
     * makes them); idempotent. Any other stack is left alone.
     */
    public static void markEgg(net.minecraft.world.item.ItemStack stack, net.minecraft.server.level.ServerPlayer wisher) {
        if (stack == null || wisher == null || !MakeGate.anyWorld()
                || !(stack.getItem() instanceof net.minecraft.world.item.SpawnEggItem egg)) {
            return;
        }
        // 1.21.9: entity_data is a TypedEntityData, its entity type held beside the tag rather than as the tag's "id"
        var data = stack.get(net.minecraft.core.component.DataComponents.ENTITY_DATA);
        net.minecraft.world.entity.EntityType<?> type = data != null ? data.type() : egg.getType(stack);
        net.minecraft.nbt.CompoundTag tag = data != null ? data.copyTagWithoutId() : new net.minecraft.nbt.CompoundTag();
        net.minecraft.nbt.ListTag tags = tag.getListOrEmpty("Tags").copy();
        java.util.Set<String> have = new java.util.HashSet<>();
        for (int i = 0; i < tags.size(); i++) {
            tags.getString(i).ifPresent(have::add);
        }
        if (have.contains(HELD_TAG)) {
            return;
        }
        tags.add(net.minecraft.nbt.StringTag.valueOf(HELD_TAG));
        tags.add(net.minecraft.nbt.StringTag.valueOf(FIT_OWNER_PREFIX + wisher.getUUID()));
        tag.put("Tags", tags);
        stack.set(net.minecraft.core.component.DataComponents.ENTITY_DATA,
                net.minecraft.world.item.component.TypedEntityData.of(type, tag));
    }

    /** Did a wish bring this body into the world (not merely change one the world made)? */
    static boolean madeByAWish(LivingEntity mob) {
        return com.moddingfromamod.world.Wished.isWished(mob) || mob.getTags().contains(HELD_TAG);
    }

    /**
     * The limits {@code mob} is held to: the world's, except that a body a wish only CHANGED is never held below its
     * own kind's default for a stat (a wild warden a wish slowed keeps its own bite). Pure but for the type's table.
     */
    static Map<String, Double> heldTo(LivingEntity mob, Map<String, Double> limits) {
        if (limits.isEmpty() || madeByAWish(mob) || !mob.getTags().contains(TOUCHED_TAG)) {
            return limits;
        }
        @SuppressWarnings("unchecked")
        var type = (net.minecraft.world.entity.EntityType<? extends LivingEntity>) mob.getType();
        if (!net.minecraft.world.entity.ai.attributes.DefaultAttributes.hasSupplier(type)) {
            return limits;
        }
        var own = net.minecraft.world.entity.ai.attributes.DefaultAttributes.getSupplier(type);
        Map<String, Double> floored = new java.util.HashMap<>(limits);
        for (Stat st : STATS) {
            Double limit = limits.get(st.fact());
            if (limit != null && own.hasAttribute(st.attribute())) {
                floored.put(st.fact(), Math.max(limit, own.getBaseValue(st.attribute())));
            }
        }
        return floored;
    }

    /** The limits a living wished body is held to right now, or empty when it is not held (for the watch). */
    static Map<String, Double> heldLimits(LivingEntity mob, Map<UUID, Map<String, Double>> perOwner) {
        String speciesId = speciesOf(mob);
        UUID owner = speciesId != null ? ownerOf(speciesId) : fitOwnerOf(mob);
        // asked by where it stands; remembered per owner only while no region could make two places differ
        Map<String, Double> limits = com.moddingfromamod.world.Regions.any() ? limitsAt(mob, owner)
                : perOwner.computeIfAbsent(owner == null ? new UUID(0L, 0L) : owner, k -> limitsAt(mob, owner));
        return speciesId != null ? limits : heldTo(mob, limits);
    }

    /** Is any fitted stat of {@code mob} above {@code limits}? */
    static boolean above(LivingEntity mob, Map<String, Double> limits) {
        for (Stat st : STATS) {
            Double limit = limits.get(st.fact());
            var inst = limit == null ? null : mob.getAttribute(st.attribute());
            if (inst != null && inst.getValue() > limit + 1e-6) {
                return true;
            }
        }
        return false;
    }

    /** The wisher a species-less wished body was fitted for ({@link #FIT_OWNER_PREFIX}), or null. */
    public static UUID fitOwnerOf(LivingEntity mob) {
        for (String t : mob.getTags()) {
            if (t.startsWith(FIT_OWNER_PREFIX)) {
                try {
                    return UUID.fromString(t.substring(FIT_OWNER_PREFIX.length()));
                } catch (IllegalArgumentException notAUuid) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Is this a wished body {@link #applyWished} fits: no species, and a wish's mark or a wisher on it? */
    static boolean fitsAsWished(LivingEntity mob) {
        return speciesOf(mob) == null && !(mob instanceof net.minecraft.world.entity.player.Player)
                && (markedByTags(mob.getTags()) || com.moddingfromamod.world.Wished.isWished(mob));
    }

    /** Do these tags mark a body the world's limits hold (a wish's mark, a wisher, held or touched)? Pure. */
    public static boolean markedByTags(java.util.Collection<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return false;
        }
        if (com.moddingfromamod.world.Wished.byTags(tags) || tags.contains(HELD_TAG) || tags.contains(TOUCHED_TAG)) {
            return true;
        }
        for (String t : tags) {
            if (t.startsWith(FIT_OWNER_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>The one way a living body's limits are asked</b> (#174, #182): {@code owner}'s, for the place the body
     * stands (a region there answers for what stands inside it, whoever owns it and wherever he is; with no region
     * anywhere, the world's limits for him, as before). With no owner known, the tightest any online player would be
     * held to there (fail closed, never none).
     */
    public static Map<String, Double> limitsAt(LivingEntity mob, UUID owner) {
        var server = mob.level().getServer();
        if (owner != null) {
            return limitsAt(mob, owner, server);
        }
        Map<String, Double> tightest = null;
        if (server != null) {
            for (net.minecraft.server.level.ServerPlayer p : server.getPlayerList().getPlayers()) {
                Map<String, Double> l = limitsAt(mob, p.getUUID(), server);
                tightest = tightest == null ? l : com.moddingfromamod.world.WorldLimits.tightest(tightest, l);
            }
        }
        return tightest == null ? limitsAt(mob, new UUID(0L, 0L), server) : tightest;
    }

    private static Map<String, Double> limitsAt(LivingEntity mob, UUID owner, net.minecraft.server.MinecraftServer server) {
        if (!com.moddingfromamod.world.Regions.any()) {
            return MakeGate.limits(owner).limits();
        }
        return MakeGate.limits(owner, new com.moddingfromamod.world.Bases.Pos(
                mob.level().dimension().identifier().toString(), mob.getX(), mob.getY(), mob.getZ())).limits();
    }
    /** The modifier on {@code mob} for this stat's fit, or null: for a test that it is there once, and only there. */
    public static AttributeModifier modifierOn(LivingEntity mob, Holder<Attribute> attribute, Identifier id) {
        var inst = mob.getAttribute(attribute);
        return inst == null ? null : inst.getModifier(id);
    }

    /** What a re-fit did to one player's creatures: how many were raised, lowered, or left as they were. */
    public record Refit(int raised, int lowered, int same) {
        /** The one line the player is told for this change, or empty when nothing moved. */
        public String line() {
            if (lowered > 0) {
                return HELD_DOWN;
            }
            return raised > 0 ? UNLEASHED : "";
        }
    }

    /** Said once when a world's limits hold a player's creatures down. */
    public static final String HELD_DOWN = "✨ Your creatures are held back a little in this world for now.";
    /** Said once when a world's limits let a player's creatures be stronger. */
    public static final String UNLEASHED = "✨ Your creatures are stronger now! This world lets them do more.";

    /**
     * <b>This player's limits changed</b> ({@code WorldMakes.limitsChanged}): every living creature his wishes made
     * is fitted again, from its own UNFITTED numbers (each fit removed and worked out afresh). A creature whose
     * health rises keeps how hurt it was (half its hearts before, half after: never healed to full, never stuck
     * half-empty); one whose health falls keeps what it has, up to its new most. He is told once for the change, not
     * once per creature. Server thread.
     */
    public static Refit refit(net.minecraft.server.MinecraftServer server, UUID player) {
        int raised = 0;
        int lowered = 0;
        int same = 0;
        int resized = 0;
        if (server == null || player == null) {
            return new Refit(0, 0, 0);
        }
        for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
            for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
                if (!(e instanceof LivingEntity mob) || !e.isAlive()) {
                    continue;
                }
                String speciesId = speciesOf(mob);
                if (speciesId == null) {
                    // A wished body with no species (#174 C1): his if he wished it, or nobody's (fitted to the
                    // tightest limits whenever any player's change).
                    if (!fitsAsWished(mob)) {
                        continue;
                    }
                    UUID wisher = fitOwnerOf(mob);
                    if (wisher != null && !player.equals(wisher)) {
                        continue;
                    }
                    int movedV = refitWished(mob);
                    if (movedV < 0) {
                        lowered++;
                    } else if (movedV > 0) {
                        raised++;
                    } else {
                        same++;
                    }
                    continue;
                }
                if (!player.equals(ownerOf(speciesId))) {
                    continue;
                }
                Moved m = refitOne(mob, speciesId);
                resized += m.resized() ? 1 : 0;
                if (m.moved() < 0) {
                    lowered++;
                } else if (m.moved() > 0) {
                    raised++;
                } else {
                    same++;
                }
            }
        }
        if (resized > 0) {
            // its size is drawn again where it stands: once for this change, never per tick. Each rig asks on its
            // next tick whether what it was built from is what it would build now (its drawn scale is in that
            // print), and only those whose size changed are reshaped; a wish creature sets its scale again.
            com.moddingfromamod.species.SpeciesRegistry.touch();
        }
        return told(server, player, new Refit(raised, lowered, same));
    }

    /** How one creature's fit moved: up (1), down (-1) or not (0), and whether its drawn size changed. */
    public record Moved(int moved, boolean resized) {
    }

    /**
     * Fit one living wish creature again, from its own unfitted numbers, its health kept as hurt as it was
     * ({@link #refit}'s step for each creature; {@code LiveFitWatch}'s for one that crossed a region's edge). Server
     * thread. Tells nobody.
     */
    public static Moved refitOne(LivingEntity mob, String speciesId) {
        double sizeWas = drawnNow(mob);
        double[] before = values(mob);
        double oldMax = mob.getMaxHealth();
        double oldHealth = mob.getHealth();
        apply(mob, speciesId, false);
        double newMax = mob.getMaxHealth();
        mob.setHealth((float) RefitWords.healthAfter(oldHealth, oldMax, newMax));
        double[] after = values(mob);
        double sizeNow = drawnScale(com.moddingfromamod.species.SpeciesRegistry.get(speciesId));
        boolean resized = Math.abs(sizeNow - sizeWas) > 1e-6;
        return new Moved(RefitWords.compare(RefitWords.with(before, sizeWas), RefitWords.with(after, sizeNow)), resized);
    }

    /**
     * Fit one wished body with no species again, where it stands, from its own unfitted numbers, its health kept as
     * hurt as it was. Tells nobody. @return up (1), down (-1) or not (0).
     */
    public static int refitWished(LivingEntity mob) {
        double[] was = values(mob);
        double oldMax = mob.getMaxHealth();
        double oldHealth = mob.getHealth();
        applyWished(mob, null);
        mob.setHealth((float) RefitWords.healthAfter(oldHealth, oldMax, mob.getMaxHealth()));
        return RefitWords.compare(RefitWords.with(was, 1.0), RefitWords.with(values(mob), 1.0));
    }

    /** Tell {@code player} once what a re-fit of his creatures did (nothing when nothing moved), and log it. */
    public static Refit told(net.minecraft.server.MinecraftServer server, UUID player, Refit r) {
        LiveFitWatch.fittedAt(player, MakeGate.limits(player).stamp());
        String line = r.line();
        net.minecraft.server.level.ServerPlayer p = server.getPlayerList().getPlayer(player);
        if (p != null && !line.isEmpty() && WishFeedback.sweeps(line, "")) {
            p.sendSystemMessage(net.minecraft.network.chat.Component.literal(line));
        }
        LOGGER.info("world limits changed for {}: {} creature(s) raised, {} lowered, {} as they were; told: {}", player,
                r.raised(), r.lowered(), r.same(), p == null ? "not online" : line.isEmpty() ? "nothing moved" : line);
        return r;
    }

    /** The size {@code mob} is drawn at now: a rig's built scale, else its own scale. */
    static double drawnNow(LivingEntity mob) {
        var rig = com.moddingfromamod.vanilla.VanillaRigAnimator.rigOf(mob);
        if (rig != null) {
            return rig.scale();
        }
        var inst = mob.getAttribute(Attributes.SCALE);
        return inst == null ? 1.0 : inst.getBaseValue();
    }

    private static double[] values(LivingEntity mob) {
        double[] v = new double[STATS.size()];
        for (int i = 0; i < STATS.size(); i++) {
            var inst = mob.getAttribute(STATS.get(i).attribute());
            v[i] = inst == null ? Double.NaN : inst.getValue();
        }
        return v;
    }

    /** The species a living wish creature is, or null: a wish creature's own, or the one a rig's carrier was built from. */
    public static String speciesOf(LivingEntity mob) {
        if (mob instanceof com.moddingfromamod.entity.WishMobEntity w) {
            return w.speciesId() == null || w.speciesId().isBlank() ? null : w.speciesId();
        }
        var built = com.moddingfromamod.vanilla.VanillaRigAnimator.builtFrom(mob.getUUID());
        return built == null ? null : built.id();
    }

    /** The owner of the species {@code speciesId}, or null. */
    public static UUID ownerOf(String speciesId) {
        var species = com.moddingfromamod.species.SpeciesRegistry.get(speciesId);
        try {
            return species == null || species.ownerId() == null || species.ownerId().isBlank()
                    ? null : UUID.fromString(species.ownerId());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    // ── a creature's size, fitted where it stands (C) ─────────────────────────────────────────────────────────────

    private static final Map<String, Double> SCALES = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The size {@code species} stands at in this world now: its own (asked) scale, fitted to this world's limits at
     * every spawn and never kept in its record or its make. A Builder creature is fitted the way its kept make is
     * (its asked spec through the one gate and fitting a make goes through, the size it would be made at now); any
     * creature is held to a {@code scaleTimes} limit. With no world answering, its own scale. Cheap: remembered per
     * species, limits stamp and reader generation. Server thread.
     */
    public static double drawnScale(com.moddingfromamod.species.Species species) {
        if (species == null) {
            return 1.0;
        }
        if (!MakeGate.anyWorld()) {
            return species.scale();
        }
        UUID owner = ownerOf(species.id());
        var limits = MakeGate.limits(owner);
        String key = species.id() + "|" + species.scale() + "|" + owner + "|" + limits.stamp() + "|"
                + MakeFacts.generation() + "|" + (com.moddingfromamod.wishbuilder.BuilderMake.askedWhileMaking(species.id())
                != null);
        Double hit = SCALES.get(key);
        if (hit != null) {
            return hit;
        }
        double scale = species.scale();
        try {
            scale = fittedScale(species, owner);
        } catch (RuntimeException e) {
            LOGGER.warn("live fit: {}'s size could not be fitted, standing at its own: {}", species.id(), e.toString());
        }
        if (SCALES.size() > 4096) {
            SCALES.clear();
        }
        SCALES.put(key, scale);
        return scale;
    }

    private static double fittedScale(com.moddingfromamod.species.Species species, UUID owner) {
        double scale = species.scale();
        var kept = owner == null ? null
                : com.moddingfromamod.wishbuilder.BuilderSpecStore.get(owner.toString(), species.id());
        var making = com.moddingfromamod.wishbuilder.BuilderMake.askedWhileMaking(species.id());
        if (owner != null && (making != null || kept != null && !kept.magicianTouched())) {
            var asked = making != null ? making : kept.read();
            if (asked != null && (asked.kind() == com.moddingfromamod.wishbuilder.BuilderSpec.Kind.CREATURE
                    || asked.kind() == com.moddingfromamod.wishbuilder.BuilderSpec.Kind.RIDE)) {
                var allow = com.moddingfromamod.wishbuilder.BuilderSettings.forPlayer(owner).allow();
                var built = MakeGate.build(asked, allow, WishAsk.replay(owner, ""));
                double was = MakeFacts.of(allow.capped(asked)).number(MakeFacts.SCALE_TIMES).orElse(0);
                double now = MakeFacts.of(built.spec()).number(MakeFacts.SCALE_TIMES).orElse(0);
                if (built.makes() && was > 0 && now > 0) {
                    scale = species.scale() * now / was;   // as much smaller (or as large again) as its make is fitted
                }
            }
        }
        // any creature: no bigger than this world's most size, whoever made it
        MakeFacts facts = new MakeFacts(com.moddingfromamod.wishbuilder.BuilderSpec.Kind.CREATURE.id(),
                Map.of(MakeFacts.SCALE_TIMES, scale), java.util.Set.of(), true);
        MakeVerdict v = MakeGate.ask(facts, WishAsk.replay(owner == null ? new UUID(0L, 0L) : owner, ""));
        Double most = v.makes() ? v.limits().get(MakeFacts.SCALE_TIMES) : null;
        return most == null ? scale : Math.min(scale, most);
    }
}
