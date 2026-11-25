package com.moddingfromamod.wish;

import com.moddingfromamod.apprentice.LayerHost;
import com.moddingfromamod.ui.MenuKit;
import com.moddingfromamod.ui.PickPage;
import com.moddingfromamod.wishbuilder.Apprentice;
import com.moddingfromamod.wishbuilder.ApprenticeWords;
import com.moddingfromamod.wishbuilder.BuilderAllowlist;
import com.moddingfromamod.wishbuilder.BuilderMenus;
import com.moddingfromamod.wishbuilder.BuilderSettings;
import com.moddingfromamod.wishbuilder.BuilderSpec;
import com.moddingfromamod.wishbuilder.BuilderTree;
import com.moddingfromamod.wishbuilder.LibraryPresets;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The Apprentice, in the game</b>: typed words → up to five Builder makes on a pick page → the Builder,
 * made at once. No model: {@link Apprentice} is a local, deterministic matcher over this world's own Builder tree.
 *
 * <p>Reached from ONE place, {@link WishService}'s submit, after the safety floor has read the words
 * ({@link WishRoute} decides who answers). So both doors (the watch's wish box, {@code /wish}) and
 * {@code /moddy apprentice} end here the same way.
 *
 * <p><b>Nothing a Crafter types is kept</b> (docs/OPEN_DOOR_PLAN.md §7.3): his words are matched on the server
 * thread and dropped. They are not logged, not stored, not held for the page, and the make's name is the Builder's
 * generated one. A MAGICIAN's words are held in memory while his page is open, for one reason: his last tile sends
 * those words to the agent instead. They are dropped when he presses anything or leaves.
 *
 * <p><b>The price is the Builder's.</b> Pressing a tile makes it, a Builder make (half a wish, charged when it stands;
 * no tune dialog first, as a wish has none). Looking at the page costs nothing. The thing it made
 * may then be changed in the Builder once for free, until his next wish ({@code FreeEdit}).
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = com.moddingfromamod.ModdingFromAModMod.MODID)
public final class ApprenticeService {

    /** The world is up: build the matcher now, off the server thread, so the first wish does not pay for it. */
    @net.neoforged.bus.api.SubscribeEvent
    public static void onServerStarted(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        warm();
        // The trained layer: loaded on its own worker, and only where the model has been put. A world with
        // the Builder off, or with the layer turned off, never loads it.
        BuilderSettings s = BuilderSettings.current();
        if (s.enabled() && s.apprenticeLayer()) {
            LayerHost.start(com.moddingfromamod.runtime.RuntimeConfig.apprenticeModelDir(),
                    net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("moddingfromamod").resolve("onnxruntime-native"));
        }
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        LayerHost.stop();
        com.moddingfromamod.wishbuilder.FreeEdit.clearAll();
    }

    /**
     * How long a page may wait for the layer. A read is a few milliseconds; past this the keyword answer is shown,
     * which is the page he would have had without the layer.
     */
    static final long LAYER_WAIT_MS = 250;
    private static volatile long layerWaitMs = LAYER_WAIT_MS;

