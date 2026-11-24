package com.moddingfromamod.world;

import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * step 4 (docs/OPEN_DOOR_PLAN.md §11.3), each rule through the REAL path a player meets:
 * chat posted on the event bus, {@code /msg} through the command dispatcher, a hit through
 * {@code hurtServer}, a sign through {@code SignBlockEntity.updateSignText} (its mixin), a rename
 * through {@code AnvilMenu.setItemName} (its mixin). Each rule is checked ON and, where a control
 * proves the probe can see anything, OFF.
 *
 * <p>Its own batch, because it sets the world's people rules as JVM properties every test shares,
 * and batches run one after another (CLAUDE.md).
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class PeopleRulesGameTest {

    private static void rules(String chat, String pvp, String placed) {
        set("chat", chat);
        set("pvp", pvp);
        set("placedText", placed);
    }

    private static void set(String k, String v) {
        if (v == null) {
            System.clearProperty(WorldPeople.PROPERTY_PREFIX + k);
        } else {
            System.setProperty(WorldPeople.PROPERTY_PREFIX + k, v);
        }
    }

    private static boolean heard(ServerPlayer p, String words) {
        return MockPlayerNetwork.chat(p).stream().anyMatch(l -> l.contains(words));
    }

    @GameTest(template = "empty_test", batch = "people_rules", timeoutTicks = 200)
    public static void onAPrivateIslandWorldChatStaysWithItsSenderAndMessagesGoNowhere(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer a = MockPlayerNetwork.inLevel(helper, "ppl_chat_a");
        ServerPlayer b = MockPlayerNetwork.inLevel(helper, "ppl_chat_b");
        try {
            rules("self", null, null);
            ServerChatEvent said = NeoForge.EVENT_BUS.post(new ServerChatEvent(a, "hello island one",
                    Component.literal("hello island one")));
            // A /msg is delivered as a PLAYER chat message, which a mock's recorder cannot see (it
            // records system lines), so the probe is the event the rule hangs on: the dispatcher's
            // own parse, posted as NeoForge posts it before running any command.
            boolean msgCancelled = commandCancelled(helper, a, "msg " + b.getScoreboardName() + " meet me at spawn");
            boolean meCancelled = commandCancelled(helper, a, "me waves");
            boolean cancelled = said.isCanceled();
            boolean echoed = heard(a, "hello island one");
            boolean toldPrivate = heard(a, "Only you can see your chat here");
            boolean bHeardChat = heard(b, "hello island one");
            boolean toldNoMessages = heard(a, "Messages don't travel between islands here");

            // the control: chat for everyone, as a world with no rules has it — nothing is cancelled
            rules("everyone", null, null);
            ServerChatEvent open = NeoForge.EVENT_BUS.post(new ServerChatEvent(a, "hello open world",
                    Component.literal("hello open world")));
            boolean controlMsgCancelled = commandCancelled(helper, a, "msg " + b.getScoreboardName() + " hi");

            helper.assertTrue(cancelled, Component.literal("the chat line must not be broadcast"));
            helper.assertTrue(echoed, Component.literal("the sender sees his own line"));
            helper.assertTrue(toldPrivate, Component.literal("and is told, once, that chat here is private"));
            helper.assertFalse(bHeardChat, Component.literal("nobody else hears it"));
            helper.assertTrue(msgCancelled, Component.literal("a /msg is refused before it runs"));
            helper.assertTrue(meCancelled, Component.literal("and so is a /me"));
            helper.assertTrue(toldNoMessages, Component.literal("and the sender is told why"));
            helper.assertFalse(open.isCanceled(), Component.literal("control: chat for everyone is not cancelled"));
            helper.assertFalse(controlMsgCancelled, Component.literal("control: a /msg runs where chat is open"));
            helper.succeed();
        } finally {
            rules(null, null, null);
            MockPlayerNetwork.remove(a);
            MockPlayerNetwork.remove(b);
        }
    }

    @GameTest(template = "empty_test", batch = "people_rules", timeoutTicks = 200)
    public static void withoutPvpNoPlayerHurtsAnother(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer a = MockPlayerNetwork.inLevel(helper, "ppl_pvp_a");
        ServerPlayer b = MockPlayerNetwork.asSurvival(MockPlayerNetwork.inLevel(helper, "ppl_pvp_b"));
        try {
            // THE EVENT, not hurtServer: a GameTest server allows no PvP of its own (measured — the
            // control hit did nothing with the rule on), so hurtServer could never tell the rule's
            // work from vanilla's. The event is where the rule acts on a real server.
            rules(null, "false", null);
            boolean byPlayer = damageCancelled(b, b.damageSources().playerAttack(a));
            boolean byArrowOwner = damageCancelled(b, b.damageSources().thrown(a, a));
            boolean byFall = damageCancelled(b, b.damageSources().fall());
            rules(null, "true", null);
            boolean controlByPlayer = damageCancelled(b, b.damageSources().playerAttack(a));
            helper.assertTrue(byPlayer, Component.literal("a player's hit must be refused"));
            helper.assertTrue(byArrowOwner, Component.literal("and anything he threw or fired"));
            helper.assertFalse(byFall, Component.literal("a fall still hurts"));
            helper.assertFalse(controlByPlayer, Component.literal("control: with pvp on the same hit is not refused"));
            helper.succeed();
        } finally {
            rules(null, null, null);
            MockPlayerNetwork.remove(a);
            MockPlayerNetwork.remove(b);
        }
    }

    @GameTest(template = "empty_test", batch = "people_rules", timeoutTicks = 200)
    public static void signsAndAnvilNamesCannotCarryAWayToFindSomeone(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer a = MockPlayerNetwork.inLevel(helper, "ppl_text_a");
        try {
            rules(null, null, "sweep");
            BlockPos at = new BlockPos(1, 2, 1);
            helper.setBlock(at, Blocks.OAK_SIGN);
            SignBlockEntity sign = (SignBlockEntity) helper.getBlockEntity(at, net.minecraft.world.level.block.entity.BlockEntity.class);
            sign.setAllowedPlayerEditor(a.getUUID());
            sign.updateSignText(a, net.minecraft.world.level.block.entity.SignTextSlot.FRONT, lines("add me on", "discord", "", ""));
            String refused = sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT).getMessages(false).get(1).getString();
            sign.setAllowedPlayerEditor(a.getUUID());
            sign.updateSignText(a, net.minecraft.world.level.block.entity.SignTextSlot.FRONT, lines("Bob's farm", "keep out", "", ""));
            String kept = sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT).getMessages(false).get(0).getString();

            AnvilMenu anvil = new AnvilMenu(0, a.getInventory(), ContainerLevelAccess.NULL);
            anvil.getSlot(0).set(new ItemStack(Items.NAME_TAG));
            boolean contactName = anvil.setItemName("snap me later");
            boolean petName = anvil.setItemName("Sparky");

            helper.assertTrue(refused.isEmpty(), Component.literal("a sign naming a way to find him must stay blank: '" + refused + "'"));
            helper.assertTrue("Bob's farm".equals(kept), Component.literal("control: an ordinary sign is written: '" + kept + "'"));
            helper.assertTrue(heard(a, "links, usernames or numbers"), Component.literal("he is told why"));
            helper.assertFalse(contactName, Component.literal("an anvil name naming a way to find him is not taken"));
            helper.assertTrue(petName, Component.literal("control: a pet's name is taken"));
            helper.succeed();
        } finally {
            rules(null, null, null);
            MockPlayerNetwork.remove(a);
        }
    }

    private static boolean commandCancelled(GameTestHelper helper, ServerPlayer p, String command) {
        var parsed = helper.getLevel().getServer().getCommands().getDispatcher()
                .parse(command, p.createCommandSourceStack());
        return NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.CommandEvent(parsed)).isCanceled();
    }

    private static boolean damageCancelled(ServerPlayer victim, net.minecraft.world.damagesource.DamageSource src) {
        return NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent(
                victim, new net.neoforged.neoforge.common.damagesource.DamageContainer(src, 4f))).isCanceled();
    }

    private static List<FilteredText> lines(String... l) {
        return java.util.Arrays.stream(l).map(FilteredText::passThrough).toList();
    }
}
