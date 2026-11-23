package com.moddingfromamod.marshal;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>Is this player a child, or a client the mod is running for its own purposes?</b>
 *
 * <p>{@link WishAudience} answers <i>"of the N children, which one asked"</i> — a <b>selector</b>.
 * This answers a question that comes before it: <i>"is this a child at all"</i> — a <b>filter</b>.
 * They are complementary and neither substitutes for the other. Build only the selector and the
 * thirty-odd {@code getPlayers().stream().findFirst()} sites still cheerfully hand a wish to a
 * camera; build only the filter and two children still read each other's narration.
 *
 * <p><b>Why a positive registry rather than a test on the player.</b> The tempting filter is
 * {@code Send.canReceive} — a mock has no channel, so it would be caught. It is the wrong question
 * and it fails on the case this class exists for: <b>the camera client is a real connected client
 * with a real channel</b>, indistinguishable from a child by anything it can be asked. The mod is
 * the thing that knows, because the mod launched it, so the mod says so.
 *
 * <p><b>A GameTest mock is deliberately NOT listed here, and that is a decision.</b> A mock stands
 * in for a child; that is its whole purpose, and several tests drive a real tool through one. Its
 * failure mode — outliving the test that made it — is a <b>liveness</b> problem, not an identity
 * one, and it is fixed at the other end: {@code MockPlayerNetwork.inLevel} retires the mock when
 * its own test finishes. Listing mocks here would fight that fix and break every test whose
 * mock is legitimately the child.
 *
 * <p><b>Registration happens BEFORE the join, not after.</b> An offline-mode UUID is derived from
 * the name, so a camera can be declared a non-child while it is still a string in a launch script.
 * Anything that learns the role from a handshake leaves the client a child for its first few
 * ticks — which is precisely the window a tool asks in.
 */
public final class PlayerKind {

    /**
     * Every client the mod is running for itself. Process-wide, because a camera outlives any one
     * wish and every tool must agree about it; small, because there is one.
     */
    private static final Set<UUID> SERVICE = ConcurrentHashMap.newKeySet();

    private PlayerKind() {
    }

    /**
     * The offline-mode UUID Minecraft derives from a username.
     *
     * <p>Mirrors {@code Player.createPlayerUUID} / {@code UUIDUtil.createOfflinePlayerUUID} without
     * importing Minecraft, so a launcher can register the camera before the server has ever heard
     * of it. Keep this in step with vanilla; it has been stable for many versions, and a GameTest
     * asserts our answer equals vanilla's rather than trusting that.
     */
    public static UUID offlineId(String username) {
        return UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + (username == null ? "" : username))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Declare this player to be the mod's own client rather than a child. Idempotent. */
    public static void registerServiceClient(UUID id) {
        if (id != null) {
            SERVICE.add(id);
        }
    }

    /** Undo {@link #registerServiceClient}. Idempotent. */
    public static void forgetServiceClient(UUID id) {
        if (id != null) {
            SERVICE.remove(id);
        }
    }

    /** Everyone currently declared a service client — a copy, safe to iterate. */
    public static Set<UUID> serviceClients() {
        return Set.copyOf(SERVICE);
    }

    /** Forget every declared service client. For tests, so one cannot leak into the next. */
    public static void forgetAllServiceClients() {
        SERVICE.clear();
    }

    /** True unless this player is one of the mod's own clients. */
    public static boolean isChild(UUID id) {
        return isChild(id, SERVICE);
    }

    /**
     * The pure form: the rule with the registry passed in.
     *
     * <p>Every decision below takes its exclusion set as an argument so the rule itself holds no
     * state — a test asks it a question rather than arranging a process.
     */
    public static boolean isChild(UUID id, Collection<UUID> serviceClients) {
        return id != null && (serviceClients == null || !serviceClients.contains(id));
    }

