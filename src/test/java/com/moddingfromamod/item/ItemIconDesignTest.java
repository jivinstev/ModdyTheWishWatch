package com.moddingfromamod.item;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * an item built on a real vanilla item (a pickaxe that must still mine) carries no icon
 * number on its stack, so the wish card used to fall back to a CAMERA shot of whatever he faced —
 * shown on his profile as the item's picture. It is now drawn from its DESIGN (the sprite, colour
 * and effects the shelf records), as the account page draws it.
 */
class ItemIconDesignTest {

    @Test
    void theSpritesMuseNamedForVanillaBasedItemsAreDrawn() throws Exception {
        // The sprites the step-6 grants named alongside a base_item.
        for (String sprite : List.of("pickaxe", "bow", "shield", "grappling_hook", "helmet", "hammer", "cup", "machine")) {
            byte[] png = ItemIconShot.ofDesign(sprite, 0xFFC030, List.of());
            assertNotNull(png, sprite + " is in our library and must draw");
            var img = ImageIO.read(new ByteArrayInputStream(png));
            assertTrue(img.getWidth() > 0 && img.getHeight() > 0, sprite + " drew an empty picture");
        }
    }

    @Test
    void anEffectAddsItsOverlayAndAnUnknownSpriteDrawsNothing() {
        assertNotNull(ItemIconShot.ofDesign("pickaxe", null, List.of("lightning")));
        assertNull(ItemIconShot.ofDesign("not_a_sprite_we_have", 0xFFFFFF, List.of()),
                "an unknown sprite is refused, never a default picture");
        assertNull(ItemIconShot.ofDesign(null, 0xFFFFFF, List.of()));
    }
}
