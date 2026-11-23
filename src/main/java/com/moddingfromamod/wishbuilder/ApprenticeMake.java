package com.moddingfromamod.wishbuilder;

import com.moddingfromamod.ui.PickPage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>An Apprentice pick, made the way a child's hands would make it</b>: every entry of the pick is PRESSED
 * on a real {@link BuilderFlow}, page by page, until the flow stands at its tune dialog. So an Apprentice make is a
 * Builder make by construction: the same allowlist checks, the same "two powers at most", the same part budget,
 * and the same spec the dialog's own Make writes. A press the menus refuse is an error that names it, never a make
 * with a piece quietly missing.
 *
 * <p>Pure. {@code ApprenticeCorpusTest} presses every make the matcher offers for the whole wish corpus.
 */
public final class ApprenticeMake {

    private ApprenticeMake() {
    }

    /** A flow standing at its tune dialog with every pick of {@code pick} pressed. */
    public static BuilderFlow pressed(BuilderAllowlist allow, LibraryPresets presets, Apprentice.Pick pick) {
        BuilderFlow f = new BuilderFlow(allow, presets);
        press(f, pick.kind());
        switch (pick.form()) {
            case "ready" -> {
                if (f.step() == BuilderFlow.Step.SUPPLY_CATEGORY && BuilderSupplies.found(pick.first("from"))) {
                    press(f, pick.first("from"));   //: an installed item found by name is pressed as itself
                } else if (f.step() == BuilderFlow.Step.SUPPLY_CATEGORY) {
                    // supplies open on the category, then the supply (none to press for a category of one).
                    BuilderSupplies.Supply s = BuilderSupplies.supply(pick.first("from"));
                    press(f, s == null ? "" : "supplies:" + s.category());
                    if (f.step() == BuilderFlow.Step.SUPPLY_WHAT) {
                        press(f, pick.first("from"));
                    }
                } else {
                    press(f, pick.first("from"));
                }
                if (f.step() == BuilderFlow.Step.BUILD_OUTSIDE) {   //: a building's type, then what goes outside
                    outside(f, pick);
                }
                if (f.step() == BuilderFlow.Step.PICT_BACKGROUND) {   //: a picture's subject, then what is behind it
                    press(f, pick.first("background").isEmpty() ? "background:" + BuilderPictures.DRAWN : pick.first("background"));
                }
                if (f.step() == BuilderFlow.Step.PARTS) {
                    all(f, pick, "parts");
                    if (f.step() == BuilderFlow.Step.PARTS) {   // a page that moved on by itself has nothing to press
                        press(f, PickPage.NEXT_ID);
                    }
                }
                if (f.step() == BuilderFlow.Step.POWERS) {
                    all(f, pick, "powers");
                    if (!pick.first("temper").isEmpty()) {
                        press(f, pick.first("temper"));
                    }
                    press(f, PickPage.NEXT_ID);
                }
                if (f.step() == BuilderFlow.Step.ABILITIES) {
                    all(f, pick, "abilities");
                    if (f.step() == BuilderFlow.Step.ABILITIES) {
                        press(f, PickPage.NEXT_ID);
                    }
                }
            }
            case "made_up" -> {
                if (pick.kind().equals("building")) {
                    // a simple building from parts: its type, roof, windows, then what goes outside.
                    press(f, pick.first("from"));
                    press(f, pick.first("roof"));
                    press(f, pick.first("windows"));
                    outside(f, pick);
                } else if (pick.kind().equals("magic_block")) {
                    // the magic-block kind opens on what it is made of, then when, then what it does.
                    press(f, pick.first("material"));
                    press(f, pick.first("trigger"));
                    press(f, pick.first("power"));
                } else if (pick.kind().equals("item")) {
                    // the item kind opens on its stock items; no "make one up" press first.
                    BuilderLooks.Look look = BuilderLooks.look(pick.first(BuilderLooks.SLOT));
                    if (look != null) {   //: a look is "🎨 More looks…", its group, itself (it picks the item too)
                        press(f, BuilderLooks.MORE_ID);
                        press(f, BuilderLooks.GROUP_PREFIX + look.group());
                        press(f, BuilderLooks.PREFIX + look.id());
                    } else {
                        press(f, pick.first("shape"));
                    }
                    press(f, pick.first("power"));
                    if (f.step() == BuilderFlow.Step.TURN_FROM) {   //: what it turns, then into what
                        all(f, pick, BuilderTurnInto.FROM_SLOT);
                        press(f, PickPage.NEXT_ID);
                        press(f, pick.first(BuilderTurnInto.TO_SLOT));
                    }
                } else {
                    press(f, pick.first("motion"));   //: the event kind opens on "What happens?"
                    if (f.step() == BuilderFlow.Step.EVENT_WHAT) {
                        press(f, pick.first("what"));
                    }
                }
            }
            case "animal" -> press(f, pick.first("from"));   //: a real farm animal goes straight to how many
            case "composed" -> {   //: the machine kind opens on its looks
                press(f, pick.first("look"));
                all(f, pick, "snack_powers");
                if (f.step() == BuilderFlow.Step.SNACK_POWER) {
                    press(f, PickPage.NEXT_ID);
                }
                press(f, pick.first("ingredient"));
            }
            case "rule" -> {
                press(f, pick.first("when"));
                if (f.step() == BuilderFlow.Step.WHO) {
                    press(f, pick.first("who"));
                }
                press(f, pick.first("does"));
            }
            default -> throw new IllegalArgumentException("no way to make a '" + pick.form() + "'");
        }
        if (f.step() != BuilderFlow.Step.TUNE) {
            throw new IllegalArgumentException("the presses for " + Apprentice.key(pick) + " stopped at " + f.step());
        }
        return f;
    }

