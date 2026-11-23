package com.moddingfromamod.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.config.SupportedModels;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Which model runs the next worker, when the first one is out of quota</b>: the public mod's automatic,
 * configurable fallback. An ordered CHAIN of entries (a supported model and how it is paid for: Claude sign-in, Claude
 * API key, Muse Code sign-in, Muse API key); each worker launch takes the first entry that is not skipped.
 *
 * <ul>
 *   <li><b>What falls through</b> ({@link Failure#fallsThrough}): a spent quota or window, a rate limit, a sign-in or
 *       key that is missing or refused, no active subscription, a plan that does not serve the model. Any other error
 *       is the wish's, not the account's, and is NOT fallen through: the same entry runs the next worker.</li>
 *   <li><b>Sticky</b> ({@link State}): an entry that failed for one of those reasons is skipped until its window
 *       resets (the time the 429 names), or for a cooldown, or until its sign-in file changes, so a wish never pays a
 *       failed start each time. The cooldowns match the server launch shim's ({@code tools/muse-cli-shim.py}: an hour for a quota
 *       with no reset time, half an hour for a failing sign-in, an hour for no subscription).</li>
 *   <li><b>Never mid-wish</b>: the decision is made at a worker's LAUNCH only; a worker already granting a wish keeps
 *       its model, and the switch reaches the next launch.</li>
 *   <li><b>Said in plain words</b> ({@link #switchNotice}), with a paid entry's price (D27).</li>
 * </ul>
 *
 * <p>The classifier's wording is the server launch shim's, line for line ({@code src/test/resources/fallback/classify-cases.json}
 * is replayed through both: {@code ModelFallbackTest} here, {@code tools/test-muse-cli-shim.py} there), so the two
 * decisions read a Muse Code log the same way. A server that keeps the shim (docs/MODEL_HARNESS.md → "The public
 * fallback") locks its picks, so this chain is never active there.
 *
 * <p>Pure: no Minecraft, no files, no clock of its own ({@code ModelFallbackTest}).
 */
public final class ModelFallback {

    private ModelFallback() {
    }

    /** The shim's cooldowns (tools/muse-cli-shim.py QUOTA_GUESS_S, LOGIN_RETRY_S, SUBS_RECHECK_S). */
    public static final long QUOTA_GUESS_MS = 3_600_000L;
    public static final long LOGIN_RETRY_MS = 1_800_000L;
    public static final long SUBS_RECHECK_MS = 3_600_000L;
    /** A rate limit without a stated wait. */
    public static final long RATE_LIMIT_MS = 120_000L;
    /** A reset further away than this is not believed (a garbled date): the quota guess is used instead. */
    static final long MAX_RESET_MS = 8L * 24 * 3_600_000L;

    // ── entries ───────────────────────────────────────────────────────────────────────────────────────

    /** One step of the chain: a supported model and how it is paid for. */
    public record Entry(SupportedModels.Model model, SupportedModels.Mode mode) {

        /** {@code muse-wonder-1.0:subscription}: the stored form, and the key of its skip. */
        public String key() {
            return model.id() + ":" + mode.id;
        }

        public boolean paid() {
            return mode == SupportedModels.Mode.API;
        }

        public SupportedModels.Harness harness() {
            return SupportedModels.harness(model, mode);
        }

        /** "Muse Wonder 1.0 on your Muse Code sign-in" / "Opus 5.5 (low) with your API key (ANTHROPIC_API_KEY)". */
        public String label() {
            return model.label() + (paid() ? " with your API key (" + model.provider().keyVar + ")"
                    : " on your " + harness().label + " sign-in");
        }

        /** {@link #label} plus the price, when it costs per token (D27). */
        public String labelWithPrice() {
            return label() + (paid() ? ", " + model.apiPrice() : "");
        }
    }

    /** Parse {@code muse-spark-1.2-contributor:api}; empty when it is not a supported model of {@code role}. */
    public static Optional<Entry> entry(String text, SupportedModels.Role role) {
        if (text == null) {
            return Optional.empty();
        }
        String s = text.strip().toLowerCase(Locale.ROOT);
        int c = s.lastIndexOf(':');
        if (c <= 0) {
            return Optional.empty();
        }
        Optional<SupportedModels.Model> m = SupportedModels.find(s.substring(0, c))
                .filter(x -> x.role() == role);
        Optional<SupportedModels.Mode> mode = SupportedModels.Mode.of(s.substring(c + 1));
        return m.isPresent() && mode.isPresent() ? Optional.of(new Entry(m.get(), mode.get())) : Optional.empty();
    }

    /** The words "none", "off" and "" all mean "no fallback". */
    static boolean none(String csv) {
        String s = csv == null ? "" : csv.strip().toLowerCase(Locale.ROOT);
        return s.isEmpty() || s.equals("none") || s.equals("off");
    }

    /** What a fallback list said: its entries in order, and a line for each piece that is not one. */
    public record Parsed(List<Entry> entries, List<String> problems) {
    }

    /** Parse a comma-separated fallback list ({@code magician.fallback}). Duplicates are dropped. */
    public static Parsed parse(String csv, SupportedModels.Role role) {
        List<Entry> out = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        if (none(csv)) {
            return new Parsed(out, problems);
        }
        for (String piece : csv.split(",")) {
            if (piece.isBlank()) {
                continue;
            }
            Optional<Entry> e = entry(piece, role);
            if (e.isEmpty()) {
                problems.add("\"" + piece.strip() + "\" is not a supported "
                        + (role == SupportedModels.Role.MAIN ? "wish" : "helper") + " model with a way to pay "
                        + "(model:subscription or model:api, models " + SupportedModels.ids(role) + ")");
            } else if (out.stream().noneMatch(x -> x.key().equals(e.get().key()))) {
                out.add(e.get());
            }
        }
        return new Parsed(out, problems);
    }

    /** The stored form of a list. */
    public static String format(List<Entry> entries) {
        return entries.isEmpty() ? "none" : String.join(",", entries.stream().map(Entry::key).toList());
    }

    /** The chain: the primary pick first, then the fallbacks in order, the primary never twice. */
    public static List<Entry> chain(Entry primary, List<Entry> fallbacks) {
        List<Entry> out = new ArrayList<>();
        out.add(primary);
        for (Entry e : fallbacks) {
            if (out.stream().noneMatch(x -> x.key().equals(e.key()))) {
                out.add(e);
            }
        }
        return out;
    }

    // ── classification ────────────────────────────────────────────────────────────────────────────────

    /** Why a worker (or a grade) ended. */
    public enum Failure {
        /** The quota or window is spent (a 5-hour or weekly window, a usage limit, no credit left). */
        QUOTA,
        /** Rate-limited: it will answer again within minutes. */
        RATE_LIMITED,
        /** The sign-in or the key is missing, expired or refused. */
        LOGIN,
        /** The account has no active subscription. */
        NO_SUBSCRIPTION,
        /** The plan (or the key) does not serve this model. */
        NOT_SERVED,
        /** Anything else: the wish's problem, not the account's. */
        OTHER;

        /** Can another entry fix it? Only an account-shaped failure falls through. */
        public boolean fallsThrough() {
            return this != OTHER;
        }
    }

    /** One classification: what, the reset time when the message named one (0 when not), the line it came from. */
    public record Classified(Failure failure, long resetsAtMs, String line) {
    }

    // The launch shim's own patterns (tools/muse-cli-shim.py MODEL_RE, QUOTA_RE, RESETS_RE, NOSUB_RE, LOGIN_RE),
    // checked in the shim's order on each line, newest line first. classify-cases.json holds them to it.
    static final Pattern MODEL_RE = Pattern.compile("does not exist or you lack access|model_not_found|not "
            + "(?:available|included) (?:on|in|with) your (?:plan|subscription)", Pattern.CASE_INSENSITIVE);
    static final Pattern QUOTA_RE = Pattern.compile("Subscription quota exhausted", Pattern.CASE_INSENSITIVE);
    static final Pattern RESETS_RE = Pattern.compile("resets at (\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d(?:\\.\\d+)?Z)");
    static final Pattern NOSUB_RE = Pattern.compile("No active subscription|Subscriptions aren.t currently available"
            + "|SubscriptionRequired|require_payment|pay-as-you-go|API error 402|Payment ?Required",
            Pattern.CASE_INSENSITIVE);
    static final Pattern LOGIN_RE = Pattern.compile("still unauthorized after a token refresh|No Meta credentials "
            + "were found|run `muse login`|not logged in|API error 401|API error 403|CredentialRejected",
            Pattern.CASE_INSENSITIVE);

    // The public mod's own additions, after the shim's: the Claude CLI's wording (ops/AgentFailureClassifier's,
    // read out of the CLI binary), and a plain rate limit. Never matched before a shim pattern on the same line.
    static final Pattern CLAUDE_QUOTA_RE = Pattern.compile("usage limit reached|credit balance is too low"
            + "|credit balance too low|out of (?:extra )?usage|weekly limit reached|hour limit reached",
            Pattern.CASE_INSENSITIVE);
    static final Pattern CLAUDE_EPOCH_RE = Pattern.compile("limit reached\\|(\\d{9,11})");
    static final Pattern CLAUDE_LOGIN_RE = Pattern.compile("please run /login|login required|login expired"
            + "|session expired|oauth token (?:expired|revoked)|invalid api key|authentication_error"
            + "|needs MUSE_API_KEY|needs ANTHROPIC_API_KEY", Pattern.CASE_INSENSITIVE);
    static final Pattern RATE_RE = Pattern.compile("rate_limit_error|rate.limited|rate limit|too many requests"
            + "|\\b429\\b", Pattern.CASE_INSENSITIVE);
    static final Pattern RETRY_AFTER_RE = Pattern.compile("retry.after[^0-9]{0,4}(\\d{1,5})\\s*s?",
            Pattern.CASE_INSENSITIVE);

    /** Classify a worker's log (or a grade's output); {@link Failure#OTHER} when nothing in it is the account's. */
    public static Classified classify(String text) {
        if (text == null || text.isBlank()) {
            return new Classified(Failure.OTHER, 0L, "");
        }
        String[] lines = text.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i];
            String clip = clip(line);
            if (MODEL_RE.matcher(line).find()) {
                return new Classified(Failure.NOT_SERVED, 0L, clip);
            }
            if (QUOTA_RE.matcher(line).find()) {
                Matcher m = RESETS_RE.matcher(line);
                return new Classified(Failure.QUOTA, m.find() ? parseIso(m.group(1)) : 0L, clip);
            }
            if (NOSUB_RE.matcher(line).find()) {
                return new Classified(Failure.NO_SUBSCRIPTION, 0L, clip);
            }
            if (LOGIN_RE.matcher(line).find()) {
                return new Classified(Failure.LOGIN, 0L, clip);
            }
            if (CLAUDE_QUOTA_RE.matcher(line).find()) {
                Matcher m = CLAUDE_EPOCH_RE.matcher(line);
                Matcher r = RESETS_RE.matcher(line);
                long at = m.find() ? Long.parseLong(m.group(1)) * 1000L : r.find() ? parseIso(r.group(1)) : 0L;
                return new Classified(Failure.QUOTA, at, clip);
            }
            if (CLAUDE_LOGIN_RE.matcher(line).find()) {
                return new Classified(Failure.LOGIN, 0L, clip);
            }
            if (RATE_RE.matcher(line).find()) {
                return new Classified(Failure.RATE_LIMITED, 0L, clip);
            }
        }
        return new Classified(Failure.OTHER, 0L, "");
    }

    /** A stated wait in seconds ("retry after 30s"), as milliseconds; 0 when the line names none. */
    static long retryAfterMs(String line) {
        Matcher m = RETRY_AFTER_RE.matcher(line == null ? "" : line);
        return m.find() ? Math.min(3_600L, Long.parseLong(m.group(1))) * 1000L : 0L;
    }

    static long parseIso(String s) {
        try {
            return Instant.parse(s).toEpochMilli();
        } catch (DateTimeParseException e) {
            return 0L;
        }
    }

    private static String clip(String line) {
        String s = line.strip();
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    // ── the sticky state ──────────────────────────────────────────────────────────────────────────────

    /**
     * Why an entry is skipped, until when, and the sign-in file's stamp when it failed (a newer sign-in clears a
     * LOGIN, NO_SUBSCRIPTION or NOT_SERVED skip at once, as the shim's re-login does).
     */
    public record Skip(Failure failure, long untilMs, long atMs, long signInStamp, String line) {
    }

    /** Every entry's skip, by {@link Entry#key}. Not thread-safe: the caller holds its lock. */
    public static final class State {
        private final Map<String, Skip> skips = new LinkedHashMap<>();

        public Map<String, Skip> skips() {
            return Map.copyOf(skips);
        }

        /**
         * Record what {@code entry}'s worker died of. Returns the skip, or empty when the failure does not fall
         * through (the entry stays in use).
         */
        public Optional<Skip> failed(Entry entry, Classified c, long nowMs, long signInStamp) {
            if (!c.failure().fallsThrough()) {
                return Optional.empty();
            }
            long until = switch (c.failure()) {
                case QUOTA -> c.resetsAtMs() > nowMs && c.resetsAtMs() - nowMs <= MAX_RESET_MS ? c.resetsAtMs()
                        : nowMs + QUOTA_GUESS_MS;
                case RATE_LIMITED -> nowMs + Math.max(retryAfterMs(c.line()), RATE_LIMIT_MS);
                case LOGIN -> nowMs + LOGIN_RETRY_MS;
                case NO_SUBSCRIPTION, NOT_SERVED -> nowMs + SUBS_RECHECK_MS;
                case OTHER -> nowMs;
            };
            Skip s = new Skip(c.failure(), until, nowMs, signInStamp, c.line());
            skips.put(entry.key(), s);
            return Optional.of(s);
        }

        /** The entry worked: forget its skip. */
        public void ok(Entry entry) {
            skips.remove(entry.key());
        }

        /** Is {@code entry} skipped at {@code nowMs}, given its sign-in file's stamp now? Expired skips are dropped. */
        public Optional<Skip> skip(Entry entry, long nowMs, long signInStampNow) {
            Skip s = skips.get(entry.key());
            if (s == null) {
                return Optional.empty();
            }
            boolean signedInAgain = s.failure() != Failure.QUOTA && s.failure() != Failure.RATE_LIMITED
                    && signInStampNow > s.signInStamp();
            if (nowMs >= s.untilMs() || signedInAgain) {
                skips.remove(entry.key());
                return Optional.empty();
            }
            return Optional.of(s);
        }

        public String toJson() {
            JsonObject root = new JsonObject();
            root.addProperty("v", 1);
            JsonObject all = new JsonObject();
            skips.forEach((k, s) -> {
                JsonObject o = new JsonObject();
                o.addProperty("failure", s.failure().name());
                o.addProperty("untilMs", s.untilMs());
                o.addProperty("atMs", s.atMs());
                o.addProperty("signInStamp", s.signInStamp());
                o.addProperty("line", s.line());
                all.add(k, o);
            });
            root.add("skips", all);
            return root.toString();
        }

        /** Read {@link #toJson}'s form; anything unreadable is an empty state (a skip forgotten is only a retry). */
        public static State fromJson(String json) {
            State st = new State();
            try {
                JsonElement e = JsonParser.parseString(json == null ? "" : json);
                if (!e.isJsonObject() || !e.getAsJsonObject().has("skips")) {
                    return st;
                }
                for (var en : e.getAsJsonObject().getAsJsonObject("skips").entrySet()) {
                    JsonObject o = en.getValue().getAsJsonObject();
                    st.skips.put(en.getKey(), new Skip(Failure.valueOf(o.get("failure").getAsString()),
                            o.get("untilMs").getAsLong(), o.get("atMs").getAsLong(),
                            o.has("signInStamp") ? o.get("signInStamp").getAsLong() : 0L,
                            o.has("line") ? o.get("line").getAsString() : ""));
                }
            } catch (RuntimeException ex) {
                return new State();
            }
            return st;
        }
    }

    // ── the decision ──────────────────────────────────────────────────────────────────────────────────

    /** An entry the decision passed over, and why, in plain words. */
    public record Passed(Entry entry, String why) {
    }

    /**
     * What the next launch runs: the entry (empty when every one is skipped or unavailable), its place in the chain,
     * the entries passed over and why, and when the soonest skip ends (0 when none will).
     */
    public record Decision(Optional<Entry> entry, int index, List<Passed> passed, long retryAtMs) {

        public boolean exhausted() {
            return entry.isEmpty();
        }
    }

    /**
     * Choose the next launch's entry.
     *
     * @param unavailable why an entry cannot run on this machine at all (no key set, no sign-in file, program not
     *                    installed), or empty when it can; such an entry is passed over without a skip
     * @param signInStamp the entry's sign-in file's modification time now (0 when none, or for an API key)
     */
    public static Decision choose(List<Entry> chain, State state, long nowMs, Function<Entry, Optional<String>> unavailable,
                                  Function<Entry, Long> signInStamp, ZoneId zone) {
        List<Passed> passed = new ArrayList<>();
        long retryAt = 0L;
        for (int i = 0; i < chain.size(); i++) {
            Entry e = chain.get(i);
            Optional<String> no = unavailable.apply(e);
            if (no.isPresent()) {
                passed.add(new Passed(e, no.get()));
                continue;
            }
            Optional<Skip> s = state.skip(e, nowMs, signInStamp.apply(e));
            if (s.isPresent()) {
                passed.add(new Passed(e, why(e, s.get(), nowMs, zone)));
                retryAt = retryAt == 0L ? s.get().untilMs() : Math.min(retryAt, s.get().untilMs());
                continue;
            }
            return new Decision(Optional.of(e), i, List.copyOf(passed), retryAt);
        }
        return new Decision(Optional.empty(), -1, List.copyOf(passed), retryAt);
    }

    /** Why a skipped entry is skipped, in a player's words. */
    public static String why(Entry e, Skip s, long nowMs, ZoneId zone) {
        String who = e.paid() ? "your " + e.model().provider().label + " API key"
                : "your " + e.harness().label + " " + (e.model().provider() == SupportedModels.Provider.MUSE
                ? "window" : "plan");
        return switch (s.failure()) {
            case QUOTA -> who + " is spent until " + clock(s.untilMs(), nowMs, zone);
            case RATE_LIMITED -> e.model().label() + " is busy (rate-limited) until " + clock(s.untilMs(), nowMs, zone);
            case LOGIN -> e.paid() ? "your " + e.model().provider().keyVar + " key was refused"
                    : "your " + e.harness().label + " sign-in isn't working (sign in again with `"
                    + e.harness().command + " login`)";
            case NO_SUBSCRIPTION -> "your " + e.harness().label + " account has no active subscription";
            case NOT_SERVED -> (e.paid() ? "your key" : "your plan") + " doesn't include " + e.model().label();
            case OTHER -> e.model().label() + " failed";
        };
    }

    /** "18:00", or "Tue 18:00" when it is not today. */
    static String clock(long ms, long nowMs, ZoneId zone) {
        ZonedDateTime t = Instant.ofEpochMilli(ms).atZone(zone);
        ZonedDateTime now = Instant.ofEpochMilli(nowMs).atZone(zone);
        String hm = t.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT));
        return t.toLocalDate().equals(now.toLocalDate()) ? hm
                : t.format(DateTimeFormatter.ofPattern("EEE", Locale.ROOT)) + " " + hm;
    }

    /**
     * What the player is told when the chosen entry is not the one the last launch used; empty when nothing changed.
     * {@code previous} null: the first launch, which is only worth a line when it is not the primary.
     */
    public static Optional<String> switchNotice(Decision d, Entry previous) {
        if (d.exhausted()) {
            return Optional.of("Moddy can't grant wishes right now: " + joinWhys(d.passed())
                    + ". Wishes wait until one comes back.");
        }
        Entry now = d.entry().get();
        if (previous != null && previous.key().equals(now.key())) {
            return Optional.empty();
        }
        if (d.index() == 0) {
            return previous == null ? Optional.empty()
                    : Optional.of("Moddy is back on " + now.label() + ".");
        }
        return Optional.of("Moddy switched models: " + joinWhys(d.passed()) + ", so wishes now use "
                + now.labelWithPrice() + ".");
    }

    private static String joinWhys(List<Passed> passed) {
        return String.join("; ", passed.stream().map(Passed::why).distinct().toList());
    }
}
