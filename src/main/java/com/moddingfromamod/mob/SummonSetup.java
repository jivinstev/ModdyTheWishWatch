package com.moddingfromamod.mob;

import java.util.Locale;

/**
 * <b>What a wished mob loses when its {@code summon} carried NBT, and must be given back</b>.
 * Pure: no Minecraft import, so the decision is a unit test.
 *
 * <p>Vanilla {@code SummonCommand.createEntity} calls {@code Mob.finalizeSpawn} only when the
 * command had NO NBT argument. The agent tags nearly everything it summons
 * ({@code summon minecraft:warden ~ ~ ~ {Tags:["…"]}}), so nearly every wished mob skips its
 * spawn setup. For most mobs that only means no random variant, baby roll or equipment — what the
 * agent asked for in the NBT is what it gets, which is right. For three it changes BEHAVIOUR, read
 * off each class's {@code finalizeSpawn} in the 1.21.4 sources (1.21.1 is the same):
 *
 * <ul>
 *   <li><b>warden</b> — {@code finalizeSpawn} sets the brain's {@code DIG_COOLDOWN} memory for
 *       1200 ticks. Without it the DIG activity's only gate (cooldown absent, no roar target) is open
 *       from the first tick: the warden burrows and {@code Digging.stop} DISCARDS it. Found in an
 *       archived grant: summoned with tags, reported "gone" to {@code modify_mob} six seconds later,
 *       and the spawn budget then refused the retry.</li>
 *   <li><b>phantom</b> — {@code anchorPoint} (what it circles) is set to five above its spawn;
 *       read from NBT only if {@code AX} is present, otherwise {@code BlockPos.ZERO}, so it flies off
 *       to circle the world origin.</li>
 *   <li><b>turtle</b> — its home is set to where it spawned; NBT reads {@code HomePosX/Y/Z}
 *       unconditionally (0 when absent), so its home becomes the world origin and it eventually
 *       swims off to lay eggs there.</li>
 * </ul>
 *
 * <p><b>Why not just call {@code finalizeSpawn} afterwards</b>: it re-rolls variants, equipment,
 * babies and jockeys and can overwrite what the NBT set (a piglin's weapon, a slime's size, a
 * fox's type). Instead each repair restores the ONE value and only when it is still at the
 * "nothing set it" default — so an agent that did set a dig cooldown, an anchor or a home in its
 * NBT keeps its own.
 */
public final class SummonSetup {

    /** One value {@code finalizeSpawn} would have set. */
    public enum Repair {
        /** Warden: {@code DIG_COOLDOWN} for 1200 ticks, as {@code Warden.finalizeSpawn}. */
        WARDEN_DIG_COOLDOWN,
        /** Phantom: circle five blocks above where it appeared, as {@code Phantom.finalizeSpawn}. */
        PHANTOM_ANCHOR,
        /** Turtle: home is where it appeared, as {@code Turtle.finalizeSpawn}. */
        TURTLE_HOME
    }

    /** {@code Warden.finalizeSpawn}'s cooldown, in ticks. */
    public static final long WARDEN_DIG_COOLDOWN_TICKS = 1200L;
    /** {@code Phantom.finalizeSpawn}'s anchor height above the spawn. */
    public static final int PHANTOM_ANCHOR_ABOVE = 5;

    private SummonSetup() {
    }

    /** The repair this entity type may need, or {@code null} when skipping its spawn setup is harmless. */
    public static Repair forType(String entityId) {
        if (entityId == null) {
            return null;
        }
        String id = entityId.toLowerCase(Locale.ROOT).trim();
        return switch (id) {
            case "minecraft:warden" -> Repair.WARDEN_DIG_COOLDOWN;
            case "minecraft:phantom" -> Repair.PHANTOM_ANCHOR;
            case "minecraft:turtle" -> Repair.TURTLE_HOME;
            default -> null;
        };
    }

    /**
     * Is the value still the default nothing set? For the warden {@code x,y,z} are ignored and
     * {@code hasValue} is whether the memory is present; for the anchor and the home they are the
     * stored position, and the origin {@code (0,0,0)} is the "never set" default both classes start at.
     */
    public static boolean needed(Repair r, boolean hasValue, int x, int y, int z) {
        if (r == null) {
            return false;
        }
        return switch (r) {
            case WARDEN_DIG_COOLDOWN -> !hasValue;
            case PHANTOM_ANCHOR, TURTLE_HOME -> x == 0 && y == 0 && z == 0;
        };
    }
}
