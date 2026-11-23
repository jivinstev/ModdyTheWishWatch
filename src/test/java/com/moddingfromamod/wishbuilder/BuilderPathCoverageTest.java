package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * <b>every Builder path reaches the editor and the re-wish reader, or says why not.</b> A path is a slot on a
 * kind's form in {@link BuilderTree} (a power, a look, a part). For each one this asks the REAL entry points:
 * {@link BuilderEdit#apply} on a kept make of that kind (does a change of the slot apply and change what is made?) and
 * {@link RewishReader#readsSlot}. The answers must equal {@link BuilderPaths}' row, and a row that says no must say why.
 * A new slot fails here by name: classify it in BuilderPaths, and if it should be editable or read, make it so in the
 * same PR (both readers: tools/rewish_reader.py too). Every answer is written to {@code build/builder-paths.txt}.
 */
class BuilderPathCoverageTest {

    static final BuilderAllowlist ALLOW = BuilderAllowlist.REVIEWED;

    /** Kept makes to change, one or more per kind: the examples, and an item with a look. */
    static List<BuilderSpec> priors() {
        List<BuilderSpec> out = new ArrayList<>();
        for (String j : BuilderExamples.ONE_PER_KIND) {
            out.add(BuilderSpec.read(JsonParser.parseString(j).getAsJsonObject()));
        }
        out.add(BuilderEditLooksTest.made("crown", BuilderLooks.SHOW));
        return out;
    }

    /** Every tile id its own edit's pages show, and its tune dialog's keys: what it is made of, as the Builder sees it. */
    static java.util.Set<String> seen(BuilderSpec prior) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        BuilderFlow w;
        try {
            w = BuilderFlow.editing(ALLOW, LibraryPresets.SHIPPED, "x_1", prior);
        } catch (RuntimeException e) {
            return ids;
        }
        for (int guard = 0; guard < 48; guard++) {
            if (w.step() == BuilderFlow.Step.TUNE) {
                w.tune().inputs().forEach(in -> ids.add("tune:" + in.key()));
                return ids;
            }
            w.page().tiles().forEach(t -> ids.add(t.id()));
            String keep = BuilderFlow.multiPick(w.step()) ? com.moddingfromamod.ui.PickPage.NEXT_ID : w.keepPress();
            if (keep == null || !w.press(keep).say().isEmpty()) {
                return ids;
            }
        }
        return ids;
    }

    /** How many of a form's slots a make's own edit shows (a page entry, or a tune control). */
    static int match(java.util.Set<String> seen, BuilderTree.Form f) {
        int n = 0;
        for (BuilderTree.Slot s : f.slots()) {
            if (seen.contains("tune:" + s.id()) || s.entries().stream().anyMatch(e -> seen.contains(e.id()))) {
                n++;
            }
        }
        return n;
    }

    /** Does some change of {@code slot} apply to {@code prior} and change what is made? */
    static boolean edits(BuilderSpec prior, BuilderTree.Slot slot) {
        String before;
        try {
            before = BuilderLowering.lower(prior, "x_1", LibraryPresets.SHIPPED, new BuilderLowering.Edit("x_1")).toString();
        } catch (RuntimeException e) {
            return false;   // nothing of it is kept to change (a real mob, supplies)
        }
        List<BuilderEdit.Op> tries = new ArrayList<>();
        if (slot.steps()) {
            tries.add(BuilderEdit.Op.step(slot.id(), 1));
            tries.add(BuilderEdit.Op.step(slot.id(), -1));
        }
        List<BuilderTree.Entry> es = slot.entries();
        for (int i = 0; i < es.size() && tries.size() < 12; i += Math.max(1, es.size() / 8)) {
            String id = es.get(i).id();
            tries.add(slot.most() > 1 ? BuilderEdit.Op.add(slot.id(), id) : BuilderEdit.Op.set(slot.id(), id));
        }
        for (BuilderEdit.Op op : tries) {
            BuilderEdit.Result r;
            try {
                r = BuilderEdit.apply(ALLOW, LibraryPresets.SHIPPED, "x_1", prior, List.of(op));
            } catch (RuntimeException e) {
                continue;
            }
            if (r.ok() && !BuilderLowering.lower(r.spec(), "x_1", LibraryPresets.SHIPPED, new BuilderLowering.Edit("x_1"))
                    .toString().equals(before)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void everyBuilderSlotIsEditedAndReadOrSaysWhyNot() throws Exception {
        BuilderTree tree = BuilderTree.of(ALLOW, LibraryPresets.SHIPPED);
        List<BuilderSpec> priors = priors();
        // and one make of EVERY form, from its first entry, as the Apprentice makes a pick (a new form has one at once)
        for (BuilderTree.Kind k : tree.kinds()) {
            for (BuilderTree.Form f : k.forms()) {
                BuilderTree.Slot first = f.slots().get(0);
                if (first.entries().isEmpty()) {
                    continue;
                }
                try {
                    priors.add(ApprenticeMake.spec(ALLOW, LibraryPresets.SHIPPED, tree, new Apprentice.Pick(k.id(), f.id(),
                            Map.of(first.id(), List.of(first.entries().get(0).id())))));
                } catch (RuntimeException e) {
                    // a form the Apprentice cannot fill from one entry is covered by the examples above
                }
            }
        }
        Map<String, String> table = new TreeMap<>();
        List<String> wrong = new ArrayList<>();
        Map<String, Boolean> seenRow = new LinkedHashMap<>();
        BuilderPaths.ROWS.keySet().forEach(k -> seenRow.put(k, false));
        for (BuilderTree.Kind k : tree.kinds()) {
            for (BuilderTree.Form f : k.forms()) {
                for (BuilderTree.Slot s : f.slots()) {
                    String key = k.id() + "/" + f.id() + "/" + s.id();
                    boolean edit = false;
                    boolean tried = false;
                    for (BuilderSpec p : priors) {
                        if (!p.kind().id().equals(k.id())) {
                            continue;
                        }
                        java.util.Set<String> seen = seen(p);
                        int mine = match(seen, f);
                        if (mine == 0 || k.forms().stream().anyMatch(o -> o != f && match(seen, o) > mine)) {
                            continue;   // a make of another form of this kind
                        }
                        tried = true;
                        if (edits(p, s)) {
                            edit = true;
                            break;
                        }
                    }
                    boolean read = RewishReader.readsSlot(k.id(), s.id());
                    table.put(key, (tried ? (edit ? "edit" : "no-edit") : "untried") + " " + (read ? "read" : "no-read"));
                    BuilderPaths.Row row = BuilderPaths.ROWS.get(key);
                    if (row == null) {
                        wrong.add(key + ": a Builder path with no row in BuilderPaths (edit " + edit + ", read " + read + ")");
                        continue;
                    }
                    seenRow.put(key, true);
                    if (tried && row.edit() != edit) {
                        wrong.add(key + ": BuilderPaths says edit " + row.edit() + ", the editor " + (edit ? "changes it" : "cannot"));
                    }
                    if (row.read() != read) {
                        wrong.add(key + ": BuilderPaths says read " + row.read() + ", the reader " + (read ? "reads it" : "does not"));
                    }
                    if ((!row.edit() || !row.read()) && row.why().isBlank()) {
                        wrong.add(key + ": says no without saying why");
                    }
                }
            }
        }
        seenRow.forEach((k, seen) -> {
            if (!seen) {
                wrong.add(k + ": a row for a slot the Builder no longer has");
            }
        });
        StringBuilder out = new StringBuilder();
        table.forEach((k, v) -> out.append(k).append(' ').append(v).append('\n'));
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/builder-paths.txt"), out);
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }
}
