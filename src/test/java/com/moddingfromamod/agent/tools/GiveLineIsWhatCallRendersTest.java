package com.moddingfromamod.agent.tools;

import java.util.List;

import com.moddingfromamod.datapack.ItemBase;
import com.moddingfromamod.datapack.VanillaCarrier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>What a wished thing looks like on BOTH routes — asked of the argument {@code call} passes.</b>
 *
 * <p>Every other test of the give line hands {@link MakeItemTool#itemArg} or a short
 * {@link MakeItemTool#gives} overload a base item of its own choosing, and each of those defaults
 * to the raw {@code wish_item}. The tool itself does not: {@code call} reads {@code base_item}
 * straight off the agent's arguments and passes it down. For an afternoon it resolved that value
 * first, and the difference is invisible to every one of those tests — this repo's own rule that
 * <i>a rule tested where it is DEFINED is not tested where it RUNS</i>.
 *
 * <p>So this asks the question in the two shapes {@code call} can actually be in — a wish made from
 * scratch ({@code base_item} absent, so {@code null}) and a wish built on an item the child named —
 * and it asks it on <b>both</b> routes, because the whole point of the vanilla-compat work is that
 * the route is a view-layer choice and a wished thing must be the same wished thing either way.
 *
 * <p>Deliberately a PROPERTY test, not a byte-pin. The bytes of each route are pinned elsewhere
 * ({@code GivesTest} for the mod item, {@code CarrierGiveLineTest} for the carrier); what is
 * asserted here is the thing that silently stopped being true.
 *
 * <h2>⚠ What this canNOT see, stated so nobody trusts it for it</h2>
 *
 * <p>{@code call} needs a live registry, so this drives {@code gives} with a base of its own —
 * which means it pins the <b>semantics</b> ("given THIS raw base, the give line must contain
 * that") and not the <b>argument</b>. Reintroducing the original defect leaves this test green;
 * verified. The call site is guarded by {@code SourceRuleSweepTest.noRendererIsHandedAResolvedBase},
 * which does fail, by file and by call. Two guards for one bug because neither covers the other
 * half, and a single green test here would read as covering both.
 */
class GiveLineIsWhatCallRendersTest {

    /** Exactly the expression {@code MakeItemTool.call} builds its GIVES from. */
    private static String asCallRendersIt(String baseItem) {
        return MakeItemTool.gives("held", "hot_coffee", 3, 1, "Right-click to use it.",
                List.of(new MakeItemTool.Variant("Hot Coffee", 0x4A2C14)), com.moddingfromamod.datapack.ItemRecipient.ME, null,
                baseItem);
    }

    private static void onEachRoute(Runnable body) {
        for (boolean carrier : List.of(false, true)) {
            VanillaCarrier.force(carrier);
            try {
                body.run();
            } finally {
                VanillaCarrier.force(null);
            }
        }
    }

    @Test
    @DisplayName("a wish made from scratch keeps its icon and its colour on either route")
    void aFromScratchWishIsStillDrawnLikeAWishedThing() {
        onEachRoute(() -> {
            // base_item absent is the ordinary case and by far the commonest: nearly every wish is
            // made from scratch, so this line is what a child sees.
            String give = asCallRendersIt(null);
            assertTrue(give.contains(ItemBase.id(null) + "["),
                    "it has to be handed out as whatever our own item currently is: " + give);
            assertTrue(give.contains("minecraft:custom_model_data=" + com.moddingfromamod.compat.ModelData.snbt(3)),
                    "no custom_model_data means no icon — the carrier renders as its own vanilla "
                            + "artwork, so this is a plain brown horse armour: " + give);
            assertTrue(give.contains("minecraft:dyed_color="),
                    "no dyed_color means the colour the tool REPORTED is not the colour he gets, "
                            + "and nothing anywhere says so: " + give);
            assertTrue(give.contains("WishItem:\"hot_coffee\""), give);
        });
    }

    @Test
    @DisplayName("on the carrier route it is marked as ours and brings none of the carrier's own meaning")
    void aCarriedWishIsMarkedAndNeutralised() {
        VanillaCarrier.force(true);
        try {
            String give = asCallRendersIt(null);
            // THE MARK. Without it the item is byte-identical to a vanilla leather horse armour
            // that happens to be named, so the creative-menu shim never picks it up and the
            // ability bridge never fires — the wish works only until he closes the game.
            assertTrue(give.contains("WishCarrier:true"),
                    "an unmarked carrier is indistinguishable from a plain vanilla item: " + give);
            // AND THE NEUTRALISATION. Leather horse armour contributes +3 Armor, which reads as
            // "When on Body: +3 Armor" under a name like Hot Coffee.
            assertTrue(give.contains("carrier_inert"),
                    "un-neutralised, his Hot Coffee is armour: " + give);
        } finally {
            VanillaCarrier.force(null);
        }
    }

    @Test
    @DisplayName("a carrier stacks like our own item did, and a one-of-a-kind stays one-of-a-kind")
    void aCarrierStacksLikeTheModItem() {
        VanillaCarrier.force(true);
        try {
            // THE HANDFUL. `thrown` wishes hand over several on purpose — the schema says so — and
            // wish_item stacks to 64 while leather horse armour stacks to 1. Without max_stack_size
            // a handful arrives as one slot each, which is a difference a child sees in his hotbar.
            assertTrue(asCallRendersIt(null).contains("minecraft:max_stack_size=64"),
                    "a carrier that is not told to stack fills a slot per item: "
                            + asCallRendersIt(null));

            // …AND NOT WHEN HE ASKED FOR THE OPPOSITE. A trophy asked to be one-of-a-kind must
            // stay one-of-a-kind; writing our default over his answer would be the same class of
            // bug as the icon one — our bookkeeping overruling something he chose.
            com.google.gson.JsonObject props = new com.google.gson.JsonObject();
            props.addProperty("max_stack_size", 1);
            String give = MakeItemTool.gives("plain", "trophy", 3, 1, "Its yours to keep.",
                    List.of(new MakeItemTool.Variant("CHAMPION", 0xFFD700, props)),
                    com.moddingfromamod.datapack.ItemRecipient.ME, null, null);
            assertTrue(give.contains("minecraft:max_stack_size=1"), give);
            assertFalse(give.contains("minecraft:max_stack_size=64"),
                    "his one-of-a-kind trophy was silently made stackable: " + give);
        } finally {
            VanillaCarrier.force(null);
        }
    }

    @Test
    @DisplayName("an item the CHILD named is left alone — no icon, no mark, no neutralisation")
    void anItemHeNamedIsNotOurs() {
        onEachRoute(() -> {
            String give = asCallRendersIt("minecraft:golden_axe");
            assertTrue(give.contains("minecraft:golden_axe["), give);
            assertFalse(give.contains("custom_model_data"),
                    "our icon library means nothing on a real golden axe, and writing it would "
                            + "have the tool report a colour he cannot see: " + give);
            assertFalse(give.contains("WishCarrier"),
                    "we did not substitute anything here — he asked for that item: " + give);
        });

        // AND THE CASE THAT BROKE, stated as its own claim: a deliberate wish for the carrier's
        // own vanilla item is his, not ours. Telling those apart is only possible from the RAW
        // base, which is the whole reason nothing may resolve before passing it on.
        VanillaCarrier.force(true);
        try {
            String give = asCallRendersIt(VanillaCarrier.ITEM_ID);
            assertFalse(give.contains("custom_model_data"),
                    "his 'magic horse armour' is a horse armour, and must keep its own artwork "
                            + "and its own armour value: " + give);
            assertFalse(give.contains("carrier_inert"), give);
            assertFalse(give.contains("WishCarrier"), give);
        } finally {
            VanillaCarrier.force(null);
        }
    }
}
