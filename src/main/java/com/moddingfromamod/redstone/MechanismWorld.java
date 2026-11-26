package com.moddingfromamod.redstone;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Puts a {@link MechanismPlan} into a real level and takes it back. Runs on the server thread.
 *
 * <p>Every block is placed with full neighbour updates ({@link Block#UPDATE_ALL}). The building tools place without
 * them, which is right for a house and wrong for a circuit: a circuit placed that way never powers up. What each
 * position held before is kept, so the whole mechanism can be taken back exactly.
 */
public final class MechanismWorld {

    /** What one position held before the mechanism replaced it. */
    public record Placed(BlockPos pos, BlockState before) {
    }

    /** The tag on the armor stand the proof stands on a pressure plate (never left behind). */
    public static final String PROOF_TAG = "moddy_mechanism_proof";

    private MechanismWorld() {
    }

    /** Places the plan in order; returns what each position held before, in placement order. */
    public static List<Placed> place(ServerLevel level, MechanismPlan plan) {
        List<Placed> placed = new ArrayList<>();
        for (RsCell c : plan.cells()) {
            BlockState put = parse(level, c.state());
            if (put == null) {
                throw new IllegalStateException("unknown block state " + c.state());
            }
            BlockPos at = LevelView.bp(c.pos());
            placed.add(new Placed(at, level.getBlockState(at)));
            level.setBlock(at, put, Block.UPDATE_ALL);
            if (!c.items().isEmpty()
                    && level.getBlockEntity(at) instanceof net.minecraft.world.Container box) {
                int slot = 0;
                for (String part : c.items().split(";")) {
                    String[] ic = part.split("\\*");
                    var item = BuiltInRegistries.ITEM.getValue(net.minecraft.resources.ResourceLocation.parse(ic[0]));
                    int n = ic.length > 1 ? Integer.parseInt(ic[1]) : 1;
                    while (n > 0 && slot < box.getContainerSize()) {
                        int k = Math.min(n, item.getDefaultMaxStackSize());
                        box.setItem(slot++, new net.minecraft.world.item.ItemStack(item, k));
                        n -= k;
                    }
                }
            }
        }
        return placed;
    }

    /** Puts back what each position held, last placed first. */
    public static void takeBack(ServerLevel level, List<Placed> placed) {
        removeProofStands(level, placed);
        // first clear everything we placed, so no piston or torch reacts to a half-restored circuit. A container we
        // filled is emptied first, or removing it would drop its contents on the ground (litter).
        for (int i = placed.size() - 1; i >= 0; i--) {
            if (level.getBlockEntity(placed.get(i).pos()) instanceof net.minecraft.world.Container box) {
                box.clearContent();
            }
        }
        for (int i = placed.size() - 1; i >= 0; i--) {
            level.setBlock(placed.get(i).pos(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                    Block.UPDATE_CLIENTS);
        }
        for (int i = placed.size() - 1; i >= 0; i--) {
            level.setBlock(placed.get(i).pos(), placed.get(i).before(), Block.UPDATE_ALL);
        }
    }

    /** Null when every expectation holds in the given state ({@code fired} or rest); else what is wrong. */
    public static String check(ServerLevel level, List<Expect> expects, boolean fired) {
        List<String> wrong = new ArrayList<>();
        for (Expect e : expects) {
            String want = fired ? e.fired() : e.rest();
            if ("*".equals(want)) {
                continue;
            }
            BlockState s = level.getBlockState(LevelView.bp(e.pos()));
            if (!matches(s, want)) {
                wrong.add(e.what() + " at " + e.pos() + " is " + describe(s) + ", should be " + want);
            }
        }
        return wrong.isEmpty() ? null : String.join("; ", wrong);
    }

    /** Uses a control the way a player would: on or off. Returns false when the control is not there. */
    /** True while a button or plate control is still down. */
    public static boolean pressed(ServerLevel level, Control c) {
        BlockState s = level.getBlockState(LevelView.bp(c.use()));
        if (s.getBlock() instanceof ButtonBlock) {
            return s.getValue(ButtonBlock.POWERED);
        }
        if (s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED)) {
            return s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED);
        }
        if (s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWER)) {
            return s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWER) > 0;
        }
        return false;
    }

    public static boolean use(ServerLevel level, Control c, boolean on) {
        BlockPos at = LevelView.bp(c.use());
        BlockState s = level.getBlockState(at);
        if (s.getBlock() instanceof LeverBlock lever) {
            if (s.getValue(LeverBlock.POWERED) != on) {
                lever.pull(s, level, at, null);
            }
            return true;
        }
        if (s.getBlock() instanceof ButtonBlock button) {
            if (on && !s.getValue(ButtonBlock.POWERED)) {
                button.press(s, level, at, null);
            }
            return true;                                  // a button lets go by itself
        }
        if (c.kind().equals("pressure_plate")) {
            if (on) {
                ArmorStand stand = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
                if (stand == null) {
                    return false;
                }
                stand.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
                stand.setInvisible(true);
                stand.addTag(PROOF_TAG);
                level.addFreshEntity(stand);
                // A plate notices an entity only when the entity's own movement checks the blocks it is inside,
                // and a stand that never moves may never do that (measured: the same test passed, then failed).
                // Run that check once, as a step down onto the plate; the plate then re-checks by itself.
                net.minecraft.world.phys.Vec3 feet = stand.position();
                stand.applyEffectsFromBlocks(feet.add(0, 0.5, 0), feet);
            } else {
                level.getEntitiesOfClass(ArmorStand.class, new net.minecraft.world.phys.AABB(at).inflate(1),
                        e -> e.getTags().contains(PROOF_TAG)).forEach(e -> e.discard());
            }
            return true;
        }
        return false;
    }

    static void removeProofStands(ServerLevel level, List<Placed> placed) {
        for (Placed p : placed) {
            BlockState s = level.getBlockState(p.pos());
            if (s.is(net.minecraft.tags.BlockTags.PRESSURE_PLATES)) {
                level.getEntitiesOfClass(ArmorStand.class, new net.minecraft.world.phys.AABB(p.pos()).inflate(1),
                        e -> e.getTags().contains(PROOF_TAG)).forEach(e -> e.discard());
            }
        }
    }

    /** True when {@code s} is the block named in {@code want} and has every property {@code want} lists. */
    static boolean matches(BlockState s, String want) {
        // "#open": a person can pass (air, a plant); "#shut": a block in the way. Used by the grader, which does
        // not know which block a door is made of, only whether the doorway is open or shut.
        if (want.equals("#open")) {
            return !s.blocksMotion();
        }
        if (want.equals("#shut")) {
            return s.blocksMotion() && !s.is(net.minecraft.world.level.block.Blocks.MOVING_PISTON);
        }
        int br = want.indexOf('[');
        String id = br < 0 ? want : want.substring(0, br);
        if (!BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString().equals(id)) {
            return false;
        }
        if (br < 0) {
            return true;
        }
        for (String kv : want.substring(br + 1, want.length() - 1).split(",")) {
            String[] p = kv.split("=", 2);
            Property<?> prop = s.getBlock().getStateDefinition().getProperty(p[0]);
            if (prop == null || !String.valueOf(s.getValue(prop)).equals(p[1])) {
                return false;
            }
        }
        return true;
    }

    static String describe(BlockState s) {
        StringBuilder b = new StringBuilder(BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString());
        for (Map.Entry<Property<?>, Comparable<?>> e : s.getValues().entrySet()) {
            if (e.getKey().getName().equals("extended") || e.getKey().getName().equals("powered")
                    || e.getKey().getName().equals("lit")) {
                b.append(' ').append(e.getKey().getName()).append('=').append(e.getValue());
            }
        }
        return b.toString();
    }

    static BlockState parse(ServerLevel level, String spec) {
        try {
            return net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(
                    level.registryAccess().lookupOrThrow(Registries.BLOCK), spec, false).blockState();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The live state of a mechanism's signal parts, for a failed proof: every wire's power, every torch lit or out,
     * every repeater and piston, and each control. A failure report names the first part that is not doing its job.
     */
    public static String trace(ServerLevel level, MechanismPlan plan) {
        StringBuilder b = new StringBuilder();
        for (RsCell c : plan.cells()) {
            String id = c.block();
            if (!(id.contains("redstone") || id.contains("repeater") || id.contains("piston") || id.contains("lever")
                    || id.contains("button") || id.contains("pressure_plate"))) {
                continue;
            }
            BlockState s = level.getBlockState(LevelView.bp(c.pos()));
            String short_ = s.getBlock().builtInRegistryHolder().key().location().getPath();
            String detail = s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWER)
                    ? "p" + s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWER)
                    : s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT)
                    ? (s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT) ? "lit" : "out")
                    : s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.EXTENDED)
                    ? (s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.EXTENDED) ? "ext" : "ret")
                    : s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED)
                    ? (s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED) ? "on" : "off")
                    : "";
            b.append(c.pos().x()).append(',').append(c.pos().y()).append(',').append(c.pos().z()).append('=')
                    .append(short_).append(detail.isEmpty() ? "" : ":" + detail).append(' ');
        }
        return b.toString().trim();
    }
}
