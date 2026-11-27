package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.ArtifactMemory;
import com.moddingfromamod.marshal.HandoverRecord;
import com.moddingfromamod.marshal.MemoryBrief;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * Gate B for the turn journal: it survives a reload, and <b>an existing world's notes are not thrown
 * away by the new format</b>.
 *
 * <p>The migration is the part that can only be proven here. Steve has a real save with a
 * revision-8 record in it; a new store that quietly ignored the old files would reset the memory of
 * everything he has built, and the symptom a child would see is Moddy suddenly re-asking things it
 * already knew — which nothing in a unit test would notice.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class ArtifactMemoryGameTest {

    private static ArtifactMemory.Turn turn(String wishId, String wish, String why,
                                            String... rejected) {
        return new ArtifactMemory.Turn(wishId, System.currentTimeMillis(), "gt-w1", wish, "",
                "did it", why, List.of(rejected), "told him", "done", "");
    }

    /** A whole ladder is written, read back, and still whole. */
    @GameTest(template = "empty_test", batch = "artifactMemory")
    public static void aLadderOfTurnsSurvivesASaveAndLoad(GameTestHelper helper) {
        String id = "gt_mem_" + helper.getLevel().getGameTime();
        for (int i = 1; i <= 6; i++) {
            ArtifactMemoryStore.append(id, "creature",
                    turn("w" + i, "wish " + i, "because reason " + i, "dead end " + i),
                    i == 1 ? "a blue hedgehog" : "", "0,64,0", List.of("scale " + i), List.of());
        }
        // As a reload would -- read the FILE, ignoring the in-memory mirror. Deliberately not
        // clear(), which would empty that mirror for every test sharing this batch.
        ArtifactMemory back = ArtifactMemoryStore.savedOnDisk(id);
        helper.assertTrue(back != null, Component.literal("the journal must survive a reload"));
        helper.assertTrue(back.revision() == 6, Component.literal("all six turns, not the last one — got "
                + back.revision()));
        for (int i = 1; i <= 6; i++) {
            helper.assertTrue(back.turns().get(i - 1).why().contains("reason " + i),
                    Component.literal("turn " + i + "'s reasoning is gone — this is the 600-char truncation bug back"));
        }
        helper.assertTrue(back.allRejected().size() == 6, Component.literal("every dead end kept"));
        helper.assertTrue(back.state().equals(List.of("scale 6")),
                Component.literal("state is what is TRUE NOW, so the newest wins — got " + back.state()));
        helper.assertTrue(back.whatItIs().equals("a blue hedgehog"),
                Component.literal("and the description is not overwritten by the latest wish text"));
        helper.succeed();
    }

    /** The same wish recorded twice is one turn — what makes two workers on one thing safe. */
    @GameTest(template = "empty_test", batch = "artifactMemory")
    public static void twoWorkersRecordingOneWishDoNotDoubleIt(GameTestHelper helper) {
        String id = "gt_mem_dup_" + helper.getLevel().getGameTime();
        ArtifactMemoryStore.append(id, "creature", turn("same-wish", "make it", "first"),
                "", "", List.of(), List.of());
        ArtifactMemoryStore.append(id, "creature", turn("same-wish", "make it", "corrected"),
                "", "", List.of(), List.of());
        ArtifactMemory m = ArtifactMemoryStore.read(id);
        helper.assertTrue(m.revision() == 1, Component.literal("idempotent per wish, got " + m.revision()));
        helper.assertTrue(m.turns().get(0).why().equals("corrected"), Component.literal("and the later write wins"));
        helper.succeed();
    }

    /**
     * An existing world's handover record is carried over rather than discarded.
     *
     * <p>Honest about what it is: the old format cannot be un-merged into the turns it was made
     * from, so it becomes ONE synthetic turn — but the edits it stands for still count towards the
     * edit number the agent is shown.
     */
    @GameTest(template = "empty_test", batch = "artifactMemory")
    public static void anOldHandoverRecordIsMigratedNotLost(GameTestHelper helper) {
        String id = "gt_mem_migrate_" + helper.getLevel().getGameTime();
        HandoverStore.write(new HandoverRecord(id, "contraption",
                "a machine that grinds trees", List.of("the grinder"),
                "switched the fill from replace to destroy so items really drop",
                "the replace fill made trees vanish · summoning output at the log let him grab it",
                "-1861,-1,1054", 8, "harvest box is x -1875..-1851", System.currentTimeMillis()));

        ArtifactMemory m = ArtifactMemoryStore.read(id);
        helper.assertTrue(m != null, Component.literal("an existing world's notes must not be silently reset"));
        helper.assertTrue(m.revision() == 8,
                Component.literal("all eight edits must still be counted — got " + m.revision()));
        helper.assertTrue(m.turns().size() == 1,
                Component.literal("…as ONE synthetic turn, because the old format cannot be un-merged honestly"));
        helper.assertTrue(m.allRejected().size() == 2,
                Component.literal("both surviving dead ends carried over, got " + m.allRejected().size()));
        helper.assertTrue(m.whatItIs().contains("grinds trees"), Component.literal("the description carries over"));

        // And a NEW turn appends onto it rather than starting again.
        ArtifactMemoryStore.append(id, "contraption",
                turn("new-1", "make it do leaves too", "extended the tag"),
                "", "", List.of("now harvests logs and leaves"), List.of());
        ArtifactMemory after = ArtifactMemoryStore.read(id);
        helper.assertTrue(after.revision() == 9, Component.literal("got " + after.revision()));
        helper.assertTrue(MemoryBrief.render(after).text().contains("edit #10"),
                Component.literal("the agent is told how much history it is really looking at"));
        helper.succeed();
    }

    /**
     * <b>An item's memory and hand-over note, under the names Windows accepts, and an old save still loads.</b> An item's
     * id is {@code id:<name>}; both stores once named the file {@code id:<name>.json}, which Windows refuses
     * ({@code InvalidPathException} inside a server task: a Builder edit of an item never finished there). Now the file
     * is {@code store/SafeFileName}'s. Where this OS can hold the old name (Linux, macOS: where every such save was
     * written), a file saved under it is put there by hand and must load, and move to the new name; where it cannot
     * (Windows), the round trip through the real stores is the regression itself.
     */
    @GameTest(template = "empty_test", batch = "artifactMemoryNames")
    public static void anItemsNotesLoadUnderWindowsSafeNamesAndOldSavesStillLoad(GameTestHelper helper) {
        long t = helper.getLevel().getGameTime();
        java.nio.file.Path wishes = com.moddingfromamod.runtime.WorldPaths.wishesDir();
        helper.assertTrue(wishes != null, Component.literal("no world directory"));
        // 1. the round trip, under an id with a ':' (it threw on Windows)
        String fresh = "id:gt_name_new_" + t;
        ArtifactMemoryStore.append(fresh, "item", turn("w1", "a zappy wand", "first"), "a wand", "", List.of(), List.of());
        helper.assertTrue(ArtifactMemoryStore.savedOnDisk(fresh) != null, Component.literal("an item's memory is saved"));
        helper.assertTrue(java.nio.file.Files.isRegularFile(wishes.resolve("memory").resolve(ArtifactMemoryStore.fileName(fresh))),
                Component.literal("under " + ArtifactMemoryStore.fileName(fresh)));
        HandoverStore.write(new HandoverRecord(fresh, "item", "a zappy wand", List.of(), "", "", "", 1, "", System.currentTimeMillis()));
        helper.assertTrue(HandoverStore.savedOnDisk(fresh) != null, Component.literal("an item's hand-over note is saved"));
        // 2. a save written under the old raw name, where this OS can hold it
        String old = "id:gt_name_old_" + t;
        java.nio.file.Path oldFile;
        try {
            oldFile = wishes.resolve("memory").resolve(old + ".json");
        } catch (java.nio.file.InvalidPathException windows) {
            helper.succeed();   // this OS can never have saved the old name: part 1 is the whole regression here
            return;
        }
        try {
            java.nio.file.Files.createDirectories(oldFile.getParent());
            java.nio.file.Files.writeString(oldFile, ArtifactMemoryStore.toJson(
                    ArtifactMemory.of(old, "item").plus(turn("w0", "an old wand", "saved before the rename"),
                            "an old wand", "", List.of(), List.of())).toString());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        ArtifactMemory back = ArtifactMemoryStore.read(old);
        helper.assertTrue(back != null && back.whatItIs().equals("an old wand"),
                Component.literal("a memory saved under its old name must load, got " + back));
        java.nio.file.Path moved = wishes.resolve("memory").resolve(ArtifactMemoryStore.fileName(old));
        helper.assertTrue(java.nio.file.Files.isRegularFile(moved) && !java.nio.file.Files.exists(oldFile),
                Component.literal("and move to " + moved.getFileName() + " on that first read"));
        helper.succeed();
    }

    /**
     * A brand-new artifact gets ONE turn, not a phantom migration of the record this very wish
     * just wrote.
     *
     * <p>Found in the first real ladder run: {@code finish_wish} wrote the legacy handover record
     * before appending to the journal, so the journal's migrate-on-first-read folded in a record
     * that was seconds old. Every new thing came out with a duplicate turn 1 reading "carried over
     * from 1 earlier edit(s)" and an edit count one too high. The store was working exactly as
     * designed, in the wrong order.
     *
     * <p><b>Driven through the REAL tool</b>, and that is the whole point. The first version of
     * this test wrote the handover record and then called the store itself — which reproduces the
     * bug from the test's own two lines and therefore fails whichever order the tool uses. It is
     * the same "test THROUGH the seam, not next to it" mistake this repo keeps paying for, and it
     * cost a red ratchet to notice.
     */
    @GameTest(template = "empty_test", batch = "artifactMemory")
    public static void aFirstWishLeavesExactlyOneTurn(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        String id = "gt_mem_first_" + helper.getLevel().getGameTime();
        java.util.UUID player = java.util.UUID.nameUUIDFromBytes("gt-first-child".getBytes());

        Wish wish = WishBook.get(server).add(Wish.create(
                "make me a " + id, WishSource.TYPED, System.currentTimeMillis(),
                player, "Steve", "minecraft:overworld", 0, 64, 0));
        com.moddingfromamod.marshal.Marshal.global().submit(wish.id(), player, wish.text());
        var run = com.moddingfromamod.marshal.Marshal.global().assign("gt-first-w1", wish.id());
        run.made(com.moddingfromamod.marshal.MadeKind.CREATURE, id);

        com.google.gson.JsonObject args = new com.google.gson.JsonObject();
        args.addProperty("id", wish.shortId());
        args.addProperty("verdict", "done");
        args.addProperty("artifact", id);
        args.addProperty("tried", "green looked wrong so he stayed blue");
        args.addProperty("state", "scale 1.0");

        java.util.concurrent.atomic.AtomicBoolean done =
                new java.util.concurrent.atomic.AtomicBoolean();
        Thread t = new Thread(() -> {
            new com.moddingfromamod.agent.tools.FinishWishTool().call(args);
            new com.moddingfromamod.agent.tools.FinishWishTool().call(args);   // BuildGate refuses once
            done.set(true);
        }, "first-wish-gametest");
        t.setDaemon(true);
        t.start();

        // WALL-CLOCK, not ticks: done is set by a background thread, and a 1.21.4 GameTest server
        // can run its whole tick budget inside one real second (test.RealTime; failed that way on
        // The Gate B with "waiting for finish_wish to return").
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(done.get(), Component.literal("waiting for finish_wish to return"));
            ArtifactMemory m = ArtifactMemoryStore.read(id);
            helper.assertTrue(m != null, Component.literal("the wish recorded nothing at all"));
            helper.assertTrue(m.turns().size() == 1,
                    Component.literal("a first wish must leave ONE turn, got " + m.turns().size()
                            + " — a phantom migration of the record this very wish wrote"));
            helper.assertTrue(m.revision() == 1, Component.literal("and edit #1, got #" + m.revision()));
            helper.assertTrue(!m.turns().get(0).did().contains("carried over"),
                    Component.literal("the one turn must be the real one, not the synthetic carry-over"));
            com.moddingfromamod.marshal.Marshal.global().finish(wish.id());
            BuildGate.settle(wish.id());
        });
    }

    /**
     * <b>Every part of a compound wish gets its notes</b>: a castle and its seahorses, with
     * no {@code artifact} named, used to record the castle alone (the first thing made), so the next
     * "make the seahorses darker" started with no history. Through the real tool.
     */
    @GameTest(template = "empty_test", batch = "artifactMemoryParts")
    public static void aCompoundWishRecordsEveryPart(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        long t = helper.getLevel().getGameTime();
        String castle = "gt_mem_castle_" + t;
        String seahorse = "gt_mem_seahorse_" + t;
        java.util.UUID player = java.util.UUID.nameUUIDFromBytes("gt-parts-child".getBytes());
        Wish wish = WishBook.get(server).add(Wish.create(
                "an underwater sand castle with seahorses", WishSource.TYPED, System.currentTimeMillis(),
                player, "Steve", "minecraft:overworld", 0, 64, 0));
        com.moddingfromamod.marshal.Marshal.global().submit(wish.id(), player, wish.text());
        var run = com.moddingfromamod.marshal.Marshal.global().assign("gt-parts-w1", wish.id());
        run.made(com.moddingfromamod.marshal.MadeKind.BUILD, castle);
        run.made(com.moddingfromamod.marshal.MadeKind.CREATURE, seahorse);

        com.google.gson.JsonObject args = new com.google.gson.JsonObject();
        args.addProperty("id", wish.shortId());
        args.addProperty("verdict", "done");
        args.addProperty("tried", "sand caps fell, so sandstone");
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        Thread th = new Thread(() -> {
            // Each of finish_wish's guards (no finish_building, a creature not standing, teach the
            // watch) refuses ONCE and then gives way, as an agent would find by calling again.
            for (int i = 0; i < 8; i++) {
                if (!new com.moddingfromamod.agent.tools.FinishWishTool().call(args).isError()) {
                    break;
                }
            }
            done.set(true);
        }, "parts-wish-gametest");
        th.setDaemon(true);
        th.start();
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(done.get(), Component.literal("waiting for finish_wish to return"));
            helper.assertTrue(ArtifactMemoryStore.read(castle) != null, Component.literal("the castle has no notes"));
            helper.assertTrue(ArtifactMemoryStore.read(seahorse) != null,
                    Component.literal("the seahorses have no notes: only the first part of the wish was recorded"));
            com.moddingfromamod.marshal.Marshal.global().finish(wish.id());
            BuildGate.settle(wish.id());
        });
    }

    /**
     * <b>THE TREATMENT ITSELF.</b> Under {@code wishcontext=on} a waking worker must actually be
     * handed the journal — and under {@code shuffled} it must be handed somebody else's.
     *
     * <p>Written before arm C is ever run, because every other failure in this branch has had the
     * same shape: a path that returns empty, a run that completes, numbers that look fine, and an
     * arm that measured nothing. If {@code contextFor} came back blank under ON, arm C would score
     * as "the treatment did not help" when the treatment was never applied.
     */
    @GameTest(template = "empty_test", batch = "artifactMemory")
    public static void theTreatmentReallyHandsOverTheJournal(GameTestHelper helper) {
        String id = "gt_treat_" + helper.getLevel().getGameTime();
        String other = "gt_decoy_" + helper.getLevel().getGameTime();
        for (String a : new String[] {id, other}) {
            for (int i = 1; i <= 3; i++) {
                ArtifactMemoryStore.append(a, "creature",
                        turn(a + "-w" + i, "wish " + i + " about " + a,
                                "because reason " + i + " for " + a, "dead end " + i),
                        i == 1 ? "a thing called " + a : "", "0,64,0", List.of("scale " + i),
                        List.of());
            }
        }
        var before = com.moddingfromamod.marshal.WishContextMode.current();
        try {
            com.moddingfromamod.marshal.WishContextMode.override(
                    com.moddingfromamod.marshal.WishContextMode.OFF);
            helper.assertTrue(HandoverBrief.contextFor("make it taller", id).isEmpty(),
                    Component.literal("OFF must inject nothing — it is arm A/B's floor"));

            com.moddingfromamod.marshal.WishContextMode.override(
                    com.moddingfromamod.marshal.WishContextMode.ON);
            var on = HandoverBrief.contextFor("make it taller", id);
            helper.assertTrue(!on.isEmpty(), Component.literal("ON must hand over the journal, or arm C measures "
                    + "nothing while looking like it ran"));
            helper.assertTrue(on.text().contains("reason 3"),
                    Component.literal("…including the most recent turn's reasoning"));
            helper.assertTrue(on.text().contains("dead end 1"),
                    Component.literal("…and every dead end, which is the point of keeping them"));
            helper.assertTrue(on.chars() > 0, Component.literal("and it must report what it cost"));

            com.moddingfromamod.marshal.WishContextMode.override(
                    com.moddingfromamod.marshal.WishContextMode.SHUFFLED);
            var shuffled = HandoverBrief.contextFor("make it taller", id);
            helper.assertTrue(!shuffled.isEmpty(), Component.literal("the control must pay the same cost as the "
                    + "treatment, or it controls for nothing"));
            helper.assertTrue(!shuffled.text().contains(id),
                    Component.literal("…and must be about a DIFFERENT artifact: got the real one, so threat 3's "
                    + "control is just the treatment again"));
            // NOT "specifically the artifact this test made". That is what it used to say, and it
            // was over-specified against a production rule that deliberately picks whichever
            // artifact has the MOST turns — so as soon as a sibling test in this batch left a
            // 6-turn artifact in the world, the correct decoy stopped being this one and the
            // assertion failed. Which sibling has run by now depends on batch ordering, so it
            // failed in some runs and not others: a flaky ratchet test, which is worse than none.
            //
            // What the control actually has to be is a real journal about something else, and at
            // least as rich as the treatment's — so the rule itself is what gets asserted, against
            // whatever the world happens to hold.
            String decoy = HandoverBrief.decoyFor(id);
            helper.assertTrue(!decoy.isBlank() && !decoy.equalsIgnoreCase(id),
                    Component.literal("the decoy must be some other artifact, not nothing and not the real one"));
            int decoyTurns = 0;
            int richestOther = 0;
            for (ArtifactMemory m : ArtifactMemoryStore.all()) {
                if (m.artifactId().equalsIgnoreCase(id)) {
                    continue;
                }
                richestOther = Math.max(richestOther, m.turns().size());
                if (m.artifactId().equalsIgnoreCase(decoy)) {
                    decoyTurns = m.turns().size();
                }
            }
            helper.assertTrue(decoyTurns == richestOther,
                    Component.literal("the decoy must be the RICHEST other journal (" + decoy + " has " + decoyTurns
                            + " turns, the richest other has " + richestOther + ") — a random "
                            + "pick would sometimes hand over a stub and make the control cheaper "
                            + "than the thing it controls for"));
            helper.assertTrue(decoyTurns >= 3,
                    Component.literal("…and this test put a 3-turn artifact in the world, so the richest cannot be "
                            + "less than that: got " + decoyTurns));
        } finally {
            com.moddingfromamod.marshal.WishContextMode.override(before);
        }
        helper.succeed();
    }

    /** A damaged turn costs that turn, never the whole artifact's history. */
    @GameTest(template = "empty_test", batch = "artifactMemory")
    public static void oneDamagedTurnDoesNotDestroyTheRest(GameTestHelper helper) {
        var o = ArtifactMemoryStore.toJson(ArtifactMemory.of("gt_mem_bad", "creature")
                .plus(turn("a", "wish a", "reason a"), "it", "0,0,0", List.of(), List.of())
                .plus(turn("b", "wish b", "reason b"), "", "", List.of(), List.of()));
        o.getAsJsonArray("turns").set(0, new com.google.gson.JsonPrimitive("not an object"));

        ArtifactMemory loaded = ArtifactMemoryStore.fromJson(o);
        helper.assertTrue(loaded != null, Component.literal("one bad turn must never lose the artifact"));
        helper.assertTrue(loaded.turns().size() == 1, Component.literal("the good turn survives"));
        helper.assertTrue(loaded.turns().get(0).why().equals("reason b"), Component.literal("and it is the right one"));
        helper.succeed();
    }
}
