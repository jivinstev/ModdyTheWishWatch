package com.moddingfromamod.item;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * <b>Put a stack in a child's hands, and know whether it got there.</b>
 *
 * <p>Every path that hands something over used to be a line of the form
 * {@code if (!player.getInventory().add(stack)) player.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);} — which reads as
 * exactly right and, in the only game mode he plays in, has never once dropped anything.
 *
 * <h2>Why the obvious line is wrong</h2>
 *
 * <p>{@code Inventory.add} does not report a full bag to a Creative player. It discards the stack
 * and says it worked. From the 1.21.1 bytecode, the tail of {@code add(int, ItemStack)}:
 *
 * <pre>
 *   126: stack.getCount()
 *   131: if != the count we started with -&gt; 151   // something moved: ordinary path
 *   134: player.hasInfiniteMaterials()             // ... nothing moved, and he is in Creative
 *   144: stack.setCount(0)                         // the stack is thrown away
 *   149: return true                               // and this is reported as success
 * </pre>
 *
 * <p>So the {@code drop} branch is unreachable for him, and the caller above it goes on to report
 * how much it gave. Measured 2026-08-31 by {@code ItemRefsClientTest} on the server's own count:
 * 200 asked for with a full bag, <b>0 in the bag and 0 on the ground</b>, and {@code give_items}
 * saying <i>"Gave 200× minecraft:diamond"</i>. With this helper: 0 in the bag and 200 on the
 * ground, as four stacks of 64, 64, 64 and 8.
 *
 * <p><b>Vanilla {@code /give} has the same shape</b>, so a path that must go through the command —
 * a made item, whose datapack give line is the definition of what that item IS — still inherits it.
 * That is not fixable here; it is worth knowing before writing "the item always arrives".
 *
 * <p>One helper rather than the same three lines in each caller, because this is a defect
 * <i>class</i>: it was found in {@code give_items} and was already live in
 * {@code WishMobCommands}' egg hand-over, where a full bag ate the egg while the command said
 * <i>"🥚 Here's your egg!"</i>. {@code SourceRuleSweepTest} now fails the build on a fourth caller
 * reading {@code getInventory().add}'s boolean.
 */
public final class HandOver {

    private HandOver() {
    }

    /**
     * Give this stack to the player, on the floor at his feet if his bag is full.
     *
     * @return true if it went into the bag, false if it was dropped — a caller that tells him what
     *         he got should say where it is
     */
    public static boolean toPlayer(ServerPlayer player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        boolean intoBag = false;
        if (hasRoomFor(player, stack)) {
            player.getInventory().add(stack);
            intoBag = true;
        }
        // WHATEVER IS LEFT, on the ground. A partial add leaves its remainder in the stack (the
        // Creative discard above only fires when NOTHING moved), so this covers both "no room at
        // all" and "room for some of it".
        if (!stack.isEmpty()) {
            player.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
        }
        // THE CLIENT HAS TO BE TOLD. Adding to the server-side inventory alone leaves him staring
        // at an empty hotbar while the command reports success — vanilla's own /give calls this for
        // the same reason, and leaving it out once shipped an egg that existed only on the server.
        player.containerMenu.broadcastChanges();
        return intoBag && stack.isEmpty();
    }

    /**
     * <b>Is there anywhere for this stack to go?</b>
     *
     * <p>Asked BEFORE {@code Inventory.add}, because that method's boolean cannot answer it: with
     * nothing free it discards the stack and reports success. A free slot, or an existing stack of
     * the same item with headroom, is the whole of "there is room".
     */
    public static boolean hasRoomFor(ServerPlayer player, ItemStack stack) {
        var inv = player.getInventory();
        if (inv.getFreeSlot() >= 0) {
            return true;
        }
        for (ItemStack held : inv.getNonEquipmentItems()) {
            if (!held.isEmpty() && held.getCount() < held.getMaxStackSize()
                    && ItemStack.isSameItemSameComponents(held, stack)) {
                return true;
            }
        }
        return false;
    }
}
