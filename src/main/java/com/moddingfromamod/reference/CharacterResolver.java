package com.moddingfromamod.reference;

import com.moddingfromamod.agent.MuseExec;
import com.moddingfromamod.agent.MuseGrade;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.moddingfromamod.agent.ClaudeCli;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>What real character, if any, is this wish about?</b>
 *
 * <p>Answers that question <b>once per wish</b>, so that prefetching and the agent's later lookup
 * key off the same string. That identity is the whole point: a reference fetch takes 75-109 seconds
 * and must start when the wish arrives, but the agent does not ask until minutes later and asks in
 * its own words. Two independent guesses at one string agree only by luck — a live run prefetched
 * {@code Spikormake}, {@code Make SPIKOR}, {@code Kroaks} and
 * {@code Fun Mealsmake Tralalelo Tralalala}, none of which anything would ever ask for.
 *
 * <p>So the agent's argument is treated as a <b>hint, not the key</b>. It cannot miss its own
 * prefetch because it is not the thing choosing where to look.
 *
 * <h2>Why a model and not a pattern</h2>
 *
 * <p>A regex cannot tell a character from a noun phrase, and the distinctions are exactly the ones
 * that matter:
 *
 * <pre>
 *   "Make SPIKOR king of the Kroaks"          -> Spikor        (not "Kroaks", not "Make SPIKOR")
 *   "make chucko mcburger"                    -> Chucko McBurger  (casing restored)
 *   "invent me a creature called Zizzle"      -> NOTHING       (the child invented it)
 *   "a big troll with a club"                 -> NOTHING       (an archetype, not a character)
 * </pre>
 *
 * <p>The last two matter as much as the first two. Looking up an invented creature returns somebody
 * else's Zizzle and quietly pollutes a design that was supposed to be the child's own.
 *
 * <p>It is a tiny classification, so it runs on the <b>fastest model available</b> rather than the
 * one that builds — a second or two, while the child is still reading the confirmation. When no CLI
 * is configured it falls back to a heuristic and never blocks.
 */
