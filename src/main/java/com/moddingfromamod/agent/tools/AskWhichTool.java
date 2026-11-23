package com.moddingfromamod.agent.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.marshal.WhichPick;
import com.moddingfromamod.marshal.WishVerb;
import com.moddingfromamod.wish.ChildCommands;
import com.moddingfromamod.wish.WhichQuestion;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>"Which one do you mean?" — asked by the agent, answered by the child</b>.
 *
 * <p>The picture picker existed for a year behind the local resolver's ASK rung, which its own
 * narrowing rules made unreachable in a real collection. Now the agent raises it, for the
 * one case worth interrupting a child about: a wrong pick that would destroy or remove something,
 * with words that fit more than one of his things. Every client can answer it:
 * <ul>
 *   <li>a <b>modded</b> client gets the photographs ({@code AskWhichPayload} → {@code WishPickScreen});</li>
 *   <li>a <b>stock</b> client gets one row of a chest with the watch's own icons ({@link
 *       com.moddingfromamod.wish.WhichMenu});</li>
 *   <li><b>everyone</b> gets the names in chat as clickable links, in case the screen is lost.</li>
 * </ul>
 * All three run {@code /moddy which <id>}, and this call returns his pick.
 *
 * <p><b>: only what is OUT in the world, for a wish that acts on one.</b> Before he is asked,
 * the server drops every offered thing that is not out (a creature design with none alive, a
 * building that does not stand), picks the last one left without asking him, and refuses an offer
 * of nothing but designs — {@link WhichPick}. A wish for ANOTHER one says {@code about: design} and
 * is offered the whole library.
 */
public final class AskWhichTool implements McpTool {

    /** How long to wait for him before handing the decision back. A question, not a wall. */
    public static final long WAIT_MS = 60_000L;

    @Override
    public String name() {
        return "ask_which";
    }

