package com.moddingfromamod.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * <b>The Magician setup's one-button choices</b>: "Use my Claude subscription", "Use my Muse Code
 * subscription", "Use API keys". Each sets all four model choices at once, from {@link SupportedModels}; the setup
 * then checks that program and runs the full Magician check ({@link MagicianSetup}). "Choose models myself" keeps the
 * per-choice pages for anything else (fallbacks, mixed providers).
 *
 * <p>Also the one thing to do when a step fails, in a player's words ({@link #whatToDo}, {@link #checkFailed}).
 *
 * <p>Pure: no Minecraft, no program run. Which programs are on this computer and which keys are set arrive as
 * arguments ({@code MagicianPresetsTest}).
 */
public final class MagicianPresets {

    private MagicianPresets() {
    }

    public static final String CLAUDE = "use_claude";
    public static final String MUSE = "use_muse";
    public static final String API = "use_api";

    /**
     * One button: its tile id and words, the program it needs, and the four choices it sets.
     *
     * @param says what the result line calls it: "your Claude subscription"
     */
    public record Preset(String id, String label, SupportedModels.Harness harness, SupportedModels.Mode mode,
                         String main, String cheap, String says) {

        public MagicianPicks picks() {
            return new MagicianPicks(main, mode.id, cheap, mode.id);
        }

        public SupportedModels.Model mainModel() {
            return SupportedModels.find(main).orElseThrow();
        }

        public SupportedModels.Model cheapModel() {
            return SupportedModels.find(cheap).orElseThrow();
        }

        /** The tile's lines: which models, and what it costs. */
        public List<String> lore() {
            List<String> out = new ArrayList<>();
            out.add("Wish model: " + mainModel().label() + ". Helper: " + cheapModel().label() + ".");
            if (mode == SupportedModels.Mode.API) {
                out.add("Charged to " + mainModel().provider().keyVar + ": " + mainModel().apiPrice() + ".");
            } else {
                out.add("Your " + harness.label + " plan: no charge per wish.");
            }
            out.add("Moddy checks it all and turns the Magician on (about 3 minutes, once).");
            return out;
        }
    }

    /** Opus 5.5 and Haiku 5.5, both on the Claude Code sign-in. */
    public static final Preset CLAUDE_SUBSCRIPTION = new Preset(CLAUDE, "Use my Claude subscription",
            SupportedModels.Harness.CLAUDE_CODE, SupportedModels.Mode.SUBSCRIPTION, SupportedModels.OPUS,
            SupportedModels.HAIKU, "your Claude subscription");
    /** Wonder and Ember, both on the Muse Code sign-in. */
    public static final Preset MUSE_SUBSCRIPTION = new Preset(MUSE, "Use my Muse Code subscription",
            SupportedModels.Harness.MUSE_CODE, SupportedModels.Mode.SUBSCRIPTION, SupportedModels.WONDER,
            SupportedModels.EMBER, "your Muse Code subscription");
    /** Opus 5.5 and Haiku 5.5 on ANTHROPIC_API_KEY. */
    public static final Preset ANTHROPIC_KEY = new Preset(API, "Use API keys", SupportedModels.Harness.CLAUDE_CODE,
            SupportedModels.Mode.API, SupportedModels.OPUS, SupportedModels.HAIKU, "your Anthropic API key");
    /** Spark Contributor (the low-cost one) and Ember on MUSE_API_KEY, when only that key is set. */
    public static final Preset MUSE_KEY = new Preset(API, "Use API keys", SupportedModels.Harness.MUSE_CODE,
            SupportedModels.Mode.API, SupportedModels.SPARK_CONTRIBUTOR, SupportedModels.EMBER, "your Muse API key");

    private static boolean set(Function<String, String> env, SupportedModels.Provider p) {
        String v = env.apply(p.keyVar);
        return v != null && !v.isBlank();
    }

