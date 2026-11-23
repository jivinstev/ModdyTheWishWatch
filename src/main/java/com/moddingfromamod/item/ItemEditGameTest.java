package com.moddingfromamod.item;

import net.minecraft.network.chat.Component;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>An edit really edits, and the gate really fires — through the REAL tool.</b>
 *
 * <p>{@code ItemDriftTest} tests the rule and {@code ItemCritiqueWiringTest} tests that the rule
 * reaches {@code critiqueFor}. Neither reaches {@code call()}, and there is a mutation that proves
 * the gap: change the tool to compare the resolved item <b>to itself</b> instead of to the design it
 * started from and both of those suites stay green, while every real edit reports nothing for ever —
 * which is indistinguishable from a gate that is silent because nothing was lost.
 *
 * <p>So this drives {@code make_item} twice against a real server and reads what came back.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class ItemEditGameTest {

    /**
     * Values for whatever parameters the catalogue's effects demand.
     *
     * <p>Every parameter is required and none is defaulted — that is the tool's whole design (
     * a default cannot tell an ambiguous wish from an explicit one) — so a test that sends {@code
     * {}} is refused before it reaches anything this class is about. The same table
     * {@code ItemMatrixGameTest} keeps, for the same reason.
     */
    private static final java.util.Map<String, String> VALUES = java.util.Map.ofEntries(
            java.util.Map.entry("MOB", "minecraft:zombie"),
            java.util.Map.entry("COUNT", "3"),
            java.util.Map.entry("BONUS", "minecraft:speed"),
            java.util.Map.entry("SECONDS", "10"),
            java.util.Map.entry("HEARTS", "5"),
            java.util.Map.entry("BLOCK", "minecraft:water"),
            java.util.Map.entry("SAFE", "yes"),
            java.util.Map.entry("SCALE", "4"),
            java.util.Map.entry("ITEM", "minecraft:emerald"),
            java.util.Map.entry("STRIKES", "2"),
            java.util.Map.entry("GLOW_SECONDS", "20"),
            java.util.Map.entry("POWER", "launch"),
            java.util.Map.entry("SIZE", "3"),
            java.util.Map.entry("BREAKS_BLOCKS", "no"),
            java.util.Map.entry("LEVEL", "2"),
            java.util.Map.entry("RATE", "4"));

    /** {@code {"<effect>": {"PARAM": "value", …}}} for each effect named, grouped as the tool wants. */
    private static String paramsFor(String... effects) {
        JsonObject all = new JsonObject();
        for (String id : effects) {
            var entry = com.moddingfromamod.datapack.ItemCatalogue.load().get(id);
            JsonObject one = new JsonObject();
            if (entry != null) {
                for (String name : entry.params()) {
                    one.addProperty(name, VALUES.getOrDefault(name, "1"));
                }
            }
            all.add(id, one);
        }
        return all.toString();
    }

    private static JsonObject call(String name, String effect, String params, String startFrom) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("icon", "wand");
        o.addProperty("colour", "purple");
        o.addProperty("shape", "held");
        o.addProperty("effect", effect);
        o.addProperty("count", 1);
        o.add("params", JsonParser.parseString(params));
        if (startFrom != null) {
            o.addProperty("start_from", startFrom);
        }
        return o;
    }

    /**
     * An effect id from the catalogue, by position — so this test pins no name that may be renamed,
     * and only ones whose parameters {@link #VALUES} can actually supply. An entry needing a
     * parameter nobody has a value for would be refused before the test began, and the failure
     * would read as a broken gate.
     */
    private static String anEffect(int index) {
        var usable = com.moddingfromamod.datapack.ItemCatalogue.load().all().stream()
                .filter(e -> e.params().stream().allMatch(VALUES::containsKey))
                .map(com.moddingfromamod.datapack.ItemCatalogue.Entry::id)
                .toList();
        return usable.isEmpty() ? "" : usable.get(Math.min(index, usable.size() - 1));
    }

    /**
     * This test's designs: ids that start with its own id. Never {@code ItemSpecStore.count()} — a test
     * beside it in the batch makes items too, and on 1.21.8's faster batches one landed in between.
     */
    private static int ownDesigns(String id) {
        return (int) ItemSpecStore.all().stream().filter(sp -> sp.id().startsWith(id)).count();
    }

    /**
     * The measured failure, end to end: adding one effect by naming only the new one.
     *
     * <p>{@code effect} is the whole list rather than a delta, so <i>"can it set things on fire
     * too"</i> answered as {@code ignite} alone silently removes the lightning the wand was made
     * for. It looks identical; he finds out when he tries it.
     */
    @GameTest(template = "empty_test", timeoutTicks = 400)
    public static void droppingAnEffectIsReportedThroughTheRealTool(GameTestHelper helper) {
        String first = anEffect(0);
        String second = anEffect(1);
        if (first.isEmpty() || first.equals(second)) {
            helper.succeed();            // a one-entry catalogue cannot express this at all
            return;
        }
        String id = "gt_edit_" + helper.getLevel().getGameTime();
        ToolResult made = new MakeItemTool().call(call(id, first, paramsFor(first), null));
        helper.assertTrue(!made.isError(), Component.literal("could not make the item at all: " + made.text()));

        ToolResult edited = new MakeItemTool().call(call(id, second, paramsFor(second), id));
        helper.assertTrue(!edited.isError(), Component.literal("the edit failed: " + edited.text()));
        helper.assertTrue(edited.text().contains("no longer does " + first),
                Component.literal("the drift gate did not reach make_item's result. It is either unwired, or it is "
                        + "comparing the item to ITSELF rather than to what it started from — both "
                        + "of which look exactly like 'nothing was lost'. Got: " + edited.text()));
        helper.succeed();
    }

    /**
     * …and a correct addition says nothing, so the note stays worth reading.
     *
     * <p>Its own case because a gate that fires on the right answer is noise, and noise is why the
     * proportion gate was deleted rather than left in place.
     */
    @GameTest(template = "empty_test", timeoutTicks = 400)
    public static void keepingBothEffectsIsSilent(GameTestHelper helper) {
        String first = anEffect(0);
        String second = anEffect(1);
        if (first.isEmpty() || first.equals(second)) {
            helper.succeed();
            return;
        }
        String id = "gt_keep_" + helper.getLevel().getGameTime();
        ToolResult made = new MakeItemTool().call(call(id, first, paramsFor(first), null));
        helper.assertTrue(!made.isError(), Component.literal("could not make the item at all: " + made.text()));
        ToolResult edited = new MakeItemTool().call(
                call(id, first + "+" + second, paramsFor(first, second), id));
        helper.assertTrue(!edited.isError(), Component.literal("the edit failed: " + edited.text()));
        helper.assertTrue(!edited.text().contains("no longer does"),
                Component.literal("it kept both effects and was told it lost one: " + edited.text()));
        helper.succeed();
    }

    /**
     * <b>An item is offered the HELD-ITEM camera, not the building one and not a field.</b>
     *
     * <p>Before this, every item wish card was the single unframed snapshot taken from wherever the
     * child happened to be standing: the real one for a purple wand that shoots lightning is grass,
     * flowers, a lake and the wish sparkles, with the wand nowhere in it.
     *
     * <p>It cannot take the photograph here — that needs a client — so it asserts the DECISION,
     * which is the part that was wrong: an item is recognised as an item, and a subject that is not
     * one is handed on with a reason rather than photographed as if it were.
     */
    @GameTest(template = "empty_test", timeoutTicks = 400)
    public static void anItemIsRecognisedByTheCamera(GameTestHelper helper) {
        String first = anEffect(0);
        if (first.isEmpty()) {
            helper.succeed();
            return;
        }
        String id = "gt_photo_" + helper.getLevel().getGameTime();
        ToolResult made = new MakeItemTool().call(call(id, first, paramsFor(first), null));
        helper.assertTrue(!made.isError(), Component.literal("could not make the item at all: " + made.text()));

        // A GameTest world has no player, so it cannot get past that — which is exactly the point:
        // reaching "nobody" means it recognised the subject as an item and went looking for a hand
        // to put it in. Not recognising it at all answers something else entirely.
        //
        // ON A WORKER THREAD, because AgentBridge.onServer refuses to be called FROM the server
        // thread — it would wait for itself — and a GameTest body IS the server thread. succeedWhen
        // keeps ticking the server so the work the thread schedules actually runs.
        String missing = "gt_not_a_thing_" + helper.getLevel().getGameTime();
        java.util.concurrent.atomic.AtomicReference<String> real = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> notAnItem = new java.util.concurrent.atomic.AtomicReference<>();
        Thread t = new Thread(() -> {
            com.moddingfromamod.agent.tools.PhotographTool.lastItemSkip = null;
            com.moddingfromamod.agent.tools.PhotographTool.lastIconSkip = "not asked";
            // THROUGH capture(), NOT captureHeldItem() DIRECTLY. Calling the method proves the
            // method works and says nothing about whether the product reaches it — deleting the
            // three lines that wire it into capture() left this test green until it was driven
            // from here instead. That is the same defect that made a Spikor castle's wish card a
            // close-up of a wall while three gates stayed green.
            //
            // Since an item's card is its ICON, drawn from the stack or
            // from its design, and the held-item camera is reached only when nothing can draw it.
            // Every vanilla base has a stand-in now, so "recognised as an item" is EITHER
            // the icon drawn (lastIconSkip null) OR the held-item camera asked (lastItemSkip set).
            ToolResult shot = com.moddingfromamod.agent.tools.PhotographTool.capture("gt_photo", id, null);
            String iconSkip = com.moddingfromamod.agent.tools.PhotographTool.lastIconSkip;
            String itemSkip = com.moddingfromamod.agent.tools.PhotographTool.lastItemSkip;
            real.set(iconSkip == null && shot != null && shot.imageBase64() != null ? "drawn"
                    : String.valueOf(itemSkip));
            com.moddingfromamod.agent.tools.PhotographTool.lastItemSkip = null;
            com.moddingfromamod.agent.tools.PhotographTool.captureHeldItem("gt_photo", missing, null);
            notAnItem.set(String.valueOf(
                    com.moddingfromamod.agent.tools.PhotographTool.lastItemSkip));
        }, "gametest-item-photo");
        t.setDaemon(true);
        t.start();

        helper.succeedWhen(() -> {
            helper.assertTrue(notAnItem.get() != null, Component.literal("waiting for the camera"));
            helper.assertTrue(real.get() != null, Component.literal("waiting for the camera"));
            helper.assertTrue(!"null".equals(real.get()),
                    Component.literal("capture() neither drew the item's icon nor reached the held-item camera — an "
                            + "item wish card is the unframed snapshot again, which is a photograph of a field"));
            helper.assertTrue(!real.get().contains("no item by that name"),
                    Component.literal("the camera did not recognise a real wish item as one: " + real.get()));
            helper.assertTrue("no item by that name".equals(notAnItem.get()),
                    Component.literal("a creature must be handed on to the orbit, with a reason, not shot as an "
                            + "item: " + notAnItem.get()));
        });
    }

    /**
     * The edit keeps the SAME item rather than making a second one.
     *
     * <p>The class's headline, asserted against the store rather than against a ladder: before the
     * stable id, {@code refinements that LANDED} equalled {@code EXTRA items} exactly, because the
     * feature id was a slug of the name and the effects and every edit was a new thing.
     */
    @GameTest(template = "empty_test", timeoutTicks = 400)
    public static void anEditKeepsOneItem(GameTestHelper helper) {
        String first = anEffect(0);
        String id = "gt_one_" + helper.getLevel().getGameTime();
        ToolResult made = new MakeItemTool().call(call(id, first, paramsFor(first), null));
        helper.assertTrue(!made.isError(), Component.literal("could not make the item at all: " + made.text()));
        int after = ownDesigns(id);   // its own, never the store's total (a batch sibling makes items too)

        // A rename AND a recolour: both used to change the feature id, so both used to duplicate.
        JsonObject edit = call(id, first, paramsFor(first), id);
        edit.addProperty("name", id + " MEGA");
        edit.addProperty("colour", "green");
        ToolResult r = new MakeItemTool().call(edit);
        helper.assertTrue(!r.isError(), Component.literal("the edit failed: " + r.text()));
        helper.assertValueEqual(ownDesigns(id), after, Component.literal("designs after a rename+recolour"));

        ItemSpecStore.Spec spec = ItemSpecStore.find(id);
        helper.assertTrue(spec != null, Component.literal("the design vanished under its own id"));
        helper.assertTrue("green".equals(spec.colour()),
                Component.literal("the recolour did not land: " + spec.colour()));
        helper.assertTrue(spec.name().endsWith("MEGA"), Component.literal("the rename did not land: " + spec.name()));
        helper.succeed();
    }

    /**
     * <b>"and one that…" leaves him with TWO, and the first one untouched.</b>
     *
     * <p>The exact mirror of {@link #anEditKeepsOneItem} above, and the outcome that was
     * <b>impossible</b> until this change: {@code MakeItemTool} chose the design id with
     * {@code base != null ? base.id() : …}, so every use of {@code start_from} wrote over A and no
     * argument in any combination meant "inherit this and mint a second". The wish that needs it —
     * <i>"and one that shoves the noob in LAVA"</i> — could only ever come out as a change to the
     * pearl he already had, and an earlier issue's expectation that the agent "reaches start_from and modifies
     * A" was structurally guaranteed rather than merely likely.
     *
     * <p>Asserted against the STORE. an earlier issue's own gate — <i>items in his inventory afterwards</i>,
     * two for a copy and one for an edit — is {@code InventoryCountGameTest}, which holds a real
     * player. It was added because the store turned out NOT to be an honest proxy for an
     * edit: one design, and the old stack still in his bag beside the new one.
     */
    @GameTest(template = "empty_test", timeoutTicks = 400)
    public static void asNewLeavesHimWithBothItems(GameTestHelper helper) {
        String first = anEffect(0);
        String id = "gt_two_" + helper.getLevel().getGameTime();
        ToolResult made = new MakeItemTool().call(call(id, first, paramsFor(first), null));
        helper.assertTrue(!made.isError(), Component.literal("could not make the first item: " + made.text()));
        // ITS OWN designs, never the store's total: a test beside it in the batch makes items too,
        // and on 1.21.8's faster batches one landed in between (257 where 256 was right).
        int after = ownDesigns(id);
        // ⚠ BY ITS REAL ID, NEVER BY find(name). find() falls through to a SUBSTRING match and
        // returns "the LAST match: the newest thing by that name" — so once the copy exists,
        // find("gt_two_1") returns "gt_two_1_sibling". My first cut did that and the gate failed
        // with "the original was recoloured", which is exactly what a real overwrite looks like.
        // The store is the source of the id because make_item derives it from the name AND the
        // effect, so the test cannot spell it.
        java.util.List<ItemSpecStore.Spec> before = ItemSpecStore.all().stream()
                .filter(sp -> sp.id().startsWith(id)).toList();
        ItemSpecStore.Spec original = before.get(before.size() - 1);
        String originalId = original.id();
        String originalColour = original.colour();
        String originalName = original.name();

        // "and one that…" — inherit the whole design, send only the delta, and SAY it is a second
        // thing. Everything but the name and colour is left out on purpose: that is what makes it
        // a copy of A rather than a description of a new item.
        JsonObject copy = new JsonObject();
        copy.addProperty("start_from", id);
        copy.addProperty("as_new", true);
        copy.addProperty("name", id + "_sibling");
        copy.addProperty("colour", "red");
        ToolResult r = new MakeItemTool().call(copy);
        helper.assertTrue(!r.isError(), Component.literal("the copy was refused: " + r.text()));

        helper.assertValueEqual(ownDesigns(id), after + 1,
                Component.literal("designs after 'and one that…' — he asked for ANOTHER one"));

        // THE HALF THAT MATTERS. A copy that quietly edited A would also raise the count if it
        // minted anything at all, so the original is checked field by field: he would be furious
        // to find his first pearl turned into a lava pearl.
        ItemSpecStore.Spec still = ItemSpecStore.get(originalId);
        helper.assertTrue(still != null,
                Component.literal("the ORIGINAL design vanished from under its own id '" + originalId
                        + "' — the copy overwrote it"));
        helper.assertTrue(originalColour == null ? still.colour() == null
                        : originalColour.equals(still.colour()),
                Component.literal("the original was recoloured by a call that asked for a SECOND item: "
                        + originalColour + " -> " + still.colour()));
        helper.assertTrue(still.name().equals(originalName),
                Component.literal("the original was renamed by the copy: " + still.name()));

        // And the new one really inherited rather than being a blank sheet — a copy that kept
        // nothing is a new item wearing the word 'copy', which is the other way to fail this.
        // The newest design, for the same reason: its id is derived, not chosen by the test.
        java.util.List<ItemSpecStore.Spec> now = ItemSpecStore.all();
        ItemSpecStore.Spec sibling = now.get(now.size() - 1);
        helper.assertTrue(!sibling.id().equals(originalId),
                Component.literal("the newest design IS the original, so nothing new was minted under its own id"));
        helper.assertTrue("red".equals(sibling.colour()),
                Component.literal("the delta did not land on the copy: " + sibling.colour()));
        helper.assertTrue(sibling.effects().equals(original.effects()),
                Component.literal("the copy did not inherit what the original DOES, so it is a new item rather than "
                        + "another one of his: " + sibling.effects() + " vs " + original.effects()));
        helper.assertTrue(first.equals(sibling.shape()) || original.shape().equals(sibling.shape()),
                Component.literal("the copy did not inherit the shape: " + sibling.shape()));
        helper.succeed();
    }

    /**
     * A copy whose name lands on its own base is refused rather than silently doing the old thing.
     *
     * <p>{@code as_new} with a name that mints the same id inherits A, writes under A's id, and
     * leaves the child with one item — the original defect wearing the new flag. Refusing asks the
     * agent to SAY something different, which is always possible, so it does not fall foul of
     * an earlier issue's "never refuse what may be impossible" rule.
     */
    @GameTest(template = "empty_test", timeoutTicks = 400)
    public static void aCopyOntoItsOwnBaseIsRefused(GameTestHelper helper) {
        String first = anEffect(0);
        String id = "gt_selfcopy_" + helper.getLevel().getGameTime();
        ToolResult made = new MakeItemTool().call(call(id, first, paramsFor(first), null));
        helper.assertTrue(!made.isError(), Component.literal("could not make the first item: " + made.text()));
        int after = ownDesigns(id);

        JsonObject copy = call(id, first, paramsFor(first), id);
        copy.addProperty("as_new", true);
        ToolResult r = new MakeItemTool().call(copy);
        helper.assertTrue(r.isError(),
                Component.literal("a 'second item' with the same name as its base was accepted, so it wrote over "
                        + "the thing it copied and he still has one: " + r.text()));
        helper.assertTrue(r.text().contains("as_new"),
                Component.literal("the refusal must name the remedy BOTH ways — the agent may have meant an edit: "
                        + r.text()));
        helper.assertValueEqual(ownDesigns(id), after,
                Component.literal("a refused call must change nothing"));
        helper.succeed();
    }

    /**
     * <b>An item wish's picture is its ICON, drawn from the stack he holds — no camera</b>.
     * Through {@code capture()}, as finish_building calls it, with a real made item in
     * a real (mock) player's inventory: the picture comes back, square, in the item's own colour, and
     * the held-item CAMERA was never asked ({@code lastItemSkip} untouched), so no VM woke for it.
     */
    @GameTest(template = "empty_test", batch = "itemIcon", timeoutTicks = 400)
    public static void anItemsPictureIsItsIconDrawnWithNoCamera(GameTestHelper helper) {
        String first = anEffect(0);
        if (first.isEmpty()) {
            helper.succeed();
            return;
        }
        var player = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper);
        String id = "gt_icon_" + helper.getLevel().getGameTime();
        JsonObject args = call(id, first, paramsFor(first), null);
        args.addProperty("colour", "#2266DD");
        ToolResult made = new MakeItemTool().call(args);
        helper.assertTrue(!made.isError(), Component.literal("could not make the item at all: " + made.text()));
        var spec = com.moddingfromamod.item.ItemSpecStore.find(id);
        helper.assertTrue(spec != null, Component.literal("the design was not stored"));
        player.getServer().getCommands().performPrefixedCommand(
                com.moddingfromamod.compat.EntityCommands.source(player).withPermission(4),
                "wishdatapack " + spec.id() + " give");
        java.util.UUID wish = java.util.UUID.randomUUID();
        String worker = "gt-itemicon-" + wish;
        com.moddingfromamod.marshal.Marshal.global().submit(wish, player.getUUID(), "gt item icon");
        com.moddingfromamod.marshal.Marshal.global().assign(worker, wish);
        java.util.concurrent.atomic.AtomicReference<ToolResult> got = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> heldSkip = new java.util.concurrent.atomic.AtomicReference<>();
        Thread t = new Thread(() -> {
            com.moddingfromamod.agent.AgentContext.bind(worker);
            try {
                com.moddingfromamod.agent.tools.PhotographTool.lastItemSkip = "untouched";
                got.set(com.moddingfromamod.agent.tools.PhotographTool.capture("gt_icon", spec.id(), null));
                heldSkip.set(com.moddingfromamod.agent.tools.PhotographTool.lastItemSkip);
            } finally {
                com.moddingfromamod.agent.AgentContext.unbind();
                com.moddingfromamod.marshal.Marshal.global().finish(wish);
            }
        }, "gametest-item-icon");
        t.setDaemon(true);
        t.start();
        helper.succeedWhen(() -> {
            helper.assertTrue(got.get() != null, Component.literal("waiting for the picture"));
            ToolResult r = got.get();
            helper.assertTrue(r.imageBase64() != null,
                    Component.literal("no picture: " + r.text() + " (icon skip: "
                            + com.moddingfromamod.agent.tools.PhotographTool.lastIconSkip + ")"));
            helper.assertTrue("untouched".equals(heldSkip.get()),
                    Component.literal("the held-item CAMERA was asked (" + heldSkip.get() + ") — an item wish woke a camera"));
            java.awt.image.BufferedImage img;
            try {
                img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(
                        java.util.Base64.getDecoder().decode(r.imageBase64())));
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
            helper.assertTrue(img != null && img.getWidth() == com.moddingfromamod.item.ItemIconPicture.SIZE
                    && img.getHeight() == img.getWidth(), Component.literal("a square icon picture"));
            int blueish = 0;
            int opaque = 0;
            for (int y = 0; y < img.getHeight(); y += 4) {
                for (int x = 0; x < img.getWidth(); x += 4) {
                    int p = img.getRGB(x, y);
                    if (p >>> 24 > 200) {
                        opaque++;
                        if ((p & 0xFF) > ((p >> 16) & 0xFF) + 30) {
                            blueish++;
                        }
                    }
                }
            }
            helper.assertTrue(opaque > 50, Component.literal("the icon drew something (" + opaque + " opaque samples)"));
            helper.assertTrue(blueish * 4 > opaque, Component.literal("the wand is in ITS colour, #2266DD: " + blueish
                    + " of " + opaque + " opaque samples are blue"));
            com.moddingfromamod.testing.MockPlayerNetwork.remove(player);
        });
    }
}
