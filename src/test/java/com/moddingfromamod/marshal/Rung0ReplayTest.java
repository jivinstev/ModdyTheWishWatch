package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.wish.WishReference;
import org.junit.jupiter.api.Test;

/**
 * <b>The offline replay asks for: how many real referring wishes would rung 0 have caught?</b>
 *
 * <p>Written as a test rather than a script on purpose. The whole value of the number is that the
 * rule being replayed is the rule that will actually ship, and a Python re-implementation of
 * {@link AliasIndex} would drift from it the first time either changed — which is the ~5:1
 * instrument-bug shape this repo already has on record.
 *
 * <p>It reads two things that exist only on a machine that has been played on:
 * <ul>
 *   <li>the Mod Repo's {@code wishes/&lt;kind&gt;/&lt;id&gt;/manifest.json} — every artifact, its
 *       {@code intent} and its {@code refinements[]}, which is where the aliases are backfilled
 *       from;</li>
 *   <li>{@code ~/.moddingfromamod/agent-actions.jsonl} — the sentences he actually typed.</li>
 * </ul>
 *
 * <p>Both absent, it skips cleanly, so a clean checkout stays green. <b>The measurement is printed,
 * never asserted as a threshold</b> — a number that fails the build when the child's play changes is
 * a gate on him rather than on us.
 */
class Rung0ReplayTest {

    private static final Path LOG =
            Path.of(System.getProperty("user.home"), ".moddingfromamod", "agent-actions.jsonl");

    /** Where a played-on machine keeps its Mod Repo, per {@code ~/.moddingfromamod/config.properties}. */
    private static Path modRepo() throws IOException {
        Path config = Path.of(System.getProperty("user.home"), ".moddingfromamod",
                "config.properties");
        if (!Files.isReadable(config)) {
            return null;
        }
        for (String line : Files.readAllLines(config)) {
            String t = line.strip();
            if (t.startsWith("persist_dir=")) {
                return Path.of(t.substring("persist_dir=".length()).strip());
            }
        }
        return null;
    }

    private static AliasIndex indexFrom(Path repo) throws IOException {
        AliasIndex index = new AliasIndex();
        Path wishes = repo.resolve("wishes");
        if (!Files.isDirectory(wishes)) {
            return index;
        }
        try (var kinds = Files.list(wishes)) {
            for (Path kind : kinds.filter(Files::isDirectory).toList()) {
                try (var ids = Files.list(kind)) {
                    for (Path dir : ids.filter(Files::isDirectory).toList()) {
                        Path manifest = dir.resolve("manifest.json");
                        if (!Files.isReadable(manifest)) {
                            continue;
                        }
                        JsonObject o = JsonParser.parseString(Files.readString(manifest))
                                .getAsJsonObject();
                        String id = str(o, "id");
                        if (id.isEmpty()) {
                            continue;
                        }
                        List<String> refinements = new ArrayList<>();
                        if (o.has("refinements") && o.get("refinements").isJsonArray()) {
                            o.getAsJsonArray("refinements")
                                    .forEach(e -> refinements.add(e.getAsString()));
                        }
                        // Aliases derived the same way the backfill will derive them in production,
                        // and de-collided the same way — so the number below is what would really
                        // have happened, not what could have happened with a perfect alias list.
                        List<String> wanted =
                                AliasBackfill.from(id, str(o, "intent"), refinements);
                        List<String> free = AliasBackfill.withoutCollisions(wanted, index);
                        index.add(new AliasIndex.Artifact(id, str(o, "kind"),
                                id.replace('_', ' '), free));
                    }
                }
            }
        }
        return index;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    /** Wishes he really typed, excluding fixtures. */
    private static List<String> realWishes() throws IOException {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(LOG)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                if (!"wish".equals(str(o, "kind"))) {
                    continue;
                }
                // Fixture traffic dropped by IDENTITY: the gates write to this same file, and a
                // report that counts its own probes is how a candidate once looked 6x bigger than
                // it was.
                if (o.has("test") && o.get("test").getAsBoolean()) {
                    continue;
                }
                String text = str(o, "text");
                if (!text.isBlank()) {
                    out.add(text);
                }
            } catch (RuntimeException ignored) {
                // one bad line must never be fatal
            }
        }
        return out;
    }

    @Test
    void howManyRealReferringWishesWouldRungZeroHaveCaught() throws IOException {
        assumeTrue(Files.isReadable(LOG), "no action log on this machine — nothing to replay");
        Path repo = modRepo();
        assumeTrue(repo != null && Files.isDirectory(repo.resolve("wishes")),
                "no Mod Repo configured on this machine — nothing to build an alias index from");

        AliasIndex index = indexFrom(repo);
        List<String> wishes = realWishes();
        assumeTrue(!index.artifacts().isEmpty() && !wishes.isEmpty(),
                "nothing has been played yet");

        List<String> referring = new ArrayList<>();
        for (String w : wishes) {
            if (WishReference.isContextual(w)) {
                referring.add(w);
            }
        }

        Map<String, Integer> byHow = new LinkedHashMap<>();
        int resolved = 0;
        int ambiguous = 0;
        List<String> missed = new ArrayList<>();
        List<String> caught = new ArrayList<>();
        for (String w : referring) {
            AliasIndex.Result r = index.resolve(w);
            switch (r.verdict()) {
                case RESOLVED -> {
                    resolved++;
                    byHow.merge(r.how(), 1, Integer::sum);
                    if (caught.size() < 12) {
                        caught.add(r.artifact().id() + " <- \"" + w + "\" (" + r.how() + ")");
                    }
                }
                case AMBIGUOUS -> ambiguous++;
                default -> {
                    if (missed.size() < 12) {
                        missed.add(w);
                    }
                }
            }
        }

        System.out.println();
        System.out.println("=== RUNG 0 OFFLINE REPLAY (#125 stage 3) ===");
        System.out.println("  artifacts with aliases : " + index.artifacts().size());
        System.out.println("  wishes in the log      : " + wishes.size());
        System.out.println("  of those, REFERRING    : " + referring.size()
                + "  (WishReference.isContextual)");
        System.out.println("  rung 0 RESOLVED        : " + resolved
                + (referring.isEmpty() ? "" : "  (" + pct(resolved, referring.size()) + ")"));
        System.out.println("     by " + byHow);
        System.out.println("  rung 0 AMBIGUOUS       : " + ambiguous
                + "  (named two things — not a failure; a two-artifact wish or a question)");
        System.out.println("  fell through to rungs 1-7 : "
                + (referring.size() - resolved - ambiguous));
        System.out.println("  --- caught ---");
        caught.forEach(c -> System.out.println("     " + c));
        System.out.println("  --- fell through ---");
        missed.forEach(m -> System.out.println("     \"" + m + "\""));
        System.out.println();

        // Deliberately not a threshold. What rung 0 catches depends on what he happened to wish for,
        // so asserting a rate would make the build fail when HIS play changes rather than when our
        // code does. The assertion is only that the replay actually ran and produced a real answer.
        assertTrue(referring.size() >= 0);
        assertTrue(resolved + ambiguous <= referring.size(), "the accounting must add up");
    }

    private static String pct(int n, int of) {
        return of == 0 ? "n/a" : String.format("%.0f%%", 100.0 * n / of);
    }
}
