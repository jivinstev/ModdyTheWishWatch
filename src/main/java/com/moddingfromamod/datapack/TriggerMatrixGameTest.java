package com.moddingfromamod.datapack;

import com.moddingfromamod.testing.gt.GameTestCompat;
import net.minecraft.network.chat.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>The whole effect × target matrix, exhaustively, in about half a minute.</b>
 *
 * <p>. The catalogue is one template per TRIGGER, parameterised by effect and target, because
 * trigger is the only axis that changes the emitted program — a proximity tick, a damage scoreboard
 * and a timer are different scaffolds, while "which effect" and "which target" are tokens.
 *
 * <p>That asymmetry is the design, so it is what gets tested. Compiling costs ~200ms, which makes
 * the full cross product cheap enough that <b>sparsity would be a false economy</b>: a pairwise
 * covering array over these axes is ~54 cells and the exhaustive set is ~54 too. Sparsity belongs
 * in the LIVE tier, where a run costs minutes and the only axis worth sampling is the trigger.
 *
 * <p>Two claims, and the second is the one that could kill the design:
 *
 * <ol>
 *   <li>every combination compiles — so an agent picking any effect and any target gets working
 *       code without writing any;</li>
 *   <li>varying effect or target changes the output <b>only where the token appears</b>. If that
 *       fails, they are not parameters, the templates would have to multiply per combination, and
 *       "3 templates cover 162 combinations" is false.</li>
 * </ol>
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class TriggerMatrixGameTest {

    /** The effects seen across 535 real mods, as the vanilla command each becomes. */
    private static final List<String> EFFECTS = List.of(
            "effect give %s minecraft:poison 5 1",
            "effect give %s minecraft:slowness 10 1",
            "effect give %s minecraft:blindness 4 0",
            "damage %s 4 minecraft:magic",
            "particle minecraft:flame ~ ~1 ~ 0.3 0.3 0.3 0.02 8 force",
            "playsound minecraft:entity.wither.ambient master @a ~ ~ ~ 1 1",
            "summon minecraft:silverfish ~ ~ ~",
            "tp %s ~ ~1 ~");

    /** The targets seen across the same corpus, as selectors. */
    private static final List<String> TARGETS = List.of(
            "@a[distance=..4]", "@a[distance=..12]", "@s",
            "@e[tag=ally,distance=..16]", "@e[type=!player,distance=..6]", "@p");

    private static final List<String> TRIGGERS = List.of("on_hit", "periodic", "proximity");

    /**
     * Entries that carry a COMMAND but are NOT one of the three original triggers — the new
     * scaffolds. They belong in the matrix for the same reason the first three do: the whole point
     * of a precomputed ability is that it is known to compile, and an entry outside the matrix has
     * only ever been compiled with the one set of parameters somebody typed by hand.
     *
     * <p>They were outside it for exactly one run. 205 tests passed while four of the ten entries
     * had never been compiled by anything, which is the shape of green that means nothing.
     */
    private static final List<String> COMMAND_ENTRIES =
            List.of("on_death", "on_low_health", "lingering_zone", "breath_cone", "erupt_blocks");

    private static String fill(String command, String target) {
        return command.contains("%s") ? String.format(command, target) : command;
    }

    /**
     * The real deadline. The tick timeout below is only a backstop: while the compiles run
     * the clock TICKS, and a GameTest server ticks as fast as it can, so a tick count says nothing
     * about how long a fork-per-cell compile is allowed to take. Wall clock does.
     */
    private static final long MATRIX_WALL_LIMIT_MS = 10 * 60 * 1000L;

    @GameTest(template = "empty_test", timeoutTicks = 200_000)
    public static void everyEffectAndTargetCompilesUnderEveryTrigger(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            System.out.println("TRIGGER MATRIX: SKIPPED — no CBScript compiler. This proved NOTHING "
                    + "about whether the catalogue generalises.");
            helper.succeed();
            return;
        }
        EffectCatalogue catalogue = EffectCatalogue.load();
        List<String> failures = new ArrayList<>();
        long started = System.currentTimeMillis();
        Path tmp;
        List<java.util.concurrent.Future<String>> pending = new ArrayList<>();
        java.util.concurrent.ExecutorService pool;
        try {
            tmp = Files.createTempDirectory("mfam_matrix");
            List<String> all = new ArrayList<>(TRIGGERS);
            all.addAll(COMMAND_ENTRIES);

            // RENDER FIRST, COMPILE AFTER — the two halves have completely different costs.
            // Rendering is pure string work in microseconds; compiling forks a Python process and
            // blocks on its stdout for ~0.5s. Separating them is what lets the second half be run
            // in parallel without touching the first half's ordering.
            record Cell(String trigger, String effect, String target, String source, int index) { }
            List<Cell> cells = new ArrayList<>();
            for (String trigger : all) {
                if (catalogue.get(trigger) == null) {
                    failures.add("no catalogue entry '" + trigger + "'");
                    continue;
                }
                for (String effect : EFFECTS) {
                    for (String target : TARGETS) {
                        Map<String, String> params = new java.util.LinkedHashMap<>();
                        params.put("TAG", "matrixmob");
                        params.put("COMMAND", fill(effect, target));
                        // Derived params are filled by the tool, not the agent, so the matrix has
                        // to supply them too or every new scaffold fails to render for a reason
                        // that has nothing to do with the combination under test.
                        for (String d : catalogue.get(trigger).derived()) {
                            params.put(d, switch (d) {
                                case "PERIOD_TICKS" -> "120";
                                case "LASTS_TICKS" -> "160";
                                case "BREATH_TICKS" -> "30";
                                default -> "1";
                            });
                        }
                        String source;
                        try {
                            source = catalogue.render(trigger, params);
                        } catch (RuntimeException e) {
                            failures.add(trigger + " / " + effect + " / " + target
                                    + " — render: " + e.getMessage());
                            continue;
                        }
                        cells.add(new Cell(trigger, effect, target, source, cells.size()));
                    }
                }
            }

            // COMPILE IN PARALLEL. 144 cells x one forked Python process each, run one after
            // another on the server thread, is 85.5s of Gate B during which the other 49 tests in
            // the batch cannot tick. Sampled 2026-09-01: this test was in 62.8% of the
            // stacks taken during defaultBatch:3, and 58.8% of ALL samples were the server thread
            // parked in FileInputStream.readBytes inside CbScriptCompiler.compile -- i.e. waiting
            // on a subprocess, using no CPU, with three cores idle.
            //
            // Same fix, same reasoning as CbScriptCorpusTest in Gate A (35.3s -> 9.4s).
            //
            // ⚠ Each cell gets its OWN work directory. CbScriptCompiler.compile does
            // copyLibsInto(workDir) before every compile, so sharing one directory across threads
            // means concurrent copies of the same .cblib files -- a race whose symptom would be a
            // sporadic compile failure blamed on the catalogue.
            //
            // AND NO LONGER BLOCKING THE SERVER THREAD. It used to wait here on the
            // futures, so the TICK clock stayed frozen and a deadline in ticks kept its serial
            // meaning. The price was every other test in the batch: on 1.21.1 this test's ~10 s
            // freeze sat in defaultBatch:9 beside the item matrix's and a 1160-tick train drive,
            // and the batch ran 80 s at 15 ticks/s (Gate B's budget line). Nothing here touches the
            // world -- it is a string rendered and a Python process forked per cell -- so the batch
            // may tick while it runs. The deadline that means something is now WALL clock
            // (MATRIX_WALL_LIMIT_MS); the tick timeout is a backstop sized so it never fires first.
            int threads = Integer.getInteger(
                    ModdingFromAModMod.MODID + ".matrixthreads",
                    Math.max(2, Runtime.getRuntime().availableProcessors()));
            pool = java.util.concurrent.Executors.newFixedThreadPool(threads, r -> {
                Thread t = new Thread(r, "trigger-matrix");
                t.setDaemon(true);
                return t;
            });
            for (Cell c : cells) {
                pending.add(pool.submit(() -> {
                    Path dir = tmp.resolve("cell" + c.index());
                    Path zip = dir.resolve("m.zip");
                    CompileResult r = CbScriptCompiler.compile(
                            dir, "matrix_" + c.index(), "matrix", c.source(), zip);
                    return r.ok() ? null : c.trigger() + " / " + c.effect() + " / " + c.target()
                            + " — compile: " + r.failureSummary();
                }));
            }
        } catch (Exception e) {
            helper.fail(Component.literal("the matrix could not run: " + e));
            return;
        }
        long deadline = started + MATRIX_WALL_LIMIT_MS;
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (System.currentTimeMillis() <= deadline
                            && !pending.stream().allMatch(java.util.concurrent.Future::isDone)) {
                        throw GameTestCompat.assertion("the trigger matrix is still compiling");
                    }
                })
                .thenExecute(() -> {
                    int compiled = 0;
                    try {
                        // Drained in SUBMISSION order, so the failure list reads the same as it
                        // did when this ran serially. A set of failures that reorders run to run
                        // is a diff nobody can review.
                        for (int i = 0; i < pending.size(); i++) {
                            java.util.concurrent.Future<String> f = pending.get(i);
                            if (!f.isDone()) {
                                failures.add("cell " + i + " did not compile within "
                                        + (MATRIX_WALL_LIMIT_MS / 1000) + " s");
                                continue;
                            }
                            compiled++;
                            try {
                                String bad = f.get();
                                if (bad != null) {
                                    failures.add(bad);
                                }
                            } catch (java.util.concurrent.ExecutionException e) {
                                failures.add("cell " + i + " threw " + e.getCause());
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                failures.add("cell " + i + " was interrupted");
                            }
                        }
                    } finally {
                        pool.shutdownNow();
                        // ~95 MB of compiled cells per run, and nothing else ever removes it: 38
                        // runs left 3.6 GB in /tmp and filled a cloud session's disk mid-gate
                        // (2026-10-02).
                        try (var walk = Files.walk(tmp)) {
                            walk.sorted(java.util.Comparator.reverseOrder())
                                    .forEach(p -> p.toFile().delete());
                        } catch (java.io.IOException ignored) {
                            // best effort: a leftover temp dir is not a test failure
                        }
                    }
                    long ms = System.currentTimeMillis() - started;
                    System.out.println("TRIGGER MATRIX: " + compiled + " combination(s) in " + ms
                            + "ms, " + failures.size() + " failed");
                    if (!failures.isEmpty()) {
                        // Print a bounded sample: 160 failures would bury the one line that
                        // explains them.
                        failures.stream().limit(8).forEach(f -> System.out.println("   " + f));
                        helper.fail(Component.literal(failures.size() + " of " + compiled + " combinations failed. "
                                + "The catalogue does NOT cover effect x target from one template "
                                + "per trigger, which is the claim #68 rests on. First: "
                                + failures.get(0)));
                    }
                })
                .thenSucceed();
    }

    /**
     * <b>Effect and target are TOKENS, not shapes.</b>
     *
     * <p>If swapping an effect changed the emitted structure, the templates would have to multiply
     * per combination and the whole "3 templates cover 162 combinations" claim collapses. Checked
     * on the rendered source, since that is where a template would branch.
     */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void swappingEffectOrTargetChangesOnlyTheToken(GameTestHelper helper) {
        EffectCatalogue catalogue = EffectCatalogue.load();
        for (String trigger : TRIGGERS) {
            if (catalogue.get(trigger) == null) {
                continue;
            }
            String a = catalogue.render(trigger, Map.of(
                    "TAG", "m", "COMMAND", "effect give @a[distance=..4] minecraft:poison 5 1"));
            String b = catalogue.render(trigger, Map.of(
                    "TAG", "m", "COMMAND", "effect give @a[distance=..4] minecraft:blindness 5 1"));
            String c = catalogue.render(trigger, Map.of(
                    "TAG", "m", "COMMAND", "effect give @e[tag=ally] minecraft:poison 5 1"));
            helper.assertTrue(
                    a.replace("minecraft:poison", "X").equals(b.replace("minecraft:blindness", "X")),
                    Component.literal(trigger + ": swapping the EFFECT changed more than the token, so effects are "
                            + "not parameters and this template cannot cover them all"));
            helper.assertTrue(
                    a.replace("@a[distance=..4]", "X").equals(c.replace("@e[tag=ally]", "X")),
                    Component.literal(trigger + ": swapping the TARGET changed more than the token, so targets are "
                            + "not parameters"));
        }
        helper.succeed();
    }

    /**
     * <b>A creature ability must work when the victim is another CREATURE.</b>
     *
     * <p>Mob-versus-mob is not an edge case here — Spikor fights Dinko, and nineteen of the
     * corpus's generated wishes name a mob as the victim ("freeze the zombies", "poison the cow",
     * "eat the cow"). The first {@code on_hit} covered none of them: it used {@code @a} and the
     * {@code damage_taken} scoreboard criterion, which is a PLAYER STATISTIC that mobs cannot have.
     * It compiled, it passed the whole 144-cell matrix, and it would have quietly served half the
     * demand — the failure being invisible until a child noticed the spider never poisons the cow.
     *
     * <p>So: no trigger may select players only.
     */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void noTriggerIsPlayerOnly(GameTestHelper helper) {
        EffectCatalogue catalogue = EffectCatalogue.load();
        for (String trigger : TRIGGERS) {
            if (catalogue.get(trigger) == null) {
                continue;
            }
            // CODE ONLY, comments stripped. The template's comment EXPLAINS why it avoids the
            // damage_taken criterion, and a raw text scan read that explanation as the offence --
            // the second time in this catalogue that prose about a hazard has tripped a check for
            // the hazard (the first was a ${...} example inside a comment). A guard that greps
            // source must judge the code, exactly as tools/verify-catalogue.py already does.
            String src = catalogue.render(trigger, Map.of("TAG", "m", "COMMAND", "say hi"))
                    .lines()
                    .filter(l -> !l.strip().startsWith("#"))
                    .collect(java.util.stream.Collectors.joining("\n"));
            helper.assertTrue(!src.contains("damage_taken"),
                    Component.literal(trigger + " detects damage with the damage_taken scoreboard criterion, which "
                            + "is a PLAYER statistic — mobs cannot have one, so any wish whose "
                            + "victim is a creature would silently do nothing"));
            // Selecting @a somewhere is fine (a command may target players deliberately); what is
            // not fine is a trigger that can ONLY ever fire for a player.
            boolean reachesNonPlayers = src.contains("@e") || src.contains("@s");
            helper.assertTrue(reachesNonPlayers,
                    Component.literal(trigger + " never selects anything but players, so mob-versus-mob abilities "
                            + "cannot be built from it"));
        }
        helper.succeed();
    }

    /**
     * The negative control. Triggers must NOT be interchangeable — if they were, one template would
     * do and the per-trigger split would be ceremony.
     */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void triggersAreGenuinelyDifferentPrograms(GameTestHelper helper) {
        EffectCatalogue catalogue = EffectCatalogue.load();
        Map<String, String> args = Map.of("TAG", "m", "COMMAND", "say hi");
        String prox = catalogue.get("proximity") == null ? null
                : catalogue.render("proximity", args);
        String hit = catalogue.get("on_hit") == null ? null : catalogue.render("on_hit", args);
        if (prox == null || hit == null) {
            helper.fail(Component.literal("both proximity and on_hit must exist for this control to mean anything"));
            return;
        }
        helper.assertTrue(!prox.equals(hit),
                Component.literal("proximity and on_hit rendered IDENTICAL source — then the trigger axis is not a "
                        + "shape axis after all, and the catalogue should be one template, not "
                        + "three"));
        helper.succeed();
    }
}