    @Override
    public String description() {
        return "Ask the child which of HIS things he means, with pictures, and wait for his pick "
                + "(up to " + (WAIT_MS / 1000) + " s). ONLY for risky ambiguity: his words would "
                + "DESTROY, REMOVE or replace something and fit 2–" + WishVerb.MAX_ASKABLE
                + " of his things (\"knock down my tower\" when he has two towers) — or a follow-up "
                + "that says \"it\"/\"him\" when NOTHING in the wish note says which of his things "
                + "(none in view, no last wish) and several fit. Never for any other reversible "
                + "change (pick the likeliest and say which in plan_wish `about`), and "
                + "never for a wish that makes something NEW. A wish that ACTS ON one of his things "
                + "(remove, hit, ride, feed, take me to, make it bigger…) can only mean one that is "
                + "OUT IN THE WORLD — the catalogue marks which — so offer only those, with about: "
                + "'instance'; this tool drops any that are not out, and when only one is left it "
                + "picks that one WITHOUT asking him. about: 'design' is for a wish for ANOTHER one "
                + "from a design (\"make me another\"), and offers any design. Returns his pick's id, "
                + "or that he picked none / did not answer.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        Schemas.prop(s, "ids", "array",
                "2–" + WishVerb.MAX_ASKABLE + " ids of HIS things to offer, from the catalogue in "
                        + "the wish note.");
        Schemas.prop(s, "about", "string",
                "'instance' (default): the wish acts on one that is OUT in the world — only those "
                        + "are offered. 'design': he wants ANOTHER one made from a design — any "
                        + "design is offered.");
        Schemas.prop(s, "question", "string",
                "OPTIONAL: a few kid-words, e.g. 'Which castle should I remove?'");
        return Schemas.required(s, "ids");
    }

    /** What the server decided before (or instead of) asking him. */
    private record Asked(WhichQuestion.Open open, WhichPick.Decision decision, String unknown) {
    }

    @Override
    public ToolResult call(JsonObject args) {
        List<String> ids = new ArrayList<>();
        if (args != null && args.has("ids") && args.get("ids").isJsonArray()) {
            args.getAsJsonArray("ids").forEach(e -> {
                if (e != null && e.isJsonPrimitive() && !e.getAsString().isBlank()
                        && !ids.contains(e.getAsString().strip())) {
                    ids.add(e.getAsString().strip());
                }
            });
        }
        if (ids.size() < 2 || ids.size() > WishVerb.MAX_ASKABLE) {
            return ToolResult.error("Offer between 2 and " + WishVerb.MAX_ASKABLE + " of his things "
                    + "in `ids` — a question with one answer is not a question, and more than "
                    + WishVerb.MAX_ASKABLE + " is not one a child can answer.");
        }
        String question = args.has("question") && args.get("question").isJsonPrimitive()
                ? args.get("question").getAsString() : "";
        WhichPick.About about = WhichPick.About.parse(
                args.has("about") && args.get("about").isJsonPrimitive() ? args.get("about").getAsString() : "");
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        if (run == null) {
            return ToolResult.error("I can't tell which wish this question is for.");
        }
        final java.util.UUID playerId = run.playerId();
        final String wishText = run.text();
        Asked asked;
        try {
            asked = AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                ServerPlayer player = server == null ? null
                        : server.getPlayerList().getPlayer(playerId);
                if (player == null) {
                    return null;
                }
                // which of these are OUT in the world, by the same count the catalogue showed.
                java.util.Map<String, com.moddingfromamod.marshal.WorldCatalogue.Entry> byKey = new java.util.HashMap<>();
                for (var e : com.moddingfromamod.wish.ArtifactIndex.everything()) {
                    byKey.putIfAbsent(com.moddingfromamod.wish.AboutIds.key(e.id()), e);
                }
                var inWorld = com.moddingfromamod.wish.InWorldCounts.snapshot(server);
                List<WhichPick.Offer> offers = new ArrayList<>();
                for (String id : ids) {
                    var entry = byKey.get(com.moddingfromamod.wish.AboutIds.key(id));
                    if (entry == null || PlanWishTool.nameOf(id) == null) {
                        return new Asked(null, null, id);
                    }
                    offers.add(new WhichPick.Offer(id, entry.name(), inWorld.apply(entry)));
                }
                WhichPick.Decision d = WhichPick.decide(about, wishText, offers);
                if (d.kind() != WhichPick.Kind.ASK) {
                    return new Asked(null, d, null);
                }
                List<String> names = new ArrayList<>();
                for (String id : d.ids()) {
                    names.add(PlanWishTool.nameOf(id).replaceFirst("^your ", ""));
                }
                WhichQuestion.Open q = WhichQuestion.ask(playerId, d.ids());
                ask(player, d.ids(), names, question, q);
                return new Asked(q, d, null);
            });
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
        if (asked == null) {
            return ToolResult.error("He is not in the world right now, so he cannot be asked. Pick "
                    + "the likeliest, or leave it.");
        }
        if (asked.unknown() != null) {
            return ToolResult.error("Every id in `ids` must be one of his things, from the catalogue "
                    + "in the wish note. Got " + ids + ".");
        }
        if (asked.decision().kind() == WhichPick.Kind.REFUSE) {
            return ToolResult.error(asked.decision().message());
        }
        if (asked.decision().kind() == WhichPick.Kind.CHOSEN) {
            return ToolResult.ok(asked.decision().message());
        }
        WhichQuestion.Open open = asked.open();
        String offeredNote = asked.decision().message().isEmpty() ? "" : " " + asked.decision().message();
        long deadline = System.currentTimeMillis() + WAIT_MS;
        try {
            while (true) {
                if (run.cancelled()) {
                    return ToolResult.ok("The wish was stopped while he was being asked.");
                }
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    return ToolResult.ok("He did not pick in " + (WAIT_MS / 1000) + " s. Do NOT "
                            + "remove or replace anything on a guess: tell him in a sentence what "
                            + "you need to know, and finish without destroying anything.");
                }
                try {
                    String picked = open.answer().get(Math.min(left, 500), TimeUnit.MILLISECONDS);
                    if (WhichQuestion.NONE.equals(picked)) {
                        return ToolResult.ok("He said it was NONE of those. Do not change any of "
                                + "them; tell him in a sentence and ask what he meant.");
                    }
                    return ToolResult.ok("He picked '" + picked + "'. Use plan_wish about: '"
                            + picked + "' and do the wish to that one." + offeredNote);
                } catch (TimeoutException e) {
                    // keep waiting, and keep checking whether the wish was stopped
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Interrupted while waiting for his answer.");
        } catch (java.util.concurrent.ExecutionException e) {
            return ToolResult.error("His answer could not be read: " + e.getMessage());
        } finally {
            WhichQuestion.close(playerId, open);
        }
    }

    /**
     * Ask, on every surface he has. Server thread only. The pictures first — their names are what failed
     * to separate them — then the question in chat to everyone; then a stock client gets the pick page
     * (closing it is "something else", so the question cannot be lost) and a modded one the chat links.
     */
    static void ask(ServerPlayer player, List<String> ids, List<String> names, String question,
                    WhichQuestion.Open asked) {
        // THE PICTURES, as the upgrade: Send.to delivers only where the channel is.
        com.moddingfromamod.net.Send.to(player, com.moddingfromamod.net.ModdedClient.askWhich(ids));
        // THE QUESTION IN CHAT, TO EVERYONE, unconditionally: a modded and a stock client must hold
        // the same answers to one question (LegacyWishUiTest).
        player.sendSystemMessage(Component.literal(question == null || question.isBlank()
                        ? "🤔 Which one do you mean?" : "🤔 " + question.strip())
                .setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)));
        // (2026-11-10): A STOCK CLIENT — Java or Bedrock — ANSWERS ON A PICK PAGE, NOT IN CHAT.
        // WhichMenu is a button chest of his things' own pictures (a one-tap grid on a phone), and closing
        // it answers "something else", so the question cannot be lost. Clickable links here used to pop a
        // second "Moddy" form over that grid on Bedrock. Only a modded client, whose own picture screen is
        // the payload above, still gets the links.
        if (!com.moddingfromamod.net.ModdedClient.can(player,
                com.moddingfromamod.net.ModdedClient.Channel.ASK_WHICH)) {
            com.moddingfromamod.wish.WhichMenu.open(player, ids, question, asked);
            return;
        }
        Component row = Component.literal("   ");
        for (int i = 0; i < ids.size(); i++) {
            row = row.copy().append(link("[" + names.get(i) + "]", ChatFormatting.AQUA,
                    ChildCommands.which(ids.get(i)))).append(Component.literal(" "));
        }
        row = row.copy().append(link("[something else]", ChatFormatting.GRAY,
                ChildCommands.which(WhichQuestion.NONE)));
        player.sendSystemMessage(row);
    }

    private static Component link(String text, ChatFormatting colour, String command) {
        return Component.literal(text).setStyle(Style.EMPTY.withColor(colour)
                .withClickEvent(com.moddingfromamod.compat.ChatEvents.click(ClickEvent.Action.RUN_COMMAND, command)));
    }
}
