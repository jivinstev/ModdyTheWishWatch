package com.moddingfromamod.wish.stress;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.datapack.DatapackStore;
import com.moddingfromamod.datapack.DatapackVerifier;
import com.moddingfromamod.wish.WishCorpus;

/**
 * Stresses {@code kind=datapacks}: the verification that did not exist.
 *
 * <p>Compiling CBScript needs Python and the compiler fork, which a headless gate cannot rely on —
 * so this harness does NOT compile. It exercises the half that was missing and is always available:
 * given a pack id, does the verifier tell the truth about whether it is loaded and whether its
 * functions are real?
 *
 * <p>That includes the negative direction, which matters more here than anywhere else: a verifier
 * that says "fine" about a pack that is not there would be worse than no verifier, because it
 * would convert an unchecked claim into a checked-looking one.
 */
public final class DatapacksHarness implements StressHarness {

    @Override
    public String kind() {
        return "datapacks";
    }

    @Override
    public Outcome run(Context ctx, WishCorpus.Case c) {
        Outcome.Builder out = new Outcome.Builder();
        var server = ctx.level().getServer();
        String id = c.spec().has("feature_id")
                ? c.spec().get("feature_id").getAsString() : "stress_" + c.id();

        boolean expectAbsent = c.flag("expectAbsent");

        List<String> entries = new ArrayList<>();
        if (c.spec().has("entries") && c.spec().get("entries").isJsonArray()) {
            c.spec().getAsJsonArray("entries").forEach(e -> entries.add(e.getAsString()));
        }

        var report = DatapackVerifier.verify(server, id, entries, false);

        if (expectAbsent) {
            // THE IMPORTANT DIRECTION. A verifier must not bless a pack that isn't there.
            out.require("the verifier refuses to bless a pack that is not loaded",
                    !report.healthy(),
                    "verify_datapack reported a pack that was never installed as healthy — a "
                            + "verifier that says yes to everything is worse than none, because it "
                            + "makes an unchecked wish look checked");
            out.require("an absent pack is described as not loaded",
                    report.describe().contains("NOT LOADED"),
                    "the report did not say the pack is missing: " + report.describe());
            return out.build();
        }

        out.require("a declared entry that is missing is reported",
                entries.isEmpty() || !report.missing().isEmpty() || report.healthy(),
                "the verifier neither found the declared functions nor reported them missing");
        out.require("the store and the verifier agree the pack exists",
                DatapackStore.exists(id) == report.loaded() || !DatapackStore.exists(id),
                "the store says the pack exists but the verifier cannot see it loaded");
        return out.build();
    }
}
