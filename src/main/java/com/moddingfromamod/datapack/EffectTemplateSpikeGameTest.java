package com.moddingfromamod.datapack;

import net.minecraft.network.chat.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Can a creature ability be PRECOMPUTED as a template, and reused for a different creature?</b>
 *
 * <p>The proposed effect catalogue has three layers: a metadata index the agent selects from, a
 * CBScript source template, and a prebuilt compiled datapack. The first two are obviously
 * buildable. The third rests on one assumption that must be proven rather than assumed:
 *
 * <blockquote>a compiled datapack can be reused for a DIFFERENT creature by substituting a token,
 * without recompiling.</blockquote>
 *
 * <p>If that is false, the compiled layer silently produces a datapack that points at the wrong
 * mob — a failure that looks exactly like a working one until a child notices the wrong creature
 * catching fire. So this compiles the SAME template twice with two different creature tags and
 * diffs the output. What differs is the substitution surface; anything else differing means the
 * compiler folded, reordered or renamed something, and the compiled layer is not safe.
 *
 * <p>Measured facts that motivated this shape: compiling costs ~881ms against a ~112s median wish,
 * so the compiled layer is worth ~1% of wall-clock and is NOT a speed feature. Its value is that
 * 14% of {@code author_datapack} calls fail, and an artifact that is already compiled cannot fail
 * to compile. Speed comes from the source template (the agent stops authoring); reliability comes
 * from the artifact.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class EffectTemplateSpikeGameTest {

    /**
     * The candidate template: give every creature carrying a tag a status effect.
     *
     * <p>{@code status_effect} is the class this spike targets because it tops both axes measured
     * across 535 real mods and 25 corpus wishes — 8 wishes want it, 79 mods build it — and because
     * it is cleanly parameterised: WHO, WHICH effect, HOW LONG, HOW STRONG.
     */
    private static String template(String tag, String effect, int seconds, int amplifier) {
        // Rendered FROM THE CATALOGUE, not hand-written here. The spike originally inlined its own
        // source, which proved CBScript works and proved nothing about the thing being shipped.
        // Now a failure in the catalogue -- a renamed parameter, a template that stopped matching
        // its declaration -- fails this test too.
        return EffectCatalogue.load().render("inflict_nearby", java.util.Map.of(
                "TAG", tag, "EFFECT", effect,
                "SECONDS", String.valueOf(seconds), "AMPLIFIER", String.valueOf(amplifier),
                "RADIUS", "2"));
    }

    private static List<String> entries(Path zip) throws Exception {
        List<String> out = new ArrayList<>();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            for (ZipEntry e : java.util.Collections.list(zf.entries())) {
                if (e.isDirectory()) {
                    continue;
                }
                out.add(e.getName() + "\n" + new String(zf.getInputStream(e).readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    @GameTest(template = "empty_test", timeoutTicks = 400)
    public static void aStatusEffectTemplateCompilesAndIsReusableForAnotherCreature(
            GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            // LOUD. A silent skip here would report "the spike passed" when nothing ran, and this
            // spike is the thing an entire architecture is waiting on.
            System.out.println("EFFECT SPIKE: SKIPPED — no CBScript compiler on this machine. "
                    + "This proved NOTHING about the effect catalogue.");
            helper.succeed();
            return;
        }
        try {
            Path tmp = Files.createTempDirectory("mfam_effect_spike");

            // 1. Does the template compile at all?
            Path zipA = tmp.resolve("a.zip");
            CompileResult a = CbScriptCompiler.compile(tmp, "spike_effect_a", "spike",
                    template("spikor", "minecraft:poison", 5, 1), zipA);
            helper.assertTrue(a.ok(), Component.literal("the status-effect template must compile: "
                    + (a.ok() ? "" : a.failureSummary())));

            // 2. Did the effect command actually survive into a function?
            List<String> filesA = entries(zipA);
            String joinedA = String.join("\n", filesA);
            helper.assertTrue(joinedA.contains("effect give"),
                    Component.literal("the compiled pack should contain the effect command; got:\n" + joinedA));
            helper.assertTrue(joinedA.contains("tag=spikor"),
                    Component.literal("the creature tag should survive compilation as a literal, or nothing can be "
                            + "substituted later; got:\n" + joinedA));

            // 3. THE ARCHITECTURAL QUESTION. Same template, different creature: does the output
            //    differ ONLY where the creature is named?
            Path zipB = tmp.resolve("b.zip");
            CompileResult b = CbScriptCompiler.compile(tmp, "spike_effect_a", "spike",
                    template("dinko", "minecraft:poison", 5, 1), zipB);
            helper.assertTrue(b.ok(), Component.literal("the same template must compile for a second creature: "
                    + (b.ok() ? "" : b.failureSummary())));

            List<String> filesB = entries(zipB);
            String joinedB = String.join("\n", filesB);
            // Replace the creature token EVERYWHERE, not just in `tag=dinko`. The real template
            // also emits a NEGATED selector (tag=!dinko), which the narrower form missed -- and
            // the miss reported SUBSTITUTABLE:false, i.e. an architectural conclusion invented by
            // this test's own normalisation. "Token-substitutable" means replacing the token
            // wherever it occurs yields the other artifact; anything narrower is a different claim.
            String normalised = joinedB.replace("dinko", "spikor");
            boolean substitutable = normalised.equals(joinedA);

            System.out.println("===== EFFECT TEMPLATE SPIKE =====");
            System.out.println("  compiles:            " + a.ok());
            System.out.println("  effect cmd emitted:  " + joinedA.contains("effect give"));
            System.out.println("  tag survives:        " + joinedA.contains("tag=spikor"));
            System.out.println("  files in pack:       " + filesA.size());
            System.out.println("  SUBSTITUTABLE:       " + substitutable
                    + "   <- decides whether the COMPILED layer is safe");
            if (!substitutable) {
                System.out.println("  (outputs differ beyond the creature token — the compiled");
                System.out.println("   layer would need a per-template substitution manifest, or");
                System.out.println("   the catalogue should ship SOURCE templates only)");
                for (int i = 0; i < Math.min(filesA.size(), filesB.size()); i++) {
                    if (!filesA.get(i).equals(filesB.get(i).replace("dinko", "spikor"))) {
                        System.out.println("   first differing entry:\n     A: "
                                + filesA.get(i).replace("\n", " | "));
                        System.out.println("     B: " + filesB.get(i).replace("\n", " | "));
                        break;
                    }
                }
            }
            System.out.println("=================================");

            // Deliberately NOT asserted: whether substitution is possible is what this spike is
            // MEASURING. Asserting it would turn a finding into a failure and tell us nothing new.
            helper.succeed();
        } catch (Exception e) {
            helper.fail(Component.literal("effect template spike failed: " + e));
        }
    }
}
