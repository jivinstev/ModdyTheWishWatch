package com.moddingfromamod.entity;

import net.minecraft.world.entity.EntitySpawnReason;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LeapAtTargetGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * The one custom creature. Everything that makes it <i>this</i> creature rather than another —
 * appearance, size, stats, behaviour — comes from a {@link Species} looked up by id.
 *
 * <p>Why one entity type instead of one per creature: Minecraft freezes its registries during
 * startup, so a genuinely new {@code EntityType} can never appear at runtime. By making the species
 * a synced <em>data</em> field, an agent (or a child) can invent unlimited creatures and play with
 * them <b>without restarting the game</b> — which is the entire point of this tier.
 *
 * <p>The species id is synced to clients so the renderer can pick a model and texture, and it's
 * saved to NBT so a creature is still itself after a reload.
 *
 * ♻️ SHIM (an old save's creature): this entity type stays REGISTERED although nothing
 * spawns one that survives a tick — {@code VanillaRigBridge} converts every wished creature to a
 * display rig as it joins. It is kept because it is what an old save's entity data deserialises
 * into, and an unregistered type is a creature the child loses. Row and end condition:
 * {@code docs/COMPAT_SHIMS.md}.
 */
public class WishMobEntity extends PathfinderMob {

    private static final EntityDataAccessor<String> SPECIES_ID =
            SynchedEntityData.defineId(WishMobEntity.class, EntityDataSerializers.STRING);

    private static final String NBT_SPECIES = "WishSpecies";

    /** Guards against re-applying attributes every tick; reset when the species changes. */
    private String appliedSpecies = null;

    /** Which SpeciesRegistry revision those attributes came from. */
    private int appliedRevision = -1;

    public WishMobEntity(EntityType<? extends WishMobEntity> type, Level level) {
        super(type, level);
    }

    /**
     * Baseline attributes. Real values are applied per-species in {@link #applySpecies}; these only
     * have to exist, because an entity type with a missing attribute crashes on spawn.
     */
    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.ATTACK_DAMAGE, 3.0)
                .add(Attributes.ARMOR, 0.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.0)
                .add(Attributes.FOLLOW_RANGE, 24.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(SPECIES_ID, "");
    }

    public String speciesId() {
        return this.entityData.get(SPECIES_ID);
    }

    /** Set the species and (server-side) apply its stats and behaviour. */
    public void setSpeciesId(String id) {
        this.entityData.set(SPECIES_ID, id == null ? "" : id);
        if (!this.level().isClientSide()) {
            applySpecies(true);
        }
    }

    /** The species this creature is, or null if its definition is missing. */
    public Species species() {
        return SpeciesRegistry.get(speciesId());
    }

    /**
     * The collision box, measured from the creature's own sculpted model instead of a fixed guess.
     *
     * <p><b>The bug this fixes.</b> Every wish mob used the registered {@code EntityType} box —
     * {@code 0.6 x 1.95}, a player — scaled only by the SCALE attribute. For Spikor (56 units tall,
     * scale 2.6) that gave a hitbox 5.07 blocks high on a creature drawn <b>9.10</b> blocks high:
     * his whole head and shoulders had no collision, so arrows and sword swings passed straight
     * through them. It also misled the wish agent, which can query the hitbox via
     * {@code run_command} and reasonably assumed it described the creature.
     *
     * <p>Vanilla's {@link net.minecraft.world.entity.LivingEntity#getDimensions} is final and
     * already multiplies by {@code getScale()}, so what we return here is the <b>unscaled</b> model
     * size and the SCALE attribute still does its job — no double-scaling.
     *
     * <p>Existing creatures need no migration: this is measured on every {@code refreshDimensions()},
     * which already runs on spawn AND on load, so creatures sculpted before this change get the
     * right box the next time their world opens.
     *
     * <p>Falls back to the registered box whenever there is no custom model to measure (a
     * {@code define_creature} species reusing a vanilla skin, an absent model folder, unparseable
     * geometry) — a creature with a slightly wrong box beats one that fails to size at all.
     */
    @Override
    protected net.minecraft.world.entity.EntityDimensions getDefaultDimensions(
            net.minecraft.world.entity.Pose pose) {
        net.minecraft.world.entity.EntityDimensions base = super.getDefaultDimensions(pose);
        Species s = species();
        if (s == null || !s.hasCustomModel()) {
            return base;
        }
        try {
            var geometry = com.moddingfromamod.runtime.ModelRepository.geometry(s.modelKey());
            if (geometry == null) {
                return base;
            }
            var extents = com.moddingfromamod.runtime.CreatureAnatomy.extents(geometry);
            if (extents.isEmpty()) {
                return base;
            }
            float w = com.moddingfromamod.runtime.CreatureAnatomy.widthBlocks(extents);
            float h = com.moddingfromamod.runtime.CreatureAnatomy.heightBlocks(extents);
            // Eyes at ~85% of height is the vanilla humanoid ratio, and only affects where the mob
            // looks FROM; CreatureAnatomy's landmarks are what an aimed effect should use.
            return net.minecraft.world.entity.EntityDimensions.scalable(w, h)
                    .withEyeHeight(h * 0.85f);
        } catch (RuntimeException e) {
            // Sizing must never be the thing that stops a creature existing.
            return base;
        }
    }

    /**
     * Push the species' numbers onto the entity and rebuild its goals.
     *
     * <p>Called on spawn, on load, and whenever the species changes — including after an agent
     * <em>edits</em> a species, so tweaking a creature takes effect on the ones already in the world.
     */
    public void applySpecies() {
        applySpecies(false);
    }

    /**
     * @param freshSpawn true only for a brand-new creature. On LOAD this must be false, or a
     *                   creature the child fought would silently heal to full every time the world
     *                   reloads (and every time an agent tweaks its species).
     */
    public void applySpecies(boolean freshSpawn) {
        Species s = species();
        if (s == null) {
            return;
        }
        appliedSpecies = s.id();
        appliedRevision = SpeciesRegistry.revision();

        set(Attributes.MAX_HEALTH, s.health());
        set(Attributes.MOVEMENT_SPEED, s.speed());
        set(Attributes.ATTACK_DAMAGE, s.damage());
        set(Attributes.ARMOR, s.armor());
        set(Attributes.KNOCKBACK_RESISTANCE, s.knockbackResistance());
        // SCALE resizes the model AND the hitbox — no renderer work needed for a giant creature.
        set(Attributes.SCALE, s.scale());
        if (freshSpawn) {
            this.setHealth((float) s.health());
        } else {
            // Keep whatever health it had, but never above the (possibly new) maximum.
            this.setHealth(Math.min(this.getHealth(), (float) s.health()));
        }

        this.setCustomName(Component.literal(s.name()));
        this.setCustomNameVisible(true);
        this.setGlowingTag(s.has(Species.Power.GLOWING));
        this.setPersistenceRequired();

        rebuildGoals(s);
        applyIdentityTags(s);
        this.refreshDimensions();
    }

    /**
     * Stamp the selector tags for this creature's species, clearing any left by a previous one.
     *
     * <p>Done here rather than at spawn on purpose: {@code applySpecies} is the one method that runs
     * on spawn, on world LOAD and on a species edit, so creatures that existed before tagging did
     * get their tag the next time the world opens. No migration, same trick as the hitbox.
     *
     * <p>See {@link WishMobTags} for why a display name was never an acceptable substitute.
     */
    private void applyIdentityTags(Species s) {
        String want = WishMobTags.forSpecies(s.id());
        // Drop stale per-species tags first — re-sculpting under a new id must not leave a creature
        // answering to both names, or "@e[tag=wishmob_spikor]" starts matching last week's dragon.
        for (String tag : new java.util.ArrayList<>(this.getTags())) {
            if (WishMobTags.isSpeciesTag(tag) && !tag.equals(want)) {
                this.removeTag(tag);
            }
        }
        this.addTag(WishMobTags.ALL);
        this.addTag(want);
    }

    private void set(net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attr,
                     double value) {
        AttributeInstance inst = this.getAttribute(attr);
        if (inst != null) {
            inst.setBaseValue(value);
        }
    }

    /**
     * Compose behaviour from the species' powers and its {@link Species.Base} profile.
     *
     * <p>The base profile is how a runtime creature borrows a vanilla mob's instincts. It can't
     * literally subclass {@code IronGolem} — registries freeze at load — but composing the same goal
     * set gets the feel: a guardian hunts monsters and is safe around people, a companion follows you.
     */
    private void rebuildGoals(Species s) {
        this.goalSelector.removeAllGoals(g -> true);
        this.targetSelector.removeAllGoals(g -> true);

        this.goalSelector.addGoal(0, new FloatGoal(this));
        Species.Base base = s.base();

        if (base == Species.Base.COWARD || s.has(Species.Power.SKITTISH)) {
            this.goalSelector.addGoal(1, new PanicGoal(this, 1.4));
        }

        boolean fights = switch (base) {
            case GUARDIAN, HUNTER -> true;
            case COWARD, COMPANION -> false;
            case WILD -> s.isHostile();
        };
        if (fights) {
            if (s.has(Species.Power.LEAPS)) {
                this.goalSelector.addGoal(2, new LeapAtTargetGoal(this, 0.4F));
            }
            this.goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.0, false));
            this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
            switch (base) {
                case GUARDIAN ->
                        // Hunts monsters, never people — the iron-golem contract.
                        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(
                                this, net.minecraft.world.entity.monster.Monster.class, true));
                case HUNTER -> {
                    this.targetSelector.addGoal(2,
                            new NearestAttackableTargetGoal<>(this, Player.class, true));
                    this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(
                            this, net.minecraft.world.entity.animal.Animal.class, true));
                }
                default -> this.targetSelector.addGoal(2,
                        new NearestAttackableTargetGoal<>(this, Player.class, true));
            }
        }

        if (base == Species.Base.COMPANION) {
            // Stick close to whoever's nearest, like a pet trotting alongside you.
            this.goalSelector.addGoal(4, new FollowPlayerGoal(this, 1.1, 3.0F, 12.0F));
        }

        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 1.0));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
    }

    /**
     * Follow the nearest player around. Vanilla's {@code FollowOwnerGoal} needs a tamed mob with an
     * owner UUID, which a wished-for creature doesn't have, so this is the "my pet" equivalent.
     */
    private static final class FollowPlayerGoal extends net.minecraft.world.entity.ai.goal.Goal {
        private final PathfinderMob mob;
        private final double speed;
        private final float stopDistance;
        private final float startDistance;
        private Player target;
        private int repathCooldown;

        FollowPlayerGoal(PathfinderMob mob, double speed, float stopDistance, float startDistance) {
            this.mob = mob;
            this.speed = speed;
            this.stopDistance = stopDistance;
            this.startDistance = startDistance;
            this.setFlags(java.util.EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            Player p = mob.level().getNearestPlayer(mob, startDistance);
            if (p == null || p.isSpectator()) {
                return false;
            }
            target = p;
            return mob.distanceToSqr(p) > stopDistance * stopDistance;
        }

        @Override
        public boolean canContinueToUse() {
            return target != null && target.isAlive() && !target.isSpectator()
                    && mob.distanceToSqr(target) > stopDistance * stopDistance;
        }

        @Override
        public void stop() {
            target = null;
            mob.getNavigation().stop();
        }

        @Override
        public void tick() {
            if (target == null) {
                return;
            }
            mob.getLookControl().setLookAt(target, 10.0F, mob.getMaxHeadXRot());
            if (--repathCooldown <= 0) {
                repathCooldown = 10;
                mob.getNavigation().moveTo(target, speed);
            }
        }
    }

    /** Per-creature memory of when each ability last fired. */
    private final com.moddingfromamod.runtime.Abilities.Cooldowns cooldowns =
            new com.moddingfromamod.runtime.Abilities.Cooldowns();

    /** The boss bar, created lazily for a species with the BOSS power. */
    private net.minecraft.server.level.ServerBossEvent bossBar;

    /**
     * Run the creature's special moves. The <em>timing</em> decision is made by the pure
     * {@link com.moddingfromamod.runtime.Abilities} helper; this method only carries it out.
     */
    private void tickAbilities(Species s) {
        if (s.abilities().isEmpty()) {
            return;
        }
        LivingEntity target = this.getTarget();
        double distance = target == null ? -1 : this.distanceTo(target);
        var kind = com.moddingfromamod.runtime.Abilities.choose(
                s.abilities(), this.tickCount, distance, cooldowns);
        if (kind == null) {
            return;
        }
        if (!(this.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }
        boolean fired = switch (kind) {
            case RANGED -> shoot(level, target);
            case SLAM -> slam(level);
            case CHARGE -> charge(target);
            case SUMMON -> summon(level, s);
            case TELEPORT -> blink(level, target);
            case HEAL -> heal();
        };
        if (fired) {
            cooldowns.use(kind, this.tickCount);
            // Swing on the big physical moves so the creature's attack ANIMATION plays with them —
            // otherwise a club smash is an invisible shockwave and the child never sees the swing.
            if (kind == com.moddingfromamod.runtime.Abilities.Kind.SLAM
                    || kind == com.moddingfromamod.runtime.Abilities.Kind.CHARGE) {
                this.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            }
        }
    }

    /** Spit a snowball-ish projectile. Harmless to the world, still exciting. */
    private boolean shoot(net.minecraft.server.level.ServerLevel level, LivingEntity target) {
        if (target == null) {
            return false;
        }
        var ball = com.moddingfromamod.compat.Projectiles.snowball(level, this);
        double dx = target.getX() - this.getX();
        double dy = target.getY(0.5) - ball.getY();
        double dz = target.getZ() - this.getZ();
        ball.shoot(dx, dy + Math.sqrt(dx * dx + dz * dz) * 0.2, dz, 1.4F, 4.0F);
        level.addFreshEntity(ball);
        this.playSound(net.minecraft.sounds.SoundEvents.SNOW_GOLEM_SHOOT, 1.0F, 1.0F);
        return true;
    }

    /** A ground slam: everything close by gets knocked away. Never breaks blocks. */
    private boolean slam(net.minecraft.server.level.ServerLevel level) {
        double radius = 5.0;
        boolean any = false;
        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class,
                this.getBoundingBox().inflate(radius), e -> e != this && e.isAlive())) {
            double dx = victim.getX() - this.getX();
            double dz = victim.getZ() - this.getZ();
            double len = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
            victim.push(dx / len * 0.8, 0.5, dz / len * 0.8);
            victim.hurt(this.damageSources().mobAttack(this),
                    (float) this.getAttributeValue(Attributes.ATTACK_DAMAGE) * 0.5F);
            any = true;
        }
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.EXPLOSION,
                this.getX(), this.getY(), this.getZ(), 8, 1.5, 0.2, 1.5, 0.0);
        this.playSound(net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value(), 1.0F, 1.4F);
        return any;
    }

    /** A burst of speed straight at the target. */
    private boolean charge(LivingEntity target) {
        if (target == null) {
            return false;
        }
        double dx = target.getX() - this.getX();
        double dz = target.getZ() - this.getZ();
        double len = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
        this.push(dx / len * 1.2, 0.35, dz / len * 1.2);
        this.playSound(net.minecraft.sounds.SoundEvents.RAVAGER_ROAR, 1.0F, 1.2F);
        return true;
    }

    /** Call in two small copies of itself. Capped so a summoner can't fork-bomb the world. */
    private boolean summon(net.minecraft.server.level.ServerLevel level, Species s) {
        int nearby = level.getEntitiesOfClass(WishMobEntity.class,
                this.getBoundingBox().inflate(24.0)).size();
        if (nearby > 12) {
            return false;
        }
        for (int i = 0; i < 2; i++) {
            WishMobEntity helper = ModEntities.WISH_MOB.get().create(level, EntitySpawnReason.COMMAND);
            if (helper == null) {
                continue;
            }
            helper.snapTo(this.getX() + (i == 0 ? 1.5 : -1.5), this.getY(), this.getZ(),
                    this.getYRot(), 0);
            helper.setSpeciesId(s.id());
            helper.setMinion(true);
            level.addFreshEntity(helper);
            // Minions are small and weak, so summoning is dramatic rather than a difficulty spike.
            helper.setBaseScale(s.scale() * 0.5);
        }
        return true;
    }

    /** Blink next to the target. */
    private boolean blink(net.minecraft.server.level.ServerLevel level, LivingEntity target) {
        if (target == null) {
            return false;
        }
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.PORTAL,
                this.getX(), this.getY() + 1, this.getZ(), 24, 0.4, 0.8, 0.4, 0.4);
        this.teleportTo(target.getX(), target.getY(), target.getZ());
        this.playSound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, 1.0F, 1.0F);
        return true;
    }

    private boolean heal() {
        if (this.getHealth() >= this.getMaxHealth()) {
            return false;
        }
        this.heal(2.0F);
        if (this.level() instanceof net.minecraft.server.level.ServerLevel level) {
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.HEART,
                    this.getX(), this.getY() + 1, this.getZ(), 3, 0.3, 0.3, 0.3, 0.0);
        }
        return true;
    }

    /** A summoned helper — flagged so minions never summon their own minions. */
    private boolean minion;

    public void setMinion(boolean value) {
        this.minion = value;
    }

    /** A summoned helper: the rig is built at its own (smaller) scale, not the species'. */
    public boolean isMinion() {
        return minion;
    }

    /** Override the scale independently of the species (used to shrink minions). */
    public void setBaseScale(double scale) {
        var inst = this.getAttribute(Attributes.SCALE);
        if (inst != null) {
            inst.setBaseValue(Math.max(Species.MIN_SCALE, Math.min(Species.MAX_SCALE, scale)));
        }
        this.refreshDimensions();
    }

    @Override
    public void tick() {
        super.tick();
        // Pick up an agent's edits to the species without needing to re-spawn the creature.
        if (!this.level().isClientSide()) {
            String id = speciesId();
            // NOT just an id comparison: editing a creature keeps its id and changes its
            // contents, which is exactly the case "make it bigger" produces.
            boolean changed = !id.equals(appliedSpecies)
                    || appliedRevision != SpeciesRegistry.revision();
            if (!id.isEmpty() && changed && SpeciesRegistry.get(id) != null) {
                applySpecies();
            }
            Species s = species();
            // NOTHING below may run once the entity is gone. super.tick() above drives the death
            // sequence, and at deathTime==20 it calls remove() — which clears the boss bar. Without
            // this guard the very next line re-CREATED that bar on an entity that will never tick
            // again, orphaning an empty bar on screen forever, one per death. A child who killed
            // four Spikors was left with four of them. See.
            if (s != null && this.isAlive() && !this.isRemoved()) {
                // A minion never summons its own minions — otherwise one summoner becomes a swarm.
                if (!minion) {
                    tickAbilities(s);
                }
                tickBossBar(s);
            }
        }
    }

    /** Is a boss bar currently showing for this creature? Exists so the guard is testable. */
    public boolean hasBossBar() {
        return bossBar != null;
    }

    /** Show (and keep updated) a boss bar for a species with the BOSS power. */
    private void tickBossBar(Species s) {
        if (!s.has(Species.Power.BOSS)) {
            if (bossBar != null) {
                bossBar.removeAllPlayers();
                bossBar = null;
            }
            return;
        }
        if (bossBar == null) {
            bossBar = new net.minecraft.server.level.ServerBossEvent(this.getDisplayName(),
                    net.minecraft.world.BossEvent.BossBarColor.PURPLE,
                    net.minecraft.world.BossEvent.BossBarOverlay.PROGRESS);
        }
        bossBar.setProgress(Math.max(0f, Math.min(1f, this.getHealth() / this.getMaxHealth())));
        if (this.level() instanceof net.minecraft.server.level.ServerLevel level) {
            // Only players who can actually see it should have the bar on screen.
            java.util.Set<net.minecraft.server.level.ServerPlayer> near = new java.util.HashSet<>(
                    level.getEntitiesOfClass(net.minecraft.server.level.ServerPlayer.class,
                            this.getBoundingBox().inflate(48.0)));
            for (var p : new java.util.ArrayList<>(bossBar.getPlayers())) {
                if (!near.contains(p)) {
                    bossBar.removePlayer(p);
                }
            }
            for (var p : near) {
                bossBar.addPlayer(p);
            }
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        if (bossBar != null) {
            bossBar.removeAllPlayers();
            bossBar = null;
        }
        super.remove(reason);
    }

    @Override
    public void die(net.minecraft.world.damagesource.DamageSource cause) {
        Species s = species();
        // A parting bang — deliberately block-safe: this is a child's world, not a griefing tool.
        if (s != null && s.has(Species.Power.EXPLODES)
                && this.level() instanceof net.minecraft.server.level.ServerLevel level) {
            level.explode(this, this.getX(), this.getY(), this.getZ(), 2.0F, false,
                    net.minecraft.world.level.Level.ExplosionInteraction.NONE);
        }
        super.die(cause);
    }

    @Override
    public boolean isNoGravity() {
        Species s = species();
        return (s != null && s.has(Species.Power.FLIES)) || super.isNoGravity();
    }

    @Override
    public boolean fireImmune() {
        Species s = species();
        return (s != null && s.has(Species.Power.FIREPROOF)) || super.fireImmune();
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false; // wished-for creatures shouldn't quietly vanish
    }

    @Override
    protected void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput out) {
        super.addAdditionalSaveData(out);
        out.putString(NBT_SPECIES, speciesId());
    }

    @Override
    protected void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput in) {
        super.readAdditionalSaveData(in);
        in.getString(NBT_SPECIES).ifPresent(species -> {
            this.entityData.set(SPECIES_ID, species);
            if (!this.level().isClientSide()) {
                applySpecies();
            }
        });
    }

    /** Used by the renderer; safe on the client because the species id is synced. */
    public static boolean isWishMob(LivingEntity entity) {
        return entity instanceof WishMobEntity;
    }
}
