package com.moddingfromamod.item;

import java.util.Locale;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * <b>{@code wishput <made thing> [count]} — put a thing he wished for down HERE</b>: "a
 * slushie machine in my kitchen", "my magic swords in the chest", "a healing totem on the roof".
 *
 * <p>Run at a position — always under {@code wishspot <thing>:<spot> run …}, which is what
 * {@code make_item}'s {@code at} sends — it does what HE would have done with the thing in his hand,
 * there:
 * <ul>
 *   <li>a thing he PUTS DOWN (a placed thing, a machine, a magic block) is put down: the same
 *   marker his right-click makes ({@link WishItemActions#putDown}), so its datapack draws and runs
 *   it exactly as if he had placed it;</li>
 *   <li>anything else goes INTO a container at the spot (the chest on the counter, the barrel in the
 *   garage) when there is one at it, under it or beside it;</li>
 *   <li>otherwise it lies there, never despawning: a thing he asked to be somewhere must not vanish
 *   five minutes later.</li>
 * </ul>
 * A command, not only Java, so a taught wish replays it: {@code wishspot kitchen:inside run wishput
 * slushie_machine}.
 */
public final class WishPut {

    /** Most of one thing put down in one go — a row of totems, not a field of them. */
    public static final int MAX_COUNT = 16;

    private WishPut() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("wishput")
                // A REPLAY command, like wishdatapack: run by the agent and the executor at
                // permission 2, never typed by a child.
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("id", StringArgumentType.string())
                        .executes(ctx -> run(ctx.getSource(), StringArgumentType.getString(ctx, "id"), 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_COUNT))
                                .executes(ctx -> run(ctx.getSource(), StringArgumentType.getString(ctx, "id"),
                                        IntegerArgumentType.getInteger(ctx, "count"))))));
    }

    static int run(CommandSourceStack src, String id, int count) {
        Put p = put(src.getServer(), src.getLevel(), src.getPosition(), src.getRotation().y, id, count);
        if (p.why() != null) {
            src.sendFailure(Component.literal(p.why()));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("✨ " + p.said()), false);
        return p.count();
    }

    /** What one put did: how many, where they went, and a sentence — or why not. */
    public record Put(int count, String how, String said, String why) {
    }

    /**
     * Put {@code count} of the made thing {@code id} (its feature id, or the name he gave it) at
     * {@code at} in {@code level}, facing {@code yaw}.
     */
    public static Put put(MinecraftServer server, ServerLevel level, Vec3 at, float yaw, String id, int count) {
        ItemStack one = stackFor(server, id);
        if (one.isEmpty()) {
            return new Put(0, null, null, "There is no made thing called '" + id + "' to put there — make it "
                    + "with make_item first (wishput takes the feature id make_item named).");
        }
        int n = Math.max(1, Math.min(MAX_COUNT, count));
        String name = one.getHoverName().getString();
        String tag = WishItem.placeTag(one);
        if (!tag.isEmpty()) {
            // Put down, as his right-click would: n markers in a short row across the spot.
            int made = 0;
            for (int i = 0; i < n; i++) {
                double off = (i - (n - 1) / 2.0) * 1.0;
                double r = Math.toRadians(yaw);
                Vec3 p = new Vec3(at.x() + Math.cos(r) * off, at.y(), at.z() + Math.sin(r) * off);
                if (WishItemActions.putDown(level, p, yaw, tag) != null) {
                    made++;
                }
            }
            return made == 0 ? new Put(0, null, null, "Could not put '" + name + "' down there.")
                    : new Put(made, "placed", (made == 1 ? name : made + "× " + name)
                            + " put down there, working as if he had placed " + (made == 1 ? "it" : "them") + ".",
                    null);
        }
        Container box = containerNear(level, BlockPos.containing(at));
        if (box != null) {
            int in = 0;
            for (int i = 0; i < n; i++) {
                ItemStack left = HopperBlockEntity.addItem(null, box, one.copy(), (Direction) null);
                if (!left.isEmpty()) {
                    break;
                }
                in++;
            }
            if (in > 0) {
                box.setChanged();
                return new Put(in, "contained", (in == 1 ? name : in + "× " + name) + " put in the "
                        + ((BlockEntity) box).getBlockState().getBlock().getName().getString().toLowerCase(Locale.ROOT)
                        + (in < n ? " (it was full after " + in + ")" : "") + ".", null);
            }
        }
        for (int i = 0; i < n; i++) {
            ItemEntity e = new ItemEntity(level, at.x(), at.y() + 0.1, at.z(), one.copy(), 0, 0, 0);
            e.setUnlimitedLifetime();
            e.setDefaultPickUpDelay();
            level.addFreshEntity(e);
        }
        return new Put(n, "dropped", (n == 1 ? name : n + "× " + name) + " left lying there for him to pick "
                + "up (it does not despawn).", null);
    }

    /** A container at {@code p}, under it, or beside it at either height — the chest on the counter. */
    static Container containerNear(ServerLevel level, BlockPos p) {
        BlockPos[] look = {p, p.below(), p.north(), p.south(), p.east(), p.west(),
                p.below().north(), p.below().south(), p.below().east(), p.below().west()};
        for (BlockPos q : look) {
            if (level.getBlockEntity(q) instanceof Container c && level.getBlockEntity(q) instanceof BlockEntity) {
                return c;
            }
        }
        return null;
    }

    /**
     * One of the made thing {@code id}, as he would be handed it: the catalogue's row for it (
     * records it at the grant), found by the design's own name, or by the feature id its behaviour
     * carries. EMPTY when nothing knows it.
     */
    public static ItemStack stackFor(MinecraftServer server, String id) {
        if (id == null || id.isBlank()) {
            return ItemStack.EMPTY;
        }
        String fid = id.trim().toLowerCase(Locale.ROOT);
        ItemSpecStore.Spec spec = ItemSpecStore.find(fid);
        if (spec != null) {
            for (WishItemCatalogue.Entry e : WishItemCatalogue.resolve(spec.name())) {
                ItemStack s = WishItemCatalogue.stack(e, server.registryAccess());
                if (!s.isEmpty()) {
                    return s.copyWithCount(1);
                }
            }
        }
        for (WishItemCatalogue.Entry e : WishItemCatalogue.all()) {
            String json = e.stackJson();
            if (json != null && (json.contains("\"" + fid + "_") || json.contains("\"" + fid + ":"))) {
                ItemStack s = WishItemCatalogue.stack(e, server.registryAccess());
                if (!s.isEmpty()) {
                    return s.copyWithCount(1);
                }
            }
        }
        return ItemStack.EMPTY;
    }
}
