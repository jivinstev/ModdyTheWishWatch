package com.moddingfromamod.item;

import com.moddingfromamod.testing.gt.GameTestCompat;
import net.minecraft.network.chat.Component;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.datapack.CbScriptCompiler;
import com.moddingfromamod.datapack.ItemCatalogue;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>T6F follow-up: "a horn that calls all my pets to me" — HIS pets come, nobody else's, and
 * nothing new is made.</b>
 *
 * <p>Red on {@code main}: there was no {@code call_pets} effect, so {@code make_item} refused the
 * wish and the agent hand-wrote a datapack every time (once summoning a fresh, untamed wolf).
 *
 * <p>His wolf sits five blocks away (told to sit, so it would not come on its own); a wolf tamed by
 * somebody else and his own cat stand the same distance off. A call for his wolf brings the wolf,
 * standing, and leaves the other two where they were. The horn test blows a real goat horn through
 * the real use path with NO instrument added by hand — measured on both targets: a wished horn is
 * blowable as make_item gives it (1.21.4 has a default instrument; 1.21.1 falls back to the first
 * in the item's instrument tag).
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class CallPetsGameTest {

    private static ItemStack made(GameTestHelper helper, ServerPlayer p, String name, String shape,
                                  String base, String kind) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("icon", "wand");
        o.addProperty("colour", "#20A0FF");
        o.addProperty("shape", shape);
        o.addProperty("effect", "call_pets");
        o.addProperty("count", 1);
        if (base != null) {
            o.addProperty("base_item", base);
        }
        JsonObject params = new JsonObject();
        params.addProperty("KIND", kind);
        o.add("params", params);
        MakeItemTool tool = new MakeItemTool();
        ToolResult r = tool.call(o);
        if (r.isError()) {
            throw GameTestCompat.assertion("make_item refused call_pets on " + shape + ": " + r.text());
        }
        String line = MakeItemTool.gives(shape, tool.lastMadeId(), ItemCatalogue.icon("wand"), 1,
                "A hint.", java.util.List.of(new MakeItemTool.Variant(name, 0x20A0FF)),
                com.moddingfromamod.datapack.ItemRecipient.ME, null, base);
        p.getInventory().clearContent();
        helper.getLevel().getServer().getCommands().performPrefixedCommand(
                p.createCommandSourceStack().withPermission(2).withSuppressedOutput(), line.strip().substring(1));
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            if (!s.isEmpty() && s.getHoverName().getString().equals(name)) {
                return s.copy();
            }
        }
        throw GameTestCompat.assertion("nothing arrived from: " + line);
    }

    private record Pets(Wolf mine, Wolf stranger, Cat cat) {
    }

    private static Pets pets(GameTestHelper helper, ServerPlayer owner) {
        Wolf mine = helper.spawn(EntityType.WOLF, new BlockPos(6, 2, 6));
        mine.tame(owner);
        mine.setOrderedToSit(true);
        mine.setInSittingPose(true);
        Wolf stranger = helper.spawn(EntityType.WOLF, new BlockPos(6, 2, 1));
        stranger.setTame(true, false);
        stranger.setOwnerReference(net.minecraft.world.entity.EntityReference.<net.minecraft.world.entity.LivingEntity>of(java.util.UUID.randomUUID()));
        stranger.setOrderedToSit(true);
        Cat cat = helper.spawn(EntityType.CAT, new BlockPos(1, 2, 6));
        cat.tame(owner);
        cat.setOrderedToSit(true);
        return new Pets(mine, stranger, cat);
    }

    private static ServerPlayer owner(GameTestHelper helper, BlockPos feet) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        p.setGameMode(GameType.SURVIVAL);
        p.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.0F, 0.0F);
        return p;
    }

    private static void expectCame(GameTestHelper helper, ServerPlayer p, Pets pets, boolean catComes, String what) {
        helper.runAfterDelay(3, () -> {
            try {
                double dMine = pets.mine().distanceTo(p);
                if (dMine > 2.6) {
                    throw GameTestCompat.assertion(what + ": his wolf did not come — it is " + dMine
                            + " blocks from him");
                }
                if (pets.mine().isOrderedToSit()) {
                    throw GameTestCompat.assertion(what + ": his wolf came but is still told to sit");
                }
                if (pets.stranger().distanceTo(p) < 3.5) {
                    throw GameTestCompat.assertion(what + ": SOMEBODY ELSE's wolf came to him");
                }
                boolean catCame = pets.cat().distanceTo(p) < 2.6;
                if (catCame != catComes) {
                    throw GameTestCompat.assertion(what + ": his cat " + (catCame ? "came" : "did not come")
                            + (catComes ? " — a call for all his pets must bring it" : " — a call for his wolf"));
                }
                int wolves = helper.getLevel().getEntitiesOfClass(Wolf.class, p.getBoundingBox().inflate(20)).size();
                if (wolves != 2) {
                    throw GameTestCompat.assertion(what + ": " + wolves + " wolves in the world, not 2 — a call "
                            + "must never make a new animal");
                }
                helper.succeed();
            } finally {
                MockPlayerNetwork.remove(p);
            }
        });
    }

    @GameTest(template = "empty_test", batch = "callpets_whistle", timeoutTicks = 600)
    public static void aWhistleCallsHisWolfAndNotAStrangersOrHisCat(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            helper.succeed();
            return;
        }
        BlockPos feet = helper.absolutePos(new BlockPos(1, 2, 1));
        ServerPlayer p = owner(helper, feet);
        try {
            ItemStack whistle = made(helper, p, "Wolf Whistle " + helper.getLevel().getGameTime(), "held", null, "wolf");
            Pets pets = pets(helper, p);
            p.getInventory().clearContent();
            p.setItemInHand(InteractionHand.MAIN_HAND, whistle);
            p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
            expectCame(helper, p, pets, false, "whistle");
        } catch (RuntimeException e) {
            MockPlayerNetwork.remove(p);
            throw e;
        }
    }

    @GameTest(template = "empty_test", batch = "callpets_horn", timeoutTicks = 600)
    public static void aRealHornIsBlownAndAllHisPetsCome(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            helper.succeed();
            return;
        }
        BlockPos feet = helper.absolutePos(new BlockPos(1, 2, 1));
        ServerPlayer p = owner(helper, feet);
        try {
            ItemStack horn = made(helper, p, "Pet Horn " + helper.getLevel().getGameTime(), "on_use_done",
                    "minecraft:goat_horn", "any");
            Pets pets = pets(helper, p);
            p.getInventory().clearContent();
            p.setItemInHand(InteractionHand.MAIN_HAND, horn);
            p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
            helper.assertTrue(p.isUsingItem(), Component.literal("the wished horn cannot be BLOWN: "
                    + p.getMainHandItem().getComponents()));
            p.releaseUsingItem();
            expectCame(helper, p, pets, true, "horn");
        } catch (RuntimeException e) {
            MockPlayerNetwork.remove(p);
            throw e;
        }
    }

    /** A call names a kind from a closed list; anything else is refused before it becomes a tag. */
    @GameTest(template = "empty_test", batch = "callpets_refuse", timeoutTicks = 100)
    public static void anUnknownKindIsRefused(GameTestHelper helper) {
        JsonObject o = new JsonObject();
        o.addProperty("name", "Dragon Whistle");
        o.addProperty("icon", "wand");
        o.addProperty("colour", "#20A0FF");
        o.addProperty("shape", "held");
        o.addProperty("effect", "call_pets");
        o.addProperty("count", 1);
        JsonObject params = new JsonObject();
        params.addProperty("KIND", "dragon run say hi");
        o.add("params", params);
        ToolResult r = new MakeItemTool().call(o);
        helper.assertTrue(r.isError(), Component.literal("make_item accepted KIND 'dragon run say hi': " + r.text()));
        helper.succeed();
    }
}
