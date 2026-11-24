package com.moddingfromamod.datapack;

/**
 * <b>Can this block be a magic block's body, or what a fill fills with?</b>
 *
 * <p>The rule is pure and unit-tested ({@link #refusal}); only {@link #live} asks the game. Every
 * refusal is one a mod repo paid for:
 * <ul>
 *   <li><b>No block entities.</b> A chest body would be a real chest — and ten thousand ticking
 *       block entities placed as decoration froze a real client (a mod repo's Wither Storm house).</li>
 *   <li><b>Nothing unbreakable.</b> A walk-through BEDROCK dropped a player out of the world, and a
 *       solid one can never be broken, so it can never be picked back up or go off.</li>
 *   <li><b>Not air, and not a liquid, as a body.</b> Neither is a thing he can see and break; a
 *       fill may be a liquid, which is what "fill it with water" means.</li>
 * </ul>
 */
public final class BlockLook {

    private BlockLook() {
    }

    /** What the game says about a block id. */
    public record Facts(boolean exists, boolean air, boolean liquid, boolean holdsThings,
                        boolean unbreakable) {
    }

    /**
     * Null when it may be used, or why not.
     *
     * @param field       how to name it in the refusal ("block.look", "fill_blocks's BLOCK")
     * @param asBody      a body he sees and breaks (false: what a fill fills with)
     * @param walkThrough the body is drawn with no collision
     */
    public static String refusal(String field, String id, Facts f, boolean asBody, boolean walkThrough) {
        if (!f.exists()) {
            return field + " '" + id + "' is not a block in this game. Use a real block id like "
                    + "minecraft:gold_block.";
        }
        if (f.holdsThings()) {
            return field + " '" + id + "' is a block that HOLDS things (a chest, a furnace, a "
                    + "sign…). Those are never used here: it would be a real one, and thousands of "
                    + "them freeze the game. Pick a plain block that looks like it.";
        }
        if (f.unbreakable()) {
            return field + " '" + id + "' can never be broken"
                    + (walkThrough ? ", and walking through something that is supposed to be "
                            + "unbreakable is how a player falls out of the world" : "")
                    + ". Pick an ordinary block.";
        }
        if (asBody && (f.air() || f.liquid())) {
            return field + " '" + id + "' is not a solid thing he can see and break. For water or "
                    + "lava he can touch, use the fill_blocks effect; for the block itself pick a "
                    + "real block.";
        }
        return null;
    }

    /** Asks the running game. Unknown ids answer {@code exists=false}. */
    public static Facts live(String id) {
        try {
            net.minecraft.resources.Identifier key = net.minecraft.resources.Identifier.tryParse(id);
            if (key == null) {
                return new Facts(false, false, false, false, false);
            }
            var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
            if (block == null) {
                return new Facts(false, false, false, false, false);
            }
            return new Facts(true, block.defaultBlockState().isAir(),
                    block instanceof net.minecraft.world.level.block.LiquidBlock,
                    block instanceof net.minecraft.world.level.block.EntityBlock,
                    block.defaultDestroyTime() < 0);
        } catch (RuntimeException | ExceptionInInitializerError notBootstrapped) {
            // A unit test with no game: the registry cannot be asked, so do not pretend it said no.
            return new Facts(true, false, false, false, false);
        }
    }

    /** Whether an entity id names a real creature in the running game (true when it cannot ask). */
    public static boolean entityExists(String id) {
        try {
            net.minecraft.resources.Identifier key = net.minecraft.resources.Identifier.tryParse(id);
            return key != null && net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.containsKey(key);
        } catch (RuntimeException | ExceptionInInitializerError notBootstrapped) {
            return true;
        }
    }
}
