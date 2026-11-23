package com.moddingfromamod.vanilla;

import com.moddingfromamod.testing.SourceRoots;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Every pack this mod pushes is REQUIRED, including the one nobody has written yet.</b>
 *
 * <p>{@code ItemPackGameTest} and {@code RigPackDeferralGameTest} read the flag off the wire, which
 * is the strongest form of the check — but each covers <i>one</i> pack. The failure this file is
 * for is the THIRD pack: somebody adds a delivery, types {@code false} because that is what the
 * two sites nearby used to say, and no gate anywhere is watching that packet. It is the same shape
 * as an earlier issue's landmine, where two override tables were compatible by coincidence rather than by rule.
 *
 * <p>So the rule is structural: a push site in the shipped delivery package passes
 * {@link PackDelivery#REQUIRED} and never a literal. One decision, one place to read the reasoning,
 * and a new pack inherits it by construction.
 *
 * <p><b>Why it is a source sweep and not a runtime check.</b> There is no runtime moment at which
 * "all push sites" exist to be interrogated — a site that is never reached is exactly the one that
 * would be wrong. CLAUDE.md's own advice applies: when you write a lesson down, ask whether a grep
 * could enforce it.
 */
class PackPushIsRequiredTest {

    private static final Path SRC = Path.of("src/main/java/com/moddingfromamod");

    /** {@code new [qualifiers.]ClientboundResourcePackPushPacket( ... )}, arguments and all. */
    private static final Pattern CONSTRUCTION = Pattern.compile(
            "new\\s+(?:[\\w.]+\\s*\\.\\s*)?ClientboundResourcePackPushPacket\\s*\\(",
            Pattern.DOTALL);

    /**
     * Files that construct the packet and are deliberately NOT routed through the constant, with
     * the reason. Named rather than silently skipped: an exemption nobody can see is how a rule
     * decays into a convention.
     */
    private static final Map<String, String> EXEMPT = Map.of(
            "lite/LiteProbe.java",
            "an off-by-default measurement probe (-Dmoddingfromamod.litepush=url|sha1) whose whole "
                    + "job is to push a caller-supplied pack again and time the client's reload. "
                    + "Its flag is part of the experiment's setup rather than a delivery decision "
                    + "-- and it is asserted below to be `true` anyway, so the exemption cannot "
                    + "hide an optional push");

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> s = SourceRoots.walk(SRC)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".java")).sorted().toList();
        }
    }

    private static String rel(Path p) {
        return SourceRoots.relativize(SRC, p).toString().replace('\\', '/');
    }

    /**
     * <b>The argument list with its COMMENTS REMOVED, and that is not tidiness.</b>
     *
     * <p>Both real push sites carry a comment beside the flag saying <i>"REQUIRED, from the one
     * place that decides it — see {@code PackDelivery.REQUIRED}"</i>. A {@code contains} check over
     * the raw text therefore matches the COMMENT and passes over an argument that says
     * {@code false} — measured: the guard was green over a genuinely reintroduced
     * {@code url, sha1, false} in {@code ItemPackService}, while its own planted-string A/B went
     * red, because the fixture had no comment in it.
     *
     * <p>That is this repo's standing rule about instruments arriving in a new costume: <b>a guard
     * verified only against a synthetic fixture has been verified against the fixture.</b> The A/B
     * that counts is the one run against the real file.
     */
    private static String stripComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//[^\n]*", " ");
    }

    /** The argument list of one construction, from the opening paren to its match. */
    private static String argsOf(String code, int openParen) {
        int depth = 0;
        for (int i = openParen; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return code.substring(openParen + 1, i);
                }
            }
        }
        return code.substring(openParen);
    }

    private record Site(String file, String args) {
    }

    private static List<Site> pushSites() throws IOException {
        List<Site> out = new ArrayList<>();
        for (Path p : javaFiles()) {
            String code = Files.readString(p, StandardCharsets.UTF_8);
            Matcher m = CONSTRUCTION.matcher(code);
            while (m.find()) {
                out.add(new Site(rel(p), stripComments(argsOf(code, m.end() - 1))));
            }
        }
        return out;
    }

    @Test
    @DisplayName("every shipped push site passes PackDelivery.REQUIRED, never a literal")
    void noPushSiteTypesItsOwnAnswer() throws IOException {
        List<Site> sites = pushSites();
        assertFalse(sites.isEmpty(),
                "no resource-pack push was found anywhere in src/main/java, so this sweep measured "
                        + "nothing. Either the pattern stopped matching or the delivery moved");

        List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (Site s : sites) {
            if (EXEMPT.containsKey(s.file())) {
                continue;
            }
            checked++;
            if (!s.args().contains("PackDelivery.REQUIRED")) {
                wrong.add(s.file() + " -> " + s.args().replaceAll("\\s+", " ").trim());
            }
        }
        assertTrue(checked > 0,
                "every push site is exempt, so this sweep checks nothing. `checked` is here because "
                        + "a guard that looked at zero sites prints the same word as one that "
                        + "looked at all of them");
        assertEquals(List.of(), wrong,
                "these push sites decide `required` for themselves instead of reading "
                        + "PackDelivery.REQUIRED, which is where the reasoning lives and the only "
                        + "thing that makes a NEW pack correct by construction:\n  "
                        + String.join("\n  ", wrong));
    }

    @Test
    @DisplayName("the exempt probe is not quietly pushing an optional pack")
    void theOneExemptionIsStillRequired() throws IOException {
        for (Site s : pushSites()) {
            if (!EXEMPT.containsKey(s.file())) {
                continue;
            }
            String args = s.args().replaceAll("\\s+", " ");
            assertTrue(args.contains(", true,") || args.contains("PackDelivery.REQUIRED"),
                    s.file() + " is exempt from the sweep on the grounds that it pushes `true` "
                            + "anyway, and it no longer does: " + args.trim()
                            + "\nEither fix it or change the reason in EXEMPT — an exemption whose "
                            + "stated reason has stopped being true is worse than no exemption.");
        }
    }

    /**
     * The sweep's own A/B. A {@code contains} check passes for a file that never had the token and
     * for one that has just lost it alike, so the only thing separating a working detector from one
     * matching nothing is watching it fire.
     */
    @Test
    @DisplayName("the sweep fires on a push site that types its own answer")
    void theSweepActuallyFires() {
        // ⚠ THE COMMENT IS THE POINT OF THIS FIXTURE. Written without one it is a weaker file
        // than the real push sites, and the first version of this guard was green over a real
        // reintroduced `url, sha1, false` for exactly that reason -- it matched the token inside
        // the comment. A fixture that is easier than reality tests the fixture.
        String planted = """
                player.connection.send(new net.minecraft.network.protocol.common
                        .ClientboundResourcePackPushPacket(
                        UUID.nameUUIDFromBytes(sha1.getBytes(StandardCharsets.UTF_8)),
                        // REQUIRED, from the one place that decides it -- PackDelivery.REQUIRED.
                        url, sha1, false, Optional.empty()));
                """;
        Matcher m = CONSTRUCTION.matcher(planted);
        assertTrue(m.find(), "the pattern does not match a push written the way the real ones are");
        String args = stripComments(argsOf(planted, m.end() - 1));
        assertFalse(args.contains("PackDelivery.REQUIRED"),
                "the planted offender was excused by the comment beside it, which is the bug this "
                        + "fixture exists to reproduce");
        assertTrue(args.contains("false"), "the planted offender should carry its literal");

        // ...and the fixed form must pass, or the guard would be unsatisfiable.
        String fixed = planted.replace("sha1, false,", "sha1, PackDelivery.REQUIRED,");
        Matcher ok = CONSTRUCTION.matcher(fixed);
        assertTrue(ok.find());
        assertTrue(stripComments(argsOf(fixed, ok.end() - 1)).contains("PackDelivery.REQUIRED"));
    }

    @Test
    @DisplayName("the decision itself is REQUIRED — the thing every site is reading")
    void theDecisionIsRequired() {
        assertTrue(PackDelivery.REQUIRED,
                "PackDelivery.REQUIRED is false, so every pack this mod pushes is optional and a "
                        + "child can decline his way into a game made of leather horse armour. If "
                        + "this is deliberate, the reasoning in that field's javadoc has to change "
                        + "with it");
    }
}
