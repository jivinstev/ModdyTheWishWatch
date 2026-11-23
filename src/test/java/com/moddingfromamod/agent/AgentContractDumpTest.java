package com.moddingfromamod.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Writes down exactly what the agent is asked and what it is offered</b> — so routing can be
 * measured without a world.
 *
 * <p> found that half of the real machine wishes never reach the machine tools: the BUTT CHEEK
 * WATER FACTORY got {@code sculpt_creature}. Routing is emergent — there is no wish-class router —
 * so the decision is made from exactly three things: the system prompt, the tool list <i>in the
 * order it is offered</i>, and the wish text. All three are available without Minecraft running,
 * which is what makes routing measurable in seconds rather than in 900-second grants.
 *
 * <p>So this dumps the real contract to {@code build/routing/agent-contract.json} and
 * {@code tools/routing-arm.py} replays it against a stub MCP server. <b>Dumped rather than
 * hand-copied on purpose</b>: a fixture copy of the prompt would drift from the prompt the child's
 * agent actually gets, and a routing harness measuring a stale prompt is worse than no harness — it
 * would report a fix as working after the thing it fixed had moved.
 *
 * <p>The assertions here are only that the dump is not empty and not obviously wrong. What the
 * numbers MEAN is the harness's job.
 */
class AgentContractDumpTest {

    /** Where the harness looks. Kept in one place so the two cannot disagree. */
    static final Path OUT = Path.of("build", "routing", "agent-contract.json");

    @Test
    void theContractTheAgentActuallyGetsIsWrittenDownForTheRoutingHarness() throws Exception {
        // ONE contract. It used to be dumped twice — with Create (the routing harness's
        // default, for machine wishes) and without (agent-contract-no-create.json, what a live
        // server serves). Create is gone, so the second IS the contract and the first no longer
        // exists. The sibling file is still written, identical, for any arm that names it.
        JsonObject contract = contract();
        Files.createDirectories(OUT.getParent());
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(contract);
        Files.writeString(OUT, json, StandardCharsets.UTF_8);
        Files.writeString(OUT.resolveSibling("agent-contract-no-create.json"), json,
                StandardCharsets.UTF_8);
        check(contract);
    }

