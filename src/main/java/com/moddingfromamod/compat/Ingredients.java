package com.moddingfromamod.compat;

/** How a NeoForge CUSTOM ingredient names its type in recipe JSON (1.21.4 half). */
public final class Ingredients {
    private Ingredients() {
    }

    /**
     * NeoForge 21.4 dispatches custom ingredients on {@code neoforge:ingredient_type}; 21.1 used plain
     * {@code type}. Read out of 21.4.157's {@code IngredientCodecs}. Written with the old key, the recipe
     * fails to parse ("does not contain a key [neoforge:ingredient_type]") and is dropped at load.
     */
    public static final String TYPE_KEY = "neoforge:ingredient_type";
}
