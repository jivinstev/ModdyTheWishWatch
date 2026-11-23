package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>The pack really is served over HTTP.</b>
 *
 * <p>{@code PackDeliveryTest} pins the URL and the routing rule without a game; this asks the thing
 * a pure test cannot — does a real socket hand back the real bytes. That distinction matters here
 * more than usual, because the failure this whole change exists to remove was invisible to every
 * check that did not actually fetch: the models were on the server's disk, the log said they had
 * been published, and the creature rendered as a cloud of paper.
 *
 * <p>Runs on a dedicated server, which is the point — the old path only worked when a client shared
 * the process, and a GameTest server shares its process with nothing.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class PackHostGameTest {

    private static final Duration T = Duration.ofSeconds(10);

    /** A pack-shaped directory: the host does not care what is in it, only that it is files. */
    private static Path fixture() throws Exception {
        Path root = Files.createTempDirectory("mfam-pack-fixture");
        Path models = root.resolve("assets").resolve("moddingfromamod")
                .resolve("models").resolve("item");
        Files.createDirectories(models);
        Files.writeString(models.resolve("a.json"), "{\"parent\":\"item/generated\"}");
        Files.writeString(models.resolve("b.json"), "{\"parent\":\"item/generated\"}");
        Files.writeString(root.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":34}}");
        return root;
    }

    @GameTest(template = "empty_test")
    public void thePackIsFetchableOverHttpAndArrivesIntact(GameTestHelper helper) {
        try {
            Path root = fixture();
            PackHost.start(PackHostConfig.LOOPBACK, PackHost.ANY_PORT);
            String url = PackHost.publish(root, PackHostConfig.LOOPBACK);

            HttpResponse<byte[]> res = HttpClient.newBuilder().connectTimeout(T).build()
                    .send(HttpRequest.newBuilder(URI.create(url)).timeout(T).GET().build(),
                            HttpResponse.BodyHandlers.ofByteArray());

            helper.assertTrue(res.statusCode() == 200,
                    Component.literal("the pack host answered " + res.statusCode() + " for its own URL " + url));

            // THE HASH IS THE CONTRACT. A stock client refuses a pack whose bytes do not match the
            // sha1 it was handed, so a host that served the right file under the wrong hash would
            // produce a client that silently declines -- which looks exactly like a client that
            // never asked.
            String served = PackHost.sha1(res.body());
            String promised = PackHost.sha1Of(url);
            helper.assertTrue(served.equals(promised),
                    Component.literal("the served bytes hash to " + served + " but the URL promises " + promised));

            // And it is really a zip of the pack, not an empty file with the right name.
            int entries = 0;
            try (ZipInputStream zis = new ZipInputStream(
                    new java.io.ByteArrayInputStream(res.body()))) {
                ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    if (!e.isDirectory()) {
                        entries++;
                    }
                }
            }
            helper.assertTrue(entries == 3, Component.literal("expected 3 files in the pack, got " + entries));
            helper.succeed();
        } catch (Exception e) {
            helper.fail(Component.literal("the pack could not be served: " + e));
        }
    }

    @GameTest(template = "empty_test")
    public void anUnchangedPackHashesTheSameSoARepublishIsFree(GameTestHelper helper) {
        try {
            Path root = fixture();
            PackHost.start(PackHostConfig.LOOPBACK, PackHost.ANY_PORT);
            String first = PackHost.publish(root, PackHostConfig.LOOPBACK);
            String second = PackHost.publish(root, PackHostConfig.LOOPBACK);
            // DETERMINISTIC ZIPPING IS WHAT MAKES THIS TRUE, and it is not free: entries are sorted
            // and timestamps zeroed for exactly this reason. Without it every republish would hash
            // differently, every stock client would take a multi-second reload it did not need, and
            // the "an unchanged pack costs nothing" claim would be quietly false.
            helper.assertTrue(first.equals(second),
                    Component.literal("the same pack published twice produced two URLs:\n  " + first + "\n  " + second));
            helper.succeed();
        } catch (Exception e) {
            helper.fail(Component.literal("republishing failed: " + e));
        }
    }

    /**
     * <b>A publish REMEMBERS what it offered, so a late joiner can be told</b> —.
     *
     * <p>The join hook is one {@code if (current == null) return;}, and the interesting question is
     * not whether the hook runs but whether anything ever makes that condition false. This repo
     * already records two guards whose enabling condition was false forever, so neither had ever
     * fired in a real session; the cheap check is to ask who writes what the guard reads, and drive
     * THAT. So this goes through the real {@code publish}, not through a setter.
     *
     * <p>What it cannot assert headlessly is the offer itself — that wants a second client joining a
     * live server, which is {@code tools/wish-paths.sh}'s arm 2. This is the half a gate can hold:
     * without it the hook is unreachable by construction and nothing would say so.
     */
    @GameTest(template = "empty_test")
    public void aPublishIsRememberedSoALateJoinerCanBeToldAboutIt(GameTestHelper helper) {
        RigReveal.forgetForTest();
        helper.assertTrue(RigReveal.currentSha1() == null,
                Component.literal("the fixture must start with nothing on offer, or this tests nothing"));
        try {
            var parts = java.util.List.of(new com.moddingfromamod.runtime.CreatureSculpt.Part(
                    "body", null, new float[] { 0, 0, 0 },
                    new float[] { -4, 0, -4 }, new float[] { 4, 8, 4 }, 0x8844aa));
            RigReveal.publish(helper.getLevel().getServer(), "gt_late_join", parts, 1.0);

            String sha1 = RigReveal.currentSha1();
            helper.assertTrue(sha1 != null && sha1.length() == 40,
                    Component.literal("publishing must record WHAT was offered, or a client that joins afterwards "
                            + "can never be told about it: " + sha1));
        } catch (Exception e) {
            helper.fail(Component.literal("publishing failed: " + e));
        } finally {
            RigReveal.forgetForTest();
        }
        helper.assertTrue(RigReveal.currentSha1() == null,
                Component.literal("and a world that closed must not offer its URL to the next world's joiner"));
        helper.succeed();
    }

    /**
     * <b>The host lets the JVM go, and the world closing takes the port with it</b> — MQP-C2b.
     *
     * <p>The bug this pins was invisible to every other test in this file, because all of them ask
     * whether the host WORKS and this asks whether it ever stops. A Gate C½ {@code runServer}
     * millstone wish (2026-09-09) PASSed at 21:34:30 and saved its worlds at 21:34:33; at 21:39:10
     * the process was still running with exactly two live Java threads, {@code DestroyJavaVM}
     * waiting and a <b>non-daemon</b> {@code HTTP-Dispatcher} — this host's, because
     * {@code ServerImpl.start()} makes the dispatcher inherit daemon status from its caller and
     * every caller was Minecraft's Server thread. At C5's 96 panel runs that is 96 JVMs that never
     * exit, each holding a port.
     *
     * <p><b>The assertion is over EVERY live dispatcher, not "ours"</b>, because there is no handle
     * on the thread the JDK creates — and it is the honest form of the invariant anyway: what makes
     * the JVM exit is that no thread of that name is a non-daemon. The MCP server keeps one of these
     * too and it has always been a daemon (it starts from {@code modloading-sync-worker}), so this
     * is not vacuously true of the process; it went red here before the fix.
     *
     * <p><b>Its own batch, and it restores what it took.</b> {@link PackHost} is a process-wide
     * singleton and every other test in this file starts it, so stopping it inside a shared batch
     * would fail a neighbour rather than the code — batches are strictly sequential, which is real
     * isolation. {@link ItemPackGameTest} then reads {@link ItemPackService#currentSha1()} and
     * {@link PackHost#port()} off the running server, so the stopping event is undone at the end by
     * the service's own {@code republish} — and only when there was something on offer to restore,
     * so the {@code } control arm is not switched on by a test.
     */
    @GameTest(template = "empty_test", batch = "packhostlifecycle")
    public void theHostIsDaemonAndTheStoppingEventClosesIt(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        String offered = ItemPackService.currentSha1();
        try {
            PackHost.start(PackHostConfig.LOOPBACK, PackHost.ANY_PORT);
            helper.assertTrue(PackHost.port() != -1,
                    Component.literal("the fixture needs a running host or neither half of this tests anything"));

            String awake = liveDispatchersThatAreNotDaemons();
            helper.assertTrue(awake.isEmpty(),
                    Component.literal("a non-daemon HTTP-Dispatcher keeps the JVM alive after the world has saved -- "
                            + "which is how a Gate C½ run never ends and a panel of 96 leaves 96 "
                            + "processes behind. Found: " + awake));

            // The wiring, driven the way the server drives it. Posting to the bus would fire every
            // other stopping handler in the mod inside a live test server; calling the handler is
            // the same claim without the collateral.
            ItemPackService.onServerStopping(new ServerStoppingEvent(server));
            helper.assertTrue(PackHost.port() == -1,
                    Component.literal("the world closed and the host is still listening on " + PackHost.port()
                            + ": PackHost.stop() has no production caller again"));
            helper.assertTrue(ItemPackService.currentSha1() == null,
                    Component.literal("the offer must be forgotten too -- it names a port nobody is listening on"));
        } catch (Exception e) {
            helper.fail(Component.literal("the host lifecycle failed: " + e));
        } finally {
            if (offered != null) {
                try {
                    ItemPackService.republish(server);
                } catch (Exception e) {
                    helper.fail(Component.literal("this test could not put the item pack back: " + e));
                }
            }
        }
        helper.succeed();
    }

    /** The live threads named {@code HTTP-Dispatcher} that are NOT daemons, for a failure message. */
    private static String liveDispatchersThatAreNotDaemons() {
        StringBuilder sb = new StringBuilder();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if ("HTTP-Dispatcher".equals(t.getName()) && !t.isDaemon() && t.isAlive()) {
                sb.append(sb.isEmpty() ? "" : ", ").append(t.getName()).append(" #").append(t.threadId());
            }
        }
        return sb.toString();
    }

    @GameTest(template = "empty_test")
    public void aHashWeNeverPublishedIsNotServed(GameTestHelper helper) {
        try {
            PackHost.start(PackHostConfig.LOOPBACK, PackHost.ANY_PORT);
            String url = PackDelivery.url(PackHostConfig.LOOPBACK, PackHost.port(),
                    "0123456789012345678901234567890123456789");
            HttpResponse<byte[]> res = HttpClient.newBuilder().connectTimeout(T).build()
                    .send(HttpRequest.newBuilder(URI.create(url)).timeout(T).GET().build(),
                            HttpResponse.BodyHandlers.ofByteArray());
            helper.assertTrue(res.statusCode() == 404,
                    Component.literal("an unpublished hash should 404, got " + res.statusCode()));
            helper.succeed();
        } catch (Exception e) {
            helper.fail(Component.literal("the 404 path failed: " + e));
        }
    }
}
