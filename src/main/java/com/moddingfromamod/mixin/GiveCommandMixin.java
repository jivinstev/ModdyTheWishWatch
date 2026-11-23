package com.moddingfromamod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moddingfromamod.item.delivery.WishDelivery;
import com.moddingfromamod.mob.SpawnScope;

import net.minecraft.server.commands.GiveCommand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * <b>A wish's {@code /give} goes through the one delivery path</b>.
 *
 * <p>{@code GiveCommand.giveItem} hands each stack over through the inventory's own {@code add}
 * and then branches on the answer: added and emptied → vanilla's pickup sound and the "Gave"
 * feedback; anything left → dropped at his feet. That one call is wrapped here, and ONLY while
 * a scope is open — {@link SpawnScope} is open around every wish command, button, template and
 * rule replay, and (by {@code FunctionSpawnScopeMixin}) every datapack function,
 * compiled ones included, which is the path that lost the oasis wish's drinks. A grown-up's own
 * typed {@code /give} opens no scope and is vanilla to the byte.
 *
 * <p>{@link WishDelivery#give} empties the stack — a hand-over into the hotbar and the Wish Bag,
 * a thing's own behaviour into the hotbar, his inventory and the floor at his feet, by the open
 * scope's {@link SpawnScope#kind} — and returns true, so vanilla's own success branch runs
 * exactly as for any give that fitted: the same sound, the same feedback, the same
 * {@code broadcastChanges}. The wrap changes WHERE, never what the command reports.
 *
 * <p>A {@code @WrapOperation}, not a {@code @Redirect}: two mods redirecting the same call crash at load,
 * while wraps chain, so another mod that also wraps {@code Inventory.add} here still runs. With no scope open it calls
 * {@code original} — vanilla's call, or the next mod's wrap — unchanged.
 *
 * <p>The target is the same on 1.21.1 and 1.21.4 — {@code giveItem(CommandSourceStack, ItemInput,
 * Collection<ServerPlayer>, int)} calling {@code Inventory.add(ItemStack)} once — and
 * {@code defaultRequire: 1} makes a target that moved fail the boot rather than go quiet.
 */
@Mixin(GiveCommand.class)
public abstract class GiveCommandMixin {

    @WrapOperation(method = "giveItem",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Inventory;add(Lnet/minecraft/world/item/ItemStack;)Z"))
    private static boolean moddingfromamod$deliver(Inventory inventory, ItemStack stack, Operation<Boolean> original) {
        var scope = SpawnScope.current();
        if (scope == null || !(inventory.player instanceof net.minecraft.server.level.ServerPlayer sp)) {
            return original.call(inventory, stack);
        }
        // Announced only when it is a hand-over: if Moddy says it, it can go in the bag.
        WishDelivery.give(sp, stack, scope.wishKey(), scope.kind(), true);
        return true;
    }
}
