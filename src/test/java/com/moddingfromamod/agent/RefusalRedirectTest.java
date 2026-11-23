package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.tools.RunCommandTool;
import org.junit.jupiter.api.Test;

/**
 * A refusal that names no alternative is an invitation to improvise.
 *
 * <p>Measured in a live run: the agent tried {@code wishmob list}, was told to "stick to things
 * like summon, give, effect" — none of which list creatures — and improvised by re-emitting an
 * entire {@code define_creature} for a creature that already existed. That cost <b>21 seconds of a
 * 64-second build</b>, the single largest chunk, and the cheap path ({@code wishmob spawn <id>})
 * had already succeeded one call earlier.
 *
 * <p>The guardrail was right to refuse. The message was wrong to stop there.
 */
class RefusalRedirectTest {

    private static String refusalFor(String command) {
        JsonObject args = new JsonObject();
        args.addProperty("command", command);
        return new RunCommandTool().call(args).text();
    }

    @Test
    void refusingAModCommandNamesTheToolThatDoesTheJob() {
        String msg = refusalFor("wishmob list");
        assertTrue(msg.contains("list_creatures"),
                "the refusal should point at the tool that lists creatures, or the agent will "
                        + "improvise an expensive way round: " + msg);
        assertTrue(msg.contains("wishmob spawn"),
                "it should say the cheap path IS allowed — re-defining an existing creature to "
                        + "spawn it cost 21s of a 64s build: " + msg);
    }

    @Test
    void anOrdinaryRefusalStaysShort() {
        // The redirect is for THIS mod's capabilities. A blocked vanilla command has no tool to
        // point at, and padding every refusal with creature advice would train the agent to skim.
        String msg = refusalFor("stop");
        assertFalse(msg.contains("list_creatures"),
                "a plain blocked command should not carry creature advice: " + msg);
    }
}
