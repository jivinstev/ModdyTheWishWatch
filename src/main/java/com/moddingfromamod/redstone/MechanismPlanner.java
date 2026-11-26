package com.moddingfromamod.redstone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Puts a module and its controls together: routes a buried wire from each control to the module, checks every cell
 * against the world, and orders the cells so nothing fires while it is being built. Pure: the world arrives as a
 * {@link WorldView}.
 */
public final class MechanismPlanner {

    /** A control asked for: its kind and the ground block it stands on. */
    public record ControlAsk(String kind, RsPos ground, RsDir face, boolean toggle) {
        public ControlAsk(String kind, RsPos ground) {
            this(kind, ground, null, false);
        }

    }

    /** The signal the far end of a module's bridge needs at its port (3 wire beyond it, at least 1 left). */
    public static final int PORT_NEED = 9;

    private MechanismPlanner() {
    }

    public static MechanismPlan plan(WorldView world, Module module, List<ControlAsk> asks, Set<RsPos> foreign) {
        if (asks.isEmpty()) {
            return MechanismPlan.refused(module.kind(), "a mechanism needs at least one control");
        }
        Map<RsPos, RsCell> cells = new LinkedHashMap<>();
        for (RsCell c : module.cells()) {
            cells.put(c.pos(), c);
        }
        List<Control> controls = new ArrayList<>();
        for (ControlAsk ask : asks) {
            if (!world.conductor(ask.ground())) {
                return MechanismPlan.refused(module.kind(), "the " + ask.kind() + " needs solid ground under it at "
                        + ask.ground() + " (it is " + world.blockId(ask.ground()) + ")");
            }
            RsPos usePos = ask.face() == null ? ask.ground().up(1) : ask.ground().step(ask.face());
            if (world.kind(usePos) != WorldView.Kind.AIR) {
                return MechanismPlan.refused(module.kind(), "the place for the " + ask.kind() + " at "
                        + usePos + " is taken by " + world.blockId(usePos));
            }
            Control best = null;
            ChannelRouter.Route bestRoute = null;
            String lastWhy = "no route";
            for (RsDir d : towardFirst(ask.ground(), module.ports())) {
                Control c = ask.face() == null ? Control.floor(ask.kind(), ask.ground(), d, module.channelY(), null)
                        : Control.wall(ask.kind(), ask.ground(), ask.face(), d, module.channelY());
                if (c != null && ask.toggle()) {
                    c = Control.toggled(c, d);
                }
                if (c == null) {
                    return MechanismPlan.refused(module.kind(), "the " + ask.kind() + " at " + ask.ground().up(1)
                            + " stands too low for its wire to reach the mechanism; put it on ground level with it");
                }
                if (!controlFits(world, c, cells.keySet())) {
                    lastWhy = "the " + ask.kind() + "'s wire has no room to go down beside it";
                    continue;
                }
                Set<RsPos> own = new HashSet<>(cells.keySet());
                c.cells().forEach(x -> own.add(x.pos()));
                own.remove(c.port());
                ChannelRouter.Route r = ChannelRouter.route(world, module.channelY(), c.port(), c.strength(),
                        module.ports(), PORT_NEED, own, foreign);
                if (r.ok() && (bestRoute == null || r.length() < bestRoute.length())) {
                    best = c;
                    bestRoute = r;
                } else if (!r.ok()) {
                    lastWhy = r.why();
                }
            }
            if (best == null) {
                return MechanismPlan.refused(module.kind(), "the " + ask.kind() + " at " + ask.ground().up(1)
                        + ": " + lastWhy);
            }
            for (RsCell c : best.cells()) {
                cells.put(c.pos(), c);
            }
            for (RsCell c : bestRoute.cells()) {
                cells.putIfAbsent(c.pos(), c);
            }
            controls.add(best);
        }
        List<String> replaced = new ArrayList<>();
        for (RsCell c : cells.values()) {
            WorldView.Kind k = world.kind(c.pos());
            if (c.role() == RsCell.Role.CASING && keepsWhatIsThere(world, c)) {
                continue;
            }
            if (k == WorldView.Kind.LOCKED) {
                return MechanismPlan.refused(module.kind(), "it would have to replace " + world.blockId(c.pos())
                        + " at " + c.pos() + ", which is never touched");
            }
            if (k == WorldView.Kind.FLUID && c.role() != RsCell.Role.CASING) {
                return MechanismPlan.refused(module.kind(), "water or lava at " + c.pos() + " is in the way");
            }
            boolean keeps = c.role() == RsCell.Role.CASING && (k == WorldView.Kind.SOLID || k == WorldView.Kind.NATURAL);
            if (!keeps && (k == WorldView.Kind.SOLID || k == WorldView.Kind.OTHER)) {
                replaced.add(world.blockId(c.pos()) + " at " + c.pos());
            }
        }
        List<RsCell> ordered = new ArrayList<>(cells.values());
        ordered.removeIf(c -> c.role() == RsCell.Role.CASING && keepsWhatIsThere(world, c));
        ordered.sort(Comparator.comparingInt(MechanismPlanner::stage).thenComparingInt(c -> c.pos().y()));
        List<String> notes = new ArrayList<>(module.notes());
        return new MechanismPlan(module.kind(), ordered, module.expects(), controls, replaced, notes, null);
    }

    /** Placement order: pockets, supports and casing, plain parts, then components, then controls. */
    static int stage(RsCell c) {
        return switch (c.role()) {
            case CLEAR -> 0;
            case CASING -> 1;
            case PART -> c.block().contains("torch") ? 4 : 2;
            case CHANNEL -> 3;
            case CONTROL -> 5;
        };
    }

    /** A casing cell keeps any solid conducting block already there (natural, built, or locked like bedrock). */
    static boolean keepsWhatIsThere(WorldView world, RsCell c) {
        WorldView.Kind k = world.kind(c.pos());
        return k == WorldView.Kind.SOLID || k == WorldView.Kind.NATURAL
                || k == WorldView.Kind.LOCKED && world.conductor(c.pos());
    }

    /** The control's cells must land on air or natural ground (its own clear pockets included), never on ours. */
    static boolean controlFits(WorldView world, Control c, Set<RsPos> taken) {
        for (RsCell x : c.cells()) {
            if (taken.contains(x.pos())) {
                return false;
            }
            WorldView.Kind k = world.kind(x.pos());
            if (x.role() == RsCell.Role.CASING) {
                continue;
            }
            if (x.role() == RsCell.Role.CONTROL ? k != WorldView.Kind.AIR
                    : k != WorldView.Kind.AIR && k != WorldView.Kind.NATURAL) {
                return false;
            }
            // the control's own wire keeps clear of everything already planned, as the router's does: a switch
            // stood close to a door once ran its wire down beside the bridge's end, turning that end into a corner
            // that no longer powered the tower
            if (x.role() == RsCell.Role.CHANNEL) {
                for (RsDir d : RsDir.values()) {
                    RsPos side = x.pos().step(d);
                    if (taken.contains(side) || taken.contains(side.up(1)) || taken.contains(side.up(-1))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Directions to try for a control's drop, nearest the mechanism first. */
    static List<RsDir> towardFirst(RsPos from, List<RsPos> to) {
        RsPos t = to.get(0);
        List<RsDir> dirs = new ArrayList<>(List.of(RsDir.values()));
        dirs.sort(Comparator.comparingInt(d -> from.step(d).manhattan(new RsPos(t.x(), from.y(), t.z()))));
        return dirs;
    }
}
