package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * Every action-log record says which deployment wrote it — or says nothing, never a guess.
 * The stamp lives in {@code ActionLog.append}, the funnel every record kind passes through.
 */
class DeploymentStampTest {

    private static final String KEY = "moddingfromamod.deployment";

    /**
     * the stamp is the {@code deployment} SETTING, which only a server's override sets (one reads this
     * property, then {@code MODDY_DEPLOYMENT}). This stand-in reads the property the same way.
     */
    @org.junit.jupiter.api.BeforeEach
    void anOverrideThatReadsTheProperty() {
        com.moddingfromamod.config.ModdySettings.overridesForTest(java.util.List.of(
                new com.moddingfromamod.config.SettingsOverride() {
                    @Override
                    public java.util.Set<String> keys() {
                        return java.util.Set.of("deployment");
                    }

                    @Override
                    public java.util.Optional<String> value(String key) {
                        return "deployment".equals(key)
                                ? java.util.Optional.ofNullable(ActionLog.deploymentOf(System.getProperty(KEY), null))
                                : java.util.Optional.empty();
                    }
                }));
    }

    @org.junit.jupiter.api.AfterEach
    void putThePlugInsBack() {
        com.moddingfromamod.config.ModdySettings.overridesForTest(null);
    }

    @Test
    void thePublicModHasNoDeployment() {
        com.moddingfromamod.config.ModdySettings.overridesForTest(java.util.List.of());
        JsonObject o = new JsonObject();
        ActionLog.stampDeployment(o);
        assertFalse(o.has("deployment"), "the public mod's records name no deployment");
    }

    @Test
    void thePropertyWinsThenTheEnvironment() {
        assertEquals("server-1", ActionLog.deploymentOf("server-1", "home"));
        assertEquals("home", ActionLog.deploymentOf(null, "home"));
        assertEquals("home", ActionLog.deploymentOf("  ", " home "));
        assertNull(ActionLog.deploymentOf(null, ""), "no deployment is NOT a deployment called 'dev'");
    }

    @Test
    void aRecordIsStampedFromTheProperty() {
        String was = System.getProperty(KEY);
        try {
            System.setProperty(KEY, "server-9");
            JsonObject o = new JsonObject();
            o.addProperty("kind", "wish");
            ActionLog.stampDeployment(o);
            assertEquals("server-9", o.get("deployment").getAsString());
        } finally {
            if (was == null) System.clearProperty(KEY); else System.setProperty(KEY, was);
        }
    }

    @Test
    void aRecordThatAlreadyNamesOneKeepsIt() {
        String was = System.getProperty(KEY);
        try {
            System.setProperty(KEY, "server-9");
            JsonObject o = new JsonObject();
            o.addProperty("deployment", "home");
            ActionLog.stampDeployment(o);
            assertEquals("home", o.get("deployment").getAsString());
        } finally {
            if (was == null) System.clearProperty(KEY); else System.setProperty(KEY, was);
        }
    }

    @Test
    void noDeploymentLeavesTheFieldOut() {
        String was = System.getProperty(KEY);
        try {
            System.clearProperty(KEY);
            JsonObject o = new JsonObject();
            ActionLog.stampDeployment(o);
            // the environment of a test run may carry MODDY_DEPLOYMENT; only assert when it does not
            if (System.getenv("MODDY_DEPLOYMENT") == null) {
                assertFalse(o.has("deployment"));
            }
        } finally {
            if (was != null) System.setProperty(KEY, was);
        }
    }
}
