package com.moddingfromamod.item;

import java.util.function.Supplier;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Item registration via {@link DeferredRegister} — the NeoForge pattern for adding content to
 * Minecraft's frozen registries. Nothing is created immediately; the suppliers fire during the
 * registration phase, which is why {@link #register(IEventBus)} takes the <b>mod</b> event bus.
 *
 * <p>Registering an item does NOT make it visible in the creative menu — you also add it to a tab
 * via {@link BuildCreativeModeTabContentsEvent} (also on the mod bus).
 *
 * <p>Each registered item needs a matching model + texture + lang entry, e.g.:
 * {@code assets/<modid>/models/item/example_gem.json} → {@code assets/<modid>/textures/item/example_gem.png},
 * and a name in {@code assets/<modid>/lang/en_us.json}. (This template ships the lang entry; add art
 * when you make it a real item, or it renders as the missing-texture cube.)
 */
public final class ModItems {
    // DeferredRegister.Items, not a plain DeferredRegister<Item>: its registerItem stamps the
    // registry id into the Properties, which 1.21.2+ REQUIRES (an unstamped Properties is an NPE at
    // registration with a clean compile -- the migrator's R25). The same API exists on 1.21.1, where
    // it simply constructs the item, so this is shared source with no pair.
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(ModdingFromAModMod.MODID);

    /** A plain example item — proves the registration + creative-tab + test wiring end to end. */
    public static final Supplier<Item> EXAMPLE_GEM =
            ITEMS.<Item>registerItem("example_gem", Item::new, new Item.Properties());

    /** Moddy — right-click to open the wish screen. One per player is plenty. */
    public static final Supplier<Item> WISH_WATCH =
            ITEMS.<Item>registerItem("wish_watch", WishWatchItem::new,
                    new Item.Properties().stacksTo(1).rarity(net.minecraft.world.item.Rarity.RARE));

    /**
     * The spawn egg for every wished-for creature — ONE registered item whose creature is data on the
     * stack. See {@link WishEggItem} for why it can't be one item per creature.
     */
    public static final Supplier<Item> WISH_EGG =
            ITEMS.<Item>registerItem("wish_egg", WishEggItem::new, new Item.Properties());

    /**
     * The item every "thing" wish wears — one registry entry, unlimited wishes, same shape as
     * {@link #WISH_EGG}. Its artwork comes from the shipped icon library via custom_model_data, and
     * right-clicking a face drops the tagged anchor a placeable datapack is built around. It is the
     * answer to the two limits a datapack cannot cross: the icon, and the creative menu.
     */
    public static final Supplier<Item> WISH_ITEM =
            ITEMS.<Item>registerItem("wish_item", WishItem::new, new Item.Properties());

    private ModItems() {
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        modEventBus.addListener(ModItems::addToCreativeTabs);
    }

    private static void addToCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            event.accept(EXAMPLE_GEM.get());
        }
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            // ♻️ NO WISH_WATCH ENTRY, on purpose. Moddy is handed out now — on join, on respawn
            // and on a five-second top-up — so an entry here could only ever give a child a SECOND
            // one, and the pre-flip one at that. The item is still REGISTERED, because an old
            // world's chest holds them (docs/COMPAT_SHIMS.md, the ♻️ SHIM row); it just
            // cannot be newly acquired. It was behind `!VanillaWatch.enabled()` deleted
            // that switch, and the guard became an unconditional absence.
            // Pinned by LegacyWishUiTest.theLegacyWatchIsOffTheCreativeMenu.

            // One entry per icon in the library: the raw vocabulary, blank and unnamed. This is NOT
            // what makes a wish findable — see the catalogue below. It stays because it is how a
            // grown-up or an agent picks an icon up by hand.
            for (WishIcons.Icon icon : WishIcons.all()) {
                event.accept(WishItem.of(WISH_ITEM.get(), icon.id(), ""));
            }
            // Every THING a wish has actually handed over, under its own name. Mirrors the
            // spawn-egg loop below: without this the menu offers the icon vocabulary and none of the
            // child's wishes, so the one item he goes looking for is the one that isn't here.
            for (WishItemCatalogue.Entry entry : WishItemCatalogue.all()) {
                ItemStack stack = WishItemCatalogue.stack(entry, event.getParameters().holders());
                if (!stack.isEmpty()) {
                    event.accept(stack);
                }
            }
        }
        // One egg per creature the world currently knows about. This handler runs again whenever the
        // tab is rebuilt, so a creature invented mid-session gets an egg as soon as something asks
        // for a rebuild (see WishEggClient.refreshEggTab) — no restart, no resource reload.
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            com.moddingfromamod.species.SpeciesRegistry.all().stream()
                    .sorted(java.util.Comparator.comparing(
                            com.moddingfromamod.species.Species::id))
                    .forEach(species ->
                            event.accept(WishEggItem.of(WISH_EGG.get(), species.id())));
        }
        // NOTE: to REMOVE an existing entry, don't call removeIf on getParentEntries() — those sets
        // are read-only for structural edits and it throws UnsupportedOperationException (a crash on
        // opening the creative menu). Collect the stacks, then use:
        //   event.remove(stack, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
    }
}
