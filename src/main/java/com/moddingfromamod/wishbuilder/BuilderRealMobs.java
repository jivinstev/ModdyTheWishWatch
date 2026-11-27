package com.moddingfromamod.wishbuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;

/**
 * <b>A real mob from the Builder, made stronger or faster</b> (#178). A {@code mob:} creature took only a named size
 * ({@code mobsize}); it now also takes How strong ({@code strength} 1–3) and {@code speed} ("1" slow, "3" fast), with the
 * Builder's own numbers ({@link BuilderCreatureStrength}) on the mob's own vanilla ones. One reading, so the lowering
 * (what is summoned) and {@link MakeFacts} (what a world's limits see) can never disagree.
 *
 * <p>A mob: spec that names neither is exactly what it always was: {@link #numbers} is null and nothing changes.
 */
public final class BuilderRealMobs {

    /** How much faster "fast" is, and how much slower "slow". */
    public static final double FAST = 1.25, SLOW = 0.8;

    private BuilderRealMobs() {
    }

    /** Its health, attack damage (0 for a mob with no melee) and movement speed, as the Builder makes it. */
    public record Numbers(double health, double damage, double speed) {
    }

    /** The vanilla mob's own numbers, or null for an id that is no living mob of this game. */
    public static Numbers base(String mobId) {
        Identifier id = Identifier.tryParse(mobId == null ? "" : mobId);
        EntityType<?> type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        if (type == null || !DefaultAttributes.hasSupplier(type)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        AttributeSupplier s = DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) type);
        double health = s.hasAttribute(Attributes.MAX_HEALTH) ? s.getValue(Attributes.MAX_HEALTH) : 0;
        double damage = s.hasAttribute(Attributes.ATTACK_DAMAGE) ? s.getValue(Attributes.ATTACK_DAMAGE) : 0;
        double speed = s.hasAttribute(Attributes.MOVEMENT_SPEED) ? s.getValue(Attributes.MOVEMENT_SPEED) : 0;
        return health <= 0 ? null : new Numbers(health, damage, speed);
    }

    /** Is this a Builder real mob ({@code mob:<id>})? */
    public static boolean isRealMob(BuilderSpec spec) {
        return spec != null && (spec.kind() == BuilderSpec.Kind.CREATURE || spec.kind() == BuilderSpec.Kind.RIDE)
                && Catalogue.prefix(spec.from()).equals("mob");
    }

    /** What a real mob is made at, or null when its spec names no How strong and no speed (it is as it always was). */
    public static Numbers numbers(BuilderSpec spec) {
        if (!isRealMob(spec) || spec.opt("strength").isEmpty() && spec.opt("speed").isEmpty()) {
            return null;
        }
        Numbers b = base(Catalogue.bare(spec.from()));
        if (b == null) {
            return null;
        }
        BuilderMobSizes.Level size = BuilderMobSizes.of(spec.opt("mobsize").isEmpty() ? BuilderMobSizes.NORMAL
                : spec.opt("mobsize"));
        int strength = BuilderCreatureStrength.strengthOf(spec);
        double h = BuilderCreatureStrength.health(b.health(), size, strength);
        double d = b.damage() <= 0 ? 0 : BuilderCreatureStrength.damage(b.damage(), size, strength, false, 0, false);
        double sp = b.speed() * switch (spec.opt("speed")) {
            case "3" -> FAST;
            case "1" -> SLOW;
            default -> 1.0;
        };
        return new Numbers(h, d, sp);
    }
}
