package com.moddingfromamod.wish;

import com.moddingfromamod.testing.SourceRoots;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Every wish kind's lessons must have more than one way to reach the agent.</b>
 *
 * <p>Lessons are the highest-leverage loop this project has — a better first draft costs no extra
 * turns — and they reach the agent only by riding out on a tool result. So a kind whose lessons
 * leave through exactly ONE tool is one skipped call away from never being read at all, and
 * nothing says so: the agent builds something, it is merely worse than it would have been.
 *
 * <p>That was live. {@code lessons/train.md} left through {@code list_trains} alone, on the
 * strength of the brief telling the agent to call it first — and a fresh world has no trains to
 * list, so "just build me a train" went straight to {@code build_train} and the agent saw not one
 * train lesson. Checking the other five found the same shape in three more: {@code building},
 * {@code item} and {@code datapack}. A defect found is a defect class found.
 *
 * <p><b>What this does NOT check</b>, and it is the more important half: whether the two paths are
 * actually reachable in different situations. Two deliveries on two branches of one tool would
 * satisfy this and fix nothing. The rule is a floor, and the judgement is still yours — put the
 * second one on the path the first cannot cover, which in practice means the AUTHORING tool's
 * refusal when the first is a list tool, and vice versa.
 */
class LessonsReachTheAgentTest {

    private static final Path LESSONS = Path.of("src/main/resources/lessons");
    private static final Path SOURCE = Path.of("src/main/java/com/moddingfromamod");
    private static final Pattern DELIVERY =
            Pattern.compile("WishLessons\\.(?:appendTo|appendOnce|text)\\(\"(\\w+)\"\\)");

    @Test
    @DisplayName("every lesson file is delivered by at least two different tools")
    void everyKindHasMoreThanOneWayIn() throws IOException {
        assertTrue(Files.isDirectory(LESSONS),
                "cannot find " + LESSONS.toAbsolutePath() + " — this guard proved NOTHING.");

        Map<String, List<String>> deliveredBy = new LinkedHashMap<>();
        try (Stream<Path> files = SourceRoots.walk(SOURCE)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String cls = p.getFileName().toString().replace(".java", "");
                Matcher m = DELIVERY.matcher(Files.readString(p, StandardCharsets.UTF_8));
                while (m.find()) {
                    deliveredBy.computeIfAbsent(m.group(1), k -> new ArrayList<>());
                    if (!deliveredBy.get(m.group(1)).contains(cls)) {
                        deliveredBy.get(m.group(1)).add(cls);
                    }
                }
            }
        }

        List<String> thin = new ArrayList<>();
        try (Stream<Path> files = Files.list(LESSONS)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".md")).toList()) {
                String kind = p.getFileName().toString().replace(".md", "");
                List<String> tools = deliveredBy.getOrDefault(kind, List.of());
                if (tools.size() < 2) {
                    thin.add("lessons/" + kind + ".md is delivered by " + tools
                            + " — one tool, or none");
                }
            }
        }
        assertTrue(thin.isEmpty(),
                "A wish kind whose lessons leave through one tool is one skipped call away from "
                        + "never being read, and nothing reports that — the agent just builds "
                        + "something worse than it would have. Add a second "
                        + "WishLessons.appendTo(\"<kind>\") on a path the first cannot cover: the "
                        + "AUTHORING tool's refusal when the first is a list tool, and the list "
                        + "tool's empty case when the first is the author.\n  "
                        + String.join("\n  ", thin));
    }

    /**
     * The lesson has to be non-empty to be worth delivering, and a kind with no file at all is a
     * kind nobody ever wrote the rules down for.
     */
    @Test
    @DisplayName("every delivered kind names a lesson file that exists and says something")
    void nobodyDeliversAnEmptyLesson() throws IOException {
        List<String> bad = new ArrayList<>();
        try (Stream<Path> files = SourceRoots.walk(SOURCE)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = DELIVERY.matcher(Files.readString(p, StandardCharsets.UTF_8));
                while (m.find()) {
                    Path lesson = LESSONS.resolve(m.group(1) + ".md");
                    if (!Files.exists(lesson)) {
                        bad.add(p.getFileName() + " delivers \"" + m.group(1)
                                + "\", but lessons/" + m.group(1) + ".md does not exist");
                    } else if (Files.readString(lesson, StandardCharsets.UTF_8).isBlank()) {
                        bad.add("lessons/" + m.group(1) + ".md is blank");
                    }
                }
            }
        }
        assertTrue(bad.isEmpty(), "a delivery that carries nothing is worse than none, because it "
                + "reads as covered:\n  " + String.join("\n  ", bad));
    }
}
