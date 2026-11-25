package com.moddingfromamod.world;

import java.util.UUID;

/**
 * <b>A kept make, handed over</b> (#70), as the core tells a world ({@link WorldMakes#handedOver}): whose it is, the
 * make's id and kind, its name, where it went (a hotbar slot 0 to 8, or {@code -1} for his Wish Bag), and whether it is
 * the real item ({@code false}: a wish he fires to make it).
 */
public record HandedOver(UUID player, String makeId, String kind, String name, int slot, boolean realItem) {

    /** Did it go to his Wish Bag (the hotbar was full)? */
    public boolean inWishBag() {
        return slot < 0;
    }
}
