package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.PaintCreatureTool;
import com.moddingfromamod.mob.Creatures;
import net.minecraft.core.component.DataComponents;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>{@code paint_creature} changes what the rig draws</b>.
 *
 * <p>It painted the species' {@code texture.png}, which the atom rig has never read: every box is
 * drawn in its own part's colour, carried on the display as a dye. So it changed nothing a child
 * could see, and told the agent <i>"It changed on the ones already in the world"</i>.
 *
 * <p>The oracle is the dye on the displays IN THE WORLD, before and after: the painted part's
 * displays wear the new colour, and every other display keeps the colour it had.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class RigPaintGameTest {

    /** Not a colour anywhere in the corpus Spikor, so any display wearing it was painted. */
    private static final int PURPLE = 0x7B2FBE;

    @GameTest(template = "empty_test", batch = "t6g4_paint", timeoutTicks = 400)
    public static void paintingHisShellPurpleChangesTheShellOnTheRig(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        String id = SpeciesOnRigGameTest.plant(helper, "gt_t6g4_paint", "{}");
        var at = helper.absolutePos(SpeciesOnRigGameTest.SPOT);
        LivingEntity made = CreatureSpawn.spawn(level, id, at.getX() + 0.5, at.getY(),
                at.getZ() + 0.5, 0f, EntitySpawnReason.COMMAND, false);
        helper.assertTrue(made != null, Component.literal("the creature did not stand up"));
        Map<Integer, Integer> before = dyes(level, made);
        int displaysBefore = before.values().stream().mapToInt(Integer::intValue).sum();
        helper.assertTrue(displaysBefore > 0 && !before.containsKey(PURPLE),
                Component.literal("the fixture already wears the paint colour, so this proves nothing: " + before));

        AtomicReference<ToolResult> reply = new AtomicReference<>();
        Thread t = new Thread(() -> {
            JsonObject args = new JsonObject();
            args.addProperty("id", id);
            args.addProperty("part", "shell");
            args.addProperty("colour", String.format("#%06X", PURPLE));
            reply.set(new PaintCreatureTool().call(args));
        }, "t6g4-paint");
        t.setDaemon(true);
        t.start();

        helper.succeedWhen(() -> {
            helper.assertTrue(reply.get() != null, Component.literal("the paint has not answered yet"));
            var carriers = Creatures.carriersOf(level, id);
            helper.assertTrue(carriers.size() == 1, Component.literal("expected one " + id + ", found "
                    + carriers.size()));
            Map<Integer, Integer> after = dyes(level, carriers.get(0));
            int painted = after.getOrDefault(PURPLE, 0);
            helper.assertTrue(painted > 0, Component.literal("#946: the tool said '" + reply.get().text()
                    + "' and not one display on the creature wears the paint: " + after));
            int total = after.values().stream().mapToInt(Integer::intValue).sum();
            helper.assertTrue(total == displaysBefore, Component.literal("the creature has " + total
                    + " displays after the paint and had " + displaysBefore));
            int changed = 0;
            for (var e : before.entrySet()) {
                int now = after.getOrDefault(e.getKey(), 0);
                helper.assertTrue(now <= e.getValue(), Component.literal(String.format("a display turned #%06X, "
                        + "which is not the paint", e.getKey())));
                changed += e.getValue() - now;
            }
            helper.assertTrue(changed == painted, Component.literal("displays that are not the shell changed colour: "
                    + before + " -> " + after));
            helper.assertTrue(painted < displaysBefore / 2, Component.literal("most of the creature turned purple — "
                    + "the paint reached more than the shell: " + after));
            for (LivingEntity c : carriers) {
                Creatures.remove(level, c);
            }
        });
    }

    /**
     * The library's starter dragon, painted by HIS agent: his own copy, on the one nearest him,
     * and the other dragon — and the library's design — untouched (the fork rule applies to
     * a paint exactly as to "make him bigger").
     */
    @GameTest(template = "empty_test", batch = "t6g4_paint_fork", timeoutTicks = 400)
    public static void paintingTheStarterDragonPaintsOnlyHisOwnCopy(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        String base = "pet_dragon";
        helper.assertTrue(com.moddingfromamod.species.CreatureFork.inLibrary(base),
                Component.literal("the starter dragon is not in the library, so this is not the fork path"));
        var child = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper, "mfam-t6g4-paint");
        java.util.UUID wish = java.util.UUID.randomUUID();
        var marshal = com.moddingfromamod.marshal.Marshal.global();
        marshal.submit(wish, child.getUUID(), "make my dragon's wings purple");
        String worker = "gt-t6g4-paint-" + wish;
        marshal.assign(worker, wish);
        String forkId = com.moddingfromamod.marshal.ArtifactOwner.forkKey(base,
                child.getUUID().toString());
        java.util.Set<java.util.UUID> already = new java.util.HashSet<>();
        for (LivingEntity c : Creatures.carriersOf(level, base)) {
            already.add(c.getUUID());
        }
        // His, as production spawns it (which is also what puts the library's design into this
        // world); then another, fifteen blocks off.
        com.moddingfromamod.wish.WishExecutor.runOne(server, level, child.position(), child,
                "wishmob spawn " + base);
        LivingEntity his = null;
        for (LivingEntity c : Creatures.carriersOf(level, base)) {
            if (!already.contains(c.getUUID())) {
                his = c;
            }
        }
        LivingEntity other = CreatureSpawn.spawn(level, base, child.getX() + 15, child.getY(),
                child.getZ(), 0f, EntitySpawnReason.COMMAND, false);
        helper.assertTrue(his != null && other != null, Component.literal("the dragons did not stand up"));
        Map<Integer, Integer> otherBefore = dyes(level, other);
        var partsBefore = com.moddingfromamod.runtime.ModelRepository.sculptedParts(base);

        AtomicReference<ToolResult> reply = new AtomicReference<>();
        Thread t = new Thread(() -> {
            JsonObject args = new JsonObject();
            args.addProperty("id", base);
            args.addProperty("part", "wing");
            args.addProperty("colour", String.format("#%06X", PURPLE));
            com.moddingfromamod.agent.AgentContext.bind(worker);
            try {
                reply.set(new PaintCreatureTool().call(args));
            } finally {
                com.moddingfromamod.agent.AgentContext.unbind();
            }
        }, "t6g4-paint-fork");
        t.setDaemon(true);
        t.start();

        helper.succeedWhen(() -> {
            helper.assertTrue(reply.get() != null, Component.literal("the paint has not answered yet"));
            helper.assertFalse(reply.get().isError(), Component.literal("the paint was refused: "
                    + reply.get().text()));
            var mine = Creatures.carriersOf(level, forkId);
            helper.assertTrue(mine.size() == 1, Component.literal("his copy '" + forkId + "' is worn by "
                    + mine.size() + " creatures: " + reply.get().text()));
            helper.assertTrue(mine.get(0).distanceTo(child) < 10, Component.literal("the dragon that moved onto "
                    + "his copy is not the one standing next to him"));
            helper.assertTrue(dyes(level, mine.get(0)).getOrDefault(PURPLE, 0) > 0,
                    Component.literal("his dragon's wings are not purple on the rig: " + dyes(level, mine.get(0))));
            helper.assertTrue(Creatures.carriersOf(level, base).contains(other)
                    && dyes(level, other).equals(otherBefore),
                    Component.literal("the OTHER dragon changed: " + otherBefore + " -> " + dyes(level, other)));
            var partsAfter = com.moddingfromamod.runtime.ModelRepository.sculptedParts(base);
            for (int i = 0; i < partsBefore.size(); i++) {
                helper.assertTrue(partsAfter.get(i).colour() == partsBefore.get(i).colour(),
                        Component.literal("the library's dragon itself was repainted: " + partsAfter.get(i).name()));
            }
            for (String id : new String[] {base, forkId}) {
                for (LivingEntity c : Creatures.carriersOf(level, id)) {
                    if (!already.contains(c.getUUID())) {
                        Creatures.remove(level, c);
                    }
                }
            }
            marshal.finish(wish);
            com.moddingfromamod.testing.MockPlayerNetwork.remove(child);
        });
    }

    /** How many of this carrier's displays wear each dye colour: {@link RigReadout#dyes}. */
    public static Map<Integer, Integer> dyes(ServerLevel level, Entity carrier) {
        return RigReadout.dyes(level, carrier);
    }
}
