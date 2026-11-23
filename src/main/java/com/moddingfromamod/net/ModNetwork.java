package com.moddingfromamod.net;

import com.moddingfromamod.ModdingFromAModMod;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Network wiring. Registered on the MOD event bus (payload types are declared once at startup).
 */
public final class ModNetwork {

    /** Bump when the payload shape changes incompatibly. */
    private static final String PROTOCOL_VERSION = "1";

    /**
     * <b>{@code -Dmoddingfromamod.optionalpayloads=off} — the A/B seam, and nothing else.</b>
     *
     * <p><b>Optional is now unconditional.</b> Every payload this mod registers is optional on
     * every run; this property exists only so a gate can turn it back off and watch a stock client
     * be refused. Absent means ON. See {@link Deployment} for why it is not conditional on anything
     * — the short version is that a flag whose value changes the CODE PATH creates a deployment
     * matrix, and the three shapes we support are then covered by two different routes with one
     * set of gates between them.
     *
     * <p>Measured, not reasoned. A stock 1.21.1 client asking to join this server is refused during
     * CONFIGURATION with <i>"You are trying to connect to a server that is running NeoForge, but
     * you are not"</i> — and the refusal is not about NeoForge being present. It comes from
     * {@code NetworkRegistry.initializeOtherConnection}, which negotiates every registered payload
     * against an <b>empty</b> client channel list:
     * {@code NetworkComponentNegotiator} drops the server's OPTIONAL components when the other
     * side lacks them, and then fails outright if anything non-optional is left. So the gate is
     * exactly <i>"is every payload on this server optional"</i>, and one required channel from any
     * mod closes it for everybody.
     *
     * <p><b>What it costs a MODDED client: nothing, and that is the negative the flip rests on.</b>
     * {@code buildDisabledOptionalComponents} only drops an optional component when the other side
     * does not have it. Two NeoForge peers both have all of them, so they match, versions are
     * compared exactly as before, and the negotiated setup is identical. Optional is not "weaker",
     * it is "absent rather than fatal when the peer cannot speak it" — and every send site already
     * asks {@link Send#canReceive} first.
     *
     * <p>⚠ That paragraph used to be a READING of NeoForge's source and is now MEASURED on both
     * modded shapes — see {@code GateB:everyChannelThisServerRegistersIsOptional} and
     * {@code docs/DEPLOYMENTS.md}. It is the load-bearing claim for the whole flip, so it does not
     * get to stay a Tier 2 statement.
     */
    public static final String OPTIONAL_PROPERTY = ModdingFromAModMod.MODID + ".optionalpayloads";

    private ModNetwork() {
    }

    /**
     * Whether this run registers its payloads as optional. <b>Absent = ON</b>; only an explicit
     * {@code off}/{@code false} turns it back into today-before-#547 behaviour, and only a gate
     * should ever do that.
     */
    public static boolean optionalPayloads() {
        String v = System.getProperty(OPTIONAL_PROPERTY, "").trim();
        return !("off".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v));
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        if (optionalPayloads()) {
            registrar = registrar.optional();
        }
        // the modded client's own channels and a proxy's (on our servers the private plug-in's:
        // the syncs, the camera, the watch list, the service-client challenge, the player's real version).
        // None in the public single-player mod, which has no client code (D17).
        ModdedClient.registerPayloads(registrar);
        // another client's own channels (on our servers the private Bedrock plug-in's: moddy:bedrock_id,
        // moddy:bedrock_ui, moddy:ride_marks). None in the public mod.
        PlayerClient.registerPayloads(registrar);
    }
}
