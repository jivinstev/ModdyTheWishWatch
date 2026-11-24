package com.moddingfromamod.vanilla;

import com.moddingfromamod.testing.SourceRoots;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>Keeps {@code docs/VANILLA_RIG_SCOREBOARD.md} honest, so progress on the vanilla-compat
 * creature path is MONOTONIC rather than remembered.</b>
 *
 * <p>A hand-maintained status table is a document that rots: a claim gets written down as verified,
 * the test behind it is renamed or deleted six weeks later, and the ledger goes on saying yes. This
 * repo has the general form of that lesson written down twice already — a step you must remember is
 * not a control, and "never failed" has also meant "never actually ran".
 *
 * <p>So three things are enforced here, all in milliseconds:
 *
 * <ol>
 *   <li>every {@code VERIFIED} row names a proof, and that proof <b>resolves</b> — a Gate A/B test
 *       method that exists somewhere in the tree, or a Gate C testmode a client test really reads;
 *   <li>no row carries a status outside the four the document defines;
 *   <li><b>the VERIFIED count never falls below the recorded floor.</b> That is the monotonic part:
 *       deleting a gate and quietly downgrading its row fails the build, and raising the floor is a
 *       visible edit in the commit that earned it.
 * </ol>
 *
 * <p>What it deliberately does NOT check is whether a claim is <i>true</i> — only that something
 * which could refute it exists and is named. A test present and wrong looks exactly like a test
 * present and right, which is the same caveat {@code audit-agent-knowledge.py} carries.
 */
class RigScoreboardTest {

    private static final Path DOC = Path.of("docs/VANILLA_RIG_SCOREBOARD.md");
    private static final Set<String> STATUSES = Set.of("VERIFIED", "PARTIAL", "OPEN", "ACCEPTED");

    private record Row(String id, String claim, String status, String proof) {
    }

    private static List<Row> rows() throws IOException {
        String all = Files.readString(DOC, StandardCharsets.UTF_8);
        String body = all.substring(all.indexOf("SCOREBOARD:BEGIN"), all.indexOf("SCOREBOARD:END"));
        List<Row> out = new ArrayList<>();
        for (String line : body.split("\n")) {
            String t = line.trim();
            if (!t.startsWith("|") || t.startsWith("|---") || t.startsWith("| id")) {
                continue;
            }
            String[] c = t.split("\\|");
            // "| a | b | c | d | e |" splits to ["", " a ", " b ", " c ", " d ", " e "]
            if (c.length < 6) {
                continue;
            }
            out.add(new Row(c[1].trim(), c[2].trim(), c[3].trim(), c[4].trim()));
        }
        return out;
    }

    /** Every identifier declared anywhere in the source tree — cheap, and good enough to resolve a proof. */
    private static String sourceText() throws IOException {
        StringBuilder sb = new StringBuilder(1 << 22);
        for (Path root : SourceRoots.javaRootsAnd()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> s = Files.walk(root)) {
                for (Path p : s.filter(x -> x.toString().endsWith(".java")).toList()) {
                    sb.append(Files.readString(p, StandardCharsets.UTF_8)).append('\n');
                }
            }
        }
        return sb.toString();
    }

    @Test
    void theScoreboardParsesAndEveryRowIsWellFormed() throws IOException {
        List<Row> rows = rows();
        assertTrue(rows.size() >= 30,
                "the scoreboard should carry the whole claim set; found " + rows.size());
        Set<String> ids = new LinkedHashSet<>();
        for (Row r : rows) {
            assertTrue(STATUSES.contains(r.status()),
                    r.id() + " has status '" + r.status() + "', which is not one of " + STATUSES);
            assertFalse(r.claim().isBlank(), r.id() + " has no claim");
            assertTrue(ids.add(r.id()), "duplicate scoreboard id " + r.id());
        }
    }

    /**
     * <b>The check that stops the ledger lying.</b> A row may only say VERIFIED if it names
     * something that exists — and the failure names the row AND the missing proof, because
     * "the test was renamed" and "the claim was never proven" want opposite fixes.
     */
    @Test
    void everyVerifiedRowNamesAProofThatActuallyExists() throws IOException {
        String src = sourceText();
        List<String> bad = new ArrayList<>();
        for (Row r : rows()) {
            if (!"VERIFIED".equals(r.status())) {
                continue;
            }
            String proof = r.proof();
            if (proof.isBlank() || "—".equals(proof) || "-".equals(proof)) {
                bad.add(r.id() + " is VERIFIED and names no proof");
                continue;
            }
            String[] parts = proof.split(":", 2);
            if (parts.length != 2) {
                bad.add(r.id() + " has an unparseable proof '" + proof + "'");
                continue;
            }
            String what = parts[1];
            switch (parts[0]) {
                case "GateA" -> {
                    // TestClass.method — the METHOD is the thing that can be renamed away.
                    String method = what.contains(".") ? what.substring(what.indexOf('.') + 1) : what;
                    if (!src.contains(method + "(")) {
                        bad.add(r.id() + " names Gate A test '" + what + "' and no such method exists");
                    }
                }
                case "GateB" -> {
                    if (!src.contains(what + "(")) {
                        bad.add(r.id() + " names Gate B test '" + what + "' and no such method exists");
                    }
                }
                case "GateC" -> {
                    // A client test is selected by its testmode string, which is the thing a
                    // launcher passes -- so that literal is what has to still be in the tree.
                    if (!src.contains("\"" + what + "\"")) {
                        bad.add(r.id() + " names Gate C testmode '" + what
                                + "' and no client test claims it");
                    }
                }
                default -> bad.add(r.id() + " has an unknown proof kind '" + parts[0] + "'");
            }
        }
        if (!bad.isEmpty()) {
            fail("the scoreboard claims proof that does not exist:\n  " + String.join("\n  ", bad));
        }
    }

    /**
     * <b>MONOTONIC PROGRESS.</b> The floor is a number in the document; the build fails if the
     * VERIFIED count drops below it. Raising it is how a commit records that it earned something,
     * and lowering it is a deliberate, reviewable edit rather than a silent regression.
     */
    @Test
    void theVerifiedCountNeverGoesBackwards() throws IOException {
        String all = Files.readString(DOC, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("<!--\\s*FLOOR:\\s*(\\d+)\\s*-->").matcher(all);
        assertTrue(m.find(), "the scoreboard must record a FLOOR comment");
        int floor = Integer.parseInt(m.group(1));
        long verified = rows().stream().filter(r -> "VERIFIED".equals(r.status())).count();
        assertTrue(verified >= floor,
                "the scoreboard has " + verified + " VERIFIED rows against a floor of " + floor
                        + " -- something that used to be proven no longer is. If that is "
                        + "deliberate, lower the floor in the same commit and say why.");
        assertTrue(verified <= floor,
                "the scoreboard has " + verified + " VERIFIED rows and the floor is still " + floor
                        + " -- raise the floor to " + verified + " in the commit that earned it, "
                        + "or the next regression will not be caught.");
    }
}
