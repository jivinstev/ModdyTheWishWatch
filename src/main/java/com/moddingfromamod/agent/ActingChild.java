package com.moddingfromamod.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.moddingfromamod.marshal.PlayerKind;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>The one place a tool asks "which child am I acting for".</b>
 *
 * <p>Thirty-odd sites answered that with
 * {@code server.getPlayerList().getPlayers().stream().findFirst()}, which is <b>join order</b>: with
 * one child always the right child, with two unrelated to who wished, and with a camera client in
 * the world potentially not a child at all. Those sites are not wrong about anything they can see —
 * they are a half-finished migration, and the correct form already existed three files away
 * ({@code FinishWishTool} resolves {@code run.playerId()}).
 *
 * <p>This is that form, once, so the next tool inherits it instead of re-deriving it. The rule
 * itself is {@link PlayerKind#acting} — pure, Minecraft-free and unit-tested; everything here is
 * plumbing that turns a {@code MinecraftServer} into the two lists it wants.
 *
 * <h2>Two answers, by what the tool does (an earlier issue's decision 1)</h2>
 *
 * <ul>
 *   <li>{@link #person} — the owner or NOBODY. For anything done to or about a child's own body,
 *       bag or history: handing him items, moving him, reading his state, borrowing his eyes. When
 *       the wish's owner has LEFT, another child is never used instead — measured before this:
 *       Alice logged off mid-wish and her wished diamonds went into Bob's bag
 *       ({@code OwnerOfflineGameTest}).</li>
 *   <li>{@link #of} — for things PLACED in the world where the wish was aimed (a build, a creature,
 *       a picture, a datapack). Those still go ahead without her, so what she asked for is standing
 *       when she comes back; the anchor is the wish's locked focus, not anybody's body.</li>
 * </ul>
 *
 * <p>With no owner named at all (a probe, a console) both answer as before: the sole child.
 *
 * <p><b>A worker whose own wish has ENDED acts for nobody</b>. Its run is gone, so "no owner named"
 * used to read as a probe's call and answered the first child online: a Builder creature wish stopped mid-sculpt
 * was conjured in front of another child. A stopped wish places nothing more, for anyone.
 */
public final class ActingChild {

    /**
     * The headless probe's {@code FakePlayer}, when one is wishing (Gate C½). Not on the player
     * list, so it is offered only when no child is — see
     * {@link PlayerKind#acting(UUID, List, java.util.Collection, UUID)}.
     */
    private static volatile ServerPlayer standIn;

    private ActingChild() {
    }

    /** Register (or with null, forget) the stand-in child for a world with nobody in it. */
    public static void standIn(ServerPlayer player) {
        standIn = player;
    }

    /** The stand-in registered now, or null — so a borrower can refuse to replace someone else's. */
    public static ServerPlayer currentStandIn() {
        return standIn;
    }

    /**
     * The child the current wish belongs to, or the best available stand-in.
     *
     * <p>The owner comes from {@link AgentContext#currentRun()}, which resolves the calling
     * worker's own run and <b>refuses to guess</b> when two are live — so a null owner here means
     * either nothing is in flight or nothing said which, and the fallback below is what answers.
     */
    public static ServerPlayer of(MinecraftServer server) {
        if (AgentContext.ownWishEnded()) {
            return null;   // its wish is over: nothing more is placed, and never at somebody else
        }
        var run = AgentContext.currentRun();
        return of(server, run == null ? null : run.playerId());
    }

    /** The same, for a caller that already knows whose wish this is. */
    public static ServerPlayer of(MinecraftServer server, UUID owner) {
        return resolve(server, owner, inTheWorld(standIn));
    }

    /**
     * <b>The wish's own child, or nobody</b> — for anything done to or about a PERSON. See the
     * class note; the rule is {@link PlayerKind#owned}.
     */
    public static ServerPlayer person(MinecraftServer server) {
        if (AgentContext.ownWishEnded()) {
            return null;
        }
        var run = AgentContext.currentRun();
        return resolveOwned(server, run == null ? null : run.playerId(), inTheWorld(standIn));
    }

    /**
     * Why {@link #person} answered nobody, in one sentence the agent can act on — so it does not
     * retry at someone else, and does not read "nobody is here" when another child plainly is.
     */
    public static String whyNobody(MinecraftServer server, String nobodyAtAll) {
        return whyNobody(server, null, nobodyAtAll);
    }

    /**
     * {@link #whyNobody(MinecraftServer, String)} for a named PERSON tool, which also records the
     * refusal ({@link ActionLog#reach}, {@code why=owner_left}) when it is the owner-left kind — so
     * the Wish stats panel can say how often a wish outlived the child who made it.
     */
    public static String whyNobody(MinecraftServer server, String tool, String nobodyAtAll) {
        var run = AgentContext.currentRun();
        if (run != null && run.playerId() != null && server != null
                && !server.getPlayerList().getPlayers().isEmpty()) {
            if (tool != null) {
                ActionLog.reach(tool, "refused", "owner_left", "", null,
                        com.moddingfromamod.wish.CameraPresence.childrenOnline(server));
            }
            return "The child who made this wish has left the world, so this is not done for "
                    + "anyone else in their place. Finish what can be built where the wish was "
                    + "aimed, and tell them about the rest when they come back.";
        }
        return nobodyAtAll;
    }

    /**
     * <b>A child who is really ONLINE — never the stand-in.</b> For whose-eyes questions
     * ({@code Viewpoint}): a {@code FakePlayer} has no client, so a photograph through it would
     * wait out its whole timeout for a frame nobody can send.
     */
    public static ServerPlayer online(MinecraftServer server) {
        if (AgentContext.ownWishEnded()) {
            return null;
        }
        var run = AgentContext.currentRun();
        // OWNED, not acting: borrowing a child's eyes freezes and moves HIM, so a wish whose owner
        // has left must never borrow another child's instead (decision 1).
        return resolveOwned(server, run == null ? null : run.playerId(), null);
    }

    /**
     * The stand-in, or null when he has LEFT the world. A stand-in is registered by whoever borrows him and
     * forgotten by them too; when the forgetting is missed, the registration outlives the body, and every tool that
     * resolves "the child" placed, summoned and teleported round a player who no longer exists — seen in full Gate B
     * runs as a mock its own test had already removed ({@code removed=UNLOADED_WITH_PLAYER}). A FakePlayer stand-in
     * (a test probe) is never added to a level and so is never removed: this asks only "has he gone".
     */
    static ServerPlayer inTheWorld(ServerPlayer p) {
        return p == null || p.isRemoved() ? null : p;
    }

    private static ServerPlayer resolveOwned(MinecraftServer server, UUID owner, ServerPlayer fake) {
        if (server == null) {
            return null;
        }
        List<ServerPlayer> online = server.getPlayerList().getPlayers();
        List<UUID> ids = new ArrayList<>(online.size());
        for (ServerPlayer p : online) {
            ids.add(p.getUUID());
        }
        UUID chosen = PlayerKind.owned(owner, ids, PlayerKind.serviceClients(),
                fake == null ? null : fake.getUUID());
        if (chosen == null) {
            return null;
        }
        if (fake != null && chosen.equals(fake.getUUID())
                && fake.level() instanceof ServerLevel level && level.getServer() == server) {
            return fake;
        }
        for (ServerPlayer p : online) {
            if (chosen.equals(p.getUUID())) {
                return p;
            }
        }
        return null;
    }

    private static ServerPlayer resolve(MinecraftServer server, UUID owner, ServerPlayer fake) {
        if (server == null) {
            return null;
        }
        List<ServerPlayer> online = server.getPlayerList().getPlayers();
        List<UUID> ids = new ArrayList<>(online.size());
        for (ServerPlayer p : online) {
            ids.add(p.getUUID());
        }
        UUID chosen = PlayerKind.acting(owner, ids, PlayerKind.serviceClients(),
                fake == null ? null : fake.getUUID());
        if (chosen == null) {
            return null;
        }
        if (fake != null && chosen.equals(fake.getUUID())
                && fake.level() instanceof ServerLevel level && level.getServer() == server) {
            return fake;
        }
        for (ServerPlayer p : online) {
            if (chosen.equals(p.getUUID())) {
                return p;
            }
        }
        return null;
    }

    /**
     * Everyone in the world who is a child.
     *
     * <p>For the sites that mean <i>the room</i> rather than <i>a child</i> — a world-wide effect,
     * a reload, a registry sync. Those are correctly global (D3), and they should still not be
     * addressing the mod's own camera.
     */
    public static List<ServerPlayer> all(MinecraftServer server) {
        if (server == null) {
            return List.of();
        }
        List<ServerPlayer> kids = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (PlayerKind.isChild(p.getUUID())) {
                kids.add(p);
            }
        }
        return kids;
    }
}
