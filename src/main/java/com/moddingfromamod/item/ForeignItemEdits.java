package com.moddingfromamod.item;

import java.util.Locale;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The world-facing half of {@link ForeignItemEdit} — find the real stack, and write onto it.
 *
 * <p>Kept apart from the decision so the decision stays Minecraft-free and unit-testable, which is
 * this repo's standing split. Everything here needs a live server.
 */
public final class ForeignItemEdits {

    private ForeignItemEdits() {
    }

    /** Does this id name a real registered item? */
    public static boolean resolves(MinecraftServer server, String id) {
        return itemOf(id) != null;
    }

    private static Item itemOf(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        Identifier rl;
        try {
            rl = Identifier.parse(id.trim().toLowerCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.getValue(rl);
        // BuiltInRegistries.ITEM.get returns AIR for an unknown id rather than null, so an
        // unresolvable id would otherwise read as "a real item" and route to an edit that can
        // never find anything. Asking for air explicitly is the only honest miss.
        return item == null || item == Items.AIR ? null : item;
    }

    /**
     * The actual stack, in somebody's inventory, or null.
     *
     * <p>Deliberately the whole inventory rather than the main hand. A child who has been asked to
     * "hold it" is holding it; a child who put it in his hotbar two minutes ago has not stopped
     * owning it, and refusing him for that would be a rule about tidiness rather than about what
     * can be changed.
     */
    public static ItemStack findCarried(MinecraftServer server, String id) {
        Item want = itemOf(id);
        if (want == null || server == null) {
            return null;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                ItemStack st = p.getInventory().getItem(i);
                if (!st.isEmpty() && st.getItem() == want) {
                    return st;
                }
            }
        }
        return null;
    }

    /**
     * Write the wish onto the stack itself, minting nothing.
     *
     * <p>Only the component-driven properties, which is the whole of what an edit can honestly
     * reach — see {@link ForeignItemEdit#REACH}. A name is written too, because a child who asks
     * for "the bloop slayer" and gets an unchanged "Diamond Sword" back cannot tell it worked.
     *
     * @return what changed, in the child's words, or empty if nothing did
     */
    public static java.util.List<String> apply(ItemStack stack, String name, JsonObject props) {
        java.util.List<String> changed = new java.util.ArrayList<>();
        if (stack == null || stack.isEmpty()) {
            return changed;
        }
        if (name != null && !name.isBlank()) {
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(name.trim()));
            changed.add("now called " + name.trim());
        }
        if (props == null) {
            return changed;
        }
        if (props.has("unbreakable") && props.get("unbreakable").getAsInt() == 1) {
            // A tool with no durability has nothing to protect, so this is silently inert there —
            // which is exactly what the property's own doc says, and why it is reported rather
            // than assumed.
            if (stack.getMaxDamage() > 0) {
                stack.set(DataComponents.UNBREAKABLE,
                        net.minecraft.util.Unit.INSTANCE);
                changed.add("never wears out");
            }
        }
        if (props.has("max_stack_size")) {
            int n = Math.max(1, Math.min(64, props.get("max_stack_size").getAsInt()));
            stack.set(DataComponents.MAX_STACK_SIZE, n);
            changed.add(n + " fit in a slot");
        }
        if (props.has("attack_damage")) {
            int dmg = props.get("attack_damage").getAsInt();
            stack.set(DataComponents.ATTRIBUTE_MODIFIERS, damageModifiers(dmg));
            changed.add("hits for " + dmg);
        }
        return changed;
    }

    /**
     * Attack damage as an attribute modifier on the stack.
     *
     * <p>REPLACES rather than adds: the base item's own modifiers are dropped and ours put in their
     * place, because adding to them makes "hit for 10" mean "10 on top of whatever a netherite axe
     * already does", and the number the child said is the number he should get.
     */
    private static net.minecraft.world.item.component.ItemAttributeModifiers damageModifiers(int dmg) {
        return net.minecraft.world.item.component.ItemAttributeModifiers.builder()
                .add(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE,
                        new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                                net.minecraft.world.item.Item.BASE_ATTACK_DAMAGE_ID,
                                dmg,
                                net.minecraft.world.entity.ai.attributes.AttributeModifier
                                        .Operation.ADD_VALUE),
                        net.minecraft.world.entity.EquipmentSlotGroup.MAINHAND)
                .build();
    }

    /** Same work, from whichever thread the tool happens to be on. */
    public static java.util.List<String> applyOnServer(MinecraftServer server, String id,
                                                       String name, JsonObject props) {
        java.util.concurrent.Callable<java.util.List<String>> work =
                () -> apply(findCarried(server, id), name, props);
        try {
            if (server.isSameThread()) {
                return work.call();
            }
            return AgentBridge.onServer(() -> {
                try {
                    return work.call();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            return java.util.List.of();
        }
    }
}
