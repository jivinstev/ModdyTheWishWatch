package com.moddingfromamod.wishbuilder;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.WishAgent;
import com.moddingfromamod.agent.McpProtocol;
import com.moddingfromamod.agent.McpSessions;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * a sure typed change on an ITEM the Magician made, through the REAL make_item: its colours change on the SAME
 * design (same id, name and power), nothing second is made, and a power change is not one this path makes.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class MagicianItemGameTest {

    private static volatile McpProtocol protocol;

    private static McpProtocol protocol() {
        if (protocol == null) {
            synchronized (MagicianItemGameTest.class) {
                if (protocol == null) {
                    protocol = new McpProtocol(WishAgent.buildTools(), new McpSessions());
                }
            }
        }
        return protocol;
    }

    private static <T> CompletableFuture<T> onServer(net.minecraft.server.MinecraftServer server,
                                                     java.util.function.Supplier<CompletableFuture<T>> start) {
        CompletableFuture<T> r = new CompletableFuture<>();
        server.execute(() -> start.get().whenComplete((res, err) -> {
            if (err != null) {
                r.completeExceptionally(err);
            } else {
                r.complete(res);
            }
        }));
        return r;
    }

    @GameTest(template = "empty_test", batch = "magicianItemInPlace", timeoutTicks = 12000)   // sets the acting child
    public static void aSureColourChangeOnAMagicianItemChangesTheSameItem(GameTestHelper helper) {
        var child = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper, "gtmagitem");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        child.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        com.moddingfromamod.testing.MockPlayerNetwork.actingChildFor(helper, child);
        var who = BuilderMake.Who.of(child);
        var server = helper.getLevel().getServer();
        String owner = child.getUUID().toString();
        long rise = BuilderMake.riseMillis;
        BuilderMake.riseMillis = 0;
        com.moddingfromamod.testing.MockPlayerNetwork.afterTest(helper, () -> BuilderMake.riseMillis = rise);
        BuilderSpec sword = BuilderSpec.read(JsonParser.parseString(
                "{\"builder\":1,\"kind\":\"item\",\"from\":\"preset:items/lightning_sword_lightning\",\"name\":\"Magi Zap\"}")
                .getAsJsonObject());
        AtomicReference<String> wrong = new AtomicReference<>();
        AtomicReference<com.moddingfromamod.item.ItemSpecStore.Spec> before = new AtomicReference<>();
        AtomicReference<Integer> countBefore = new AtomicReference<>();
        AtomicReference<String> powerChange = new AtomicReference<>();
        CompletableFuture<?> all = onServer(server, () -> BuilderMake.run(server, who, sword, null, protocol(), false, false))
                .thenComposeAsync(made -> {
                    if (made.outcome() != BuilderMake.Outcome.MADE && made.outcome() != BuilderMake.Outcome.PARTIAL) {
                        wrong.set("the sword was not made: " + made.detail());
                        return CompletableFuture.completedFuture(null);
                    }
                    var spec = com.moddingfromamod.item.ItemSpecStore.find("Magi Zap");   // make_item mints its own id
                    if (spec == null) {
                        wrong.set("no item stored for '" + made.madeId() + "'");
                        return CompletableFuture.completedFuture(null);
                    }
                    try {
                        // a wish changed it: it is the Magician's now, not the Builder's own make
                        BuilderSpecStore.touch(owner, List.of(made.madeId(), spec.id()), "gt-magician-wish");
                    } catch (java.io.IOException e) {
                        wrong.set("could not mark it as the Magician's: " + e);
                        return CompletableFuture.completedFuture(null);
                    }
                    before.set(spec);
                    countBefore.set(com.moddingfromamod.item.ItemSpecStore.count());
                    MagicianItem.Thing thing = BuilderMenus.magicianItem(child.getUUID(), spec.id());
                    if (thing == null) {
                        wrong.set("his Magician sword '" + spec.id() + "' (owner " + spec.ownerId() + ") is not found as his");
                        return CompletableFuture.completedFuture(null);
                    }
                    var power = MagicianItem.calls(thing, List.of(BuilderEdit.Op.set("power", "itempower:heal")));
                    powerChange.set(power == null ? "passed" : "made");
                    var calls = MagicianItem.calls(thing, List.of(BuilderEdit.Op.set("scheme", "ocean")));
                    return onServer(server, () -> BuilderMake.magicianItemRun(server, who, thing, calls, protocol(), false, false))
                            .thenAcceptAsync(r -> {
                                if (r.outcome() != BuilderMake.Outcome.MADE && r.outcome() != BuilderMake.Outcome.PARTIAL) {
                                    wrong.set("the change did not stand: " + r.detail() + " " + r.calls());
                                }
                            });
                });
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(all.isDone(), Component.literal("still making"));
            helper.assertFalse(all.isCompletedExceptionally(), Component.literal("a make threw"));
            helper.assertTrue(wrong.get() == null, Component.literal(String.valueOf(wrong.get())));
            var now = com.moddingfromamod.item.ItemSpecStore.get(before.get().id());
            helper.assertTrue(now != null, Component.literal("the sword's design is gone"));
            helper.assertValueEqual(now.colour(), Schemes.byId("ocean").main(), Component.literal("its colour"));
            helper.assertValueEqual(now.name(), before.get().name(), Component.literal("its name"));
            helper.assertValueEqual(now.effects(), before.get().effects(), Component.literal("its power"));
            helper.assertValueEqual(com.moddingfromamod.item.ItemSpecStore.count(), countBefore.get(),
                    Component.literal("designs stored (nothing second is made)"));
            helper.assertValueEqual(powerChange.get(), "passed", Component.literal("a power change on a Magician item"));
        });
    }
}
