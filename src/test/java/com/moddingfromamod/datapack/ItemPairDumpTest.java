package com.moddingfromamod.datapack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Writes composed cells out so the REAL CBScript compiler can be run over them. */
class ItemPairDumpTest {
    @Test
    void dumpPairs() throws Exception {
        String dir = System.getenv("MFAM_PAIR_DUMP");
        if (dir == null) {
            return;
        }
        ItemCatalogue c = ItemCatalogue.load();
        Path out = Path.of(dir);
        Files.createDirectories(out);
        List<String> ids = c.all().stream().map(ItemCatalogue.Entry::id).toList();
        int n = 0;
        for (String a : ids) {
            for (String b : ids) {
                if (a.equals(b)) {
                    continue;
                }
                for (String shape : ItemCatalogue.EFFECT_SHAPES) {
                    Map<String, Map<String, String>> args = new LinkedHashMap<>();
                    args.put(a, ItemCatalogueTestSupport.argsFor(c.get(a), shape));
                    args.put(b, ItemCatalogueTestSupport.argsFor(c.get(b), shape));
                    String src = c.render(List.of(a, b), shape, args,
                            ItemCatalogueTestSupport.shapeArgs(a + "_" + b + "_" + shape));
                    Files.writeString(out.resolve(a + "+" + b + "_" + shape + ".cbscript"), src);
                    n++;
                }
            }
        }
        System.out.println("PAIR DUMP: " + n + " composed cells");
    }
}
