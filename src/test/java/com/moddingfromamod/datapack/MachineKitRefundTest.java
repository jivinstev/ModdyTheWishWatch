package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * a machine's KIT is handed over with it and with every re-wish of it, and a
 * machine broken and handed back comes back ALONE — the refund must not mint a second kit. Both
 * shapes that refund (machine, magic block), read off the rendered source and, where the compiler
 * is here, off the compiled functions.
 */
class MachineKitRefundTest {

    private static final String KIT = "\n    /give @s minecraft:melon 4";

    private static String render(String shape) {
        return render(shape, KIT);
    }

    private static String render(String shape, String kit) {
        Map<String, String> args = ItemCatalogueTestSupport.shapeArgs("kit_" + shape);
        if (kit != null) {
            args.put("KIT", kit);
        }
        ItemCatalogue c = ItemCatalogue.load();
        return c.render("heal", shape, ItemCatalogueTestSupport.argsFor(c.get("heal"), shape), args);
    }

    private static String function(String src, String name) {
        int at = src.indexOf("function " + name + "()");
        assertTrue(at >= 0, "no function " + name);
        return src.substring(at, src.indexOf("\nend", at));
    }

    @Test
    void theKitComesWithTheThingAndTheRefundGivesTheThingAlone() {
        for (String shape : List.of(ItemCatalogue.MACHINE, MagicBlock.SHAPE)) {
            String src = render(shape);
            assertTrue(function(src, "give").contains("minecraft:melon 4"),
                    shape + ": the give (a grant, a re-wish) must hand the kit over");
            assertTrue(function(src, "give").contains("give_one"),
                    shape + ": the give must hand the thing itself over too");
            assertFalse(function(src, "give_one").contains("minecraft:melon"),
                    shape + ": give_one is the thing ALONE");
            String refund = function(src, "refund");
            assertTrue(refund.contains("give_one") && !refund.contains(":give\n")
                            && !refund.endsWith(":give"),
                    shape + ": a refund must hand back the thing alone, never the kit: " + refund);
        }
    }

    @Test
    void aShapeWithNoKitStillRenders() {
        String src = render(ItemCatalogue.MACHINE, null);
        assertFalse(src.contains("${KIT}"), "KIT is optional: a machine with no kit is the common case");
    }

    @Test
    void theCompiledRefundNeverRunsTheKit(@TempDir Path work) throws Exception {
        Assumptions.assumeTrue(CbScriptCompiler.available(),
                "CBScript compiler not on this machine — skipping the live compile");
        String ns = "kit_machine";
        Path zip = work.resolve(ns + ".zip");
        CompileResult r = CbScriptCompiler.compile(work.resolve(ns), ns, "kit", render(
                ItemCatalogue.MACHINE), zip);
        assertTrue(r.ok(), "did not compile: " + r.failureSummary());
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            String dir = "data/" + ns + "/function/";
            for (String fn : new String[]{"give", "give_one", "refund"}) {
                assertNotNull(zf.getEntry(dir + fn + ".mcfunction"), "no " + fn);
            }
            String refund = new String(zf.getInputStream(zf.getEntry(dir + "refund.mcfunction"))
                    .readAllBytes());
            String giveOne = new String(zf.getInputStream(zf.getEntry(dir + "give_one.mcfunction"))
                    .readAllBytes());
            String give = new String(zf.getInputStream(zf.getEntry(dir + "give.mcfunction"))
                    .readAllBytes());
            assertTrue(refund.contains(ns + ":give_one") && !refund.contains(ns + ":give\n"),
                    "the refund runs give_one: " + refund);
            assertFalse(giveOne.contains("melon"), "give_one carries no kit: " + giveOne);
            assertTrue(give.contains("melon"), "give carries the kit: " + give);
        }
    }
}