public final class CharacterResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(CharacterResolver.class);

    /** This must not be felt. It runs on the wish-submission path. */
    private static final int TIMEOUT_SECONDS = 15;

    /** Model for the classification. Small on purpose; the job is extraction, not judgement. */
    private static final String FAST_MODEL = com.moddingfromamod.safety.SafetyGrader.HAIKU_PIN;

    /**
     * {@code -Dmoddingfromamod.reference.model}: which model classifies first. Default
     * {@link MuseGrade#EMBER} through Muse Code since 2026-11-15 (docs/EMBER_GRADERS.md: 36/36
     * on the labelled subjects against Haiku's 27/36, p50 1.4 s against 4.3 s, $0.0007 against
     * $0.0019). When Muse Code gives no answer the classification falls back to {@link #FAST_MODEL}
     * on the Claude CLI, then to the heuristic, exactly as before. {@code haiku} skips Muse.
     */
    public static final String MODEL_PROPERTY = "moddingfromamod.reference.model";

    public static String model() {
        String m = System.getProperty(MODEL_PROPERTY, com.moddingfromamod.config.MagicianWiring.cheapSpec(MuseGrade.EMBER));
        return m == null || m.isBlank() ? com.moddingfromamod.config.MagicianWiring.cheapSpec(MuseGrade.EMBER) : m.strip();
    }

    /** One answer per wish text, so the classification happens once however often it is asked. */
    private static final Map<String, Optional<Resolved>> RESOLVED = new ConcurrentHashMap<>();

    /**
     * Wishes that named a real PERSON with no public article, by wish text. Kept so that
     * {@code look_up_character} refuses to search for them too: the agent asking in its own words
     * must not open what the gate closed.
     */
    private static final Map<String, String> NOT_PUBLIC = new ConcurrentHashMap<>();

    /** A name worth looking up, and which sort of pictures to look for. */
    public record Resolved(String name, SubjectKind kind) {
    }

    private CharacterResolver() {
    }

    /**
     * The canonical character for a wish, or empty when there is nothing to look up.
     *
     * <p>Empty is a real answer, not a failure: invented creatures and archetypes must NOT be
     * looked up.
     */
    public static Optional<String> resolve(String wishText) {
        return resolveSubject(wishText).map(Resolved::name);
    }

    /**
     * The same answer with its {@link SubjectKind}. Empty — no lookup at all — for an invention,
     * a bare description, and for anything that is not a character or a creature:
     * the live log showed lookups for a treehouse, a volcano, a torch, gems, a lightning sword
     * and a grappling hook, each a web-search run on the server's sign-in.
     */
    public static Optional<Resolved> resolveSubject(String wishText) {
        if (wishText == null || wishText.isBlank()) {
            return Optional.empty();
        }
        Optional<Resolved> r = RESOLVED.computeIfAbsent(wishText.strip(), CharacterResolver::classify);
        // a name one of the children's own wished creatures carries is theirs, not a character's.
        // After the cache, because creatures come and go while a classification is kept.
        if (r.isPresent() && r.get().kind() == SubjectKind.CHARACTER && OwnCreatures.wished(r.get().name())) {
            if (OWN_LOGGED.add(wishText.strip())) {
                LOGGER.info("'{}' is the name of a creature a child wished for in this world, not a character: "
                        + "nothing is looked up for it (#2645)", r.get().name());
            }
            return Optional.empty();
        }
        return r;
    }

    /** Wish texts already logged as naming a child's own creature, so the line is said once per wish. */
    private static final java.util.Set<String> OWN_LOGGED = ConcurrentHashMap.newKeySet();

    /**
     * The real person this wish named who is NOT a public figure, if any — resolved first, so a
     * caller never sees this before the classification ran.
     */
    public static Optional<String> privatePerson(String wishText) {
        if (wishText == null || wishText.isBlank()) {
            return Optional.empty();
        }
        resolveSubject(wishText);
        return Optional.ofNullable(NOT_PUBLIC.get(wishText.strip()));
    }

    /** The cache key both paths use. Derived from the resolved term, never from the caller. */
    public static Optional<String> keyFor(String wishText) {
        return resolve(wishText).map(CharacterResolver::normalise);
    }

    public static String normalise(String character) {
        return character.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_|_$", "");
    }

    private static Optional<Resolved> classify(String wish) {
        boolean muse = MuseGrade.isMuse(model());
        // Which of the job's models may be asked, and how each is paid: the route (HelperRoute).
        var steps = com.moddingfromamod.config.HelperRoute.steps(com.moddingfromamod.config.HelperRoute.Job.CHARACTER,
                muse);
        boolean[] museAsked = {false};
        var got = new java.util.concurrent.atomic.AtomicReference<Optional<Resolved>>();
        Boolean answered = com.moddingfromamod.config.HelperRoute.first(steps, !muse ? null : museSteps -> {
            museAsked[0] = true;
            MuseExec.Answer a = MuseExec.text(null, prompt(wish), MuseGrade.modelId(model()), "low",
                    TIMEOUT_SECONDS * 1000L, museSteps);
            if (a == null) {
                return null;
            }
            // WHICH MODEL ANSWERED, in a fixed shape: a log reader can tell an Ember
            // success from a quiet fallback (docs/EMBER_GRADERS.md).
            LOGGER.info("reference classify: model={} ms={} arm={}", a.modelId(), a.wallMs(), a.arm());
            got.set(resolved(parse(a.text(), wish), wish));
            return Boolean.TRUE;
        }, step -> {
            Optional<Resolved> r = askClaude(wish, step, museAsked[0]);
            if (r == null) {
                return null;
            }
            got.set(r);
            return Boolean.TRUE;
        });
        if (answered == null) {
            // No step answered (Magician off, no program, the route names none): the local heuristic, no process.
            return heuristic(wish).map(n -> new Resolved(n, SubjectKind.CHARACTER));
        }
        return got.get();
    }

    /** The pinned Haiku on the Claude CLI, paid as {@code step} says; null when it cannot run (the route's next step). */
    private static Optional<Resolved> askClaude(String wish, com.moddingfromamod.config.HelperRoute.Step step,
                                                boolean afterMuse) {
        String cli = ClaudeCli.resolve();
        if (cli == null || cli.isBlank()) {
            // Magician off (or no CLI): no process at all (this used to log "asking haiku instead" first,
            // which read as a model call in a world with Magician off).
            return null;
        }
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(List.of(
                    cli, "-p", prompt(wish),
                    "--model", FAST_MODEL,
                    // No tools at all. This is a text classification; anything else it could reach
                    // would be a liability with no upside.
                    "--allowed-tools", "",
                    "--permission-mode", "dontAsk"));
            pb.redirectErrorStream(true);
            if (!com.moddingfromamod.config.HelperRoute.claudeEnv(step, pb.environment())) {
                return null;
            }
            if (afterMuse) {
                LOGGER.info("character resolve: Muse gave no answer; asking {} instead", FAST_MODEL);
            }
            pb.redirectInput(new File("/dev/null"));
            // A clean cwd, as the researcher has: started in a checkout of this repo the CLI
            // read its CLAUDE.md and, measured, answered nothing parseable for 10 of 20
            // subjects inside the 15 s budget -- every one of which reads as "not a character".
            pb.directory(new File(System.getProperty("java.io.tmpdir")));
            p = pb.start();
            String out;
            try (var in = p.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                LOGGER.info("character resolve timed out; falling back to the heuristic");
                return heuristic(wish).map(n -> new Resolved(n, SubjectKind.CHARACTER));
            }
            LOGGER.info("reference classify: model={} ms=- arm={}", FAST_MODEL, MuseExec.ARM_HAIKU);
            return resolved(parse(out, wish), wish);
        } catch (Exception e) {
            return heuristic(wish).map(n -> new Resolved(n, SubjectKind.CHARACTER));
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }

    /** The PERSON gate and the log line, the same for whichever model answered. */
    private static Optional<Resolved> resolved(Optional<Resolved> parsed, String wish) {
        Optional<Resolved> r = gatePerson(parsed);
        if (parsed.isPresent() && r.isEmpty()) {
            NOT_PUBLIC.put(wish, parsed.get().name());
        }
        LOGGER.info("reference subject for this wish: {}", r.map(x -> x.kind() + " '"
                + x.name() + "'").orElse("NONE — no lookup"));
        return r;
    }

    /**
     * <b>A PERSON survives only with an outside fact behind it.</b> The model judged the
     * phrasing — "a picture of X" talks about X as somebody who exists — and that is all it can
     * judge; whether X is a PUBLIC figure or the child's classmate is {@link PublicFigure}'s
     * answer, and anything but {@code PUBLIC} (no article, or Wikipedia unreachable) is no lookup.
     */
    static Optional<Resolved> gatePerson(Optional<Resolved> r) {
        if (r.isEmpty() || r.get().kind() != SubjectKind.PERSON) {
            return r;
        }
        PublicFigure.Verdict v = PublicFigure.check(r.get().name());
        if (v != PublicFigure.Verdict.PUBLIC) {
            LOGGER.info("reference subject: PERSON '{}' has no public article ({}) — no lookup",
                    r.get().name(), v);
            return Optional.empty();
        }
        return r;
    }

    /**
     * <b>Never ask the resolver a knowledge question.</b>
     *
     * <p>The first version asked "is this a REAL character?" and Haiku answered NONE for
     * "Tralalelo Tralalala" — an obscure meme it does not know. So the gate deciding whether to look
     * something up failed precisely when the lookup was most needed, which is the same
     * shared-ignorance failure as a judge preferring its own misconception over a sourced reference.
     *
     * <p>So this asks a <b>naming</b> question, not a knowledge one: is there a proper name here
     * that the child did not invent on the spot? And it leans YES, because the costs are wildly
     * asymmetric — a needless background search costs about 26 seconds nobody waits for, while a
     * missed one costs the entire feature for exactly the creatures it exists to help.
     */
    /**
     * <b>Decide from the PHRASING, never from whether the model knows the name.</b>
     *
     * <p>Two earlier versions failed the same way. Asking "is this a REAL character?" made Haiku
     * answer NONE for "Tralalelo Tralalala" — an obscure meme it does not know — so the gate failed
     * exactly where the lookup was needed. Telling it "do not judge whether you have heard of it"
     * was not enough either: to a model that knows neither, <b>an unknown real name and an invented
     * name are textually identical</b>. "Tralalelo Tralalala" and "Zizzle" look the same.
     *
     * <p>So the signal cannot be the name. It is how the child REFERS to it: "invent me a creature
     * <i>called</i> Zizzle" announces an invention, while "make Tralalelo Tralalala the shark"
     * refers to something assumed to already exist. That is a syntactic judgement, which a small
     * model can make reliably without knowing anything about the subject.
     */
    private static String prompt(String wish) {
        return String.join("\n",
                "Extract the name of the thing a child is asking for, if the wish refers to "
                        + "something that ALREADY EXISTS and is a CHARACTER, a CREATURE or a real "
                        + "PERSON. The wish may be to BUILD it, or to make a PICTURE, painting, "
                        + "poster or mural OF it -- either way, name the subject.",
                "",
                "WISH: \"" + wish + "\"",
                "",
                "Decide from HOW THE CHILD REFERS TO IT, never from whether you recognise the name.",
                "You will not have heard of some of these. That is expected and is not a reason to "
                        + "answer NONE — an unfamiliar name is the most useful kind to look up.",
                "",
                "ANSWER  CHARACTER: <name>  for a particular character the child talks about as a "
                        + "thing that exists — a cartoon, game, film or meme character, a mascot, "
                        + "a game's monster or mob:",
                "    \"make Tralalelo Tralalala the shark with the shoes\"  ->  CHARACTER: "
                        + "Tralalelo Tralalala",
                "    \"Make CAPTAIN COMET king of the space pirates\"      ->  CHARACTER: Captain "
                        + "Comet",
                "    \"make chuckles the burger clown\"                     ->  CHARACTER: Chuckles "
                        + "the Burger Clown",
                "    \"spawn three ghasts\"                                 ->  CHARACTER: Ghast",
                "    \"a painting of Captain Comet on my wall\"             ->  CHARACTER: Captain Comet",
                "    \"put a picture of Zip the hedgehog on the train\"     ->  CHARACTER: Zip the "
                        + "Hedgehog",
                "  A monster or mob FROM A GAME is a CHARACTER, even when the game has many of "
                        + "it: its look is the game's design, not nature's.",
                "",
                "ANSWER  CREATURE: <name>  for a real animal or a creature from myth, named as "
                        + "the kind of thing it is:",
                "    \"make me a capybara\"                ->  CREATURE: Capybara",
                "    \"i want a giant kraken\"             ->  CREATURE: Kraken",
                "    \"a narwhal that follows me\"         ->  CREATURE: Narwhal",
                "",
                "ANSWER  PERSON: <name>  for a REAL person the child names as somebody who exists "
                        + "-- a YouTuber, a streamer, a sports star, a singer, a famous person. Do "
                        + "not decide whether they are famous; just name them:",
                "    \"make a picture of BlockyBob\"           ->  PERSON: BlockyBob",
                "    \"paint TechnoTom's face on the wall\"    ->  PERSON: TechnoTom",
                "    \"a poster of my friend Jake\"           ->  PERSON: Jake",
                "",
                "ANSWER NONE in exactly three cases:",
                "  1. the child says they are INVENTING it — the words invent, called, named, "
                        + "make up:",
                "       \"invent me a creature called Zizzle\"      ->  NONE",
                "       \"a big troll called Grumbnar with a club\" ->  NONE",
                "  2. there is no name at all, only a description:",
                "       \"a big troll with a club\"                 ->  NONE",
                "       \"an icy mage that makes a blizzerd\"       ->  NONE",
                "  3. it is NOT a character or a living creature — an object, item, weapon, tool, "
                        + "food, building, vehicle, place, material or weather:",
                "       \"build me a lighthouse\"                   ->  NONE",
                "       \"give me a magic wand that shoots ice\"    ->  NONE",
                "       \"make a waterfall\" / \"a jetpack\" / \"some diamonds\"  ->  NONE",
                "       \"paint a sunset\" / \"a picture of a castle\"  ->  NONE",
                "",
                "Reply with ONLY one line: CHARACTER: <name>, CREATURE: <name>, PERSON: <name>, "
                        + "or NONE. The name "
                        + "properly capitalised and searchable — no verb, no title, no "
                        + "description.");
    }

    static Optional<Resolved> parse(String raw, String wish) {
        String s = raw == null ? "" : raw.strip();
        // Take the last non-empty line: a chatty model puts the answer last.
        String[] lines = s.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].strip().replaceAll("^[\"'`*]+|[\"'`*.]+$", "");
            if (line.isEmpty()) {
                continue;
            }
            if (line.equalsIgnoreCase("none")) {
                return Optional.empty();
            }
            // THE PREFIX IS REQUIRED. A bare line used to be taken as a name, and measured on
            // the live wishes the model once answered "What's the wish?" -- which would have been
            // looked up as a character. An answer that is not one of the three shapes is no
            // lookup at all: the agent's own look_up_character still works, and a gate that
            // guessed on a garbled reply would be no gate.
            var m = java.util.regex.Pattern.compile("(?i)^(CHARACTER|CREATURE|PERSON)\\s*:\\s*(.+)$")
                    .matcher(line);
            if (!m.matches()) {
                break;
            }
            SubjectKind kind = SubjectKind.valueOf(m.group(1).toUpperCase(Locale.ROOT));
            String name = m.group(2).strip().replaceAll("^[\"'`*]+|[\"'`*.]+$", "");
            // A name, not a sentence or a question. Anything long is the model explaining itself.
            if (!name.isEmpty() && name.length() <= 40 && name.split("\\s+").length <= 5
                    && !name.contains("?") && !name.equalsIgnoreCase("none")) {
                return Optional.of(new Resolved(name, kind));
            }
            break;
        }
        return Optional.empty();
    }

    /**
     * Fallback when no model is available. Deliberately conservative: it would rather return
     * nothing than the wrong thing, because a wrong term costs a wasted fetch AND a reference that
     * belongs to a different creature.
     */
    static Optional<String> heuristic(String wish) {
        // "called X" / "named X" means the child invented it -- never look those up.
        if (wish.toLowerCase(Locale.ROOT).matches(".*\\b(called|named|invent)\\b.*")) {
            return Optional.empty();
        }
        for (String clause : wish.split("[.!?,;:\\n]+")) {
            var m = java.util.regex.Pattern
                    .compile("\\b([A-Z][\\w'-]+(?:\\s+[A-Z][\\w'-]+)*)").matcher(clause);
            while (m.find()) {
                String c = ReferenceHint.stripNoise(m.group(1));
                if (c.length() >= 4 && c.split("\\s+").length <= 4) {
                    return Optional.of(c);
                }
            }
        }
        return Optional.empty();
    }

    /** Between runs and tests. */
    public static void clear() {
        RESOLVED.clear();
        NOT_PUBLIC.clear();
        OWN_LOGGED.clear();
    }

    /** A test's classification, so a GameTest needs no model: {@code wishText} resolves to {@code r}. */
    public static void putForTest(String wishText, Optional<Resolved> r) {
        RESOLVED.put(wishText.strip(), r);
    }
}
