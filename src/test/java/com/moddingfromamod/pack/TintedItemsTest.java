package com.moddingfromamod.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.wishbuilder.BuilderItemPowers;
import org.junit.jupiter.api.Test;

/**
 * <b>Gate A on the tinted real items</b>: the generated pack files ({@code tools/gen-tinted-items.py}) are
 * whole and drawable, every real item the Builder offers is either tinted or written down as not, and a give line
 * draws a real item in his colour only when asked.
 */
class TintedItemsTest {

    static byte[] res(String path) throws Exception {
        try (InputStream in = TintedItemsTest.class.getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    static JsonObject json(String path) throws Exception {
        byte[] b = res(path);
        assertNotNull(b, path + " is not on the classpath");
        return JsonParser.parseReader(new InputStreamReader(new java.io.ByteArrayInputStream(b), StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    static void models(JsonElement node, List<JsonObject> out) {
        if (node.isJsonObject()) {
            JsonObject o = node.getAsJsonObject();
            if (o.has("type") && o.get("type").getAsString().equals("minecraft:model")) {
                out.add(o);
            }
            o.entrySet().forEach(e -> models(e.getValue(), out));
        } else if (node.isJsonArray()) {
            node.getAsJsonArray().forEach(e -> models(e, out));
        }
    }

    /** Every stock item the Builder builds on a real item — at both tiers — is tinted or written down as not. */
    @Test
    void everyRealItemTheBuilderOffersIsTintedOrSaysWhyNot() {
        Set<String> tinted = new HashSet<>(TintedItems.items());
        for (BuilderItemPowers.Stock s : BuilderItemPowers.STOCK) {
            for (String tier : BuilderItemPowers.TIERS) {
                BuilderItemPowers.Stock t = BuilderItemPowers.tiered(s, tier);
                if (t == null || t.base().isEmpty()) {
                    continue;   // not offered there, or rides Moddy's carrier (dyed as before)
                }
                assertTrue(tinted.contains(t.base()) || TintedItems.notTinted().containsKey(s.id()),
                        s.id() + " at " + tier + " is " + t.base() + ": add it to tools/gen-tinted-items.py, or to "
                                + "NOT_TINTED with the reason");
            }
        }
        assertTrue(TintedItems.notTinted().get("trident").contains("special"), "the trident's reason is written down");
    }

    /** Every definition names models we ship, each tinted by the dye; every model's textures are files we ship. */
    @Test
    void everyGeneratedFileIsThereAndDrawable() throws Exception {
        Set<String> files = new HashSet<>(TintedItems.files());
        for (String f : files) {
            assertNotNull(res("/assets/wishpack/" + f), f);
        }
        for (String item : TintedItems.items()) {
            String model = TintedItems.modelFor(item);
            assertEquals("wishpack:tinted/" + item.substring("minecraft:".length()), model);
            String defPath = "items/" + model.substring("wishpack:".length()) + ".json";
            assertTrue(files.contains(defPath), defPath);
            List<JsonObject> nodes = new ArrayList<>();
            models(json("/assets/wishpack/" + defPath), nodes);
            assertFalse(nodes.isEmpty(), item);
            for (JsonObject n : nodes) {
                String m = n.get("model").getAsString();
                assertTrue(m.startsWith("wishpack:item/tinted/"), item + " names " + m);
                assertEquals("minecraft:dye", n.getAsJsonArray("tints").get(0).getAsJsonObject().get("type").getAsString(),
                        item + ": its tint layer reads dyed_color");
                String mp = "models/item/" + m.substring("wishpack:item/".length()) + ".json";
                assertTrue(files.contains(mp), mp);
                JsonObject mj = json("/assets/wishpack/" + mp);
                assertTrue(mj.get("parent").getAsString().startsWith("minecraft:item/"),
                        mp + ": vanilla's model is the parent, so it is held and shown as vanilla's");
                JsonObject tex = mj.getAsJsonObject("textures");
                String tint = tex.get("layer0").getAsString();
                assertTrue(tint.endsWith("_tint"), mp + ": layer0 is the one the dye tints");
                for (String k : tex.keySet()) {
                    String tp = "textures/item/" + tex.get(k).getAsString().substring("wishpack:item/".length()) + ".png";
                    assertTrue(files.contains(tp), tp);
                }
            }
        }
    }

    /** The tint layer is greyscale with a white highlight (the dye MULTIPLIES it); the kept layer never overlaps it. */
    @Test
    void theTintLayerIsGreyscaleAndTheKeptPartIsApart() throws Exception {
        int n = 0;
        for (String f : TintedItems.files()) {
            if (!f.endsWith("_tint.png")) {
                continue;
            }
            BufferedImage tint = ImageIO.read(new java.io.ByteArrayInputStream(res("/assets/wishpack/" + f)));
            byte[] keepBytes = res("/assets/wishpack/" + f.replace("_tint.png", "_keep.png"));
            BufferedImage keep = keepBytes == null ? null : ImageIO.read(new java.io.ByteArrayInputStream(keepBytes));
            int top = 0;
            for (int y = 0; y < tint.getHeight(); y++) {
                for (int x = 0; x < tint.getWidth(); x++) {
                    int p = tint.getRGB(x, y);
                    if ((p >>> 24) == 0) {
                        continue;
                    }
                    int r = (p >> 16) & 0xFF;
                    assertEquals(r, (p >> 8) & 0xFF, f);
                    assertEquals(r, p & 0xFF, f);
                    top = Math.max(top, r);
                    if (keep != null) {
                        assertEquals(0, keep.getRGB(x, y) >>> 24, f + " overlaps its kept part at " + x + "," + y);
                    }
                }
            }
            assertEquals(255, top, f + ": its brightest pixel is white, so the colour shows at full strength");
            n++;
        }
        assertTrue(n >= 16, "a tint layer per drawn model");
    }

    @Test
    void aGiveLineDrawsARealItemInItsColourOnlyWhenPainted() {
        MakeItemTool.Variant v = new MakeItemTool.Variant("Ocean Pickaxe", 0x2E7DD1);
        String plain = MakeItemTool.itemArg(v, 0, "hint", null, "minecraft:diamond_pickaxe", "");
        assertFalse(plain.contains("item_model"), plain);
        assertFalse(plain.contains("dyed_color"), plain);
        String painted = MakeItemTool.itemArg(v.painted(), 0, "hint", null, "minecraft:diamond_pickaxe", "");
        assertTrue(painted.startsWith("minecraft:diamond_pickaxe["), "still the real pickaxe: " + painted);
        assertTrue(painted.contains("minecraft:item_model='wishpack:tinted/diamond_pickaxe'"), painted);
        assertTrue(painted.contains("minecraft:dyed_color=" + 0x2E7DD1 + ","), painted);
        assertTrue(painted.contains("minecraft:tooltip_display={hidden_components:[\"minecraft:dyed_color\"]}"), painted);
        assertTrue(plain.contains("enchantment_glint_override=true"), "a plain real item keeps its glint: " + plain);
        assertFalse(painted.contains("enchantment_glint_override"),
                "a painted one takes no forced glint, which washed every scheme purple on Java: " + painted);
        String trident = MakeItemTool.itemArg(v.painted(), 0, "hint", null, "minecraft:trident", "");
        assertFalse(trident.contains("item_model"), "no tinted model, nothing painted: " + trident);
        assertNull(TintedItems.modelFor("minecraft:trident"));
        assertNull(TintedItems.modelFor(null));
    }
}
