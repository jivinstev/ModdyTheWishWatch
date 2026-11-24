package com.moddingfromamod.testing;

import net.minecraft.network.chat.Component;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;

import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.registration.ChannelAttributes;
import net.neoforged.neoforge.network.registration.NetworkChannel;
import net.neoforged.neoforge.network.registration.NetworkPayloadSetup;

/**
 * Make a GameTest's mock player able to be sent packets, so a runtime test can involve a player.
 *
 * <h2>The problem</h2>
 *
 * {@code helper.makeMockServerPlayerInLevel()} puts a player in the real player list with no real
 * connection. Any mod that broadcasts to all players then kills the server on the next tick:
 *
 * <pre>
 * Create CommonEvents.onServerTick → ServerChainConveyorHandler.sync
 *   → PacketDistributor.sendToAllPlayers → PlayerList.broadcastAll
 *   → NetworkRegistry.checkPacket   ✗ UnsupportedOperationException
 * </pre>
 *
 * That is Create being ordinary, not Create being modified — we have no mixins and touch it only by
 * reflection, and it has been seen from two of its subsystems. Gate B runs WITH Create deliberately,
 * so it made a whole class of runtime test impossible: anything about progress bars, HUD state or
 * per-player behaviour had to be demoted to a unit test or dropped.
 *
 * <h2>The shim</h2>
 *
 * {@code NetworkRegistry.hasChannel} asks the connection for its {@link NetworkPayloadSetup} and
 * does nothing with it but {@code containsKey}. So this hands the mock connection a setup whose maps
 * simply answer <b>yes</b> — no enumeration of anyone's payload ids, which is what stops it going
 * stale as mods add channels. The packets still go nowhere; they just stop throwing on the way.
 *
 * <h2>Why this is honest and not a cheat</h2>
 *
 * It changes what a MOCK is capable of, not what the game does. A real client always has these
 * channels — that is precisely why this never fires in play — so the shim moves the mock closer to a
 * real player rather than further away. It is only reachable from test code, and it fails loudly
 * rather than silently if NeoForge moves the API, because a quietly-inert shim would put the crash
 * back with no explanation attached.
 */
public final class MockPlayerNetwork {

    /** The one profile name this shim ever creates — what clearStaleMocks matches on. */
    public static final String MOCK_NAME = com.moddingfromamod.seam.MockPlayers.MOCK_NAME;


    private MockPlayerNetwork() {
    }

    /** A map that claims to contain everything, and holds nothing. */
    private static final Map<ResourceLocation, NetworkChannel> EVERY_CHANNEL =
            new AbstractMap<>() {
                @Override
                public Set<Entry<ResourceLocation, NetworkChannel>> entrySet() {
                    return Set.of();
                }

                @Override
                public boolean containsKey(Object key) {
                    return true;
                }
            };

    private static final Map<ConnectionProtocol, Map<ResourceLocation, NetworkChannel>> EVERY_PROTOCOL =
            new AbstractMap<>() {
                @Override
                public Set<Entry<ConnectionProtocol, Map<ResourceLocation, NetworkChannel>>> entrySet() {
                    return Set.of();
                }

                @Override
                public Map<ResourceLocation, NetworkChannel> get(Object key) {
                    return EVERY_CHANNEL;   // getChannels() uses getOrDefault, so get() decides
                }

                @Override
                public boolean containsKey(Object key) {
                    return true;
                }
            };

