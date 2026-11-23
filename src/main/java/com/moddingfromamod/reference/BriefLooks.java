package com.moddingfromamod.reference;

import com.moddingfromamod.agent.ClaudeCli;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b> (b): a description of how a character LOOKS, written by a vision model from its pictures.</b>
 *
 * <p>The researcher is text only by design, and its pictures are only ever COUNTED
 * ({@link ReferencePalette}). This is the one place a model looks at them: the reference artwork the
 * researcher's pages already supplied, downloaded by {@link ReferenceImages} (its public-address check
 * and size bounds), and shown to a model in its first message, which answers in one turn with no tool at
 * all: no wish tools, no file tools, no web and no world. What it writes is not trusted: the caller
 * passes it through {@link WishBrief#injection} and the child-safety grader before any worker sees it.
 */
public final class BriefLooks {

    private static final Logger LOGGER = LoggerFactory.getLogger(BriefLooks.class);

    /** Pictures shown to the describer: enough to see past one pose, few enough to stay quick. */
    public static final int MAX_PICTURES = 3;   // speed: 5 cost a turn per picture; 3 still see past one pose

    public static final long TIMEOUT_SECONDS = 90;

    /** {@code -Ptestprops wishbrieflookmodel=…}: the describer's model (a small vision model by default). */
    public static final String MODEL_PROPERTY = "moddingfromamod.wishbrieflookmodel";

    /**
     * {@code -Ptestprops wishbrieflook=ember|haiku}: {@code ember} (the default) runs the whole chain in
     * {@link #describePictures}; {@code haiku} skips Ember and asks the Haiku CLI only.
     */
    public static final String DESCRIBER_PROPERTY = "moddingfromamod.wishbrieflook";

    /**
     * {@code -Ptestprops wishbrieflookeffort=minimal|low|…}: overrides the describer's effort on every step. Unset:
     * Ember at minimal (both legs) and the Haiku CLI at low, its fastest measured (2026-11-19: Ember 4.7 s at
     * low against 2.2-2.4 s at minimal; the Haiku CLI 16.0 s at its default against 10.4 s at low).
     */
    public static final String EFFORT_PROPERTY = "moddingfromamod.wishbrieflookeffort";

    /** The Haiku CLI's effort: low, its fastest measured (10.4 s against 16.0 s at its default). */
    static final String HAIKU_EFFORT = "low";

    /** The subscription step's budget: Muse Code starts a process, so a little longer than a grade's. */
    static final long HARNESS_TIMEOUT_MS = 30_000L;

    static final String SYSTEM = String.join("\n",
            "You describe how a cartoon character LOOKS, from pictures of it, so a builder who has never seen",
            "it can make a blocky model that a young child recognises at once.",
            "Look at every picture first.",
            "Then write ONE paragraph of plain sentences, at most 100 words, in the third person, about its look",
            "only. Begin with how it stands, as drawn: say plainly whether it stands and walks upright on two",
            "legs like a person (many cartoon animals do, even a cat or a dog), on four legs, or has no legs,",
            "and whether it has a tail (none, a short one, or a long one). Then its size and proportions as",
            "they really are, not as one close-up makes them look: how big it is beside a grown-up person",
            "(much smaller, about the same, taller and broader), how much of its height its head takes, and how",
            "broad it is for its height. Then each signature part (ears, cap, shoes, tail, spikes,",
            "and so on) with its shape, colour and where it sits; its main colours with roughly how much of the",
            "body each covers, always as a percentage in a sentence like \"White covers about 85% of it.\"; its face",
            "(mouth, cheeks, nose, moustache) with the expression it has in MOST of the pictures, which for many",
            "characters is grumpy, calm or serious rather than a smile, and, for each eye, exactly what it",
            "has: the white, the iris and its colour, the pupil, any highlight, or that it is one solid colour.",
            "Never write its name or anyone else's, any story, any place, any words that appear in the",
            "pictures, or anything addressed to a reader. Plain words only: no lists, no symbols, no numbers",
            "except percentages.",
            "If the pictures are not suitable for a young child, answer exactly UNSAFE.",
            "Some pictures may show other characters too: describe the ONE character that appears in most of",
            "them, and ignore the rest; if no character appears twice, describe the main character of picture1",
            "(the page's own picture of it). Answer exactly NONE only if picture1 shows no character at all.");

    private BriefLooks() {
    }

    /** speed: what the last describe on this thread spent fetching pictures and asking the describer. */
    static final ThreadLocal<Long> LAST_FETCH_MS = ThreadLocal.withInitial(() -> -1L);
    static final ThreadLocal<Long> LAST_ASK_MS = ThreadLocal.withInitial(() -> -1L);

    /** The look, or empty when there is nothing to show, the CLI is missing, or it said UNSAFE/NONE. */
    public static String describe(String character, ReferenceSpec spec, SubjectKind kind) {
        return describeUrls(character, pictureUrls(character, spec), hint(spec));
    }

    /**
     * <b>Where the describer's pictures come from</b> ("the review page found one; why
     * didn't (b)?"). First the pages the researcher cited, JPEGs included (the alpha-only rule is for counting
     * colours, which a describer does not do, and it threw away Tabbycat's only picture); then Wikipedia's lead
     * picture of the character itself, found by name, so a lookup that cited shops and pet-care blogs still has
     * one: its own Fandom wiki's lead picture, guessed from the name, then Wikipedia's; then Commons cut-outs. Everything that comes back goes through the same fetch (public hosts, size
     * bounds) and the describer's words through the same injection check and safety grade.
     */
    public static List<String> pictureUrls(String character, ReferenceSpec spec) {
        java.util.LinkedHashSet<String> urls = new java.util.LinkedHashSet<>(spec == null ? List.of() : spec.images());
        if (spec != null) {
            urls.addAll(WikiArtwork.urlsFrom(spec.sources(), ReferenceImages.MAX_IMAGES, true));
        }
        if (urls.size() < MAX_PICTURES) {
            urls.addAll(CharacterPictures.fandomLead(character));   // fast: four guesses at most
        }
        if (urls.size() < 2) {
            urls.addAll(CharacterPictures.leadImages(character));   // Wikipedia, which can rate-limit and retry
        }
        if (urls.size() < MAX_PICTURES) {
            // the same fallback the palette measurement uses when a franchise wiki gives nothing
            urls.addAll(CommonsCutouts.urlsFor(character, ReferenceImages.MAX_IMAGES));
        }
        return List.copyOf(urls);
    }

    /** What the written reference already knows about size and expression, in closed words, or "". */
    static String hint(ReferenceSpec spec) {
        if (spec == null) {
            return "";
        }
        List<String> bits = new ArrayList<>();
        if (spec.size() != ReferenceSpec.Size.UNKNOWN) {
            bits.add("it is " + spec.size().name().toLowerCase(java.util.Locale.ROOT) + "-sized");
        }
        if (spec.expression() != ReferenceSpec.Expression.UNKNOWN) {
            bits.add("it usually looks " + spec.expression().name().toLowerCase(java.util.Locale.ROOT));
        }
        return bits.isEmpty() ? "" : " Its written reference says " + String.join(" and ", bits)
                + "; describe what the pictures show, and where they are unclear, follow this.";
    }

    /** The look from these picture links (speed: also called early, on the first cited page's pictures). */
    public static String describeUrls(String character, List<String> urls) {
        return describeUrls(character, urls, "");
    }

    /** With a hint from the written reference (its size and expression), when there is one. */
    public static String describeUrls(String character, List<String> urls, String hint) {
        if (urls == null || urls.isEmpty()) {
            LOGGER.info("brief look for '{}': no pictures to describe", character);
            return "";
        }
        long tf = System.currentTimeMillis();
        List<ReferenceImages.Fetched> got = ReferenceImages.fetch(urls);
        LAST_FETCH_MS.set(System.currentTimeMillis() - tf);
        if (got.isEmpty()) {
            return "";
        }
        List<java.awt.image.BufferedImage> images = new ArrayList<>();
        for (ReferenceImages.Fetched f : got) {
            images.add(f.image());
        }
        return describePictures(character, images, hint);
    }

    /**
     * <b>The describer's chain</b> ("Ember at its fastest by default, and the Haiku backup
     * solidly working"), each step only when the one before gave nothing:
     * <ol>
     *   <li>Ember on a sign-in ({@code muse-subscription}: a dedicated server's own Muse Code subscription through the shim, by the
     *       same sticky policy and account files as every Ember grade, or on a local install Muse Code's own login),
     *       effort minimal, the pictures as {@code --image};</li>
     *   <li>Ember on the API key ({@code muse-key}), DIRECT ({@link EmberVision}: the Responses API at effort minimal,
     *       2.4 s; through {@code muse exec} on the key measured 2.2 s, so the simpler one, with no process, is the key
     *       leg);</li>
     *   <li>Haiku on the Claude CLI ({@link #ask}, {@code claude-key} or {@code claude-subscription}), the pictures in
     *       its first message, at {@code --effort low};</li>
     *   <li>nothing: the caller ({@link WishBriefs}) writes the structured look from the reference, and the wish goes on.</li>
     * </ol>
     * WHICH of these run, in what order, is the job's route ({@link com.moddingfromamod.config.HelperRoute}): on a
     * single-player host only the provider and payment their Magician picks chose; on a dedicated server the
     * {@code helper.route.describer} setting, else exactly the order above as each machine ran it before.
     * {@code wishbrieflook=haiku} drops the Ember steps.
     */
    static String describePictures(String character, List<java.awt.image.BufferedImage> images, String hint) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("mfam-brief-look-");
            int n = 0;
            List<java.awt.image.BufferedImage> pictures = new ArrayList<>();
            for (java.awt.image.BufferedImage raw : images) {
                if (n >= MAX_PICTURES) {
                    break;
                }
                java.awt.image.BufferedImage img = scaled(raw);
                pictures.add(img);
                javax.imageio.ImageIO.write(img, "png", dir.resolve("picture" + (++n) + ".png").toFile());
            }
            if (n == 0) {
                return "";
            }
            List<String> files = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                files.add(dir.resolve("picture" + i + ".png").toAbsolutePath().toString());
            }
            long ta = System.currentTimeMillis();
            String ask = "Describe the character in these pictures." + hint;
            boolean ember = !"haiku".equals(System.getProperty(DESCRIBER_PROPERTY, "ember"));
            // Which describer may be asked, and how each is paid: the route (HelperRoute). A
            // Claude-only player with MUSE_API_KEY in the environment never reaches Meta; a Muse-only one never
            // reaches Anthropic. wishbrieflook=haiku drops the Muse steps (a developer's switch).
            var steps = com.moddingfromamod.config.HelperRoute.steps(com.moddingfromamod.config.HelperRoute.Job.DESCRIBER,
                    true, EmberVision.hasKey());
            if (!ember) {
                steps = steps.stream().filter(st -> !st.muse()).toList();
            }
            String[] by = {""};
            Path pdir = dir;
            String out = com.moddingfromamod.config.HelperRoute.first(steps, museSteps -> {
                for (var st : museSteps) {
                    String o;
                    if (st == com.moddingfromamod.config.HelperRoute.Step.MUSE_KEY) {
                        // Ember on the API key, DIRECT (EmberVision: the Responses API at effort minimal, 2.4 s).
                        o = EmberVision.describe(SYSTEM, ask, pictures);
                        by[0] = "Ember, API key";
                    } else {
                        // Ember on a sign-in: a server's subscription through the shim, else Muse Code's own login
                        // ("only a Muse subscription must still get a described look").
                        var a = com.moddingfromamod.agent.MuseExec.describe(SYSTEM, ask,
                                files.stream().map(Path::of).toList(), com.moddingfromamod.config.SupportedModels.EMBER,
                                effort(EmberVision.EFFORT), HARNESS_TIMEOUT_MS, List.of(st));
                        o = a == null ? null : a.text();
                        by[0] = "Ember, " + (com.moddingfromamod.agent.MuseExec.shim() != null ? "subscription"
                                : "Muse Code login");
                    }
                    if (!sane(o).isEmpty()) {
                        return o;
                    }
                }
                return null;
            }, st -> {
                try {
                    String o = ask(pdir, files, hint, st);
                    by[0] = "Haiku";
                    return sane(o).isEmpty() ? null : o;
                } catch (Exception e) {
                    return null;
                }
            });
            LAST_ASK_MS.set(System.currentTimeMillis() - ta);
            String look = fit(sane(out));
            LOGGER.info("brief look for '{}': {} picture(s), {} chars by {} in {} ms{}", character, n,
                    out == null ? -1 : out.length(), look.isEmpty() ? "nobody" : by[0], System.currentTimeMillis() - ta,
                    out != null && sane(out).isEmpty() ? " (" + out.strip().substring(0, Math.min(160, out.strip().length())) + ")" : "");
            return look;
        } catch (Exception e) {
            LOGGER.info("brief look for '{}' failed: {}", character, e.toString());
            return "";
        } finally {
            if (dir != null) {
                try (var s = Files.list(dir)) {
                    for (Path p : s.toList()) {
                        Files.deleteIfExists(p);
                    }
                    Files.deleteIfExists(dir);
                } catch (Exception ignored) {
                    // a temp directory left behind is harmless
                }
            }
        }
    }

    /** Where {@link #fit} stops: under {@link WishBrief#MAX_LOOK_CHARS}, so a long look is never refused for length. */
    static final int FIT_CHARS = 850;

    /**
     * A look longer than {@link #FIT_CHARS} keeps its whole sentences up to there (at effort minimal the answers
     * run 120-126 words against the prompt's 100, ~750 characters, which is ACCEPTED; this only stops a rare very long
     * one from failing the injection check's 900-character limit, which would cost the brief). Never cut mid-sentence.
     */
    static String fit(String look) {
        if (look == null || look.length() <= FIT_CHARS) {
            return look == null ? "" : look;
        }
        int cut = -1;
        for (int i = 0; i < FIT_CHARS; i++) {
            char c = look.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && (i + 1 == look.length() || look.charAt(i + 1) == ' ')) {
                cut = i + 1;
            }
        }
        return cut > 0 ? look.substring(0, cut) : look.substring(0, FIT_CHARS);
    }

    /** Pictures over 512 px are shrunk: the describer needs the shape, not the pixels (speed: was 768). */
    static java.awt.image.BufferedImage scaled(java.awt.image.BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight(), max = Math.max(w, h);
        if (max <= 512) {
            return img;
        }
        double k = 512.0 / max;
        int nw = Math.max(1, (int) (w * k)), nh = Math.max(1, (int) (h * k));
        var out = new java.awt.image.BufferedImage(nw, nh, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }

    /** The model's answer reduced to one paragraph; UNSAFE, NONE and junk become empty. */
    static String sane(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.strip().replaceAll("\\s+", " ");
        if (s.isEmpty() || s.equalsIgnoreCase("UNSAFE") || s.equalsIgnoreCase("NONE")
                || s.toUpperCase(java.util.Locale.ROOT).startsWith("UNSAFE")) {
            return "";
        }
        return s;
    }

    /**
     * The Haiku CLI with the pictures in its FIRST message (speed): {@code stream-json}
     * input carrying image blocks, so it is one model turn with NO tools at all (it used to be given the Read
     * tool and spend a turn reading the files), no MCP, no settings files, no skills, run in the pictures'
     * fresh directory. Whether it runs on a sign-in or an API key is the CLI's own choice, as for
     * every other Claude CLI call here.
     */
    private static String ask(Path dir, List<String> files, String hint,
                              com.moddingfromamod.config.HelperRoute.Step step) throws Exception {
        String cli = ClaudeCli.resolve();
        if (cli == null || cli.isBlank()) {
            return null;
        }
        String model = System.getProperty(MODEL_PROPERTY, com.moddingfromamod.safety.ChildSafetyConfig.HAIKU_55);
        ProcessBuilder pb = new ProcessBuilder(haikuArgv(cli, model, effort(HAIKU_EFFORT)));
        if (!com.moddingfromamod.config.HelperRoute.claudeEnv(step, pb.environment())) {
            return null;                         // a key step with no key: the route's next step
        }
        Path answer = Files.createTempFile("mfam-brief-look-", ".jsonl");   // outside the pictures' directory
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.redirectOutput(answer.toFile());
        pb.directory(dir.toFile());
        Process p = pb.start();
        try (var in = p.getOutputStream()) {   // the one message, then EOF (PlatformParityTest: no /dev/null)
            in.write(message(files, "Describe the character in these pictures." + hint).getBytes(StandardCharsets.UTF_8));
            in.write('\n');
        }
        try {
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.info("brief look timed out after {} s", TIMEOUT_SECONDS);
                return null;
            }
            return result(Files.readString(answer, StandardCharsets.UTF_8));
        } finally {
            if (p.isAlive()) {
                p.destroyForcibly();
            }
            Files.deleteIfExists(answer);
        }
    }

    /** The Haiku CLI's command line: one turn, the pictures in the message, and nothing it could use. */
    static List<String> haikuArgv(String cli, String model, String effort) {
        List<String> a = new ArrayList<>(List.of(cli, "-p",
                "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
                "--system-prompt", SYSTEM,
                "--model", model,
                "--tools", "",                     // it sees the pictures in the message: no tool of any kind
                "--strict-mcp-config",
                "--setting-sources", "",
                "--disable-slash-commands",
                "--max-turns", "1",
                "--permission-mode", "dontAsk"));
        if (effort != null) {
            a.add("--effort");
            a.add(effort);
        }
        return List.copyOf(a);
    }

    /** The harness routes' effort: the knob's word when it is one, else {@code dflt} (null: the CLI's own). */
    static String effort(String dflt) {
        String v = System.getProperty(EFFORT_PROPERTY, "").strip().toLowerCase(java.util.Locale.ROOT);
        return v.matches("minimal|low|medium|high") ? v : dflt;
    }

    /** One stream-json user message: each picture as a base64 PNG block, then the ask. */
    static String message(List<String> files, String text) throws java.io.IOException {
        com.google.gson.JsonArray content = new com.google.gson.JsonArray();
        for (String f : files) {
            com.google.gson.JsonObject src = new com.google.gson.JsonObject();
            src.addProperty("type", "base64");
            src.addProperty("media_type", "image/png");
            src.addProperty("data", java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(Path.of(f))));
            com.google.gson.JsonObject img = new com.google.gson.JsonObject();
            img.addProperty("type", "image");
            img.add("source", src);
            content.add(img);
        }
        com.google.gson.JsonObject t = new com.google.gson.JsonObject();
        t.addProperty("type", "text");
        t.addProperty("text", text);
        content.add(t);
        com.google.gson.JsonObject msg = new com.google.gson.JsonObject();
        msg.addProperty("role", "user");
        msg.add("content", content);
        com.google.gson.JsonObject line = new com.google.gson.JsonObject();
        line.addProperty("type", "user");
        line.add("message", msg);
        return line.toString();
    }

    /** The {@code result} event's text from the CLI's stream-json output, or null when it did not succeed. */
    static String result(String jsonl) {
        String last = null;
        for (String l : jsonl.split("\n")) {
            if (l.isBlank()) {
                continue;
            }
            try {
                var o = com.google.gson.JsonParser.parseString(l).getAsJsonObject();
                if ("result".equals(str(o, "type"))) {
                    boolean error = o.has("is_error") && o.get("is_error").isJsonPrimitive()
                            && o.get("is_error").getAsBoolean();
                    last = "success".equals(str(o, "subtype")) && !error ? str(o, "result") : null;
                }
            } catch (RuntimeException ignored) {
                // a line that is not JSON is not the result
            }
        }
        return last;
    }

    private static String str(com.google.gson.JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
    }
}
