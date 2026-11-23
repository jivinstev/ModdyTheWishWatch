package com.moddingfromamod.readymade;

import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * <b>The push half of the matrix: Haiku points the worker at a ready-made thing.</b>
 *
 * <p>One call per moment — (a) at the hand-over on the wish alone, (b)(c)(d) together at
 * {@code plan_wish} on the wish and its plan — and it never blocks a wish: the answer rides the
 * worker's next tool reply when it lands ({@link WishNotes}), and a wish that gets {@code none} is
 * exactly the wish it would have been.
 *
 * <p>The boundary is {@code docs/PATTERNS.md}'s: the model answers KEYS of a menu this class
 * wrote, {@link #parse} keeps only keys it was shown, and the note the worker reads is built here
 * from the index — so no word the model writes reaches the worker. Every failure is "no picks",
 * never a throw.
 *
 * <p>Pure apart from {@link CliTransport}, which takes the CLI's path and a working directory, so
 * {@code ReadyMadeCli} runs the very same call for the routing arm without a game.
 */
public final class ReadyMadeNudge {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReadyMadeNudge.class);

    private ReadyMadeNudge() {
    }

    /** How long one question is worth asking. A late answer is dropped, never waited for. */
    public static final long TIMEOUT_MS = 20_000L;


    /** What one call came back as, plus what it cost. */
    /**
     * {@code answered} separates "the model said none of these" (true, empty picks) from "no model
     * answered" (false) — a search falls back to keywords only on the second, and says so.
     */
    public record Reply(List<ReadyMade> picks, long apiMs, double costUsd, String note, boolean answered) {
        public static Reply none(String note) {
            return new Reply(List.of(), 0L, 0.0, note == null ? "" : note, false);
        }
    }

    /** How one call is made. One implementation ships; a test injects its own. */
    public interface Transport {
        /** Ask once. Must not throw; null is "no answer". */
        String ask(String systemPrompt, String userText, String jsonSchema, long timeoutMs);
    }

    /**
     * The menu the model chooses from: EVERY entry of the asked kinds, keyed {@code <letter>:<id>}
     * — ids repeat across kinds ({@code teleport} is an item ability AND a creature move), and a
     * key that could mean either is a guess.
     *
     * <p>Never trimmed by keywords: a keyword pre-filter cannot see that "a cat
     * with orange stripes" is a tiger, and a filter that starts at N entries is a behaviour change
     * nobody would notice arriving. A library too big for one call is a design decision, measured
     * 's scale arm, not a threshold.
     */
    public static Map<String, ReadyMade> menu(ReadyMadeIndex index, Set<ReadyMade.Kind> kinds) {
        Map<String, ReadyMade> out = new LinkedHashMap<>();
        for (ReadyMade.Kind k : kinds.isEmpty() ? EnumSet.noneOf(ReadyMade.Kind.class) : EnumSet.copyOf(kinds)) {
            for (ReadyMade e : index.of(EnumSet.of(k))) {
                out.put(key(e), e);
            }
        }
        return out;
    }

    public static String key(ReadyMade e) {
        return e.kind().letter + ":" + e.id();
    }

    /** The one message the model reads: the menu, then the wish, then the plan or the search. */
    /**
     * <b>What the game knows about him right now</b>, cut from the wish note the WORKER is handed —
     * so the nudge sees the same history the worker does, not a second account of it (owner
     * 2026-10-26: "make it stronger and kill things with one hit" is an item or a mob depending on
     * what he made last). The {@link com.moddingfromamod.marshal.ReferentFacts} lines only: what is in
     * his view, his last wish and what it made. {@code ""} when the note carries none.
     */
    public static String factsOf(String note) {
        if (note == null || note.isBlank()) {
            return "";
        }
        if (note.contains(com.moddingfromamod.marshal.ReferentFacts.NO_ANTECEDENT)) {
            return "- Nothing of his is in his view, and no wish of his has made or changed anything yet.";
        }
        int at = note.indexOf(com.moddingfromamod.marshal.ReferentFacts.HEADING);
        if (at < 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String[] lines = note.substring(at).split("\n");
        for (int i = 1; i < lines.length && lines[i].startsWith("- "); i++) {
            sb.append(sb.length() == 0 ? "" : "\n").append(lines[i].strip());
        }
        return sb.toString();
    }

    public static String userText(Map<String, ReadyMade> menu, String wish, List<String> steps,
                                  String need) {
        return userText(menu, wish, steps, need, "");
    }

    public static String userText(Map<String, ReadyMade> menu, String wish, List<String> steps,
                                  String need, String facts) {
        StringBuilder sb = new StringBuilder("MENU — the only keys you may answer with:\n");
        ReadyMade.Kind last = null;
        for (Map.Entry<String, ReadyMade> m : menu.entrySet()) {
            ReadyMade e = m.getValue();
            if (e.kind() != last) {
                sb.append("[").append(e.kind().noun).append("s]\n");
                last = e.kind();
            }
            sb.append("  ").append(m.getKey()).append(" — ").append(e.purpose()).append('\n');
        }
        if (facts != null && !facts.isBlank()) {
            sb.append("\nWHAT THE GAME KNOWS ABOUT HIM RIGHT NOW (facts, not his words):\n")
              .append(facts.strip()).append('\n');
        }
        sb.append("\nTHE CHILD'S WISH, between the markers:\n<<<WISH\n")
          .append(wish == null ? "" : wish.strip()).append("\nWISH>>>\n");
        if (steps != null && !steps.isEmpty()) {
            sb.append("\nTHE BUILDER'S PLAN:\n");
            for (String s : steps) {
                sb.append("  - ").append(s).append('\n');
            }
        }
        if (need != null && !need.isBlank()) {
            sb.append("\nTHE BUILDER IS SEARCHING FOR, in its own words:\n<<<NEED\n")
              .append(need.strip()).append("\nNEED>>>\n");
        }
        return sb.toString();
    }

    /** The nudge: at most three picks for this wish (and its plan). Never throws. */
    public static Reply ask(Transport t, ReadyMadeIndex index, Set<ReadyMade.Kind> kinds, String wish,
                            List<String> steps) {
        return ask(t, index, kinds, wish, steps, "");
    }

    /** The nudge with his {@link #factsOf facts} beside the wish. */
    public static Reply ask(Transport t, ReadyMadeIndex index, Set<ReadyMade.Kind> kinds, String wish,
                            List<String> steps, String facts) {
        return call(t, index, kinds, wish, steps, "", 3, facts);
    }

    /** The search: at most five picks for what the builder searched for, read beside the wish. */
    public static Reply search(Transport t, ReadyMadeIndex index, Set<ReadyMade.Kind> kinds,
                               String wish, String need) {
        return call(t, index, kinds, wish, List.of(), need, 5, "");
    }

    private static Reply call(Transport t, ReadyMadeIndex index, Set<ReadyMade.Kind> kinds,
                              String wish, List<String> steps, String need, int max, String facts) {
        boolean noWish = wish == null || wish.isBlank();
        boolean noNeed = need == null || need.isBlank();
        if (t == null || kinds.isEmpty() || (noWish && noNeed)) {
            return Reply.none("nothing to ask");
        }
        Map<String, ReadyMade> menu = menu(index, kinds);
        if (menu.isEmpty()) {
            return Reply.none("empty menu");
        }
        String prompt = resource("/readymade/nudge-prompt.md");
        String schema = resource("/readymade/nudge-schema.json");
        if (prompt.isEmpty() || schema.isEmpty()) {
            return Reply.none("the nudge prompt or schema is not on the classpath");
        }
        try {
            return parse(t.ask(prompt, userText(menu, wish, steps, need, facts), schema, TIMEOUT_MS), menu, max);
        } catch (Throwable e) {
            return Reply.none("the nudge could not run: " + e);
        }
    }

    /** The CLI's JSON envelope → picks that are keys of {@code menu}; anything else is dropped. */
    public static Reply parse(String raw, Map<String, ReadyMade> menu, int max) {
        if (raw == null || raw.isBlank()) {
            return Reply.none("no reply");
        }
        try {
            int a = raw.indexOf('{');
            int b = raw.lastIndexOf('}');
            if (a < 0 || b <= a) {
                return Reply.none("reply was not JSON");
            }
            JsonObject o = JsonParser.parseString(raw.substring(a, b + 1)).getAsJsonObject();
            long apiMs = o.has("duration_api_ms") ? safeLong(o.get("duration_api_ms")) : 0L;
            double cost = o.has("total_cost_usd") ? safeDouble(o.get("total_cost_usd")) : 0.0;
            JsonElement so = o.has("structured_output") ? o.get("structured_output") : o;
            if (so == null || !so.isJsonObject() || !so.getAsJsonObject().has("picks")) {
                return new Reply(List.of(), apiMs, cost, "no structured_output", false);
            }
            List<ReadyMade> picks = new ArrayList<>();
            int dropped = 0;
            for (JsonElement p : so.getAsJsonObject().getAsJsonArray("picks")) {
                ReadyMade e = p.isJsonPrimitive() ? menu.get(p.getAsString().strip()) : null;
                if (e == null) {
                    dropped++;
                } else if (!picks.contains(e) && picks.size() < max) {
                    picks.add(e);
                }
            }
            return new Reply(List.copyOf(picks), apiMs, cost,
                    dropped > 0 ? "dropped " + dropped + " key(s) not on the menu" : "", true);
        } catch (RuntimeException e) {
            return Reply.none("reply could not be read: " + e);
        }
    }

    /**
     * What the worker reads. Worded as an OPTION, never an order: the issue's rule, and the reason
     * a wrong pick costs a sentence of reading rather than a wrong grant.
     */
    public static String note(List<ReadyMade> picks, boolean canDescribe) {
        if (picks == null || picks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("📚 READY-MADE things that may fit this wish — use one "
                + "if it really does what he asked, or carry on as you were if none of these fits:");
        for (ReadyMade e : picks) {
            sb.append("\n  • ").append(e.kind().noun).append(": ").append(e.line());
        }
        if (canDescribe) {
            sb.append("\n  describe_ready_made shows any of these in full.");
        }
        return sb.toString();
    }

    private static long safeLong(JsonElement e) {
        try {
            return e.getAsLong();
        } catch (RuntimeException x) {
            return 0L;
        }
    }

    private static double safeDouble(JsonElement e) {
        try {
            return e.getAsDouble();
        } catch (RuntimeException x) {
            return 0.0;
        }
    }

    static String resource(String path) {
        // A prompt's Ember-only fork (`X.ember.md`) is registered as it is read (agent/MusePrompts).
        return com.moddingfromamod.agent.MusePrompts.load(ReadyMadeNudge.class, path);
    }

    /**
     * The shipped transport: one {@code claude -p} subprocess, no tools, one JSON schema — the
     * cache classifier's invocation flag for flag (its javadoc says what each flag bought), from a
     * clean working directory so no CLAUDE.md is read as a mission.
     */
    public static final class CliTransport implements Transport {
        private final String cli;
        private final String model;
        private final String effort;
        private final Path cwd;

        public CliTransport(String cli, String model, String effort, Path cwd) {
            this.cli = cli;
            this.model = model;
            this.effort = effort;
            this.cwd = cwd;
        }

        @Override
        public String ask(String systemPrompt, String userText, String jsonSchema, long timeoutMs) {
            boolean muse = com.moddingfromamod.agent.MuseGrade.isMuse(model);
            // Which of the job's two models may be asked, and how each is paid: the route (HelperRoute), never a key
            // this transport happens to see.
            var steps = com.moddingfromamod.config.HelperRoute.steps(com.moddingfromamod.config.HelperRoute.Job.READY_MADE,
                    muse);
            return com.moddingfromamod.config.HelperRoute.first(steps, !muse ? null : museSteps -> {
                // A Muse model (docs/EMBER_GRADERS.md): Muse with the prompt's Ember fork; when it
                // gives no answer, the same ask of the pinned Haiku with the ORIGINAL prompt below.
                com.moddingfromamod.agent.MuseExec.Answer a = com.moddingfromamod.agent.MuseExec.grade(
                        com.moddingfromamod.agent.MusePrompts.forMuse(systemPrompt), userText, jsonSchema,
                        com.moddingfromamod.agent.MuseGrade.modelId(model),
                        effort == null || effort.isBlank() || "none".equalsIgnoreCase(effort) ? "low" : effort,
                        Math.min(timeoutMs, com.moddingfromamod.agent.MuseExec.TIMEOUT_MS), museSteps);
                if (a == null) {
                    LOGGER.info("ready-made nudge: model={} ms=- arm={} (Muse gave no answer)",
                            com.moddingfromamod.safety.SafetyGrader.HAIKU_PIN, com.moddingfromamod.agent.MuseExec.ARM_HAIKU);
                    return null;
                }
                // WHICH MODEL ANSWERED, in a fixed shape a log reader can rely on: an Ember
                // success, told apart from the Haiku fallback below.
                LOGGER.info("ready-made nudge: model={} ms={} arm={}", a.modelId(), a.wallMs(), a.arm());
                // The envelope parse() reads from the Claude CLI, built from the Muse answer.
                com.google.gson.JsonObject env = new com.google.gson.JsonObject();
                env.add("structured_output", com.google.gson.JsonParser.parseString(a.text()));
                env.addProperty("total_cost_usd", a.costUsd());
                env.addProperty("duration_api_ms", a.wallMs());
                env.addProperty("model", a.modelId());
                env.addProperty("arm", a.arm());     //: which arm of the chain answered
                return env.toString();
            }, step -> claude(muse ? com.moddingfromamod.safety.SafetyGrader.HAIKU_PIN : model, systemPrompt, userText,
                    jsonSchema, timeoutMs, step));
        }

        /** One ask of the Claude CLI as {@code claudeModel}, paid as {@code step} says. */
        private String claude(String claudeModel, String systemPrompt, String userText, String jsonSchema,
                              long timeoutMs, com.moddingfromamod.config.HelperRoute.Step step) {
            String model = claudeModel;
            if (cli == null || cli.isBlank()) {
                return null;
            }
            Process p = null;
            try {
                List<String> cmd = new ArrayList<>(List.of(cli, "-p", userText,
                        "--system-prompt", systemPrompt,
                        "--model", model,
                        "--tools", "", "--allowed-tools", "", "--strict-mcp-config",
                        "--max-turns", "3", "--permission-mode", "dontAsk",
                        "--output-format", "json", "--json-schema", jsonSchema));
                if (effort != null && !effort.isBlank() && !"none".equalsIgnoreCase(effort)) {
                    cmd.add("--effort");
                    cmd.add(effort);
                }
                ProcessBuilder pb = new ProcessBuilder(cmd);
                if (!com.moddingfromamod.config.HelperRoute.claudeEnv(step, pb.environment())) {
                    return null;                 // a key step with no key: the route's next step
                }
                pb.redirectErrorStream(true);
                Files.createDirectories(cwd);
                pb.directory(cwd.toFile());
                p = pb.start();
                p.getOutputStream().close();
                StringBuilder out = new StringBuilder();
                Process proc = p;
                Thread reader = new Thread(() -> {
                    try (var in = proc.getInputStream()) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                        }
                    } catch (Exception ignored) {
                        // A closed pipe is how this ends normally.
                    }
                }, "mfam-readymade-read");
                reader.setDaemon(true);
                reader.start();
                if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly();
                    return null;
                }
                reader.join(2_000);
                return out.toString();
            } catch (Exception e) {
                return null;
            } finally {
                if (p != null && p.isAlive()) {
                    p.destroyForcibly();
                }
            }
        }
    }
}
