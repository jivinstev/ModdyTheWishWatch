package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * <b>A precompiled pack is the pack Python would have compiled</b>, for every effect template and a spread of
 * values: each catalogue default, edge values (a long tag, decimals and big numbers, other effects, particles and
 * sounds, every TARGET word, a command with a selector argument the compiler rebuilds), and each Builder special move's
 * own parameters. For each value set the filled template is compiled by the real CBScript compiler, and its skeleton
 * ({@link EffectCatalogue#skeleton}) is compiled once and the values put back in Java
 * ({@link PrecompiledPacks#substitute}); the two must be the same files, line for line, {@code pack.mcmeta} included.
 *
 * <p>What it proves is the MECHANISM: that every value {@link ReadyMadeSkeleton#accepts} lets through as a slot rides
 * the compiler as plain text, and that a literal (a selector clause, a command with a selector argument) is kept in
 * the skeleton. Which skeletons the store holds is {@code ReadyMadePrecompileGameTest}'s (every Builder power made with
 * no Python) and {@link PrecompiledFingerprintTest}'s.
 */
class PrecompiledEquivalenceTest {

    private static final String INERT = "effect give @s minecraft:speed 3 1";
    private static final String REBUILT = "damage @s[type=#moddingfromamod:hostile] 2 minecraft:magic";

    @Test
    void everyEffectTemplateIsTheSamePackPrecompiled() throws Exception {
        Assumptions.assumeTrue(CbScriptCompiler.available(), "SKIPPED — no CBScript compiler here");
        EffectCatalogue cat = EffectCatalogue.load();
        Map<String, List<Map<String, String>>> spread = new LinkedHashMap<>();
        for (EffectCatalogue.Effect e : cat.all()) {
            spread.put(e.id(), valueSets(e));
        }
        assertEquals(16, spread.size(), "the catalogue has a template this test does not know: " + spread.keySet());

        ExecutorService pool = Executors.newFixedThreadPool(4);
        Map<String, Future<Map<String, String>>> skeletons = new ConcurrentHashMap<>();
        record Job(String effect, int n, Map<String, String> args, ReadyMadeSkeleton.Skeleton sk, String featureId,
                   String desc, Future<Map<String, String>> real) {
        }
        List<Job> jobs = new ArrayList<>();
        int n = 0;
        for (var e : spread.entrySet()) {
            for (Map<String, String> args : e.getValue()) {
                String real = cat.render(e.getKey(), args);
                ReadyMadeSkeleton.Skeleton sk = cat.skeleton(e.getKey(), args);
                assertEquals(real, sk.fill(), e.getKey() + ": a skeleton must fill back to its render");
                String featureId = "eq_" + e.getKey() + "_" + n;
                String desc = "ready-made ability: " + e.getKey() + " for Zoë \"the\" creature";
                skeletons.computeIfAbsent(PrecompiledPacks.key(sk.source()), k -> pool.submit(() ->
                        compile(ReadyMadeSkeleton.NAMESPACE, ReadyMadeSkeleton.DESC, sk.source())));
                jobs.add(new Job(e.getKey(), n, args, sk, featureId, desc,
                        pool.submit(() -> compile(featureId, desc, real))));
                n++;
            }
        }
        List<String> wrong = new ArrayList<>();
        Map<String, TreeSet<String>> slotted = new LinkedHashMap<>();
        Map<String, TreeSet<String>> literal = new LinkedHashMap<>();
        Map<String, java.util.Set<String>> skeletonsPer = new LinkedHashMap<>();
        for (Job j : jobs) {
            Map<String, String> compiled = j.real().get();
            List<String[]> skFiles = new ArrayList<>();
            skeletons.get(PrecompiledPacks.key(j.sk().source())).get()
                    .forEach((k, v) -> skFiles.add(new String[] {k, v}));
            Map<String, String> precompiled = PrecompiledPacks.substitute(skFiles, j.featureId(),
                    CbScriptCompiler.headerDescription(j.desc()), j.sk().outputValues());
            if (!compiled.equals(precompiled)) {
                wrong.add(j.effect() + " " + j.args() + ": " + PrecompiledPacks.firstDifference(compiled, precompiled));
            }
            skeletonsPer.computeIfAbsent(j.effect(), k -> new java.util.HashSet<>()).add(j.sk().source());
            String src = cat.templateSource(j.effect());
            var m = java.util.regex.Pattern.compile("\\$\\{([A-Z_]+)}").matcher(src);
            while (m.find()) {
                String s = ReadyMadeSkeleton.sentinel("", m.group(1));
                (j.sk().slots().containsKey(s) ? slotted : literal)
                        .computeIfAbsent(j.effect(), k -> new TreeSet<>()).add(m.group(1));
            }
        }
        pool.shutdown();
        for (String e : spread.keySet()) {
            System.out.println("precompiled equivalence: " + e + " — " + spread.get(e).size() + " value sets, "
                    + skeletonsPer.get(e).size() + " skeleton(s); slots " + slotted.getOrDefault(e, new TreeSet<>())
                    + ", literal at least once " + literal.getOrDefault(e, new TreeSet<>()));
        }
        assertTrue(wrong.isEmpty(), wrong.size() + " value set(s) where the precompiled pack is NOT the compiled "
                + "one:\n" + String.join("\n", wrong));
    }

    private static Map<String, String> compile(String featureId, String desc, String source) throws Exception {
        Path dir = Files.createTempDirectory("precompile_eq");
        Path zip = dir.resolve("p.zip");
        CompileResult r = CbScriptCompiler.compile(dir, featureId, desc, source, zip);
        if (!r.ok()) {
            throw new IllegalStateException(featureId + " does not compile: " + r.failureSummary());
        }
        return PrecompiledPacks.readZip(zip);
    }

    /** The defaults, then edge values, then the Builder's own moves for this template. */
    static List<Map<String, String>> valueSets(EffectCatalogue.Effect e) {
        String src = EffectCatalogue.load().templateSource(e.id());
        List<Map<String, String>> out = new ArrayList<>();
        Map<String, String> raw = raw(e, "gt_snake");
        out.add(derive(e, src, raw));
        Map<String, String> edge = new LinkedHashMap<>(raw);
        edge.put("TAG", "a_very_long_creature_tag_42");
        bump(edge);
        out.add(derive(e, src, edge));
        if (src.contains("${VICTIMS}")) {
            for (String target : List.of("monsters", "players", "everyone", "animals", "minecraft:zombie")) {
                Map<String, String> t = new LinkedHashMap<>(raw);
                t.put("TARGET", target);
                out.add(derive(e, src, t));
            }
        }
        if (src.contains("${COMMAND}")) {
            for (String command : List.of(REBUILT, "data modify entity @s Fire set value 100s")) {
                Map<String, String> c = new LinkedHashMap<>(raw);
                c.put("COMMAND", command);
                out.add(derive(e, src, c));
            }
        }
        for (com.moddingfromamod.wishbuilder.BuilderAbilities.Ability a
                : com.moddingfromamod.wishbuilder.BuilderAbilities.ALL) {
            if (a.effect().equals(e.id())) {
                Map<String, String> b = new LinkedHashMap<>(raw);
                b.putAll(a.params());
                out.add(derive(e, src, b));
            }
        }
        return out;
    }

    /** The words a template is asked with, before apply_effect derives anything: defaults and what has none. */
    private static Map<String, String> raw(EffectCatalogue.Effect e, String tag) {
        Map<String, String> m = new LinkedHashMap<>(e.defaults());
        m.put("TAG", tag);
        if (e.params().contains("COMMAND") && !m.containsKey("COMMAND")) {
            m.put("COMMAND", INERT);
        }
        if (e.params().contains("ALLY_TAG")) {
            m.put("ALLY_TAG", "gt_ally");
        }
        if (e.id().equals("colour_cycle")) {
            m.put("MOB", "axolotl");
        }
        if (e.id().equals("impulse")) {
            m.put("TRIGGER", "periodic");
            m.put("EVERY_SECONDS", "4");
        }
        return m;
    }

    /** What apply_effect derives before it renders: ticks from seconds, impulse's words, the colour steps. */
    private static Map<String, String> derive(EffectCatalogue.Effect e, String src, Map<String, String> in) {
        Map<String, String> m = new LinkedHashMap<>(in);
        if (e.id().equals("impulse")) {
            Map<String, String> words = new LinkedHashMap<>();
            for (String k : List.of("TAG", "TRIGGER", "DIRECTION", "STRENGTH", "FEEL", "RADIUS", "TARGET",
                    "EVERY_SECONDS", "SOFT_LANDING")) {
                if (m.containsKey(k)) {
                    words.put(k, m.get(k));
                }
            }
            m = new LinkedHashMap<>(com.moddingfromamod.agent.tools.ApplyEffectTool.impulseTemplateParams(words));
        }
        if (e.id().equals("colour_cycle") && m.containsKey("MOB")) {
            m.putIfAbsent("PERIOD_TICKS", ticks(m.getOrDefault("EVERY_SECONDS", "1")));
            String bad = com.moddingfromamod.agent.tools.ApplyEffectTool.fillColourCycle(m);
            if (bad != null) {
                throw new IllegalStateException(bad);
            }
        }
        seconds(m, "EVERY_SECONDS", "PERIOD_TICKS", src);
        seconds(m, "LASTS_SECONDS", "LASTS_TICKS", src);
        seconds(m, "BREATH_SECONDS", "BREATH_TICKS", src);
        if (src.contains("${STEP_TICKS}")) {
            m.putIfAbsent("STEP_TICKS", "4");
        }
        // Keep only what the entry accepts: render refuses anything else.
        Map<String, String> out = new LinkedHashMap<>();
        for (var en : m.entrySet()) {
            if (e.allParams().contains(en.getKey())) {
                out.put(en.getKey(), en.getValue());
            }
        }
        return out;
    }

    private static void seconds(Map<String, String> m, String from, String to, String src) {
        if (src.contains("${" + to + "}") && m.containsKey(from)) {
            m.put(to, ticks(m.get(from)));
        }
    }

    private static String ticks(String seconds) {
        return String.valueOf(Math.max(1, (int) Math.round(Double.parseDouble(seconds) * 20)));
    }

    /** Edge values: other numbers (a decimal where one is allowed), another effect, particle, sound, block, item. */
    private static void bump(Map<String, String> m) {
        for (var en : m.entrySet()) {
            String k = en.getKey();
            switch (k) {
                case "RADIUS", "REACH" -> en.setValue("12.5");
                case "SECONDS", "AMPLIFIER", "DENSITY", "HEIGHT", "PERCENT", "MOST" -> en.setValue("255");
                case "EVERY_SECONDS", "LASTS_SECONDS", "BREATH_SECONDS" -> en.setValue("0.5");
                case "EFFECT" -> en.setValue("minecraft:jump_boost");
                case "PARTICLE" -> en.setValue("minecraft:end_rod");
                case "SOUND" -> en.setValue("minecraft:entity.blaze.shoot");
                case "BLOCK" -> en.setValue("minecraft:blue_ice");
                case "ITEM" -> en.setValue("minecraft:golden_apple");
                case "COMMAND" -> en.setValue("execute at @s run summon minecraft:lightning_bolt ~ ~ ~");
                case "TARGET" -> en.setValue("monsters");
                default -> {
                }
            }
        }
    }
}
