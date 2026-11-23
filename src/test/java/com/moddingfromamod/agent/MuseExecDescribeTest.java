package com.moddingfromamod.agent;

import com.moddingfromamod.config.HelperRoute;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * the describer's pictures through Muse Code ({@link MuseExec#describe}) take the same chain as a grade
 * (a server's subscription through the shim, then the API key), each with every picture as an {@code --image}
 * of the first message and the graders' lockdown flags. Fake shim and fake {@code muse}; no real binary runs.
 */
@org.junit.jupiter.api.extension.ExtendWith(com.moddingfromamod.config.MagicianOn.class)
class MuseExecDescribeTest {

    @TempDir
    Path tmp;

    private static final String DONE = "{\"payload_type\":\"run.terminal.completed\",\"payload\":"
            + "{\"terminal\":\"completed\",\"text\":\"It stands upright on two legs.\"}}";

    private final String museBefore = System.getProperty("moddingfromamod.muse.bin");
    private final String shimBefore = System.getProperty("moddingfromamod.muse.shim");
    private final java.util.function.Supplier<String> keyBefore = MuseExec.keySource;

    @AfterEach
    void restore() {
        put("moddingfromamod.muse.bin", museBefore);
        put("moddingfromamod.muse.shim", shimBefore);
        MuseExec.keySource = keyBefore;
    }

    private static void put(String k, String v) {
        if (v == null) {
            System.clearProperty(k);
        } else {
            System.setProperty(k, v);
        }
    }

    private Path script(String name, String body) throws Exception {
        Path p = tmp.resolve(name);
        Files.writeString(p, "#!/bin/sh\n" + body + "\n");
        assertTrue(p.toFile().setExecutable(true));
        return p;
    }

    private List<Path> pictures() throws Exception {
        Path a = tmp.resolve("picture1.png"), b = tmp.resolve("picture2.png");
        Files.write(a, new byte[] {1});
        Files.write(b, new byte[] {2});
        return List.of(a, b);
    }

    @Test
    void theSubscriptionGetsThePicturesInTheFirstMessage() throws Exception {
        Path muse = script("muse", "echo nope");
        Path shim = script("muse-cli-shim.py", "printf '%s\\n' \"$@\" > '" + tmp.resolve("shim-argv") + "'\necho '"
                + DONE + "'");
        System.setProperty("moddingfromamod.muse.bin", muse.toString());
        System.setProperty("moddingfromamod.muse.shim", shim.toString());
        MuseExec.keySource = () -> "LLM_realkey";
        List<Path> pics = pictures();
        MuseExec.Answer a = MuseExec.describe("SYSTEM", "Describe it.", pics, "muse-ember-1.0", "low", 10_000,
                List.of(HelperRoute.Step.MUSE_SUBSCRIPTION, HelperRoute.Step.MUSE_KEY));
        assertNotNull(a);
        assertEquals("It stands upright on two legs.", a.text());
        assertEquals(MuseExec.ARM_SUBSCRIPTION, a.arm());
        List<String> argv = Files.readAllLines(tmp.resolve("shim-argv"));
        assertEquals(pics.get(0).toAbsolutePath().toString(), argv.get(argv.indexOf("--image") + 1));
        assertEquals(2, argv.stream().filter("--image"::equals).count(), "one --image per picture");
        assertEquals("Describe it.", argv.get(argv.size() - 1), "the ask comes last, after the pictures");
        assertTrue(argv.containsAll(List.of("--disable-shell", "--disable-write", "--disable-web-tools")),
                "no tool it could use: " + argv);
        assertFalse(argv.contains("--api-key-stdin"));
    }

    @Test
    void aSkippedSubscriptionSendsThePicturesOnTheKey() throws Exception {
        Path muse = script("muse", "cat > '" + tmp.resolve("muse-stdin") + "'\nprintf '%s\\n' \"$@\" > '"
                + tmp.resolve("muse-argv") + "'\necho '" + DONE + "'");
        Path shim = script("muse-cli-shim.py", "echo '{\"shim\":\"grade\",\"arm\":\"api\"}'\nexit "
                + MuseExec.SHIM_SKIPPED);
        System.setProperty("moddingfromamod.muse.bin", muse.toString());
        System.setProperty("moddingfromamod.muse.shim", shim.toString());
        MuseExec.keySource = () -> "LLM_realkey";
        MuseExec.Answer a = MuseExec.describe("SYSTEM", "Describe it.", pictures(), "muse-ember-1.0", "low", 10_000,
                List.of(HelperRoute.Step.MUSE_SUBSCRIPTION, HelperRoute.Step.MUSE_KEY));
        assertNotNull(a);
        assertEquals(MuseExec.ARM_API, a.arm());
        List<String> argv = Files.readAllLines(tmp.resolve("muse-argv"));
        assertEquals(2, argv.stream().filter("--image"::equals).count());
        assertTrue(argv.contains("--api-key-stdin"));
        assertEquals("LLM_realkey", Files.readString(tmp.resolve("muse-stdin")));
    }

    @Test
    void theDescribersSubscriptionStepIsOnlyTheSubscription() throws Exception {
        Path muse = script("muse", "printf '%s\\n' \"$@\" > '" + tmp.resolve("muse-argv") + "'\necho '" + DONE + "'");
        System.setProperty("moddingfromamod.muse.bin", muse.toString());
        MuseExec.keySource = () -> "LLM_realkey";
        System.setProperty("moddingfromamod.muse.shim", "none");
        MuseExec.Answer login = MuseExec.describe("S", "Describe it.", pictures(), "muse-ember-1.0", "minimal",
                10_000, List.of(HelperRoute.Step.MUSE_SUBSCRIPTION));
        assertNotNull(login, "no shim: the sign-in step is Muse Code's own login");
        assertEquals(MuseExec.ARM_LOGIN, login.arm());
        assertFalse(Files.readAllLines(tmp.resolve("muse-argv")).contains("--api-key-stdin"),
                "the sign-in step never sends the key");
        Files.delete(tmp.resolve("muse-argv"));
        Path shim = script("muse-cli-shim.py", "echo '{\"shim\":\"grade\",\"arm\":\"api\"}'\nexit "
                + MuseExec.SHIM_SKIPPED);
        System.setProperty("moddingfromamod.muse.shim", shim.toString());
        assertEquals(null, MuseExec.describe("S", "Describe it.", pictures(), "muse-ember-1.0", "minimal",
                10_000, List.of(HelperRoute.Step.MUSE_SUBSCRIPTION)), "the shim says not to try: the caller's direct key step is next");
        assertFalse(Files.exists(tmp.resolve("muse-argv")), "the key leg is the caller's, not muse exec's");
        Path ok = script("muse-cli-shim.py", "printf '%s\\n' \"$@\" > '" + tmp.resolve("shim-argv") + "'\necho '"
                + DONE + "'");
        System.setProperty("moddingfromamod.muse.shim", ok.toString());
        MuseExec.Answer a = MuseExec.describe("S", "Describe it.", pictures(), "muse-ember-1.0",
                "minimal", 10_000, List.of(HelperRoute.Step.MUSE_SUBSCRIPTION));
        assertNotNull(a);
        assertEquals(MuseExec.ARM_SUBSCRIPTION, a.arm());
        List<String> argv = Files.readAllLines(tmp.resolve("shim-argv"));
        assertEquals("minimal", argv.get(argv.indexOf("--reasoning-effort") + 1));
        assertEquals(2, argv.stream().filter("--image"::equals).count());
    }

    @Test
    void aGradeStillSendsNoImage() {
        assertFalse(MuseGrade.argv("/bin/muse", "m", "S", "low", null, false, "w").contains("--image"));
        List<String> a = MuseGrade.argv("/bin/muse", "m", "S", "low", null, false, List.of("/p/1.png"), "w");
        assertEquals(List.of("--image", "/p/1.png", "w"), a.subList(a.size() - 3, a.size()));
    }
}
