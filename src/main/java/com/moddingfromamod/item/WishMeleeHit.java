package com.moddingfromamod.item;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;

/**
 * <b>"…when it HITS mobs."</b>
 *
 * <p>The melee twin of {@link WishThrowImpact}, and it exists because of a wish he actually typed:
 * <i>a new type of sword that does something when it hits mobs</i>. The effect such a wish wants is
 * usually already in the catalogue; what was missing was the TRIGGER. {@code held} means
 * right-click, so a sword wish was granted as a wand — the child got a thing he waves rather than a
 * thing he swings, and every gate stayed green because a wand is a perfectly good item.
 *
 * <p>Same shape as the throw impact deliberately, rather than a second mechanism: an item carries
 * the function to run, and this runs it <b>at what was hit</b>, as the player who swung. That is
 * the piece a datapack cannot get for itself — a function can be told a player attacked, but not
 * with which item, or which of several wish swords.
 *
 * <p><b>Not a sword feature.</b> Nothing here knows what a sword is: any wish item at all can carry
 * the tag, so <i>"a stick that sets things on fire when you whack them"</i> and <i>"gloves that
 * heal what you touch"</i> are the same mechanism. The catalogue's 17 effects all compose with it
 * for free, which is the whole reason it is a trigger and not an effect.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class WishMeleeHit {

    private WishMeleeHit() {
    }

    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        var player = event.getEntity();
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        // THE HAND HE SWUNG WITH, which is always the main hand for an attack — an off-hand item is
        // not what lands the blow, and running its function too would fire two wishes per swing.
        ItemStack held = player.getMainHandItem();
        String fn = WishItem.onHit(held);
        if (fn.isEmpty()) {
            return;
        }
        Identifier id = Identifier.tryParse(fn);
        if (id == null) {
            return;
        }
        Entity target = event.getTarget();
        // AT the thing he hit, AS him. The same pair of choices the throw impact makes, for the
        // same reason: an effect that lands on the target needs to be positioned there, and one
        // that hands something back needs `@s` to mean the child.
        CommandSourceStack source = com.moddingfromamod.compat.EntityCommands.source(player)
                .withPosition(target.position())
                .withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER)
                .withSuppressedOutput();
        var server = level.getServer();
        WishBoltGuard.mark(player);   //: a bolt at what he hit reaches him too; his own wish never strikes him
        server.getFunctions().get(id)
                .ifPresent(f -> server.getFunctions().execute(f, source));
    }
}
