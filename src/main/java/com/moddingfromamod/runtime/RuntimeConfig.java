package com.moddingfromamod.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import com.moddingfromamod.ModdingFromAModMod;

/**
 * Where the machine's custom-mob assets live, and where "make this a real mod" should write.
 *
 * <p>Deliberately <b>not</b> read from {@code .env.local}: that file is a Gradle-time thing, and the
 * running game (launched from the vanilla launcher) never sees it. The mod already keeps a runtime
 * home at {@code ~/.moddingfromamod/} (see {@code AgentToken}); this reads an optional
 * {@code config.properties} there, so a grown-up can point the model repository and the persist
 * target at <i>their</i> mod repo without recompiling.
 *
 * <p><b>Layering:</b> this engine is deliberately NOT aware of any specific mod repo (e.g. Battle of
 * Lord) in its checked-in code — the persist/publish target is machine-local context only, so the
 * dependency arrow only ever points <i>mod repo → engine</i>, never the reverse.
 *
 * <p>Resolution order for every value: JVM system property (used by the tests) → env var →
 * {@code config.properties} → built-in default. Nothing here throws; a missing file just means
 * defaults.
 */
public final class RuntimeConfig {

    private RuntimeConfig() {
    }

    /**
     * {@code ~/.moddingfromamod} — the mod's runtime home, shared with the agent token. Overridable
     * via the {@code moddingfromamod.home} system property so tests never touch the real home.
     */
    public static Path home() {
        String override = System.getProperty(ModdingFromAModMod.MODID + ".home");
        if (override != null && !override.isBlank()) {
            return Path.of(expand(override));
        }
        return Path.of(System.getProperty("user.home"), "." + ModdingFromAModMod.MODID);
    }

    /**
     * The custom-mob repository: one folder per creature id, each holding {@code geometry.json},
     * {@code texture.png}, the Blockbench {@code project.bbmodel} source (for later iteration) and a
     * small {@code species.json}. Both the integrated server and the client read from here — same
     * machine, same disk — which is why a new custom model needs no network payload and no restart.
     *
     * <p><b>Per-world since the split-brain fix.</b> Models used to sit in one shared folder while
     * the species referencing them were per-world, so re-sculpting an id in one world silently
     * rewrote another world's creature — see {@link WorldPaths}. An explicit config override still
     * wins (tests rely on it); the old shared folder is the fallback only when no world is open.
     */
    public static Path modelDir() {
        Path override = modelDirOverride();
        if (override != null) {
            return override;
        }
        Path world = WorldPaths.modelDir();
        return world != null ? world : home().resolve("models");
    }

    /**
     * Where the Apprentice's sentence model is looked for ({@code model.onnx} + {@code vocab.txt}): system
     * property {@code moddingfromamod.apprenticeModelDir}, env {@code MODDINGFROMAMOD_APPRENTICE_MODEL_DIR},
     * {@code config.properties} key {@code apprentice_model_dir}, else
     * {@code <gameDir>/moddingfromamod/apprentice-model}. An empty or missing folder is normal: the Apprentice then
     * answers from its keywords alone.
     */
    public static Path apprenticeModelDir() {
        String v = value("apprentice_model_dir", "APPRENTICE_MODEL_DIR", "apprenticeModelDir");
        if (v != null && !v.isBlank()) {
            return Path.of(expand(v));
        }
        return net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("moddingfromamod").resolve("apprentice-model");
    }

    /**
     * The configured mod repository a persisted artifact is baked into. Resolved ONLY from config
     * (system property {@code moddingfromamod.persistDir}, env {@code MODDINGFROMAMOD_PERSIST_DIR},
     * or {@code ~/.moddingfromamod/config.properties} key {@code persist_dir}). Returns {@code null}
     * when nothing is configured — the engine assumes no specific mod repo in checked-in code, so
     * publishing is simply disabled until a target is set as machine-local context.
     */
    public static Path persistDir() {
        // Explicit override (system property / env) always wins — tests set this to a temp dir.
        String sys = System.getProperty(ModdingFromAModMod.MODID + ".persistDir");
        if (sys != null && !sys.isBlank()) {
            return Path.of(expand(sys));
        }
        String env = System.getenv(
                ModdingFromAModMod.MODID.toUpperCase(java.util.Locale.ROOT) + "_PERSIST_DIR");
        if (env != null && !env.isBlank()) {
            return Path.of(expand(env));
        }
        // The config FILE points at the real Mod Repo. A TEST run must NEVER publish there, or
        // gametests that persist templates/datapacks would litter the repo. Only real play reads it.
        if (com.moddingfromamod.RunContext.isTestRun()) {
            return null;
        }
        String file = props().getProperty("persist_dir");
        return (file != null && !file.isBlank()) ? Path.of(expand(file)) : null;
    }

