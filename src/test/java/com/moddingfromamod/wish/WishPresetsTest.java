package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Gate A for which wishes get Moddy's buttons. */
class WishPresetsTest {

    private static WishCatalog.Preset p(String label, String text) {
        return new WishCatalog.Preset(label, text);
    }

    /** A library row standing in for the starter set (the built-ins it used to be were deleted). */
    private static final List<WishCatalog.Preset> LIBRARY = List.of(
            p("Make it rain chickens!", "make it rain chickens"),
            p("A pet dragon!", "a pet dragon that follows me"),
            p("A lava castle!", "a huge castle with a lava moat"));

    @Test
    void whatHeJustMadeComesFirstAmongHisOwn() {
        // The whole point: the cool thing from five minutes ago should be one tap away.
        var learned = List.of(p("Floating Castle", "floating castle"),
                              p("Cheese Island", "cheese island"));
        var out = WishPresets.forScreen(null, learned);
        assertEquals("Floating Castle", out.get(0).label());
        assertEquals("Cheese Island", out.get(1).label());
    }

    @Test
    void thereIsNoShippedFloorBelowTheLearnedWishes() {
        // The built-ins that used to be pinned at the bottom are gone:
        // the list is the library and his own, and nothing else.
        var many = new ArrayList<WishCatalog.Preset>();
        for (int i = 0; i < 20; i++) {
            many.add(p("Learned " + i, "learned wish " + i));
        }
        var out = WishPresets.forScreen(LIBRARY, many);
        assertEquals(LIBRARY.size() + 20, out.size());
        assertEquals(LIBRARY, out.subList(0, LIBRARY.size()), "the library leads");
        assertEquals("Learned 19", out.get(out.size() - 1).label(), "his own end the list");
    }

    @Test
    void learnedHistoryIsCapped() {
        var many = new ArrayList<WishCatalog.Preset>();
        for (int i = 0; i < 150; i++) {
            many.add(p("Learned " + i, "learned wish " + i));
        }
        var out = WishPresets.forScreen(null, many);
        assertEquals(WishPresets.MAX_LEARNED, out.size(),
                "the history is capped at the hundred most recent");
        assertEquals("Learned 0", out.get(0).label(), "newest first");
    }

    @Test
    void aRelearnedWishDoesNotAppearTwice() {
        var learned = List.of(p("Rain Chickens", "make it rain chickens"),
                              p("Rain Chickens Again", "Make it rain chickens "));
        var out = WishPresets.forScreen(null, learned);
        assertEquals(1, out.size(), "de-duplicated on what the button DOES");
        assertEquals("Rain Chickens", out.get(0).label(), "and the newest one wins");
    }

    @Test
    void nothingLearnedYetJustShowsTheLibrary() {
        assertEquals(LIBRARY, WishPresets.forScreen(LIBRARY, List.of()));
        assertEquals(LIBRARY, WishPresets.forScreen(LIBRARY, null));
        assertEquals(List.of(), WishPresets.forScreen(null, null), "and nothing at all is nothing");
    }

    @Test
    void rubbishEntriesAreSkippedNotShown() {
        var learned = new ArrayList<WishCatalog.Preset>();
        learned.add(null);
        learned.add(p("Blank", "   "));
        learned.add(p("Good", "a good wish"));
        var out = WishPresets.forScreen(null, learned);
        assertEquals("Good", out.get(0).label());
        assertFalse(out.stream().anyMatch(x -> x == null || x.wishText().isBlank()));
    }

    /**
     * <b>The button a child was PROMISED.</b> A recipe written with {@code any} used to get none,
     * because {@code asPresets} demanded {@code all} — while Moddy said "next time just tap its
     * button". Verified-to-fail: with the old rule this method returns null for the first case.
     */
    @Test
    void aRecipeWrittenWithANYStillGetsAButton() {
        assertEquals("spikor", WishPresets.matchingText(
                List.of(), List.of("spikor", "kroak"), List.of()),
                "one `any` term is enough to reach the template, so it is enough for a button");
        assertEquals("dragon", WishPresets.matchingText(
                List.of("dragon"), List.of(), List.of()));
        assertEquals("dragon red", WishPresets.matchingText(
                List.of("dragon"), List.of("red", "crimson"), List.of()),
                "every `all` term, then ONE `any` term — exactly what matches() asks for");
    }

