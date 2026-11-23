package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * Gate A for the geometry parser. Like the rest of this codebase the important property isn't
 * "parses a perfect Blockbench export" — it's "survives whatever comes off disk": missing fields,
 * a zero-size cube, the two different UV encodings, and outright garbage all degrade instead of
 * throwing, because this JSON is drawn every frame and is authored by an agent + a child.
 */
class BedrockGeometryTest {

    private static BedrockGeometry parse(String json) {
        return BedrockGeometry.fromJson(JsonParser.parseString(json).getAsJsonObject());
    }

    /** A realistic minimal Blockbench "Bedrock" export: one root bone, one box-UV cube. */
    private static final String SIMPLE = """
        {
          "format_version": "1.12.0",
          "minecraft:geometry": [
            {
              "description": {"identifier":"geometry.blorp","texture_width":32,"texture_height":32},
              "bones": [
                {
                  "name": "body",
                  "pivot": [0, 8, 0],
                  "cubes": [
                    {"origin": [-4, 4, -2], "size": [8, 8, 4], "uv": [0, 0]}
                  ]
                }
              ]
            }
          ]
        }
        """;

    @Test
    void parsesASimpleModel() {
        BedrockGeometry g = parse(SIMPLE);
        assertNotNull(g);
        assertEquals(32, g.textureWidth());
        assertEquals(32, g.textureHeight());
        assertEquals(1, g.bones().size());
        BedrockGeometry.Bone body = g.bones().get(0);
        assertEquals("body", body.name());
        assertNull(body.parent());
        assertEquals(8f, body.pivot()[1], 1e-6);
        assertEquals(1, body.cubes().size());
        BedrockGeometry.Cube c = body.cubes().get(0);
        assertEquals(8f, c.size()[0], 1e-6);
        assertEquals(0f, c.inflate(), 1e-6);
        assertFalse(c.mirror());
        assertFalse(c.uv().isPerFace());
        assertEquals(0f, c.uv().boxUv()[0], 1e-6);
    }

    @Test
    void parsesParentInflateMirrorAndRotation() {
        BedrockGeometry g = parse("""
            {"minecraft:geometry":[{"description":{"texture_width":16,"texture_height":16},
              "bones":[
                {"name":"body","pivot":[0,10,0]},
                {"name":"head","parent":"body","pivot":[0,18,0],"rotation":[10,0,0],
                 "cubes":[{"origin":[-3,18,-3],"size":[6,6,6],"uv":[0,0],
                           "inflate":0.5,"mirror":true,"rotation":[5,0,0],"pivot":[0,20,0]}]}
              ]}]}
            """);
        assertNotNull(g);
        assertEquals(2, g.bones().size());
        BedrockGeometry.Bone head = g.bones().get(1);
        assertEquals("body", head.parent());
        assertEquals(10f, head.rotation()[0], 1e-6);
        BedrockGeometry.Cube c = head.cubes().get(0);
        assertEquals(0.5f, c.inflate(), 1e-6);
        assertTrue(c.mirror());
        assertNotNull(c.rotation());
        assertEquals(5f, c.rotation()[0], 1e-6);
        assertNotNull(c.pivot());
        assertEquals(20f, c.pivot()[1], 1e-6);
    }

    @Test
    void parsesPerFaceUv() {
        BedrockGeometry g = parse("""
            {"minecraft:geometry":[{"description":{"texture_width":64,"texture_height":64},
              "bones":[{"name":"b","cubes":[
                {"origin":[0,0,0],"size":[2,2,2],
                 "uv":{"north":{"uv":[0,0],"uv_size":[2,2]},
                       "up":{"uv":[4,0],"uv_size":[2,2]}}}
              ]}]}]}
            """);
        assertNotNull(g);
        BedrockGeometry.Cube c = g.bones().get(0).cubes().get(0);
        assertTrue(c.uv().isPerFace());
        assertEquals(4f, c.uv().perFace().get("up")[0], 1e-6);
        assertEquals(2f, c.uv().perFace().get("north")[2], 1e-6);
    }

    @Test
    void acceptsARawGeometryObjectWithoutTheWrapper() {
        BedrockGeometry g = parse("""
            {"description":{"texture_width":16,"texture_height":16},
             "bones":[{"name":"b","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":[0,0]}]}]}
            """);
        assertNotNull(g);
        assertEquals(1, g.cubeCount());
    }

    @Test
    void dropsZeroSizeCubesButKeepsTheBone() {
        BedrockGeometry g = parse("""
            {"minecraft:geometry":[{"bones":[
              {"name":"b","cubes":[
                {"origin":[0,0,0],"size":[0,0,0],"uv":[0,0]},
                {"origin":[0,0,0],"size":[2,2,2],"uv":[0,0]}
              ]}]}]}
            """);
        assertNotNull(g);
        assertEquals(1, g.bones().size());
        assertEquals(1, g.cubeCount()); // the zero cube was dropped
    }

    @Test
    void garbageAndEmptyDegradeToNullNeverThrow() {
        assertNull(BedrockGeometry.fromJson(null));
        assertNull(parse("{}"));
        assertNull(parse("{\"minecraft:geometry\":[]}"));
        assertNull(parse("{\"minecraft:geometry\":[{\"bones\":[]}]}"));
        // a bone with a missing name is skipped -> no usable bones -> null
        assertNull(parse("{\"minecraft:geometry\":[{\"bones\":[{\"pivot\":[0,0,0]}]}]}"));
        // totally wrong types where numbers are expected must not throw
        BedrockGeometry g = parse("""
            {"minecraft:geometry":[{"description":{"texture_width":"nope"},
              "bones":[{"name":"b","cubes":[{"origin":"x","size":[1,1,1],"uv":[0,0]}]}]}]}
            """);
        assertNotNull(g);
        assertEquals(16, g.textureWidth()); // fell back to default
    }
}