    /**
     * <b>The shared wish LIBRARY — the server owner's curated wishes and artifacts</b>.
     *
     * <p>{@code -Dmoddingfromamod.libraryDir} / {@code MODDINGFROMAMOD_LIBRARY_DIR} /
     * {@code library_dir} in {@code config.properties}, defaulting to {@code <home>/library}. A server
     * can point this at a checkout it refreshes and then reloads over RCON, with no restart.
     *
     * <p><b>Never null, unlike {@link #persistDir()}</b>, and the difference is deliberate. The Mod
     * Repo is a publish TARGET, so "unconfigured" has to switch publishing off and every caller has
     * to handle a null. The library is a read-only SOURCE, so a directory that is not there is
     * simply an empty library — one INFO line, no error, and no branch anywhere else. It is also
     * not suppressed on a test run for the same reason: reading a folder cannot litter anybody's
     * repo, and a GameTest points the system property at a temp dir.
     */
    public static Path libraryDir() {
        String v = value("library_dir", "LIBRARY_DIR", "libraryDir");
        if (v != null && !v.isBlank()) {
            return Path.of(expand(v.trim()));
        }
        return home().resolve("library");
    }

    /** An explicitly configured model folder, or null — tests use it to pin every model in one place. */
    public static Path modelDirOverride() {
        String v = value("model_dir", "MODEL_DIR", "modelDir");
        return v != null && !v.isBlank() ? Path.of(expand(v)) : null;
    }

    /**
     * <b>Each child's shelf</b>: {@code <playersDir>/<uuid>/} holds the things that child
     * made (creature designs and models, item designs and icons, learned buttons, buildings), so
     * they follow him from Creative to Survival (D6). See {@code store/PlayerShelf}.
     *
     * <p>Under the mod home by default because that is the one directory every server on the
     * machine already shares, so moving a server's directories moves it with the rest. Overridable
     * ({@code moddingfromamod.playersDir}, {@code MODDINGFROMAMOD_PLAYERS_DIR}, {@code players_dir}).
     * A test run or a headless probe never uses the real one — see the body.
     */
    public static Path playersDir() {
        String v = value("players_dir", "PLAYERS_DIR", "playersDir");
        if (v != null && !v.isBlank()) {
            return Path.of(expand(v.trim()));
        }
        // ⚠ A TEST RUN OR A HEADLESS PROBE KEEPS ITS SHELVES INSIDE ITS OWN WORLD. Every gate
        // that binds a child (Gate B's per-child tests, Gate C's real Dev player, Gate D), and the
        // headless probe (Gate C½, author-wish.sh, machine-repeats.sh) with its FIXED fake child,
        // makes owned things. On the real home they would outlive the world each of those deletes
        // to start clean — machine-repeats.sh exists because learned.json once grew a template per
        // run — re-appear in the next run's world, and write strangers' things onto a real
        // machine. Inside the world they go when it goes. (Gate A has no world: its `test` task
        // sets the property above to a folder under build/.)
        Path wishes = WorldPaths.wishesDir();
        String probe = System.getProperty(ModdingFromAModMod.MODID + ".headlesswish");
        boolean probing = probe != null && !probe.isBlank();
        if (wishes != null && (probing || com.moddingfromamod.RunContext.isTestRun())) {
            return wishes.getParent().resolve("moddy-players");
        }
        return home().resolve("players");
    }

    /**
     * The mod id assets are written under when persisting (namespace for resource paths), or
     * {@code ""} when unconfigured. No specific mod is assumed in checked-in code — set
     * {@code persist_modid} in config to your repo's mod id.
     */
    public static String persistModId() {
        String v = value("persist_modid", "PERSIST_MODID", "persistModId");
        return (v == null || v.isBlank()) ? "" : v.trim();
    }

