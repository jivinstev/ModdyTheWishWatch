package com.moddingfromamod.item;

import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.component.DataComponents;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.DyedItemColor;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Gate B — a thing a wish handed over stays findable.</b>
 *
 * <p>The bug: a wished-for BUTT CHEEK WATER SWORD existed only as the one stack it was given as. It
 * was in the child's inventory and nowhere else, while a wished-for <i>creature</i> gets a spawn egg
 * in the creative menu automatically. He went looking under "E" and it was not there.
 *
 * <p>What each test here is really guarding. A weaker version of each would pass without the feature
 * working at all, which is the only reason these are worth their length:
 *
 * <ul>
 *   <li>Recording <b>only named</b> things — record everything and the menu fills with two dozen
 *       blanks from the icon library, which is the state that made the feature useless in the first
 *       place.</li>
 *   <li>The rebuilt stack being the <b>same item</b>, not a lookalike. Storing icon + name + colour is
 *       the obvious design and it silently drops {@code WishOnUse}: the menu would then offer a sword
 *       that looks right and does nothing, which is a worse bug than the one being fixed because it
 *       looks like success.</li>
 *   <li>The tab handler <b>actually emitting</b> it, with a vanilla control — a creative-tab check with
 *       no control reports "0 items" just as readily when the check itself is wrong.</li>
 * </ul>
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class WishItemCatalogueGameTest {

    /** The real thing, as the live wish built it: named, gold-tinted, sword icon, runs a function. */
    private static ItemStack theSword() {
        ItemStack stack = WishItem.doing(ModItems.WISH_ITEM.get(), 17, "butt_cheek_water:give",
                0xFFD700);
        stack.set(DataComponents.CUSTOM_NAME,
                Component.literal("BUTT CHEEK WATER SWORD"));
        return stack;
    }

    @GameTest(template = "empty_test")
    public static void aNamedThingIsRememberedAndABlankIconIsNot(GameTestHelper helper) {
        WishItemCatalogue.inIsolationForTest(() -> {
            MinecraftServer server = helper.getLevel().getServer();

            helper.assertTrue(WishItemCatalogue.remember(server, theSword()),
                    Component.literal("a named wished thing must be remembered, or it stays a single losable stack"));
            helper.assertTrue(WishItemCatalogue.count() == 1,
                    Component.literal("expected exactly one remembered thing, got " + WishItemCatalogue.count()));

            // A blank out of the icon library has no name. Recording these is how the creative menu ends
            // up holding the icon vocabulary and none of the child's actual wishes.
            ItemStack blank = WishItem.of(ModItems.WISH_ITEM.get(), 17, "");
            helper.assertTrue(!WishItemCatalogue.remember(server, blank),
                    Component.literal("an unnamed blank from the icon library must NOT be remembered"));
            helper.assertTrue(WishItemCatalogue.count() == 1,
                    Component.literal("a blank icon changed the catalogue; the menu will fill with blanks"));

            helper.succeed();
        });
    }

    @GameTest(template = "empty_test")
    public static void oneMoreCopyOfTheSameThingIsNotANewThing(GameTestHelper helper) {
        WishItemCatalogue.inIsolationForTest(() -> {
            MinecraftServer server = helper.getLevel().getServer();

            WishItemCatalogue.remember(server, theSword());
            helper.assertTrue(!WishItemCatalogue.remember(server, theSword()),
                    Component.literal("the same thing given twice must not be remembered twice"));

            // A second give with a different count is still the same sword to a child.
            ItemStack five = theSword();
            five.setCount(5);
            helper.assertTrue(!WishItemCatalogue.remember(server, five),
                    Component.literal("a stack of the same thing must not read as a different thing"));
            helper.assertTrue(WishItemCatalogue.count() == 1,
                    Component.literal("duplicates got in: " + WishItemCatalogue.count() + " entries for one sword"));

            helper.succeed();
        });
    }

    /**
     * <b>Two things that look alike but DO different things are two things.</b>
     *
     * <p>The first cut keyed identity on name + icon alone, which was enough while every item was
     * hand-authored. {@code make_item} makes items cheap to produce from a fixed library of 24
     * icons, so a collision — same name, same icon, different effect — went from unlikely to merely
     * uncommon. Keying on appearance would silently drop the second from the creative menu, which is
     * the same disappearance this class exists to prevent, arrived at from the other direction.
     */
    @GameTest(template = "empty_test")
    public static void twoThingsThatLookAlikeButActDifferentlyAreBothKept(GameTestHelper helper) {
        WishItemCatalogue.inIsolationForTest(() -> {
            MinecraftServer server = helper.getLevel().getServer();

            // Same name, same icon, same colour. Different function.
            ItemStack a = WishItem.doing(ModItems.WISH_ITEM.get(), 17, "wand:lightning", 0xFFD700);
            a.set(DataComponents.CUSTOM_NAME, Component.literal("MAGIC WAND"));
            ItemStack b = WishItem.doing(ModItems.WISH_ITEM.get(), 17, "wand:fireball", 0xFFD700);
            b.set(DataComponents.CUSTOM_NAME, Component.literal("MAGIC WAND"));

            helper.assertTrue(WishItemCatalogue.remember(server, a), Component.literal("the first wand was not remembered"));
            helper.assertTrue(WishItemCatalogue.remember(server, b),
                    Component.literal("the second wand was treated as a duplicate of the first because they look alike — "
                            + "so the child can only ever find one of them in the creative menu"));
            helper.assertTrue(WishItemCatalogue.count() == 2,
                    Component.literal("expected both wands, got " + WishItemCatalogue.count()));

            // And both must come back as themselves, not as two copies of one.
            java.util.Set<String> functions = new java.util.HashSet<>();
            for (WishItemCatalogue.Entry e : WishItemCatalogue.all()) {
                functions.add(WishItem.onUse(WishItemCatalogue.stack(e, server.registryAccess())));
            }
            helper.assertTrue(functions.contains("wand:lightning") && functions.contains("wand:fireball"),
                    Component.literal("both wands are stored but they do not run their own functions: " + functions));

            helper.succeed();
        });
    }

    /**
     * <b>Refining a thing he already has must not leave the OLD one in his menu —.</b>
     *
     * <p>He reported this himself: a renamed trident he could not find when he searched for it. The key used to be name + icon + custom_data, and a
     * refinement changes all three — so <i>"make the wand blue"</i> minted a second key and he ended
     * up with one wand and two rows, the older row no longer doing what its name said.
     *
     * <p>{@link WishItemCatalogue#keyOf} fixes that by keying on the behaviour id, which is stable
     * for the item's life. <b>Nothing pinned it.</b> This test is that pin: recolour it, re-icon it,
     * rename it, hand over a stack of them — four things a real ladder does across five rungs — and
     * the count must still be one.
     *
     * <p>Verified red by keying on appearance again: the recolour alone takes the count to 2.
     */
    @GameTest(template = "empty_test")
    public static void refiningAThingUpdatesItsRowInsteadOfAddingASecond(GameTestHelper helper) {
        WishItemCatalogue.inIsolationForTest(() -> {
            MinecraftServer server = helper.getLevel().getServer();

            // Rung 1: he asks for it. One row.
            ItemStack made = WishItem.doing(ModItems.WISH_ITEM.get(), 17, "kaboom_dynamite:explode",
                    0xFFD700);
            made.set(DataComponents.CUSTOM_NAME, Component.literal("DYNAMITE"));
            helper.assertTrue(WishItemCatalogue.remember(server, made),
                    Component.literal("the thing he asked for was not remembered at all"));

            // Rungs 2-5, each an EDIT of that same thing: a new colour, a new icon, a new name, and a
            // whole stack of them. The behaviour id is what does not move, exactly as an edit through
            // `make_item start_from` leaves it.
            ItemStack recoloured = WishItem.doing(ModItems.WISH_ITEM.get(), 17,
                    "kaboom_dynamite:explode", 0xFF69B4);
            recoloured.set(DataComponents.CUSTOM_NAME, Component.literal("DYNAMITE"));
            WishItemCatalogue.remember(server, recoloured);

            ItemStack reIconed = WishItem.doing(ModItems.WISH_ITEM.get(), 9,
                    "kaboom_dynamite:explode", 0xFF69B4);
            reIconed.set(DataComponents.CUSTOM_NAME, Component.literal("DYNAMITE"));
            WishItemCatalogue.remember(server, reIconed);

            ItemStack renamed = WishItem.doing(ModItems.WISH_ITEM.get(), 9,
                    "kaboom_dynamite:explode", 0xFF69B4);
            renamed.set(DataComponents.CUSTOM_NAME, Component.literal("KABOOM DYNAMITE"));
            WishItemCatalogue.remember(server, renamed);

            ItemStack aPileOfThem = renamed.copy();
            aPileOfThem.setCount(16);
            WishItemCatalogue.remember(server, aPileOfThem);

            helper.assertTrue(WishItemCatalogue.count() == 1,
                    Component.literal("four edits of ONE thing left " + WishItemCatalogue.count() + " rows in his menu. "
                            + "That is #163: he searches for the thing he refined and finds the version "
                            + "before the refinement sitting next to it"));

            // …and the row he has must be the CURRENT one, not the first draft. A menu that keeps one
            // row but keeps the STALE one is the same disappointment wearing a different number.
            WishItemCatalogue.Entry only = WishItemCatalogue.all().iterator().next();
            helper.assertTrue(only.name().contains("KABOOM"),
                    Component.literal("the single row is the pre-refinement draft: '" + only.name() + "'"));

            helper.succeed();
        });
    }

    /**
     * <b>an earlier issue's ORIGINATING case: the same wand, refined into a different SHAPE.</b>
     *
     * <p>Every edit the test above makes leaves the behaviour where it was. A shape change does
     * not — it moves the behaviour from one tag to another:
     *
     * <pre>
     *   rung 3  "..."                               held    -&gt; WishOnUse
     *   rung 4  "can it explode when it lands too"  thrown  -&gt; WishThrowTag
     * </pre>
     *
     * <p>The agent is right to switch: <i>landing</i> is what a thrown item does. But the old key
     * was name + icon + custom_data, and all three moved, so the child ended up with one Mjolnir
     * and two rows — <b>identical names</b>, one of them the superseded held version. That is the
     * observation the issue was opened on, and nothing drove it.
     *
     * <p><b>Built from the REAL rendered give line</b>, parsed by the game's own {@code ItemParser},
     * rather than assembled here with {@code WishItem.doing}. A hand-built stack is a second
     * opinion about what {@code make_item} produces, and this test's whole subject is whether what
     * it produces carries something stable across a shape change.
     */
    @GameTest(template = "empty_test")
    public static void changingAThingsSHAPEDoesNotMintASecondRow(GameTestHelper helper) {
        WishItemCatalogue.inIsolationForTest(() -> {
            MinecraftServer server = helper.getLevel().getServer();

            ItemStack held = renderedThen(server, "held", "mjolnir_explode", "MJOLNIR");
            ItemStack thrown = renderedThen(server, "thrown", "mjolnir_explode", "MJOLNIR");
            if (held == null || thrown == null) {
                helper.fail(Component.literal("could not parse make_item's own give line, so this test measured nothing"));
                return;
            }

            // The two stacks really are different, or the assertion below passes by comparing a thing
            // with itself — the shape is supposed to change what the item DOES.
            helper.assertTrue(!ItemStack.matches(held, thrown),
                    Component.literal("held and thrown rendered identically, so the shape did not change the behaviour "
                            + "tag and this test is measuring nothing"));

            helper.assertTrue(WishItemCatalogue.remember(server, held), Component.literal("rung 3 was not remembered"));
            WishItemCatalogue.remember(server, thrown);

            helper.assertTrue(WishItemCatalogue.count() == 1,
                    Component.literal("one wand refined from held to thrown left " + WishItemCatalogue.count()
                            + " rows in his menu. That is #163 exactly: two identical names, and "
                            + "picking the older one gives him the version that no longer does what "
                            + "he last asked for"));

            // …and the row must be the CURRENT one. Keeping one row but keeping the STALE one is the
            // same disappointment wearing a different number.
            WishItemCatalogue.Entry only = WishItemCatalogue.all().iterator().next();
            ItemStack rebuilt = WishItemCatalogue.stack(only, server.registryAccess());
            helper.assertTrue(rebuilt != null && !WishItem.throwTag(rebuilt).isBlank(),
                    Component.literal("the surviving row is the pre-refinement HELD draft, so he picks it and it does "
                            + "nothing when he throws it"));

            helper.succeed();
        });
    }

    /** A stack exactly as make_item hands it over: rendered by the tool, parsed by the game. */
    private static ItemStack renderedThen(MinecraftServer server, String shape, String feature,
                                          String name) {
        String give = com.moddingfromamod.agent.tools.MakeItemTool.gives(
                shape, feature, 17, 1, "Throw it!",
                java.util.List.of(new com.moddingfromamod.agent.tools.MakeItemTool.Variant(
                        name, 0xFFD700)));
        // `gives` renders whole /give LINES; the item argument is the third word of the first one.
        String first = give.strip().split("\n")[0].strip();
        int at = first.indexOf(com.moddingfromamod.datapack.ItemBase.id(null));
        if (at < 0) {
            return null;
        }
        String arg = first.substring(at).strip();
        int lastSpace = arg.lastIndexOf(' ');
        if (lastSpace > 0) {
            arg = arg.substring(0, lastSpace).strip();
        }
        try {
            var parsed = new net.minecraft.commands.arguments.item.ItemParser(
                    server.registryAccess())
                    .parse(new com.mojang.brigadier.StringReader(arg));
            return new ItemStack(parsed.item(), 1, parsed.components());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * <b>The residual half, stated as a test so it cannot be forgotten.</b>
     *
     * <p>{@code keyOf} is honest that a keepsake with NO behaviour still falls back to
     * name + icon + data, so an icon change on one of those does mint a second row. That is a
     * deliberate, documented limit rather than an oversight — <i>"these are not the items
     * refinements act on"</i> — and this test records the boundary so a future reader learns it from
     * a green gate rather than by rediscovering it in the child's menu.
     */
    @GameTest(template = "empty_test")
    public static void aKeepsakeWithNoBehaviourStillDoublesAndThatIsKnown(GameTestHelper helper) {
        WishItemCatalogue.inIsolationForTest(() -> {
            MinecraftServer server = helper.getLevel().getServer();

            ItemStack keepsake = WishItem.of(ModItems.WISH_ITEM.get(), 17, "");
            keepsake.set(DataComponents.CUSTOM_NAME, Component.literal("MY LUCKY ROCK"));
            WishItemCatalogue.remember(server, keepsake);

            ItemStack reIconed = WishItem.of(ModItems.WISH_ITEM.get(), 9, "");
            reIconed.set(DataComponents.CUSTOM_NAME, Component.literal("MY LUCKY ROCK"));
            WishItemCatalogue.remember(server, reIconed);

            helper.assertTrue(WishItemCatalogue.count() == 2,
                    Component.literal("a behaviourless keepsake no longer doubles on an icon change — which is BETTER "
                            + "than this test expects. Delete this test and close the residual half of "
                            + "#163 rather than weakening the fix to keep it green"));

            helper.succeed();
        });
    }

    /**
     * The important one. A rebuilt entry must be the item he was given — not a picture of it.
     */
    @GameTest(template = "empty_test")
    public static void theRebuiltThingIsTheSameITEMNotALookalike(GameTestHelper helper) {
        WishItemCatalogue.inIsolationForTest(() -> {
            MinecraftServer server = helper.getLevel().getServer();

            ItemStack given = theSword();
            WishItemCatalogue.remember(server, given);

            List<WishItemCatalogue.Entry> all = WishItemCatalogue.all();
            helper.assertTrue(all.size() == 1, Component.literal("nothing was remembered, so there is nothing to rebuild"));
            ItemStack back = WishItemCatalogue.stack(all.get(0), server.registryAccess());

            helper.assertTrue(!back.isEmpty(), Component.literal("the remembered thing could not be rebuilt at all"));
            helper.assertTrue(back.getItem() == given.getItem(),
                    Component.literal("the rebuilt thing is a different item entirely"));
            helper.assertTrue(back.getHoverName().getString().equals("BUTT CHEEK WATER SWORD"),
                    Component.literal("the name is gone, so it is unsearchable by the only word he knows it by: got '"
                            + back.getHoverName().getString() + "'"));

            CustomModelData icon = back.get(DataComponents.CUSTOM_MODEL_DATA);
            helper.assertTrue(icon != null && com.moddingfromamod.compat.ModelData.id(icon) == 17,
                    Component.literal("the icon is gone, so it renders as the default artwork"));
            DyedItemColor dye = back.get(DataComponents.DYED_COLOR);
            helper.assertTrue(dye != null && (dye.rgb() & 0xFFFFFF) == 0xFFD700,
                    Component.literal("the colour is gone; a gold sword came back the wrong colour"));

            // The whole point: it still DOES what it did. This is the assertion that fails on the
            // icon-plus-name-plus-colour design, which is the design anyone would reach for first.
            helper.assertTrue(WishItem.onUse(back).equals("butt_cheek_water:give"),
                    Component.literal("the rebuilt thing does NOTHING when used — the menu would offer a sword that looks "
                            + "right and is inert, which reads as success and is worse than the bug"));

            helper.succeed();
        });
    }

    @GameTest(template = "empty_test")
    public static void aRememberedThingSurvivesAReload(GameTestHelper helper) {
        WishItemCatalogue.inIsolationForTest(() -> {
            MinecraftServer server = helper.getLevel().getServer();
            WishItemCatalogue.remember(server, theSword());

            // remember() persists; load() re-reads from the world folder, which is what a new session does.
            // Emptying MEMORY first is what makes that a real test rather than a tautology — without
            // it, count()==1 afterwards is equally consistent with load() doing nothing at all.
            WishItemCatalogue.emptyWithinIsolationForTest();
            helper.assertTrue(WishItemCatalogue.count() == 0, Component.literal("the catalogue did not actually clear"));
            WishItemCatalogue.load(server);

            helper.assertTrue(WishItemCatalogue.count() == 1,
                    Component.literal("the remembered thing did not survive a reload, so it is findable only until "
                            + "the child quits: " + WishItemCatalogue.count() + " entries"));
            ItemStack back = WishItemCatalogue.stack(
                    WishItemCatalogue.all().get(0), server.registryAccess());
            helper.assertTrue(WishItem.onUse(back).equals("butt_cheek_water:give"),
                    Component.literal("it survived the reload as a lookalike — the function it ran is gone"));

            helper.succeed();
        });
    }

    /**
     * A damaged entry must cost that entry and nothing else. The same rule the species and arena
     * stores follow: one bad record can never take the world down with it.
     */
    @GameTest(template = "empty_test")
    public static void aDamagedEntryIsSkippedRatherThanFatal(GameTestHelper helper) {
        WishItemCatalogue.Entry bad = WishItemCatalogue.Entry.fromJson(
                com.google.gson.JsonParser.parseString("{\"key\":\"x\"}").getAsJsonObject());
        helper.assertTrue(bad == null, Component.literal("a record with no stack must be rejected, not half-built"));

        WishItemCatalogue.Entry nonsense = new WishItemCatalogue.Entry(
                "k", "Nonsense", "{\"id\":\"minecraft:not_a_real_item\",\"count\":1}");
        ItemStack stack = WishItemCatalogue.stack(
                nonsense, helper.getLevel().getServer().registryAccess());
        helper.assertTrue(stack.isEmpty(),
                Component.literal("an unreadable entry must come back empty so the caller can skip it, never throw"));

        helper.succeed();
    }

    /**
     * <b>The tab handler emits it — with a vanilla control.</b>
     *
     * <p>The control is not padding. A creative-tab assertion that only counts our own entries reports
     * "not there" identically whether the feature is broken or the check is (tab contents are built
     * lazily, so a fresh world can legitimately have none). If the control is missing too, the check
     * is what is wrong.
     */
    @GameTest(template = "empty_test")
    public static void theWishedThingIsPutIntoTheCreativeMenu(GameTestHelper helper) {
        WishItemCatalogue.inIsolationForTest(() -> {
            MinecraftServer server = helper.getLevel().getServer();
            WishItemCatalogue.remember(server, theSword());

            CreativeModeTab tab = net.minecraft.core.registries.BuiltInRegistries.CREATIVE_MODE_TAB
                    .getValue(CreativeModeTabs.TOOLS_AND_UTILITIES);
            helper.assertTrue(tab != null, Component.literal("the Tools & Utilities tab does not exist"));

            tab.buildContents(new CreativeModeTab.ItemDisplayParameters(
                    server.getWorldData().enabledFeatures(), true, server.registryAccess()));

            boolean control = false;
            boolean found = false;
            for (ItemStack entry : tab.getSearchTabDisplayItems()) {
                if (entry.getItem() == Items.SHEARS) {
                    control = true;
                }
                if (entry.getItem() == ModItems.WISH_ITEM.get()
                        && entry.getHoverName().getString().equals("BUTT CHEEK WATER SWORD")) {
                    found = true;
                }
            }
            helper.assertTrue(control,
                    Component.literal("the vanilla control (shears) is missing from Tools & Utilities, so this check is "
                            + "not reading the tab correctly and proves nothing either way"));
            helper.assertTrue(found,
                    Component.literal("the wished thing is not in the creative menu under its own name — which is exactly "
                            + "the report: findable only in his own inventory"));

            helper.succeed();
        });
    }
}