    @Test
    void aTextThatCouldNotReachItsOwnRecipeIsNotOffered() {
        assertNull(WishPresets.matchingText(List.of(), List.of(), List.of()),
                "a template with no terms matches every wish and is refused upstream anyway");
        assertNull(WishPresets.matchingText(List.of(), List.of("  ", ""), List.of()));
        assertNull(WishPresets.matchingText(
                List.of("big dragon"), List.of(), List.of("dragon")),
                "a `none` term inside the text would make the button miss its own recipe");
    }

    @Test
    void idsBecomeWordsAChildCanRead() {
        assertEquals("Floating Castle", WishPresets.labelFor("floating_castle"));
        assertEquals("Rain Cod", WishPresets.labelFor("rain_cod"));
        assertEquals("Wish", WishPresets.labelFor(""));
        assertEquals("Wish", WishPresets.labelFor(null));
        assertTrue(WishPresets.labelFor("a_very_long_template_id_that_goes_on_and_on").length() <= 28,
                "labels must fit on a button");
    }
    @Test
    void theLibraryLeadsTheScreenAndItsOrderIsKept() {
        var library = java.util.List.of(
                new WishCatalog.Preset("Cake Party", "cake party", "cake_party", "minecraft:cake"),
                new WishCatalog.Preset("Rain Chickens", "make it rain chickens", "lib_chickens",
                        "minecraft:chicken_spawn_egg"));
        var learned = java.util.List.of(
                new WishCatalog.Preset("Cheese Island", "cheese island", "cheese"),
                new WishCatalog.Preset("Rain Chickens", "make it rain chickens", "mine"));
        var out = WishPresets.forScreen(library, learned);

        assertEquals("cake_party", out.get(0).templateId(), "curated first, in file order");
        assertEquals("lib_chickens", out.get(1).templateId());
        assertEquals("cheese", out.get(2).templateId(), "then this world's own");
        assertEquals("minecraft:cake", out.get(0).icon(), "and it carries its icon to the panel");
        // A learned "make it rain chickens" would be the same BUTTON, so it appears once — as the
        // library's copy, which is the recipe and icon actually being served.
        assertEquals(1, out.stream()
                .filter(p -> p.wishText().equals("make it rain chickens")).count());
    }

    @Test
    void withNoLibraryTheScreenIsExactlyWhatItWasBefore() {
        var learned = java.util.List.of(
                new WishCatalog.Preset("Cheese Island", "cheese island", "cheese"));
        assertEquals(learned, WishPresets.forScreen(null, learned));
        assertEquals(learned, WishPresets.forScreen(java.util.List.of(), learned));
    }

    @Test
    void aBigLibraryCannotPushAChildsOwnWishesOffTheList() {
        // The cap is per SECTION. A hundred curated buttons plus a hundred learned ones is the
        // point of the scroll; a shared cap would silently hide everything this child made.
        var library = new java.util.ArrayList<WishCatalog.Preset>();
        for (int i = 0; i < WishPresets.MAX_LEARNED + 5; i++) {
            library.add(new WishCatalog.Preset("L" + i, "library wish " + i, "lib" + i));
        }
        var learned = java.util.List.of(
                new WishCatalog.Preset("Mine", "cheese island", "cheese"));
        var out = WishPresets.forScreen(library, learned);
        assertEquals(WishPresets.MAX_LEARNED,
                out.stream().filter(p -> p.templateId().startsWith("lib")).count());
        assertTrue(out.stream().anyMatch(p -> "cheese".equals(p.templateId())),
                "the child's own wish is still there");
    }

    // ── The panel is per child ─────────────────────────────────────────────────────────

    private static final String ALICE = "1f2e3d4c-0000-0000-0000-000000000001";
    private static final String BOB = "9a8b7c6d-0000-0000-0000-000000000002";

    private static WishCatalog.Preset taught(String id, String text, String owner) {
        return new WishCatalog.Preset(id, text, id, "", owner);
    }

