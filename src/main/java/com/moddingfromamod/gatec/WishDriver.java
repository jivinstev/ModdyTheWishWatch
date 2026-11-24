package com.moddingfromamod.gatec;

import com.moddingfromamod.item.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.dialog.DialogScreen;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * <b>Type a wish through the route that actually ships — the ONE driver, not a copy per test.</b>
 *
 * <p>This is the end-to-end gate's driver, lifted out of it verbatim. It was the only correct
 * one in the repo after an earlier change, and it stayed the only one because it was private: every other client
 * test kept its own straight-line {@code useItem} → {@code mc.screen instanceof WishScreen} →
 * {@code charTyped} sequence, which was the LEGACY route and had been unreachable on the shipped
 * default for the life of the vanilla watch. Those tests did not fail; they could not
 * start. then deleted that screen, so the sequence is uncompilable rather than unreachable.
 *
 * <p>So this class exists for the same reason {@code Viewpoint} does: <b>re-introducing a second
 * copy of this sequence is the regression it is here to prevent.</b> A copy would not inherit the
 * four measured fixes already in it — the watch's use cooldown, latching while the cells are still
 * arriving, putting the watch back in hand every attempt, and the fact that a panel's labels exist
 * only while the container is open.
 *
 * <p><b>The route, in three steps</b>, none of which involves a mod-side screen: the watch
 * opens a {@code GENERIC_9x6} chest via a server packet; clicking COMPOSE opens vanilla's
 * {@code DialogScreen} (the wish box, {@code WishCompose}; an anvil until D2) whose text box is
 * where the wish is typed; its "✨ Make my wish" button runs {@code /moddy wishbox <words>}, which submits it.
 *
 * <p>⚠ The clicks go through {@code gameMode.handleInventoryMouseClick}, not through
 * {@code menu.clicked}, because the whole claim of the vanilla route is that a plain client drives
 * it with plain packets. Calling the menu directly would exercise our own server code with the
 * transport removed — green here and silent about the thing being tested.
 *
 * <p><b>Tick-driven, so an instance is a position rather than a call.</b> On the vanilla route the
 * screen arrives in a SERVER PACKET, so asserting on the same tick as the click could never have
 * held even pointed at the right class. {@link #drive} returns true on the tick the wish is
 * submitted and false while still working; {@link #failure()} is non-null once it has given up, and
 * the caller reports it in its own voice.
 */
public final class WishDriver {

    /** Long enough to outlast the watch's 10-tick use cooldown many times over. */
    public static final int DEFAULT_TIMEOUT_TICKS = 20 * 20;

    /**
     * The COMPOSE cell — the one a child clicks to wish for something new.
     *
     * <p>Derived from {@code WatchPanel}'s own layout rather than typed as 49, because a panel
     * relaid out with a different control row would otherwise leave a gate clicking a cell that has
     * quietly become something else, and clicking the wrong control is not a failure that announces
     * itself.
     */
    public static final int COMPOSE_SLOT =
            com.moddingfromamod.marshal.WatchPanel.CONTROL_ROW_START
                    + com.moddingfromamod.marshal.WatchPanel.COLS / 2;

    private final int timeoutTicks;
    private Runnable onPanelOpen = () -> { };
    private Runnable onBeforeCompose = () -> { };
    private java.util.function.Consumer<Minecraft> beforeTyping = mc -> { };
    private java.util.function.Function<Minecraft, String> afterTyping = mc -> null;

    private int step;
    private int stepTicks;
    private String failure;

    public WishDriver() {
        this(DEFAULT_TIMEOUT_TICKS);
    }

    public WishDriver(int timeoutTicks) {
        this.timeoutTicks = timeoutTicks;
    }

    /**
     * Run on EVERY tick the vanilla panel is open, before the COMPOSE click.
     *
     * <p>Every tick rather than once, and that is not a detail: the screen and its items arrive in
     * different packets, so a hook fired on the first tick the panel is open sees no cells at all.
     */
    public WishDriver onPanelOpen(Runnable hook) {
        this.onPanelOpen = hook == null ? () -> { } : hook;
        return this;
    }

    /** Run once, on the tick the COMPOSE cell is clicked and the cells are known to have landed. */
    public WishDriver onBeforeCompose(Runnable hook) {
        this.onBeforeCompose = hook == null ? () -> { } : hook;
        return this;
    }

    /**
     * Run once with the wish UI open and BEFORE a character is typed.
     *
     * <p>The place a caller empties the field, and on this route it should never need to: the
     * wish box ({@code WishCompose}) opens with an EMPTY initial. The hook outlived the legacy
     * screen that did need it — that one
     * seeded Moddy's suggested follow-up into its box, which a harness typing on top of produced
     * wishes like {@code "make Spikor's fire even biggermake a zippy blue hedgehog"} from — because
     * a field that opens dirty is a bug CLASS rather than a one-off, and a caller that finds one
     * wants somewhere to put the fix.
     */
    public WishDriver beforeTyping(java.util.function.Consumer<Minecraft> hook) {
        this.beforeTyping = hook == null ? mc -> { } : hook;
        return this;
    }

    /**
     * Run once with the wish typed and BEFORE it is submitted; return null to go ahead, or why not.
     *
     * <p>For a caller whose case is invalidated by the field not holding what it typed. Returning a
     * reason aborts the drive and surfaces through {@link #failure()}, so the caller reports it in
     * its own voice rather than submitting a wish it knows is wrong.
     */
    public WishDriver afterTyping(java.util.function.Function<Minecraft, String> hook) {
        this.afterTyping = hook == null ? mc -> null : hook;
        return this;
    }

    /**
     * Put this driver back at the start.
     *
     * <p>A caller that drives more than one wish MUST call this between them, or the second starts
     * at whatever step the first ended on and submits nothing — which reads as the agent ignoring
     * the wish rather than as the harness never sending it.
     */
    public void reset() {
        step = 0;
        stepTicks = 0;
        failure = null;
    }

    /** Non-null once the driver has given up; the caller reports it in its own voice. */
    public String failure() {
        return failure;
    }

    /** How far in — only for a diagnostic; a caller should not branch on it. */
    public int step() {
        return step;
    }

    /** Returns true on the tick the wish is submitted, false while still working. */
    public boolean drive(Minecraft mc, String text) {
        if (failure != null) {
            return false;
        }
        if (mc.player == null) {
            return false;
        }
        stepTicks++;
        if (stepTicks > timeoutTicks) {
            failure = "the " + route() + " never got past step " + step + " in "
                    + (timeoutTicks / 20) + "s — " + seen(mc);
            return false;
        }
        // ── REFUSE A WISH THE BOX CANNOT TAKE, BEFORE TYPING A CHARACTER.
        //
        // The box's max_length is a stock client's own cap: the keystroke past it is DISCARDED at
        // the point of typing, so nothing is entered and then cut. A harness that types past it
        // does not fail — it submits a shorter wish than the one it was asked to measure (measured
        // on the anvil this replaced: 57 characters typed, 49 recorded). Refusing is the honest
        // form: the run stops instead of measuring a wish nobody wrote. Here rather than in each
        // caller, because the long wishes are in `-Pwish`/`wishb64` and the corpora, not the
        // constants. (The anvil's cap was 50 and a long wish detoured through chat's /wish; the
        // box takes WishText.BOX_MAX, so it no longer needs to.)
        String tooLong = com.moddingfromamod.wish.WishText.tooLongForBox(text);
        if (tooLong != null) {
            failure = tooLong;
            return false;
        }
        switch (step) {
            case 0 -> {
                // PUT THE WATCH BACK IN HAND. Whatever is in the selected slot is what a
                // right-click uses, and a grant can hand the child something.
                mc.player.getInventory().getNonEquipmentItems().set(0, new ItemStack(ModItems.WISH_WATCH.get()));
                mc.player.getInventory().setSelectedSlot(0);
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                advance();
            }
            case 1 -> {
                // (on by default since 2026-11-16): the watch opens the WISH BOX itself, so there is no
                // panel and no "Wish for something new" to press — straight to typing.
                if (wishUiOpen(mc)) {
                    onPanelOpen.run();
                    onBeforeCompose.run();
                    step = 4;
                    stepTicks = 0;
                    return false;
                }
                if (panelOpen(mc)) {
                    // ⚠ THE PANEL IS THE ONLY PLACE THE VANILLA ROUTE'S LABELS EXIST, and
                    // it exists only while the container is open -- so a "before" snapshot has to
                    // be taken HERE rather than before the watch is even clicked.
                    onPanelOpen.run();
                    advance();
                    return false;
                }
                // THE WATCH HAS A 10-TICK USE COOLDOWN, so a click that lands inside the previous
                // interaction's window is silently ignored -- which an earlier run reported as
                // "Moddy did not open", a real-looking failure that was purely impatience. Re-issue
                // rather than wait it out.
                if (stepTicks % 15 == 0) {
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                }
            }
            case 2 -> {
                // Let the slot contents land before clicking one: the screen and its items arrive
                // in different packets, and clicking an empty cell does nothing at all.
                //
                // ⚠ AND KEEP LATCHING WHILE THEY DO. Step 1 latches on the first tick the panel is
                // open, which is the tick the cells have NOT arrived on -- so on its own it records
                // nothing and EVERY label after the wish counts as new. The last of them is the
                // bottom-right control, so the gate went on to press "✖ Close" and report that
                // pressing the remembered wish granted nothing.
                onPanelOpen.run();
                if (stepTicks < 10) {
                    return false;
                }
                onBeforeCompose.run();
                mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId,
                        COMPOSE_SLOT, 0, ClickType.PICKUP, mc.player);
                advance();
            }
            case 3 -> {
                if (!wishUiOpen(mc)) {
                    return false;
                }
                advance();
            }
            default -> {
                if (!wishUiOpen(mc)) {
                    failure = "the " + route() + " closed before the wish could be typed — "
                            + seen(mc);
                    return false;
                }
                beforeTyping.accept(mc);
                // The panel can close between the check and the typing: a world event or rule
                // the first wish left running fires in the gap (the events smoke's Muse sinkhole
                // chain restaged its crater whenever a creeper came near, and this line threw an
                // NPE on mc.screen). That is the world being busy, not the wish failing, so close
                // what is left of the menu and start this wish again from opening the watch.
                if (mc.screen == null) {
                    System.out.println("wish-driver: the panel closed before typing; reopening the watch");
                    if (mc.player != null) {
                        mc.player.closeContainer();
                    }
                    step = 0;
                    stepTicks = 0;
                    return false;
                }
                GuiEventListener box = wishBox(mc);
                if (box == null) {
                    failure = "the wish box has no text field — " + seen(mc);
                    return false;
                }
                // Moddy may have FILLED the box (a suggestion); a child would clear it first, and
                // typing after it sent "meteor craterpaint a picture of …" (2026-11-18's A/B).
                if (box instanceof MultiLineEditBox m) {
                    m.setValue("");
                } else if (box instanceof EditBox e) {
                    e.setValue("");
                }
                // Typed key by key into the box itself, so its max_length is the client's own.
                for (char c : text.toCharArray()) {
                    box.charTyped(c, 0);
                }
                String typed = boxValue(box);
                if (!text.equals(typed)) {
                    failure = "the wish box holds \"" + typed + "\" after typing \"" + text + "\"";
                    return false;
                }
                String bad = afterTyping.apply(mc);
                if (bad != null) {
                    failure = bad;
                    return false;
                }
                // The dialog's one button runs `/moddy wishbox <words>` (WishCompose) and closes it.
                if (!pressWish(mc)) {
                    failure = "the wish box has no \"" + com.moddingfromamod.wish.WishCompose.ACTION
                            + "\" button — " + seen(mc);
                    return false;
                }
                return true;

            }
        }
        return false;
    }

    private void advance() {
        step++;
        stepTicks = 0;
    }

    // ---------- what the route is, and what the client can see of it ----------

    /**
     * <b>THERE IS ONE ROUTE</b> — deleted the other one, so this is a name rather than a
     * branch.
     *
     * <p>It was a branch, read from the same switch the item reads (/), for as long as
     * {@code vanillawatch=off} could put a child back on {@code WishScreen}. Kept as a constant
     * because every failure message in this file names it, and "the vanilla watch panel never
     * opened" diagnoses itself where "the watch never opened" does not.
     */
    public static String route() {
        return "vanilla watch panel";
    }

    /** The panel is a plain vanilla double chest — that is the whole point of the vanilla route. */
    public static boolean panelOpen(Minecraft mc) {
        return mc.player != null
                && mc.player.containerMenu instanceof ChestMenu chest
                && chest.getType() == MenuType.GENERIC_9x6;
    }

    /** The wish box: vanilla's dialog screen, whose text box is where a wish is actually typed. */
    public static boolean wishBoxOpen(Minecraft mc) {
        return mc.screen instanceof DialogScreen<?>;
    }

    /** True once the watch's own UI is up and ready to be typed into. */
    public static boolean wishUiOpen(Minecraft mc) {
        return wishBoxOpen(mc) && wishBox(mc) != null;
    }

    /**
     * The wish box's text field, focused, or null. Found by walking the screen's widgets — a
     * dialog's inputs sit inside its scroll container — so a relaid dialog still finds it.
     */
    public static GuiEventListener wishBox(Minecraft mc) {
        if (!(mc.screen instanceof DialogScreen<?> d)) {
            return null;
        }
        GuiEventListener box = find(d, l -> l instanceof MultiLineEditBox || l instanceof EditBox);
        if (box != null) {
            d.setFocused(box);
            box.setFocused(true);
        }
        return box;
    }

    /** What the wish box holds now. */
    public static String boxValue(GuiEventListener box) {
        if (box instanceof MultiLineEditBox m) {
            return m.getValue();
        }
        return box instanceof EditBox e ? e.getValue() : null;
    }

    /** Press the wish box's "✨ Make my wish" button; false when there is none. */
    public static boolean pressWish(Minecraft mc) {
        if (!(mc.screen instanceof DialogScreen<?> d)) {
            return false;
        }
        GuiEventListener b = find(d, l -> l instanceof Button btn
                && (btn.getMessage().getString().equals(com.moddingfromamod.wish.WishCompose.ACTION)
                        || btn.getMessage().getString().equals(com.moddingfromamod.wish.WatchHome.MAKE_MY_WISH)));
        if (b == null) {
            return false;
        }
        ((Button) b).onPress();
        return true;
    }

    private static GuiEventListener find(ContainerEventHandler parent,
                                         java.util.function.Predicate<GuiEventListener> want) {
        for (GuiEventListener l : parent.children()) {
            if (want.test(l)) {
                return l;
            }
            if (l instanceof ContainerEventHandler c) {
                GuiEventListener hit = find(c, want);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /**
     * True once the watch itself is open, before anything has been typed.
     *
     * <p>Kept as its own name beside {@link #panelOpen} because they answer different questions to
     * a reader: this one is "did the watch open", and a later change to what the watch opens has
     * one place to land.
     */
    public static boolean watchOpen(Minecraft mc) {
        return panelOpen(mc);
    }

    /**
     * What the client can see right now, for a failure message that diagnoses itself.
     *
     * <p>Both halves, because they fail differently: {@code screen} is null while a container is
     * still in flight, and {@code containerMenu} is the player's own inventory menu when nothing
     * was opened at all. Reporting only one of them is how "screen=null" read as a broken watch
     * when the real state was "one tick too early".
     */
    public static String seen(Minecraft mc) {
        String screen = mc.screen == null ? "null" : mc.screen.getClass().getSimpleName();
        String menu = mc.player == null ? "no player"
                : mc.player.containerMenu.getClass().getSimpleName();
        return "screen=" + screen + " menu=" + menu + " route=" + route();
    }
}
