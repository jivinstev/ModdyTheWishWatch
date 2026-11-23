package com.moddingfromamod.paint;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.marshal.ArtifactOwner;
import com.moddingfromamod.marshal.ForkPolicy;
import com.moddingfromamod.runtime.CreatureSculpt;

/**
 * <b>What one {@code paint_surface} call is allowed to do to whose picture</b>.
 *
 * <p>Before this, the only "edit" a picture had was re-using its id, which painted over whatever
 * hung at that spot and never asked whose it was. So *"make his picture green"*, said by his
 * brother, painted over the first child's picture, and *"make the sun bigger"* was a redraw from
 * nothing, because nothing could read the recipe back.
 *
 * <p>One pure decision, the same one {@code make_item} and {@code sculpt_creature} ask:
 * {@link ForkPolicy}. Its answers here:
 *
 * <ul>
 *   <li><b>his own, or a shared one</b> → edited under its own id, where it already hangs;</li>
 *   <li><b>another child's, or the library's</b> → FORKED: his own copy under
 *       {@code <id>~<him>}, on a FRESH board at his locked focus, and the original left hanging
 *       untouched. A second refinement edits his fork;</li>
 *   <li><b>nobody to attribute a fork to</b> → refused. Nothing is painted.</li>
 * </ul>
 *
 * <p>And two ways to build on a picture rather than redraw it: {@code start_from} inherits the
 * recipe and lays the new rectangles ON TOP (so only the change is sent); {@code as_new} does the
 * same into a SECOND picture with a new id, which always gets its own board, never the first one's.
 *
 * <p>Minecraft-free, so the whole table is a unit test ({@code PictureEditTest}).
 */
public final class PictureEdit {

    /**
     * The outcome. {@code refusal} non-null means nothing may be painted. {@code freshBoard} means
     * this picture must not go where another one already hangs: a new board at the child's focus.
     */
    public record Plan(String targetId, String ownerId, PaintRecipe recipe, int wide, int tall,
                       boolean freshBoard, String note, String refusal) {

        static Plan refuse(String why) {
            return new Plan("", "", PaintRecipe.blank(), 1, 1, false, "", why);
        }

        public boolean refused() {
            return refusal != null;
        }
    }

    /** Where to look a picture up — the store in production, a map in a test. */
    public interface Lookup {
        PictureStore.Picture get(String id);

        boolean inLibrary(String id);
    }

    private PictureEdit() {
    }

