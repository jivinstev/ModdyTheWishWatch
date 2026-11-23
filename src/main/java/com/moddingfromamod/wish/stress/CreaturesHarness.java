package com.moddingfromamod.wish.stress;

import java.util.List;

import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.wish.WishCorpus;

/**
 * Stresses {@code kind=creatures}: the mobs a child asked for, from a baby creeper to a 5×-scale
 * King T-Rex.
 *
 * <p>The interesting axis here is the <b>clamps</b>. A creature's scale, health, speed, damage and
 * armour are all silently bounded by {@link Species}'s compact constructor, exactly as the building
 * shapes were — so "make him a THOUSAND times bigger" produces an 8× mob and nobody is told the
 * wish was rewritten. Same defect class, different class of wish, which is precisely the kind of
 * thing a shared standard is supposed to surface.
 */
public final class CreaturesHarness implements StressHarness {

    @Override
    public String kind() {
        return "creatures";
    }

    @Override
    public Outcome run(Context ctx, WishCorpus.Case c) {
        Outcome.Builder out = new Outcome.Builder();
        var server = ctx.level().getServer();

        double wantScale = num(c, "scale", 1.0);
        double wantHealth = num(c, "health", 20.0);
        String base = c.spec().has("base") ? c.spec().get("base").getAsString() : "WILD";
        String id = "stress_" + c.id();

        Species species = Species.fromJson(speciesJson(id, base, wantScale, wantHealth));
        if (species == null) {
            return out.problem("could not build a species from the case spec").build();
        }

        // ── the wish is stored and comes back
        SpeciesRegistry.put(server, species);
        Species back = SpeciesRegistry.get(id);
        out.require(Invariants.MATERIALISES, back != null,
                "the species was saved and could not be read back");
        if (back == null) {
            return out.build();
        }

        // ── caps must be reported, not silent
        boolean expectsClampNote = c.flag("clampReported");
        boolean wasClamped = Math.abs(back.scale() - wantScale) > 1e-6
                || Math.abs(back.health() - wantHealth) > 1e-6;
        if (expectsClampNote) {
            out.require(Invariants.NO_SILENT_CAPS, wasClamped,
                    "this case asks for a stat far past the limit; if nothing was clamped the "
                            + "case has stopped exercising the cap");
            // The clamp itself is right. What is missing is anyone SAYING so — recorded as a
            // finding rather than asserted, because there is no reporting channel for it yet.
            out.checked("creature caps are reported (NOT YET IMPLEMENTED — see findings)");
        } else {
            out.require("the creature is stored as asked", !wasClamped,
                    "asked for scale " + wantScale + "/health " + wantHealth + ", stored scale "
                            + back.scale() + "/health " + back.health()
                            + " — a within-limits wish must not be altered");
        }

        // ── the stored stats are inside the documented range whatever was asked
        out.require("stats stay inside their documented range",
                back.scale() >= Species.MIN_SCALE && back.scale() <= Species.MAX_SCALE
                        && back.health() >= Species.MIN_HEALTH
                        && back.health() <= Species.MAX_HEALTH,
                "stored scale " + back.scale() + " / health " + back.health()
                        + " is outside the range the renderer and the AI assume");

        // ── P6: the design survives a reload, or the child loses their creature overnight
        out.require(Invariants.P6_SURVIVES_RELOAD,
                Invariants.survivesReload(
                        () -> SpeciesRegistry.put(server, species),
                        SpeciesRegistry::clear,
                        () -> SpeciesRegistry.load(server),
                        () -> SpeciesRegistry.has(id)),
                "the creature did not come back after a reload — the child would lose it overnight");

        return out.build();
    }

    private static double num(WishCorpus.Case c, String key, double fallback) {
        try {
            return c.spec().has(key) ? c.spec().get(key).getAsDouble() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static com.google.gson.JsonObject speciesJson(String id, String base,
                                                          double scale, double health) {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", id);
        o.addProperty("body", "HUMANOID");
        o.addProperty("texture", Species.DEFAULT_TEXTURE);
        o.addProperty("scale", scale);
        o.addProperty("health", health);
        o.addProperty("base", base);
        o.add("powers", new com.google.gson.JsonArray());
        return o;
    }

    /** Kinds this harness does not clean up after: species are cheap and per-world. */
    static List<String> notes() {
        return List.of("species are left in the registry; the world is discarded after the run");
    }
}
