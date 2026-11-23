package com.moddingfromamod.species;

import net.minecraft.network.chat.Component;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.agent.tools.SculptCreatureTool;
import com.moddingfromamod.item.ItemSpecStore;
import com.moddingfromamod.marshal.Marshal;

import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>B cannot edit A's thing in place; B refining it gets B's own copy</b> (two children sharing a
 * world: "neither ever touches another child's").
 *
 * <p>{@code ForkPolicy} decides this and is unit-tested, and it is WIRED on two paths:
 * {@code modify_mob} (creature numbers) and {@code BuildRegistry.put} (buildings). It is not wired on
 * the two paths a refinement most often takes: {@code sculpt_creature} re-sculpting by id ("give his
 * Spikor wings" — {@code SpeciesRegistry.put} keeps the owner's stamp and replaces the body), and
 * {@code make_item} with {@code start_from} ({@code ItemSpecStore.put} likewise). So these drive the
 * REAL tools, as two different children, and look at what is left of the first child's thing.
 *
 * <p>Each child acts on his own thread, bound to his own run — {@code AgentContext} is per-thread,
 * and the sculpt tool hops to the server thread, which it refuses to do FROM the server thread.
 * Its own batch: it binds runs in the global Marshal.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class ChildForkGameTest {

    private static final String BATCH = "childFork";

    private ChildForkGameTest() {
    }

    /** Run {@code body} as an agent granting a wish for {@code child}, off the server thread. */
    private static Thread as(UUID child, String label, Runnable body) {
        UUID wish = UUID.randomUUID();
        String worker = "gt-fork-" + label + "-" + wish;
        Marshal.global().submit(wish, child, "gt fork " + label);
        Marshal.global().assign(worker, wish);
        Thread t = new Thread(() -> {
            AgentContext.bind(worker);
            try {
                body.run();
            } finally {
                AgentContext.unbind();
                Marshal.global().finish(wish);
            }
        }, "gametest-fork-" + label);
        t.setDaemon(true);
        return t;
    }

    private static JsonObject body(String id, String colour) {
        return JsonParser.parseString("""
            {"id":"%s","spawn":false,"parts":[
              {"name":"body","from":[-4,0,-4],"to":[4,16,4],"colour":"%s"},
              {"name":"head","from":[-5,16,-5],"to":[5,26,5],"colour":"%s"}]}
            """.formatted(id, colour, colour)).getAsJsonObject();
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 6000)
    public static void bRefiningAsCreatureGetsHisOwnAndAsIsUntouched(GameTestHelper helper) {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        String id = "gt_fork_spikor_" + helper.getLevel().getGameTime();
        AtomicReference<String> done = new AtomicReference<>();
        AtomicReference<String> bobSaid = new AtomicReference<>();

        Thread b = as(bob, "bob", () -> {
            // "give his Spikor wings" — Bob reuses Alice's id, which is how sculpt re-sculpts.
            JsonObject wings = JsonParser.parseString("""
                {"id":"%s","start_from":"%s","spawn":false,"parts":[
                  {"name":"wingL","from":[-8,10,-1],"to":[-4,16,1],"colour":"black"}]}
                """.formatted(id, id)).getAsJsonObject();
            ToolResult r = new SculptCreatureTool().call(wings);
            bobSaid.set((r.isError() ? "ERROR " : "") + r.text());
            done.set("yes");
        });
        Thread a = as(alice, "alice", () -> {
            ToolResult r = new SculptCreatureTool().call(body(id, "green"));
            if (r.isError()) {
                done.set("alice failed: " + r.text());
                return;
            }
            b.start();
        });
        a.start();

        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(done.get() != null, Component.literal("waiting for both children"));
            helper.assertTrue("yes".equals(done.get()), Component.literal(done.get()));
            Species hers = SpeciesRegistry.get(id);
            helper.assertTrue(hers != null && alice.toString().equals(hers.ownerId()),
                    Component.literal("Alice's creature is gone or no longer hers: " + hers));
            var parts = com.moddingfromamod.runtime.ModelRepository.sculptedParts(id);
            helper.assertTrue(parts.stream().noneMatch(p -> p.name().equals("wingL")),
                    Component.literal("Bob's wings are on ALICE's Spikor — he re-sculpted her creature in place. "
                            + "Bob was told: " + bobSaid.get()));
            Species his = SpeciesRegistry.all().stream()
                    .filter(s -> bob.toString().equals(s.ownerId())).findFirst().orElse(null);
            helper.assertTrue(his != null,
                    Component.literal("Bob has no creature of his own after refining Alice's — his wish went "
                            + "nowhere. Bob was told: " + bobSaid.get()));
            helper.assertTrue(com.moddingfromamod.runtime.ModelRepository.sculptedParts(his.id())
                            .stream().anyMatch(p -> p.name().equals("wingL"))
                            && com.moddingfromamod.runtime.ModelRepository.sculptedParts(his.id())
                            .stream().anyMatch(p -> p.name().equals("head")),
                    Component.literal("Bob's copy must be Alice's Spikor WITH wings — the body inherited, not a "
                            + "lone wing"));
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 6000)
    public static void bRefiningAsItemGetsHisOwnAndAsIsUntouched(GameTestHelper helper) {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        String name = "Gt Fork Wand " + helper.getLevel().getGameTime();
        AtomicReference<String> aliceId = new AtomicReference<>();
        AtomicReference<String> done = new AtomicReference<>();
        AtomicReference<String> bobSaid = new AtomicReference<>();

        Thread b = as(bob, "bobitem", () -> {
            JsonObject green = new JsonObject();
            green.addProperty("start_from", aliceId.get());
            green.addProperty("colour", "green");
            MakeItemTool tool = new MakeItemTool();
            ToolResult r = tool.call(green);
            bobSaid.set((r.isError() ? "ERROR " : "") + r.text());
            // His SECOND refinement, still naming her wand: it must change HIS copy, not mint
            // another one and not touch hers.
            JsonObject bigger = new JsonObject();
            bigger.addProperty("start_from", aliceId.get());
            bigger.addProperty("name", name + " Mega");
            ToolResult r2 = new MakeItemTool().call(bigger);
            bobSaid.set(bobSaid.get() + " | second: " + (r2.isError() ? "ERROR " : "") + r2.text());
            done.set("yes");
        });
        Thread a = as(alice, "aliceitem", () -> {
            JsonObject wand = new JsonObject();
            wand.addProperty("name", name);
            wand.addProperty("icon", "wand");
            wand.addProperty("colour", "purple");
            wand.addProperty("shape", com.moddingfromamod.datapack.ItemCatalogue.PLAIN);
            wand.addProperty("count", 1);
            MakeItemTool tool = new MakeItemTool();
            ToolResult r = tool.call(wand);
            if (r.isError()) {
                done.set("alice failed: " + r.text());
                return;
            }
            aliceId.set(tool.lastMadeId());
            b.start();
        });
        a.start();

        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(done.get() != null, Component.literal("waiting for both children"));
            helper.assertTrue("yes".equals(done.get()), Component.literal(done.get()));
            // THE COPY MUST ACTUALLY BUILD, not only be written down. The design is stored before
            // the datapack compiles, so a fork whose id no datapack can carry ('~') passed every
            // check below while every child's edit of a shared item answered with an error (
            // smoke: re-wishing the canned lucky block).
            helper.assertTrue(!bobSaid.get().contains("ERROR"),
                    Component.literal("Bob's copy was stored but did not build: " + bobSaid.get()));
            ItemSpecStore.Spec hers = ItemSpecStore.get(aliceId.get());
            helper.assertTrue(hers != null && alice.toString().equals(hers.ownerId()),
                    Component.literal("Alice's wand is gone or no longer hers: " + hers));
            helper.assertTrue("purple".equals(hers.colour()),
                    Component.literal("Bob recoloured ALICE's wand " + hers.colour() + " — he edited her design in "
                            + "place. Bob was told: " + bobSaid.get()));
            ItemSpecStore.Spec his = ItemSpecStore.all().stream()
                    .filter(s -> bob.toString().equals(s.ownerId())).findFirst().orElse(null);
            helper.assertTrue(his != null && "green".equals(his.colour()),
                    Component.literal("Bob has no green wand of his own: " + his + ". Bob was told: " + bobSaid.get()));
            long bobs = ItemSpecStore.all().stream()
                    .filter(s -> bob.toString().equals(s.ownerId())).count();
            helper.assertValueEqual((int) bobs, 1,
                    Component.literal("Bob's second refinement must edit his OWN copy, not fork a third wand"));
            helper.assertTrue(his.name().endsWith("Mega"),
                    Component.literal("his second refinement did not land on his copy: " + his.name()));
            helper.assertTrue(!hers.name().endsWith("Mega"),
                    Component.literal("his second refinement renamed ALICE's wand"));
        });
    }

    /**
     * two children making a NEW item of the same name. The id is minted from the name and effects alone, so
     * Bob's fresh make used to land on Alice's id and replace her design under her stamp (seen in Gate B: two Builder
     * "Turbo Pickaxe" makes, gold and ocean, and the gold child was handed the ocean one). Now the second gets his
     * own id, and hers is untouched.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 6000)
    public static void aSameNamedNewItemIsHisOwnNeverAWriteIntoHers(GameTestHelper helper) {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        String name = "Gt Twin Pick " + helper.getLevel().getGameTime();
        AtomicReference<String> aliceId = new AtomicReference<>();
        AtomicReference<String> bobId = new AtomicReference<>();
        AtomicReference<String> done = new AtomicReference<>();
        java.util.function.Function<String, JsonObject> pick = colour -> {
            JsonObject o = new JsonObject();
            o.addProperty("name", name);
            o.addProperty("icon", "wand");
            o.addProperty("colour", colour);
            o.addProperty("shape", com.moddingfromamod.datapack.ItemCatalogue.PLAIN);
            o.addProperty("count", 1);
            return o;
        };
        Thread b = as(bob, "bobtwin", () -> {
            MakeItemTool tool = new MakeItemTool();
            ToolResult r = tool.call(pick.apply("blue"));
            bobId.set(tool.lastMadeId());
            done.set(r.isError() ? "bob failed: " + r.text() : "yes");
        });
        Thread a = as(alice, "alicetwin", () -> {
            MakeItemTool tool = new MakeItemTool();
            ToolResult r = tool.call(pick.apply("gold"));
            if (r.isError()) {
                done.set("alice failed: " + r.text());
                return;
            }
            aliceId.set(tool.lastMadeId());
            b.start();
        });
        a.start();
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(done.get() != null, Component.literal("waiting for both children"));
            helper.assertTrue("yes".equals(done.get()), Component.literal(done.get()));
            helper.assertFalse(aliceId.get().equals(bobId.get()),
                    Component.literal("Bob's new item took Alice's id '" + aliceId.get() + "'"));
            ItemSpecStore.Spec hers = ItemSpecStore.get(aliceId.get());
            helper.assertTrue(hers != null && alice.toString().equals(hers.ownerId()) && "gold".equals(hers.colour()),
                    Component.literal("Alice's gold pick was overwritten by Bob's make: " + hers));
            ItemSpecStore.Spec his = ItemSpecStore.get(bobId.get());
            helper.assertTrue(his != null && bob.toString().equals(his.ownerId()) && "blue".equals(his.colour()),
                    Component.literal("Bob has no blue pick of his own: " + his));
        });
    }
}