    /**
     * @param asker     the child whose wish this is ({@code ""} when nothing is bound)
     * @param givenId   the {@code id} argument
     * @param startFrom the {@code start_from} argument, or blank
     * @param asNew     {@code as_new}: a second picture, not a change to the first
     * @param changes   the rectangles sent in this call (all of it, without {@code start_from})
     * @param wide      the {@code wide} argument, 0 when not given
     * @param tall      the {@code tall} argument, 0 when not given
     */
    public static Plan plan(String asker, String givenId, String startFrom, boolean asNew,
                            PaintRecipe changes, int wide, int tall, Lookup store) {
        String me = ArtifactOwner.normalise(asker);
        String id = PictureStore.key(givenId);
        String from = PictureStore.key(startFrom);
        PaintRecipe sent = changes == null ? PaintRecipe.blank() : changes;

        if (asNew && from.isEmpty()) {
            return Plan.refuse("as_new makes a SECOND picture FROM one he already has, so it needs "
                    + "start_from — the picture to copy. Without it, just paint a new id.");
        }
        PictureStore.Picture base = null;
        if (!from.isEmpty()) {
            base = store.get(from);
            if (base == null) {
                return Plan.refuse("there is no picture called '" + from + "'. Call "
                        + "list_paintings to see his pictures — do not invent an id, because "
                        + "redrawing when he asked for a change loses everything he did not "
                        + "describe again.");
            }
        }
        if (base == null && sent.isEmpty()) {
            return Plan.refuse("That picture has no shapes in it, so it would paint nothing.");
        }

        if (asNew) {
            if (id.isEmpty() || id.equals(base.id())) {
                return Plan.refuse("as_new needs a NEW id for the second picture, different from '"
                        + base.id() + "'.");
            }
            PictureStore.Picture taken = store.get(id);
            if (taken != null && !ArtifactOwner.normalise(taken.ownerId()).equals(me)) {
                return Plan.refuse("'" + id + "' is already somebody else's picture — pick "
                        + "another id for the new one.");
            }
            return new Plan(id, me, layer(base.recipe(), sent), pick(wide, base.wide()),
                    pick(tall, base.tall()), true,
                    "a SECOND picture, '" + id + "', made from '" + base.id() + "', on its own "
                            + "board — the first one is untouched.\n", null);
        }

        // THE PICTURE BEING CHANGED: the one named by start_from, else whatever already has this
        // id. Re-using an id is an edit too, and is the path the brother's repaint used to take.
        PictureStore.Picture target = base != null ? base : (id.isEmpty() ? null : store.get(id));
        if (target == null) {
            return new Plan(id, me, sent, pick(wide, 1), pick(tall, 1), false, "", null);
        }
        ForkPolicy.Decision d = ForkPolicy.decide(me, target.id(), target.ownerId(),
                store.inLibrary(target.id()));
        if (d == ForkPolicy.Decision.REFUSE) {
            return Plan.refuse("'" + target.id() + "' is not this child's to change, and with no "
                    + "child bound to this wish there is nobody to make a copy for. Nothing was "
                    + "painted.");
        }
        PaintRecipe recipe = base != null ? layer(target.recipe(), sent) : sent;
        if (d == ForkPolicy.Decision.FORK) {
            String key = ForkPolicy.keyFor(d, target.id(), me);
            PictureStore.Picture his = store.get(key);
            if (his != null) {
                // His second refinement edits HIS copy, where it hangs — never a third board.
                PaintRecipe onHis = base != null ? layer(his.recipe(), sent) : sent;
                return new Plan(key, me, onHis, pick(wide, his.wide()), pick(tall, his.tall()),
                        false, "", null);
            }
            return new Plan(key, me, recipe, pick(wide, target.wide()),
                    pick(tall, target.tall()), true,
                    "'" + target.id() + "' is not this child's — it belongs to another child or "
                            + "to the shared library — so this change made HIS OWN copy, '" + key
                            + "', on its own board, and the original is untouched. Use '" + key
                            + "' next time.\n", null);
        }
        return new Plan(target.id(), ForkPolicy.ownerFor(d, me, target.ownerId()), recipe,
                pick(wide, target.wide()), pick(tall, target.tall()), false, "", null);
    }

    /** The base's rectangles, then the new ones ON TOP — later rectangles paint over earlier. */
    public static PaintRecipe layer(PaintRecipe base, PaintRecipe changes) {
        if (changes == null || changes.isEmpty()) {
            return base;
        }
        List<CreatureSculpt.PaintRect> all = new ArrayList<>(base.rects());
        // Changes drawn at a different size are scaled onto the base's canvas, so "a bigger sun"
        // sent on a 256 canvas lands in the right place on a 64 one.
        double k = (double) base.size() / changes.size();
        for (CreatureSculpt.PaintRect r : changes.rects()) {
            all.add(k == 1.0 ? r : new CreatureSculpt.PaintRect(
                    (int) Math.round(r.x() * k), (int) Math.round(r.y() * k),
                    Math.max(1, (int) Math.round(r.width() * k)),
                    Math.max(1, (int) Math.round(r.height() * k)), r.colour()));
        }
        // AT THE CAP, THE OLDEST SHAPES GO, never the edit. The recipe's own constructor keeps
        // the FIRST MAX_RECTS, which on a merge would silently drop exactly what he just asked
        // for; the oldest shapes are the ones the later ones paint over (P3).
        if (all.size() > PaintRecipe.MAX_RECTS) {
            all = new ArrayList<>(all.subList(all.size() - PaintRecipe.MAX_RECTS, all.size()));
        }
        return new PaintRecipe(base.size(), all);
    }

    private static int pick(int asked, int had) {
        return asked > 0 ? asked : had;
    }
}
