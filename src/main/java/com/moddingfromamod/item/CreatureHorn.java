package com.moddingfromamod.item;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.species.Species;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Instruments;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.List;

/**
 * <b>A horn named for the creature it calls</b> (it should have a clear
 * name, so that he knows what he is calling when he uses it).
 *
 * <p>A real goat horn — so a stock client holds, blows and hears it — named "Call Fierce Red Dragon",
 * its tooltip saying what blowing it does, and in its custom data the {@link CallPets} kind it calls
 * ({@code creature_<id>} or {@code event_<id>}). Blowing it tags him exactly as a made
 * {@code call_pets} item's function does, so a horn he was handed and a horn he wished for answer
 * through one path: only HIS creatures come, never a stranger's, and nothing is ever made.
 *
 * <p>Handed over by default for a RIDEABLE creature only: a mount is the creature
 * he needs to come back to him. A follower is already at his side and a creature told to stay must not
 * be called off its post; any other creature or crowd gets a horn when he asks for one. Never twice:
 * a re-wish or a button press of the same creature finds the one he has.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class CreatureHorn {

    /** The custom-data key holding the {@link CallPets} kind this horn calls. */
    public static final String CALLS = "mfam_calls";

    private CreatureHorn() {
    }

    /** The call kind for one wished creature. */
    public static String kindFor(Species sp) {
        return CallPets.creatureKind(sp.id());
    }

    /** A horn that calls {@code kind}, named for {@code what}. */
    public static ItemStack make(ServerPlayer holder, String kind, String what) {
        ItemStack s = new ItemStack(Items.GOAT_HORN);
        holder.level().registryAccess().lookupOrThrow(Registries.INSTRUMENT)
                .get(Instruments.PONDER_GOAT_HORN).ifPresent(h -> s.set(DataComponents.INSTRUMENT,
                        new net.minecraft.world.item.component.InstrumentComponent(h)));
        s.set(DataComponents.CUSTOM_NAME, Component.literal("Call " + what)
                .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withItalic(false)));
        s.set(DataComponents.LORE, new ItemLore(List.of(
                (Component) Component.literal("Blow it and your " + what + " comes to you.")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withItalic(false)),
                Component.literal("Only yours ever come.")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY).withItalic(false)))));
        CompoundTag tag = new CompoundTag();
        tag.putString(CALLS, kind);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return s;
    }

    /** The kind this stack calls, or null when it is not one of these horns. */
    public static String callsOf(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.is(Items.GOAT_HORN)) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || !data.contains(CALLS)) {
            return null;
        }
        String k = CallPets.normalise(data.copyTag().getStringOr(CALLS, ""));
        return CallPets.isKindWord(k) ? k : null;
    }

    /** Does he already hold a horn for {@code kind} — in his inventory or his Wish Bag? */
    public static boolean has(ServerPlayer player, String kind) {
        for (ItemStack s : player.getInventory().getNonEquipmentItems()) {
            if (kind.equals(callsOf(s))) {
                return true;
            }
        }
        if (kind.equals(callsOf(player.getOffhandItem()))) {
            return true;
        }
        for (ItemStack s : com.moddingfromamod.bag.WishBagService.contents(player.level().getServer(), player.getUUID())) {
            if (kind.equals(callsOf(s))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Hand him the horn for this creature unless he has it. Returns where it landed, in words, or
     * null when he already had one (nothing was given).
     */
    public static String giveIfMissing(ServerPlayer player, Species sp) {
        String kind = kindFor(sp);
        if (has(player, kind)) {
            return null;
        }
        var landed = com.moddingfromamod.item.delivery.WishDelivery.give(player, make(player, kind, sp.name()),
                com.moddingfromamod.item.delivery.WishDelivery.currentWishKey(),
                com.moddingfromamod.item.delivery.DeliveryPlan.Kind.HANDOVER, false);
        return com.moddingfromamod.item.delivery.DeliveryWords.where(landed);
    }

    /** Blowing it: the same call a made call_pets item makes (the horn still sounds, as ever). */
    @SubscribeEvent
    public static void onUse(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        String kind = callsOf(event.getItemStack());
        if (kind != null && !player.getCooldowns().isOnCooldown(event.getItemStack())) {
            player.addTag(CallPets.TAG + kind);
        }
    }
}
