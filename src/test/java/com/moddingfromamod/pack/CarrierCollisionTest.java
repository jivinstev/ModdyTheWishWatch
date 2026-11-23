package com.moddingfromamod.pack;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.datapack.VanillaCarrier;
import com.moddingfromamod.vanilla.RigCarrier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The rig and the wish icons share ONE carrier item, and only one pack may ever write it.</b>
 *
 * <p>{@code custom_model_data} on one item is <b>ONE number space</b> ({@code VanillaCarrier:54})
 * and one model FILE, and a pack that carries that file carries it WHOLE — so two packs writing
 * the same item do not merge, the one applied last erases the other's table.
 *
 * <p>Up to there were two writers: the item pack (wish icons, Moddy, and the atom cube
 * library) and a per-creature RIG pack. They were kept apart first by different items
 * ({@code leather_horse_armor} against {@code paper}) and, once the atom default made them the
 * same item, by the atom arm never delivering its rig pack. {@code ATOM_RIG_PLAN.md} §5.8.3
 * photographed what happens when that protection fails: a rig pack that IS selected rewrites
 * {@code leather_horse_armor.json} whole, and because a vanilla {@code custom_model_data}
 * predicate is a {@code >=} threshold with the last match winning, all 62 of Spikor's atom
 * displays fell through to the pack's last creature group and the creature filmed as a scatter
 * of slabs — with every control green.
 *
 * <p>The pack rig is deleted now, so the invariant is simpler and it is the one this file
 * pins: <b>the rig wears the wish carrier</b> (its only colour channel is {@code dyed_color}, which
 * needs a dyeable item), and <b>nothing on the rig path writes or delivers a pack</b> — the
 * item pack is the only table on that item. The pack-arm halves (a delivered rig pack on a
 * different item, and the measurement that its slot range swallowed the icon library) went with
 * the arm they described.
 */
class CarrierCollisionTest {

    /**
     * Are these the same Minecraft item? The two constants are spelled differently on purpose —
     * {@code VanillaCarrier.ITEM_ID} is a full id because it goes into a give line, and
     * {@code RigCarrier.CARRIER} is a bare path because a pack keys models by path — so a string
     * compare would report "no collision" for two names of one item.
     */
    private static boolean sameItem(String itemId, String packPath) {
        String a = itemId.startsWith("minecraft:") ? itemId.substring("minecraft:".length()) : itemId;
        return a.equals(packPath);
    }

    /**
     * Re-pinned from the atom half of the old two-arm test. Stated as an assertion rather
     * than as a comment, so if the two come apart somebody reads the reason instead of finding out
     * from a creature that renders pure white.
     */
    @Test
    @DisplayName("the rig's displays wear the wish carrier — one item, one number space")
    void theRigWearsTheWishCarrier() {
        assertTrue(sameItem(VanillaCarrier.ITEM_ID, RigCarrier.CARRIER),
                "the rig's CARRIER should BE " + VanillaCarrier.ITEM_ID + " and it is "
                        + RigCarrier.CARRIER + ". If these have come apart, either the rig moved "
                        + "to an item with no dyed_color handler (every creature then renders pure "
                        + "white) or the wish carrier moved -- and then the atom cube library is "
                        + "being bound on an item no rig display wears.");
    }

    /**
     * <b>The other half: nothing on the rig path delivers a pack.</b>
     *
     * <p>Read out of {@code RigReveal.java} rather than exercised, because a unit test cannot watch
     * a dedicated server push a pack to a stock client. Re-pinned from "the atom branch
     * returns above the only {@code deliver} call" to "there is no {@code deliver} call": the
     * branch and the call were both deleted with the pack rig, and the regression this now catches
     * is somebody putting a per-creature pack back — which, on a shared item, is §5.8.3's scatter
     * of slabs and a 6.7 s reload in every reveal. Comment lines are skipped, because the class
     * javadoc rightly explains what it used to do.
     */
    @Test
    @DisplayName("the reveal writes and delivers no pack, which is what makes the shared item safe")
    void theRevealDeliversNoPack() throws Exception {
        String src = Files.readString(
                Path.of("src/main/java/com/moddingfromamod/vanilla/RigReveal.java"),
                StandardCharsets.UTF_8);
        assertTrue(src.contains("public static void reveal("),
                "RigReveal.reveal has moved; this pin has to move with it or it is guarding nothing");
        for (String line : src.split("\n")) {
            String t = line.trim();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
                continue;
            }
            for (String token : new String[]{"deliver(", "PackDelivery", "PackHost"}) {
                assertFalse(t.contains(token),
                        "RigReveal is delivering a pack again (" + token + "): " + t + " -- a rig "
                                + "pack rewrites " + VanillaCarrier.ITEM_ID + "'s whole "
                                + "custom_model_data table, shadowing the wish icons, Moddy's "
                                + "watch AND the atom band the rig itself renders from");
            }
        }
    }

    @Test
    @DisplayName("the tripwire is not defeated by the two spellings of one item")
    void theComparisonNormalisesTheNamespace() {
        // The A/B for the guard itself. A naive equals() reads
        //   "minecraft:leather_horse_armor" != "leather_horse_armor"
        // as two different items and reports all-clear on the exact configuration this exists to
        // catch, which is the only way this test could pass while being useless.
        assertTrue(sameItem("minecraft:leather_horse_armor", "leather_horse_armor"),
                "the namespaced id and the bare pack path name ONE item and must compare equal");
        assertTrue(sameItem("minecraft:paper", "paper"));
        assertFalse(sameItem("minecraft:leather_horse_armor", "paper"),
                "these are genuinely different items and must not trip the wire");
        // And the live pairing, spelled out, so a reader can see which side is which today.
        assertTrue(sameItem(VanillaCarrier.ITEM_ID, WishPackWriter.BASE_ITEM),
                "the item pack must be written for the same carrier the give line hands out, or "
                        + "the overrides land on an item no wished thing is ever minted as");
    }
}
