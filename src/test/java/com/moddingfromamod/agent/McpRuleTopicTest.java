package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** a set_rule that makes or changes a rule names it as the last thing his wish touched. */
class McpRuleTopicTest {

    @Test
    void aRuleHeMadeIsTheTopicAListOrARemovalIsNot() {
        assertEquals("bouncy_cloud", McpProtocol.ruleTouched(JsonParser.parseString(
                "{\"name\":\"Bouncy Cloud\",\"when\":\"near_block\"}").getAsJsonObject()));
        assertNull(McpProtocol.ruleTouched(JsonParser.parseString(
                "{\"list\":true}").getAsJsonObject()));
        assertNull(McpProtocol.ruleTouched(JsonParser.parseString(
                "{\"name\":\"bouncy_cloud\",\"remove\":true}").getAsJsonObject()));
        assertNull(McpProtocol.ruleTouched(null));
    }
}
