package com.moddingfromamod.wishbuilder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>The register of Builder paths</b> (every new Builder path is added to the Editor
 * and the Apprentice too). One row per slot of a kind's form in {@link BuilderTree}: does the
 * editor ({@link BuilderEdit}) change it on a kept make, and does a typed change ({@link RewishReader}, with its twin
 * tools/rewish_reader.py) read it. The Apprentice reads every slot's words already (BuilderTreeTest fails an entry with
 * none). A "no" says why. {@code BuilderPathCoverageTest} asks the real editor and reader and fails a row that is wrong, a
 * slot with no row, and a row for a slot that is gone.
 */
public final class BuilderPaths {

    private BuilderPaths() {
    }

    /** {@code why}: why not, where edit or read is false (else empty). */
    public record Row(boolean edit, boolean read, String why) {
    }

    public static final Map<String, Row> ROWS = new LinkedHashMap<>();

    private static void row(String key, boolean edit, boolean read, String why) {
        ROWS.put(key, new Row(edit, read, why));
    }

    static {
        row("building/made_up/floors", true, false, "not read by a typed change yet: #2352");
        row("building/made_up/from", false, false, "a house's type is what it is: another type is a new make");
        row("building/made_up/outside", true, true, "");
        row("building/made_up/roof", true, false, "not read by a typed change yet: #2352");
        row("building/made_up/scheme", true, true, "");
        row("building/made_up/windows", true, false, "not read by a typed change yet: #2352");
        row("building/ready/from", true, false, "a new make, not a change of his");
        row("building/ready/outside", true, true, "");
        row("building/ready/scheme", true, true, "");
        row("building/ready/size", true, true, "");
        row("creature/animal/count", false, false, "how many to make is a new make, never a change of the ones he has");
        row("creature/animal/from", false, false, "a real farm animal is a real mob: nothing of it is kept to change");
        row("creature/animal/mobsize", false, true, "a real farm animal is a real mob: nothing of it is kept to change");
        row("creature/animal/acts", false, false, "a real mob is not kept to change; how it acts is read when made (#2402), and a typed change of it is #2352's backlog");
        row("creature/ready/abilities", true, true, "");
        row("creature/ready/count", false, false, "how many to make is a new make, never a change of the ones he has");
        row("creature/ready/from", true, false, "a new make, not a change of his");
        row("creature/ready/mobsize", true, true, "");
        row("creature/ready/parts", true, true, "");
        row("creature/ready/powers", true, true, "");
        row("creature/ready/scheme", true, true, "");
        row("creature/ready/speed", true, true, "");
        row("creature/ready/strength", true, true, "");
        row("creature/ready/temper", true, true, "");
        row("event/made_up/count", false, false, "how many to make is a new make, never a change of the ones he has");
        row("event/made_up/motion", true, false, "not read by a typed change yet: #2352");
        row("event/made_up/path", false, false, "a path belongs to its motion: the example's motion has none to change; not read by a typed change yet: #2352");
        row("event/made_up/size", true, false, "not read by a typed change yet: #2352");
        row("event/made_up/what", true, false, "not read by a typed change yet: #2352");
        row("event/made_up/wreck", true, false, "not read by a typed change yet: #2352");
        row("item/made_up/count", false, false, "how many to make is a new make, never a change of the ones he has");
        row("item/made_up/itemlook", true, true, "");
        row("item/made_up/power", true, true, "");
        row("item/made_up/scheme", true, true, "");
        row("item/made_up/shape", true, false, "not read by a typed change yet: #2352");
        row("item/made_up/strength", true, true, "");
        row("item/made_up/turn_from", false, false, "only with Turn things into… as its power (its own pages); not read by a typed change yet: #2352");
        row("item/made_up/turn_to", false, false, "only with Turn things into… as its power (its own pages); not read by a typed change yet: #2352");
        row("machine/composed/fill", true, false, "not read by a typed change yet: #2352");
        row("machine/composed/ingredient", true, false, "not read by a typed change yet: #2352");
        row("machine/composed/look", true, false, "not read by a typed change yet: #2352");
        row("machine/composed/snack_powers", true, false, "not read by a typed change yet: #2352");
        row("machine/composed/strength", true, true, "");
        row("magic_block/made_up/material", true, false, "not read by a typed change yet: #2352");
        row("magic_block/made_up/power", true, false, "not read by a typed change yet: #2352");
        row("magic_block/made_up/scheme", true, true, "");
        row("magic_block/made_up/strength", true, true, "");
        row("magic_block/made_up/trigger", false, false, "the trigger is fixed by its power: change the power; not read by a typed change yet: #2352");
        row("picture/ready/background", true, false, "not read by a typed change yet: #2352");
        row("picture/ready/frame", true, false, "not read by a typed change yet: #2352");
        row("picture/ready/from", true, false, "a new make, not a change of his");
        row("picture/ready/scheme", true, true, "");
        row("ride/ready/abilities", true, true, "");
        row("ride/ready/from", true, false, "a new make, not a change of his");
        row("ride/ready/mobsize", true, true, "");
        row("ride/ready/parts", true, true, "");
        row("ride/ready/powers", true, true, "");
        row("ride/ready/scheme", true, true, "");
        row("ride/ready/speed", true, true, "");
        row("ride/ready/strength", true, true, "");
        row("ride/ready/temper", true, true, "");
        row("rule/rule/does", true, false, "not read by a typed change yet: #2352");
        row("rule/rule/when", true, false, "not read by a typed change yet: #2352");
        row("rule/rule/who", true, false, "not read by a typed change yet: #2352");
        row("supplies/ready/amount", false, false, "supplies are given, not kept: nothing to change");
        row("supplies/ready/from", false, false, "supplies are given, not kept: nothing to change");
    }
}
