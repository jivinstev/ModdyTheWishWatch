package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: a rigged creature whose chunk unloads and loads again is driven again.</b>
 *
 * <p>Nothing here simulates the reload. The whale stands in a chunk far from every test, held
 * loaded by a forced-chunk ticket; the ticket is dropped and the test waits until the server has
 * really unloaded the carrier ({@code UNLOADED_TO_CHUNK}); the ticket is put back and the test waits
 * until the SAME carrier (same UUID) has been read back from disk. Only then does it ask whether
 * anything is driving it, whether its displays are the saved ones rather than a rebuild, and
 * whether it has its hit proxies back and none of the saved ones.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class RigReloadGameTest {

    private static final String ID = "gt_reload_whale";

    static List<Entity> owned(ServerLevel level, UUID carrier, boolean proxies) {
        List<Entity> out = new ArrayList<>();
        for (Entity e : level.getEntities().getAll()) {
            boolean mine = proxies ? HitProxies.belongsTo(e, carrier)
                    : e instanceof Display && e.getTags().contains(
                            DisplayRig.RIG_OWNER_PREFIX + carrier);
            if (mine && !e.isRemoved()) {
                out.add(e);
            }
        }
        return out;
    }

    /** The parts as a sculpt's editable spec. */
    public static String spec(List<CreatureSculpt.Part> parts) {
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (CreatureSculpt.Part p : parts) {
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("name", p.name());
            o.add("from", vec(p.from()));
            o.add("to", vec(p.to()));
            o.add("pivot", vec(p.pivot()));
            o.addProperty("colour", String.format("#%06X", p.colour() & 0xFFFFFF));
            arr.add(o);
        }
        com.google.gson.JsonObject spec = new com.google.gson.JsonObject();
        spec.add("parts", arr);
        return spec.toString();
    }

    private static com.google.gson.JsonArray vec(float[] v) {
        com.google.gson.JsonArray a = new com.google.gson.JsonArray();
        for (float f : v) {
            a.add(f);
        }
        return a;
    }

    @GameTest(template = "empty_test", timeoutTicks = 1600)
    public static void aRigIsDrivenAgainAfterItsChunkUnloadsAndLoads(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<CreatureSculpt.Part> parts = RigFitGameTest.whale();
        try {
            // The editable spec carries the parts: it is what an adopt reads the creature back
            // from (ModelRepository.sculptedParts). "{}" reads back as a stand-in body.
            CreatureFiles.write(ID, parts, spec(parts));
        } catch (Exception e) {
            helper.fail(Component.literal("could not write the sculpt: " + e));
            return;
        }
        Species sp = com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, Species.fromJson(JsonParser.parseString(
                "{\"id\":\"" + ID + "\",\"modelKey\":\"" + ID + "\",\"health\":40,"
                        + "\"speed\":0.0,\"scale\":1.0}").getAsJsonObject()));
        // Far from every test, so no other ticket holds the chunk and no test's cleanup reaches it.
        BlockPos base = helper.absolutePos(new BlockPos(4, 1, 4)).offset(4096, 0, 0);
        ChunkPos chunk = new ChunkPos(base);
        Vec3 at = Vec3.atBottomCenterOf(base);
        level.setChunkForced(chunk.x, chunk.z, true);

        UUID[] carrier = new UUID[1];
        Mob[] first = new Mob[1];
        int[] displays = new int[1];
        int[] proxies = new int[1];
        helper.startSequence()
                .thenExecuteAfter(5, () -> {
                    // As the bridge spawns it: from the species' saved sculpt (the parts an adopt
                    // reads back) and its own movement class.
                    List<CreatureSculpt.Part> saved = StandInSculpt.manifest(sp,
                            com.moddingfromamod.runtime.ModelRepository.sculptedParts(ID));
                    DisplayRig rig = DisplayRig.spawn(level, at, 0f, saved, sp.scale(), ID,
                            CarrierPool.movementOf(sp, saved));
                    rig.base().setNoAi(true);
                    VanillaRigAnimator.drive(rig, RigFit.intoRigSpace(saved), java.util.Map.of());
                    carrier[0] = rig.base().getUUID();
                    first[0] = rig.base();
                    displays[0] = rig.displays().size();
                    proxies[0] = rig.proxies().size();
                })
                .thenExecuteAfter(5, () -> {
                    helper.assertTrue(VanillaRigAnimator.isDriving(first[0]), Component.literal("not driven to begin "
                            + "with"));
                    helper.assertTrue(proxies[0] > 0, Component.literal("the whale was given no proxies to lose"));
                    level.setChunkForced(chunk.x, chunk.z, false);
                })
                .thenWaitUntil(() -> {
                    // A GameTest server ticks flat out, so vanilla's unloader never finds the
                    // spare time it waits for (ChunkMap.processUnloads runs its queue only while
                    // the server "has time"). Give it that time here, as a quiet tick would.
                    level.getChunkSource().tick(() -> true, false);
                    helper.assertTrue(first[0].isRemoved() && first[0].getRemovalReason()
                                    == Entity.RemovalReason.UNLOADED_TO_CHUNK,
                            Component.literal("the carrier's chunk has not unloaded yet (" + first[0].getRemovalReason()
                                    + ", noSave=" + level.noSave + ")"));
                })
                .thenExecute(() -> level.setChunkForced(chunk.x, chunk.z, true))
                .thenWaitUntil(() -> {
                    Entity back = level.getEntity(carrier[0]);
                    helper.assertTrue(back != null && back != first[0],
                            Component.literal("the carrier has not been read back from disk yet"));
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        VanillaRigAnimator.isDriving(level.getEntity(carrier[0])),
                        Component.literal("the whale came back from disk and nothing is driving it: a statue")))
                // ⚠ WAIT for the full set, do not count on the adopting tick. The adopt
                // spawns the proxies that tick, and one standing in a neighbouring chunk that is
                // still settling from the reload can be dropped with it; HitProxies.follow puts it
                // back once that spot is loaded. Counting on the first tick read "2 of 3" one run
                // in several. What must hold is that the whale ENDS UP whole — and never with more
                // than it had, which fails at once rather than being waited out.
                .thenWaitUntil(() -> {
                    int d = owned(level, carrier[0], false).size();
                    int p = owned(level, carrier[0], true).size();
                    if (d > displays[0] || p > proxies[0]) {
                        throw new IllegalStateException("the whale came back with MORE than it had: "
                                + d + " displays (had " + displays[0] + "), " + p
                                + " hit proxies (plan has " + proxies[0] + ")");
                    }
                    helper.assertTrue(d == displays[0] && p == proxies[0], Component.literal("the whale came back "
                            + "with " + d + " of " + displays[0] + " displays and " + p + " of "
                            + proxies[0] + " hit proxies"));
                })
                .thenExecute(() -> {
                    try {
                        // whole on this tick too, not just once
                        helper.assertTrue(owned(level, carrier[0], true).size() == proxies[0],
                                Component.literal("the whale's hit proxies came and went again"));
                    } finally {
                        Entity back = level.getEntity(carrier[0]);
                        if (back != null) {
                            VanillaRigAnimator.forget(carrier[0]);
                            DisplayRig.reap(level, carrier[0]);
                            back.discard();
                        }
                        level.setChunkForced(chunk.x, chunk.z, false);
                    }
                })
                .thenSucceed();
    }
}
