package com.moddingfromamod.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * <b>What a 1.21.4 item node DRAWS, for the tests.</b>
 *
 * <p>A wish icon's node is a composite — backing glow, twinkles, the PICTURE, the overlay select —
 * and the picture is a vanilla item where {@code items/icon-bases.json} names one. So "does the
 * name draw what the number drew" is asked of the picture layer, and "what should 1.21.4 show for
 * the sprite 1.21.1 draws" goes through the same map. One helper, so no test re-derives the shape.
 */
final class IconNodes {

    /** Index of the picture in a wish icon's composite: after the two backing layers. */
    static final int PICTURE = VanillaItemPack.BACKING.size();

    private IconNodes() {
    }

    /** The node that draws the item itself — the whole node when it is not a composite. */
    static JsonObject picture(JsonObject node) {
        if (!"minecraft:composite".equals(node.get("type").getAsString())) {
            return node;
        }
        JsonArray layers = node.getAsJsonArray("models");
        return layers.get(PICTURE).getAsJsonObject();
    }

    /** The picture's model id. */
    static String pictureModel(JsonObject node) {
        return picture(node).get("model").getAsString();
    }

    /**
     * What 1.21.4 draws for a binding whose own model is {@code legacyModel}: the vanilla base for
     * a mapped wish icon, otherwise the same model. (no binding names a {@code _flat} twin
     * any more, so there is nothing to strip.)
     */
    static String modernFor(String legacyModel) {
        int slash = legacyModel.lastIndexOf('/');
        if (legacyModel.contains(":item/wish/")) {
            String vanilla = IconBases.forIcon(legacyModel.substring(slash + 1));
            if (vanilla != null) {
                return vanilla;
            }
        }
        return legacyModel;
    }

    /** The node that carries his colour: the backing glow on a composite, else the node itself. */
    static JsonObject tinted(JsonObject node) {
        if (!"minecraft:composite".equals(node.get("type").getAsString())) {
            return node;
        }
        return node.getAsJsonArray("models").get(0).getAsJsonObject();
    }
}
