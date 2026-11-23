package com.moddingfromamod.config;

import com.moddingfromamod.agent.ModelFallback;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * <b>Which provider, and which way of paying it, each HELPER model call may use</b>. The one place
 * that decides; every helper job asks it and walks the answer in order, so no job keeps a billing rule of its own.
 *
 * <p>The class of bug this closes: <b>a helper reaches a provider the player did not choose</b>. After a
 * single-player host whose Magician choice was Claude only, with {@code MUSE_API_KEY} happening to be in the
 * environment, still had the picture describer bill Meta ({@code BriefLooks} → {@code EmberVision}), because each job
 * decided for itself from whichever key it could see.
 *
 * <p>A route is an ordered list of {@link Step}s, tried in turn until one answers:
 * <ul>
 *   <li><b>The Magician setup is in charge</b> (a single-player host's picks, {@link MagicianWiring#active}): the
 *       steps are the player's HELPER chain ({@code magician.cheapModel}/{@code cheapMode}, then
 *       {@code magician.cheapFallback}), and nothing else. A key for a provider they did not choose is never used. A
 *       {@code helper.route.<job>} value there may only narrow or reorder those steps, never add one.</li>
 *   <li><b>Otherwise</b> (a server whose settings override locks the picks; a dedicated server with no picks), the per-job
 *       setting {@code helper.route.<job>} ({@link Job#key}) when it is set, else {@link #today}: exactly what each job
 *       did before this class, so a deploy changes nothing.</li>
 * </ul>
 *
 * <p>The route never changes a job's MODEL: a Muse step runs the job's Muse model (Ember, or the helper chain's own
 * entry), a Claude step its Claude one (its pinned Haiku, or the Claude CLI researcher). A grader whose configured model
 * is a Claude one has no Muse model, so its Muse steps are skipped ({@link #first}'s caller passes none).
 *
 * <p>Pure apart from {@link #steps(Job, boolean)}, which reads the shared settings and the machine.
 */
public final class HelperRoute {

    private HelperRoute() {
    }

    /** One way to answer a helper call: a provider, and how it is paid for. */
    public enum Step {
        /** Muse Code on a sign-in: the server's subscription through the shim, else Muse Code's own login. */
        MUSE_SUBSCRIPTION("muse-subscription", SupportedModels.Provider.MUSE, SupportedModels.Mode.SUBSCRIPTION),
        /** Muse on {@code MUSE_API_KEY}, metered. */
        MUSE_KEY("muse-key", SupportedModels.Provider.MUSE, SupportedModels.Mode.API),
        /** The Claude CLI billed to {@code ANTHROPIC_API_KEY} (skipped when the environment has none). */
        CLAUDE_KEY("claude-key", SupportedModels.Provider.ANTHROPIC, SupportedModels.Mode.API),
        /** The Claude CLI on its own sign-in: {@code ANTHROPIC_API_KEY} is removed from its environment. */
        CLAUDE_SUBSCRIPTION("claude-subscription", SupportedModels.Provider.ANTHROPIC, SupportedModels.Mode.SUBSCRIPTION);

        public final String id;
        public final SupportedModels.Provider provider;
        public final SupportedModels.Mode mode;

        Step(String id, SupportedModels.Provider provider, SupportedModels.Mode mode) {
            this.id = id;
            this.provider = provider;
            this.mode = mode;
        }

        public boolean muse() {
            return provider == SupportedModels.Provider.MUSE;
        }

        public static Optional<Step> parse(String s) {
            String v = s == null ? "" : s.strip().toLowerCase(Locale.ROOT);
            for (Step st : values()) {
                if (st.id.equals(v)) {
                    return Optional.of(st);
                }
            }
            return Optional.empty();
        }

        public static Step of(SupportedModels.Provider p, SupportedModels.Mode m) {
            for (Step st : values()) {
                if (st.provider == p && st.mode == m) {
                    return st;
                }
            }
            throw new IllegalArgumentException(p + "/" + m);
        }

        public static List<String> ids() {
            return java.util.Arrays.stream(values()).map(st -> st.id).toList();
        }
    }

    /** Every helper job: the calls that are not a wish grant. */
    public enum Job {
        /** The picture describer ({@code reference/BriefLooks}): Ember, else the Haiku CLI. */
        DESCRIBER("describer"),
        /** The reference researcher ({@code reference/ReferenceResearcher}): the Claude CLI, else Ember at high. */
        RESEARCHER("researcher"),
        /** The plan grader ({@code safety/SafetyPlanGate}). */
        PLAN("plan"),
        /** The wish-text safety grader ({@code safety/SafetyGrader}). */
        WISH_TEXT("wishText"),
        /** World rules ({@code world/WorldRulesGrader}). */
        WORLD_RULES("worldRules"),
        /** The ready-made nudge ({@code readymade/ReadyMadeNudge}). */
        READY_MADE("readyMade"),
        /** The character lookup ({@code reference/CharacterResolver}). */
        CHARACTER("character");

        public final String id;

        Job(String id) {
            this.id = id;
        }

        /** The setting that holds this job's route: {@code helper.route.<id>}. */
        public String key() {
            return "helper.route." + id;
        }
    }

    /** What today's default is worked out from: is this a server with the Muse shim, which keys are set. */
    public record Machine(boolean shim, boolean museKey, boolean anthropicKey) {

        public static Machine now() {
            return new Machine(com.moddingfromamod.agent.MuseExec.shim() != null,
                    com.moddingfromamod.agent.MuseExec.keyPresent(), present(SupportedModels.Provider.ANTHROPIC.keyVar));
        }

        private static boolean present(String var) {
            String v = env.apply(var);
            return v != null && !v.isBlank() && !"subscription".equals(v.strip());
        }
    }

    /** The environment variables read for {@link Machine#now} and {@link #claudeEnv}; a unit test replaces it. */
    static volatile Function<String, String> env = System::getenv;

    /** For a test in another package: these variables in place of the environment (null puts it back). */
    public static void envForTest(Map<String, String> vars) {
        env = vars == null ? System::getenv : vars::get;
    }

    /**
     * <b>Today's route</b>: what each job did before the route was configurable, so a server with no
     * {@code helper.route.*} value behaves exactly as it did. {@code museModel}: the job has a Muse model (a grader
     * whose configured model is a Muse one; always for the describer).
     */
    static List<Step> today(Job job, boolean museModel, Machine m) {
        Step claude = m.anthropicKey() ? Step.CLAUDE_KEY : Step.CLAUDE_SUBSCRIPTION;   // the CLI on its inherited env
        if (job == Job.RESEARCHER) {
            return List.of(claude);              // the Claude CLI researcher; Ember only where Claude is not chosen
        }
        List<Step> out = new ArrayList<>();
        if (museModel) {
            if (m.shim()) {
                out.add(Step.MUSE_SUBSCRIPTION);  // the server's plan through the shim, then its key
                out.add(Step.MUSE_KEY);
            } else {
                out.add(m.museKey() ? Step.MUSE_KEY : Step.MUSE_SUBSCRIPTION);   // the key if set, else the login
            }
        }
        out.add(claude);                          // the job's pinned Haiku on the Claude CLI
        return List.copyOf(out);
    }

    /** The helper chain's entries as steps, in order, each once. */
    static List<Step> fromChain(List<ModelFallback.Entry> chain) {
        List<Step> out = new ArrayList<>();
        for (ModelFallback.Entry e : chain) {
            Step st = Step.of(e.model().provider(), e.mode());
            if (!out.contains(st)) {
                out.add(st);
            }
        }
        return List.copyOf(out);
    }

    /**
     * A {@code helper.route.<job>} value as steps, or empty when unset, {@code default}, or not a valid list (an
     * unknown step is refused whole, never half-read: the server's file is checked at start, {@link #invalid}).
     * {@code none} is the empty route: the job makes no model call.
     */
    public static Optional<List<Step>> parse(String value) {
        if (value == null || value.isBlank() || "default".equalsIgnoreCase(value.strip())) {
            return Optional.empty();
        }
        if ("none".equalsIgnoreCase(value.strip())) {
            return Optional.of(List.of());
        }
        List<Step> out = new ArrayList<>();
        for (String piece : value.split(",", -1)) {
            Optional<Step> st = Step.parse(piece);
            if (st.isEmpty()) {
                return Optional.empty();
            }
            if (!out.contains(st.get())) {
                out.add(st.get());
            }
        }
        return Optional.of(List.copyOf(out));
    }

    /** Why a {@code helper.route.<job>} value cannot be used, or empty when it can. */
    public static Optional<String> invalid(String value) {
        if (value == null || value.isBlank() || "default".equalsIgnoreCase(value.strip())
                || "none".equalsIgnoreCase(value.strip())) {
            return Optional.empty();
        }
        for (String piece : value.split(",", -1)) {
            if (Step.parse(piece).isEmpty()) {
                return Optional.of("'" + piece.strip() + "' is not one of " + Step.ids());
            }
        }
        return Optional.empty();
    }

    /**
     * <b>The decision</b>, pure. {@code helperChain}: the player's helper chain when the Magician setup is in charge;
     * {@code configured}: the job's {@code helper.route.<job>} value; {@code museModel}: the job has a Muse model.
     */
    static List<Step> decide(Job job, Optional<List<ModelFallback.Entry>> helperChain, Optional<String> configured,
                             boolean museModel, Machine m) {
        Optional<List<Step>> set = configured.flatMap(HelperRoute::parse);
        if (helperChain.isPresent()) {
            List<Step> chosen = fromChain(helperChain.get());
            // The player's choice is the ceiling: a server value may narrow or reorder it, never add a provider.
            return set.map(s -> s.stream().filter(chosen::contains).toList()).orElse(chosen);
        }
        return set.orElseGet(() -> today(job, museModel, m));
    }

    /** {@code job}'s route on this machine, now. */
    public static List<Step> steps(Job job, boolean museModel) {
        return steps(job, museModel, Machine.now());
    }

    /**
     * The same, where the job reads its Muse key from its own source (the describer's direct key leg,
     * {@code reference/EmberVision}): {@code museKey} says whether that source has one.
     */
    public static List<Step> steps(Job job, boolean museModel, boolean museKey) {
        Machine m = Machine.now();
        return steps(job, museModel, new Machine(m.shim(), museKey, m.anthropicKey()));
    }

    private static List<Step> steps(Job job, boolean museModel, Machine m) {
        ModdySettings s = ModdySettings.shared();
        return decide(job, MagicianWiring.cheapChain(), s.value(job.key()), museModel, m);
    }

    /**
     * Walk {@code steps} in order: each run of Muse steps is ONE call of {@code muse} (they share a budget and the
     * server's account policy, {@code agent/MuseExec}), each Claude step one call of {@code claude}. The first non-null
     * answer wins; null when no step answered. A job with no Muse model passes {@code muse} null: its Muse steps are
     * skipped.
     */
    public static <T> T first(List<Step> steps, Function<List<Step>, T> muse, Function<Step, T> claude) {
        // every helper job's model call comes through here; a Wish Builder make is local only.
        if (com.moddingfromamod.marshal.LocalOnly.refuse("a helper model call")) {
            return null;
        }
        int i = 0;
        while (i < steps.size()) {
            Step st = steps.get(i);
            if (st.muse()) {
                List<Step> run = new ArrayList<>();
                while (i < steps.size() && steps.get(i).muse()) {
                    run.add(steps.get(i++));
                }
                T a = muse == null ? null : muse.apply(List.copyOf(run));
                if (a != null) {
                    return a;
                }
            } else {
                i++;
                T a = claude == null ? null : claude.apply(st);
                if (a != null) {
                    return a;
                }
            }
        }
        return null;
    }

    /** Only the Muse steps of a route. */
    public static List<Step> muse(List<Step> steps) {
        return steps.stream().filter(Step::muse).toList();
    }

    /** Does the route have a Claude step? */
    public static boolean hasClaude(List<Step> steps) {
        return steps.stream().anyMatch(st -> !st.muse());
    }

    /**
     * Fit a Claude CLI process's environment to {@code step}: a key step keeps {@code ANTHROPIC_API_KEY} and is
     * unusable (false) without one; a subscription step removes it, so the sign-in pays and never a key that happens
     * to be set. Nothing else is touched (a shim server's CLI is its shim, which runs as it always has).
     */
    public static boolean claudeEnv(Step step, Map<String, String> processEnv) {
        String keyVar = SupportedModels.Provider.ANTHROPIC.keyVar;
        if (step == Step.CLAUDE_SUBSCRIPTION) {
            processEnv.remove(keyVar);
            return true;
        }
        String k = processEnv.get(keyVar);
        return k != null && !k.isBlank();
    }

    /**
     * The same for Muse Code on a sign-in step: no Muse key in its environment, since each outranks Muse Code's own
     * login (docs/EMBER_GRADERS.md, the shim's {@code _carry_login}), so a key that happens to be set never pays.
     */
    public static void museEnv(Step step, Map<String, String> processEnv) {
        if (step == Step.MUSE_SUBSCRIPTION) {
            processEnv.remove("MUSE_API_KEY");
            processEnv.remove("META_API_KEY");
        }
    }
}
