package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * a load-and-pour machine, rendered from the REAL derived fragments and put through the
 * REAL CBScript compiler, on every shape.
 *
 * <p>The catalogue's own render test only proves no placeholder survives; the runtime matrix
 * proves it on a server. This sits between them: seconds, off-game, and it fails on a fragment
 * MachineFill generates that the compiler cannot read — which is the one failure the pure unit
 * test cannot see.
 */
class LoadAndPourCompileTest {

    @Test
    void theFullestMachineCompilesOnEveryShape(@TempDir Path work) throws Exception {
        Assumptions.assumeTrue(CbScriptCompiler.available(),
                "CBScript compiler not on this machine — skipping the live compile");
        ItemCatalogue c = ItemCatalogue.load();
        ItemCatalogue.Entry e = c.get("load_and_pour");
        assertNotNull(e, "the catalogue has no load_and_pour");
        for (String shape : ItemCatalogue.EFFECT_SHAPES) {
            Map<String, String> args = ItemCatalogueTestSupport.argsFor(e, shape);
            String src = c.render("load_and_pour", shape, args,
                    ItemCatalogueTestSupport.shapeArgs("pour_" + shape));
            assertFalse(src.contains("${"), shape + ": a placeholder survived");
            String ns = "pour_" + shape;
            Path zip = work.resolve(ns + ".zip");
            CompileResult r = CbScriptCompiler.compile(work.resolve(ns), ns, "load and pour", src, zip);
            assertTrue(r.ok(), shape + " did not compile: " + r.failureSummary());
            if (ItemCatalogue.MACHINE.equals(shape)) {
                try (ZipFile zf = new ZipFile(zip.toFile())) {
                    // the parts the child meets: loading, pouring into a chest, the how-to
                    for (String fn : new String[]{"fill_load0", "fill_pour", "fill_slot26",
                            "fill_help", "refresh_sign"}) {
                        assertNotNull(zf.getEntry("data/" + ns + "/function/" + fn + ".mcfunction"),
                                "no " + fn + " in the machine pack");
                    }
                }
            }
        }
    }
}
