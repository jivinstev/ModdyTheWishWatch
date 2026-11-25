package com.moddingfromamod.world;

import java.util.UUID;

import com.moddingfromamod.wishbuilder.MakeFacts;

/**
 * <b>A kept make, handed over</b> (#70), as the core tells a world ({@link WorldMakes#handedOver}): whose it is, the
 * make's id and kind ({@code creature} or {@code item}), the recipe its hotbar wish fires (the same one "Make another
 * one" uses), its facts as this world made it, where it went (a hotbar slot 0 to 8, or {@code -1} for his Wish Bag or
 * the real item), and whether it is the real item ({@code false}: a wish he fires to make it). Data only: never a
 * world's words or anything he typed.
 */
public record HandedOver(UUID player, String makeId, String kind, String recipe, MakeFacts facts, int slot,
                         boolean realItem) {

    /** Did it go to his Wish Bag (the hotbar was full)? */
    public boolean inWishBag() {
        return slot < 0 && !realItem;
    }
}