    /** Test seam: the wait cap (0 or less puts the shipped one back). */
    public static void layerWaitForTest(long ms) {
        layerWaitMs = ms > 0 ? ms : LAYER_WAIT_MS;
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("moddingfromamod/apprentice");

    /** What an open page is waiting on. {@code words} is empty for a Crafter, always. */
    /** {@code name}:, the name a Magician gave in his words, for the make (null for none, and always for a Crafter). */
    private record Pending(List<Apprentice.Pick> offers, String words, WishSource source, BuilderSettings settings,
                           String name) {
    }

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    /** One matcher per allowlist: building it walks the menus (about 10 ms), matching is then microseconds. */
    private static final Map<BuilderAllowlist, Apprentice> MATCHERS = new ConcurrentHashMap<>();
    private static final Map<BuilderAllowlist, BuilderTree> TREES = new ConcurrentHashMap<>();

    /** The Magician's toggle, per player, read through from his shelf once a session. */
    private static final Map<UUID, Boolean> PREFERS = new ConcurrentHashMap<>();
    static final String MODE = "mode";
    static final String APPRENTICE = "apprentice";
    static final String MAGICIAN = "magician";

    private ApprenticeService() {
    }

    static BuilderTree tree(BuilderAllowlist allow) {
        return TREES.computeIfAbsent(allow, a -> BuilderTree.of(a, LibraryPresets.SHIPPED));
    }

    static Apprentice matcher(BuilderAllowlist allow) {
        return MATCHERS.computeIfAbsent(allow, a -> new Apprentice(tree(a), Apprentice.english()));
    }

    /** Build the matcher off the first wish: called when the world's Builder settings are known. */
    public static void warm() {
        BuilderSettings s = BuilderSettings.current();
        if (s.enabled()) {
            Thread t = new Thread(() -> {
                try {
                    matcher(s.allow());
                } catch (RuntimeException e) {
                    LOGGER.warn("apprentice: could not build the matcher ahead of the first wish: {}", e.toString());
                }
            }, "moddy-apprentice-warm");
            t.setDaemon(true);
            t.start();
        }
    }

    // ── the Magician's toggle ──────────────────────────────────────────────────────────────────────

    /** Does this player's watch say "Apprentice"? Only ever true for a Magician: a Crafter has no toggle. */
    public static boolean prefersApprentice(ServerPlayer p) {
        if (CrafterService.isCrafter(p)) {
            return false;
        }
        return PREFERS.computeIfAbsent(p.getUUID(), id -> APPRENTICE.equals(WishSettingsStore.get(
                com.moddingfromamod.runtime.RuntimeConfig.playersDir(), p.getStringUUID(), MODE, MAGICIAN)));
    }

    /** Flip the Magician's toggle, say what it now is, and keep it on his shelf. Returns the new state. */
    public static boolean toggle(ServerPlayer p) {
        boolean now = !prefersApprentice(p);
        keep(p, now);
        p.sendSystemMessage(Component.literal(ApprenticeWords.modeSaid(now)).withStyle(ChatFormatting.AQUA));
        return now;
    }

    /**
     * The wish box's own choice: the same setting the toggle flips, kept the same way, but QUIET, because
     * what he chose is on the screen he chose it on and the answer says which magic it is. Nothing happens when it
     * is what he already had, and a Crafter has no choice to keep.
     */
    public static void choose(ServerPlayer p, boolean apprentice) {
        if (CrafterService.isCrafter(p) || prefersApprentice(p) == apprentice) {
            return;
        }
        keep(p, apprentice);
    }

    private static void keep(ServerPlayer p, boolean now) {
        PREFERS.put(p.getUUID(), now);
        try {
            if (!WishSettingsStore.set(com.moddingfromamod.runtime.RuntimeConfig.playersDir(), p.getStringUUID(), MODE,
                    now ? APPRENTICE : MAGICIAN)) {
                LOGGER.warn("apprentice: {}'s wish mode was not kept on his shelf (the file is not ours to write)",
                        p.getGameProfile().name());
            }
        } catch (IOException e) {
            LOGGER.warn("apprentice: {}'s wish mode was not kept on his shelf: {}", p.getGameProfile().name(), e.toString());
        }
        LOGGER.info("apprentice: {} set typed wishes to {}", p.getGameProfile().name(), now ? APPRENTICE : MAGICIAN);
    }

    /** The route for this player's typed words, by the one rule ({@link WishRoute}). */
    public static WishRoute.Route route(ServerPlayer p, WishRoute.Hint hint, boolean question) {
        // A toggle left on Apprentice means nothing on a world whose Builder is off: there are no Parts to pick from there.
        boolean prefers = prefersApprentice(p) && BuilderSettings.forPlayer(p.getUUID()).enabled();
        // Magician off means the Apprentice answers every typed wish (allowsAi: on, its check, or a developer's
        // forced pool).
        boolean magician = MagicianDoor.on(p.getUUID());
        return WishRoute.choose(magician, CrafterService.role(p), prefers, hint, question);
    }

    /** Would words sent to the Magician reach him, or come back to the Apprentice (Magician mode off)? */
    static boolean magicianAnswers(ServerPlayer p) {
        return MagicianDoor.offered(p);
    }

    // ── the wish ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Answer {@code words} with Apprentice magic. The words have already passed the safety floor
     * ({@code WishService}). Server thread: a match is well under a millisecond (docs/APPRENTICE.md §4½).
     *
     * @return true when a page of makes was opened
     */
    public static boolean offer(ServerPlayer p, String words, WishSource source) {
        // the Apprentice is LOCAL ONLY. Reading the words into a page runs inside the guard, so a model or web
        // call asked from here is refused and recorded; only the hand-off to the Magician (show) steps outside it.
        return com.moddingfromamod.marshal.LocalOnly.during(() -> offerLocally(p, words, source));
    }

    private static boolean offerLocally(ServerPlayer p, String words, WishSource source) {
        boolean crafter = CrafterService.isCrafter(p);
        if (crafter && WishIntent.isQuestion(words)) {
            // A question is not a make: the same answer a Crafter's question always had.
            p.sendSystemMessage(Component.literal(Crafters.QUESTION).withStyle(ChatFormatting.AQUA));
            return false;
        }
        BuilderSettings settings = BuilderSettings.forPlayer(p.getUUID());
        if (!settings.enabled()) {
            p.sendSystemMessage(Component.literal(ApprenticeWords.BUILDER_OFF).withStyle(ChatFormatting.AQUA));
            return false;
        }
        Apprentice matcher = matcher(settings.allow());
        // "a pet wolf called sir fluffy" — the name comes out of the words before they are read, so "fluffy" is
        // never read as a pick. A Magician's make takes it (after the checks, BuilderMenus.makeApprentice); a Crafter's
        // never does, and he is told a Wish Magician can. The words with the name in them are what the Magician gets.
        com.moddingfromamod.wishbuilder.ApprenticeName.Named named = com.moddingfromamod.wishbuilder.ApprenticeName.parse(words);
        String read = named == null ? words : named.rest();
        String name = named == null ? null : named.name();
        if (named != null && crafter) {
            p.sendSystemMessage(Component.literal(ApprenticeWords.NAME_MAGICIAN).withStyle(ChatFormatting.AQUA));
            nudge(p);
        }
        boolean layer = settings.apprenticeLayer() && LayerHost.ready();
        if (!layer || !matcher.wantsReader(read)) {
            return show(p, words, read, name, source, settings, matcher.match(read), layer, false);
        }
        // A clause the keywords could not read: the layer reads it on its own worker and the page opens on the
        // server thread when it has. Past LAYER_WAIT_MS the keyword answer is shown instead (the future completes
        // once, so a late read is dropped). The words live in these tasks and nowhere else.
        net.minecraft.server.MinecraftServer server = ((net.minecraft.server.level.ServerLevel) p.level()).getServer();
        long t0 = System.nanoTime();
        LayerHost.match(matcher, read).completeOnTimeout(null, layerWaitMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .thenAccept(answer -> server.execute(() -> {
                    if (p.hasDisconnected()) {
                        return;
                    }
                    if (answer == null) {
                        LOGGER.info("apprentice: the layer took over {} ms, answering from keywords", layerWaitMs);
                    } else {
                        LOGGER.debug("apprentice: the layer answered in {} ms", (System.nanoTime() - t0) / 1_000_000);
                    }
                    com.moddingfromamod.marshal.LocalOnly.run(() -> show(p, words, read, name, source, settings,
                            answer == null ? matcher.match(read) : answer, true, answer != null));
                }));
        return true;
    }

    /** Open the page for this answer (server thread). {@code tagged}: the layer is in use, so tiles say what they are. */
    private static boolean show(ServerPlayer p, String words, String readWords, String name, WishSource source,
                                BuilderSettings settings, Apprentice.Answer answer, boolean tagged, boolean read) {
        boolean crafter = CrafterService.isCrafter(p);
        // What is logged: who, how many tiles, the reach, how many picks the layer supplied. Never the words
        // (a Crafter's are not kept, §7.3).
        int guessed = 0;
        for (List<String> by : answer.layer()) {
            guessed += by.isEmpty() ? 0 : 1;
        }
        LOGGER.info("apprentice: {} ({}) -> {} tile(s), reach {}{}", p.getGameProfile().name(),
                crafter ? "crafter" : "magician", answer.offers().size(), answer.reach().id(),
                read ? ", layer on " + guessed + " tile(s)" : "");
        boolean priced = WishCharge.get().priced(p.getUUID());
        //, the collaborate mode: for a Magician on Apprentice magic, the page only when the Apprentice is SURE;
        // otherwise the same words go straight to the Magician, as "✪" would send them.
        // only when a Magician is there to take them. With Magician mode off (the public mod's default) every route
        // is the Apprentice's, so handing the words over would come straight back here, for ever (a StackOverflowError).
        Apprentice.Confidence sure = crafter || !magicianAnswers(p) ? null
                : matcher(settings.allow()).confidence(readWords, answer);
        if (sure != null) {
            LOGGER.info("apprentice: magician page {} ({})", sure.band(), sure.stratum());
            if (sure.band() != Apprentice.Band.SURE) {
                PENDING.remove(p.getUUID());
                p.closeContainer();
                p.sendSystemMessage(Component.literal(ApprenticeWords.TO_THE_MAGICIAN).withStyle(ChatFormatting.AQUA));
                // the hand-off: the Magician may call models, so this steps outside the Apprentice's guard
                com.moddingfromamod.marshal.LocalOnly.outside(() -> WishService.submit(p, words, source, WishRoute.Hint.MAGICIAN));
                return true;
            }
        }
        // #68: what this world shows of the ranked makes, AFTER ranking and outside the matcher: kept, fitted (made
        // fitted at the press, said once), locked, or dropped. No world answering: exactly the ranked answer.
        com.moddingfromamod.wishbuilder.ApprenticeFit.Shown shown = com.moddingfromamod.wishbuilder.ApprenticeFit.fit(answer,
                MakeGate.anyWorld() ? pick -> judge(p, settings, pick) : null);
        answer = shown.answer();
        if (answer.offers().isEmpty()) {
            PENDING.remove(p.getUUID());
            p.closeContainer();
            p.sendSystemMessage(Component.literal(ApprenticeWords.OUT).withStyle(ChatFormatting.AQUA));
            if (crafter) {
                nudge(p);
                return false;
            }
            if (!MagicianDoor.offered(p)) {
                // #78: with the Magician off there is nobody to wish it from scratch, so no "✪" tile that would only send
                // the words back here. Instead: other words, or the Builder; and for the host, where the setup is.
                p.sendSystemMessage(Component.literal(ApprenticeWords.NO_MAGICIAN_TRY).withStyle(ChatFormatting.AQUA));
                hostTurnOn(p);
                return false;
            }
            // A Magician chose Apprentice magic, often for its price: his words are never sent to the agent for a
            // whole wish without his say. The page opens with its one honest tile, "Ask Moddy instead".
            PENDING.put(p.getUUID(), new Pending(List.of(), words, source, settings, null));
            MenuKit.pick(p, "apprentice", ApprenticeWords.page(settings.allow(), LibraryPresets.SHIPPED, tree(settings.allow()),
                    List.of(), true, priced), ApprenticeService::press);
            return true;
        }
        PENDING.put(p.getUUID(), new Pending(answer.offers(), crafter ? "" : words, source, settings, crafter ? null : name));
        // the last tile is "✪ Moddy wishes it from scratch" only where the Magician would answer (#78); else "None of these"
        boolean offered = MagicianDoor.offered(p);
        p.sendSystemMessage(Component.literal(ApprenticeWords.hello(tree(settings.allow()), answer)).withStyle(ChatFormatting.AQUA));
        // each tile names what it leaves out, from the words he typed (held in memory for this page only,
        // as the words already are; never logged or stored). The words passed the safety floor before the matcher.
        List<Apprentice.Notes> notes = new ArrayList<>();
        if (tagged) {
            Apprentice m = matcher(settings.allow());
            for (Apprentice.Pick o : answer.offers()) {
                notes.add(m.notes(readWords, o));
            }
        }
        PickPage page = ApprenticeWords.page(settings.allow(), LibraryPresets.SHIPPED, tree(settings.allow()),
                answer.offers(), tagged ? answer : null, tagged ? notes : null, offered, priced, offered);
        page = com.moddingfromamod.wishbuilder.ApprenticeFit.page(page, shown.locked());
        MenuKit.pick(p, "apprentice", page, ApprenticeService::press);
        return true;
    }

    /** This world's view of one ranked make: the same gate and fitting its press goes through, nothing made. */
    static com.moddingfromamod.wishbuilder.BuilderPageFit.Judged judge(ServerPlayer p, BuilderSettings settings,
                                                                       Apprentice.Pick pick) {
        BuilderSpec spec;
        try {
            spec = BuilderMenus.apprenticeSpec(p, settings, tree(settings.allow()), pick);
        } catch (RuntimeException e) {
            return com.moddingfromamod.wishbuilder.BuilderPageFit.Judged.KEEP;   // said at the press, as it always was
        }
        MakeGate.Built built = com.moddingfromamod.wishbuilder.BuilderMake.askApprentice(p.getUUID(), spec, settings.allow());
        if (built.makes()) {
            return built.lowered().isEmpty() ? com.moddingfromamod.wishbuilder.BuilderPageFit.Judged.KEEP
                    : com.moddingfromamod.wishbuilder.BuilderPageFit.Judged.FIT;
        }
        MakeVerdict v = built.verdict();
        return v.locked() && !built.unfit() && !MakeVerdict.TOO_MUCH.equals(v.line())
                ? new com.moddingfromamod.wishbuilder.BuilderPageFit.Judged(com.moddingfromamod.wishbuilder.BuilderPageFit.Seen.LOCK, v.line())
                : com.moddingfromamod.wishbuilder.BuilderPageFit.Judged.DROP;
    }

    /**
     * (Moddy Media): what the Apprentice offers for these words, read by the keyword matcher with no page and
     * no agent: the same {@link Apprentice#match} a page is built from. For a film's planning and staging.
     */
    public static List<Apprentice.Pick> picksFor(ServerPlayer p, String words) {
        BuilderSettings settings = BuilderSettings.forPlayer(p.getUUID());
        return matcher(settings.allow()).match(words).offers();
    }

    /**
     * make offer {@code n} for these words, exactly as pressing that tile would ({@link BuilderMenus#makeApprentice}).
     * @return false when the Builder is off or there is no such offer
     */
    public static boolean makePick(ServerPlayer p, String words, int n) {
        BuilderSettings settings = BuilderSettings.forPlayer(p.getUUID());
        List<Apprentice.Pick> offers = matcher(settings.allow()).match(words).offers();
        if (n < 0 || n >= offers.size()) {
            return false;
        }
        return BuilderMenus.makeApprentice(p, settings, tree(settings.allow()), offers.get(n), null);
    }

    /** A press on the Apprentice page. */
    static void press(ServerPlayer p, String id) {
        Pending pending = PENDING.remove(p.getUUID());
        if (pending == null || PickPage.CLOSE_ID.equals(id)) {
            p.closeContainer();
            return;
        }
        if (ApprenticeWords.NONE.equals(id)) {
            p.closeContainer();
            if (pending.words().isEmpty() || !MagicianDoor.offered(p)) {
                // a Crafter, or anyone while the Magician is off (#78): the Builder has every Part; nothing goes on
                p.sendSystemMessage(Component.literal(ApprenticeWords.NONE_SAID).withStyle(ChatFormatting.AQUA));
                nudge(p);
            } else {
                // A Magician: the same words, to the agent, as the wish they always were.
                WishService.submit(p, pending.words(), pending.source(), WishRoute.Hint.MAGICIAN);
            }
            return;
        }
        int n = -1;
        try {
            n = id.startsWith(ApprenticeWords.OFFER) ? Integer.parseInt(id.substring(ApprenticeWords.OFFER.length())) : -1;
        } catch (NumberFormatException ignored) {
            // not an offer tile
        }
        if (n < 0 || n >= pending.offers().size()) {
            p.closeContainer();
            return;
        }
        LOGGER.info("apprentice: {} picked tile {} ({})", p.getGameProfile().name(), n, Apprentice.key(pending.offers().get(n)));
        // A tile MAKES its thing: no tune dialog in between, as a wish has none. A guess not
        // quite right is changed after, free once (FreeEdit), from the thing's page on his watch.
        BuilderMenus.makeApprentice(p, pending.settings(), tree(pending.settings().allow()), pending.offers().get(n),
                pending.name());
    }

    /** The one line about Wish Magicians, at most once in ten minutes (docs/OPEN_DOOR_PLAN.md §5). */
    private static void nudge(ServerPlayer p) {
        if (CrafterService.takeNudge(p)) {
            p.sendSystemMessage(Component.literal(ApprenticeWords.UPSELL).withStyle(ChatFormatting.GRAY)
                    .append(CrafterService.link(CrafterService.signupUrl())));
        }
    }

    /** The host, while the Magician is off: one line that names the setup, its words a link that opens it (#78). */
    private static void hostTurnOn(ServerPlayer p) {
        if (MagicianDoor.hostMayTurnOn(p)) {
            String cmd = "/" + ChildCommands.MODDY + " " + com.moddingfromamod.config.MagicianSetup.VERB;
            p.sendSystemMessage(Component.literal(ApprenticeWords.HOST_MAGICIAN_OFF).withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(ApprenticeWords.HOST_TURN_ON).withStyle(s -> s.withColor(ChatFormatting.AQUA)
                            .withUnderlined(true).withClickEvent(com.moddingfromamod.compat.ChatEvents.click(
                                    net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, cmd)))));
        }
    }

    /** He left: nothing of his page, his words or his toggle's cache outlives the session. */
    public static void forget(UUID player) {
        PENDING.remove(player);
        PREFERS.remove(player);
        com.moddingfromamod.wishbuilder.FreeEdit.forget(player);
    }

    /** Test seam: the offers a page waiting on this player holds, in its tiles' order; empty when none. */
    public static List<Apprentice.Pick> pendingPicksForTest(UUID player) {
        Pending pd = PENDING.get(player);
        return pd == null ? List.of() : pd.offers();
    }

    /** Test seam: is a page waiting on this player, and how many offers does it hold. */
    public static int pendingOffers(UUID player) {
        Pending pd = PENDING.get(player);
        return pd == null ? -1 : pd.offers().size();
    }

    /** Test seam: the words a page holds for this player ("" for a Crafter, null when no page is open). */
    public static String pendingWords(UUID player) {
        Pending pd = PENDING.get(player);
        return pd == null ? null : pd.words();
    }

    /** Test seam: press a tile as the chest would. */
    public static void pressForTest(ServerPlayer p, String id) {
        press(p, id);
    }
}
