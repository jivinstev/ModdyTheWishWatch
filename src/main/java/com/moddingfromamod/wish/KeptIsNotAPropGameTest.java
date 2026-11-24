package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.tools.FinishWishTool;
import com.moddingfromamod.agent.tools.RunCommandTool;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.testing.MockPlayerNetwork;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>What a wish summons with {@code keep: true} survives the wish's own tidy-up —.</b>
 *
 * <p>Production, wish {@code 0bc44a2f}: three minecarts parked "permanently" in a garage, the
 * reveal said <i>"They stay forever!"</i>, and the settle logged {@code removed the test prop
 * minecraft:minecart (no handle)} three times. A minecart is not alive, not named and not ridden,
 * so every keep rule missed; only the agent knows a car from a probe, so the agent marks it.
 *
 * <p>Driven through the REAL {@code run_command} and {@code finish_wish}, never
 * {@link WishProps#sweep} directly — the pattern {@code LivingMobIsNotAPropGameTest} set, because
 * the mark is stamped inside one tool and read inside the other. Both directions in one wish, and
 * the warning asked of each call:
 *
 * <ul>
 *   <li>three minecarts with {@code keep: true} — no warning, and they STAND after the sweep;</li>
 *   <li>a NoAI armour stand without it — the warning names it, and the sweep still REMOVES it (a fix
 *       that simply stopped sweeping would pass the first half);</li>
 *   <li>a cow with its AI on — no warning, because the sweep already keeps it;</li>
 *   <li>a minecart killed in the same batch it was summoned in — no warning, because nothing is
 *       left standing to tidy;</li>
 *   <li><b>a probe the agent marked anyway</b> — a NoAI pig and a dropped test item in one
 *       {@code keep: true} call, as the magic carrot's agent marks its test animals: the call is
 *       told the mark does not keep them, and the sweep still REMOVES both, while a NoAI pig in the
 *       same call that carries a name (a statue he asked for) STANDS.</li>
 * </ul>
 *
 * <p>Its own batch: it binds a wish run on {@code Marshal.global()}, and {@code AgentContext}
 * falls back to "the single wish in flight", which a concurrent test's wish would make ambiguous.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class KeptIsNotAPropGameTest {

    private static final String TAG = "gt_1343";
    private static final String GONE = "gt_1343_gone";
    private static final int CARS = 3;
    /** How long they must still stand after finish_wish — the production sweep ran at the settle. */
    private static final int STAND_TICKS = 100;
    private static final String WARNS = "will be tidied away when the wish ends";
    /** The marked-anyway probes: removed in spite of keep:true. */
    private static final String PROBE = "gt_1343_probe";
    /** The frozen pig with a name: what a statue he asked for looks like. */
    private static final String STATUE = "gt_1343_statue";

    @GameTest(template = "empty_test", batch = "i1343_keep", timeoutTicks = 1200)
    public static void keptMinecartsSurviveFinishWishAndTheUnkeptPropDoesNot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer mock = MockPlayerNetwork.inLevel(helper);
        UUID player = mock.getUUID();
        // a mock joins at WORLD SPAWN, so every "~" below is at the spot every test shares, and what
        // earlier batches leave there (a building's blocks, lava) killed the statue in a full ci,default
        // run while it passed alone. Planted on purpose, so this is not 1 run in N: a leftover wall where
        // the statue is summoned, which it suffocates in unless the workspace is cleared first.
        BlockPos base = mock.blockPosition();
        level.setBlockAndUpdate(base.offset(7, 0, -3), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(base.offset(7, 1, -3), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        clearWorkspace(level, base, mock);
        Wish wish = WishBook.get(level.getServer()).add(Wish.create(
                "put cars in my garage and make them stay there permanently", WishSource.TYPED,
                System.currentTimeMillis(), player, "Steve", "minecraft:overworld", 0, 64, 0));
        Marshal.global().submit(wish.id(), player, wish.text());
        String worker = "gt-1343";
        Marshal.global().assign(worker, wish.id());
        BuildGate.startWish(wish.id());
        // put back on EVERY ending, never only after a verdict: a timeout here used to leave the wish WORKING,
        // and "the single wish in flight" is what every later test with no worker of its own reads.
        MockPlayerNetwork.afterTest(helper, () -> tidy(level, wish, mock));

        AtomicReference<String> finished = new AtomicReference<>();
        AtomicReference<String> failed = new AtomicReference<>();
        AtomicReference<String> wrongWarning = new AtomicReference<>("");
        Thread t = new Thread(() -> {
            try {
                AgentContext.with(worker, () -> {
                    // The delivery: three cars, marked.
                    JsonArray cars = new JsonArray();
                    for (int i = 0; i < CARS; i++) {
                        cars.add("summon minecraft:minecart ~" + (3 + 2 * i) + " ~ ~3 {Tags:[\"" + TAG
                                + "\"]}");
                    }
                    String said = call(cars, true);
                    if (said.contains(WARNS)) {
                        wrongWarning.set("kept minecarts were warned about: " + said);
                    }
                    // The probe: a NoAI armour stand, unmarked. Warned, by kind and count.
                    JsonArray probe = new JsonArray();
                    probe.add("summon minecraft:armor_stand ~-3 ~ ~3 {NoAI:1b,Tags:[\"" + TAG
                            + "\",\"firetest\"]}");
                    said = call(probe, false);
                    if (!said.contains("1 armor stand from this command " + WARNS)
                            || !said.contains("keep:true")) {
                        wrongWarning.set(wrongWarning.get() + " | the unkept armour stand was not "
                                + "warned about: " + said);
                    }
                    // A live mob: the sweep keeps it already, so no warning.
                    JsonArray cow = new JsonArray();
                    cow.add("summon minecraft:cow ~-3 ~ ~-3 {Tags:[\"" + TAG + "\"]}");
                    said = call(cow, false);
                    if (said.contains(WARNS)) {
                        wrongWarning.set(wrongWarning.get() + " | a live cow was warned about: "
                                + said);
                    }
                    // Summoned and cleared in one batch: nothing left standing, so no warning.
                    JsonArray gone = new JsonArray();
                    gone.add("summon minecraft:minecart ~-5 ~ ~-5 {Tags:[\"" + GONE + "\"]}");
                    gone.add("kill @e[tag=" + GONE + "]");
                    said = call(gone, false);
                    if (said.contains(WARNS)) {
                        wrongWarning.set(wrongWarning.get() + " | a minecart killed in its own "
                                + "batch was warned about: " + said);
                    }

                    // The probe the agent marked anyway: a frozen pig and a dropped test item, in
                    // one keep:true call, beside a named frozen pig (a statue). The mark is
                    // refused for the two probes, by name and count, and the statue is NAMED.
                    JsonArray marked = new JsonArray();
                    marked.add("summon minecraft:pig ~5 ~ ~-3 {NoAI:1b,Tags:[\"" + PROBE + "\"]}");
                    marked.add("summon minecraft:item ~6 ~ ~-6 {PickupDelay:32767,Item:{id:"
                            + "\"minecraft:raw_iron\",count:1},Tags:[\"" + PROBE + "\"]}");
                    marked.add("summon minecraft:pig ~7 ~ ~-3 {NoAI:1b,CustomName:'\"Statue\"',"
                            + "Tags:[\"" + STATUE + "\"]}");
                    said = call(marked, true);
                    // The order of kinds is the order the level hands the new entities back.
                    String refused = said.contains("keep:true does not keep")
                            ? said.substring(said.indexOf("keep:true does not keep")) : "";
                    int colon = refused.indexOf(':', "keep:true".length());
                    refused = colon > 0 ? refused.substring(0, colon) : "";
                    if (!refused.contains("1 pig") || !refused.contains("1 item")
                            || refused.contains("2 pigs") || said.contains("from this command " + WARNS)) {
                        wrongWarning.set(wrongWarning.get() + " | the marked probes were not told "
                                + "the mark does not keep them (or were warned as unmarked): " + said);
                    }

                    String label = "gt_1343_" + System.nanoTime();
                    answerTheCamera(label);
                    JsonObject reveal = new JsonObject();
                    reveal.addProperty("say", "Three cars in your garage. They stay forever!");
                    reveal.addProperty("label", label);
                    new com.moddingfromamod.agent.tools.FinishBuildingTool().call(reveal);
                    WishCapture.forgetForTest(player);

                    JsonObject done = new JsonObject();
                    done.addProperty("id", wish.shortId());
                    done.addProperty("verdict", "done");
                    done.addProperty("note", "Three cars are parked in your garage for good!");
                    var r = new FinishWishTool().call(done);
                    // asked once to teach itself; declining is finishing again.
                    if (r != null && r.isError() && r.text().contains("teach this one first")) {
                        r = new FinishWishTool().call(done);
                    }
                    finished.set(r == null ? "" : r.text());
                    return null;
                });
            } catch (Throwable e) {
                failed.set(String.valueOf(e));
            }
        }, "i1343-agent");
        t.setDaemon(true);
        t.start();

        AtomicLong finishedAt = new AtomicLong(-1);
        AtomicReference<String> verdict = new AtomicReference<>();
        // Held by REFERENCE until finish_wish returns, so the verdict can ask HOW each one went.
        java.util.Set<Entity> seen = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        helper.onEachTick(() -> {
            if (finished.get() == null) {
                for (Entity e : level.getEntities().getAll()) {
                    if (e != null && (e.getTags().contains(TAG) || e.getTags().contains(PROBE)
                            || e.getTags().contains(STATUE))) {
                        seen.add(e);
                    }
                }
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(failed.get() == null, Component.literal("the agent thread threw: " + failed.get()));
            String said = finished.get();
            helper.assertTrue(said != null, Component.literal("waiting for finish_wish"));
            if (finishedAt.get() < 0) {
                finishedAt.set(level.getGameTime());
            }
            if (verdict.get() == null) {
                helper.assertTrue(level.getGameTime() - finishedAt.get() >= STAND_TICKS,
                        Component.literal("waiting " + STAND_TICKS + " ticks after finish_wish"));
                verdict.set(wrongWarning.get().isEmpty() ? judge(said, seen) : wrongWarning.get());
            }
            helper.assertTrue(verdict.get().isEmpty(), Component.literal(verdict.get()));
        });
    }

    /** "" when the kept cars stand and the unkept prop is gone, else what is wrong. */
    /** Taken away by the tidy (or killed), as opposed to merely unloaded with its chunk. */
    private static boolean swept(Entity e) {
        return e.isRemoved() && (e.getRemovalReason() == Entity.RemovalReason.DISCARDED
                || e.getRemovalReason() == Entity.RemovalReason.KILLED);
    }

    private static String statueReason = "";

    private static String judge(String said, java.util.Set<Entity> seen) {
        if (!said.contains("ended as")) {
            return "finish_wish did not end the wish: " + said;
        }
        int cars = 0;
        int carsSwept = 0;
        int stands = 0;
        int standsStanding = 0;
        int probes = 0;
        int probesStanding = 0;
        int statues = 0;
        int statuesSwept = 0;
        for (Entity e : seen) {
            // SWEPT means the tidy took it — DISCARDED or KILLED — never that its chunk UNLOADED,
            // which isRemoved() also reports. The minecart check below always asked for the reason;
            // these two did not, and a release build's busy Gate B unloaded the statue's chunk and read
            // as "the named statue was removed" (2026-11-02, the release; passed in the two before).
            if (e.getTags().contains(PROBE)) {
                probes++;
                if (!swept(e)) {
                    probesStanding++;
                }
                continue;
            }
            if (e.getTags().contains(STATUE)) {
                statues++;
                if (swept(e)) {
                    statuesSwept++;
                    // KILLED is not the sweep (it discards) — say what killed it, and where.
                    var src = e instanceof net.minecraft.world.entity.LivingEntity le
                            ? le.getLastDamageSource() : null;
                    statueReason = e.getRemovalReason() + ", last damage "
                            + (src == null ? "none" : src.getMsgId() + (src.getEntity() == null ? ""
                                    : " by " + net.minecraft.world.entity.EntityType.getKey(
                                            src.getEntity().getType()) + " " + src.getEntity().getTags()))
                            + " at " + e.blockPosition().toShortString();
                }
                continue;
            }
            if (e instanceof Minecart) {
                cars++;
                if (e.isRemoved() && e.getRemovalReason() == Entity.RemovalReason.DISCARDED) {
                    carsSwept++;
                }
            } else if (e instanceof ArmorStand) {
                stands++;
                if (!e.isRemoved()) {
                    standsStanding++;
                }
            }
        }
        if (cars != CARS || stands != 1) {
            return "the fixture summoned " + cars + " minecarts and " + stands + " armour stands, not "
                    + CARS + " and 1 — the fixture, not the sweep. finish_wish said: " + said;
        }
        if (carsSwept != 0) {
            return "#1343: " + carsSwept + " of " + CARS + " minecarts summoned with keep:true were "
                    + "REMOVED by the end-of-wish tidy. finish_wish said: " + said;
        }
        if (standsStanding != 0) {
            return "the unkept NoAI armour stand was a test prop and is still standing — keep must "
                    + "mark one command's spawns, not stop sweeping. finish_wish said: " + said;
        }
        if (probes != 2 || statues != 1) {
            return "the fixture summoned " + probes + " marked probes and " + statues + " statues, "
                    + "not 2 and 1. finish_wish said: " + said;
        }
        if (probesStanding != 0) {
            return "#1343: " + probesStanding + " of 2 probes summoned with keep:true (a NoAI pig, a "
                    + "dropped item) are STILL STANDING — the mark must not keep a frozen mob or a "
                    + "drop. finish_wish said: " + said;
        }
        if (statuesSwept != 0) {
            return "the NAMED frozen pig (a statue) was removed (" + statueReason + ") — a name keeps it. "
                    + "finish_wish said: "
                    + said;
        }
        if (!said.contains("left") || !said.contains("part of the wish")) {
            return "finish_wish did not report the kept things it left alone: " + said;
        }
        return "";
    }

    private static String call(JsonArray commands, boolean keep) {
        JsonObject args = new JsonObject();
        args.add("command", commands);
        if (keep) {
            args.addProperty("keep", true);
        }
        var r = new RunCommandTool().call(args);
        if (r == null || r.isError()) {
            throw new IllegalStateException("run_command failed: " + (r == null ? "null" : r.text()));
        }
        return r.text();
    }

    /** Writes the photo finish_building waits for, as LivingMobIsNotAPropGameTest does. */
    private static void answerTheCamera(String label) {
        com.moddingfromamod.testing.FakeCamera.answer(label);
    }

    /**
     * <b>Its own room at the spot every test shares</b>: air where its commands summon (they reach
     * ~-6..~7 sideways), a floor under it, and nothing another test left standing there. A named batch runs
     * alone, so anything here is a leftover.
     */
    private static void clearWorkspace(ServerLevel level, BlockPos base, ServerPlayer mock) {
        int r = 10;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                level.setBlockAndUpdate(base.offset(x, -1, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                for (int y = 0; y <= 4; y++) {
                    level.setBlockAndUpdate(base.offset(x, y, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                }
            }
        }
        for (Entity e : level.getEntities((Entity) null, new net.minecraft.world.phys.AABB(base).inflate(r + 2, 6, r + 2),
                e -> !(e instanceof net.minecraft.world.entity.player.Player))) {
            e.discard();
        }
    }

    private static void tidy(ServerLevel level, Wish wish, ServerPlayer mock) {
        java.util.List<Entity> ours = new java.util.ArrayList<>();
        for (Entity e : level.getEntities().getAll()) {
            if (e != null && (e.getTags().contains(TAG) || e.getTags().contains(GONE)
                    || e.getTags().contains(PROBE) || e.getTags().contains(STATUE))) {
                ours.add(e);
            }
        }
        ours.forEach(Entity::discard);
        Marshal.global().finish(wish.id());
        BuildGate.settle(wish.id());
        MockPlayerNetwork.remove(mock);
    }
}
