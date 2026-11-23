package com.moddingfromamod.wish;

import java.util.List;

/**
 * <b>A TEST FIXTURE, never shipped</b>: the hard-coded built-in templates and the six retired panel
 * buttons exactly as {@code WishCatalog} carried them until they were deleted
 *.
 *
 * <p>The matcher ({@link WishTemplates}) is unchanged — its future is — and these were the
 * fixtures its tests were written against: "knight" must not be {@code night_spooky}, a counted
 * chicken wish keeps its number, a loose guess survives as a hint. Moving them here keeps that
 * coverage without a single one of them reaching a child. Nothing under {@code src/main} may read
 * this class; it lives in the test source set, so nothing can.
 */
public final class OldBuiltInsFixture {

    private OldBuiltInsFixture() {
    }

    /** The six retired panel buttons, each naming its own icon. */
    public static final List<WishCatalog.Preset> PRESETS = List.of(
            new WishCatalog.Preset("Make it rain chickens!", "make it rain chickens", "",
                    "minecraft:chicken_spawn_egg"),
            new WishCatalog.Preset("Give me treasure!", "give me treasure", "", "minecraft:chest"),
            new WishCatalog.Preset("Make me super fast!", "make me super fast", "", "minecraft:sugar"),
            new WishCatalog.Preset("Make it night + spooky!", "make it night and spooky", "",
                    "minecraft:jack_o_lantern"),
            new WishCatalog.Preset("Build me a tower!", "build me a tower", "", "minecraft:bricks"),
            new WishCatalog.Preset("Make me fly!", "make me fly", "", "minecraft:elytra"));

    /** The old built-in template set. */
    public static final String JSON = """
        {"templates":[
          {"id":"rock_crusher","phrase":"build me a rock crusher machine",
           "all":["crusher"],
           "commands":["wishmachine crushing_plant"],
           "say":"Your rock crusher is ready! Drop rocks in the top and watch them get squashed into gravel."},

          {"id":"cobble_factory_wish","phrase":"build me a factory",
           "all":["factory"],
           "commands":["wishmachine cobble_factory"],
           "say":"Your factory is built! Rocks go in the crusher and gravel rides the belt to the end."},

          {"id":"windmill_wish","phrase":"build me a windmill with sails",
           "all":["windmill"],
           "commands":["wishmachine windmill"],
           "say":"Your windmill is turning! It makes its own power, all by itself."},

          {"id":"conveyor_wish","phrase":"build me a conveyor belt",
           "all":["conveyor"],
           "commands":["wishmachine belt_conveyor"],
           "say":"Your conveyor belt is running! Put something on it and watch it ride."},

          {"id":"carousel_wish","phrase":"build me a spinning carousel merry go round",
           "all":["carousel"],
           "commands":["wishmachine carousel"],
           "say":"Your carousel is spinning round and round!"},

          {"id":"water_mill_wish","phrase":"build me a water wheel mill",
           "all":["water","wheel"],
           "commands":["wishmachine water_mill"],
           "say":"Your water wheel is turning the millstone! Real falling water makes it go."},

          {"id":"rain_chickens","phrase":"make it rain chickens",
           "all":["chicken"],
           "commands":["summon minecraft:chicken ~ ~14 ~"],
           "repeat":25,"countable":true,"maxRepeat":60,
           "say":"It's raining chickens!"},

          {"id":"treasure","phrase":"give me treasure",
           "all":["treasure"],
           "commands":["give @p minecraft:diamond 12",
                       "give @p minecraft:gold_ingot 16",
                       "give @p minecraft:emerald 8",
                       "give @p minecraft:golden_apple 3"],
           "say":"Treasure for you!"},

          {"id":"super_fast","phrase":"make me super fast speed",
           "all":["fast"],
           "commands":["effect give @p minecraft:speed 120 4",
                       "effect give @p minecraft:jump_boost 120 2"],
           "say":"ZOOM! You are super fast!"},

          {"id":"night_spooky","phrase":"make it night and spooky dark",
           "all":["night"],
           "commands":["time set night",
                       "weather thunder 120",
                       "effect give @p minecraft:night_vision 200 0"],
           "say":"Spooky night time! Ooooooo!"},

          {"id":"tower","phrase":"build me a tower",
           "all":["tower"],
           "commands":["fill ~2 ~ ~2 ~4 ~20 ~4 minecraft:stone_bricks hollow",
                       "fill ~3 ~21 ~3 ~3 ~22 ~3 minecraft:glowstone",
                       "setblock ~3 ~1 ~2 minecraft:torch"],
           "say":"I built you a tower! Look around!"},

          {"id":"fly","phrase":"make me fly flying",
           "all":["fly"],
           "commands":["effect give @p minecraft:levitation 12 1",
                       "effect give @p minecraft:slow_falling 120 0"],
           "say":"Up you go! You can fly!"},

          {"id":"zombies","phrase":"spawn some zombies",
           "all":["zombie"],
           "commands":["summon minecraft:zombie ~ ~1 ~"],
           "repeat":5,"countable":true,"maxRepeat":40,
           "say":"Uh oh... zombies!"},

          {"id":"cats","phrase":"give me cats kittens",
           "any":["cat","kitten"],
           "commands":["summon minecraft:cat ~ ~1 ~"],
           "repeat":5,"countable":true,"maxRepeat":30,
           "say":"Here come the cats!"},

          {"id":"rainbow_sheep","phrase":"rainbow sheep",
           "all":["sheep"],
           "commands":["summon minecraft:sheep ~ ~1 ~ {Color:0b}",
                       "summon minecraft:sheep ~ ~1 ~ {Color:4b}",
                       "summon minecraft:sheep ~ ~1 ~ {Color:5b}",
                       "summon minecraft:sheep ~ ~1 ~ {Color:6b}",
                       "summon minecraft:sheep ~ ~1 ~ {Color:11b}",
                       "summon minecraft:sheep ~ ~1 ~ {Color:14b}"],
           "say":"Rainbow sheep!"},

          {"id":"sunny","phrase":"make it sunny day time daytime sunshine",
           "any":["sunny","sunshine","day time","daytime","make it day"],
           "commands":["time set day","weather clear"],
           "say":"Sunshine!"},

          {"id":"diamonds","phrase":"give me diamonds",
           "all":["diamond"],
           "commands":["give @p minecraft:diamond 32"],
           "say":"Diamonds!"},

          {"id":"cake","phrase":"cake party",
           "any":["cake","party"],
           "commands":["give @p minecraft:cake 5",
                       "particle minecraft:firework ~ ~2 ~ 2 2 2 0.1 60"],
           "say":"Party time! Cake for everyone!"}
        ]}
        """;

    /** The old built-ins as a live matcher. */
    public static WishTemplates templates() {
        return WishTemplates.fromJson(JSON);
    }
}
