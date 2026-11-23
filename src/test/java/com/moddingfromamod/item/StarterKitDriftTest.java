package com.moddingfromamod.item;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * Every filled machine on the starter shelf hands over the kit {@link MachineFill#kit} says it
 * should (follow-up).
 *
 * <p>A starter's pack is COMPILED and committed, so it does not change when the rule does: a
 * machine promoted before the kit existed would go on handing over a machine and nothing to put
 * in it, and every gate that makes a fresh machine would stay green. This reads each starter's
 * own saved recipe, asks the rule, and looks for exactly those give lines in its pack.
 */
class StarterKitDriftTest {

    private static final Path SHELF = Path.of("src/main/resources/starter-library");

    @Test
    void everyStarterMachineHandsOverItsKit() throws Exception {
        List<String> checked = new ArrayList<>();
        try (Stream<Path> items = Files.list(SHELF.resolve("items"))) {
            for (Path dir : items.toList()) {
                Path json = dir.resolve("item.json");
                if (!Files.isRegularFile(json)) {
                    continue;
                }
                JsonObject o = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
                if (!o.has("effects") || !o.get("effects").toString().contains("load_and_pour")) {
                    continue;
                }
                String id = o.get("id").getAsString();
                JsonObject p = o.getAsJsonObject("params");
                List<MachineFill.Ingredient> required = new ArrayList<>();
                List<MachineFill.Ingredient> extras = new ArrayList<>();
                List<String> gives = new ArrayList<>();
                for (String one : p.get("INGREDIENTS").getAsString().split(",")) {
                    required.add(new MachineFill.Ingredient(one.trim(), one.trim()));
                    gives.add(one.trim());
                }
                String xs = p.has("EXTRAS") ? p.get("EXTRAS").getAsString().trim() : "none";
                if (!xs.isEmpty() && !"none".equalsIgnoreCase(xs)) {
                    for (String one : xs.split(",")) {
                        extras.add(new MachineFill.Ingredient(one.trim(), one.trim()));
                        gives.add(one.trim());
                    }
                }
                for (String g : gives) {
                    // A made ingredient's give line carries its components, which only make_item
                    // can render; a starter using one needs this test taught to read them.
                    assertTrue(g.contains(":"), id + ": ingredient '" + g + "' is a made item — "
                            + "teach this test to render its give line before shelving it");
                }
                var spec = new MachineFill.Spec(required, extras, "minecraft:stone", "X", null,
                        Integer.parseInt(p.get("MAX").getAsString().trim()), false);
                String give = giveFunction(SHELF.resolve("datapacks").resolve(id).resolve(id + ".zip"));
                for (var part : MachineFill.kit(spec, gives)) {
                    String line = "give @s " + part.give() + " " + part.count();
                    assertTrue(give.contains(line), id + "'s pack does not hand over '" + line
                            + "' — its kit is stale; rebuild the pack. Give function:\n" + give);
                }
                checked.add(id);
            }
        }
        assertTrue(!checked.isEmpty(), "no filled machine on the starter shelf — this test checked "
                + "nothing, which is not a pass");
    }

    private static String giveFunction(Path zip) throws Exception {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            for (ZipEntry e : java.util.Collections.list(z.entries())) {
                if (e.getName().endsWith("function/give.mcfunction")) {
                    return new String(z.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError(zip + " has no give function");
    }
}
