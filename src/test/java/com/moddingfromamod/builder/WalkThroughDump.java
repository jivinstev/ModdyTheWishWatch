package com.moddingfromamod.builder;

import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

/**
 * Prints a building's layers with where a walk reaches, for a person asking why a room was flagged:
 * {@code ./gradlew test --tests '*WalkThroughDump' with WALKDUMP=<plan.json>[:yFrom:yTo] in the environment}. Does nothing
 * without the property, so it is never part of a gate.
 */
class WalkThroughDump {

    @Test
    void dump() throws Exception {
        String arg = System.getenv().getOrDefault("WALKDUMP", "");
        if (arg.isBlank()) {
            return;
        }
        String[] p = arg.split(":");
        BuildPlan plan = BuildPlan.fromJson(JsonParser.parseString(Files.readString(Path.of(p[0]))).getAsJsonObject());
        WalkThrough w = WalkThrough.plan(plan);
        int from = p.length > 1 ? Integer.parseInt(p[1]) : -2;
        int to = p.length > 2 ? Integer.parseInt(p[2]) : 6;
        StringBuilder sb = new StringBuilder();
        for (WalkThrough.Room r : w.report().rooms()) {
            sb.append(r).append('\n');
        }
        for (int y = from; y <= to; y++) {
            sb.append(w.layer(y));
        }
        Files.writeString(Path.of("build/walkthrough/dump.txt"), sb.toString());
    }
}
