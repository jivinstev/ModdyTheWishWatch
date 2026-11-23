package com.moddingfromamod.wish;

import com.moddingfromamod.ui.MenuKit;
import com.moddingfromamod.ui.PickPage;
import com.moddingfromamod.ui.WishBoxDialog;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>The text-first watch</b>. On by default since 2026-11-16; {@link #KNOB} {@code off} turns a server back to the chest.
 *
 * <ul>
 *   <li><b>Java:</b> the watch opens the wish box, with one button under it per thing he can do now
 *       ({@link WishBoxDialog}).</li>
 *   <li><b>Bedrock</b> (design decision B2): a form cannot hold a text box AND buttons, so the watch opens a button page
 *       whose first button, {@link #WISH_FOR}, opens the wish box; the rest are the same buttons, in the same order.
 *       A design deviation, in the register (docs/BEDROCK_COMPAT.md).</li>
 * </ul>
 *
 * <p>"📚 My wishes" is the library: his things and the ready-made wishes only ({@link WatchMenu#openLibrary}). Every
 * button is an action the chest already had, by its own path ({@code /moddy stop}, {@code go}, {@code back}, {@code no},
 * {@code builder}, {@code magician}); a button shows only when it applies. {@link #acts} is pure, so a test reads exactly what he sees.
 */
public final class WatchHome {

    private WatchHome() {
    }

    /** The knob: on (the default since 2026-11-16) opens the text-first watch; {@code off} the chest. */
    public static final String KNOB = "watch.textFirst";

    /**
     * on Bedrock, Moddy's idea is a TILE of its own, first on the watch's page, and one
     * tap wishes it; "✨ I wish for…" then opens an EMPTY box, so a phone never deletes an idea by hand (a Bedrock
     * form cannot hold a link beside its text field, where Java has ✖ Clear). A design deviation (D8,
     * docs/BEDROCK_COMPAT.md), DECIDED and ON by default (2026-11-17);
     * {@code off} turns a server back to the grid with "I wish for…" first.
     */
    public static final String IDEA_KNOB = "watch.bedrockIdeaTile";
    /** The idea tile's words, before the idea itself. */
    public static final String IDEA_PREFIX = "💡 ";
    /** The idea tile's picture: Moddy's own lit light bulb (a menu sprite, VanillaCarrier.LIGHT_BULB_ICON), undyed. */
    public static final String IDEA_ICON = com.moddingfromamod.marshal.WatchIcons.sprite(
            com.moddingfromamod.datapack.VanillaCarrier.LIGHT_BULB_ICON, 0xFFFFFF);
    /** The idea tile's id on the page. */
    static final String IDEA_ID = "idea";

    /** Is the Bedrock idea tile on? ON when unset (since 2026-11-17). */
    public static boolean ideaTileOn() {
        Boolean t = ideaForTest;
        return t != null ? t : !"off".equals(com.moddingfromamod.config.ModdySettings.get(IDEA_KNOB).orElse("on"));
    }

    private static volatile Boolean ideaForTest;

    /** The idea tile's words: {@link #IDEA_PREFIX} and the idea as it will be wished. */
    static String ideaLabel(String words) {
        return IDEA_PREFIX + words.strip();
    }

    /** Test seam: on, off, or null for the knob. */
    public static void ideaTileForTest(Boolean on) {
        ideaForTest = on;
    }

    // ── the words. Every glyph is one the Bedrock glyph page draws (BedrockGlyphs). ──
    /** Bedrock's first button: opens the wish box. */
    public static final String WISH_FOR = "✨ I wish for…";
    /** The Java box's Submit. */
    public static final String MAKE_MY_WISH = WishCompose.ACTION;
    public static final String MY_WISHES = "📚 My wishes";
    public static final String BUILD = "✦ Build it myself";
    public static final String STOP = "✋ Stop the wish Moddy is making";
    public static final String WATCH = "🔍 Watch my wish being made";
    public static final String UNDO = "↩ Undo my last wish";
    public static final String BACK = "🏠 Take me back";
    public static final String NOW = com.moddingfromamod.marshal.GuidePanel.BUTTON;   // "📖 What do I do now?"
    /** the host's way in to the Magician setup, while it is off ("✪ Magician: on (…)" once it is on). */
    public static final String MAGICIAN = com.moddingfromamod.config.MagicianPresets.TURN_ON;
    /** The Bedrock page's title. */
    public static final String TITLE = "✨ Moddy ✨";
    /** A name in the undo button is cut to this, so the button stays one line. */
    static final int NAME_MAX = 12;

    /** The two new {@code /moddy} branches the buttons run: the library, and "What do I do now?". */
    public static final String LIBRARY_VERB = "library";
    public static final String NOW_VERB = "now";

    /** Is it on? ON when unset: approved after a side-by-side review (2026-11-16). */
    public static boolean on() {
        Boolean t = forTest;
        return t != null ? t : !"off".equals(com.moddingfromamod.config.ModdySettings.get(KNOB).orElse("on"));
    }

    private static volatile Boolean forTest;

    /** Test seam: on, off, or null for the knob. */
    public static void onForTest(Boolean on) {
        forTest = on;
    }

    /**
     * What decides the buttons: pure, so a test sets each. {@code magician} is the Magician tile's words, or null when
     * this player has none ({@code MagicianSetup.watchTile}).
     */
    public record Facts(boolean builder, String making, String undoName, boolean wayBack, String magician) {
        public Facts(boolean builder, String making, String undoName, boolean wayBack) {
            this(builder, making, undoName, wayBack, null);
        }
    }

    /** One button: its id (a Bedrock tile's press), its words, its Bedrock icon, and the command a Java button runs. */
    public record Act(String id, String label, String icon, String command) {
    }

    /** The buttons, in their fixed order; each only when it applies. */
    public static List<Act> acts(Facts f) {
        String m = ChildCommands.MODDY + " ";
        List<Act> out = new ArrayList<>();
        out.add(new Act("library", MY_WISHES, "minecraft:book", m + LIBRARY_VERB));
        if (f.builder()) {
            out.add(new Act("builder", BUILD, com.moddingfromamod.marshal.WatchIcons.BUILDER, m + ChildCommands.BUILDER));
        }
        if (f.making() != null) {
            out.add(new Act("stop", STOP, com.moddingfromamod.marshal.WatchIcons.STOP, m + ChildCommands.STOP));
            out.add(new Act("go", WATCH, "minecraft:spyglass", m + ChildCommands.GO + " " + f.making()));
        }
        if (f.undoName() != null) {
            // Not the barrier: Close wears it, and two red ⊘ tiles side by side on the phone read as one (step 3).
            out.add(new Act("undo", undoLabel(f.undoName()), "minecraft:recovery_compass",
                    m + ChildCommands.NO));
        }
        if (f.wayBack()) {
            out.add(new Act("back", BACK, com.moddingfromamod.marshal.WatchIcons.BACK_HOME, m + ChildCommands.BACK));
        }
        if (f.magician() != null) {
            out.add(new Act("magician", f.magician(), "minecraft:nether_star",
                    m + com.moddingfromamod.config.MagicianSetup.VERB));
        }
        out.add(new Act("now", NOW, com.moddingfromamod.marshal.WatchIcons.GUIDE, m + NOW_VERB));
        return out;
    }

    /** "↩ Undo my last wish: Sparky", the name cut so the button stays one line. */
    static String undoLabel(String name) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty()) {
            return UNDO;
        }
        if (n.length() > NAME_MAX) {
            n = n.substring(0, NAME_MAX - 1).strip() + "…";
        }
        return UNDO + ": " + n;
    }

    /** This player's facts, now. Server thread. */
    static Facts facts(ServerPlayer p) {
        boolean builder = com.moddingfromamod.wishbuilder.BuilderSettings.forPlayer(p.getUUID()).enabled();
        var snapshot = WatchSnapshot.forPlayer(p);
        String making = null;
        for (String row : snapshot.happening()) {
            making = com.moddingfromamod.marshal.WatchRow.Happening.decode(row).shortId();
            break;
        }
        String undo = null;
        Optional<Wish> last = WishRejection.pick(WishBook.get(p.getServer()).recent(40), p.getUUID(),
                System.currentTimeMillis());
        if (last.isPresent() && !WishRejection.alreadyRejected(last.get())
                && (making == null || !last.get().shortId().equals(making))) {
            undo = nameOf(last.get(), snapshot.creations());
        }
        boolean back = WishGo.canGoBack(p.getUUID(), System.currentTimeMillis());
        return new Facts(builder, making, undo, back,
                com.moddingfromamod.config.MagicianSetup.watchTile(p).orElse(null));
    }

    /** The wish's name on his watch: the thing it made when the watch knows it, else his own words. */
    static String nameOf(Wish w, List<String> creationRows) {
        String best = null;
        long at = -1;
        for (String row : creationRows) {
            var c = com.moddingfromamod.marshal.WatchRow.Creation.decode(row);
            if (c.updatedAt() >= w.createdMs() && c.updatedAt() > at) {
                best = c.name();
                at = c.updatedAt();
            }
        }
        return best != null ? best : w.text();
    }

    /** THE watch's door (a right-click, a bare {@code /wish} or {@code /moddy}): text-first when on, else the chest. */
    public static boolean openWatch(ServerPlayer p) {
        return on() ? open(p) : WatchMenu.open(p);
    }

    /** Open the text-first watch for this player. Server thread. */
    public static boolean open(ServerPlayer p) {
        List<Act> acts = acts(facts(p));
        com.moddingfromamod.agent.ActionLog.menuOpen(p.getUUID().toString(), "watch_home");
        if (com.moddingfromamod.net.PlayerClient.isBedrock(p)) {
            List<PickPage.Tile> tiles = new ArrayList<>();
            NextWish.Held held = ideaTileOn() ? NextWish.hold(p) : null;
            if (held != null) {
                tiles.add(PickPage.Tile.of(IDEA_ID, IDEA_ICON, ideaLabel(held.idea().words())));
            }
            tiles.add(PickPage.Tile.of("wish", PickPage.WATCH_ICON, WISH_FOR));   // the watch itself
            for (Act a : acts) {
                tiles.add(PickPage.Tile.of(a.id(), a.icon(), a.label()));
            }
            p.closeContainer();
            // with the idea tile on, the page is Bedrock's button list with NO pictures, its
            // words the full width, so a long idea reads whole; every button keeps its leading symbol
            MenuKit.pick(p, "watch_home", PickPage.of(TITLE, tiles, false), (sp, id) -> press(sp, id, acts),
                    ideaTileOn() ? "text" : null);
            return true;
        }
        List<WishBoxDialog.Button> buttons = new ArrayList<>();
        for (Act a : acts) {
            buttons.add(new WishBoxDialog.Button(a.label(), a.command()));
        }
        return WishCompose.openHome(p, buttons);
    }

    /** A Bedrock press: the same paths the Java buttons' commands run. */
    static void press(ServerPlayer p, String id, List<Act> acts) {
        if (IDEA_ID.equals(id)) {
            // the idea, sent as it was offered: the same instant way a Java box sent unchanged takes
            NextWish.Idea idea = NextWish.offered(p.getUUID());
            p.closeContainer();
            if (idea != null) {
                WishCompose.submit(p, idea.words());
            }
            return;
        }
        if ("wish".equals(id)) {
            if (ideaTileOn()) {
                WishCompose.openEmpty(p);   //: the idea has its own tile, so the box starts empty
            } else {
                WishCompose.open(p);
            }
            return;
        }
        for (Act a : acts) {
            if (a.id().equals(id)) {
                p.closeContainer();
                p.getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack(), a.command());
                return;
            }
        }
    }

    /** "What do I do now?": the watch's guide page, in the chest it has always been. */
    public static boolean openNow(ServerPlayer p) {
        return WatchMenu.openGuide(p);
    }
}