    /** The children among these players, in the order given. Never null. */
    public static List<UUID> children(List<UUID> online, Collection<UUID> serviceClients) {
        if (online == null) {
            return List.of();
        }
        Set<UUID> seen = new LinkedHashSet<>();
        for (UUID id : online) {
            if (isChild(id, serviceClients)) {
                seen.add(id);
            }
        }
        return List.copyOf(seen);
    }

    /**
     * <b>The child a tool should act on, with today's behaviour as the floor.</b>
     *
     * <p>Three steps, in order, and the third one is the point of this method existing:
     *
     * <ol>
     *   <li>the <b>owner</b>, when the calling wish could be identified and he is here — the answer
     *       D9 wants, and the only one that is right with two children;</li>
     *   <li>the <b>sole child</b>, when there is exactly one — unambiguous, and the case every
     *       single-child world and almost every GameTest is in;</li>
     *   <li>otherwise the <b>first child</b> in join order.</li>
     * </ol>
     *
     * <p>⚠ <b>Step 3 is deliberately today's behaviour, minus service clients.</b> The honest answer
     * for an unidentifiable caller with two children in the world is <i>refuse</i> — see
     * {@link WishAudience#actingPlayer}, which does exactly that for narration. But refusing is a
     * <b>policy change per tool</b> (a build should still rise where it was aimed; a sword must not
     * be handed to the wrong boy), and folding it into the same edit that installs the seam would
     * make thirty-odd sites change behaviour at once with nothing to attribute a moved gate to. So
     * this method is a pure seam: the only player it newly excludes is one the mod launched itself.
     * The refusals land per category, with a gate each.
     */
    /**
     * <b>The same, with a STAND-IN for a world that has no child in it at all</b>.
     *
     * <p>The headless probe (Gate C½) wishes as a NeoForge {@code FakePlayer}, which is never on the
     * player list — so {@link #acting(UUID, List, Collection)} answered null and every tool that
     * acts for a child refused: {@code conjure_at} failed 7 of 7, and on a creature wish the agent
     * abandoned the whole wish. The stand-in is used only when <b>no child is online</b> and the
     * wish is the stand-in's own (or unattributed), so a real child always wins and a wish made by
     * a real child can never land on the probe.
     */
    public static UUID acting(UUID owner, List<UUID> online, Collection<UUID> serviceClients,
            UUID standIn) {
        UUID chosen = acting(owner, online, serviceClients);
        if (chosen != null || standIn == null) {
            return chosen;
        }
        return owner == null || owner.equals(standIn) ? standIn : null;
    }

    /**
     * <b>The owner or NOBODY</b> — for anything done to or about a child's own body, bag or history
     * (an earlier issue's decision 1).
     *
     * <p>{@link #acting} falls through to "the first child" when the owner is not here, which is
     * right for an UNIDENTIFIED caller in a one-child world and exactly wrong when the owner is
     * KNOWN and has left: measured, Alice's wished diamonds went into Bob's bag the moment she
     * logged off. So when the wish names its owner, only the owner answers — or the stand-in, when
     * the stand-in IS the owner (Gate C½). With no owner at all it is {@link #acting}, unchanged, so
     * a probe or a console caller behaves as before.
     *
     * <p>Things PLACED in the world where the wish was aimed (a build, a creature, a picture) keep
     * using {@link #acting}: a build she asked for should be standing when she comes back.
     */
    public static UUID owned(UUID owner, List<UUID> online, Collection<UUID> serviceClients,
            UUID standIn) {
        if (owner == null) {
            return acting(owner, online, serviceClients, standIn);
        }
        if (children(online, serviceClients).contains(owner)) {
            return owner;
        }
        return owner.equals(standIn) ? standIn : null;
    }

    public static UUID acting(UUID owner, List<UUID> online, Collection<UUID> serviceClients) {
        List<UUID> kids = children(online, serviceClients);
        if (owner != null && kids.contains(owner)) {
            return owner;
        }
        return kids.isEmpty() ? null : kids.get(0);
    }
}
