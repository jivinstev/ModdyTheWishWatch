package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.AskWhichTool;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.mob.Creatures;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>"kill my t rex" offers only the T-Rex that is out — and with one out, does not ask.</b>
 *
 * <p>Drives the REAL {@code ask_which} off the server thread, as a worker does, in a world with two
 * creature designs: one spawned and standing, the other spawned and then removed (so its design is
 * in this world's catalogue and nothing of it is out). The live failure was the picker offering
 * both and the child picking the one that was not there. The oracle is the tool's answer and the
 * absence of an open question: a question with one answer must never reach him.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class PickOutInWorldGameTest {

    private static final String OUT = "pet_dragon";
    private static final String GONE = "trex";

    @GameTest(template = "empty_test", batch = "g1724_pick", timeoutTicks = 600)
    public static void anInstanceWishOffersOnlyWhatIsOut(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "mfam-g1724-pick");
        UUID wish = UUID.randomUUID();
        Marshal.global().submit(wish, child.getUUID(), "kill my t rex");
        String worker = "gt-g1724-pick-" + wish;
        Marshal.global().assign(worker, wish);
        Set<UUID> already = new HashSet<>();
        for (String id : new String[] {OUT, GONE}) {
            Creatures.carriersOf(level, id).forEach(c -> already.add(c.getUUID()));
        }
        // Both designs into this world, as his wishes would; then the T-Rex is taken away again.
        AgentContext.with(worker, () -> WishExecutor.runOne(server, level, child.position(), child,
                "wishmob spawn " + OUT));
        AgentContext.with(worker, () -> WishExecutor.runOne(server, level, child.position(), child,
                "wishmob spawn " + GONE));
        for (String id : new String[] {OUT, GONE}) {
            for (LivingEntity c : Creatures.carriersOf(level, id)) {
                if (!already.contains(c.getUUID()) && GONE.equals(id)) {
                    Creatures.remove(level, c);
                }
            }
        }
        // Anything another test left out of the T-Rex design would make this not a "none out" world.
        for (LivingEntity c : Creatures.carriersOf(level, GONE)) {
            Creatures.remove(level, c);
        }

        AtomicReference<ToolResult> instance = new AtomicReference<>();
        AtomicReference<ToolResult> design = new AtomicReference<>();
        Thread t = new Thread(() -> {
            AgentContext.bind(worker);
            try {
                instance.set(new AskWhichTool().call(JsonParser.parseString(
                        "{\"ids\":[\"" + GONE + "\",\"" + OUT + "\"],\"about\":\"instance\"}")
                        .getAsJsonObject()));
                design.set(new AskWhichTool().call(JsonParser.parseString(
                        "{\"ids\":[\"" + GONE + "\",\"" + OUT + "\"],\"about\":\"design\"}")
                        .getAsJsonObject()));
            } finally {
                AgentContext.unbind();
            }
        }, "g1724-gametest");
        t.setDaemon(true);
        t.start();

        helper.succeedWhen(() -> {
            helper.assertTrue(design.get() != null, Component.literal("ask_which has not answered yet"));
            ToolResult r = instance.get();
            helper.assertFalse(r.isError(), Component.literal("#1724: ask_which refused: " + r.text()));
            helper.assertTrue(r.text().contains("Only '" + OUT + "'") && r.text().contains("did not ask"),
                    Component.literal("#1724: with one of the two out, ask_which must pick it without asking — said: "
                            + r.text()));
            helper.assertTrue(WhichQuestion.openFor(child.getUUID()) == null,
                    Component.literal("#1724: a question with one answer was put to him"));
            helper.assertTrue(design.get().isError() && design.get().text().contains("about: 'instance'"),
                    Component.literal("#1724: 'kill my t rex' as a DESIGN pick must be refused — said: " + design.get().text()));
            for (LivingEntity c : Creatures.carriersOf(level, OUT)) {
                if (!already.contains(c.getUUID())) {
                    Creatures.remove(level, c);
                }
            }
            Marshal.global().finish(wish);
            MockPlayerNetwork.remove(child);
        });
    }
}
