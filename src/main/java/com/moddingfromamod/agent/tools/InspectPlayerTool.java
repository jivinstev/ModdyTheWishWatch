package com.moddingfromamod.agent.tools;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.player.NeverDie;
import com.moddingfromamod.player.PlayerPowers;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * <b>What is the child RIGHT NOW</b> — his powers, how strong each one is, and his size.
 *
 * <p>The missing member of a set every other class already has: {@code inspect_build},
 * {@code inspect_creature}, {@code inspect_item}. Player effects had none, and it cost exactly what
 * you would predict.
 *
 * <p><b>Measured, class 5's first A/B.</b> Asked <i>"make me even stronger, more more more!"</i>:
 *
 * <pre>
 *   owner     strength 5 -> 21, 5 -> 10, 5 -> 10      (3 of 3)
 *   stranger  strength 5 -> 5,  5 -> 5,  5 -> 5       (0 of 3)
 * </pre>
 *
 * <p>The stranger did not ignore the wish — it added {@code jump_boost}, {@code speed} and
 * {@code slow_falling}. It could not see how strong he already was, so "stronger" had no baseline
 * to exceed and it reached for breadth instead. The owner knew because it had granted the power
 * itself, that session.
 *
 * <p>Same shape as, where {@code inspect_creature} never reported colour and a second worker
 * repainted the thing it was asked to edit. One missing field, one class-wide gap.
 *
 * <p><b>Why a tool rather than always-on context:</b> the handover brief is read on every wish in
 * every class, and a section about the child's powers would be dead weight in almost all of them.
 * Measured across the recorded matrices, agents reach inspection tools readily on their own —
 * {@code inspect_build} 31 times in the buildings cells, {@code inspect_item} 8 in the items cells —
 * so the habit exists and only the tool was missing.
 */
public final class InspectPlayerTool implements McpTool {

    @Override
    public String name() {
        return "inspect_player";
    }

    @Override
    public String description() {
        return "See what the CHILD is right now — every power he has, HOW STRONG each one is, and "
                + "his size. Call this BEFORE changing him, the same way you inspect_build before "
                + "changing a building. THIS is what makes \"make me even stronger\", \"make it "
                + "last longer\" and \"even bigger\" raise the power he ALREADY HAS instead of "
                + "piling a new one on top: without it you cannot see what 'stronger' has to beat, "
                + "and a child who asked for more of one thing gets a handful of different things.";
    }

    /**
     * The highest level {@code modify_player} can put this effect at, or 0 if it does not grant it.
     *
     * <p>Derived from {@link PlayerPowers} rather than written down, so a power whose base level is
     * retuned does not leave a stale number here — a wrong ceiling is worse than none, because it
     * would send the agent to {@code run_command} for a raise it could have made, or stop it going
     * there for one it could not.
     */
    private static int modifyPlayerCeiling(String effectId) {
        int best = 0;
        for (String name : PlayerPowers.names()) {
            PlayerPowers.Spec spec = PlayerPowers.find(name);
            if (spec == null) {
                continue;
            }
            for (PlayerPowers.Buff b : spec.buffs()) {
                if (b.effectId().equals(effectId)) {
                    best = Math.max(best, b.level() + PlayerPowers.Level.SUPER.bonus() + 1);
                }
            }
        }
        return best;
    }

    @Override
    public JsonObject inputSchema() {
        return Schemas.object();
    }

    @Override
    public ToolResult call(JsonObject args) {
        try {
            return AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                ServerPlayer p = server == null ? null
                        : com.moddingfromamod.agent.ActingChild.person(server);
                if (p == null) {
                    return ToolResult.noPlayerOnline(com.moddingfromamod.agent.ActingChild.whyNobody(server, name(),
                            "Nobody is in the world, so there is nobody to look at."));
                }
                StringBuilder sb = new StringBuilder("The child right now:\n");

                List<String> powers = new ArrayList<>();
                for (var eff : p.getActiveEffects()) {
                    String id = String.valueOf(
                            BuiltInRegistries.MOB_EFFECT.getKey(eff.getEffect().value()));
                    // LEVEL IS THE WHOLE POINT of this tool, so it leads — and it is reported the
                    // way a child counts (I, II, III => 1, 2, 3), not as the internal amplifier,
                    // which is one lower and would make every "make it stronger" off by one.
                    int shown = eff.getAmplifier() + 1;
                    int ceiling = modifyPlayerCeiling(id);
                    powers.add("  - " + id + "  level " + shown
                            + (eff.isInfiniteDuration() ? "  (forever)"
                               : "  (" + (eff.getDuration() / 20) + "s left)")
                            // THE HALF THAT MAKES THE NUMBER USEFUL. modify_player's levels are a
                            // three-rung named ladder, so for a power already at its top rung
                            // "give it again, stronger" produces the level he is on and nothing
                            // changes. Saying so here is the difference between the agent reaching
                            // for run_command and the agent reporting a raise it did not make.
                            + (ceiling > 0 && shown >= ceiling
                               ? "  <- ALREADY at modify_player's highest ('super'); to go above "
                                 + "this use run_command: /effect give @s " + id
                                 + " infinite <level minus 1> true"
                               : ""));
                }
                if (powers.isEmpty()) {
                    sb.append("  (no powers at all — this is the first one)\n");
                } else {
                    sb.append(String.join("\n", powers)).append('\n');
                }

                double scale = p.getAttributeValue(Attributes.SCALE);
                sb.append("  size: ").append(String.format("%.2f", scale))
                  .append(scale > 1.05 ? "  (bigger than normal)"
                          : scale < 0.95 ? "  (smaller than normal)" : "  (normal)")
                  .append('\n');
                if (NeverDie.isOn(p)) {
                    sb.append("  he cannot die (never_die is on)\n");
                }
                sb.append("\nTo make one of these STRONGER, give back the SAME power at a "
                        + "HIGHER NUMBER than the one printed above — not a different power. A "
                        + "power with no note beside it can be raised with modify_player; one "
                        + "marked ALREADY needs run_command, as shown.");
                return ToolResult.ok(sb.toString());
            });
        } catch (RuntimeException e) {
            return ToolResult.error("Could not look at the child: " + e);
        }
    }
}
