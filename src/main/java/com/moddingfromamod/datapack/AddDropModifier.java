package com.moddingfromamod.datapack;

import java.util.List;
import java.util.Locale;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.moddingfromamod.ModdingFromAModMod;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * <b>One extra drop, added on top of what a loot table already rolled</b> — the game half of
 * {@link DropRule}. Written into a world datapack by {@code change_drops}.
 *
 * <p>The serializer registry is NeoForge's {@code global_loot_modifier_serializers}, which is built
 * without {@code sync(true)}: it lives on the server only, so a stock client never has to know this
 * type exists (Deployment 1, {@code docs/DEPLOYMENTS.md}).
 */
public final class AddDropModifier extends LootModifier {

    /** The {@code type} a loot modifier JSON names to reach this class. */
    public static final String TYPE = ModdingFromAModMod.MODID + ":add_drop";

    public static final MapCodec<AddDropModifier> CODEC = RecordCodecBuilder.mapCodec(inst ->
            codecStart(inst).and(inst.group(
                    Codec.STRING.fieldOf("source").forGetter(m -> m.rule.source().name()
                            .toLowerCase(Locale.ROOT)),
                    Codec.STRING.fieldOf("which").forGetter(m -> m.rule.which()),
                    Codec.STRING.fieldOf("item").forGetter(m -> m.rule.item()),
                    Codec.FLOAT.fieldOf("chance").forGetter(m -> (float) m.rule.chance()),
                    Codec.INT.fieldOf("count").forGetter(m -> m.rule.count())))
                    .apply(inst, AddDropModifier::new));

    /**
     * Registered from a mod-bus {@code RegisterEvent} rather than a {@code DeferredRegister}
     * handed the bus in the {@code @Mod} constructor: the effect is the same, and it keeps the
     * entry point (which puts every PR on the full Gate B) untouched.
     */
    @EventBusSubscriber(modid = ModdingFromAModMod.MODID)
    public static final class Registration {
        private Registration() {
        }

        @SubscribeEvent
        public static void register(RegisterEvent event) {
            event.register(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS,
                    helper -> helper.register(
                            Identifier.fromNamespaceAndPath(ModdingFromAModMod.MODID, "add_drop"),
                            CODEC));
        }
    }

    private final DropRule rule;

    private AddDropModifier(LootItemCondition[] conditions, String source, String which, String item,
                            float chance, int count) {
        super(conditions);
        this.rule = new DropRule("", DropRule.Source.of(source), which, item, chance, count);
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> loot, LootContext context) {
        if (rule.source() == null
                || !rule.matches(String.valueOf(context.getQueriedLootTableId()), AddDropModifier::inTag)) {
            return loot;
        }
        if (context.getRandom().nextFloat() >= rule.chance()) {
            return loot;
        }
        Item item = pick(rule.item(), context);
        if (item != null) {
            loot.add(new ItemStack(item, rule.count()));
        }
        return loot;
    }

    /** An item id, or a random member of an item tag; null when neither exists on this build. */
    static Item pick(String id, LootContext context) {
        if (id.startsWith("#")) {
            Identifier tagId = Identifier.tryParse(id.substring(1));
            if (tagId == null) {
                return null;
            }
            TagKey<Item> tag = TagKey.create(Registries.ITEM, tagId);
            List<Item> members = BuiltInRegistries.ITEM.stream()
                    .filter(i -> i.builtInRegistryHolder().is(tag)).toList();
            return members.isEmpty() ? null : members.get(context.getRandom().nextInt(members.size()));
        }
        Identifier rl = Identifier.tryParse(id);
        return rl == null ? null : BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
    }

    /**
     * Is the entity or block a table's path names in this tag? Tried as a block first (leaves,
     * ores, logs are what children name), then as an entity type.
     */
    static boolean inTag(String tag, String thing) {
        Identifier tagId = Identifier.tryParse(tag);
        Identifier id = Identifier.tryParse(thing);
        if (tagId == null || id == null) {
            return false;
        }
        var block = BuiltInRegistries.BLOCK.getOptional(id);
        if (block.isPresent()
                && block.get().builtInRegistryHolder().is(TagKey.create(Registries.BLOCK, tagId))) {
            return true;
        }
        var type = BuiltInRegistries.ENTITY_TYPE.getOptional(id);
        return type.isPresent()
                && type.get().builtInRegistryHolder().is(TagKey.create(Registries.ENTITY_TYPE, tagId));
    }

    @Override
    public MapCodec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}