    /**
     * A mock player that can be sent packets, shimmed BEFORE it enters the world.
     *
     * <p><b>This is the whole fix, and why {@link #acceptEverything} on its own was not.</b>
     * {@code makeMockServerPlayerInLevel} builds the connection and calls {@code placeNewPlayer}
     * before it returns — and placing a player is itself a broadcast point. By the time a caller
     * gets the player back and shims it, the join has already gone out to every mod that listens,
     * and Create has already thrown. Shimming after the fact can only ever fix the SECOND packet.
     *
     * <p>So the five lines of the vanilla helper are reproduced here with the payload setup attached
     * to the {@code Connection} in the middle, before the player is placed. All public API.
     */
    /**
     * <b>Remove any mock left behind by an earlier test — call this FIRST, not last.</b>
     *
     * <p>Teardown that runs at the END of a test only runs when the test PASSES. A failing test
     * therefore leaks its mock into the shared player list, and the next victim is somebody else:
     * {@code modify_mob} resolves "the child" as the first player in that list, so it went looking
     * around a plot thousands of blocks away and four correct, unrelated tests went red reporting
     * "nothing nearby". Nothing in the failure named players.
     *
     * <p>Cleaning up on ENTRY is idempotent and cannot be skipped by any failure mode, so it fixes
     * the leak rather than narrowing it. Cheap: it matches only this shim's own profile name, so a
     * real player in a real world is never touched.
     *
     * @return how many stale mocks were cleared
     */
    /**
     * <b>…and the one leak entry-cleanup structurally cannot reach: the LAST mock of the run.</b>
     *
     * <p>{@link #clearStaleMocks} is lazy — it tidies up the previous test's mock when the next one
     * starts. Whichever mock-using test runs last therefore leaves its player in the list for the
     * whole rest of the server's life, and "the rest of the run" is every batch that follows.
     *
     * <p><b>Measured, twice, on consecutive full Gate B runs:</b> 20 mock logins, one
     * {@code left the game}, and that one only at shutdown. Seventeen later tests went red —
     * sixteen mob tests reporting <i>"No mobs within 24 blocks of the child"</i> about plots that
     * had mobs in them, and the item-camera test failing because its whole premise is that a
     * GameTest world has NO player, so reaching "nobody" is what proves the camera recognised the
     * subject. With a mock in the list it got past that check and took the shot. Not one of the
     * seventeen messages mentions a player.
     *
     * <p><b>Why a sweep rather than teardown at 16 call sites.</b> Teardown that runs at the end of
     * a test only runs when the test PASSES — the exact reasoning that made cleanup happen on ENTRY
     * in the first place — so adding it back per-site would reintroduce the leak for any
     * failing test and would have to be remembered by every future one. This repo already records
     * that a step you must remember is not a control.
     *
     * <p><b>Why WALL CLOCK and not ticks.</b> A GameTest server ticks as fast as it can, so a tick
     * count has no fixed relationship to how long a test has been running and cannot separate "in
     * use" from "abandoned". Real seconds can: every {@code @GameTest} here is capped by
     * {@code timeoutTicks} and none holds a mock for anything like {@value #ABANDONED_MS}ms, while
     * the leaked one in both measured runs sat there for over nine minutes. Deliberately generous
     * — sweeping a live mock would fail a test that was working, which is strictly worse than the
     * leak.
     */
    // REGISTERED ON DEMAND, from inLevel, rather than by a subscriber annotation on the class. This
    // class ships in the jar, and a real game has no mock players — so an annotation would add a
    // per-tick listener to every player's world to watch for something that can only exist inside a
    // GameTest. Registering the first time a mock is actually created keeps the shipped mod exactly
    // as it was, which is the same "prove the negatives" rule the carrier route is held to.
    static void sweepAbandonedMocks(
            net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (BORN.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        // COLLECT, THEN REMOVE. remove() takes the player out of the very list being iterated, so
        // removing inline throws ConcurrentModificationException out of the server tick loop and
        // crashes the whole run — measured, on the first run this sweep was live: it found exactly
        // the right player and killed the server taking it out. Same family as the warning in
        // remove()'s own javadoc about getPlayers() being an unmodifiable VIEW of the real list.
        java.util.List<ServerPlayer> abandoned = event.getServer().getPlayerList().getPlayers()
                .stream()
                .filter(MockPlayerNetwork::isMock)
                .filter(p -> {
                    Long born = BORN.get(p.getUUID());
                    return born != null && (now - born) / 1_000_000L >= ABANDONED_MS;
                })
                // OLD IS NOT ABANDONED WHILE ITS TEST RUNS. A long test outlives any wall-clock
                // limit on a slow server: the carrier probe's child was swept 30 s in, and every
                // pet after it had nobody to follow (MockSweepGameTest). An owned mock is retired
                // by its own test's end; this sweep is for the rest.
                .filter(p -> {
                    var owner = OWNER.get(p.getUUID());
                    return owner == null || owner.isDone();
                })
                .toList();
        for (ServerPlayer p : abandoned) {
            System.out.println("MockPlayerNetwork: swept a mock abandoned for over "
                    + ABANDONED_MS + "ms — no test can still be using it, and leaving it in the "
                    + "player list makes every later test that resolves 'the child' look at it");
            remove(p);
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean ARMED =
            new java.util.concurrent.atomic.AtomicBoolean();

    private static void armTheSweep(net.minecraft.server.MinecraftServer server) {
        if (server != null && ARMED.compareAndSet(false, true)) {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                    net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class,
                    MockPlayerNetwork::sweepAbandonedMocks);
        }
    }

    /** For {@code MockSweepGameTest}: make a mock look older than it is, without waiting. */
    static void ageForTest(java.util.UUID mock, long ms) {
        BORN.computeIfPresent(mock, (k, born) -> born - ms * 1_000_000L);
    }

    /** How long a mock may live before it is certainly abandoned. See {@link #sweepAbandonedMocks}. */
    static final long ABANDONED_MS = 30_000L;

    /** When each mock was created, so the sweep can tell "in use" from "left behind". */
    private static final Map<java.util.UUID, Long> BORN = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Which test made each mock. A mock whose test is still RUNNING is not stale, however it got
     * here: tests in one batch run concurrently, so a sibling calling {@link #clearStaleMocks} at
     * its own start used to take out a mock another test was halfway through using. Measured on
     * the 1.21.4 build: the pictureFork batch cleared Bob mid-refinement, and his test
     * failed with "Nobody is in the world to hang the picture", naming no sibling at all. A mock
     * whose test has FINISHED is already removed by {@link #retireWhenItsOwnTestEnds}; what is
     * left for this sweep is a mock with no owner or a finished one.
     */
    private static final Map<java.util.UUID, net.minecraft.gametest.framework.GameTestInfo> OWNER =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static int clearStaleMocks(net.minecraft.gametest.framework.GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        java.util.List<ServerPlayer> stale = server.getPlayerList().getPlayers().stream()
                .filter(MockPlayerNetwork::isMock)
                .filter(p -> {
                    var owner = OWNER.get(p.getUUID());
                    return owner == null || owner.isDone();
                })
                .toList();
        stale.forEach(MockPlayerNetwork::remove);
        if (!stale.isEmpty()) {
            System.out.println("MockPlayerNetwork: cleared " + stale.size()
                    + " mock player(s) left behind by an earlier test");
        }
        return stale.size();
    }

    /**
     * <b>Run a body with this player's payload channels REMOVED — what a vanilla client looks like
     * to the server — and always put them back.</b>
     *
     * <p>Everything else in this class exists to make a mock able to receive anything. This is the
     * deliberate inverse, and it is the only channel-less listener a headless gate can produce, so
     * it is what makes {@code Send.all}'s reason for existing testable rather than merely reasoned.
     *
     * <p>⚠ <b>It is scoped, and the first version was not — that version killed a whole Gate B
     * run.</b> Joining a channel-less player to the real player list is not a local act: other mods
     * broadcast on join, and {@code PacketDistributor} <i>throws</i> rather than skipping. Measured
     * — <b>Create</b> tried to send {@code create:sync_rail_graph} to it inside
     * {@code placeNewPlayer} and took the server down, with no result line at all. So the player
     * joins normally and loses its channels only for the length of a synchronous body, where no
     * tick boundary can let anyone else notice.
     *
     * <p>That crash is also the finding: <b>any mod that broadcasts will crash a Lite server the
     * moment a vanilla client joins</b>, and Create is in the child's world. Ours are guarded now;
     * theirs are not, and cannot be.
     */
    public static void withoutChannels(ServerPlayer player, Runnable body) {
        var connection = player.connection.getConnection();
        try {
            ChannelAttributes.setPayloadSetup(connection, new NetworkPayloadSetup(Map.of()));
            body.run();
        } finally {
            ChannelAttributes.setPayloadSetup(connection, new NetworkPayloadSetup(EVERY_PROTOCOL));
        }
    }

    public static ServerPlayer inLevel(net.minecraft.gametest.framework.GameTestHelper helper) {
        return inLevel(helper, true, MOCK_NAME);
    }

    /**
     * A mock with its OWN name — for a test whose subject tells players apart by name, as a
     * command selector does ({@code @a[name=Alice]}). Every other mock shares {@link #MOCK_NAME},
     * which makes two of them indistinguishable to Minecraft itself. Swept and retired exactly
     * like the rest: a mock is recognised by its record here, not by its name.
     */
    public static ServerPlayer inLevel(net.minecraft.gametest.framework.GameTestHelper helper,
            String name) {
        return inLevel(helper, true, name);
    }

    /** Chat lines (not the action bar) sent to each mock, in order. See {@link #chat}. */
    private static final Map<java.util.UUID, java.util.List<String>> CHAT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Every CHAT line this mock has been sent so far, as plain text, oldest first — a copy.
     * Action-bar lines are excluded: they vanish on their own and are not what "too chatty" means.
     */
    public static java.util.List<String> chat(ServerPlayer player) {
        java.util.List<String> lines = player == null ? null : CHAT.get(player.getUUID());
        if (lines == null) {
            return java.util.List.of();
        }
        synchronized (lines) {
            return java.util.List.copyOf(lines);
        }
    }

    /**
     * True for a gate mock: {@code WatchPinService} pins a mock only when a test opts it in,
     * because hundreds of GameTests hand a mock something in hotbar slot 1.
     */
    public static boolean isMockPlayer(ServerPlayer p) {
        return p != null && isMock(p);
    }

    /** True for a player this class made, whatever it is called. */
    private static boolean isMock(ServerPlayer p) {
        return com.moddingfromamod.seam.MockPlayers.is(p);   // shipped code asks the seam directly
    }

    /** Mocks that answer {@code isCreative()} honestly as false; every other mock is creative. */
    private static final java.util.Set<java.util.UUID> SURVIVAL =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Make a mock a SURVIVAL player: its game mode, and {@code isCreative()}, which every
     * other mock answers {@code true} whatever its mode. A creature that must never hunt a child in
     * creative cannot be shown hunting one in survival without this.
     */
    public static ServerPlayer asSurvival(ServerPlayer mock) {
        SURVIVAL.add(mock.getUUID());
        mock.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        return mock;
    }

    private static ServerPlayer inLevel(net.minecraft.gametest.framework.GameTestHelper helper,
            boolean withChannels) {
        return inLevel(helper, withChannels, MOCK_NAME);
    }

    /**
     * A mock with its own name AND a UUID chosen beforehand — for a test that must declare the
     * player a service client BEFORE it joins, as a real camera is (its offline id is known from
     * its name), so everything {@code placeNewPlayer} broadcasts about it is already judged.
     */
    public static ServerPlayer inLevel(net.minecraft.gametest.framework.GameTestHelper helper,
            String name, java.util.UUID id) {
        return inLevel(helper, true, name, id);
    }

    private static ServerPlayer inLevel(net.minecraft.gametest.framework.GameTestHelper helper,
            boolean withChannels, String name) {
        return inLevel(helper, withChannels, name, java.util.UUID.randomUUID());
    }

    private static ServerPlayer inLevel(net.minecraft.gametest.framework.GameTestHelper helper,
            boolean withChannels, String name, java.util.UUID id) {
        ServerPlayer player = place(helper.getLevel(), withChannels, name, id, helper.testInfo);
        retireWhenItsOwnTestEnds(helper, player);
        return player;
    }

    /**
     * <b>A stand-in child in a RUNNING server, outside any GameTest</b> (the behaviour
     * exercise after a headless grant). It is in the real player list and the level, so it is a
     * selector's {@code @a}/{@code @p}/{@code @e}, a rider and a victim, which a {@code FakePlayer}
     * is not. Not swept and not retired: no test owns it, and the caller {@link #remove}s it.
     * {@code withChannels=false} is a STOCK child, the shipped shape (docs/DEPLOYMENTS.md shape 1):
     * the mod sends him nothing of its own and never borrows his eyes for a photograph.
     */
    public static ServerPlayer inServer(net.minecraft.server.level.ServerLevel level, String name,
            java.util.UUID id, boolean withChannels) {
        return place(level, withChannels, name, id, null);
    }

    /**
     * a real login passes the configuration phase, where NeoForge's {@code ConfigSync} records the
     * connection; a mock skips it. On 21.8 {@code syncPendingConfigs} then THROWS for a plain
     * {@code ServerPlayer} on an unrecorded connection that claims the config channel — and the player
     * {@code PlayerList.respawn} makes after a mock dies is exactly that (measured: the second 1.21.8 Gate B
     * crashed the server at the bag's death test, "configsToSync should contain an entry for player").
     * Recording the connection is what the configuration phase would have done. Test harness only.
     */
    @SuppressWarnings("unchecked")
    private static void registerForConfigSync(Connection connection) {
        try {
            Class<?> sync = net.neoforged.neoforge.network.ConfigSync.class;
            java.lang.reflect.Field lock = sync.getDeclaredField("lock");
            java.lang.reflect.Field map = sync.getDeclaredField("configsToSync");
            lock.setAccessible(true);
            map.setAccessible(true);
            synchronized (lock.get(null)) {
                ((Map<Connection, Map<String, byte[]>>) map.get(null))
                        .put(connection, new java.util.LinkedHashMap<>());
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("MockPlayerNetwork: NeoForge's ConfigSync changed shape — " + e, e);
        }
    }

    private static ServerPlayer place(net.minecraft.server.level.ServerLevel level, boolean withChannels,
            String name, java.util.UUID id, net.minecraft.gametest.framework.GameTestInfo owner) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(id, name), false);
        ServerPlayer player = new ServerPlayer(level.getServer(), level,
                cookie.gameProfile(), cookie.clientInformation()) {
            @Override
            public boolean isSpectator() {
                return false;
            }

            @Override
            public boolean isCreative() {
                return !SURVIVAL.contains(getUUID());
            }

            // WHAT THE CHILD READ. Every chat line and action-bar line funnels through here
            // (sendSystemMessage(Component) and displayClientMessage both delegate to it), so a
            // GameTest can count what was said rather than inferring it from a tool's result.
            @Override
            public void sendSystemMessage(net.minecraft.network.chat.Component message,
                                          boolean overlay) {
                if (!overlay && message != null) {
                    CHAT.computeIfAbsent(getUUID(),
                                    k -> java.util.Collections.synchronizedList(
                                            new java.util.ArrayList<>()))
                            .add(message.getString());
                }
                super.sendSystemMessage(message, overlay);
            }
        };
        if (owner != null) {
            armTheSweep(level.getServer());
            BORN.put(player.getUUID(), System.nanoTime());
            com.moddingfromamod.seam.MockPlayers.mark(player.getUUID());
            OWNER.put(player.getUUID(), owner);
        }
        Connection connection = new Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        registerForConfigSync(connection);
        if (withChannels) {
            ChannelAttributes.setPayloadSetup(connection, new NetworkPayloadSetup(EVERY_PROTOCOL));
        }
        if (owner != null) {
            holdTheSpawn(level.getServer());
        }
        // A player with no saved data starts at world spawn. 1.21.8's placeNewPlayer did that itself; 1.21.9 moved it
        // into the configuration phase's PrepareSpawnTask, which a mock never passes through, so without this every
        // mock logged in at (0, 0, 0). The same placement 1.21.8 made: the overworld's spawn, adjusted for his size.
        net.minecraft.server.level.ServerLevel spawnLevel = level.getServer().overworld();
        player.setServerLevel(spawnLevel);
        player.snapTo(player.adjustSpawnLocation(spawnLevel, spawnLevel.getRespawnData().pos()).getBottomCenter(),
                spawnLevel.getRespawnData().yaw(), 0.0F);
        entitiesLoadedWhereHeLands(level.getServer());
        level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }

    private static volatile java.lang.reflect.Field entityManagerField;
    private static volatile java.lang.reflect.Method processPendingLoads;

    /**
     * <b>: the entities round the held spawn are LOADED before {@code placeNewPlayer} waits for them, so its wait
     * returns at once and can never hang.</b>
     *
     * <p>{@code placeNewPlayer} ends in {@code ServerLevel.waitForChunkAndEntities}: a {@code managedBlock} on the
     * Server thread until the entity sections of the 3x3 chunks round the player are loaded. A chunk's entities are
     * asked for only when its full status is promoted, which is a task on the CHUNK SOURCE's executor, and
     * {@code MinecraftServer.pollTaskInternal} runs those tasks only while the server "has time"
     * ({@code haveTime()}: the clock is before the next tick's deadline). A GameTest server ticks as fast as it can, so
     * it is nearly always behind its deadline and never has time: the promotion never runs, the entities are never
     * asked for, and the wait never ends. No tick comes, so no {@code timeoutTicks} fires. Measured: the first mock of
     * a run, placed the moment {@link #holdTheSpawn} forced the spawn's chunks, hung there (2026-11-20, run 11 of a
     * loop); a returning mock placed on its old island hung on every run.
     *
     * <p>So the harness does the same wait itself, with the one thing vanilla's lacks: it runs the chunk source's tasks
     * too. Bounded by wall clock (a tick count cannot bound a blocked Server thread); past it, the test that placed the
     * mock FAILS naming the chunks, rather than the run sitting silent. Server thread only (a mock is placed from a
     * test body or a tick); a no-op once the spawn's entities are loaded, which is every call after the first.
     */
    private static void entitiesLoadedWhereHeLands(net.minecraft.server.MinecraftServer server) {
        if (!server.isSameThread()) {
            return;
        }
        net.minecraft.server.level.ServerLevel overworld = server.overworld();
        net.minecraft.world.level.ChunkPos c = new net.minecraft.world.level.ChunkPos(overworld.getRespawnData().pos());
        java.util.List<net.minecraft.world.level.ChunkPos> want = new java.util.ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                want.add(new net.minecraft.world.level.ChunkPos(c.x + dx, c.z + dz));
            }
        }
        if (want.stream().allMatch(p -> overworld.areEntitiesLoaded(p.toLong()))) {
            return;
        }
        Object manager;
        try {
            if (entityManagerField == null) {
                java.lang.reflect.Field f = net.minecraft.server.level.ServerLevel.class.getDeclaredField("entityManager");
                f.setAccessible(true);
                java.lang.reflect.Method m = f.getType().getMethod("processPendingLoads");
                processPendingLoads = m;
                entityManagerField = f;
            }
            manager = entityManagerField.get(overworld);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("MockPlayerNetwork: ServerLevel.entityManager changed shape — " + e, e);
        }
        // The wait's own ticket, the one vanilla adds: the 3x3 round the spawn, and the held 5x5 is already forced.
        want.forEach(p -> overworld.getChunk(p.x, p.z));
        long deadline = System.nanoTime() + 120_000_000_000L;
        server.managedBlock(() -> {
            while (overworld.getChunkSource().pollTask()) {
                // the chunk tasks vanilla's wait skips while the server has no time
            }
            try {
                processPendingLoads.invoke(manager);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("MockPlayerNetwork: processPendingLoads failed — " + e, e);
            }
            for (net.minecraft.world.level.ChunkPos p : want) {
                if (!overworld.areEntitiesLoaded(p.toLong())) {
                    if (System.nanoTime() > deadline) {
                        throw new IllegalStateException("MockPlayerNetwork: the spawn's entities at " + p
                                + " did not load in 120 s of wall clock; placing a mock now would block the Server"
                                + " thread for ever in placeNewPlayer (#2604)");
                    }
                    return false;
                }
            }
            return true;
        });
    }

    /** The server whose spawn {@link #holdTheSpawn} already holds. */
    private static volatile net.minecraft.server.MinecraftServer spawnHeldFor;

    /**
     * <b>: keep the chunks a mock is placed in loaded for the whole run, so placing one cannot hang.</b>
     *
     * <p>A mock has no saved data, so {@code placeNewPlayer} snaps it to WORLD SPAWN and then blocks the server
     * thread in {@code ServerLevel.waitForChunkAndEntities} until the chunks and entities there are loaded — a
     * {@code managedBlock} that no GameTest timeout can interrupt, behind a ticket ({@code TicketType.UNKNOWN})
     * that EXPIRES. A GameTest world does not keep its spawn loaded, and once (the nightly regression,
     * 2026-11-11) that wait never ended: 80 minutes, then the build's timeout. Forcing the 5x5 chunks round the
     * spawn (a forced ticket never expires; the player's ±10-block spawn radius plus the wait's one-chunk ring)
     * and loading them HERE, synchronously, once per server, leaves the wait nothing to wait for.
     *
     * <p>Why not land the mock on its own plot instead (tried first): a mock is "the child" to every OTHER test
     * that acts in front of the first player online, and a mock left idle on its plot drew those actions into
     * the test grid — a treehouse went up between a replay test's mock and the hall it was looking at.
     * At the spawn, far from every plot, an idle mock draws nothing anywhere a test looks.
     */
    private static void holdTheSpawn(net.minecraft.server.MinecraftServer server) {
        if (spawnHeldFor == server) {
            return;
        }
        net.minecraft.server.level.ServerLevel overworld = server.overworld();
        net.minecraft.world.level.ChunkPos c = new net.minecraft.world.level.ChunkPos(overworld.getRespawnData().pos());
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                overworld.setChunkForced(c.x + dx, c.z + dz, true);
                overworld.getChunk(c.x + dx, c.z + dz);
            }
        }
        spawnHeldFor = server;
    }

    /**
     * <b>Refuse to run a test whose premise is "nobody is online", when somebody is.</b>
     *
     * <p>Several GameTests spawn their subject at WORLD SPAWN, because the tools resolve "the
     * child" as the first player in the list and <em>with nobody online that means world spawn</em>.
     * That is a claim about the whole server, and a test that creates no player cannot make it
     * true: any mock belonging to any other test silently becomes the child.
     *
     * <p>What it looked like before this existed: <i>"No mobs within 24 blocks of the child"</i>,
     * about a plot with a mob standing in it. True, about the wrong child, and mentioning no
     * player — so it reads as a defect in the tool, or in the reader's own branch. Two spikes
     * independently spent a control run on it before either filed an issue.
     *
     * <p>So it fails here instead, naming the player, where it is, and how far that is from world
     * spawn. Measured with the leak deliberately restored: <i>'mfam-mock' at
     * BlockPos{x=752260, y=-58, z=-8031905}, 8067056 blocks from world spawn</i>. This makes the
     * tests no more robust — they still cannot run with a stray mock about — but it turns a
     * distance into a cause, which is the difference between one line and a control run.
     *
     * <p>Lives here rather than in either test because there are <b>two</b> files with this
     * premise and its own helper for it ({@code ModifyMobGameTest.spawnAtWorldSpawn} and
     * {@code MobIdentityGameTest.arrive}), and the second was found only because the first one's
     * fix left it still failing. A third would otherwise write a third copy.
     */
    public static void requireNobodyIsOnline(
            net.minecraft.gametest.framework.GameTestHelper helper) {
        net.minecraft.server.level.ServerLevel overworld =
                helper.getLevel().getServer().overworld();
        net.minecraft.world.phys.Vec3 spawn = net.minecraft.world.phys.Vec3
                .atBottomCenterOf(overworld.getRespawnData().pos());
        for (ServerPlayer p : helper.getLevel().getServer().getPlayerList().getPlayers()) {
            helper.fail(Component.literal("a player is online, so 'the child' is not world spawn and this test "
                    + "cannot run: '" + p.getGameProfile().name() + "' at "
                    + p.blockPosition() + ", " + (int) p.position().distanceTo(spawn)
                    + " blocks from world spawn. If that name is '" + MOCK_NAME + "' it is another "
                    + "test's mock that outlived it — see MockPlayerNetwork, not the tool."));
        }
    }

    /**
     * <b>Take this mock out again the moment the test that made it finishes — pass, fail OR
     * timeout — and touch nobody else's.</b>
     *
     * <p>This is the fix, and the interesting part is why the two cleanups that already
     * existed could not be it. Both are right about what they do and neither can close the hole:
     *
     * <ul>
     *   <li>{@link #clearStaleMocks} runs on ENTRY, which is a SEQUENTIAL assumption inside a
     *       CONCURRENT framework. Tests in a batch run together, so a sibling calling it deletes
     *       this test's player mid-wait — measured, and it is why two carrier tests already had to
     *       escape into batches of their own. It also cannot reach the LAST mock of a run, because
     *       nothing runs after it to be the next entry.</li>
     *   <li>{@link #sweepAbandonedMocks} is deliberately generous at {@value #ABANDONED_MS}ms,
     *       for the good reason that sweeping a LIVE mock fails a working test. But the default
     *       shard finishes in about three minutes, so a mock created seconds before a mob test is
     *       legitimately not abandoned and stays. Measured on `main`: <b>38 mocks joined, 0
     *       left</b>, and three mob tests red.</li>
     * </ul>
     *
     * <p><b>Ownership is what both are missing.</b> A mock belongs to exactly one test, and that
     * test's own completion is the one event that is both early enough to help and specific enough
     * to be safe. {@code GameTestInfo} notifies its listeners from {@code tick()} the moment
     * {@code isDone()} — on the error path as well as the success one, and a timeout arrives as
     * {@code fail}, so there is no completion path that skips this. Nothing else's mock is
     * touched, so no test needs a batch of its own to be safe from this any more.
     *
     * <p><b>Why here and not at the call sites.</b> Teardown written per test only runs when the
     * test PASSES, which reintroduces the leak for exactly the tests already failing, and has to be
     * remembered by every future one — the rule this repo states as <i>a step you must remember is
     * not a control</i>. Registered inside {@code inLevel}, a mock cannot be created without its
     * own removal being armed in the same statement.
     *
     * <p>The two existing cleanups are deliberately KEPT as backstops rather than deleted with
     * this: they cover a mock made some other way, and removing a working guard in the same change
     * as adding one makes a regression impossible to attribute.
     */
    private static void retireWhenItsOwnTestEnds(
            net.minecraft.gametest.framework.GameTestHelper helper, ServerPlayer player) {
        helper.testInfo.addListener(new Retire(player));
    }

    /**
     * <b>Holds the mock only until it is retired</b> (2026-11-20, docs/GATE_FLAKES_2026-11-20.md §5). The runner keeps every
     * {@code GameTestInfo}, and so every listener, until the run ends ({@code GameTestRunner.allTestInfos}). An anonymous
     * listener that named the player kept the WHOLE mock alive for the rest of Gate B: its advancement progress (about
     * 1,400 entries) and the advancement tree of the reload it saw, ~530 KB each, several hundred of them, a live heap
     * that climbed to 2.1 GB, over the release builder's 2 GB default (the 11-18 nightly OOM). A STATIC class, because an
     * anonymous one keeps a hidden {@code val$player} copy of anything it mentions even after its own field is cleared
     * (measured: the first try at this, which only nulled a field, left the heap dump's path unchanged).
     */
    private static final class Retire implements net.minecraft.gametest.framework.GameTestListener {
        private ServerPlayer held;

        Retire(ServerPlayer player) {
            this.held = player;
        }

        private void retire() {
            ServerPlayer p = held;
            held = null;
            remove(p);
        }

        @Override
        public void testStructureLoaded(net.minecraft.gametest.framework.GameTestInfo info) {
        }

        @Override
        public void testPassed(net.minecraft.gametest.framework.GameTestInfo info,
                net.minecraft.gametest.framework.GameTestRunner runner) {
            retire();
        }

        @Override
        public void testFailed(net.minecraft.gametest.framework.GameTestInfo info,
                net.minecraft.gametest.framework.GameTestRunner runner) {
            retire();
        }

        @Override
        public void testAddedForRerun(net.minecraft.gametest.framework.GameTestInfo before,
                net.minecraft.gametest.framework.GameTestInfo after,
                net.minecraft.gametest.framework.GameTestRunner runner) {
            // A rerun builds a fresh GameTestInfo and runs the body again, so it creates its
            // own mock and arms its own removal. Nothing to do for the one that just ended —
            // it was already removed by testFailed.
        }
    }

    /**
     * Can this player actually be sent a custom payload?
     *
     * <p>The shim's own postcondition, so a test can assert it up front instead of discovering it
     * several ticks later as somebody else's payload name.
     */
    public static boolean canBeSentAnything(ServerPlayer player) {
        return player != null && player.connection != null
                && net.neoforged.neoforge.network.registration.NetworkRegistry.hasChannel(
                        player.connection,
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                                "moddingfromamod", "any_probe_id"));
    }

    /**
     * Take a mock player back out of the world when the test is done.
     *
     * <p>Not optional. Every tool resolves "the child" as the first player in the list, so a mock
     * left behind becomes the child for every test that runs afterwards — which shows up as
     * unrelated tests failing about distances to a player in a plot thousands of blocks away, with
     * nothing pointing back at the test that created it.
     */
    /**
     * Make {@code child} the ACTING child (ActingChild's stand-in) for THIS test only — put back, and
     * the mock taken out of the world, when the test PASSES OR FAILS.
     *
     * <p>Why it exists (2026-10-10, the 18-test cluster): the event tests set the stand-in and only
     * restored it on their success path. When one timed out — the crowd test, which counts creatures
     * that wander — the stand-in stayed on a mock player parked 90,000 blocks away, and every later
     * test that acts "for the child" spawned, summoned and hung pictures round him instead: eighteen
     * red tests across a dozen batches from one flaky one, seen three times (Gate B runs 93b5790f5,
     * c9892e0fd, 6d0498bf0 — the last a release build). Shared state a test changes is put back by a
     * listener, never by the last line of a success branch.
     */
    public static void actingChildFor(net.minecraft.gametest.framework.GameTestHelper helper, ServerPlayer child) {
        ServerPlayer before = com.moddingfromamod.agent.ActingChild.currentStandIn();
        com.moddingfromamod.agent.ActingChild.standIn(child);
        afterTest(helper, () -> {
            if (com.moddingfromamod.agent.ActingChild.currentStandIn() == child) {
                // Never put back a stand-in whose own test has ended: tests in one batch run at once, so "before"
                // may be a mock already retired, which would then read as a child in every later batch.
                com.moddingfromamod.agent.ActingChild.standIn(before != null && !before.isRemoved()
                        && before.level().getServer() != null && before.level().getServer().getPlayerList().getPlayer(before.getUUID()) == before
                        ? before : null);
            }
            remove(child);
            if (com.moddingfromamod.agent.ActingChild.currentStandIn() == child) {
                com.moddingfromamod.agent.ActingChild.standIn(null);
            }
        });
    }

    /**
     * Run {@code cleanup} when THIS test ends — passed, failed, or added for a rerun. Shared state a
     * test changes (a stand-in, a staged event, a forced chunk, a kept creature) is put back here,
     * never on the last line of a success branch, or one timeout poisons every test after it.
     */
    public static void afterTest(net.minecraft.gametest.framework.GameTestHelper helper, Runnable cleanup) {
        helper.testInfo.addListener(new AfterTest(cleanup));
    }

    /** Runs its cleanup once, then lets go of it (and of whatever it closes over) — see {@link Retire}. */
    private static final class AfterTest implements net.minecraft.gametest.framework.GameTestListener {
        private Runnable pending;

        AfterTest(Runnable cleanup) {
            this.pending = cleanup;
        }

        private void runOnce() {
            Runnable r = pending;
            pending = null;
            if (r != null) {
                r.run();
            }
        }

        @Override public void testStructureLoaded(net.minecraft.gametest.framework.GameTestInfo info) {
        }

        @Override public void testPassed(net.minecraft.gametest.framework.GameTestInfo info,
                                         net.minecraft.gametest.framework.GameTestRunner runner) {
            runOnce();
        }

        @Override public void testFailed(net.minecraft.gametest.framework.GameTestInfo info,
                                         net.minecraft.gametest.framework.GameTestRunner runner) {
            runOnce();
        }

        @Override public void testAddedForRerun(net.minecraft.gametest.framework.GameTestInfo a,
                                                net.minecraft.gametest.framework.GameTestInfo b,
                                                net.minecraft.gametest.framework.GameTestRunner r) {
            runOnce();
        }
    }

    public static void remove(ServerPlayer player) {
        if (player == null) {
            return;
        }
        BORN.remove(player.getUUID());
        com.moddingfromamod.seam.MockPlayers.unmark(player.getUUID());
        OWNER.remove(player.getUUID());
        CHAT.remove(player.getUUID());
        // PlayerList.remove(), not getPlayers().remove() — the latter is an UNMODIFIABLE VIEW and
        // throws UnsupportedOperationException. Which then failed the test in its own finally block,
        // left the player in the world anyway, and so caused exactly the pollution this exists to
        // prevent — while the thrown exception named nothing to do with players.
        net.minecraft.server.MinecraftServer server = player.level().getServer();
        try {
            if (server != null) {
                server.getPlayerList().remove(player);
            }
        } catch (RuntimeException ignored) {
            // A mock has no save data or stats to write; if vanilla's teardown objects to that,
            // discarding is still enough to get it out of the world.
        }
        forgetSavedData(server, player.getUUID());
        player.discard();
    }

    /**
     * <b>: a mock leaves no saved player file behind, so the next mock with its UUID is placed at the held
     * spawn like any other and placing it cannot hang.</b>
     *
     * <p>{@code PlayerList.remove} SAVES the player ({@code playerdata/<uuid>.dat}). A test that brings the same
     * child back ({@code IslandReclaimGameTest}: he leaves, his island rests, he comes back) then had vanilla load
     * that file and place him where he LEFT, on his old island, and {@code placeNewPlayer} blocked the server
     * thread in {@code ServerLevel.waitForChunkAndEntities} for chunks whose entities were not loaded. That wait
     * runs the chunk source's tasks only while the server "has time" ({@code MinecraftServer.haveTime}), and a
     * GameTest server ticks as fast as it can, so it never has: the wait never ended, no tick came, the test's
     * {@code timeoutTicks} could not fire and Gate B sat silent until something killed it (77 min, 2026-11-20).
     * {@link #holdTheSpawn} keeps the spawn's chunks and entities loaded for exactly this reason; this keeps every
     * mock going THERE. Deterministic: it hung on every run, at every commit tried.
     */
    private static void forgetSavedData(net.minecraft.server.MinecraftServer server, java.util.UUID id) {
        if (server == null) {
            return;
        }
        java.nio.file.Path dir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR);
        for (String suffix : new String[] {".dat", ".dat_old"}) {
            try {
                java.nio.file.Files.deleteIfExists(dir.resolve(id + suffix));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException("MockPlayerNetwork: could not forget " + id + suffix
                        + " — the next mock with this id would be placed where this one left (#2604)", e);
            }
        }
    }

    /**
     * Let this mock player be sent anything, by any mod.
     *
     * @return the player, so it reads as one line at the top of a test
     * @throws IllegalStateException if the shim could not be applied — a mock player that silently
     *         stayed un-shimmed would crash the server several ticks later, naming somebody else's
     *         payload, which is exactly the wild goose chase this class exists to end
     */
    public static ServerPlayer acceptEverything(ServerPlayer player) {
        if (player == null) {
            throw new IllegalStateException("no player to shim");
        }
        Connection connection = player.connection == null ? null : player.connection.getConnection();
        if (connection == null) {
            throw new IllegalStateException(
                    "the mock player has no Connection to attach a payload setup to — "
                            + "makeMockServerPlayerInLevel() has changed shape");
        }
        ChannelAttributes.setPayloadSetup(connection, new NetworkPayloadSetup(EVERY_PROTOCOL));
        return player;
    }
}
