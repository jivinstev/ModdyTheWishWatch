package com.moddingfromamod.wishbuilder;

import java.util.List;

/**
 * One spec per kind, each from a real ready-made or real catalogue ids — what Gate A lowers
 * ({@code BuilderLoweringTest}) and Gate B makes end to end through the real tools
 * ({@code BuilderMakeGameTest}), so both gates hold the same list.
 */
public final class BuilderExamples {

    private BuilderExamples() {
    }

    /** an earlier issue's example: a composed snack machine with two powers (the item engine stacks them, measured). */
    public static final String COMPOSED = "{\"builder\":1,\"kind\":\"machine\",\"from\":\"\",\"name\":\"Zoom Pizza\","
            + "\"opts\":{\"look\":\"look:pizza\",\"snack_power\":\"speed\",\"snack_effect\":\"effect:speed\","
            + "\"snack_power2\":\"jump\",\"snack_effect2\":\"effect:jump\",\"strength\":\"2\","
            + "\"ingredient\":\"ingredient:minecraft:wheat\",\"fill\":\"3\"}}";

    public static final List<String> ONE_PER_KIND = List.of(
            "{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\",\"scheme\":\"ocean\",\"size\":3,"
                    + "\"powers\":[\"power:glowing\"],\"temper\":\"shy\",\"count\":2,\"name\":\"Blueberry\"}",
            "{\"builder\":1,\"kind\":\"ride\",\"from\":\"preset:species/pet_dragon\",\"powers\":[\"power:flies\"]}",
            "{\"builder\":1,\"kind\":\"creature\",\"from\":\"mob:minecraft:cow\",\"count\":2,\"name\":\"Daisy\"}",
            "{\"builder\":1,\"kind\":\"item\",\"from\":\"preset:items/lightning_sword_lightning\",\"name\":\"Zappy\"}",
            "{\"builder\":1,\"kind\":\"machine\",\"from\":\"preset:items/slushie_machine_load_and_pour\"}",
            "{\"builder\":1,\"kind\":\"magic_block\",\"from\":\"preset:items/lucky_block_outcome_table\"}",
            // a building at size 4 in Royal blocks, a picture in Sunset colours.
            "{\"builder\":1,\"kind\":\"building\",\"from\":\"preset:buildings/secret_treehouse\",\"size\":4,\"scheme\":\"royal\"}",
            "{\"builder\":1,\"kind\":\"picture\",\"from\":\"preset:pictures/castle\",\"scheme\":\"sunset\"}",
            "{\"builder\":1,\"kind\":\"event\",\"from\":\"preset:events/chicken_rain\",\"size\":1}",
            "{\"builder\":1,\"kind\":\"rule\",\"opts\":{\"when\":\"when:jumps\",\"does\":\"rule:flowers\"}}",
            // a Builder BODY (not in the wish library), whole, as a ride, recoloured.
            "{\"builder\":1,\"kind\":\"ride\",\"from\":\"preset:bodies/horse\",\"scheme\":\"royal\",\"name\":\"Comet\"}",
            // rules about a MOB — zombies wear a gold hat as they appear; a defeated cow drops a cake.
            "{\"builder\":1,\"kind\":\"rule\",\"opts\":{\"when\":\"when:mob_spawns\",\"who\":\"mob:minecraft:zombie\",\"does\":\"rule:hat\"}}",
            "{\"builder\":1,\"kind\":\"rule\",\"opts\":{\"when\":\"when:mob_dies\",\"who\":\"mob:minecraft:cow\",\"does\":\"rule:cake\"}}",
            // a Builder snack machine — its food is made first, then the machine that pours it.
            "{\"builder\":1,\"kind\":\"machine\",\"from\":\"preset:items/popcorn_machine\"}",
            // a skin — a cat made of ice.
            "{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\",\"scheme\":\"ice\",\"name\":\"Frosty\"}",
            // a COMPOSED snack machine — a pizza that makes him fast and jumpy, three wheat a fill.
            COMPOSED,
            // a COMPOSED picture — the duck on a starry night, three blocks a side.
            "{\"builder\":1,\"kind\":\"picture\",\"opts\":{\"subject\":\"preset:pictures/duck\",\"background\":\"night\",\"frame\":\"3\"}}",
            // SUPPLIES — sixteen real oak logs in his bag (give_items).
            "{\"builder\":1,\"kind\":\"supplies\",\"from\":\"supply:oak_log\",\"opts\":{\"amount\":\"16\"}}");
}
