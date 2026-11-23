package com.moddingfromamod.builder;

import com.google.gson.JsonParser;

/**
 * an earlier issue's fixture, shared by {@code RoomClearingTest} (Gate A) and {@code WalkThroughGameTest} (Gate B): a stone
 * room 7x7 with a roof and a door (x 0..6), and beside it a courtyard walled two high (a ring) with no roof (x 8..14).
 */
public final class RoomClearingFixture {

    private RoomClearingFixture() {
    }

    public static BuildPlan plan() {
        return BuildPlan.fromJson(JsonParser.parseString("""
            {"id":"t_room_court","name":"Room and Court","shapes":[
             {"kind":"box","dx":0,"dy":0,"dz":0,"width":7,"height":5,"depth":7,"block":"minecraft:stone_bricks","hollow":true},
             {"kind":"box","dx":3,"dy":1,"dz":0,"width":1,"height":2,"depth":1,"block":"minecraft:air"},
             {"kind":"box","dx":8,"dy":0,"dz":0,"width":7,"height":1,"depth":7,"block":"minecraft:stone_bricks"},
             {"kind":"box","dx":8,"dy":1,"dz":0,"width":7,"height":2,"depth":7,"block":"minecraft:stone_brick_wall","hollow":true},
             {"kind":"box","dx":11,"dy":1,"dz":0,"width":1,"height":2,"depth":1,"block":"minecraft:air"}]}
            """).getAsJsonObject());
    }
}
