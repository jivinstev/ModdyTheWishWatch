package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.moddingfromamod.marshal.Spot;
import org.junit.jupiter.api.Test;

/** a make's relative summon is moved to the locked spot, and nothing else is touched. */
class BuilderAtTheSpotTest {

    private static BuilderLowering.Call run(String... cmds) {
        JsonObject a = new JsonObject();
        JsonArray c = new JsonArray();
        for (String s : cmds) {
            c.add(s);
        }
        a.add("command", c);
        return new BuilderLowering.Call("run_command", a);
    }

    @Test
    void aRelativeSummonGoesToTheSpot() {
        var out = BuilderMake.atTheSpot(run("summon cow ~ ~ ~ {CustomName:\"Daisy\"}", "say hi"), new Spot("", 10, 64, -3));
        assertEquals("summon cow 10 64 -3 {CustomName:\"Daisy\"}", out.args().getAsJsonArray("command").get(0).getAsString());
        assertEquals("say hi", out.args().getAsJsonArray("command").get(1).getAsString());
    }

    @Test
    void noSpotOrNoSummonLeavesTheCallAlone() {
        var call = run("summon cow ~ ~ ~");
        assertSame(call, BuilderMake.atTheSpot(call, null));
        var give = run("wishdatapack zap give");
        assertSame(give, BuilderMake.atTheSpot(give, new Spot("", 1, 2, 3)));
    }
}