    /**
     * The buttons for this computer, in order: a subscription button per program found, then "Use API keys" when a
     * key's environment variable is set (Anthropic's first when both are). On a dedicated server Claude's subscription
     * is never offered (D29), though a server is set up in its file and never sees this page.
     *
     * @param found the programs installed here (a file lookup, nothing run)
     */
    public static List<Preset> offered(Set<SupportedModels.Harness> found, Function<String, String> env,
                                       boolean dedicated) {
        List<Preset> out = new ArrayList<>();
        if (found.contains(SupportedModels.Harness.CLAUDE_CODE) && !dedicated) {
            out.add(CLAUDE_SUBSCRIPTION);
        }
        if (found.contains(SupportedModels.Harness.MUSE_CODE)) {
            out.add(MUSE_SUBSCRIPTION);
        }
        if (set(env, SupportedModels.Provider.ANTHROPIC) && found.contains(SupportedModels.Harness.CLAUDE_CODE)) {
            out.add(ANTHROPIC_KEY);
        } else if (set(env, SupportedModels.Provider.MUSE) && found.contains(SupportedModels.Harness.MUSE_CODE)) {
            out.add(MUSE_KEY);
        }
        return out;
    }

    /** The button with this tile id on this computer. */
    public static Optional<Preset> find(String id, Set<SupportedModels.Harness> found, Function<String, String> env,
                                        boolean dedicated) {
        return offered(found, env, dedicated).stream().filter(p -> p.id().equals(id)).findFirst();
    }

    /** Said when no program is installed: the one thing to do. */
    public static final String NOTHING_FOUND = "Install Claude Code or Muse Code and sign in, then open this page again.";

    /**
     * The one thing to do when {@code preset}'s program did not work, or empty when it did. {@code r} is the program
     * check's answer (null when it never ran).
     */
    public static Optional<String> whatToDo(Preset preset, HarnessProbe.Result r) {
        SupportedModels.Harness h = preset.harness();
        boolean muse = h == SupportedModels.Harness.MUSE_CODE;
        if (r == null || !r.installed()) {
            return Optional.of(h.label + " isn't installed. Install it, sign in, then press the button again.");
        }
        if (r.works(preset.mode())) {
            return Optional.empty();
        }
        String why = r.answers().get(preset.mode());
        why = why == null ? "" : why;
        if (preset.mode() == SupportedModels.Mode.API) {
            String var = preset.mainModel().provider().keyVar;
            if (why.contains("refused")) {
                return Optional.of("Your " + var + " was refused. Check the key, restart Minecraft, then press the"
                        + " button again.");
            }
            return Optional.of(h.label + " didn't answer on " + var + (why.isEmpty() ? "" : " (" + why + ")")
                    + ". Try the button again in a minute.");
        }
        if (why.contains("not signed in")) {
            return Optional.of(h.label + " isn't signed in. Run `" + (muse ? "muse login" : "claude")
                    + "` once in a terminal" + (muse ? "" : ", sign in") + ", then press the button again.");
        }
        return Optional.of(h.label + " didn't answer" + (why.isEmpty() ? "" : " (" + why + ")")
                + ". Try the button again in a minute.");
    }

    /** The result line when the full check did not pass: where, and the one thing to do. */
    public static String checkFailed(MagicianCheck.Report report) {
        String at = report.failed();
        return "✖ The Magician check didn't pass" + (at.isEmpty() ? "" : " at " + at)
                + ". The Magician stays off: press the button to try again.";
    }

    /** The result line when it passed: the Magician is on, and on what. */
    public static String turnedOn(Preset preset) {
        return "✪ The Magician is on: " + shortLabel(preset.mainModel()) + " on " + preset.says()
                + ". Your typed wishes go to the Magician now.";
    }

    /** A model's label without its effort note: "Opus 5.5 (low)" reads "Opus 5.5" on a button. */
    public static String shortLabel(SupportedModels.Model m) {
        String l = m.label();
        int at = l.indexOf(" (");
        return at > 0 ? l.substring(0, at) : l;
    }

    /** The watch tile's words: how to turn it on, or what it runs on. */
    public static final String TURN_ON = "✪ Turn on the Magician";
    public static final String OFF_GUEST = "✪ Magician: off";

    public static String onTile(MagicianPicks picks) {
        return picks.main().map(m -> "✪ Magician: on (" + shortLabel(m) + ")").orElse("✪ Magician: on");
    }

    /** What the progress line says at each step of the one-button flow. */
    public static String progress(int step, String words) {
        return "✪ Magician setup " + step + "/3: " + words;
    }
}