    /** The tune dialog's values for {@code pick}: its size, count, strength, fill and colours (the name is the dialog's own). */
    public static Map<String, String> values(Apprentice.Pick pick) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : List.of("size", "count", "strength", "fill", "floors", "frame", "amount", "scheme", "path", "wreck", "speed", "mobsize")) {
            if (!pick.first(k).isEmpty()) {
                out.put(k, pick.first(k));
            }
        }
        return out;
    }

    /**
     * The spec the Builder's own Make would write for {@code pick}. The name is the Builder's generated one, never
     * a typed word (docs/OPEN_DOOR_PLAN.md §7.3: nothing a Visitor types is kept).
     */
    public static BuilderSpec spec(BuilderAllowlist allow, LibraryPresets presets, BuilderTree tree, Apprentice.Pick pick) {
        Apprentice.Pick full = Apprentice.filled(tree, pick);
        return pressed(allow, presets, full).spec(values(full));
    }

    /** What goes outside: each thing he named (several may), then Done; none named is nothing outside. */
    private static void outside(BuilderFlow f, Apprentice.Pick pick) {
        List<String> on = pick.picks().getOrDefault("outside", List.of()).stream()
                .filter(id -> !id.equals("outside:" + BuilderBuildings.NOTHING)).toList();
        if (on.isEmpty()) {
            press(f, "outside:" + BuilderBuildings.NOTHING);
            return;
        }
        for (String id : on) {
            press(f, id);
        }
        if (f.step() == BuilderFlow.Step.BUILD_OUTSIDE) {   // a full page moves on by itself
            press(f, com.moddingfromamod.ui.PickPage.NEXT_ID);
        }
    }

    private static void all(BuilderFlow f, Apprentice.Pick pick, String slot) {
        for (String id : pick.picks().getOrDefault(slot, List.of())) {
            press(f, id);
        }
    }

    private static void press(BuilderFlow f, String id) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("nothing to press at " + f.step());
        }
        BuilderFlow.Step before = f.step();
        BuilderFlow.Outcome o = f.press(id);
        if (!o.say().isEmpty()) {
            throw new IllegalArgumentException("pressing " + id + " at " + before + " was refused: " + o.say());
        }
    }
}