    /** True when a usable persist target is configured (the folder exists). */
    public static boolean persistEnabled() {
        try {
            Path d = persistDir();
            return d != null && Files.isDirectory(d);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Opt-in: should the engine git-commit/push the wish content it publishes into the Mod Repo?
     * OFF unless {@code wish_git_sync=true} is configured. See {@code docs/WISH_GIT_SYNC.md}.
     */
    public static boolean gitSyncEnabled() {
        String v = value("wish_git_sync", "WISH_GIT_SYNC", "wishGitSync");
        return "true".equalsIgnoreCase(v == null ? "" : v.trim());
    }

    /** The Moddy-owned branch the engine commits/pushes wish content to. Default {@code moddy/wishes}. */
    public static String gitBranch() {
        String v = value("wish_git_branch", "WISH_GIT_BRANCH", "wishGitBranch");
        return (v == null || v.isBlank()) ? "moddy/wishes" : v.trim();
    }


    /**
     * The camera's username when nothing configures one — the same default
     * {@code tools/camera-client.sh} carries, because the server and the client must agree on it.
     */
    public static final String DEFAULT_CAMERA_NAME = "Moddy-Camera";

    /**
     * <b>The username the agent's camera client joins under.</b> Never empty.
     *
     * <p>⚠ <b>This used to default to the empty string, and empty MEANT "there is no camera".</b>
     * That was right while the camera was a spike and was open — nothing launched one, so a
     * name would have named nothing. {@link com.moddingfromamod.wish.CameraSupervisor} closed that:
     * a camera is started the moment a child is online and stopped when the last one leaves. With a
     * launcher present and no name, the supervisor is inert and every photograph borrows the
     * child's eyes — which on a stock client {@code WishCapture.enter} refuses outright, so the
     * agent is told {@code noeyes} and simply cannot see. One unset string, and the whole capture
     * path is off with nothing saying so.
     *
     * <p><b>{@link #DEFAULT_CAMERA_NAME} is deliberately the same string
     * {@code tools/camera-client.sh} already defaults to.</b> The two halves have to agree — the
     * server registers {@code offlineId(name)} as a service client and the client joins under
     * {@code -Pusername} — and they were defaulted on one side only, which is why this was invisible.
     *
     * <p>Defaulting the NAME does not by itself start anything. The supervisor still refuses on a
     * singleplayer server (a camera is a whole Minecraft, and §3's topology decision says not on a
     * child's laptop), on a test run, and with no launcher to run. So this makes a camera possible
     * where it was configured-out, and changes nothing where it was impossible.
     *
     * <p>⚠ <b>This is read at mod construction, not at first use.</b> A camera is declared a
     * non-child from its offline-mode UUID ({@code PlayerKind.offlineId}), which is derivable from
     * the name alone — so it can be registered while it is still a string in a launch script.
     * Learning the role from a handshake instead would leave the camera a child for its first few
     * ticks, which is exactly the window a tool asks in.
     *
     * <p>Resolution is the usual three: {@code -Dmoddingfromamod.cameraName}, then
     * {@code MODDINGFROMAMOD_CAMERA_NAME}, then {@code camera_name} in the properties file — and
     * {@code off}, {@code none} or {@code false} turns the camera off, which is the only way to say
     * so now that unset means the default.
     */
    public static String cameraName() {
        String v = value("camera_name", "CAMERA_NAME", "cameraName");
        if (v == null || v.isBlank()) {
            return DEFAULT_CAMERA_NAME;
        }
        String name = v.strip();
        // ⚠ THE OFF SWITCH HAS TO BE A WORD, not an empty value, and that is not a style choice:
        // `value()` discards a blank system property and a blank environment variable before this
        // method ever sees them, so `-Dmoddingfromamod.cameraName=` is indistinguishable from
        // unset. Without a word there is simply no way to say "no camera" once a default exists.
        // `off`/`none`/`false` is the spelling VanillaCarrier and VanillaArt already use.
        return "off".equalsIgnoreCase(name) || "none".equalsIgnoreCase(name)
                || "false".equalsIgnoreCase(name) ? "" : name;
    }

    /**
     * <b>The A/B seam 's pack deferral.</b> On by default; {@code false} makes every stock
     * client get every interim sculpt, exactly as before the deferral existed.
     *
     * <p>This repo's testing discipline asks for a {@code -Ptestprops} toggle on a
     * "the player sees/hears X" change, <em>so you can re-prove it later</em> — and this one
     * cannot be re-proved any other way. The child is a genuinely stock client, so the only
     * measurement available is a count of resource reloads in his own log
     * ({@code tools/freeze-report.py}), and a count means nothing without the same wish, the same
     * topology and the same build measured with the feature off. Removing the camera is NOT that
     * control: with no camera the agent photographs through the child, the self-critique loop
     * changes, and the number of sculpts changes with it — a control that alters the thing being
     * counted.
     *
     * <p>{@code -Dmoddingfromamod.deferPack=false} / {@code MODDINGFROMAMOD_DEFER_PACK} /
     * {@code defer_pack}. Used by {@code tools/freeze-ab.sh} arm A.
     */
    public static boolean deferPack() {
        String v = value("defer_pack", "DEFER_PACK", "deferPack");
        return v == null || !"false".equalsIgnoreCase(v.strip());
    }

    /**
     * <b>Where {@code CameraSupervisor} finds the script that launches a camera.</b>
     *
     * <p>Resolved rather than assumed, because a dedicated server's working directory is its RUN
     * directory ({@code run/server-wish2}), not the repo root — so a bare relative
     * {@code tools/camera-client.sh} resolves to a path that has never existed and the supervisor
     * would report "no launcher" on a checkout that has one. Walks up from the working directory
     * looking for it, which finds it from a run directory and from the repo root alike.
     *
     * <p>Override with {@code -Dmoddingfromamod.cameraLauncher} /
     * {@code MODDINGFROMAMOD_CAMERA_LAUNCHER} / {@code camera_launcher} — a deployment that keeps
     * the script somewhere else, or that wants the supervisor pointed at a wrapper.
     *
     * @return the script, or null when there is none — which is not an error: the camera may be
     *         started by something else entirely (a harness, a service manager), and
     *         {@code CameraSupervisor.serving} asks the SERVER who is connected rather than asking
     *         itself what it spawned.
     */
    public static Path cameraLauncher() {
        String v = value("camera_launcher", "CAMERA_LAUNCHER", "cameraLauncher");
        if (v != null && !v.isBlank()) {
            Path explicit = Path.of(expand(v.strip()));
            return java.nio.file.Files.isExecutable(explicit) ? explicit : null;
        }
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve("tools").resolve("camera-client.sh");
            if (java.nio.file.Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    // ---- child safety (docs/CHILD_SAFETY_PLAN.md §8) ----
    //
    // Five raw readers, one per key. The DEFAULTS live in ChildSafetyConfig rather than here, for
    // the same reason vanillaRig()'s does: this method's job is to say what was configured, and
    // "nothing" is a real answer that a defaulting reader would erase.

    /**
     * Raw value of the child-safety switch — {@code on} / {@code shadow} / {@code off}.
     *
     * <p>{@code -Dmoddingfromamod.childsafety} / {@code MODDINGFROMAMOD_CHILD_SAFETY} /
     * {@code child_safety}. The system property is what {@code -Ptestprops="childsafety=off"}
     * reaches, which is the A/B control arm.
     */
    public static String childSafety() {
        return value("child_safety", "CHILD_SAFETY", "childsafety");
    }

    /** Raw value of the parent's dial — {@code relaxed} / {@code standard} / {@code strict}. */
    public static String childSafetyStrictness() {
        return value("child_safety_strictness", "CHILD_SAFETY_STRICTNESS", "childSafetyStrictness");
    }

    /** Raw value of what an ungradeable wish does — {@code open} / {@code closed}. */
    public static String childSafetyUnavailable() {
        return value("child_safety_unavailable", "CHILD_SAFETY_UNAVAILABLE",
                "childSafetyUnavailable");
    }

    /** Raw value of how long the dequeue side waits for the input grade, in milliseconds. */
    public static String childSafetyInputBudgetMs() {
        return value("child_safety_input_budget_ms", "CHILD_SAFETY_INPUT_BUDGET_MS",
                "childSafetyInputBudgetMs");
    }

    /** Raw value of the grader model alias. */
    public static String childSafetyModel() {
        return value("child_safety_model", "CHILD_SAFETY_MODEL", "childSafetyModel");
    }

    /** Raw value of the INPUT grader's CLI {@code --effort}. */
    public static String childSafetyEffort() {
        return value("child_safety_effort", "CHILD_SAFETY_EFFORT", "childSafetyEffort");
    }

    /** Raw value of the PLAN grader's model — measured separately, so set separately. */
    public static String childSafetyPlanModel() {
        return value("child_safety_plan_model", "CHILD_SAFETY_PLAN_MODEL", "childSafetyPlanModel");
    }

    /** Raw value of the PLAN grader's {@code --effort} — its own, like its model. */
    public static String childSafetyPlanEffort() {
        return value("child_safety_plan_effort", "CHILD_SAFETY_PLAN_EFFORT", "childSafetyPlanEffort");
    }

    /**
     * Raw value of the library layer's NUDGE kinds: letters of the 2×4 matrix, e.g.
     * {@code bcd}; empty or {@code none} is today's agent. {@code -Ptestprops="readymade.nudge=d"}.
     */
    public static String readyMadeNudge() {
        return value("readymade_nudge", "READYMADE_NUDGE", "readymade.nudge");
    }

    /** Raw value of the library layer's SEARCH kinds, the same letters. */
    public static String readyMadeSearch() {
        return value("readymade_search", "READYMADE_SEARCH", "readymade.search");
    }

    // ---- the deployment assertion (docs/plan/03-implementation-plan.md, HO-0) ----
    //
    // Three raw readers, same shape and same reason as the safety and router ones above: the
    // DEFAULTS live in DeploymentAssertion, because this method's job is to say what was
    // CONFIGURED. Here that distinction is load-bearing rather than tidy — "absent" means dev and
    // is safe, while a value that is set and unrecognised is a refusal, and a defaulting reader
    // here would erase the difference.

    /**
     * Raw value of which deployment this is — {@code dev} (the default) / {@code test} /
     * {@code prod}.
     *
     * <p>{@code -Dmoddingfromamod.env} / {@code MODDINGFROMAMOD_ENV} / {@code deploy_env}. The
     * launcher passes it from {@code MODDY_ENV}; every gate in this repo leaves it unset, which is
     * why absent has to mean {@code dev}.
     */
    public static String deployEnv() {
        return value("deploy_env", "ENV", "env");
    }

    /**
     * Raw value of whether a protocol proxy is in front of this server — {@code on} / {@code off}.
     *
     * <p>The launcher's own two-condition answer ({@code MODDY_PROXY=on} <b>and</b> the jar being
     * present), not a re-derivation of it: a layout without {@code viaproxy/} runs the old topology
     * and says so once, and a second opinion here could disagree with the process that actually
     * started — or did not start — the proxy.
     */
    public static String proxyConfigured() {
        return value("proxy", "PROXY", "proxy");
    }

    /** Raw value of whether the PROXY verifies joining players with Mojang (decision D12). */
    public static String proxyOnlineMode() {
        return value("proxy_online_mode", "PROXY_ONLINE_MODE", "proxyOnlineMode");
    }

    // ---- the metrics endpoint (docs/plan/03-implementation-plan.md, HO-2) ----

    /**
     * Raw value of the metrics port. Absent means the endpoint is <b>off</b>, which is the default:
     * a new listening socket that nobody asked for is a change to every existing deployment's
     * attack surface.
     */
    public static String metricsPort() {
        return value("metrics_port", "METRICS_PORT", "metrics.port");
    }

    /**
     * Raw value of the metrics bind. Absent means loopback.
     *
     * <p>Configurable because a container's Prometheus is in another network namespace — and
     * therefore asserted: {@code DeploymentAssertion} refuses a {@code prod} server that binds this
     * to a public interface.
     */
    public static String metricsBind() {
        return value("metrics_bind", "METRICS_BIND", "metrics.bind");
    }

    // ---- where a player who cannot get in is sent (net/JoinWords) ----

    /** The "ask to join" address in the not-on-the-allowlist refusal. Blank → the site's. */
    public static String accessUrl() {
        return value("access_url", "ACCESS_URL", "accessUrl");
    }

    /**
     * Where a world that takes requests sends a stranger to ask for a seat.
     * Blank → {@code JoinWords.DEFAULT_SEAT_URL}, the site's /join.
     */
    public static String seatUrl() {
        return value("seat_url", "SEAT_URL", "seatUrl");
    }

    /** The first-join intro — {@code on}, {@code off}, or blank (on exactly when the world names itself). */
    public static String intro() {
        return value("intro", "INTRO", "intro");
    }

    /** The server list's "Trouble joining?" address; {@code off} drops the line. Blank → the site's. */
    public static String joinHelpUrl() {
        return value("join_help_url", "JOIN_HELP_URL", "joinHelpUrl");
    }

    // ---- resolution helpers ----

    /**
     * The <b>public</b> half of the service-client key (D14) — base64 X.509, or blank.
     *
     * <p>⚠ <b>This is not a secret and must not be treated as one.</b> It is the half a server
     * holds to CHECK a signature; holding it lets you verify and never sign. Putting it in config,
     * in git or in a deployment's config is correct. The PRIVATE half belongs in a secret store (Q17) and is
     * read by the client, never by the server.
     *
     * <p>Blank means no key, which {@code ServiceClientAuth.decide} turns into a REFUSAL in prod
     * rather than into an exemption — a prod server running a camera without its key refuses its
     * own camera. Fail closed.
     */
    public static String serviceClientPublicKey() {
        String v = value("service_client_public_key", "SERVICE_CLIENT_PUBLIC_KEY",
                "serviceClientPublicKey");
        return v == null ? "" : v.strip();
    }

    /**
     * The <b>private</b> half, read only by a service client so it can answer a challenge.
     *
     * <p>⚠ A SECRET. It is read from the environment or a system property so it can come from
     * a secret store without ever being written to a config file on the box.
     */
    public static String serviceClientPrivateKey() {
        String v = value("service_client_private_key", "SERVICE_CLIENT_PRIVATE_KEY",
                "serviceClientPrivateKey");
        if ((v == null || v.isBlank()) && System.getProperty(
                ModdingFromAModMod.MODID + ".serviceClientPrivateKeyFile") != null) {
            v = keyFromFile(Path.of(System.getProperty(
                    ModdingFromAModMod.MODID + ".serviceClientPrivateKeyFile")));
        }
        return v == null ? "" : v.strip();
    }

    /**
     * <b>The key read from a FILE, at the moment of the challenge — never at boot.</b>
     *
     * <p>A camera client may boot BEFORE it knows which server it will serve (that is what makes
     * a resumed camera fast: the 45–60 s client boot is paid while nobody waits), so the key cannot
     * ride in the environment the JVM was started with. Whatever launches the camera writes the server's key
     * into this file once it knows the server, and the challenge reads it then. Only the PATH
     * is a system property; the secret itself never is, so {@code ps} still cannot show it.
     * Missing or unreadable reads as no key, which a prod server refuses (fail closed).
     */
    static String keyFromFile(Path f) {
        try {
            return Files.isRegularFile(f) ? Files.readString(f).strip() : "";
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /**
     * A machine-local value: {@code -Dmoddingfromamod.<sysKey>}, else {@code MODDINGFROMAMOD_<envKey>}, else
     * {@code <propKey>} in config.properties. Public so a plug-in reads its own settings the same way.
     */
    public static String value(String propKey, String envKey, String sysKey) {
        String sys = System.getProperty(ModdingFromAModMod.MODID + "." + sysKey);
        if (sys != null && !sys.isBlank()) {
            return sys;
        }
        String env = System.getenv(ModdingFromAModMod.MODID.toUpperCase(java.util.Locale.ROOT)
                + "_" + envKey);
        if (env != null && !env.isBlank()) {
            return env;
        }
        return props().getProperty(propKey);
    }

    private static volatile Properties cachedProps;

    private static Properties props() {
        Properties p = cachedProps;
        if (p != null) {
            return p;
        }
        p = new Properties();
        Path f = home().resolve("config.properties");
        try {
            if (Files.isRegularFile(f)) {
                try (var in = Files.newInputStream(f)) {
                    p.load(in);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // a broken config file must never stop the game — defaults win
        }
        cachedProps = p;
        return p;
    }

    /** So a test can force a fresh read after writing config. */
    public static void invalidate() {
        cachedProps = null;
    }

    private static String expand(String path) {
        if (path.startsWith("~")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }
}
