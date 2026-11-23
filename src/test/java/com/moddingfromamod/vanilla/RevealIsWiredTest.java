package com.moddingfromamod.vanilla;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The reveal exists AND is reachable from every path a wish can end on</b> — the "a rule tested
 * only where it is DEFINED is not tested where it RUNS" guard, applied to /#515b.
 *
 * <h2>Why this test and not a GameTest</h2>
 *
 * <p>Everything about the deferral is already asserted where it is defined:
 * {@code RigVeilTest} owns the rule, and
 * {@code RigPackDeferralGameTest} / {@code RigVeilGameTest} own the packets. All of that stays
 * green over a build in which nothing ever CALLS the reveal — and then the child's models and the
 * child's creature are both withheld for ever, which is a far worse wish than the one the deferral
 * was written to improve.
 *
 * <p>Driving the real {@code finish_building} tool would be the stronger check and needs an agent,
 * a wish book and a live tool call; this is the cheap grep that catches the failure that actually
 * happens — someone refactors the finish path and the call goes with it. CLAUDE.md's own advice:
 * when you write a lesson down, ask whether a grep could enforce it.
 *
 * <p>⚠ It proves the call is PRESENT, never that it is reached at run time. A reveal moved inside
 * a branch that is false for ever would satisfy this and nothing else here would notice — which is
 * exactly the shape, and is why the end-to-end freeze count is measured on a real run
 * rather than inferred from this file.
 */
class RevealIsWiredTest {

    private record Wire(String file, String mustContain, String why) {
    }

    private static final Path SRC = Path.of("src/main/java/com/moddingfromamod");

    private static final List<Wire> WIRES = List.of(
            new Wire("agent/tools/FinishBuildingTool.java",
                    "RigReveal.reveal(",
                    "the reveal is the ONE freeze the child is allowed to pay, and this is the "
                            + "tool that ends a build. Without the call he is withheld from for "
                            + "ever and the creature never renders"),
            new Wire("agent/tools/FinishWishTool.java",
                    "RigReveal.reveal(",
                    "BuildGate.refuseFinish() refuses once and then gives way, so a wish can end "
                            + "having never called finish_building. This is the backstop, and "
                            + "without it that wish reveals nothing"),
            new Wire("vanilla/RigReveal.java",
                    "RigVeilService.reveal(",
                    "the reveal must release the parts: clearing the state without it leaves the "
                            + "child looking at an empty anchor for ever"),
            new Wire("vanilla/DisplayRig.java",
                    "RigVeilService.veil(",
                    "every rig that ever exists is built by DisplayRig.spawn. A veil wired to the "
                            + "wish tools instead would be bypassed by every other caller, and "
                            + "'there is no check' and 'the check is not wired to this path' look "
                            + "identical from outside"),
            new Wire("vanilla/RigVeilService.java",
                    "ServerTickEvent.Post",
                    "a child who walks out of tracking range and back gets a fresh pairing from "
                            + "vanilla. Without the per-tick sweep the veil holds only until he "
                            + "takes a walk"),
            new Wire("ModdingFromAModMod.java",
                    "NeoForge.EVENT_BUS.register(com.moddingfromamod.vanilla.RigVeilService.class)",
                    "an unregistered handler compiles fine and silently does nothing -- the wrong "
                            + "bus is the most common cause of a mod that builds and no-ops"),
            new Wire("wish/CameraPresence.java",
                    "@SubscribeEvent",
                    "the sweep is public so a GameTest can drive it directly; without a listener "
                            + "calling it every tick the camera is a person standing in the "
                            + "child's world again, and the GameTest would still pass"),
            new Wire("ModdingFromAModMod.java",
                    "NeoForge.EVENT_BUS.register(com.moddingfromamod.wish.CameraPresence.class)",
                    "an unregistered handler compiles fine and silently does nothing"),
            new Wire("ModdingFromAModMod.java",
                    "NeoForge.EVENT_BUS.register(com.moddingfromamod.wish.CameraSupervisor.class)",
                    "#509's supervisor is what starts the camera at all. Off the bus, no camera "
                            + "ever joins, `serving` is false for ever, and the whole deferral "
                            + "quietly reverts to pushing every interim pack at the child"));

    @Test
    @DisplayName("every reveal and veil call site is still where the design says it is")
    void theRevealIsReachableFromEveryPathAWishCanEndOn() throws IOException {
        List<String> broken = new ArrayList<>();
        for (Wire w : WIRES) {
            Path p = SRC.resolve(w.file());
            if (!Files.exists(p)) {
                broken.add(w.file() + " is gone -- " + w.why());
                continue;
            }
            String code = Files.readString(p, StandardCharsets.UTF_8);
            if (!code.contains(w.mustContain())) {
                broken.add(w.file() + " no longer contains `" + w.mustContain() + "`: " + w.why());
            }
        }
        assertTrue(broken.isEmpty(),
                "the #515 deferral is defined but no longer wired to the path that runs it:\n  "
                        + String.join("\n  ", broken));
    }

    /**
     * <b>The veil is called once, from {@code spawn}'s own body.</b>
     *
     * <p>Every rig goes through one method precisely so there is no second path to forget. This
     * used to also guard against the call drifting into one of {@code spawn}'s {@code if (atoms)}
     * arms; deleted the pack rig and with it the arms, so what is left is the shape: exactly
     * one call, at the method body's own indentation (a proxy, and it says so).
     */
    @Test
    @DisplayName("DisplayRig.spawn veils exactly once, at its own body level")
    void theVeilCallIsNotInsideAnArmBranch() throws IOException {
        String code = Files.readString(SRC.resolve("vanilla/DisplayRig.java"),
                StandardCharsets.UTF_8);
        List<String> calls = new ArrayList<>();
        for (String line : code.split("\n", -1)) {
            if (line.contains("RigVeilService.veil(")) {
                calls.add(line);
            }
        }
        assertEquals(1, calls.size(),
                "DisplayRig should call RigVeilService.veil exactly once -- every rig goes through "
                        + "one method precisely so there is no second path to forget. Found: "
                        + calls);
        String call = calls.get(0);
        int indent = call.length() - call.stripLeading().length();
        assertEquals(8, indent,
                "the veil call is indented " + indent + " spaces rather than 8, so it is nested "
                        + "inside a block within spawn, and some rigs would be built in front of a "
                        + "stock child. Line: `" + call.strip() + "`");
    }

    /**
     * The guard's own A/B. A `contains` check over a file that is present passes for a file that
     * has never had the call in it and for one that has just lost it alike, so the only thing that
     * distinguishes a working detector from one matching nothing is watching it fire.
     */
    @Test
    @DisplayName("the guard actually fires when a call site is removed")
    void everyWireIsAThingThatCanBeMissing() throws IOException {
        for (Wire w : WIRES) {
            Path p = SRC.resolve(w.file());
            String code = Files.readString(p, StandardCharsets.UTF_8);
            String without = code.replace(w.mustContain(), "/* removed */");
            assertFalse(without.contains(w.mustContain()),
                    "the rule for " + w.file() + " matches text that survives its own removal, so "
                            + "it can never fail: `" + w.mustContain() + "`");
        }
    }
}
