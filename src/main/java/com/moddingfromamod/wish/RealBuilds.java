package com.moddingfromamod.wish;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.moddingfromamod.builder.BuildPlan;

/**
 * <b>Buildings the agent really built, as a corpus.</b>
 *
 * <p>Every one of the 84 buildings cases in {@code wishes.jsonl} is a fixture — 65 from a
 * combinatorial sweep over the builder's own vocabulary, 19 from a feature inventory. None came
 * from an agent. Calibrating a gate on that is the exact mistake that made a creature gate catch
 * <b>0 of 14</b> real creatures on its first live run: the fixtures end up built to the thresholds
 * instead of the thresholds being built to reality.
 *
 * <p>So these 19 are the real thing, mined by {@code tools/mine-real-builds.py} out of the worlds
 * they were built in — Spikor's Castle, the crystal lighthouses, a battle arena, a floating wizard
 * castle, a sky airplane. They exist as a corpus because the obvious source does not work: all 37
 * {@code build_structure} calls in the action log are clipped to 600 characters, cut mid-way
 * through their shape arrays. {@code BuildRegistry} writes the plan whole into
 * {@code <world>/wishes/buildings.json}, so that is where they came from.
 *
 * <p><b>These are post-clamp.</b> The file is written by {@link BuildPlan#toJson()}, after the
 * compact constructor has already clamped every dimension and offset, so they are ground truth for
 * geometry and say nothing at all about whether a cap rewrote the wish on the way in.
 *
 * <p>Held apart from {@link WishCorpus} on purpose. These are real buildings — one is 192,000
 * blocks — and the corpus stress test puts every case it loads into a live world, twice. Their
 * value is in the pure geometry oracle, which needs no world and runs the whole set in
 * milliseconds, so they gate on the unit suite rather than slowing the game-test suite by minutes.
 */
public final class RealBuilds {

    public static final String RESOURCE = "/corpus/real-builds.jsonl";

    private RealBuilds() {
    }

    /** One real build, with enough provenance to go back to where it came from. */
    public record Real(String id, String name, String worlds, String savedAt, BuildPlan plan) {
    }

    /** Every real build in the bundled corpus. Never throws; an empty result is the caller's cue. */
    public static List<Real> load() {
        String text;
        try (InputStream in = RealBuilds.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return List.of();
            }
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return List.of();
        }
        List<Real> out = new ArrayList<>();
        for (WishCorpus.Case c : WishCorpus.parse(text)) {
            JsonObject planJson = new JsonObject();
            planJson.addProperty("id", c.id());
            planJson.addProperty("name", c.id());
            if (c.spec().has("shapes")) {
                planJson.add("shapes", c.spec().get("shapes"));
            }
            BuildPlan plan = BuildPlan.fromJson(planJson);
            if (plan != null) {
                out.add(new Real(c.id(), c.id(), "", "", plan));
            }
        }
        return List.copyOf(out);
    }
}
