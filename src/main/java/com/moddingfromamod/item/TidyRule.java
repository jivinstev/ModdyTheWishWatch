package com.moddingfromamod.item;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * <b>A bag that tidies, the pure half.</b> Which group a thing belongs in, the order
 * the groups go in, and what counts as junk. Deliberately a closed list: "the coolest loot" is a
 * judgement, and the only honest version of it is a list someone chose and wrote down.
 */
public final class TidyRule {

    /** The groups, in the order they are laid out: one group per row where it fits. */
    public enum Group { BLOCKS, FOOD, TOOLS, OTHER }

    /** Junk: what a child digs, kills or picks up by the stack and never wanted. */
    public static final Set<String> JUNK = Set.of(
            "minecraft:dirt", "minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:gravel",
            "minecraft:netherrack", "minecraft:andesite", "minecraft:diorite", "minecraft:granite",
            "minecraft:tuff", "minecraft:rotten_flesh", "minecraft:poisonous_potato",
            "minecraft:wheat_seeds", "minecraft:spider_eye", "minecraft:string");

    /** One stack as the sort sees it. */
    public record Stack(String id, Group group, int count) {
    }

    private TidyRule() {
    }

    public static boolean isJunk(String id) {
        return JUNK.contains(id);
    }

    /** Group first, then the item's id, so like things sit together and the order is stable. */
    public static final Comparator<Stack> ORDER =
            Comparator.comparing(Stack::group).thenComparing(Stack::id);

    /**
     * Where each group starts in a 27-slot main inventory: each group on a fresh row of nine when
     * the whole layout still fits, otherwise packed.
     */
    public static int[] rowStarts(List<Stack> sorted) {
        int[] counts = new int[Group.values().length];
        for (Stack s : sorted) {
            counts[s.group().ordinal()]++;
        }
        int rows = 0;
        for (int c : counts) {
            rows += (c + 8) / 9;
        }
        boolean fresh = rows <= 3;
        int[] start = new int[counts.length];
        int at = 0;
        for (int g = 0; g < counts.length; g++) {
            start[g] = at;
            at += fresh ? ((counts[g] + 8) / 9) * 9 : counts[g];
        }
        return start;
    }
}
