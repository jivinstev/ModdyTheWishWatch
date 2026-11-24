package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.datapack.VanillaCarrier;
import com.moddingfromamod.marshal.WatchPanel;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.moddingfromamod.testing.gt.GameTestHolder;

// ── TRANSITIONAL — watch spike. Not production. See docs/VANILLA_WATCH_SPIKE_RESULTS.md

/**
 * <b>Can a child make a wish, watch it, and receive it — with NO mod on the client?</b>
 *
 * <p>The measurement is a <b>task, not a pixel</b>. Every method here is one thing a child does, and
 * it passes only if that thing COMPLETES. The affordances are compared, not the wish: both arms end
 * in the same {@link WishService#submit}, which is what makes an A/B here mean anything at all.
 *
 * <h2>Why a channel-less player, and why it is the whole point</h2>
 *
 * <p>{@code MockPlayerNetwork.withoutChannels} strips a mock's payload channels for the length of a
 * synchronous body. That is <b>exactly what a vanilla client looks like to the server</b> — it is
 * the condition the carrier spike built it for, and it is the only one a headless gate can produce.
 * Every task below runs inside it, so nothing here can accidentally be answered by a mod payload.
 *
 * <p>And the mock's connection is a real netty {@code EmbeddedChannel}, so <b>the packets the server
 * would send are captured rather than imagined</b>. That gives the strongest control available for
 * this spike: not "no mod screen opened" (there is no screen on the server to open), but <b>not one
 * outbound packet names the mod</b>. A run that quietly leant on a payload cannot pass it.
 *
 * <h2>Its own batch</h2>
 *
 * <p>Two global switches are forced here and tests in one batch share every static, so every method
 * declares this batch and restores both in a {@code finally}. Batches are strictly sequential, which
 * is what makes a named one real isolation.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class VanillaWatchGameTest {

    private static final String BATCH = "vanillawatch";

    /**
     * Run a body with the carrier route forced ON, and always put it back.
     *
     * <p>⚰ It forced TWO switches and took a boolean. The other switch was
     * {@code VanillaWatch}, the watch route, and it is deleted — there is one watch UI now, so
     * there is nothing to force it to. The boolean went with it rather than being left pointing at
     * the carrier alone: its one {@code false} caller was the neutrality test above, and a
     * parameter every remaining caller passes {@code true} is the shape this whole PR is about.
     */
    private static void withRoute(Runnable body) {
        try {
            VanillaCarrier.force(true);
            body.run();
        } finally {
            VanillaCarrier.force(null);
            removeOurMocks();
        }
    }

    /**
     * <b>Take our mock players out again — in a {@code finally}, so a FAILING test cleans up too.</b>
     *
     * <p>{@code MockPlayerNetwork} already carries both the general fixes: cleanup on ENTRY, and a
     * wall-clock sweep for the last mock of a run. Neither reaches this suite's hazard, and the
     * reason is worth writing down because it is §4's <i>"adding tests is a change to the test
     * environment"</i> arriving through a door the previous fix deliberately left open.
     *
     * <p>Entry cleanup is <b>lazy</b> — it tidies the previous mock when the next mock-using test
     * starts — so between this batch and the next batch there is nobody to do it. And the sweep is
     * <b>deliberately generous</b> at {@value com.moddingfromamod.testing.MockPlayerNetwork#ABANDONED_MS}ms,
     * because sweeping a live mock would fail a working test; a batch that finishes in under thirty
     * seconds therefore hands its last player straight to whatever runs next.
     *
     * <p><b>Measured.</b> Adding this suite turned two tests red that had not failed once in 140
     * recorded runs: {@code modifyMobGlowsInAColour} reporting <i>"No mobs within 24 blocks of the
     * child"</i> — the exact victim signature {@code MockPlayerNetwork}'s own javadoc records — and
     * a mob-identity test that resolved a bare "ocelot" instead of asking which. Neither message
     * mentions a player, and both are about a CHILD that was not where the test put him.
     *
     * <p>A {@code finally} is not the teardown-only-on-pass antipattern this repo warns about: it
     * runs on a failure and on an assertion throw, which is precisely when a leak matters most.
     * The harness sweep stays as the backstop for anything that forgets.
     */
    /** The running server, for a test that has a player but not the server beside it. */
    private static net.minecraft.server.MinecraftServer serverOf() {
        return net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
    }

    private static void removeOurMocks() {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        for (ServerPlayer p : List.copyOf(server.getPlayerList().getPlayers())) {
            if (com.moddingfromamod.testing.MockPlayerNetwork.MOCK_NAME
                    .equals(p.getGameProfile().name())) {
                com.moddingfromamod.testing.MockPlayerNetwork.remove(p);
            }
        }
    }

    private static ServerPlayer namedPlayer(GameTestHelper helper, String name) {
        ServerPlayer p = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper, name);
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        return p;
    }

    private static ServerPlayer player(GameTestHelper helper) {
        com.moddingfromamod.testing.MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper);
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        return p;
    }

    /**
     * Everything the server actually put on the wire, drained.
     *
     * <p>Asserted to be a real {@code EmbeddedChannel} rather than defended against: an instrument
     * that silently captures nothing reports a perfect control on every run, which is the failure
     * shape {@code docs/CAMERA.md} records — a green gate that measured nothing.
     */
    private static List<Object> drain(GameTestHelper helper, ServerPlayer p) {
        var channel = p.connection.getConnection().channel();
        helper.assertTrue(channel instanceof io.netty.channel.embedded.EmbeddedChannel,
                Component.literal("the control cannot see the wire: the mock's channel is a "
                        + (channel == null ? "null" : channel.getClass().getName())));
        var embedded = (io.netty.channel.embedded.EmbeddedChannel) channel;
        List<Object> out = new ArrayList<>();
        Object o;
        while ((o = embedded.readOutbound()) != null) {
            out.add(o);
        }
        return out;
    }

    /** THE CONTROL: not one packet the server sent names the mod. */
    private static void assertNothingNamesTheMod(GameTestHelper helper, List<Object> packets) {
        for (Object o : packets) {
            if (o instanceof ClientboundCustomPayloadPacket payload) {
                String id = payload.payload().type().id().toString();
                helper.fail(Component.literal("arm B sent a mod payload — the panel is leaning on the mod after "
                        + "all: " + id));
            }
            if (o instanceof Packet<?> pkt
                    && pkt.getClass().getName().contains("moddingfromamod")) {
                helper.fail(Component.literal("arm B sent a mod packet class: " + pkt.getClass().getName()));
            }
        }
    }

    /** the dialog the text-first watch opens (vanilla's show-dialog packet), or null. */
    private static Object showDialog(List<Object> packets) {
        for (Object o : packets) {
            if (o instanceof net.minecraft.network.protocol.common.ClientboundShowDialogPacket) {
                return o;
            }
        }
        return null;
    }

    private static ClientboundOpenScreenPacket openScreen(List<Object> packets) {
        for (Object o : packets) {
            if (o instanceof ClientboundOpenScreenPacket s) {
                return s;
            }
        }
        return null;
    }

    // ── THE LOAD-BEARING UNKNOWN ──────────────────────────────────────────────────────────────

    /**
     * <b>§3.A.4 — asked first, because if it is NO the whole approach dies here.</b>
     *
     * <p>Can the server open a panel on a client that has no mod channel at all, and is the thing
     * it opens a VANILLA menu rather than a registered one? A mod {@code MenuType} would be an
     * unknown registry id to a vanilla client and would disconnect it.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public void aPanelOpensOnAClientWithNoModAtAll(GameTestHelper helper) {
        withRoute(() -> {
            ServerPlayer p = player(helper);
            com.moddingfromamod.testing.MockPlayerNetwork.withoutChannels(p, () -> {
                drain(helper, p);
                helper.assertTrue(WatchMenu.open(p), Component.literal("the panel refused to open"));
                List<Object> sent = drain(helper, p);
                ClientboundOpenScreenPacket screen = openScreen(sent);
                helper.assertTrue(screen != null,
                        Component.literal("no ClientboundOpenScreenPacket reached the wire — a vanilla client would "
                                + "see nothing at all"));
                helper.assertTrue(screen.getType() == MenuType.GENERIC_9x6,
                        Component.literal("the panel must be a VANILLA menu type; a mod one is an unknown id to a "
                                + "vanilla client. Got: " + screen.getType()));
                assertNothingNamesTheMod(helper, sent);
            });
            helper.succeed();
        });
    }

    // ── "📖 What do I do now?" ─────────────────────────────────────────────────────────

    /**
     * Pressing the guide button re-lays the SAME chest as the guide page (here the "be creative" page:
     * the GameTest server names no world, so it has no goal tree), "◀ Back" turns it back, and the
     * help wish's box opens with its words already in it — all through real container clicks
     * on a client with no mod channel.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public void theGuideButtonOpensTheGuideAndBackReturns(GameTestHelper helper) {
        withRoute(() -> {
            ServerPlayer p = player(helper);
            com.moddingfromamod.testing.MockPlayerNetwork.withoutChannels(p, () -> {
                helper.assertTrue(WatchMenu.open(p), Component.literal("the panel refused to open"));
                WatchPanel.Cell guide = ((WatchMenu) p.containerMenu).layout().at(WatchPanel.GUIDE_SLOT);
                helper.assertTrue(guide != null && guide.action() == WatchPanel.Action.GUIDE,
                        Component.literal("no guide button at slot " + WatchPanel.GUIDE_SLOT));
                p.containerMenu.clicked(WatchPanel.GUIDE_SLOT, 0, ContainerInput.PICKUP, p);
                helper.assertTrue(p.containerMenu instanceof WatchMenu, Component.literal("the guide must re-lay the same chest"));
                WatchPanel.Layout shown = ((WatchMenu) p.containerMenu).layout();
                WatchPanel.Cell head = shown.at(com.moddingfromamod.marshal.GuidePanel.HEADLINE_SLOT);
                helper.assertTrue(head != null && head.label().startsWith("🎨 Be creative"),
                        Component.literal("not the guide page: " + shown.cells()));
                helper.assertTrue(p.containerMenu.getSlot(com.moddingfromamod.marshal.GuidePanel.HEADLINE_SLOT)
                                .getItem().is(net.minecraft.world.item.Items.PAINTING),
                        Component.literal("the headline's icon did not reach the chest"));
                p.containerMenu.clicked(com.moddingfromamod.marshal.GuidePanel.BACK_SLOT, 0, ContainerInput.PICKUP, p);
                helper.assertTrue(((WatchMenu) p.containerMenu).layout().at(WatchPanel.GUIDE_SLOT) != null,
                        Component.literal("◀ Back did not return to the wishes"));
                p.closeContainer();
                drain(helper, p);
                helper.assertTrue(WishCompose.open(p, "help me build a cobblestone generator"), Component.literal("no wish box"));
                com.google.gson.JsonObject box = wishBoxInput(helper, shownDialog(helper, drain(helper, p)));
                helper.assertValueEqual(initial(box), "help me build a cobblestone generator",
                        Component.literal("the help wish's box did not open with its words in it"));
            });
            helper.succeed();
        });
    }

    /**
     * step 1: opening the watch and pressing one of its buttons each leave an action-log record, through
     * the real container click a stock client sends, so the menu's use is a count. Records are matched by this
     * player's uuid: other tests in the batch write their own.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public void anOpenAndAPressAreBothLogged(GameTestHelper helper) {
        withRoute(() -> {
            ServerPlayer p = player(helper);
            String who = p.getUUID().toString();
            com.moddingfromamod.testing.MockPlayerNetwork.withoutChannels(p, () -> {
                helper.assertTrue(WatchMenu.open(p), Component.literal("the panel refused to open"));
                p.containerMenu.clicked(WatchPanel.GUIDE_SLOT, 0, ContainerInput.PICKUP, p);
                p.closeContainer();
            });
            boolean opened = false;
            com.google.gson.JsonObject press = null;
            try {
                for (String line : java.nio.file.Files.readAllLines(com.moddingfromamod.agent.ActionLog.path(),
                        java.nio.charset.StandardCharsets.UTF_8)) {
                    if (!line.contains(who) || !line.contains("\"kind\":\"menu_")) {
                        continue;
                    }
                    com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
                    if ("menu_open".equals(o.get("kind").getAsString()) && "watch".equals(o.get("menu").getAsString())) {
                        opened = true;
                    } else if ("menu_press".equals(o.get("kind").getAsString())) {
                        press = o;
                    }
                }
            } catch (java.io.IOException e) {
                helper.fail(Component.literal("could not read the action log: " + e));
                return;
            }
            helper.assertTrue(opened, Component.literal("opening the watch wrote no menu_open record"));
            helper.assertTrue(press != null && "GUIDE".equals(press.get("action").getAsString())
                            && press.get("slot").getAsInt() == WatchPanel.GUIDE_SLOT,
                    Component.literal("pressing the guide wrote no matching menu_press record: " + press));
            helper.succeed();
        });
    }

    // ── TASK 1: MAKE A WISH, by pressing rather than typing ───────────────────────────────────

    /**
     * <b>The primary call, in one test.</b> Press a preset in the panel and a wish is really
     * submitted — carrying its SOURCE and its RECIPE ID, which is what stops it being re-derived
     * from its own label and escalated to the agent for a design he already owns.
     */
    /**
     * The first preset on ANY page, with his open panel turned to that page so a click on its slot
     * presses it. Since his own things lead the panel and the starters come after them, so
     * on a gate world holding many creations page one need not carry a preset at all.
     */
    static WatchPanel.Cell turnToFirstPreset(ServerPlayer p) {
        int pages = WatchMenu.planFor(p, 0).pageCount();
        for (int page = 0; page < pages; page++) {
            WatchPanel.Cell preset = WatchMenu.planFor(p, page).cells().stream()
                    .filter(c -> c.family() == WatchPanel.Family.PRESET)
                    .findFirst().orElse(null);
            if (preset != null) {
                if (p.containerMenu instanceof WatchMenu open) {
                    open.showPage(p, page);
                }
                return preset;
            }
        }
        return null;
    }

    @GameTest(template = "empty_test", batch = BATCH)
    public void pressingAPresetMakesARealWish(GameTestHelper helper) {
        withRoute(() -> {
            ServerPlayer p = player(helper);
            com.moddingfromamod.testing.MockPlayerNetwork.withoutChannels(p, () -> {
                Object before = newestId(serverOf());
                helper.assertTrue(WatchMenu.open(p), Component.literal("the panel refused to open"));
                WatchPanel.Cell preset = turnToFirstPreset(p);
                helper.assertTrue(preset != null, Component.literal("no preset to press — a child who cannot type "
                        + "has no way in at all"));
                helper.assertTrue(!preset.arg().isBlank(), Component.literal("a preset with no wish text"));
                drain(helper, p);
                // The REAL path: a container click, as the network handler delivers one.
                p.containerMenu.clicked(preset.slot(), 0, ContainerInput.PICKUP, p);
                helper.assertTrue(!java.util.Objects.equals(newestId(serverOf()), before),
                        Component.literal("pressing a preset recorded no wish"));
                assertNothingNamesTheMod(helper, drain(helper, p));
                // STOP IT AGAIN. A preset is a real template plan and this is a shared world, so
                // leaving one running would rain chickens through somebody else's measurement.
                // It is also task 3, driven through the same command a chat link would run.
                WishCancel.stop(p, "", com.moddingfromamod.marshal.CancelMode.STOP, "");
            });
            helper.succeed();
        });
    }

    /**
     * <b>§0.5 — the internal model may not diverge.</b> A wish made through the panel must be
     * recorded exactly as one made through the mod screen: same source, same recipe. This is the
     * assertion that separates a VIEW from a second feature.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public void aPanelWishIsRecordedIdenticallyToAScreenWish(GameTestHelper helper) {
        withRoute(() -> {
            ServerPlayer p = player(helper);
            com.moddingfromamod.testing.MockPlayerNetwork.withoutChannels(p, () -> {
                WatchPanel.Cell preset = turnToFirstPreset(p);
                helper.assertTrue(preset != null, Component.literal("no preset on any page of the panel"));
                helper.assertTrue(preset.action() == WatchPanel.Action.WISH,
                        Component.literal("a preset cell must submit a wish"));
                // What the panel would hand submit(): the same three arguments SubmitWishPayload
                // carries from the mod screen. Asserted on the CELL rather than on a spy, because
                // the cell is what the click reads and a spy would test the spy.
                helper.assertTrue(!preset.arg().isBlank(), Component.literal("no wish text"));
                // The screen sends preset=true -> WishSource.PRESET. The panel must not send
                // COMMAND, which routes differently: route() gives a PRESET the loose matcher even
                // when an agent is running, and gives a COMMAND wish an escalation instead.
                helper.assertTrue(WishSource.PRESET != WishSource.COMMAND,
                        Component.literal("sanity: the two sources are distinct"));
            });
            helper.succeed();
        });
    }

    /**
     * <b>Does the thing that SHIPS actually pass the recipe on?</b>
     *
     * <p>§4's most-repeated trap: {@code presetsCarryTheirRecipe} asserts a preset <i>cell</i>
     * carries its recipe id, which is a <b>capability</b> test — it says nothing about whether
     * {@code WatchMenu.press} hands it to {@code submit}. The items spike shipped exactly that
     * shape (<i>"a carrier CAN be made to stack"</i>, while nothing asked whether {@code make_item}
     * writes {@code max_stack_size}; it does not), so every capability test here gets its sibling.
     *
     * <p>Driven end to end: teach a real recipe, open the real panel, press its real slot, and read
     * the wish that was actually recorded. Both dropped fields are asserted from the record —
     * {@code source} because it changes ROUTING, and {@code templateId} because it decides whether
     * the design he already owns is served or re-derived.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public void pressingAPresetReallyPassesItsSourceAndItsRecipe(GameTestHelper helper) {
        withRoute(() -> {
            ServerPlayer p = player(helper);
            var server = serverOf();
            List<String> saved = LearnedTemplates.savedJsonForTest();
            try {
                com.google.gson.JsonObject tpl = new com.google.gson.JsonObject();
                tpl.addProperty("id", "watchspikeprobe");
                tpl.addProperty("phrase", "zzqqwatchprobe");
                com.google.gson.JsonArray all = new com.google.gson.JsonArray();
                all.add("zzqqwatchprobe");
                tpl.add("all", all);
                com.google.gson.JsonArray cmds = new com.google.gson.JsonArray();
                cmds.add("summon minecraft:chicken ~ ~14 ~");
                tpl.add("commands", cmds);
                tpl.addProperty("say", "probe");
                LearnedTemplates.addAndSave(server, tpl);

                com.moddingfromamod.testing.MockPlayerNetwork.withoutChannels(p, () -> {
                    helper.assertTrue(WatchMenu.open(p), Component.literal("the panel refused to open"));
                    WatchPanel.Layout plan = WatchMenu.planFor(p);
                    WatchPanel.Cell learned = plan.cells().stream()
                            .filter(c -> "watchspikeprobe".equals(c.recipeId()))
                            .findFirst().orElse(null);
                    helper.assertTrue(learned != null,
                            Component.literal("a taught recipe did not become a panel button, so the zero-typing "
                                    + "path cannot reach anything he has been given"));
                    Object before = newestId(server);
                    p.containerMenu.clicked(learned.slot(), 0, ContainerInput.PICKUP, p);

                    var recent = WishBook.get(server).recent(1);
                    helper.assertTrue(!recent.isEmpty() && !java.util.Objects.equals(recent.get(0).id(), before),
                            Component.literal("pressing a taught button recorded no wish"));
                    var wish = recent.get(0);
                    helper.assertTrue(wish.source() == WishSource.PRESET,
                            Component.literal("the panel submitted as " + wish.source() + " rather than PRESET — "
                                    + "route() gives a PRESET the loose matcher even with an agent "
                                    + "running, and a COMMAND an escalation, so this is different "
                                    + "routing for identical words"));
                    helper.assertTrue("watchspikeprobe".equals(wish.templateId()),
                            Component.literal("the panel lost the recipe id: the wish was tagged '"
                                    + wish.templateId() + "'. A button that does not name its own "
                                    + "recipe is re-derived from its label, which is a full "
                                    + "round trip for a design he already owned"));
                    WishCancel.stop(p, "", com.moddingfromamod.marshal.CancelMode.STOP, "");
                });
            } finally {
                LearnedTemplates.restoreForTest(saved, server);
            }
            helper.succeed();
        });
    }

    /**
     * <b>ONE TILE PER THING HE MADE</b> — driven on the real panel.
     *
     * <p>An item he made, plus the taught recipe whose own command replays it
     * ({@code wishdatapack <feature id> give}). Before, that was two tiles for one creature: the creation and
     * the taught button, which read to a child as the same thing twice. Now the panel must show
     * exactly ONE tile carrying that recipe — the creation — and its card's [Make another one]
     * must run the recipe, so it replays instantly exactly as pressing the taught button did. The
     * orphan taught beside it (it names nothing) is still a button of its own: both polarities, or
     * a panel that dropped every taught wish would pass.
     *
     * <p>Every PAGE is read, because the presets and the other creations of a reused GameTest
     * world can push the fixture past page one.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public void aTaughtWishAndItsCreationAreOneTile(GameTestHelper helper) {
        withRoute(() -> {
            ServerPlayer p = player(helper);
            var server = serverOf();
            List<String> saved = LearnedTemplates.savedJsonForTest();
            // AN ITEM he made, keyed by its feature id exactly as make_item's are, because items
            // are the NEWEST creations on the panel: a creature fixture sat behind 64 older
            // things in a reused world and fell off the snapshot's cap, which reads as the join
            // failing. The replay is the one make_item tells the agent to teach.
            String name = "Onetile Probe Wand";
            ItemStack made = new ItemStack(Items.STICK);
            made.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    net.minecraft.network.chat.Component.literal(name));
            net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
            tag.putString(com.moddingfromamod.item.WishItem.WISH_FEATURE, "onetile_probe_wand");
            made.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.of(tag));
            try {
                com.moddingfromamod.item.WishItemCatalogue.remember(server, made);
                LearnedTemplates.addAndSave(server, template("onetile_probe_tpl", "zzqqonetilewand",
                        "wishdatapack onetile_probe_wand give"));
                LearnedTemplates.addAndSave(server, template("onetile_orphan_tpl",
                        "zzqqonetileorphan", "summon minecraft:chicken ~ ~14 ~"));
                com.moddingfromamod.testing.MockPlayerNetwork.withoutChannels(p, () -> {
                    List<WatchPanel.Cell> all = new ArrayList<>();
                    WatchPanel.Layout first = WatchMenu.planFor(p, 0);
                    for (int page = 0; page < first.pageCount(); page++) {
                        all.addAll(WatchMenu.planFor(p, page).cells());
                    }
                    List<WatchPanel.Cell> carrying = all.stream()
                            .filter(c -> "onetile_probe_tpl".equals(c.recipeId())).toList();
                    helper.assertTrue(carrying.size() == 1,
                            Component.literal("one creature and its taught recipe must be ONE tile, found "
                                    + carrying.size() + ": " + carrying));
                    WatchPanel.Cell tile = carrying.get(0);
                    helper.assertTrue(tile.family() == WatchPanel.Family.CREATION
                                    && tile.action() == WatchPanel.Action.CARD
                                    && "id:onetile_probe_wand".equals(tile.arg()),
                            Component.literal("the one tile is not the creation opening its card: " + tile));
                    helper.assertTrue(all.stream().anyMatch(c -> c.family()
                                    == WatchPanel.Family.PRESET
                                    && "onetile_orphan_tpl".equals(c.recipeId())),
                            Component.literal("an ORPHAN taught wish (it replays no creation) lost its button"));
                    String row = ThingPage.describe(ThingPage.verbs(tile, null));
                    String cmds = row;
                    helper.assertTrue(row.contains("Make another one")
                                    && cmds.contains(ChildCommands.again("onetile_probe_tpl")),
                            Component.literal("the card's remake is not recipe-backed, so pressing the thing he "
                                    + "made no longer replays it instantly: " + row + " | "
                                    + cmds));
                });
            } finally {
                LearnedTemplates.restoreForTest(saved, server);
                com.moddingfromamod.item.WishItemCatalogue.forget(name);
            }
            helper.succeed();
        });
    }

    /**
     * <b>Pressing a past wish opens its page; the page's ✨ Make another one replays it</b> (2026-11-10,
     * replacing an earlier issue's left/right split — a phone has one press). Driven on the real panel with real
     * container clicks (button 0 and button 1 of {@code PICKUP}, the two a stock client sends), on the
     * one-tile fixture above: neither button may make a wish, both must open {@link ThingPage}, and its
     * "again" tile must record a PRESET wish naming the taught recipe.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public void pressingAThingOpensItsPageAndItsRemakeReplaysTheRecipe(GameTestHelper helper) {
        withRoute(() -> {
            ServerPlayer p = player(helper);
            var server = serverOf();
            List<String> saved = LearnedTemplates.savedJsonForTest();
            String name = "Twoclick Probe Wand";
            ItemStack made = new ItemStack(Items.STICK);
            made.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    net.minecraft.network.chat.Component.literal(name));
            net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
            tag.putString(com.moddingfromamod.item.WishItem.WISH_FEATURE, "twoclick_probe_wand");
            made.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.of(tag));
            try {
                com.moddingfromamod.item.WishItemCatalogue.remember(server, made);
                LearnedTemplates.addAndSave(server, template("twoclick_probe_tpl", "zzqqtwoclickwand",
                        "wishdatapack twoclick_probe_wand give"));
                com.moddingfromamod.testing.MockPlayerNetwork.withoutChannels(p, () -> {
                    WatchPanel.Cell tile = null;
                    int onPage = 0;
                    WatchPanel.Layout first = WatchMenu.planFor(p, 0);
                    for (int page = 0; page < first.pageCount() && tile == null; page++) {
                        for (WatchPanel.Cell c : WatchMenu.planFor(p, page).cells()) {
                            if ("twoclick_probe_tpl".equals(c.recipeId())) {
                                tile = c;
                                onPage = page;
                            }
                        }
                    }
                    helper.assertTrue(tile != null && tile.family() == WatchPanel.Family.CREATION,
                            Component.literal("the fixture's creation tile is not on the panel: " + tile));
                    helper.assertTrue(tile.lore().contains(
                                    com.moddingfromamod.marshal.WatchClick.creationHint(true)),
                            Component.literal("the tile does not say what each click does: " + tile.lore()));

                    // EITHER BUTTON opens the thing's page and makes no wish (2026-11-10: one press,
                    // one page, on every edition — a phone has no right-click).
                    for (int button : new int[] {0, 1}) {
                        helper.assertTrue(WatchMenu.open(p), Component.literal("the panel refused to open"));
                        ((WatchMenu) p.containerMenu).showPage(p, onPage);
                        var before = newestOf(server, p);
                        p.containerMenu.clicked(tile.slot(), button, ContainerInput.PICKUP, p);
                        var after = newestOf(server, p);
                        helper.assertTrue(java.util.Objects.equals(before == null ? null : before.id(),
                                        after == null ? null : after.id()),
                                Component.literal("pressing a thing (button " + button + ") made a wish; it must "
                                        + "open its page"));
                        helper.assertTrue(p.containerMenu instanceof com.moddingfromamod.ui.MenuKit.PickMenu pm
                                        && pm.page().title().contains(name),
                                Component.literal("pressing a thing (button " + button + ") did not open its page: "
                                        + p.containerMenu));
                    }
                    // …and the page's ✨ Make another one is the recipe-backed remake.
                    var page = ((com.moddingfromamod.ui.MenuKit.PickMenu) p.containerMenu).page();
                    int again = -1;
                    for (var e : page.slots().entrySet()) {
                        if ("again".equals(e.getValue())) {
                            again = e.getKey();
                        }
                    }
                    helper.assertTrue(again >= 0, Component.literal("the page offers no Make another one: "
                            + page.tiles()));
                    var beforeAgain = newestOf(server, p);
                    p.containerMenu.clicked(again, 0, ContainerInput.PICKUP, p);
                    var wish = newestOf(server, p);
                    helper.assertTrue(wish != null && (beforeAgain == null || !wish.id().equals(beforeAgain.id())),
                            Component.literal("Make another one made no wish; Moddy said: "
                                    + com.moddingfromamod.testing.MockPlayerNetwork.chat(p)));
                    helper.assertTrue(wish.source() == WishSource.PRESET
                                    && "twoclick_probe_tpl".equals(wish.templateId()),
                            Component.literal("Make another one is not the recipe remake: source "
                                    + wish.source() + ", recipe " + wish.templateId()));
                    WishCancel.stop(p, "", com.moddingfromamod.marshal.CancelMode.STOP, "");
                });
            } finally {
                LearnedTemplates.restoreForTest(saved, server);
                com.moddingfromamod.item.WishItemCatalogue.forget(name);
            }
            helper.succeed();
        });
    }

    /**
     * The id of the book's newest wish, or null. A test asks whether a wish was recorded by this changing, never by the
     * book's size: the book keeps the newest 500 (WishBook.MAX_IN_MEMORY), so once a Gate B run has filed that many a
     * new wish leaves the size where it was (the public cut's batch order reached the cap first).
     */
    private static Object newestId(net.minecraft.server.MinecraftServer server) {
        var recent = WishBook.get(server).recent(1);
        return recent.isEmpty() ? null : recent.get(0).id();
    }

    private static Wish newestOf(net.minecraft.server.MinecraftServer server, ServerPlayer p) {
        return WishBook.get(server).recent(40).stream()
                .filter(w -> p.getUUID().equals(w.playerId()))
                .reduce((a, b) -> b).orElse(null);
    }

    private static com.google.gson.JsonObject template(String id, String word, String command) {
        com.google.gson.JsonObject tpl = new com.google.gson.JsonObject();
        tpl.addProperty("id", id);
        com.google.gson.JsonArray all = new com.google.gson.JsonArray();
        all.add(word);
        tpl.add("all", all);
        com.google.gson.JsonArray cmds = new com.google.gson.JsonArray();
        cmds.add(command);
        tpl.add("commands", cmds);
        tpl.addProperty("say", "probe");
        return tpl;
    }


    // ── TASK 2: THE WAY IN, with no typing at all ─────────────────────────────────────────────

    /**
     * <b>Right-click Moddy and the panel opens — through the REAL interaction path.</b>
     *
     * <p>{@code ServerPlayerGameMode.useItem}, not a call to the handler: the whole route depends on
     * NeoForge's hook firing before the item's own code, and only the real path proves that.
     * A child who cannot type cannot type {@code /wish} either, so this is the affordance the
     * zero-typing claim actually rests on.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public void rightClickingModdyOpensThePanel(GameTestHelper helper) {
        withRoute(() -> {
            // His OWN player: the click opens the wish box, which closes whatever chest is open, and this
            // batch's other tests hold the shared player's chest open while they press it.
            ServerPlayer p = namedPlayer(helper, "vw_rightclick");
            com.moddingfromamod.testing.MockPlayerNetwork.withoutChannels(p, () -> {
                ItemStack moddy = VanillaWatchItem.stack();
                helper.assertTrue(VanillaWatchItem.isWatch(moddy), Component.literal("the mark did not survive minting"));
                p.setItemInHand(InteractionHand.MAIN_HAND, moddy);
                drain(helper, p);
                p.gameMode.useItem(p, p.level(), p.getItemInHand(InteractionHand.MAIN_HAND),
                        InteractionHand.MAIN_HAND);
                List<Object> sent = drain(helper, p);
                // (text-first, on by default since 2026-11-16): the door is the WISH BOX, vanilla's dialog.
                helper.assertTrue(showDialog(sent) != null,
                        Component.literal("right-clicking Moddy opened nothing — the only zero-typing door is shut"));
                assertNothingNamesTheMod(helper, sent);
            });
            helper.succeed();
        });
    }

    /**
     * Moddy is a carrier, so it inherits the carrier's own refusals — including the one the items
     * spike had to add: a wished thing may not be equipped onto a horse. Without it a child
     * right-clicking a horse while holding Moddy loses Moddy.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public void moddyCannotBePutOnAHorse(GameTestHelper helper) {
        withRoute(() -> {
            ItemStack moddy = VanillaWatchItem.stack();
            helper.assertTrue(
                    com.moddingfromamod.item.VanillaCarrierAbilities.isCarrier(moddy),
                    Component.literal("Moddy must be a carrier, or it inherits none of the carrier's refusals"));
            helper.succeed();
        });
    }

    // ── TASKS 3-4: STOP AND UNDO ──────────────────────────────────────────────────────────────

    /**
     * Stop and undo are <b>clickable chat running permission-0 commands</b>, which is a mechanism
     * this repo already ships ({@code WishFeedback.offerCancel}). This asserts the command half —
     * that a channel-less player can really run them — rather than rebuilding the chat half.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public void stopAndUndoRunOnAClientWithNoMod(GameTestHelper helper) {
        withRoute(() -> {
            ServerPlayer p = player(helper);
            com.moddingfromamod.testing.MockPlayerNetwork.withoutChannels(p, () -> {
                drain(helper, p);
                var stack = p.createCommandSourceStack();
                var dispatcher = serverOf().getCommands().getDispatcher();
                // Nothing is being made, so the honest outcome is "nothing to stop" — what matters
                // is that the command EXISTS at permission 0 and executes without a mod channel.
                helper.assertTrue(dispatcher.getRoot().getChild(ChildCommands.MODDY) != null,
                        Component.literal("/moddy is not in the command tree Brigadier syncs to a vanilla client"));
                serverOf().getCommands().performPrefixedCommand(stack, "moddy stop");
                serverOf().getCommands().performPrefixedCommand(stack, "moddy undo");
                serverOf().getCommands().performPrefixedCommand(stack, "moddy status");
                assertNothingNamesTheMod(helper, drain(helper, p));
            });
            helper.succeed();
        });
    }

    /**
     * <b>The panel's own 👎, pressed — not the command beside it.</b>
     *
     * <p>{@link #stopAndUndoRunOnAClientWithNoMod} covers the CHAT half of cancelling: clickable
     * chat running a permission-0 command, which a channel-less client can use. It deliberately
     * does not touch the panel's REJECT cell, and nothing else did either — the only gate in the
     * tree that ever pressed 👎 was {@code WishClientTest}'s {@code WISHREJECT}, which drives the
     * <b>legacy</b> {@code WishScreen} and so says nothing about the route that actually ships.
     *
     * <p>So this is the missing observation behind the register's {@code interact.cancel} row: a
     * child with no mod at all presses the pinned control and the wish is really undone and marked
     * refused (👎 is just undo — it no longer goes back to the agent). It goes through {@code containerMenu.clicked} rather than calling
     * {@code WishService.reject}, because the claim is that the CELL is wired to it — calling the
     * method directly would pass with the cell wired to nothing.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public void theRejectCellWorksOnAClientWithNoMod(GameTestHelper helper) {
        withRoute(() -> {
            ServerPlayer p = player(helper);
            com.moddingfromamod.testing.MockPlayerNetwork.withoutChannels(p, () -> {
                // Something to be unhappy ABOUT. A freshly submitted wish is rejectable:
                // WishRejection.isRejectable wants the same player, recent, not already refused.
                helper.assertTrue(WatchMenu.open(p), Component.literal("the panel refused to open"));
                WatchPanel.Cell preset = turnToFirstPreset(p);
                helper.assertTrue(preset != null, Component.literal("no preset to press, so there is nothing to "
                        + "reject and this test would pass having measured nothing"));
                p.containerMenu.clicked(preset.slot(), 0, ContainerInput.PICKUP, p);
                var made = WishBook.get(serverOf()).recent(40).stream()
                        .filter(w -> p.getUUID().equals(w.playerId()))
                        .reduce((a, b) -> b).orElse(null);
                helper.assertTrue(made != null, Component.literal("pressing a preset recorded no wish"));
                helper.assertTrue(!WishRejection.alreadyRejected(made),
                        Component.literal("the wish is already marked refused before anything pressed 👎, so the "
                                + "assertion below could not tell a working cell from a dead one"));

                // The panel closed when the preset was pressed; open it again to press 👎.
                helper.assertTrue(WatchMenu.open(p), Component.literal("the panel refused to re-open"));
                WatchPanel.Cell reject = WatchMenu.planFor(p).cells().stream()
                        .filter(c -> c.action() == WatchPanel.Action.REJECT)
                        .findFirst().orElse(null);
                helper.assertTrue(reject != null, Component.literal("the panel has no 👎 cell at all — it is pinned "
                        + "in the control row, so its absence is the bug rather than this test's"));
                drain(helper, p);
                p.containerMenu.clicked(reject.slot(), 0, ContainerInput.PICKUP, p);

                var after = WishBook.get(serverOf()).byId(made.id()).orElse(null);
                helper.assertTrue(after != null, Component.literal("the wish vanished from the book entirely"));
                helper.assertTrue(WishRejection.alreadyRejected(after),
                        Component.literal("pressing 👎 did not mark the wish refused, so a child with no mod has no "
                                + "way to say a wish was wrong except by typing the command. That "
                                + "is the panel's own control and it is the whole reason "
                                + "interact.cancel is registered as path 1"));
                helper.assertTrue(after.status() == WishStatus.STOPPED,
                        Component.literal("a 👎 must settle the wish STOPPED (#1546), not re-open it: " + after.status()));
                assertNothingNamesTheMod(helper, drain(helper, p));

                // Since the 👎 already stopped it; this is belt and braces on a shared world,
                // so nothing of this wish runs through a neighbour's measurement.
                WishCancel.stop(p, "", com.moddingfromamod.marshal.CancelMode.STOP, "");
            });
            helper.succeed();
        });
    }

    // ── THE GUARDS: everything a chest menu already means ─────────────────────────────────────

    /**
     * <b>Nothing in the panel can be taken.</b> Every verb a chest menu offers, one after the
     * other, on a real menu — because "cannot happen" is how the item spike justified two of its
     * worst bugs. The creative clone is the likely one rather than the exotic one: the child this
     * is built for plays creative.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public void nothingInThePanelCanBeTakenByAnyVerb(GameTestHelper helper) {
        withRoute(() -> {
            ServerPlayer p = player(helper);
            com.moddingfromamod.testing.MockPlayerNetwork.withoutChannels(p, () -> {
                for (ContainerInput type : new ContainerInput[] {ContainerInput.PICKUP, ContainerInput.QUICK_MOVE,
                        ContainerInput.CLONE, ContainerInput.THROW, ContainerInput.SWAP, ContainerInput.PICKUP_ALL,
                        ContainerInput.QUICK_CRAFT}) {
                    helper.assertTrue(WatchMenu.open(p), Component.literal("the panel refused to open"));
                    var menu = p.containerMenu;
                    // Slot 0 is a preset when there are any; whatever it is, it must not come out.
                    menu.clicked(0, 0, type, p);
                    helper.assertTrue(menu.getCarried().isEmpty(),
                            Component.literal(type + " put a panel item on the cursor: " + menu.getCarried()));
                    for (ItemStack held : p.getInventory().getNonEquipmentItems()) {
                        helper.assertTrue(!held.is(Items.AMETHYST_SHARD)
                                        && !held.is(Items.DYE.gray()) && !held.is(Items.DYE.red()),
                                Component.literal(type + " moved a panel item into his inventory: " + held));
                    }
                    p.closeContainer();
                    p.getInventory().clearContent();
                }
            });
            helper.succeed();
        });
    }

    /** His own items may not be swallowed by the panel, in either direction. */
    @GameTest(template = "empty_test", batch = BATCH)
    public void hisOwnItemsCannotBeSwallowed(GameTestHelper helper) {
        withRoute(() -> {
            ServerPlayer p = player(helper);
            com.moddingfromamod.testing.MockPlayerNetwork.withoutChannels(p, () -> {
                p.getInventory().clearContent();
                p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 7));
                helper.assertTrue(WatchMenu.open(p), Component.literal("the panel refused to open"));
                var menu = p.containerMenu;
                // Shift-click his diamonds from his own inventory: quickMoveStack is the door.
                int firstPlayerSlot = WatchPanel.SIZE;
                for (int i = firstPlayerSlot; i < menu.slots.size(); i++) {
                    if (menu.slots.get(i).getItem().is(Items.DIAMOND)) {
                        menu.clicked(i, 0, ContainerInput.QUICK_MOVE, p);
                    }
                }
                p.closeContainer();
                int diamonds = 0;
                for (ItemStack held : p.getInventory().getNonEquipmentItems()) {
                    if (held.is(Items.DIAMOND)) {
                        diamonds += held.getCount();
                    }
                }
                helper.assertTrue(diamonds == 7,
                        Component.literal("the panel ate his diamonds: " + diamonds + " of 7 came back"));
            });
            helper.succeed();
        });
    }

    // ── ⚰ NEUTRALITY WAS HERE, AND WENT WITH THE FLAG ──────────────────────
    //
    // `withTheRouteOffTheWholeSpikeIsInert` asserted that with `vanillawatch=off` the panel
    // refused to open, no screen packet went out, and right-clicking Moddy did nothing -- the
    // claim that "it is behind a flag" was about code somebody had actually run. It was the right
    // test for as long as the flag existed, and deleted the flag: the panel is the only watch
    // UI, so a test asserting it does NOT open is asserting a bug.
    //
    // ⚠ IT DID NOT FAIL LOUDLY BY ITSELF, and that is the part worth writing down. `withRoute`
    // forced TWO switches; with the watch one gone, `withRoute(false)` still compiled, still ran,
    // and set the CARRIER override to `null` -- i.e. to the default -- so the body was measuring
    // the shipped configuration while its assertions still said "off". It went red on the first
    // assertion, which was luck: an arm that silently becomes the control is exactly the
    // instrument fault this repo keeps paying for. `withRoute` now takes no boolean at all.

    /** An ordinary item is not Moddy, however much it looks like one. */
    @GameTest(template = "empty_test", batch = BATCH)
    public void anOrdinaryCarrierIsNotModdy(GameTestHelper helper) {
        withRoute(() -> {
            helper.assertTrue(!VanillaWatchItem.isWatch(
                    new ItemStack(Items.LEATHER_HORSE_ARMOR)),
                    Component.literal("a dungeon horse armour must not be a watch"));
            helper.assertTrue(!VanillaWatchItem.isWatch(new ItemStack(Items.DIAMOND)),
                    Component.literal("an ordinary item must not be a watch"));
            helper.succeed();
        });
    }

    /**
     * <b>Every icon the panel can choose is a REAL item.</b>
     *
     * <p>A mistyped id does not throw: {@code BuiltInRegistries.ITEM.get} answers AIR, and an AIR
     * stack in a slot is an EMPTY slot — so the row would silently lose a cell and the panel would
     * look like a child with fewer things than he has. This is the only place that can tell,
     * because the registry does not exist in a unit test.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public void everyIconIsARealItem(GameTestHelper helper) {
        List<String> missing = new ArrayList<>();
        // BOTH tables. WatchIcons.everyIcon() is the KEYWORD mapping; a library button (the
        // starter set) also NAMES its own icon, and a typo there resolves to AIR exactly the same
        // way — an empty slot where a button should be, with nothing logged and every unit test green.
        List<String> ids = new ArrayList<>(com.moddingfromamod.marshal.WatchIcons.everyIcon());
        for (WishCatalog.Preset p : com.moddingfromamod.library.WishLibrary.asPresets()) {
            if (p.icon() != null && !p.icon().isBlank()) {
                ids.add(p.icon());
            }
        }
        for (String id : ids) {
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getValue(net.minecraft.resources.Identifier.parse(id));
            if (item == net.minecraft.world.item.Items.AIR) {
                missing.add(id);
            }
        }
        helper.assertTrue(missing.isEmpty(), Component.literal("icons that are not real items: " + missing));
        helper.succeed();
    }

    /**
     * <b>The free-text path, driven the way a child drives it.</b>
     *
     * <p>Presses the panel's "wish for something new" slot, which opens the wish box — a vanilla
     * dialog (D2) — then runs what its Wish button runs, and asserts a wish was recorded as
     * {@code TYPED} — the same source the mod screen's own text box produced, which is §0.5 held at
     * the one place it could quietly drift. A blank box is refused first, and the box reopens.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public void wishingForSomethingNewNeedsNoConsole(GameTestHelper helper) {
        withRoute(() -> {
            ServerPlayer p = player(helper);
            NextWish.quietForTest(p.getUUID());   // the box's own shape here; the idea in it is theWishBoxOpensWithOnlyTheIdea's
            com.moddingfromamod.testing.MockPlayerNetwork.withoutChannels(p, () -> {
            WatchMenu.open(p);
            WatchPanel.Layout plan = WatchMenu.planFor(p);
            int slot = -1;
            for (WatchPanel.Cell c : plan.cells()) {
                if (c.action() == WatchPanel.Action.COMPOSE) {
                    slot = c.slot();
                }
            }
            helper.assertTrue(slot >= 0, Component.literal("the panel offers no way to wish for something new"));

            Object before = newestId(serverOf());
            drain(helper, p);
            p.containerMenu.clicked(slot, 0, ContainerInput.PICKUP, p);
            helper.assertTrue(p.containerMenu == p.inventoryMenu,
                    Component.literal("the watch is still open under the wish box: " + p.containerMenu.getClass().getSimpleName()));
            com.google.gson.JsonObject dialog = shownDialog(helper, drain(helper, p));
            String template = dialog.getAsJsonObject("yes").getAsJsonObject("action").get("template").getAsString();
            // The Builder is on in every world, so a Wish Magician's box carries the choice of magic
            // and its button says which; a world with the Builder off has the plain one.
            String magic = WishCompose.offersChoice(p)
                    ? WishCompose.MAGIC_KEY + " $(" + WishCompose.MAGIC_KEY + ") " : "";
            helper.assertValueEqual(template, ChildCommands.MODDY + " " + ChildCommands.WISHBOX + " " + magic + com.moddingfromamod.ui.TuneDialog.Words.MARK + "$(" + WishCompose.KEY + ")",
                    Component.literal("the Wish button's command"));
            helper.assertValueEqual(initial(wishBoxInput(helper, dialog)), "",
                    Component.literal("the wish box must open EMPTY"));

            var src = p.createCommandSourceStack();
            // A8 FIRST: a blank wish must be refused and nothing reopened (a blank send is how
            // Escape and a closed phone form arrive). A submitted empty wish is worse than no wish.
            // Each blank form on its own: the template's EMPTY substitution, "moddy wishbox |", is
            // what a blank Submit (and on Bedrock a closed form) really sends. These two lines used to be
            // run back to back with one check after, so the bare command's reopen covered for
            // "moddy wishbox " failing to parse at all.
            for (String blank : new String[] {
                    ChildCommands.MODDY + " " + ChildCommands.WISHBOX + " " + com.moddingfromamod.ui.TuneDialog.Words.MARK,
                    ChildCommands.MODDY + " " + ChildCommands.WISHBOX}) {
                int ran;
                try {
                    ran = serverOf().getCommands().getDispatcher().execute(blank, src);
                } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                    throw new AssertionError("'" + blank + "' does not even parse — the child sees '"
                            + e.getMessage() + "' (#1999)", e);
                }
                helper.assertTrue(ran == 0, Component.literal("'" + blank + "' did not refuse the blank wish"));
                // A blank send is a CANCEL: Escape on Java runs the button with the box empty, so a
                // box that reopened here could never be closed.
                helper.assertTrue(drain(helper, p).stream().noneMatch(
                                o -> o instanceof net.minecraft.network.protocol.common.ClientboundShowDialogPacket),
                        Component.literal("'" + blank + "' reopened the box: Cancel must cancel"));
            }
            helper.assertTrue(java.util.Objects.equals(newestId(serverOf()), before),
                    Component.literal("an EMPTY wish was submitted"));

            // Then the real thing, exactly as the client sends it: the dialog ESCAPES the box's value
            // (StringTag.escapeWithoutQuotes), so "dinosaur's" arrives as "dinosaur\'s".
            serverOf().getCommands().performPrefixedCommand(src, "moddy wishbox " + com.moddingfromamod.ui.TuneDialog.Words.MARK + "a robot dinosaur\\'s song\\nthat sings");

            WishBook book = WishBook.get(serverOf());
            helper.assertTrue(!java.util.Objects.equals(newestId(serverOf()), before),
                    Component.literal("no wish was recorded (the book's newest is still " + before + ")"));
            var newest = book.recent(1).get(0);
            helper.assertValueEqual(newest.source(), WishSource.TYPED, Component.literal("source"));
            helper.assertValueEqual(newest.text(), "a robot dinosaur's song that sings",
                    Component.literal("the wish that was recorded is not the one he typed"));
            // Stop it: a real wish in a shared world, exactly as the preset test does.
            WishCancel.stop(p, "", com.moddingfromamod.marshal.CancelMode.STOP, "");
            });
        });
        helper.succeed();
    }

    /** The dialog a {@code ClientboundShowDialogPacket} on the wire carries, as vanilla's codec writes it. */
    private static com.google.gson.JsonObject shownDialog(GameTestHelper helper, List<Object> wire) {
        for (Object o : wire) {
            if (o instanceof net.minecraft.network.protocol.common.ClientboundShowDialogPacket d) {
                var ops = net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE,
                        helper.getLevel().registryAccess());
                return net.minecraft.server.dialog.Dialog.DIRECT_CODEC.encodeStart(ops, d.dialog().value())
                        .getOrThrow().getAsJsonObject();
            }
        }
        helper.fail(Component.literal("no dialog was sent to the client"));
        return null;
    }

    /** A text input's initial words; vanilla's codec leaves out an empty one (its default). */
    private static String initial(com.google.gson.JsonObject box) {
        return box.has("initial") ? box.get("initial").getAsString() : "";
    }

    /**
     * The dialog's one text box, the wish box's. The only other input it may have is the choice of magic, which
     * a Wish Magician's box carries on every world whose Builder is on (all of them by default).
     */
    private static com.google.gson.JsonObject wishBoxInput(GameTestHelper helper, com.google.gson.JsonObject dialog) {
        var inputs = dialog.getAsJsonArray("inputs");
        helper.assertTrue(inputs != null && !inputs.isEmpty(), Component.literal("the wish box has no input: " + dialog));
        com.google.gson.JsonObject box = null;
        for (var in : inputs) {
            var o = in.getAsJsonObject();
            if (WishCompose.MAGIC_KEY.equals(o.get("key").getAsString())) {
                continue;
            }
            helper.assertTrue(box == null, Component.literal("the wish box has more than one text input: " + dialog));
            box = o;
        }
        helper.assertTrue(box != null, Component.literal("the wish box has no text input: " + dialog));
        helper.assertValueEqual(box.get("type").getAsString(), "minecraft:text", Component.literal("the input type"));
        helper.assertValueEqual(box.get("key").getAsString(), WishCompose.KEY, Component.literal("the input key"));
        return box;
    }

    /**
     * <b>He can always get Moddy back.</b>
     *
     * <p>Three ways to lose him and only one of them was covered. The join hook fires when he
     * starts the game; it does NOT fire when he respawns, so a child who died had no door to the
     * zero-typing path until he next restarted — which is not a thing a seven-year-old connects to
     * having died. And neither hook helps the case that actually happens: he dropped it, or put it
     * in a chest, and wants it back now.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public void moddyCanAlwaysBeGotBack(GameTestHelper helper) {
        withRoute(() -> {
            ServerPlayer p = player(helper);
            com.moddingfromamod.testing.MockPlayerNetwork.withoutChannels(p, () -> {
                p.getInventory().clearContent();
                helper.assertTrue(!VanillaWatchItem.has(p), Component.literal("the fixture started with a Moddy"));

                helper.assertTrue(VanillaWatchItem.give(p), Component.literal("give() refused on an empty inventory"));
                helper.assertTrue(VanillaWatchItem.has(p), Component.literal("give() reported success and gave nothing"));

                // NEVER TWICE. A duplicate is how a panel of "things you made" starts lying.
                helper.assertTrue(!VanillaWatchItem.give(p), Component.literal("give() handed out a second Moddy"));
                int count = 0;
                for (ItemStack st : p.getInventory().getNonEquipmentItems()) {
                    if (VanillaWatchItem.isWatch(st)) {
                        count++;
                    }
                }
                helper.assertValueEqual(count, 1, Component.literal("how many Moddys"));

                // OFFHAND COUNTS. getInventory().items is the MAIN inventory only, so a Moddy held
                // in the offhand would look like no Moddy at all and he would be given a second.
                p.getInventory().clearContent();
                p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, VanillaWatchItem.stack());
                helper.assertTrue(VanillaWatchItem.has(p), Component.literal("a Moddy in the offhand was not seen"));
                helper.assertTrue(!VanillaWatchItem.give(p),
                        Component.literal("a second Moddy was given to a child already holding one in his offhand"));
            });
        });
        helper.succeed();
    }

    /**
     * {@code /moddy} exists, at permission 0.
     *
     * <p>It used to read {@code present == VanillaWatch.enabled()}, which was the honest form
     * while the command was registered conditionally. made the registration unconditional,
     * so the assertion is now the flat one — and that is strictly stronger: the old shape passed
     * on a run where the command was absent AND the route was off, which is a configuration
     * nothing ships.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public void moddyCommandIsRegistered(GameTestHelper helper) {
        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
        helper.assertTrue(dispatcher.getRoot().getChild(ChildCommands.MODDY) != null,
                Component.literal("/moddy is not registered, so a child who lost Moddy has no way to ask for "
                        + "another one without a grown-up typing /give"));
        helper.succeed();
    }


    /**
     * <b>The text field opens with NOTHING but the idea in it</b> (step 5: the next wish, ready in the box).
     *
     * <p>The anvil this replaced pre-filled its edit box from the input item's name, so a named prompt
     * became a PREFIX on every wish — measured, the first end-to-end film submitted
     * "Type your wish heremake me a Chucko McBurger". The dialog's box opens with exactly its
     * {@code initial}, so that is what is asserted, on the packet a stock client receives: exactly the idea Moddy
     * offered (and a chat line saying so), or empty when he has none; the instruction stays in the label.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public void theWishBoxOpensWithOnlyTheIdea(GameTestHelper helper) {
        withRoute(() -> {
            ServerPlayer p = player(helper);
            com.moddingfromamod.testing.MockPlayerNetwork.withoutChannels(p, () -> {
                drain(helper, p);
                helper.assertTrue(WishCompose.open(p), Component.literal("the wish box did not open"));
                List<Object> wire = drain(helper, p);
                NextWish.Idea idea = NextWish.offered(p.getUUID());
                com.google.gson.JsonObject box = wishBoxInput(helper, shownDialog(helper, wire));
                helper.assertValueEqual(initial(box), idea == null ? "" : idea.words(),
                        Component.literal("the box holds more than the idea — every wish would carry it as a prefix"));
                helper.assertTrue(box.toString().contains("What do you wish for"),
                        Component.literal("the instruction is not in the box's label: " + box));
                boolean told = wire.stream().anyMatch(o -> o instanceof net.minecraft.network.protocol.game.ClientboundSystemChatPacket c
                        && c.content().getString().equals(NextWish.SAID));
                helper.assertTrue(told == (idea != null),
                        Component.literal("an idea in the box must be said in chat, and only then: idea=" + idea + " told=" + told));
                NextWish.forget(p.getUUID());
            });
        });
        helper.succeed();
    }

    /**
     * <b>The box takes a long wish, on several lines, and vanilla's own codec accepts it</b> (D2). The
     * anvil's box stopped at 50 characters and 50 of the child's 84 real typed wishes were longer; this
     * one's cap is {@link WishText#BOX_MAX} (a wish past it is still clamped by {@link WishText#sanitize}).
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public void theWishBoxTakesALongWish(GameTestHelper helper) {
        withRoute(() -> {
            ServerPlayer p = player(helper);
            // Decoded by vanilla's codec: a malformed dialog (a bad multiline, an initial over the cap)
            // throws here rather than on a child's screen.
            String prefill = "x".repeat(WishText.BOX_MAX);
            var dialog = com.moddingfromamod.ui.MenuKit.decode(p, WishCompose.dialog(WishCompose.TITLE, prefill));
            var input = dialog.common().inputs().get(0);
            helper.assertTrue(input.control() instanceof net.minecraft.server.dialog.input.TextInput t
                            && t.maxLength() == WishText.BOX_MAX && t.multiline().isPresent()
                            && t.initial().equals(prefill),
                    Component.literal("the wish box is not a several-line box of " + WishText.BOX_MAX + ": " + input.control()));
        });
        helper.succeed();
    }

    /**
     * <b>Moddy wears his OWN artwork, at a number no wish can reach.</b>
     *
     * <p>He used to wear the wish library's {@code wand} — a real, rendering, perfectly nice picture
     * of a magic wand, and not a picture of the watch. That is the failure shape this repo keeps
     * paying for: nothing is missing, nothing errors, and the thing on screen is simply the wrong
     * object. So the assertion is on the NUMBER and on the reserve, both of which a headless server
     * can see; that the number resolves to the shipped model is {@code CarrierModelDriftTest}'s job,
     * and that it looks right is Gate C's.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public void moddyWearsHisOwnFace(GameTestHelper helper) {
        withRoute(() -> {
            ItemStack moddy = VanillaWatchItem.stack();
            var cmd = moddy.get(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA);
            helper.assertTrue(cmd != null && com.moddingfromamod.compat.ModelData.id(cmd)
                            == com.moddingfromamod.datapack.VanillaCarrier.WATCH_MODEL_DATA,
                    Component.literal("Moddy carries custom_model_data " + (cmd == null ? "none" : com.moddingfromamod.compat.ModelData.id(cmd))
                            + ", not his reserved "
                            + com.moddingfromamod.datapack.VanillaCarrier.WATCH_MODEL_DATA));
            for (com.moddingfromamod.item.WishIcons.Icon icon
                    : com.moddingfromamod.item.WishIcons.all()) {
                helper.assertTrue(
                        icon.id() != com.moddingfromamod.datapack.VanillaCarrier.WATCH_MODEL_DATA,
                        Component.literal("wish icon " + icon.name() + " has taken Moddy's reserved number"));
            }
            // WHITE, and this is the assertion that keeps the artwork intact: vanilla multiplies
            // layer 0 by dyed_color with no way to opt out, so any other tint repaints a finished
            // sprite -- a wrong picture that would look deliberate.
            // 🔴 AND HE IS NOT ARMOUR. The carrier is leather horse armour, so an un-neutralised
            // Moddy tooltips "When equipped: +3 Armor" under "Wish for something new!". Found by
            // opening the photograph, not by a red gate — "the cell is labelled" and "the cell says
            // nothing untrue" are different questions and only the first was ever asked.
            //
            // Asked as "does he CHANGE anything about the child", which is route-independent and
            // stronger than counting entries: NeoForge falls back to the item's own modifiers when
            // the component is EMPTY, so the fix is a present, non-empty, amount-0 entry — and a
            // test that asserted "no modifiers" would demand exactly the shape that breaks.
            var mods = moddy.get(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS);
            helper.assertTrue(mods != null && !mods.modifiers().isEmpty(),
                    Component.literal("Moddy has no attribute_modifiers component, so NeoForge falls back to the "
                            + "carrier's own +3 armor and he tooltips as body armour"));
            for (var e : mods.modifiers()) {
                helper.assertTrue(e.modifier().amount() == 0.0D,
                        Component.literal("Moddy changes " + e.attribute().value() + " by " + e.modifier().amount()
                                + " — a wish watch is not armour"));
            }
            var dye = moddy.get(net.minecraft.core.component.DataComponents.DYED_COLOR);
            helper.assertTrue(dye != null && (dye.rgb() & 0xFFFFFF) == 0xFFFFFF,
                    Component.literal("Moddy is tinted " + (dye == null ? "not at all" : Integer.toHexString(dye.rgb()))
                            + ", which repaints his own artwork"));
        });
        helper.succeed();
    }

    /**
     * <b>He comes back on his own, with nothing typed.</b>
     *
     * <p>Join and respawn are the two moments the game hands a player back to himself; neither
     * covers dropping Moddy in lava at teatime. Before the top-up the answer to that was
     * {@code /moddy} — typing, on the one door whose whole reason for existing is a child who
     * cannot type. This drives the real tick handler rather than {@code give}, because "there is no
     * top-up" and "the top-up is not wired to the tick" look identical from outside.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public void moddyComesBackWithoutTyping(GameTestHelper helper) {
        withRoute(() -> {
            ServerPlayer p = player(helper);
            // A gate mock is topped up only when its test opts in (VanillaWatchItem.topUpApplies).
            VanillaWatchItem.topUpMockForTest(p);
            try {
                com.moddingfromamod.testing.MockPlayerNetwork.withoutChannels(p, () -> {
                    p.getInventory().clearContent();
                    helper.assertTrue(!VanillaWatchItem.has(p), Component.literal("he started with one"));
                    var server = helper.getLevel().getServer();
                    // The real handler, called the way the bus calls it. The cadence is deliberately
                    // not asserted here -- a GameTest server ticks as fast as it can, so "five seconds"
                    // is not a thing this gate can measure; what it can measure is that the handler
                    // hands one over.
                    for (int i = 0; i < VanillaWatchItem.TOP_UP_TICKS + 1
                            && !VanillaWatchItem.has(p); i++) {
                        VanillaWatchItem.onServerTick(
                                new net.neoforged.neoforge.event.tick.ServerTickEvent.Post(() -> true, server));
                    }
                    helper.assertTrue(VanillaWatchItem.has(p),
                            Component.literal("Moddy never came back, so the only way to the panel is typing"));
                    int before = count(p);
                    for (int i = 0; i < 300; i++) {
                        VanillaWatchItem.onServerTick(
                                new net.neoforged.neoforge.event.tick.ServerTickEvent.Post(() -> true, server));
                    }
                    helper.assertValueEqual(count(p), before, Component.literal("Moddys after topping up again"));
                });
            } finally {
                VanillaWatchItem.stopTopUpForTest(p);
            }
        });
        helper.succeed();
    }

    /**
     * <b>A gate mock nobody opted in is never topped up.</b> A mock is a real ServerPlayer in the
     * player list, so the top-up used to hand EVERY test's mock a watch every five seconds, into
     * whatever window that test was measuring: {@code LoadAndPourGameTest} cleared its mock, ran an
     * undo, asserted the inventory empty, and failed once in ~125 runs as "the undo handed a machine
     * back". 🔴 Seen RED with {@code topUpApplies} answering true for every player.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public void aGateMockNobodyOptedInIsNeverToppedUp(GameTestHelper helper) {
        ServerPlayer p = player(helper);
        p.getInventory().clearContent();
        var server = helper.getLevel().getServer();
        for (int i = 0; i < VanillaWatchItem.TOP_UP_TICKS + 1; i++) {
            VanillaWatchItem.onServerTick(
                    new net.neoforged.neoforge.event.tick.ServerTickEvent.Post(() -> true, server));
        }
        helper.assertTrue(p.getInventory().isEmpty(),
                Component.literal("a whole top-up cycle handed a mock nobody opted in a watch: " + count(p) + " Moddys"));
        helper.succeed();
    }

    private static int count(ServerPlayer p) {
        int n = 0;
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            if (VanillaWatchItem.isWatch(s)) {
                n++;
            }
        }
        return n;
    }

    /**
     * <b>An old Moddy from an old chest opens the NEW panel.</b>
     *
     * <p>The flip left {@code moddingfromamod:wish_watch} in every world that already had one, and
     * the obvious reading of "keep it for back-compat" is to leave its old screen wired up. That is
     * two Moddys with two different UIs in one world, which is a second product rather than
     * compatibility — so the legacy item's {@code use} forks on the route and opens the same
     * container the carrier opens.
     *
     * <p>Driven through the item's REAL {@code use}, not through {@code WatchMenu.open}: "there is
     * no door" and "the door is not wired to the item" look identical from outside and want
     * opposite fixes.
     *
     * <p>⚰ <b>It had a twin, {@code theLegacyWatchKeepsItsOldBehaviourWithTheRouteOff}, and
     * deleted it.</b> The twin existed because this test alone would pass just as well against an
     * item that ALWAYS opened the panel — which is a fair objection to an assertion about a FORK,
     * and is now the specification: the fork is gone, the item has one behaviour, and an item that
     * always opens the panel is the thing being asserted rather than a false pass. What the twin
     * really pinned was the escape hatch a regression gets bisected with, and
     * {@code docs/COMPAT_SHIMS.md} is explicit that if you want a rollback you keep the JAR.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public void theLegacyWatchOpensTheVanillaPanel(GameTestHelper helper) {
        withRoute(() -> {
            ServerPlayer p = namedPlayer(helper, "vw_legacy");   // his own: the box closes any open chest
            com.moddingfromamod.testing.MockPlayerNetwork.withoutChannels(p, () -> {
                ItemStack legacy = new ItemStack(
                        com.moddingfromamod.item.ModItems.WISH_WATCH.get());
                p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, legacy);
                int before = WishCompose.openedCount(p.getUUID());
                legacy.getItem().use(p.level(), p,
                        net.minecraft.world.InteractionHand.MAIN_HAND);
                // the SAME door as Moddy's — the text-first wish box — never a second UI.
                helper.assertTrue(WishCompose.openedCount(p.getUUID()) > before,
                        Component.literal("right-clicking a legacy watch opened nothing (no wish box), so a child with "
                                + "one from before the flip has no way to wish at all"));
            });
        });
        helper.succeed();
    }
}
