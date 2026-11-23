package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Every pick in the Builder can be reached by typed words</b> (can
 * natural-language Apprentice wishes reach every node?). The back-test asks whether whole
 * wishes land; nothing asked whether each pick CAN be landed on. A pick with words the matcher never turns into
 * that pick is a tile a Crafter can only find by hand.
 *
 * <p>For every entry of every slot of every form, some phrase built from the entry's OWN words must make the
 * matcher offer a make that has it. The phrase is the least a child would say: a word for the kind, a word for
 * each other pick the form cannot be made without (its anchors), and the entry's word. Nothing here names an
 * entry: a new pick is covered the day it is added, and fails here by name if its words do not reach it.
 *
 * <p>Left out, each for a reason that is checked: a slider's DEFAULT step (it is what no word gives), and a
 * creature's "I can ride it" (the matcher answers with the same body as a RIDE, which is that pick).
 */
class ApprenticeReachTest {

    private static final BuilderTree TREE = BuilderTree.of(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
    private static final Apprentice APPRENTICE = new Apprentice(TREE, Apprentice.english());

    /** How many choices of each other pick are tried before a pick is called unreachable. */
    // 16, not 12: a ride's ready-mades list the rideable ones first, and once the car became a rideable vehicle
    // the first twelve were all bodies that already "stay forever" — so "Stays forever" was met by
    // the body on every try, though it is reachable on any body that does not (a cat). The sample, not the matcher.
    private static final int TRIES = 16;

    /** The picks a phrase must also name for the form to be offered at all (the matcher's own anchors, plus a snack's power). */
    private static List<String> carrier(String kind, String form) {
        List<String> out = new ArrayList<>(Apprentice.anchors(kind, form));
        if (kind.equals("machine") && form.equals("composed")) {
            out.add("snack_powers");   // a snack machine with no power named is the stock machine
        }
        return out;
    }

    @Test
    @DisplayName("every Builder pick is offered for some phrase made of its own words")
    void everyPickIsReachable() throws Exception {
        List<String> unreachable = new ArrayList<>();
        int checked = 0;
        for (BuilderTree.Kind kind : TREE.kinds()) {
            for (BuilderTree.Form form : kind.forms()) {
                for (BuilderTree.Slot slot : form.slots()) {
                    for (BuilderTree.Entry entry : slot.entries()) {
                        if (slot.steps() && entry.id().equals(slot.dflt())) {
                            continue;   // the default step: what a wish that names none gets
                        }
                        if (kind.id().equals("creature") && slot.id().equals("from") && Apprentice.RIDDEN_ONLY.contains(entry.id())) {
                            continue;   //: a vehicle is reached as a ride, never as a creature he cannot ride
                        }
                        checked++;
                        if (!reachable(kind, form, slot, entry)) {
                            unreachable.add(kind.id() + "/" + form.id() + " " + slot.id() + " = " + entry.label()
                                    + " (" + entry.id() + ") words " + entry.words());
                        }
                    }
                }
            }
        }
        Path out = Path.of("build", "apprentice-reach.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, checked + " picks checked, " + unreachable.size() + " unreachable\n"
                + String.join("\n", unreachable) + "\n");
        assertTrue(unreachable.isEmpty(), unreachable.size() + " of " + checked + " Builder pick(s) cannot be reached by "
                + "typed words: give the pick words the matcher can use (its record, or BuilderVocab), or fix the "
                + "matcher. docs/APPRENTICE.md §2.\n  " + String.join("\n  ", unreachable));
    }

    private static boolean reachable(BuilderTree.Kind kind, BuilderTree.Form form, BuilderTree.Slot slot, BuilderTree.Entry entry) {
        String kindWord = kind.words().isEmpty() ? "" : BuilderTree.Term.parse(kind.words().get(0)).phrase();
        for (List<String> others : carriers(kind, form, slot, entry)) {
            String rest = String.join(" ", others);
            for (String word : entry.words()) {
                // a pick said after a word (a To of "Turn things into…", its facts' `cue`) is said with it.
                String w = (entry.facts().containsKey("cue") ? entry.facts().get("cue") + " " : "")
                        + BuilderTree.Term.parse(word).phrase();
                for (String phrase : List.of(rest + " " + w, w + " " + rest, kindWord + " " + rest + " " + w,
                        kindWord + " " + w + " " + rest)) {
                    if (offered(kind, slot, entry, phrase.strip())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Up to {@link #TRIES} sets of words for the form's other needed picks, each one this entry may go with. */
    private static List<List<String>> carriers(BuilderTree.Kind kind, BuilderTree.Form form, BuilderTree.Slot slot, BuilderTree.Entry entry) {
        List<List<String>> out = new ArrayList<>();
        for (int n = 0; n < TRIES; n++) {
            List<String> words = new ArrayList<>();
            for (String sid : carrier(kind.id(), form.id())) {
                BuilderTree.Slot other = form.slot(sid);
                if (sid.equals(slot.id()) || other == null) {
                    continue;
                }
                List<String> allowed = entry.only().get(sid);
                List<BuilderTree.Entry> fits = new ArrayList<>();
                for (BuilderTree.Entry e : other.entries()) {
                    List<String> back = e.only().get(slot.id());
                    // A body that already does this (its facts' `has`) is no carrier for it: the matcher rightly
                    // drops a power the body has, so the pick would look unreachable for a reason that is not one.
                    boolean hasIt = List.of(e.facts().getOrDefault("has", "").split(","))
                            .contains(entry.id().substring(entry.id().indexOf(':') + 1));
                    if (!hasIt && (allowed == null || allowed.contains(e.id())) && (back == null || back.contains(entry.id())) && !e.words().isEmpty()) {
                        fits.add(e);
                    }
                }
                if (!fits.isEmpty()) {
                    words.add(BuilderTree.Term.parse(fits.get(n % fits.size()).words().get(0)).phrase());
                }
            }
            if (!out.contains(words)) {
                out.add(words);
            }
        }
        return out;
    }

    private static boolean offered(BuilderTree.Kind kind, BuilderTree.Slot slot, BuilderTree.Entry entry, String phrase) {
        for (Apprentice.Pick pick : APPRENTICE.match(phrase).offers()) {
            Map<String, List<String>> picks = Apprentice.filled(TREE, pick).picks();
            if (pick.kind().equals(kind.id()) && picks.getOrDefault(slot.id(), List.of()).contains(entry.id())) {
                return true;
            }
            // "a cat i can ride" is answered with the cat as a RIDE: the same pick, under the kind it belongs to.
            if (entry.id().equals("power:rideable") && pick.kind().equals("ride")) {
                return true;
            }
        }
        return false;
    }
}