    /**
     * 🔴 THE PER-CHILD GATE, and it is an identity rather than an absence: Alice sees HERS. A test that
     * only asserted "Alice does not see Bob's" would pass with the learned section dead entirely.
     */
    @Test
    void oneChildsTeachingIsNotOnAnotherChildsPanel() {
        var learned = java.util.List.of(
                taught("bobs_thing", "a rocket ship", BOB),
                taught("alices_thing", "a cheese island", ALICE));
        var hers = WishPresets.forScreen(ALICE, null, learned);
        var his = WishPresets.forScreen(BOB, null, learned);
        assertTrue(hers.stream().anyMatch(p -> "alices_thing".equals(p.templateId())),
                "Alice must see the wish SHE taught");
        assertTrue(his.stream().anyMatch(p -> "bobs_thing".equals(p.templateId())),
                "Bob must see the wish HE taught");
        assertFalse(hers.stream().anyMatch(p -> "bobs_thing".equals(p.templateId())),
                "…and not her brother's");
        assertFalse(his.stream().anyMatch(p -> "alices_thing".equals(p.templateId())),
                "…nor he hers");
    }

    /**
     * An UNOWNED learned wish belongs to everyone, not to nobody. Every learned entry on every disk
     * today is unowned, so hiding them would empty the panel on the first boot of this build — a
     * failure that looks exactly like the feature working.
     */
    @Test
    void aLearnedWishWithNoOwnerIsStillEverybodys() {
        var learned = java.util.List.of(taught("legacy", "a cheese island", ""));
        for (String viewer : java.util.List.of(ALICE, BOB, "")) {
            assertTrue(WishPresets.forScreen(viewer, null, learned)
                            .stream().anyMatch(p -> "legacy".equals(p.templateId())),
                    "an unstamped learned wish must be on every panel; viewer=" + viewer);
        }
    }

    /** The library is everybody's and is never filtered. */
    @Test
    void theLibraryIsNeverPerChild() {
        var library = java.util.List.of(taught("curated", "a curated wish", BOB));
        var out = WishPresets.forScreen(ALICE, library, java.util.List.of());
        assertTrue(out.stream().anyMatch(p -> "curated".equals(p.templateId())));
    }

    /** With no viewer the panel is byte-for-byte what it was before per-child ownership. */
    @Test
    void withNoViewerNothingIsFilteredAtAll() {
        var learned = java.util.List.of(
                taught("bobs_thing", "a rocket ship", BOB),
                taught("alices_thing", "a cheese island", ALICE));
        assertEquals(WishPresets.forScreen(null, learned),
                WishPresets.forScreen("", null, learned));
    }

    /**
     * MAX_LEARNED is per CHILD, because it is applied after the filter. A busy afternoon by one
     * child must not spend another child's budget — on a stock client the panel is ONE row, so the
     * loss would be silent and total.
     */
    @Test
    void theLearnedCapIsSpentPerChildRatherThanSharedBetweenThem() {
        var learned = new java.util.ArrayList<WishCatalog.Preset>();
        for (int i = 0; i < WishPresets.MAX_LEARNED; i++) {
            learned.add(taught("bob" + i, "bob wish " + i, BOB));
        }
        learned.add(taught("alice_only", "alice's own wish", ALICE));
        var hers = WishPresets.forScreen(ALICE, null, learned);
        assertTrue(hers.stream().anyMatch(p -> "alice_only".equals(p.templateId())),
                "Alice's one wish must survive a hundred of her brother's");
    }

    // ──: ONE pool — the starters, then his orphan taught wishes ────────────────────────

