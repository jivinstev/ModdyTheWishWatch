package com.moddingfromamod.content;

import com.moddingfromamod.wishbuilder.Apprentice;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The live {@link ContentIndex}: every item, block and entity the running game has registered, vanilla and every
 * mod, with its English name. Built once (registries are frozen once a server runs) and shared by the Magician's
 * {@code find_content} and the Builder and Apprentice, so the two paths can never find different things. {@code ContentIndexGameTest} holds {@code corpus/apprentice/content-index.json} equal to it.
 *
 * <p>Left out of the index (an earlier issue's list): things that are not for a child to be handed or to summon from a menu —
 * command, structure, jigsaw and test blocks, barriers, light blocks, the debug stick, the knowledge book, spawn eggs
 * and air; and the player as an entity. Everything else is in, including every modded creature (any
 * creature in the game, vanilla and mods).
 */
public final class ContentIndexes {

    /** Ids no child is handed or shown, whatever a search says. */
    static final Set<String> REFUSED = Set.of(
            "minecraft:command_block", "minecraft:chain_command_block", "minecraft:repeating_command_block",
            "minecraft:command_block_minecart", "minecraft:structure_block", "minecraft:structure_void",
            "minecraft:jigsaw", "minecraft:barrier", "minecraft:light", "minecraft:debug_stick",
            "minecraft:knowledge_book", "minecraft:test_block", "minecraft:test_instance_block",
            "minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:moving_piston",
            "minecraft:player");

    private static volatile ContentIndex live;

    private ContentIndexes() {
    }

    /** The index of what this game has installed. Server side; built on first use. */
    public static ContentIndex live() {
        ContentIndex c = live;
        if (c == null) {
            synchronized (ContentIndexes.class) {
                if (live == null) {
                    live = new ContentIndex(entries(), Apprentice.english());
                }
                c = live;
            }
        }
        return c;
    }

    /** Every entry, in registry order: items, then blocks, then entities. */
    public static List<ContentIndex.Entry> entries() {
        List<ContentIndex.Entry> out = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            if (item == Items.AIR || REFUSED.contains(id) || item instanceof SpawnEggItem) {
                continue;
            }
            out.add(new ContentIndex.Entry(id, ContentIndex.Kind.ITEM, new ItemStack(item).getHoverName().getString()));
        }
        for (Block block : BuiltInRegistries.BLOCK) {
            String id = BuiltInRegistries.BLOCK.getKey(block).toString();
            if (block == Blocks.AIR || REFUSED.contains(id)) {
                continue;
            }
            out.add(new ContentIndex.Entry(id, ContentIndex.Kind.BLOCK, block.getName().getString()));
        }
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            String id = key.toString();
            if (REFUSED.contains(id)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            boolean living = DefaultAttributes.hasSupplier((EntityType<? extends net.minecraft.world.entity.LivingEntity>) type);
            out.add(new ContentIndex.Entry(id, living ? ContentIndex.Kind.MOB : ContentIndex.Kind.ENTITY,
                    type.getDescription().getString()));
        }
        return out;
    }
}
