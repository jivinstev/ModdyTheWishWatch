package com.moddingfromamod.provenance;

import com.google.gson.JsonObject;

/**
 * <b>Which model made this, and on which build</b> (the model-stamp half of a wish's provenance).
 *
 * <p>One grant of one artifact: the model and effort the wish was granted at, the mod's version,
 * Minecraft's version, the world, and when. It rides with the artifact forever — in the local
 * {@link StampLedger} on every server, in a Mod Repo's {@code manifest.json} where one is
 * configured, and into the library when the artifact is promoted — which is the difference from
 * The {@code spend.wish}: a spend record is operational and ages out with the logs.
 *
 * <p>⚠ <b>The clock starts when this is added.</b> An artifact granted before it has no stamp and
 * gets none: under this repo's rule <i>never fill a field you had to guess</i>, an absent stamp
 * means "unattributable", and nothing back-fills it from today's configuration.
 *
 * <p>{@code model} is the REQUESTED spec ({@code opus}, {@code muse:…}), the same value the
 * {@code settled} record carries, because a foreign endpoint chooses which model id it echoes.
 * Carries no player id and no words — a stamp is not personal data.
 *
 * <p>Pure: {@link #current()} is the one method that reads the running game, and it degrades to
 * empty strings rather than throwing where there is no game (a unit test).
 */
public record ModelStamp(String model, String effort, String mod, String mc, String world, long at) {

    public ModelStamp {
        model = model == null ? "" : model;
        effort = effort == null ? "" : effort;
        mod = mod == null ? "" : mod;
        mc = mc == null ? "" : mc;
        world = world == null ? "" : world;
    }

    /** The stamp for a grant happening NOW, on this server. */
    public static ModelStamp current() {
        // on the Muse subscription path the shim picks the arm per worker launch, so the
        // worker making THIS call says which model served it; elsewhere this is the requested spec.
        return new ModelStamp(safe(ModelStamp::servedOrRequested),
                safe(com.moddingfromamod.agent.AgentSession::resolvedEffort),
                safe(ModelStamp::modVersion), safe(ModelStamp::mcVersion),
                safe(com.moddingfromamod.runtime.WorldPaths::worldName),
                System.currentTimeMillis());
    }

    /** The model the calling worker was served by, else the server's requested spec. */
    static String servedOrRequested() {
        return com.moddingfromamod.agent.MuseShimState.servedSpecForCurrentWorker()
                .orElseGet(com.moddingfromamod.agent.AgentSession::resolvedModel);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("model", model);
        o.addProperty("effort", effort);
        o.addProperty("mod", mod);
        o.addProperty("mc", mc);
        o.addProperty("world", world);
        o.addProperty("at", at);
        return o;
    }

    public static ModelStamp fromJson(JsonObject o) {
        return new ModelStamp(str(o, "model"), str(o, "effort"), str(o, "mod"), str(o, "mc"),
                str(o, "world"), o.has("at") && o.get("at").isJsonPrimitive()
                        ? o.get("at").getAsLong() : 0L);
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    private static String modVersion() {
        return net.neoforged.fml.ModList.get()
                .getModContainerById(com.moddingfromamod.ModdingFromAModMod.MODID)
                .map(c -> c.getModInfo().getVersion().toString()).orElse("");
    }

    private static String mcVersion() {
        return net.minecraft.SharedConstants.getCurrentVersion().name();
    }

    private static String safe(java.util.function.Supplier<String> s) {
        try {
            String v = s.get();
            return v == null ? "" : v;
        } catch (Throwable t) {       // no game, no mod list, no world: an empty field, never a throw
            return "";
        }
    }
}