    private static List<WishCatalog.Preset> starters(int n) {
        List<WishCatalog.Preset> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new WishCatalog.Preset("S" + i, "starter wish " + i, "starter_" + i, ""));
        }
        return out;
    }

    private static boolean isMine(WishCatalog.Preset p) {
        return p.wishText().startsWith("my own wish");
    }

    @Test
    void aNewChildSeesTheStarterSetInLibraryOrder() {
        var shown = WishPresets.forPanel("", starters(10), List.of());
        assertEquals(10, shown.size());
        for (int i = 0; i < 10; i++) {
            assertEquals("starter wish " + i, shown.get(i).wishText(), "library order at " + i);
        }
    }

    /**
     * (every wish history is newest first): his ORPHAN taught wishes (no
     * creation on the panel carries them) LEAD the pool, newest first, and the starters follow in
     * library order, losing nothing. This was the other way round, which put the stock
     * wishes at the top of page one and his own out of sight.
     */
    @Test
    void hisOrphanTaughtWishesLeadThenTheStarters() {
        var learned = List.of(p("Mine A", "my own wish a"), p("Mine B", "my own wish b"),
                p("Mine C", "my own wish c"), p("Mine D", "my own wish d"));
        var shown = WishPresets.forPanel("", starters(10), learned);
        assertEquals(List.of("my own wish a", "my own wish b", "my own wish c", "my own wish d"),
                shown.subList(0, 4).stream().map(WishCatalog.Preset::wishText).toList(),
                "his orphans lead, newest first: " + shown);
        assertEquals(starters(10).stream().map(WishCatalog.Preset::wishText).toList(),
                shown.subList(4, shown.size()).stream().map(WishCatalog.Preset::wishText)
                        .toList(), "every starter follows, in library order");
    }

    /**
     * 🔴 THE GATE: a taught wish whose thing is on the panel is NOT a second button — the
     * creation's tile carries it. The orphan beside it (no creation) is still shown, and the
     * starters keep their order. Both polarities, or a forPanel that dropped every taught wish
     * would pass.
     */
    @Test
    void aTaughtWishWithItsCreationOnThePanelIsNotASecondButton() {
        var learned = List.of(
                new WishCatalog.Preset("Fire Dragon", "fire dragon", "fire_dragon"),
                new WishCatalog.Preset("Chicken Rain", "chicken rain", "chicken_rain"));
        var shown = WishPresets.forPanel("", starters(9), learned,
                java.util.Set.of("fire_dragon"));
        var ids = shown.stream().map(WishCatalog.Preset::templateId).toList();
        assertFalse(ids.contains("fire_dragon"), "the dragon is on the panel twice: " + ids);
        assertTrue(ids.contains("chicken_rain"), "an orphan taught wish must still be shown");
        assertEquals("chicken_rain", ids.get(0), "his orphan comes before the starters (#1352)");
        assertEquals(starters(9).stream().map(WishCatalog.Preset::templateId).toList(),
                ids.subList(1, 10), "the starters keep their order");
    }

    @Test
    void hisOrphanShareIsHisNewestFewAfterEveryStarter() {
        var learned = new ArrayList<WishCatalog.Preset>();
        for (int i = 0; i < 20; i++) {
            learned.add(p("Mine " + i, "my own wish " + i));
        }
        var library = starters(12);
        var shown = WishPresets.forPanel("", library, learned);
        var mine = shown.stream().filter(WishPresetsTest::isMine).toList();
        assertEquals(WishPresets.OWN_SLOTS, mine.size());
        assertEquals("my own wish 0", mine.get(0).wishText(), "newest first");
        assertEquals(12 + WishPresets.OWN_SLOTS, shown.size());
    }

    /** A joined wish does not use up an orphan slot: the cap counts what is SHOWN. */
    @Test
    void aJoinedWishDoesNotSpendAnOrphanSlot() {
        var learned = new ArrayList<WishCatalog.Preset>();
        for (int i = 0; i < WishPresets.OWN_SLOTS + 3; i++) {
            learned.add(new WishCatalog.Preset("Mine " + i, "my own wish " + i, "mine_" + i));
        }
        var shown = WishPresets.forPanel("", List.of(), learned,
                java.util.Set.of("mine_0", "mine_1", "mine_2"));
        assertEquals(WishPresets.OWN_SLOTS, shown.size());
        assertEquals("mine_3", shown.get(0).templateId());
    }

    @Test
    void theLibrarysCopyWinsAndABrothersWishIsNotHis() {
        var library = List.of(new WishCatalog.Preset("Castle", "a castle", "starter_castle", "",
                ""));
        var learned = List.of(taught("dup", "a castle", ""), taught("bobs", "a rocket", BOB),
                taught("alices", "a cheese island", ALICE));
        var hers = WishPresets.forPanel(ALICE, library, learned);
        assertEquals(List.of("alices", "starter_castle"),
                hers.stream().map(WishCatalog.Preset::templateId).toList());
    }
}
