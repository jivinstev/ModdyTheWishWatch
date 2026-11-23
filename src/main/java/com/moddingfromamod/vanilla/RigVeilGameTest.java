package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.PlayerKind;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>#515b, asserted on the wire: the child is not shown the half-built creature, and the camera
 * is.</b>
 *
 * <p>Every claim here is read off the child's own {@code EmbeddedChannel} rather than from a
 * counter or a flag, for the reason its neighbour {@code RigPackDeferralGameTest} records: a gate
 * that asks the code whether it did the thing reports its own success. The question a child cares
 * about is which packets reached his client, so that is what is measured.
 *
 * <p>⚠ Deliberately NOT a test of what renders. A remove packet the client never draws and a
 * missing texture look identical to a headless server, so "the paper is gone" is a Gate C claim
 * and is recorded there. What this can prove — and what regresses silently — is the ROUTING: the
 * withheld client is told to forget the parts, the modded client is not, and the reveal hands them
 * back.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class RigVeilGameTest {

    /** Its own batch: mock players and the service-client set are both process-wide. */
    static final String BATCH = "rigveil";

    private static final List<UUID> REGISTERED = new ArrayList<>();

    static List<Object> drain(GameTestHelper helper, ServerPlayer p) {
        var channel = p.connection.getConnection().channel();
        helper.assertTrue(channel instanceof io.netty.channel.embedded.EmbeddedChannel,
                Component.literal("the instrument cannot see the wire: the mock's channel is a "
                        + (channel == null ? "null" : channel.getClass().getName())
                        + ". A capture that silently records nothing reports a perfect control on "
                        + "every run"));
        var embedded = (io.netty.channel.embedded.EmbeddedChannel) channel;
        List<Object> out = new ArrayList<>();
        Object o;
        while ((o = embedded.readOutbound()) != null) {
            out.add(o);
        }
        return out;
    }

    /** Every entity id this client was told to forget, across all remove packets seen. */
    static java.util.Set<Integer> removed(List<Object> packets) {
        java.util.Set<Integer> out = new java.util.HashSet<>();
        for (Object o : packets) {
            if (o instanceof ClientboundRemoveEntitiesPacket rm) {
                rm.getEntityIds().forEach((int id) -> out.add(id));
            }
        }
        return out;
    }

    /** Every entity id this client was told to spawn. */
    private static java.util.Set<Integer> added(List<Object> packets) {
        java.util.Set<Integer> out = new java.util.HashSet<>();
        for (Object o : packets) {
            if (o instanceof ClientboundAddEntityPacket add) {
                out.add(add.getId());
            }
        }
        return out;
    }

    private static List<CreatureSculpt.Part> threeParts() {
        return List.of(
                new CreatureSculpt.Part("body", null, new float[] { 0, 0, 0 },
                        new float[] { -4, 0, -4 }, new float[] { 4, 8, 4 }, 0x8844aa),
                new CreatureSculpt.Part("head", null, new float[] { 0, 8, 0 },
                        new float[] { -3, 8, -3 }, new float[] { 3, 14, 3 }, 0x44aa88),
                new CreatureSculpt.Part("tail", null, new float[] { 0, 2, 4 },
                        new float[] { -2, 2, 4 }, new float[] { 2, 5, 8 }, 0xaa8844));
    }

    static void isolated(GameTestHelper helper, Runnable body) {
        MockPlayerNetwork.clearStaleMocks(helper);
        RigReveal.forgetForTest();
        RigVeil.forget();
        REGISTERED.clear();
        try {
            body.run();
        } finally {
            RigReveal.forgetForTest();
            RigVeil.forget();
            for (UUID id : REGISTERED) {
                PlayerKind.forgetServiceClient(id);
            }
            REGISTERED.clear();
        }
    }

    static ServerPlayer camera(GameTestHelper helper) {
        ServerPlayer cam = MockPlayerNetwork.inLevel(helper);
        PlayerKind.registerServiceClient(cam.getUUID());
        REGISTERED.add(cam.getUUID());
        return cam;
    }

    /** Publish a pack so the deferral has something to withhold, exactly as a sculpt would. */
    static void publish(GameTestHelper helper, String key) {
        try {
            RigReveal.publish(helper.getLevel().getServer(), key, threeParts(), 1.0);
        } catch (Exception e) {
            helper.fail(Component.literal("publishing failed, so this test measured nothing: " + e));
        }
    }

    static DisplayRig build(GameTestHelper helper, Vec3 at) {
        return DisplayRig.spawn(helper.getLevel(), at, 0f, threeParts(), 1.0, 1.0, 2.0);
    }

    static void tearDown(DisplayRig rig) {
        if (rig == null) {
            return;
        }
        for (Entity e : rig.all()) {
            e.discard();
        }
    }

    // ── the withheld child ────────────────────────────────────────────────────────────────────

    // The camera's control, "the camera is shown everything", is RigVeilCameraGameTest: a camera is a modded client,
    // which only the private modded-client plug-in makes.

    // ── placement: the veil must not move anything ────────────────────────────────────────────

    /**
     * <b>A veiled rig stands exactly where it was aimed.</b>
     *
     * <p>The regression this exists to prevent is the design that was NOT taken: hiding the sculpt
     * by building it a thousand blocks away and moving it at the reveal. That works, and it makes
     * the creature's final position a thing computed by a move rather than the spot the child
     * aimed at — CLAUDE.md's mid-air-McTrain bug, in the one feature whose whole promise is "it
     * appears where you pointed". Hiding is a fact about who is TOLD, never about where it is.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public void aVeiledRigStandsExactlyWhereItWasAimed(GameTestHelper helper) {
        isolated(helper, () -> {
            ServerPlayer cam = camera(helper);
            ServerPlayer child = MockPlayerNetwork.inLevel(helper);
            DisplayRig[] rig = new DisplayRig[1];
            try {
                MockPlayerNetwork.withoutChannels(child, () -> {
                    publish(helper, "gt_veil_placement");
                    Vec3 aimed = helper.absoluteVec(new Vec3(2, 2, 2));
                    rig[0] = build(helper, aimed);
                    helper.assertTrue(RigVeil.isVeiling(),
                            Component.literal("nothing was veiled, so this test would pass over a rig that is not "
                                    + "hidden at all"));
                    assertAt(helper, rig[0], aimed, "while veiled");
                    RigReveal.reveal(helper.getLevel().getServer());
                    assertAt(helper, rig[0], aimed, "after the reveal");
                });
            } finally {
                tearDown(rig[0]);
                MockPlayerNetwork.remove(child);
                MockPlayerNetwork.remove(cam);
            }
        });
        helper.succeed();
    }

    private static void assertAt(GameTestHelper helper, DisplayRig rig, Vec3 aimed, String when) {
        double dx = rig.base().getX() - aimed.x;
        double dy = rig.base().getY() - aimed.y;
        double dz = rig.base().getZ() - aimed.z;
        double off = Math.sqrt(dx * dx + dy * dy + dz * dz);
        helper.assertTrue(off < 0.001,
                Component.literal("the creature moved " + String.format("%.3f", off) + " blocks from the spot it "
                        + "was aimed at (" + when + "). A wish manifests where it was aimed, "
                        + "locked at wish time -- hiding it must not stage it somewhere else"));
        for (Entity d : rig.displays()) {
            double ddx = d.getX() - aimed.x;
            double ddz = d.getZ() - aimed.z;
            helper.assertTrue(Math.sqrt(ddx * ddx + ddz * ddz) < 0.001,
                    Component.literal("a rig part is not on the carrier's own column (" + when + "), so the sculpt "
                            + "and the creature are in two different places"));
        }
    }

    // ── the ATOM arm (docs/ATOM_RIG_PLAN.md Phase 3b) ─────────────────────────────────────────

    /**
     * Its own batch, and it has to be: these cases share {@link RigReveal}'s process-wide reveal
     * state, and tests in ONE batch run concurrently. Batches are strictly sequential (measured
     * over all 522 tests, 0 started before their own batch began), so this is the only real
     * isolation available. (They used to also flip the {@code rigatoms} arm, deleted.)
     */
    private static final String ATOM_BATCH = "rigveilatoms";

    /** Publish as a sculpt does: recorded for the reveal, and delivered to nobody. */
    private static void publishAtoms(GameTestHelper helper, String key) {
        publish(helper, key);
    }

    private static void revealAtoms(GameTestHelper helper) {
        RigReveal.reveal(helper.getLevel().getServer());
    }

    /**
     * The LAST thing this client was told about each rig part — the order-aware question, which is
     * the one that matters.
     *
     * <p>A veiled part is genuinely ADDED first: {@code ChunkMap.addEntity} runs synchronously
     * inside {@code addFreshEntity} and the remove rides out in the same flush, so "the child got no
     * add packet" is false of correct behaviour and would fail against the shipping code. What must
     * be true is that nothing he was told to forget is ever handed back before the reveal — an add,
     * a teleport, a move or a data packet arriving after the remove is a leak, and a set-based
     * check cannot see one.
     *
     * @return part id → {@code "add"} / {@code "data"} / {@code "teleport"} / {@code "move"} /
     *         {@code "remove"}, for the last packet that mentioned it
     */
    private static java.util.Map<Integer, String> lastWordOn(
            GameTestHelper helper, List<Object> packets) {
        java.util.Map<Integer, String> out = new java.util.LinkedHashMap<>();
        for (Object o : packets) {
            if (o instanceof ClientboundAddEntityPacket add) {
                out.put(add.getId(), "add");
            } else if (o instanceof net.minecraft.network.protocol.game
                    .ClientboundSetEntityDataPacket data) {
                out.put(data.id(), "data");
            } else if (o instanceof net.minecraft.network.protocol.game
                    .ClientboundTeleportEntityPacket tp) {
                out.put(tp.id(), "teleport");
            } else if (o instanceof net.minecraft.network.protocol.game
                    .ClientboundMoveEntityPacket mv) {
                Entity e = mv.getEntity(helper.getLevel());
                if (e != null) {
                    out.put(e.getId(), "move");
                }
            } else if (o instanceof ClientboundRemoveEntitiesPacket rm) {
                rm.getEntityIds().forEach((int id) -> out.put(id, "remove"));
            }
        }
        return out;
    }

    /** Every rig part whose last word on this wire is not "forget it". */
    private static List<String> stillHeld(GameTestHelper helper, DisplayRig rig,
                                          List<Object> packets) {
        java.util.Map<Integer, String> last = lastWordOn(helper, packets);
        List<String> out = new ArrayList<>();
        for (Entity d : rig.displays()) {
            String word = last.get(d.getId());
            if (word != null && !"remove".equals(word)) {
                out.add(d.getId() + ":" + word);
            }
        }
        return out;
    }

    /**
     * <b>The child was actively told to forget every part</b> — the assertion that is not vacuous.
     *
     * <p>⚠ <b>"He received no add packet" is TRUE HERE FOR THE WRONG REASON, so nothing may rest on
     * it alone.</b> {@code MockPlayerNetwork.inLevel} places its player at WORLD SPAWN, and a
     * GameTest plot is millions of blocks away — measured in this file's own log, {@code mfam-mock
     * (child) 2536440.8m}. Vanilla's tracker therefore never pairs him with the rig at all, so a
     * check that only looks for the absence of adds passes over a build with the veil deleted
     * outright. Verified: with both {@code RigVeilService} sends stubbed out, the order-aware check
     * below stayed green while its pack-arm sibling went red.
     *
     * <p>So the load-bearing question is the ROUTING one its sibling asks — did the veil PUT a
     * remove for this part on this wire — and the order-aware check is the addition on top of it,
     * catching a later add, teleport, move or data packet that hands a veiled part back.
     */
    private static void assertToldToForgetEveryPart(GameTestHelper helper, DisplayRig rig,
                                                    List<Object> packets, String when) {
        java.util.Set<Integer> forgotten = removed(packets);
        List<String> notTold = new ArrayList<>();
        for (Entity d : rig.displays()) {
            if (!forgotten.contains(d.getId())) {
                notTold.add(String.valueOf(d.getId()));
            }
        }
        helper.assertTrue(notTold.isEmpty(),
                Component.literal("the veil never told the child to forget " + notTold.size() + " of "
                        + rig.displays().size() + " rig parts (ids " + String.join(",", notTold)
                        + ") " + when + ". He is far from the plot, so he was sent no spawn either "
                        + "-- but that is an accident of where a mock player stands and would stop "
                        + "being true the moment a real child is beside the anchor, which is the "
                        + "whole topology this phase is about"));
    }

    /**
     * <b>ATOM-P3b (a): on the atom arm the child is shown nothing and the camera is shown
     * everything.</b>
     *
     * <p>The plan's hypothesis for this phase was that the veil would switch OFF here — the child
     * always has the constant cube, so "withhold the pack" has nothing to withhold. The code says a
     * different thing, and this test is what settles it: {@code publish}'s atom branch returns above
     * {@link RigReveal#deliver}, which is the only writer of {@code current}, so before
     * ATOM-P3b {@code currentSha1()} was null for the whole session. {@code alreadyRevealed} guards
     * on non-null, so "revealed" read false for ever and the child WAS veiled — correctly, and by
     * accident. What broke was the other end; see {@link #theRevealOnTheAtomArmReleasesEveryPart}.
     *
     * <p>Kept as its own case anyway, because the accident is now a deliberate invariant: the atom
     * arm records a real sha1 with no URL, so this asserts the veil for the reason it is supposed
     * to hold rather than for the reason it used to.
     */
    @GameTest(template = "empty_test", batch = ATOM_BATCH)
    public void onTheAtomArmTheChildIsNotShownAnInterimSculpt(GameTestHelper helper) {
        isolated(helper, () -> {
            ServerPlayer cam = camera(helper);
            ServerPlayer child = MockPlayerNetwork.inLevel(helper);
            DisplayRig[] rig = new DisplayRig[1];
            try {
                MockPlayerNetwork.withoutChannels(child, () -> {
                    publishAtoms(helper, "gt_atomveil_interim");
                    helper.assertTrue(RigReveal.currentSha1() != null,
                            Component.literal("the atom arm recorded no pack sha1, so RigVeil's rule has no reveal "
                                    + "state to read -- the child would either never be hidden or "
                                    + "never be shown, and neither throws"));
                    drain(helper, child);
                    drain(helper, cam);
                    rig[0] = build(helper, helper.absoluteVec(new Vec3(2, 2, 2)));
                    helper.assertTrue(!rig[0].displays().isEmpty(),
                            Component.literal("the rig has no display parts, so this test asserted nothing"));

                    List<Object> childSaw = drain(helper, child);
                    assertToldToForgetEveryPart(helper, rig[0], childSaw,
                            "on the atom arm, during an interim sculpt");
                    List<String> shown = stillHeld(helper, rig[0], childSaw);
                    helper.assertTrue(shown.isEmpty(),
                            Component.literal("on the atom arm a stock child was left holding " + shown.size()
                                    + " of " + rig[0].displays().size() + " interim rig parts "
                                    + shown + ". He has the constant cube, so these do not render "
                                    + "as paper -- they render as the half-built creature, which is "
                                    + "exactly what item (B) says he must not see"));

                    // The camera's half needs a camera, and a camera is a modded client: without the modded-client
                    // plug-in (the public mod) there is none, and the child's half above is the whole claim.
                    if (!com.moddingfromamod.net.ModdedClient.hasOurJar(cam)) {
                        return;
                    }
                    java.util.Map<Integer, String> camLast = lastWordOn(helper, drain(helper, cam));
                    List<String> hidden = new ArrayList<>();
                    for (Entity d : rig[0].displays()) {
                        if ("remove".equals(camLast.get(d.getId()))) {
                            hidden.add(String.valueOf(d.getId()));
                        }
                    }
                    helper.assertTrue(hidden.isEmpty(),
                            Component.literal("the camera was told to forget " + hidden.size() + " rig part(s) it has "
                                    + "to photograph (ids " + String.join(",", hidden) + "). It is "
                                    + "the only client that must see an interim sculpt"));
                });
            } finally {
                tearDown(rig[0]);
                MockPlayerNetwork.remove(child);
                MockPlayerNetwork.remove(cam);
            }
        });
        helper.succeed();
    }

    /**
     * <b>, blocker 1: the atom arm WRITES no pack, and an unchanged re-publish moves nothing.</b>
     *
     * <p>Until the atom arm wrote the whole per-creature pack on every publish and delivered it
     * to nobody, because the writer's byte-compare was the only re-sculpt oracle and the zip's sha1
     * was the reveal state. {@link RigGeometryPrint} replaced both, so this pins the two things the
     * replacement has to be: no file touched, and a re-publish of the same geometry leaving the
     * reveal state exactly where it was (else a creature nobody re-sculpted would be re-veiled from
     * the child after it had been revealed). The other direction — a real re-sculpt DOES move it —
     * is {@link #onTheAtomArmASecondSculptIsStillVeiled} and {@code RigGeometryPrintTest}.
     */
    @GameTest(template = "empty_test", batch = ATOM_BATCH)
    public void onTheAtomArmNoPackIsWrittenAndARepublishMovesNothing(GameTestHelper helper) {
        isolated(helper, () -> {
            java.nio.file.Path dir = helper.getLevel().getServer().getServerDirectory()
                    .resolve("resourcepacks").resolve("mfam-vanilla-rig");
            String before = snapshot(dir);
            publishAtoms(helper, "gt_atom_nowrite");
            String first = RigReveal.currentSha1();
            helper.assertTrue(first != null && PackDelivery.isSha1(first),
                    Component.literal("the atom arm recorded no reveal state (" + first + "), so the veil has "
                            + "nothing to read"));
            helper.assertTrue(before.equals(snapshot(dir)),
                    Component.literal("the atom arm touched " + dir + " -- it has no per-creature pack, and writing "
                            + "one anyway is the work #582 removed"));
            publishAtoms(helper, "gt_atom_nowrite");
            helper.assertTrue(first.equals(RigReveal.currentSha1()),
                    Component.literal("re-publishing IDENTICAL geometry moved the reveal state " + first + " -> "
                            + RigReveal.currentSha1() + ", so a revealed creature would be "
                            + "veiled again for a re-sculpt that never happened"));
        });
        helper.succeed();
    }

    /** Every file under {@code dir} with its size and mtime, or "absent" — a cheap "was it touched". */
    private static String snapshot(java.nio.file.Path dir) {
        if (!java.nio.file.Files.isDirectory(dir)) {
            return "absent";
        }
        try (var walk = java.nio.file.Files.walk(dir)) {
            StringBuilder out = new StringBuilder();
            walk.filter(java.nio.file.Files::isRegularFile).sorted().forEach(f -> {
                try {
                    out.append(dir.relativize(f)).append(' ')
                            .append(java.nio.file.Files.size(f)).append(' ')
                            .append(java.nio.file.Files.getLastModifiedTime(f).toMillis())
                            .append('\n');
                } catch (java.io.IOException e) {
                    out.append(f).append(" unreadable\n");
                }
            });
            return out.toString();
        } catch (java.io.IOException e) {
            return "unwalkable: " + e;
        }
    }

    /**
     * <b>ATOM-P3b (b): a RE-SCULPT stays veiled.</b>
     *
     * <p>The case a boolean "have we revealed yet" flag passes and a content hash is needed for: a
     * wish sculpts three or four times while the agent critiques itself, and every one of those has
     * to be hidden. On this arm the hash is {@link RigGeometryPrint#combine} over every creature
     * published — the same print {@code VanillaRigAnimator} uses to answer "was this creature
     * re-sculpted", so the two facts cannot drift.
     */
    @GameTest(template = "empty_test", batch = ATOM_BATCH)
    public void onTheAtomArmASecondSculptIsStillVeiled(GameTestHelper helper) {
        isolated(helper, () -> {
            ServerPlayer cam = camera(helper);
            ServerPlayer child = MockPlayerNetwork.inLevel(helper);
            DisplayRig[] first = new DisplayRig[1];
            DisplayRig[] second = new DisplayRig[1];
            try {
                MockPlayerNetwork.withoutChannels(child, () -> {
                    publishAtoms(helper, "gt_atomveil_resculpt");
                    first[0] = build(helper, helper.absoluteVec(new Vec3(2, 2, 2)));
                    // A different creature key, which is what a re-sculpt writes into the shared
                    // pack: new bytes, new sha1, so "already revealed" must go false again.
                    String sha1Before = RigReveal.currentSha1();
                    publishAtoms(helper, "gt_atomveil_resculpt2");
                    helper.assertTrue(!java.util.Objects.equals(
                                    sha1Before, RigReveal.currentSha1()),
                            Component.literal("a second sculpt left the pack sha1 unchanged, so the reveal state "
                                    + "cannot tell the two apart and this test measures nothing"));
                    drain(helper, child);
                    second[0] = build(helper, helper.absoluteVec(new Vec3(4, 2, 2)));
                    List<Object> childSaw = drain(helper, child);
                    assertToldToForgetEveryPart(helper, second[0], childSaw,
                            "on the atom arm, during the SECOND sculpt of one wish");
                    List<String> shown = stillHeld(helper, second[0], childSaw);
                    helper.assertTrue(shown.isEmpty(),
                            Component.literal("the child was left holding " + shown.size() + " part(s) of the SECOND "
                                    + "sculpt " + shown + ". A wish sculpts three or four times and "
                                    + "every one of them has to be hidden"));
                });
            } finally {
                tearDown(first[0]);
                tearDown(second[0]);
                MockPlayerNetwork.remove(child);
                MockPlayerNetwork.remove(cam);
            }
        });
        helper.succeed();
    }

    /**
     * <b>ATOM-P3b (c): a child who relogs mid-wish is veiled again.</b>
     *
     * <p>There is nothing to re-offer a rejoining child (the pack rig's join hook went with it in
     *), so the whole answer is {@link RigVeilService#onServerTick} — vanilla's tracker makes a fresh pairing
     * for a player who rejoins (or simply walks back into range) and the sweep takes it away in the
     * same tick.
     *
     * <p>Driven through the real hook and the real tick handler rather than a reimplementation of
     * either, for the reason its neighbour records: <i>"there is no check" and "the check is not
     * wired to this path" look identical from outside.</i>
     */
    @GameTest(template = "empty_test", batch = ATOM_BATCH)
    public void onTheAtomArmAChildWhoRelogsMidWishIsVeiledAgain(GameTestHelper helper) {
        isolated(helper, () -> {
            ServerPlayer cam = camera(helper);
            ServerPlayer child = MockPlayerNetwork.inLevel(helper);
            DisplayRig[] rig = new DisplayRig[1];
            try {
                MockPlayerNetwork.withoutChannels(child, () -> {
                    publishAtoms(helper, "gt_atomveil_relog");
                    rig[0] = build(helper, helper.absoluteVec(new Vec3(2, 2, 2)));
                    helper.assertTrue(RigVeil.isVeiling(),
                            Component.literal("nothing was veiled before the relog, so this test would pass over a "
                                    + "rig that was never hidden"));
                    drain(helper, child);

                    // (The join hook this used to drive was the pack rig's re-offer;
                    // deleted it with the pack rig. The atom rig has nothing to re-offer, so the
                    // whole answer is the per-tick sweep below.)

                    // Vanilla's tracker re-pairing him: the add the sweep has to answer.
                    for (Entity d : rig[0].displays()) {
                        child.connection.send(new ClientboundAddEntityPacket(d, 0,
                                d.blockPosition()));
                    }
                    drain(helper, child);
                    RigVeilService.onServerTick(
                            new net.neoforged.neoforge.event.tick.ServerTickEvent.Post(
                                    () -> true, helper.getLevel().getServer()));
                    java.util.Set<Integer> forgotten = removed(drain(helper, child));
                    List<String> shown = new ArrayList<>();
                    for (Entity d : rig[0].displays()) {
                        if (!forgotten.contains(d.getId())) {
                            shown.add(String.valueOf(d.getId()));
                        }
                    }
                    helper.assertTrue(shown.isEmpty(),
                            Component.literal("after the relog the per-tick sweep left " + shown.size()
                                    + " rig part(s) visible to the child (ids "
                                    + String.join(",", shown) + "). A child who quits and comes "
                                    + "back mid-wish would watch the rest of the sculpt"));
                });
            } finally {
                tearDown(rig[0]);
                MockPlayerNetwork.remove(child);
                MockPlayerNetwork.remove(cam);
            }
        });
        helper.succeed();
    }

    /**
     * <b>ATOM-P3b (d): the reveal releases every veiled part, exactly once — the case that was
     * BROKEN, and the reason this phase exists.</b>
     *
     * <p>Before ATOM-P3b {@link RigReveal#reveal} took its {@code pack == null} early return on
     * this arm, because {@code current} was never recorded at all. {@code RigVeilService.reveal} has
     * exactly one caller — that method — so the veil never lifted: the ledger stayed full, the
     * per-tick sweep went on hiding the parts every tick, and a stock child's creature was invisible
     * at the reveal and for ever afterwards, including for every later wish.
     *
     * <p><b>Exactly once</b> is asserted as well as "at all". A reveal that re-sent the pairing on
     * every subsequent tick would satisfy the first and put a visible blink in the child's frame.
     */
    @GameTest(template = "empty_test", batch = ATOM_BATCH)
    public void theRevealOnTheAtomArmReleasesEveryPart(GameTestHelper helper) {
        isolated(helper, () -> {
            ServerPlayer cam = camera(helper);
            ServerPlayer child = MockPlayerNetwork.inLevel(helper);
            DisplayRig[] rig = new DisplayRig[1];
            try {
                MockPlayerNetwork.withoutChannels(child, () -> {
                    publishAtoms(helper, "gt_atomveil_reveal");
                    rig[0] = build(helper, helper.absoluteVec(new Vec3(2, 2, 2)));
                    helper.assertTrue(RigVeil.isVeiling(),
                            Component.literal("nothing was veiled, so a reveal that released nothing would pass"));
                    drain(helper, child);

                    revealAtoms(helper);

                    List<Object> afterReveal = drain(helper, child);
                    java.util.Map<Integer, Integer> adds = new java.util.LinkedHashMap<>();
                    for (Object o : afterReveal) {
                        if (o instanceof ClientboundAddEntityPacket add) {
                            adds.merge(add.getId(), 1, Integer::sum);
                        }
                    }
                    List<String> missing = new ArrayList<>();
                    List<String> twice = new ArrayList<>();
                    for (Entity d : rig[0].displays()) {
                        int n = adds.getOrDefault(d.getId(), 0);
                        if (n == 0) {
                            missing.add(String.valueOf(d.getId()));
                        } else if (n > 1) {
                            twice.add(d.getId() + "x" + n);
                        }
                    }
                    helper.assertTrue(missing.isEmpty(),
                            Component.literal("the reveal left " + missing.size() + " of "
                                    + rig[0].displays().size() + " rig parts invisible to the child "
                                    + "(ids " + String.join(",", missing) + "). This is the atom "
                                    + "arm's own defect: reveal returned above RigVeilService "
                                    + "because publish never recorded a pack, so the creature was "
                                    + "never shown to him at all"));
                    helper.assertTrue(twice.isEmpty(),
                            Component.literal("the reveal re-sent a pairing for " + twice + ". Re-adding an entity a "
                                    + "client already holds replaces the instance, which is a "
                                    + "visible blink"));
                    helper.assertTrue(!RigVeil.isVeiling(),
                            Component.literal("the reveal released the parts and left the ledger veiling, so the "
                                    + "per-tick sweep hides them again on the very next tick"));

                    // And it stays released: the sweep must have nothing left to hide.
                    drain(helper, child);
                    RigVeilService.onServerTick(
                            new net.neoforged.neoforge.event.tick.ServerTickEvent.Post(
                                    () -> true, helper.getLevel().getServer()));
                    java.util.Set<Integer> reHidden = removed(drain(helper, child));
                    List<String> takenBack = new ArrayList<>();
                    for (Entity d : rig[0].displays()) {
                        if (reHidden.contains(d.getId())) {
                            takenBack.add(String.valueOf(d.getId()));
                        }
                    }
                    helper.assertTrue(takenBack.isEmpty(),
                            Component.literal("the tick after the reveal took " + takenBack.size() + " part(s) back "
                                    + "off the child (ids " + String.join(",", takenBack) + ")"));
                });
            } finally {
                tearDown(rig[0]);
                MockPlayerNetwork.remove(child);
                MockPlayerNetwork.remove(cam);
            }
        });
        helper.succeed();
    }

    // ── ATOM-P3c: the reveal shows ONE creature, not every unreaped sculpt ────────────────────

    /**
     * <b>Put the world clock forward, so the next rig is veiled at a DIFFERENT tick.</b>
     *
     * <p>A GameTest body is synchronous inside one tick — deliberately, because
     * {@code MockPlayerNetwork.withoutChannels} may not straddle a tick boundary (a channel-less
     * player visible to another mod's join broadcast took a whole Gate B run down). So five sculpts
     * seconds apart cannot be produced by waiting; the clock has to be moved.
     *
     * <p><b>Only ever FORWARD, and never restored.</b> Rewinding a shared world's clock re-runs
     * whatever it had scheduled; advancing an empty test plot by a few seconds costs nothing and is
     * monotonic, which is what every other reader of the clock assumes.
     */
    private static void secondsLater(GameTestHelper helper, int seconds) {
        var data = helper.getLevel().getLevelData();
        helper.assertTrue(data instanceof net.minecraft.world.level.storage.ServerLevelData,
                Component.literal("the world clock cannot be moved, so all five sculpts would land on one tick and "
                        + "this test would assert nothing about the residue"));
        ((net.minecraft.world.level.storage.ServerLevelData) data)
                .setGameTime(helper.getLevel().getGameTime() + seconds * 20L);
    }

    /**
     * <b>ATOM-P3c: five sculpts, one creature at the reveal.</b>
     *
     * <p>The defect this pins, measured on a real wish and photographed
     * ({@code docs/img/atom-rig/veil/atom2-child-reveal-022406.png}): the reveal released
     * <b>290 parts</b> — every rig built while the child could not see them — so he was shown four
     * spare winged Spikors beside the real one, on the beat he is told to look, for nineteen
     * seconds until the agent's own {@code kill @e[tag=…]} cleared them.
     *
     * <p><b>The five rigs here are five LIVE creatures, which is what makes this the real case.</b>
     * A sculpt superseded in place is already gone — {@code rebuildIfRewished} reaps it — so a
     * fixture that re-sculpted one creature five times would leave four dead rigs the reveal
     * already skips, and would pass over the shipping bug. These are five separate
     * {@link DisplayRig#spawn}s of one species at five different ticks, which is exactly what
     * {@code wishmob spawn winged_spikor} four times in sixteen seconds produced.
     *
     * <p>Both halves are asserted on the same drain, and that is what stops the negative being
     * vacuous (see {@link #assertToldToForgetEveryPart}): the final rig's parts ARE on the wire, so
     * the instrument demonstrably sees adds — and none of the other four is.
     */
    @GameTest(template = "empty_test", batch = ATOM_BATCH, timeoutTicks = 300)
    public void theRevealShowsTheChildTheCreatureAndNotEveryUnreapedSculpt(GameTestHelper helper) {
        isolated(helper, () -> {
            ServerPlayer cam = camera(helper);
            ServerPlayer child = MockPlayerNetwork.inLevel(helper);
            List<DisplayRig> rigs = new ArrayList<>();
            try {
                MockPlayerNetwork.withoutChannels(child, () -> {
                    String id = "gt_atomveil_fivesculpt";
                    // Registered so DisplayRig.speciesOn can invert the carrier's tag: the reveal
                    // groups by CREATURE, and a rig it cannot name is never grouped and never
                    // reaped — which would make this test pass for the wrong reason.
                    com.moddingfromamod.testing.SpeciesForTest.put(helper, com.moddingfromamod.species.Species.fromJson(
                                    com.google.gson.JsonParser.parseString(
                                            "{\"id\":\"" + id + "\",\"name\":\"Five Beast\","
                                                    + "\"health\":18,\"scale\":1.0}")
                                            .getAsJsonObject()));
                    publishAtoms(helper, id);

                    for (int i = 0; i < 5; i++) {
                        if (i > 0) {
                            secondsLater(helper, 4);   // the gap the agent's retries really had
                        }
                        rigs.add(DisplayRig.spawn(helper.getLevel(),
                                helper.absoluteVec(new Vec3(2 + i * 2, 2, 2)), 0f,
                                threeParts(), 1.0, 1.0, 2.0, id));
                    }
                    DisplayRig last = rigs.get(4);
                    int parts = last.displays().size();
                    helper.assertTrue(parts > 0, Component.literal("the rigs have no display parts, so this test "
                            + "asserted nothing"));
                    helper.assertTrue(RigVeil.veiled().length == 5 * parts,
                            Component.literal("expected all " + (5 * parts) + " parts of five sculpts to be veiled, "
                                    + "the ledger holds " + RigVeil.veiled().length
                                    + " -- the residue this test is about is what the ledger "
                                    + "releases, so a short ledger measures nothing"));
                    drain(helper, child);

                    revealAtoms(helper);

                    // ── the wire ──────────────────────────────────────────────────────────────
                    java.util.Set<Integer> shown = added(drain(helper, child));
                    List<String> missing = new ArrayList<>();
                    for (Entity d : last.displays()) {
                        if (!shown.contains(d.getId())) {
                            missing.add(String.valueOf(d.getId()));
                        }
                    }
                    helper.assertTrue(missing.isEmpty(),
                            Component.literal("the reveal left " + missing.size() + " of " + parts + " parts of the "
                                    + "creature the child was promised invisible to him (ids "
                                    + String.join(",", missing) + "). He has the models and is "
                                    + "looking at nothing"));
                    List<String> residue = new ArrayList<>();
                    for (int i = 0; i < 4; i++) {
                        for (Entity d : rigs.get(i).displays()) {
                            if (shown.contains(d.getId())) {
                                residue.add("sculpt" + (i + 1) + ":" + d.getId());
                            }
                        }
                    }
                    helper.assertTrue(residue.isEmpty(),
                            Component.literal("the reveal handed the child " + residue.size() + " part(s) of the "
                                    + "four superseded sculpts " + residue + ". That is the "
                                    + "photographed defect: five creatures appear at once on the "
                                    + "beat he is told to look, and stay until something reaps "
                                    + "them"));

                    // ── the world ─────────────────────────────────────────────────────────────
                    List<String> standing = new ArrayList<>();
                    for (int i = 0; i < 4; i++) {
                        for (Entity e : rigs.get(i).all()) {
                            if (e.isAlive()) {
                                standing.add("sculpt" + (i + 1) + ":" + e.getId());
                            }
                        }
                    }
                    helper.assertTrue(standing.isEmpty(),
                            Component.literal(standing.size() + " entit(ies) of the four superseded sculpts are "
                                    + "still standing after the reveal " + standing + ". Left "
                                    + "veiled rather than reaped they are solid, hostile and "
                                    + "invisible -- a worse wish than the duplicate"));
                    for (Entity e : last.all()) {
                        helper.assertTrue(e.isAlive(),
                                Component.literal("the reveal reaped part of the creature it was revealing (id "
                                        + e.getId() + "). The rule is per species and NEWEST-tick "
                                        + "wins; reaping the survivor inverts it"));
                    }
                    helper.assertTrue(!RigVeil.isVeiling(),
                            Component.literal("the reveal left the ledger veiling, so the per-tick sweep hides the "
                                    + "creature again on the very next tick"));
                });
            } finally {
                for (DisplayRig r : rigs) {
                    tearDown(r);
                }
                MockPlayerNetwork.remove(child);
                MockPlayerNetwork.remove(cam);
            }
        });
        helper.succeed();
    }

    /**
     * <b>ATOM-P3c, the control that is not true of the bug: a HERD survives the reveal.</b>
     *
     * <p>The rule reaps by species, so the obvious cheap version of it — "keep the newest rig" —
     * would end {@code /wishmob spawn spikor 5} with one Spikor and four silently deleted. That is
     * why the ledger carries the TICK rather than an ordinal: five rigs from one command share one
     * tick and are one answer, where five spawns four seconds apart are one creature and four
     * retries. Without this case the fix passes its own test by deleting things a child asked for.
     */
    @GameTest(template = "empty_test", batch = ATOM_BATCH, timeoutTicks = 300)
    public void aHerdSpawnedInOneTickAllSurvivesTheReveal(GameTestHelper helper) {
        isolated(helper, () -> {
            ServerPlayer cam = camera(helper);
            ServerPlayer child = MockPlayerNetwork.inLevel(helper);
            List<DisplayRig> herd = new ArrayList<>();
            try {
                MockPlayerNetwork.withoutChannels(child, () -> {
                    String id = "gt_atomveil_herd";
                    com.moddingfromamod.testing.SpeciesForTest.put(helper, com.moddingfromamod.species.Species.fromJson(
                                    com.google.gson.JsonParser.parseString(
                                            "{\"id\":\"" + id + "\",\"name\":\"Herd Beast\","
                                                    + "\"health\":18,\"scale\":1.0}")
                                            .getAsJsonObject()));
                    publishAtoms(helper, id);
                    long tick = helper.getLevel().getGameTime();
                    for (int i = 0; i < 3; i++) {
                        herd.add(DisplayRig.spawn(helper.getLevel(),
                                helper.absoluteVec(new Vec3(2 + i * 2, 2, 4)), 0f,
                                threeParts(), 1.0, 1.0, 2.0, id));
                    }
                    helper.assertTrue(helper.getLevel().getGameTime() == tick,
                            Component.literal("the herd was not spawned in ONE tick, so this control is measuring "
                                    + "the case it exists to rule out"));
                    drain(helper, child);

                    revealAtoms(helper);

                    java.util.Set<Integer> shown = added(drain(helper, child));
                    List<String> lost = new ArrayList<>();
                    for (int i = 0; i < herd.size(); i++) {
                        for (Entity e : herd.get(i).all()) {
                            if (!e.isAlive()) {
                                lost.add("member" + (i + 1) + ":" + e.getId() + " reaped");
                            }
                        }
                        for (Entity d : herd.get(i).displays()) {
                            if (!shown.contains(d.getId())) {
                                lost.add("member" + (i + 1) + ":" + d.getId() + " not shown");
                            }
                        }
                    }
                    helper.assertTrue(lost.isEmpty(),
                            Component.literal("the reveal took " + lost.size() + " away from a herd the child asked "
                                    + "for in one command " + lost + ". One command, one tick, one "
                                    + "answer -- reaping inside it is the fix deleting a wish"));
                });
            } finally {
                for (DisplayRig r : herd) {
                    tearDown(r);
                }
                MockPlayerNetwork.remove(child);
                MockPlayerNetwork.remove(cam);
            }
        });
        helper.succeed();
    }

    /**
     * <b>ATOM-P3b (e): with NO camera serving the child is not veiled on this arm either.</b>
     *
     * <p>{@code Viewpoint}'s {@code CHILDS_EYES} arm, and the third line of {@code PackAudience} (deleted):
     * the child IS the viewpoint, so hiding the rig from him would make the agent's own
     * self-critique photograph an empty anchor and call it a bad sculpt (records that it cannot
     * tell). A veil that fired unconditionally would pass every other case in this file.
     */
    @GameTest(template = "empty_test", batch = ATOM_BATCH)
    public void onTheAtomArmWithNoCameraTheChildSeesTheRigBeingBuilt(GameTestHelper helper) {
        isolated(helper, () -> {
            ServerPlayer child = MockPlayerNetwork.inLevel(helper);
            DisplayRig[] rig = new DisplayRig[1];
            try {
                MockPlayerNetwork.withoutChannels(child, () -> {
                    publishAtoms(helper, "gt_atomveil_nocamera");
                    drain(helper, child);
                    rig[0] = build(helper, helper.absoluteVec(new Vec3(2, 2, 2)));
                    java.util.Set<Integer> forgotten = removed(drain(helper, child));
                    List<String> hidden = new ArrayList<>();
                    for (Entity d : rig[0].displays()) {
                        if (forgotten.contains(d.getId())) {
                            hidden.add(String.valueOf(d.getId()));
                        }
                    }
                    helper.assertTrue(hidden.isEmpty(),
                            Component.literal("no camera is serving, so the child is the viewpoint -- but "
                                    + hidden.size() + " rig part(s) were hidden from him anyway. "
                                    + "The agent would photograph an empty spot and blame its own "
                                    + "sculpt"));
                    helper.assertTrue(!RigVeil.isVeiling(),
                            Component.literal("nothing may be veiled when nobody is being withheld from"));
                });
            } finally {
                tearDown(rig[0]);
                MockPlayerNetwork.remove(child);
            }
        });
        helper.succeed();
    }

    // ── a wish no agent granted ───────────────────────────────────────────────────────

    /**
     * Its own batch: unlike every test above, this one WAITS across ticks (the executor runs a
     * few commands a tick), and tests in one batch run concurrently and share RigReveal's state —
     * in ATOM_BATCH its camera leaked into the no-camera test and their resets erased its state.
     */
    private static final String SERVED_BATCH = "rigveilserved";

    /**
     * <b>A served wish's creature is revealed when the wish finishes.</b> A starter, a cached wish
     * or a library entry runs its commands through {@code WishExecutor} — no agent, so no
     * {@code finish_wish} and no {@code finish_building} — and nothing on that path
     * ever called the reveal: with a camera serving, the starter dragon stayed veiled from every
     * stock child for good (production, 2026-09-28: only its fire breath showed). Driven through
     * the REAL executor, a tick at a time, rather than by calling the reveal — calling it is what
     * the other tests here do, and it is exactly what this path was missing.
     */
    @GameTest(template = "empty_test", batch = SERVED_BATCH, timeoutTicks = 200)
    public void aServedWishRevealsItsCreatureWhenItFinishes(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        RigReveal.forgetForTest();
        RigVeil.forget();
        ServerPlayer cam = camera(helper);
        ServerPlayer child = MockPlayerNetwork.inLevel(helper);
        DisplayRig[] rig = new DisplayRig[1];
        MockPlayerNetwork.withoutChannels(child, () -> {
            publish(helper, "gt_served_reveal");
            rig[0] = build(helper, helper.absoluteVec(new Vec3(2, 2, 2)));
        });
        helper.assertTrue(RigVeil.isVeiling(), Component.literal("nothing was veiled, so this test would pass over "
                + "a creature that was never hidden -- the camera and the stock child did not "
                + "set up the case it exists for"));
        Vec3 at = child.position();
        com.moddingfromamod.wish.WishExecutor.submit(child,
                com.moddingfromamod.wish.Wish.create("a pet dragon that follows me",
                        com.moddingfromamod.wish.WishSource.PRESET, System.currentTimeMillis(),
                        child.getUUID(), child.getGameProfile().getName(),
                        helper.getLevel().dimension().location().toString(), at.x, at.y, at.z),
                new com.moddingfromamod.wish.CommandPlan(List.of("say served"), "Here it is!", 1));
        helper.succeedWhen(() -> {
            helper.assertTrue(!RigVeil.isVeiling()
                            && RigReveal.currentSha1() != null
                            && RigReveal.currentSha1().equals(RigReveal.revealedSha1()),
                    Component.literal("the served wish finished and its creature is still veiled from the stock "
                            + "child (#1088): nothing on the WishExecutor path called the reveal"));
            tearDown(rig[0]);
            MockPlayerNetwork.remove(child);
            MockPlayerNetwork.remove(cam);
            RigReveal.forgetForTest();
            RigVeil.forget();
            for (UUID id : REGISTERED) {
                PlayerKind.forgetServiceClient(id);
            }
            REGISTERED.clear();
        });
    }
}
