package com.moddingfromamod.chat;

import java.util.Optional;

import com.moddingfromamod.agent.ActionLog;
import com.moddingfromamod.safety.ChatStrikes;
import com.moddingfromamod.safety.SafetyChatSweep;
import com.moddingfromamod.safety.SafetyLexicon;
import com.moddingfromamod.world.WorldFacts;
import com.moddingfromamod.world.WorldPeople;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * <b>The chat sweep's wiring</b> — public chat and the chat commands, through
 * {@link SafetyChatSweep}, on a world whose manifest turns it on ({@link WorldPeople}). The decision is
 * all in the pure class; this posts the answer: cancels what is held back, tells the sender, writes
 * the {@code safety} record (no words) and the grown-up note (local only), and remembers delivered
 * lines in {@link ChatHistory} for a report.
 *
 * <p>Priority HIGHEST so nothing downstream (a decorator, another mod) sees a message that will not
 * go out. Only a PLAYER's commands are read — RCON, functions and the wish executor's replays are not
 * chat. {@code /say} is permission 2 and not a player's chat on this server.
 *
 * <p><b>Placed text</b> (signs, books, anvil names — text a player PLACES rather than says) is swept by
 * {@link #placed} on a world whose {@code people.placedText} is {@code sweep} (step 4), reached
 * from three mixins ({@code SignTextMixin}, {@code BookTextMixin}, {@code AnvilNameMixin}) because
 * NeoForge has no event to cancel for any of them. Elsewhere it is still not covered (CHILD_SAFETY.md §9).
 */
public final class ChatGuard {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("moddingfromamod/chat");

    private ChatGuard() {
    }

    /** This server's break book and chat memory (both forget at a restart). */
    static final ChatStrikes STRIKES = new ChatStrikes();
    static final ChatHistory HISTORY = new ChatHistory();

    /** What a held-back line is remembered as — never its words. */
    static final String HELD_MARK = "[held back by the chat sweep]";

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onChat(ServerChatEvent event) {
        ServerPlayer p = event.getPlayer();
        if (p == null) {
            return;
        }
        if (!apply(p, SafetyChatSweep.Surface.CHAT, event.getRawText(), "")) {
            event.setCanceled(true);
            return;
        }
        // step 4 (docs/OPEN_DOOR_PLAN.md §11.3): on a world whose chat stays with its sender
        // (pseudo-single-player, any age), the line goes back to HIM and to nobody else.
        // AFTER the sweep, so a held-back line is held back and the strikes still count.
        if (toSelf(p)) {
            event.setCanceled(true);
            p.sendSystemMessage(Component.translatable("chat.type.text", p.getDisplayName(),
                    Component.literal(event.getRawText())));
            if (TOLD_PRIVATE.add(p.getUUID())) {
                p.sendSystemMessage(Component.literal(com.moddingfromamod.safety.ChatWords.sayChatIsPrivate())
                        .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            }
        }
    }

    /** Who has been told, this server run, that chat here stays with him. */
    static final java.util.Set<java.util.UUID> TOLD_PRIVATE = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Chat stays with its sender for this player here: the world says so, and he is no operator. */
    static boolean toSelf(ServerPlayer p) {
        return com.moddingfromamod.world.WorldPeople.current().chatToSelf() && !p.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
    }

    /**
     * step 4: a sign, a book or an anvil name — text a player PLACES — on a world whose
     * {@code people.placedText} is {@code sweep}. HERE, beside the chat sweep, so this file stays the
     * chat's one caller of the lexicon (CHILD_SAFETY.md's choke-point census). True when it may stand.
     * A wellbeing phrasing is never refused, and leaves a grown-up note.
     *
     * @param what "sign", "book" or "name" — said back to him
     */
    public static boolean placed(ServerPlayer p, String what, String text) {
        if (p == null || text == null || text.isBlank()) {
            return true;
        }
        if (!com.moddingfromamod.world.WorldPeople.current().placedText() || p.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)) {
            return true;
        }
        PlacedText.Verdict v;
        try {
            v = PlacedText.check(text, SafetyLexicon.shared());
        } catch (RuntimeException e) {
            return true;    // the floor failing never stops a child building
        }
        if (v.wellbeing()) {
            String world = WorldFacts.current().name();
            ActionLog.grownUpNote("", p.getScoreboardName() + " wrote on a " + what
                    + (world.isEmpty() ? "" : " on " + world) + ": " + text.strip());
        }
        if (!v.refused()) {
            return true;
        }
        p.sendSystemMessage(Component.literal(PlacedText.say(v.act(), what)).withStyle(ChatFormatting.AQUA));
        LOGGER.info("placed text held back: {} on a {} ({})", p.getScoreboardName(), what, v.act());
        return false;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onCommand(CommandEvent event) {
        ServerPlayer p = event.getParseResults().getContext().getSource().getPlayer();
        if (p == null) {
            return;
        }
        Optional<SafetyChatSweep.Spoken> s = SafetyChatSweep.spoken(event.getParseResults().getReader().getString());
        if (s.isEmpty()) {
            return;
        }
        // step 4: where chat stays with its sender, a private message or an emote has no one to
        // reach — refused before the sweep, with the reason, and never sent.
        if (toSelf(p)) {
            event.setCanceled(true);
            p.sendSystemMessage(Component.literal(com.moddingfromamod.safety.ChatWords.sayNoMessages()).withStyle(ChatFormatting.AQUA));
            return;
        }
        if (!apply(p, s.get().surface(), s.get().text(), s.get().to())) {
            event.setCanceled(true);
        }
    }

    /** Decide, act, remember. True when the message goes out. */
    static boolean apply(ServerPlayer p, SafetyChatSweep.Surface surface, String text, String to) {
        long now = System.currentTimeMillis();
        SafetyChatSweep.Decision d;
        try {
            d = SafetyChatSweep.decide(text, WorldPeople.current().chatSweep(), SafetyLexicon.shared(),
                    STRIKES, p.getUUID(), now);
        } catch (RuntimeException e) {
            // The floor failing must never silence a whole server's chat: it goes out, unswept.
            return true;
        }
        if (d.logAction() != null) {
            ActionLog.safety("", "chat", d.verdict(), d.logAction(), 0L, 0.0, "lexicon", null,
                    surface.label(), 0, false, !d.tell().isEmpty(), p.getUUID().toString());
        }
        if (d.note()) {
            String world = WorldFacts.current().name();
            ActionLog.grownUpNote("", p.getScoreboardName() + " said in "
                    + surface.label() + (world.isEmpty() ? "" : " on " + world) + ": " + text);
        }
        if (!d.tell().isEmpty()) {
            p.sendSystemMessage(Component.literal(d.tell()).withStyle(
                    d.act() == SafetyChatSweep.Act.WELLBEING ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.AQUA));
        }
        String name = p.getScoreboardName();
        HISTORY.said(name, surface.label(), d.deliver() ? text : HELD_MARK, to, now);
        return d.deliver();
    }

    /**
     * How many of a report's lines were held back, or would be by the lexicon (a world in shadow or
     * off) — here, beside the sweep, so this file stays the chat's ONE caller of the lexicon
     * (CHILD_SAFETY.md's choke-point census).
     */
    static int flagged(java.util.List<ChatHistory.Line> lines) {
        int n = 0;
        SafetyLexicon lex = SafetyLexicon.shared();
        for (ChatHistory.Line l : lines) {
            if (HELD_MARK.equals(l.text()) || lex.check(l.text())
                    .map(x -> x.overall() == com.moddingfromamod.safety.SafetyVerdict.Band.CLEARLY).orElse(false)) {
                n++;
            }
        }
        return n;
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        TOLD_PRIVATE.clear();
        STRIKES.clear();
        HISTORY.clear();
        PlayerReports.LIMITS.clear();
    }
}
