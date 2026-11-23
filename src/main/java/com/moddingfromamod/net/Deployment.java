package com.moddingfromamod.net;

import com.moddingfromamod.ModdingFromAModMod;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * <b>What shape of server is this, and who can actually join it — said once, at boot, in one line.</b>
 *
 * <p>This class is the whole 's "guard", and the
 * shape of it is the finding. Full reasoning, with the three supported deployments and the fourth
 * that cannot work: <b>{@code docs/DEPLOYMENTS.md}</b>.
 *
 * <h2>It is a REPORT, not a switch — and that is the decision</h2>
 *
 * <p> was framed as <i>"how do we decide whether to enable optional payloads"</i>, and four
 * options were costed for it (a {@link ModList} allowlist, reflection over NeoForge's private
 * payload registry, an installer opt-in, and a combination). Every one of them pays for the decision
 * by introducing a <b>second behavioural mode</b>, and the mode lands on the richest deployment:
 * a switch keyed on "is Create loaded" runs the well-gated vanilla-compat route on the bare server
 * and the other route on the Create server. That is §0.5's governing invariant inverted — instead of
 * one route with a divergent VIEW, you get two routes selected by which mods happen to be installed.
 *
 * <p>So the question was split in two, and only one half needed answering:
 *
 * <ol>
 *   <li><b>Are our payloads registered optional?</b> Always, unconditionally
 *       ({@link ModNetwork#optionalPayloads()} now defaults ON). It is the only thing that lets a
 *       stock client in, and it costs a modded peer nothing —
 *       {@code NetworkComponentNegotiator.buildDisabledOptionalComponents} drops an optional
 *       component only when the OTHER side lacks it, so two peers that both have the mod negotiate
 *       identically. There is nothing to detect.</li>
 *   <li><b>Can a stock client actually join THIS server?</b> A deployment fact, not a mod-detection
 *       problem, and it wants a sentence in the log rather than a branch in the code.</li>
 * </ol>
 *
 * <h2>Why {@link ModList} and not reflection</h2>
 *
 * <p>The precise answer to (2) is "does any other loaded mod register a REQUIRED payload", and it is
 * exactly computable — {@code LiteProbe} computes it. It needs
 * {@code NetworkRegistry.PAYLOAD_REGISTRATIONS}, which is {@code private static} holding
 * {@code @ApiStatus.Internal} values, so a NeoForge release that moves the field turns a precise
 * answer into a silent one. {@code LiteProbe}'s own javadoc already draws that line: <i>a probe may
 * do this; shipping code may not</i>. This class therefore answers the coarser question with
 * supported API, and the coarseness is in the harmless direction: an unrecognised mod id makes it
 * decline to promise, never wrongly promise. The precise per-mod census stays where it belongs, in
 * the probe, and the boot line points at it.
 *
 * <p>⚠ <b>The allowlist is about PROVENANCE, not about ids we like.</b> A library that ships INSIDE
 * our own jar via JarJar appears in {@link ModList} as a separate mod, so an allowlist that failed
 * to name it would report "other mods are loaded" on the deployment that is most certainly
 * mod-alone. {@link #BUNDLED} is therefore deliberately populated ahead of need: {@code geckolib} is
 * behind {@code uses_geckolib}, which is <b>false today</b> — measured, a Gate B server's
 * {@link ModList} is exactly {@code [create, flywheel, minecraft, moddingfromamod, neoforge,
 * ponder]} — so flipping that one line in {@code gradle.properties} is all it would take.
 *
 * <p>The same provenance point arrives from the other side and is not hypothetical at all:
 * {@code flywheel} and {@code ponder} ride inside <i>Create's</i> jar, so installing one mod puts
 * three ids in the list. The report says all three, which is honest — any of them may register a
 * required channel, and measured on that server {@code ponder} registers 3 of the 117.
 */
public final class Deployment {

    private static final Logger LOG = LoggerFactory.getLogger("MODDINGFROMAMOD/deploy");

    /** Not mods in any sense a deployment cares about — the platform is always there. */
    public static final Set<String> PLATFORM = Set.of("minecraft", "neoforge");

    /**
     * Libraries that ride inside our own jar. They are our footprint, not a companion mod.
     * See the provenance warning in the class javadoc — this list is why config 1 and 2 classify
     * as {@link Shape#MOD_ALONE} at all.
     */
    public static final Set<String> BUNDLED = Set.of("geckolib");

    /** What this server is, as far as a joining client is concerned. */
    public enum Shape {
        /**
         * Our mod, its bundled libraries and the platform. <b>A stock client can join</b> — every
         * channel on the server is ours and every one of ours is optional.
         */
        MOD_ALONE,
        /**
         * Other mods are loaded. Each is free to register REQUIRED channels, and one required
         * channel anywhere refuses a channel-less client for everybody. <b>We will not promise a
         * stock client can join</b>; a modded client still can, and that is deployment 3.
         */
        WITH_COMPANIONS
    }

    /**
     * @param shape      what the server is
     * @param companions the loaded mod ids that are neither ours, bundled, nor the platform —
     *                   sorted, so the boot line is stable run to run
     */
    public record Verdict(Shape shape, List<String> companions) {
        public boolean stockClientWelcome() {
            return shape == Shape.MOD_ALONE;
        }
    }

    private Deployment() {
    }

    /**
     * The pure half: classify a set of loaded mod ids. Takes a collection rather than reading
     * {@link ModList} so a unit test can drive it with no game — including the shapes we cannot
     * boot here, such as a server with a mod nobody has written yet.
     */
    public static Verdict classify(Collection<String> loadedModIds) {
        return classify(loadedModIds, Set.of());
    }

    /**
     * As {@link #classify(Collection)}, where {@code builtOnUs} are the loaded mods that REQUIRE this one: an extension
     * of this mod (a level, a server's own mod) answers for its channels with ours, so it is ours, never a companion
     * (our servers load the house mod beside the core, and it registers no channel of its own).
     */
    public static Verdict classify(Collection<String> loadedModIds, Collection<String> builtOnUs) {
        Set<String> ours = new java.util.HashSet<>();
        for (String id : builtOnUs) {
            if (id != null) {
                ours.add(id.trim().toLowerCase(java.util.Locale.ROOT));
            }
        }
        Set<String> companions = new TreeSet<>();
        for (String id : loadedModIds) {
            if (id == null || id.isBlank()) {
                continue;
            }
            String norm = id.trim().toLowerCase(java.util.Locale.ROOT);
            if (norm.equals(ModdingFromAModMod.MODID) || PLATFORM.contains(norm)
                    || BUNDLED.contains(norm) || ours.contains(norm)) {
                continue;
            }
            companions.add(norm);
        }
        return new Verdict(companions.isEmpty() ? Shape.MOD_ALONE : Shape.WITH_COMPANIONS,
                List.copyOf(companions));
    }

    /** Classify the server this code is running on. */
    public static Verdict here() {
        List<String> ids = new ArrayList<>();
        List<String> builtOnUs = new ArrayList<>();
        ModList.get().forEachModContainer((id, c) -> {
            ids.add(id);
            if (c.getModInfo().getDependencies().stream().anyMatch(d -> ModdingFromAModMod.MODID.equals(d.getModId())
                    && d.getType() == net.neoforged.neoforgespi.language.IModInfo.DependencyType.REQUIRED)) {
                builtOnUs.add(id);
            }
        });
        return classify(ids, builtOnUs);
    }

    /**
     * The boot line. One sentence, naming the shape and the reason, because the failure this exists
     * to prevent is an admin assembling deployment 4 (Moddy + Create on the server, a stock client
     * at the door) and getting a bare "connection lost" with nothing naming the mod that closed it.
     */
    public static String line(Verdict v) {
        if (v.stockClientWelcome()) {
            return "this server runs our mod alone, so a STOCK (non-NeoForge) client can join it;"
                    + " a client that has the mod gets the richer view of the same wish";
        }
        return "other mods are loaded (" + String.join(", ", v.companions()) + "), so a STOCK client"
                + " may be refused at CONFIGURATION -- any one of them may register a required"
                + " channel, and one required channel anywhere refuses a channel-less client."
                + " Clients that have this mod are unaffected."
                + " For the exact per-mod census run tools/lite-server.sh and read probe.tsv";
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        Verdict v = here();
        LOG.info("DEPLOYMENT {}: {}", v.shape(), line(v));
        if (!ModNetwork.optionalPayloads()) {
            LOG.warn("DEPLOYMENT {}=off -- our payloads are REQUIRED on this run, so a stock client"
                    + " is refused whatever the line above says. Only a gate should set this.",
                    ModNetwork.OPTIONAL_PROPERTY);
        }
    }
}