    /**
     * the contract each SEARCH arm serves — the two search tools, and the catalogues of the
     * searched kinds slimmed to a line an entry — one file per arm, plus the shipped library index
     * the stub answers from ({@code readymade-index.json}). The nudge arms need no contract of their
     * own: a nudge changes what a reply says, not what is offered.
     */
    @Test
    void theLibraryArmsContractsAreWrittenDown() throws Exception {
        String key = "moddingfromamod.readymade.search";
        String was = System.getProperty(key);
        try {
            for (String arm : java.util.List.of("a", "b", "c", "d", "abcd")) {
                System.setProperty(key, arm);
                JsonObject contract = contract();
                Files.createDirectories(OUT.getParent());
                Files.writeString(OUT.resolveSibling("agent-contract-search-" + arm + ".json"),
                        new GsonBuilder().setPrettyPrinting().create().toJson(contract),
                        StandardCharsets.UTF_8);
                java.util.List<String> names = new java.util.ArrayList<>();
                contract.getAsJsonArray("tools").forEach(t -> names.add(t.getAsJsonObject().get("name").getAsString()));
                assertTrue(names.contains("find_ready_made") && names.contains("describe_ready_made"),
                        "search arm " + arm + " does not offer the search tools: " + names);
            }
        } finally {
            if (was == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, was);
            }
        }
        java.util.List<com.moddingfromamod.readymade.ReadyMade> all =
                new java.util.ArrayList<>(com.moddingfromamod.readymade.ReadyMadeSources.shipped());
        all.addAll(com.moddingfromamod.readymade.ReadyMadeSources.starterButtons());
        Files.writeString(OUT.resolveSibling("readymade-index.json"),
                new com.moddingfromamod.readymade.ReadyMadeIndex(all).toJson().toString(), StandardCharsets.UTF_8);
        // …and the default contract offers NEITHER: with every knob off the agent is the one that
        // shipped.
        java.util.List<String> names = new java.util.ArrayList<>();
        contract().getAsJsonArray("tools").forEach(t -> names.add(t.getAsJsonObject().get("name").getAsString()));
        assertFalse(names.contains("find_ready_made"), "the search tool is offered with search off");
    }

    private static JsonObject contract() {
        JsonArray tools = new JsonArray();
        for (McpTool tool : WishAgent.buildTools().all()) {
            JsonObject t = new JsonObject();
            t.addProperty("name", tool.name());
            t.addProperty("description", tool.description());
            t.add("inputSchema", tool.inputSchema());
            tools.add(t);
        }
        JsonObject contract = new JsonObject();
        contract.addProperty("prompt", AgentSession.prompt());
        contract.add("tools", tools);
        contract.add("planFirst", planFirst());
        contract.add("teachFirst", teachFirst());
        contract.add("propWarning", propWarning());
        return contract;
    }

    /**
     * The plan-first rule as McpProtocol enforces it, so tools/routing-stub-mcp.py refuses
     * exactly what the server would — read from here, never typed into the stub. {tool} is where
     * the stub puts the tool name. run_command is left out: no world has a wish budget yet.
     */
    private static JsonObject planFirst() {
        JsonArray names = new JsonArray();
        com.moddingfromamod.marshal.PlanFirst.NEEDS_PLAN.stream().sorted().forEach(names::add);
        JsonObject o = new JsonObject();
        o.add("tools", names);
        o.addProperty("message", com.moddingfromamod.marshal.PlanFirst.message("{tool}"));
        return o;
    }

    /**
     * finish_wish's one-time "teach it first" refusal, so the stub can answer an untaught
     * command-granted wish with the server's own words. {@value #DRAFT_SLOT} is where the stub puts
     * the draft it folds from the run's run_command lines (the same fold TeachDraft does).
     */
    static final String DRAFT_SLOT = "{\"commands\":[\"{COMMANDS}\"]}";

    private static JsonObject teachFirst() {
        JsonObject o = new JsonObject();
        o.addProperty("message", com.moddingfromamod.wish.TeachDraft.refusal(
                new com.moddingfromamod.wish.TeachDraft.Draft(java.util.List.of("{COMMANDS}"), 1, false,
                        java.util.List.of())));
        o.addProperty("slot", DRAFT_SLOT);
        return o;
    }

    /**
     * the warning run_command adds when it spawned things the end-of-wish tidy will take, so
     * the stub answers in the server's own words. The stub spells {THINGS} ("3 minecarts") and
     * picks the singular or plural sentence; the sentence itself is read from here.
     */
    private static JsonObject propWarning() {
        JsonObject o = new JsonObject();
        o.addProperty("one", com.moddingfromamod.wish.PropSweep.warningText("{THINGS}", false));
        o.addProperty("many", com.moddingfromamod.wish.PropSweep.warningText("{THINGS}", true));
        o.addProperty("refused_one", com.moddingfromamod.wish.PropSweep.refusedText("{THINGS}", false));
        o.addProperty("refused_many", com.moddingfromamod.wish.PropSweep.refusedText("{THINGS}", true));
        o.addProperty("slot", "{THINGS}");
        return o;
    }

    private void check(JsonObject contract) {
        JsonArray tools = contract.getAsJsonArray("tools");
        assertFalse(AgentSession.prompt().isBlank(), "the agent's system prompt is empty");
        assertTrue(tools.size() > 20, "only " + tools.size() + " tools — the registry did not build");

        // ORDER IS THE THING BEING MEASURED, so a dump that loses it measures nothing. WishAgent's
        // own doc says an agent "works down the list looking for the first tool that could
        // plausibly satisfy the wish".
        assertTrue(tools.get(0).getAsJsonObject().get("name").getAsString().equals("await_wish"),
                "await_wish is no longer first, so the dumped order is not the offered order");

        // ── A TOOL CLASS THAT EXISTS BUT IS NOT OFFERED IS INVISIBLE, and that is not a
        // hypothetical: merging a branch back after its work had been reverted from main let git
        // auto-resolve every deleted hunk in main's favour. AuthorRecipeTool.class shipped in the
        // jar while its one registration line was silently dropped, so the agent was never offered
        // it, the lesson pointing at it had been reverted too, and the whole capability was dead in
        // a build that compiled, passed both gates and deployed.
        //
        // Nothing else can catch that: the class is present, the tests that call it directly still
        // pass, and only the LIST says whether a child's agent can reach it.
        java.util.List<String> offered = new java.util.ArrayList<>();
        for (var el : tools) {
            offered.add(el.getAsJsonObject().get("name").getAsString());
        }
        for (String must : java.util.List.of("author_datapack", "make_item", "build_structure",
                "finish_building", "finish_wish")) {
            assertTrue(offered.contains(must), must + " is not offered to the agent. The class may "
                    + "well exist and its own tests may well pass — but nothing registers it, so no "
                    + "wish can ever reach it. Offered: " + offered);
        }
    }
}
