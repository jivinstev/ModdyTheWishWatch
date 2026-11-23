package com.moddingfromamod.item;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.compat.ModelData;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;

/**
 * <b>The picture of a wished item, read off the stack itself</b> —
 * {@link ItemIconPicture} fed with what the stack says: the icon by its {@code custom_model_data}
 * number, its dye ({@code minecraft:dyed_color}), its accent ({@code colors[0]}, 1.21.4) and its
 * overlay ({@code strings[0]}). Reading the STACK rather than the design record is the point: it is
 * what the child holds, so the picture cannot disagree with his inventory.
 */
public final class ItemIconShot {

    private ItemIconShot() {
    }

    /** The PNG, or null when this stack is not drawn by our icon library (a real golden axe). */
    public static byte[] of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        var cmd = stack.get(DataComponents.CUSTOM_MODEL_DATA);
        if (cmd == null) {
            return null;
        }
        int number = ModelData.id(cmd);
        IconSprites.Sprite sprite = null;
        for (IconSprites.Sprite s : IconSprites.bindings()) {
            if (s.customModelData() == number) {
                sprite = s;
                break;
            }
        }
        if (sprite == null) {
            return null;
        }
        var dye = stack.get(DataComponents.DYED_COLOR);
        Integer dyeRgb = dye == null ? null : dye.rgb() & 0xFFFFFF;
        return draw(sprite.model(), drawnByNumber(number), dyeRgb, ModelData.accentOf(cmd),
                ModelData.overlayOf(cmd));
    }

    /**
     * <b>The picture of an item drawn from its DESIGN</b> — the sprite, colour and effects
     * the shelf records, which is what the account page composes its icon from. A wish built on a
     * real vanilla item (a pickaxe that must still mine) carries no icon number on the stack, so
     * {@link #of} cannot draw it, and the wish card fell back to the CAMERA: a photo of whatever
     * he was facing — a field, his hotbar — filed under the wish and shown on his profile. Our own
     * library art for the sprite the design names is the honest picture of it. Null when the design
     * names no sprite our library draws.
     */
    public static byte[] ofDesign(String sprite, Integer rgb, java.util.List<String> effects) {
        if (sprite == null || sprite.isBlank()) {
            return null;
        }
        IconSprites.Sprite s = IconSprites.byName(sprite.trim());
        if (s == null) {
            return null;
        }
        String overlay = effects == null ? null : IconOverlays.forEffects(effects);
        return draw(s.model(), drawnByNumber(s.customModelData()), rgb == null ? null : rgb & 0xFFFFFF,
                null, overlay);
    }

    /**
     * Pure apart from the classpath: which layer takes which colour. A drawn icon of kind
     * {@code fixed} keeps its own colours; an accent icon's second layer takes the accent (or, with
     * none on the stack, stays as drawn); an older library icon tints layer 0 only, as the pack does.
     */
    static byte[] draw(String model, IconSprites.LibraryIcon lib, Integer dyeRgb, Integer accentRgb,
                       String overlay) {
        List<String> textures = ItemIconPicture.layers(text("/assets/" + ModdingFromAModMod.MODID
                + "/models/item/" + model + ".json"));
        if (textures.isEmpty()) {
            return null;
        }
        List<ItemIconPicture.Layer> layers = new ArrayList<>();
        for (int i = 0; i < textures.size(); i++) {
            Integer tint = null;
            if (i == 0 && (lib == null || lib.tints())) {
                tint = dyeRgb;
            } else if (i == 1 && lib != null && lib.accent()) {
                tint = accentRgb;
            }
            layers.add(new ItemIconPicture.Layer(textures.get(i), tint));
        }
        if (overlay != null && IconOverlays.known(overlay)) {
            layers.add(new ItemIconPicture.Layer(ModdingFromAModMod.MODID + ":item/"
                    + com.moddingfromamod.pack.VanillaItemPack.overlaySprite(overlay), null));
        }
        return ItemIconPicture.render(layers, ItemIconShot::bytes);
    }

    private static IconSprites.LibraryIcon drawnByNumber(int number) {
        for (IconSprites.LibraryIcon i : IconSprites.drawn()) {
            if (i.number() == number) {
                return i;
            }
        }
        return null;
    }

    private static byte[] bytes(String path) {
        try (InputStream in = ItemIconShot.class.getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private static String text(String path) {
        byte[] b = bytes(path);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }
}
