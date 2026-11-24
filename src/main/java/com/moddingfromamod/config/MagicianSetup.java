package com.moddingfromamod.config;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.moddingfromamod.ui.MenuKit;
import com.moddingfromamod.ui.PickPage;
import com.moddingfromamod.ui.TuneDialog;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>The Magician setup</b>: {@code /moddy magician}, for the single-player owner or the LAN host only (D18:
 * a guest wishes on the host's setup and never changes it; a dedicated server is set up in its file, D28). Built from
 * the two menu forms ({@code ui/MenuKit}): PICK pages for the choices and ONE TUNE dialog for how each model is paid
 * for.
 *
 * <p><b>The first page is one button per program found</b>: "Use my Claude subscription", "Use my Muse Code
 * subscription", "Use API keys" ({@link MagicianPresets}). One press sets the four choices, checks that program is
 * installed and signed in, runs the full check and turns the Magician on, with progress on the action bar and one
 * result line; on a failure it says the one thing to do. Python 3 for the Magician's own powers is checked beside the
 * program ({@link com.moddingfromamod.datapack.PythonCheck}): missing, the Magician still turns on and one more
 * line says what to install. "Choose models myself" opens the per-choice pages, where:
 *
 * <ol>
 *   <li><b>The programs are checked by themselves</b>: Claude Code and Muse Code, installed, signed in, answering
 *       ({@link HarnessProbe}), when the page opens and again whenever a choice changes.</li>
 *   <li><b>Pick the wish model and the helper model</b> from {@link SupportedModels}; a model whose program did not
 *       pass is shown grey with the reason ({@link MagicianOffer}).</li>
 *   <li><b>How each is paid for</b>: subscription, or an API key from its environment variable. The menu never takes a
 *       key; it names the variable and offers the API key only when it is set.</li>
 *   <li><b>Turn on (about 3 minutes)</b>: the programs again, then the check ({@link MagicianCheck}); Magician turns
 *       on only when it passes. A change made while it is off says so in chat, with a [Turn on] link.</li>
 * </ol>
 *
 * <p>Every change goes through {@link ModdySettings#change} (as {@code /moddy setting} does), so a lock, a guest or a
 * dedicated server is refused by the action itself, whatever the menu showed.
 */
public final class MagicianSetup {

    public static final String VERB = "magician";
    public static final String MENU = "magician_setup";
    public static final String TITLE = "Magician setup";

    static final String STATUS = "status";
    static final String PROGRAMS = "programs";
    static final String MAIN = "main";
    static final String CHEAP = "cheap";
    static final String PAY = "pay";
    static final String CHECK = "check";
    static final String OFF = "off";
    static final String CONTRIBUTOR = "contributor";
    static final String FALLBACK = "fallback";
    static final String CHEAP_FALLBACK = "cheap_fallback";
    static final String CLEAR = "clear";
    static final String MANUAL = "manual";
    /** {@code /moddy magician details}: the per-choice page (the chat prompt's [Turn on] link opens it). */
    static final String DETAIL = "details";
    static final String NONE_FOUND = "none_found";

    /** The detailed page's title (the first page is the one-button page, {@link #TITLE}). */
    public static final String DETAIL_TITLE = "Choose models myself";
    /** The detailed page's last button: probe the programs, run the check, turn on. */
    public static final String TURN_ON = "Turn on (about 3 minutes)";
    /** Said to a guest who presses the watch's Magician tile (D18). */
    public static final String HOST_ONLY = "Only the host sets up the Magician.";
    /** Said in chat after a choice changes while the Magician is off, before its [Turn on] link. */
    public static final String OFF_UNTIL_CHECKED = "Your Magician is off until it's checked again. ";
    public static final String TURN_ON_LINK = "[Turn on]";
    static final String BUSY = "Moddy is already checking; wait for it to finish.";

    private static final Map<SupportedModels.Harness, HarnessProbe.Result> probed =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile boolean probing;
    private static volatile boolean checking;

    private static volatile HarnessProbe probe = new CliHarnessProbe();
    /** What the last Python check found, or null before one. */
    private static volatile com.moddingfromamod.datapack.PythonCheck.Verdict python;
    private static volatile Function<String, String> env = System::getenv;
    private static volatile java.util.function.BiFunction<ServerPlayer, MagicianPicks, MagicianCheck.Steps> steps =
            (p, picks) -> new LiveCheckSteps(p.level().getServer(),
                    () -> p.level().getServer().getPlayerList().getPlayer(p.getUUID()),
                    picks);

    private MagicianSetup() {
    }

    // ── test seams ─────────────────────────────────────────────────────────────────────────────────────

    /** A test's programs, environment and check steps (null each puts the real one back). */
    public static void forTest(HarnessProbe p, Function<String, String> e,
                               java.util.function.BiFunction<ServerPlayer, MagicianPicks, MagicianCheck.Steps> s) {
        probe = p == null ? new CliHarnessProbe() : p;
        env = e == null ? System::getenv : e;
        steps = s == null ? (pl, picks) -> new LiveCheckSteps(pl.level().getServer(),
                () -> pl.level().getServer().getPlayerList().getPlayer(pl.getUUID()), picks) : s;
        probed.clear();
        python = null;
        probing = false;
        checking = false;
    }

    /** What the last program check found (empty before one). */
    public static Map<SupportedModels.Harness, HarnessProbe.Result> probed() {
        return Map.copyOf(probed);
    }

    /** What the last Python check found, or null before one. */
    public static com.moddingfromamod.datapack.PythonCheck.Verdict python() {
        return python;
    }

    public static boolean busy() {
        return probing || checking;
    }

    // ── who may open it ────────────────────────────────────────────────────────────────────────────────

    /** Why {@code p} may not use the setup, or empty when they may (the host, nothing locked, not a server). */
    static Optional<String> refusal(ServerPlayer p) {
        ModdySettings.Change c = ModdySettings.shared().decide(SettingsCatalog.MAIN_MODEL,
                SupportedModels.OPUS, SettingsCommand.who(p));
        return c.done() ? Optional.empty() : Optional.of(c.line());
    }

    /** {@code /moddy magician}: open the setup, or say why not. */
    public static boolean open(ServerPlayer p) {
        Optional<String> no = refusal(p);
        if (no.isPresent()) {
            say(p, ModdySettings.HOST_ONLY.equals(no.get()) ? HOST_ONLY : no.get(), false);
            return false;
        }
        MenuKit.pick(p, MENU, home(p), MagicianSetup::press);
        return true;
    }

    /** The per-choice pages' first page; its programs are checked straight away when they never were. */
    static boolean openDetail(ServerPlayer p) {
        if (refusal(p).isPresent()) {
            return open(p);
        }
        MenuKit.pick(p, MENU, detailPage(p), MagicianSetup::press);
        if (probed.isEmpty() && !busy()) {
            autoProbe(p, found());
        }
        return true;
    }

    /**
     * The wish watch's Magician tile, or empty when this player has none: the host reads "✪ Turn on the
     * Magician" (or "✪ Magician: on (Opus 5.5)"), a LAN guest "✪ Magician: off" while it is off (pressing it says
     * only the host sets it up), and a dedicated server or a locked setting shows nothing (set up in its file).
     * Reads settings only: nothing is looked for or run.
     */
    public static Optional<String> watchTile(ServerPlayer p) {
        Optional<String> no = refusal(p);
        boolean on = Magician.enabled();
        if (no.isEmpty()) {
            return Optional.of(on ? MagicianPresets.onTile(MagicianPicks.current()) : MagicianPresets.TURN_ON);
        }
        if (!on && ModdySettings.HOST_ONLY.equals(no.get())) {
            return Optional.of(MagicianPresets.OFF_GUEST);
        }
        return Optional.empty();
    }

    /** Which programs are on this computer: a file lookup, nothing run. Only once the host opened the setup. */
    static java.util.Set<SupportedModels.Harness> found() {
        java.util.Set<SupportedModels.Harness> out = java.util.EnumSet.noneOf(SupportedModels.Harness.class);
        for (SupportedModels.Harness h : SupportedModels.Harness.values()) {
            try {
                if (probe.installed(h)) {
                    out.add(h);
                }
            } catch (RuntimeException ignored) {
                // a lookup that throws found nothing
            }
        }
        return out;
    }

    // ── pages (built from state, so a test reads them without a client) ─────────────────────────────────

    /**
     * The first page: where the Magician stands, one button per program found, and "Choose models myself".
     */
    static PickPage home(ServerPlayer p) {
        boolean on = Magician.enabled();
        List<PickPage.Tile> tiles = new ArrayList<>();
        tiles.add(statusTile(on));
        List<MagicianPresets.Preset> offered = MagicianPresets.offered(found(), env, ServerSettings.dedicated());
        for (MagicianPresets.Preset pr : offered) {
            tiles.add(new PickPage.Tile(pr.id(), pr.harness() == SupportedModels.Harness.CLAUDE_CODE
                    ? "minecraft:amethyst_shard" : "minecraft:prismarine_crystals", busy() ? pr.label() + " (checking…)"
                    : pr.label(), pr.lore(), on && pr.picks().equals(MagicianPicks.current())));
        }
        if (offered.isEmpty()) {
            tiles.add(new PickPage.Tile(NONE_FOUND, PickPage.BLOCKED_ITEM, "No AI program found",
                    lines("Claude Code or Muse Code runs the Magician on your own subscription.",
                            MagicianPresets.NOTHING_FOUND), false));
        }
        tiles.add(new PickPage.Tile(MANUAL, "minecraft:writable_book", DETAIL_TITLE,
                lines("Pick each model, how it's paid for, and fallbacks."), false));
        if (on) {
            tiles.add(new PickPage.Tile(OFF, "minecraft:red_dye", "Turn Magician off",
                    lines("Back to the Apprentice and the Builder; no AI program runs."), false));
        }
        return PickPage.of(TITLE, tiles, false);
    }

    private static PickPage.Tile statusTile(boolean on) {
        String last = MagicianCheckRecord.shared().summary();
        return new PickPage.Tile(STATUS, on ? "minecraft:nether_star" : "minecraft:gray_dye",
                on ? "Magician is ON" : "Magician is off", lines(on
                        ? "Typed wishes go to " + MagicianPicks.current().main().map(MagicianPresets::shortLabel)
                        .orElse("the wish model") + "."
                        : "Typed wishes go to the Apprentice.", on ? "" : checking ? "The check is running…" : "",
                        last), on);
    }

    /** The per-choice page ("Choose models myself"). */
    static PickPage detailPage(ServerPlayer p) {
        MagicianPicks picks = MagicianPicks.current();
        boolean on = Magician.enabled();
        List<PickPage.Tile> tiles = new ArrayList<>();
        String last = MagicianCheckRecord.shared().summary();
        tiles.add(new PickPage.Tile(STATUS, on ? "minecraft:nether_star" : "minecraft:gray_dye",
                on ? "Magician is ON" : "Magician is off", lines(on
                        ? "Typed wishes go to the wish model."
                        : "Typed wishes go to the Apprentice.", last), on));
        List<String> prog = new ArrayList<>();
        if (probing) {
            prog.add("Checking…");
        } else if (probed.isEmpty()) {
            prog.add("Not checked yet: checked as you choose.");
        } else {
            for (SupportedModels.Harness h : SupportedModels.Harness.values()) {
                HarnessProbe.Result r = probed.get(h);
                prog.add(r == null ? h.label + ": not checked." : r.line());
            }
        }
        prog.add("Checked again whenever a choice changes; press to check now.");
        tiles.add(new PickPage.Tile(PROGRAMS, "minecraft:spyglass", "Claude Code and Muse Code", prog, false));
        tiles.add(new PickPage.Tile(MAIN, "minecraft:enchanted_book", "Wish model: "
                + picks.main().map(SupportedModels.Model::label).orElse("not chosen"),
                lines("Grants the wish. Effort is always low."), false));
        tiles.add(new PickPage.Tile(CHEAP, "minecraft:book", "Helper model: "
                + picks.cheap().map(SupportedModels.Model::label).orElse("not chosen"),
                lines("Safety grading, routing and lookups."), false));
        tiles.add(new PickPage.Tile(PAY, "minecraft:gold_ingot", "How each is paid for",
                lines("Wish model: " + picks.mainPay().map(m -> m.label).orElse("not chosen"),
                        "Helper model: " + picks.cheapPay().map(m -> m.label).orElse("not chosen")), false));
        tiles.add(new PickPage.Tile(FALLBACK, "minecraft:hopper", "If the wish model runs out",
                fallbackLore(SettingsCatalog.MAIN_FALLBACK, SupportedModels.Role.MAIN), false));
        tiles.add(new PickPage.Tile(CHEAP_FALLBACK, "minecraft:hopper", "If the helper model runs out",
                fallbackLore(SettingsCatalog.CHEAP_FALLBACK, SupportedModels.Role.CHEAP), false));
        List<String> problems = picks.problems(env, ServerSettings.dedicated());
        if (on) {
            tiles.add(new PickPage.Tile(OFF, "minecraft:red_dye", "Turn Magician off",
                    lines("Back to the Apprentice and the Builder; no AI program runs."), false));
        } else if (checking) {
            tiles.add(new PickPage.Tile(CHECK, "minecraft:clock", "The check is running…", List.of(), false));
        } else if (problems.isEmpty()) {
            tiles.add(new PickPage.Tile(CHECK, "minecraft:lime_dye", TURN_ON,
                    lines("Checks the programs, every safety grader, one forced refusal and one small wish.",
                            "Costs a few cents plus one wish."), false));
        } else {
            tiles.add(new PickPage.Tile(CHECK, PickPage.BLOCKED_ITEM, TURN_ON, problems, false));
        }
        return PickPage.of(DETAIL_TITLE, tiles, true);
    }

    static PickPage mainPage() {
        MagicianPicks picks = MagicianPicks.current();
        boolean dedicated = ServerSettings.dedicated();
        List<PickPage.Tile> tiles = new ArrayList<>();
        for (SupportedModels.Model m : SupportedModels.of(SupportedModels.Role.MAIN)) {
            if (m.id().equals(SupportedModels.SPARK_CONTRIBUTOR)) {
                continue;   // the Contributor toggle below, not a model of its own
            }
            boolean selected = picks.mainModel().equals(m.id())
                    || m.id().equals(SupportedModels.SPARK) && picks.mainModel().equals(SupportedModels.SPARK_CONTRIBUTOR);
            tiles.add(modelTile(m, selected, MagicianOffer.blocked(m, probed, env, dedicated)));
        }
        boolean spark = picks.mainModel().startsWith(SupportedModels.SPARK);
        if (spark) {
            boolean contributor = picks.mainModel().equals(SupportedModels.SPARK_CONTRIBUTOR);
            tiles.add(new PickPage.Tile(CONTRIBUTOR, contributor ? "minecraft:lime_dye" : "minecraft:gray_dye",
                    "Contributor: " + (contributor ? "on" : "off"), lines("Spark's contributor tier: "
                    + SupportedModels.find(SupportedModels.SPARK_CONTRIBUTOR).orElseThrow().apiPrice() + "."),
                    contributor));
        }
        return PickPage.of("Wish model", tiles, true);
    }

    private static List<String> fallbackLore(String key, SupportedModels.Role role) {
        var list = com.moddingfromamod.agent.ModelFallback.parse(ModdySettings.get(key).orElse("none"), role).entries();
        List<String> out = new ArrayList<>();
        if (list.isEmpty()) {
            out.add("No fallback: when its quota or window runs out, wishes wait.");
        } else {
            for (int i = 0; i < list.size(); i++) {
                out.add((i + 1) + ". " + list.get(i).labelWithPrice());
            }
        }
        out.add("Tried in order when it is out of quota, rate-limited or its sign-in fails.");
        return out;
    }

    /**
     * The fallback page: every model and way to pay for {@code role}, the pick itself left out. A selected
     * tile is in the list, numbered in the order it is tried; pressing one adds it at the end or takes it out. A paid
     * one shows its price before it is chosen (D27).
     */
    static PickPage fallbackPage(SupportedModels.Role role) {
        MagicianPicks picks = MagicianPicks.current();
        String key = role == SupportedModels.Role.MAIN ? SettingsCatalog.MAIN_FALLBACK : SettingsCatalog.CHEAP_FALLBACK;
        var list = com.moddingfromamod.agent.ModelFallback.parse(ModdySettings.get(key).orElse("none"), role).entries();
        String primary = role == SupportedModels.Role.MAIN ? picks.mainModel() + ":" + picks.mainMode()
                : picks.cheapModel() + ":" + picks.cheapMode();
        boolean dedicated = ServerSettings.dedicated();
        List<PickPage.Tile> tiles = new ArrayList<>();
        for (SupportedModels.Model m : SupportedModels.of(role)) {
            for (SupportedModels.Mode mode : SupportedModels.Mode.values()) {
                var e = new com.moddingfromamod.agent.ModelFallback.Entry(m, mode);
                if (e.key().equals(primary)) {
                    continue;
                }
                int at = -1;
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i).key().equals(e.key())) {
                        at = i;
                    }
                }
                Optional<String> blocked = MagicianOffer.blocked(m, mode, probed, env, dedicated);
                List<String> lore = new ArrayList<>();
                blocked.ifPresent(lore::add);
                lore.add(mode == SupportedModels.Mode.API ? "Charged to your key: " + m.apiPrice() + "."
                        : "Your " + e.harness().label + " plan: no charge per wish.");
                if (at >= 0) {
                    lore.add("Tried " + ordinal(at + 1) + ". Press to take it out.");
                }
                tiles.add(new PickPage.Tile(e.key(), blocked.isPresent() && at < 0 ? PickPage.BLOCKED_ITEM
                        : mode == SupportedModels.Mode.API ? "minecraft:gold_nugget" : "minecraft:iron_nugget",
                        (at >= 0 ? (at + 1) + ". " : "") + m.label() + ", " + mode.label, lore, at >= 0));
            }
        }
        tiles.add(new PickPage.Tile(CLEAR, "minecraft:barrier", "No fallback", lines("Take every fallback out."),
                list.isEmpty()));
        return PickPage.of(role == SupportedModels.Role.MAIN ? "Wish model fallbacks" : "Helper model fallbacks",
                tiles, true);
    }

    private static String ordinal(int n) {
        return n == 1 ? "first" : n == 2 ? "second" : n == 3 ? "third" : n + "th";
    }

    /** Press a fallback tile: add it at the end, or take it out; {@link #CLEAR} empties the list. */
    static void pressFallback(ServerPlayer p, SupportedModels.Role role, String id) {
        if (PickPage.BACK_ID.equals(id)) {
            openDetail(p);
            return;
        }
        String key = role == SupportedModels.Role.MAIN ? SettingsCatalog.MAIN_FALLBACK : SettingsCatalog.CHEAP_FALLBACK;
        var list = new ArrayList<>(com.moddingfromamod.agent.ModelFallback
                .parse(ModdySettings.get(key).orElse("none"), role).entries());
        if (CLEAR.equals(id)) {
            list.clear();
        } else {
            Optional<com.moddingfromamod.agent.ModelFallback.Entry> e = com.moddingfromamod.agent.ModelFallback.entry(id,
                    role);
            if (e.isEmpty()) {
                return;
            }
            if (!list.removeIf(x -> x.key().equals(e.get().key()))) {
                Optional<String> blocked = MagicianOffer.blocked(e.get().model(), e.get().mode(), probed, env,
                        ServerSettings.dedicated());
                if (blocked.isPresent()) {
                    say(p, blocked.get(), false);
                    return;
                }
                list.add(e.get());
                if (e.get().paid()) {
                    say(p, e.get().model().label() + " with your API key costs " + e.get().model().apiPrice()
                            + ", charged to " + e.get().model().provider().keyVar + " only when Moddy falls back to it.",
                            true);
                }
            }
        }
        String value = com.moddingfromamod.agent.ModelFallback.format(list);
        if (!value.equals(ModdySettings.get(key).orElse("none"))) {
            changed(p, key, value);
        }
        MenuKit.pick(p, MENU, fallbackPage(role), (pl, i) -> pressFallback(pl, role, i));
    }

    static PickPage cheapPage() {
        MagicianPicks picks = MagicianPicks.current();
        List<PickPage.Tile> tiles = new ArrayList<>();
        for (SupportedModels.Model m : SupportedModels.of(SupportedModels.Role.CHEAP)) {
            tiles.add(modelTile(m, picks.cheapModel().equals(m.id()),
                    MagicianOffer.blocked(m, probed, env, ServerSettings.dedicated())));
        }
        return PickPage.of("Helper model", tiles, true);
    }

    private static PickPage.Tile modelTile(SupportedModels.Model m, boolean selected, Optional<String> blocked) {
        List<String> lore = new ArrayList<>();
        blocked.ifPresent(lore::add);
        lore.add(m.provider().label + ". API key: " + m.apiPrice() + ".");
        return new PickPage.Tile(m.id(), blocked.isPresent() ? PickPage.BLOCKED_ITEM
                : m.provider() == SupportedModels.Provider.ANTHROPIC ? "minecraft:amethyst_shard" : "minecraft:prismarine_crystals",
                m.label(), lore, selected);
    }

    /** The one TUNE dialog: how the wish model and the helper model are paid for. */
    static TuneDialog payDialog() {
        MagicianPicks picks = MagicianPicks.current();
        boolean dedicated = ServerSettings.dedicated();
        List<TuneDialog.Input> inputs = new ArrayList<>();
        inputs.add(payChoice("main_mode", "Wish model", picks.main(), picks.mainMode(), dedicated));
        inputs.add(payChoice("cheap_mode", "Helper model", picks.cheap(), picks.cheapMode(), dedicated));
        List<TuneDialog.Line> body = new ArrayList<>();
        body.add(new TuneDialog.Line(keyLine(SupportedModels.Provider.ANTHROPIC) + " "
                + keyLine(SupportedModels.Provider.MUSE)));
        body.add(new TuneDialog.Line("Keys are never typed here. Your terms are yours."));
        return new TuneDialog("How each is paid for", body, inputs, "Save", "moddy " + VERB + " pay");
    }

    private static String keyLine(SupportedModels.Provider p) {
        String v = env.apply(p.keyVar);
        return p.keyVar + (v == null || v.isBlank() ? ": unset." : ": set.");
    }

    private static TuneDialog.Choice payChoice(String key, String label, Optional<SupportedModels.Model> model,
                                               String current, boolean dedicated) {
        List<TuneDialog.Option> options = new ArrayList<>();
        if (model.isPresent()) {
            for (SupportedModels.Mode mode : SupportedModels.Mode.values()) {
                if (MagicianOffer.blocked(model.get(), mode, probed, env, dedicated).isEmpty()) {
                    options.add(new TuneDialog.Option(mode.id, label + ": " + mode.label
                            + (mode == SupportedModels.Mode.API ? " (" + model.get().provider().keyVar + ")" : "")));
                }
            }
        }
        if (options.isEmpty()) {
            options.add(new TuneDialog.Option("none", label + ": " + (model.isEmpty() ? "pick the model first"
                    : "no way to pay works here yet")));
        }
        String initial = options.stream().anyMatch(o -> o.id().equals(current)) ? current : options.get(0).id();
        return TuneDialog.Choice.wide(key, label, options, initial, 300);
    }

    /** Everything standing between these picks and the check, in plain words. */
    static List<String> problems(MagicianPicks picks) {
        List<String> out = new ArrayList<>(picks.problems(env, ServerSettings.dedicated()));
        if (out.isEmpty()) {
            for (var pair : List.of(Map.entry(picks.main().get(), picks.mainPay().get()),
                    Map.entry(picks.cheap().get(), picks.cheapPay().get()))) {
                MagicianOffer.blocked(pair.getKey(), pair.getValue(), probed, env, ServerSettings.dedicated())
                        .ifPresent(out::add);
            }
        }
        return out.stream().distinct().toList();
    }

    // ── presses ────────────────────────────────────────────────────────────────────────────────────────

    static void press(ServerPlayer p, String id) {
        if (refusal(p).isPresent()) {
            say(p, refusal(p).get(), false);
            p.closeContainer();
            return;
        }
        Optional<MagicianPresets.Preset> preset = MagicianPresets.find(id, found(), env, ServerSettings.dedicated());
        if (preset.isPresent()) {
            quick(p, preset.get());
            return;
        }
        switch (id) {
            case MANUAL -> openDetail(p);
            case NONE_FOUND -> say(p, MagicianPresets.NOTHING_FOUND, false);
            case STATUS -> open(p);
            case PROGRAMS -> checkPrograms(p);
            case MAIN -> MenuKit.pick(p, MENU, mainPage(), MagicianSetup::pressMain);
            case CHEAP -> MenuKit.pick(p, MENU, cheapPage(), MagicianSetup::pressCheap);
            case FALLBACK -> MenuKit.pick(p, MENU, fallbackPage(SupportedModels.Role.MAIN),
                    (pl, i) -> pressFallback(pl, SupportedModels.Role.MAIN, i));
            case CHEAP_FALLBACK -> MenuKit.pick(p, MENU, fallbackPage(SupportedModels.Role.CHEAP),
                    (pl, i) -> pressFallback(pl, SupportedModels.Role.CHEAP, i));
            case PAY -> {
                p.closeContainer();
                MenuKit.tune(p, payDialog());
            }
            case CHECK -> turnOn(p, null);
            case OFF -> {
                SettingsCommand.change(p, SettingsCatalog.MAGICIAN, "off");
                stopIfOff();
                open(p);
            }
            default -> open(p);
        }
    }

    static void pressMain(ServerPlayer p, String id) {
        if (PickPage.BACK_ID.equals(id)) {
            openDetail(p);
            return;
        }
        MagicianPicks picks = MagicianPicks.current();
        String value = id;
        if (CONTRIBUTOR.equals(id)) {
            value = picks.mainModel().equals(SupportedModels.SPARK_CONTRIBUTOR) ? SupportedModels.SPARK
                    : SupportedModels.SPARK_CONTRIBUTOR;
        } else {
            Optional<SupportedModels.Model> m = SupportedModels.find(id);
            Optional<String> blocked = m.flatMap(mm -> MagicianOffer.blocked(mm, probed, env, ServerSettings.dedicated()));
            if (blocked.isPresent()) {
                say(p, blocked.get(), false);
                return;
            }
        }
        if (!value.equals(picks.mainModel())) {
            changed(p, SettingsCatalog.MAIN_MODEL, value);
        }
        MenuKit.pick(p, MENU, mainPage(), MagicianSetup::pressMain);
    }

    static void pressCheap(ServerPlayer p, String id) {
        if (PickPage.BACK_ID.equals(id)) {
            openDetail(p);
            return;
        }
        Optional<String> blocked = SupportedModels.find(id)
                .flatMap(m -> MagicianOffer.blocked(m, probed, env, ServerSettings.dedicated()));
        if (blocked.isPresent()) {
            say(p, blocked.get(), false);
            return;
        }
        if (!id.equals(MagicianPicks.current().cheapModel())) {
            changed(p, SettingsCatalog.CHEAP_MODEL, id);
        }
        MenuKit.pick(p, MENU, cheapPage(), MagicianSetup::pressCheap);
    }

    /** The TUNE dialog's Save: {@code /moddy magician pay <main> <cheap>}. */
    static void pay(ServerPlayer p, String main, String cheap) {
        MagicianPicks picks = MagicianPicks.current();
        if (!"none".equals(main) && !main.equals(picks.mainMode())) {
            changed(p, SettingsCatalog.MAIN_MODE, main);
        }
        if (!"none".equals(cheap) && !cheap.equals(picks.cheapMode())) {
            changed(p, SettingsCatalog.CHEAP_MODE, cheap);
        }
        openDetail(p);
    }

    /**
     * A choice on the per-choice pages changed: save it as {@code /moddy setting} does, check the programs the
     * choices now need straight away (seconds, no button), and, with the Magician off, say so with a [Turn on] link.
     * A pick that moves the wish workers to another program stops the old program's pool: the save goes
     * through {@code ModdySettings.change}, whose pick hook calls {@code AgentSession.stopIfProviderChanged()}.
     */
    static void changed(ServerPlayer p, String key, String value) {
        if (!SettingsCommand.change(p, key, value)) {
            return;
        }
        if (!Magician.enabled()) {
            p.sendSystemMessage(Component.literal(OFF_UNTIL_CHECKED).withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal(TURN_ON_LINK).withStyle(s -> s.withColor(ChatFormatting.GREEN)
                            .withUnderlined(true)
                            .withClickEvent(com.moddingfromamod.compat.ChatEvents.click(
                                    net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND,
                                    "/" + com.moddingfromamod.wish.ChildCommands.MODDY + " " + VERB + " " + DETAIL)))));
        }
        if (!busy()) {
            java.util.Set<SupportedModels.Harness> need = needed(MagicianPicks.current());
            need.retainAll(found());
            if (!need.isEmpty()) {
                autoProbe(p, need);
            }
        }
    }

    /** The programs these choices run on (a choice not made yet needs none). */
    static java.util.Set<SupportedModels.Harness> needed(MagicianPicks picks) {
        java.util.Set<SupportedModels.Harness> out = java.util.EnumSet.noneOf(SupportedModels.Harness.class);
        picks.main().ifPresent(m -> out.add(SupportedModels.harness(m,
                picks.mainPay().orElse(SupportedModels.Mode.SUBSCRIPTION))));
        picks.cheap().ifPresent(m -> out.add(SupportedModels.harness(m,
                picks.cheapPay().orElse(SupportedModels.Mode.SUBSCRIPTION))));
        return out;
    }

    // ── the program check and the end-to-end check ─────────────────────────────────────────────────────

    /** The programs tile on the per-choice page: check both now. */
    static void checkPrograms(ServerPlayer p) {
        if (busy()) {
            say(p, BUSY, false);
            return;
        }
        autoProbe(p, java.util.EnumSet.allOf(SupportedModels.Harness.class));
    }

    /**
     * Check {@code which} programs off the server thread (seconds, a fraction of a cent), then say what each answered
     * and redraw the per-choice page if the host is still on it. Only ever after the host pressed something in the
     * setup: with the Magician off nothing probes by itself ({@code MagicianOffTest}).
     */
    static void autoProbe(ServerPlayer p, java.util.Set<SupportedModels.Harness> which) {
        probe(p, which, (back, found) -> {
            found.values().forEach(r -> say(back, r.line(), r.installed()));
            if (python != null) {
                say(back, com.moddingfromamod.datapack.PythonCheck.statusLine(python,
                        com.moddingfromamod.datapack.PythonCheck.windows()), python.works());
            }
            if (back.containerMenu instanceof MenuKit.PickMenu pm && DETAIL_TITLE.equals(pm.page().title())) {
                MenuKit.pick(back, MENU, detailPage(back), MagicianSetup::press);
            }
        });
    }

    /** Probe {@code which}, then hand what was found to {@code then} on the server thread (if the player is still on). */
    private static void probe(ServerPlayer p, java.util.Set<SupportedModels.Harness> which,
                              java.util.function.BiConsumer<ServerPlayer, Map<SupportedModels.Harness,
                                      HarnessProbe.Result>> then) {
        probing = true;
        var server = p.level().getServer();
        java.util.UUID who = p.getUUID();
        List<SupportedModels.Harness> list = List.copyOf(which);
        HarnessProbe pr = probe;
        Function<String, String> e = env;
        Thread t = new Thread(() -> {
            Map<SupportedModels.Harness, HarnessProbe.Result> found = new EnumMap<>(SupportedModels.Harness.class);
            com.moddingfromamod.datapack.PythonCheck.Verdict[] py = {null};
            try {
                Magician.during(() -> {
                    for (SupportedModels.Harness h : list) {
                        try {
                            found.put(h, pr.probe(h, e));
                        } catch (RuntimeException ex) {
                            found.put(h, HarnessProbe.Result.missing(h));
                        }
                    }
                    // The powers' Python: checked beside the programs, never blocking the Magician.
                    try {
                        py[0] = pr.python();
                    } catch (RuntimeException ex) {
                        py[0] = null;
                    }
                    return null;
                });
            } finally {
                server.execute(() -> {
                    probed.putAll(found);
                    if (py[0] != null) {
                        python = py[0];
                    }
                    probing = false;
                    ServerPlayer back = server.getPlayerList().getPlayer(who);
                    if (back != null) {
                        then.accept(back, found);
                    }
                });
            }
        }, "mfam-magician-probe");
        t.setDaemon(true);
        t.start();
    }

    /**
     * <b>One button</b>: set the four choices of {@code preset}, then {@link #turnOn}. The choices are saved
     * quietly (one result line at the end, not four "Set …" lines); a refusal (a guest, a lock) stops it with its reason.
     */
    static void quick(ServerPlayer p, MagicianPresets.Preset preset) {
        if (busy()) {
            say(p, BUSY, false);
            return;
        }
        MagicianPicks want = preset.picks();
        MagicianPicks now = MagicianPicks.current();
        ModdySettings.Who who = SettingsCommand.who(p);
        String[][] sets = {{SettingsCatalog.MAIN_MODEL, want.mainModel(), now.mainModel()},
                {SettingsCatalog.MAIN_MODE, want.mainMode(), now.mainMode()},
                {SettingsCatalog.CHEAP_MODEL, want.cheapModel(), now.cheapModel()},
                {SettingsCatalog.CHEAP_MODE, want.cheapMode(), now.cheapMode()}};
        for (String[] kv : sets) {
            if (!kv[1].equals(kv[2])) {
                ModdySettings.Change c = ModdySettings.shared().change(kv[0], kv[1], who);
                if (!c.done()) {
                    say(p, c.line(), false);
                    return;
                }
            }
        }
        org.slf4j.LoggerFactory.getLogger(com.moddingfromamod.ModdingFromAModMod.MODID)
                .info("magician: {} pressed '{}' ({})", p.getName().getString(), preset.label(), want.fingerprint());
        if (Magician.enabled() && MagicianCheckRecord.shared().passedFor(want)) {
            p.closeContainer();
            say(p, MagicianPresets.turnedOn(preset), true);
            if (python != null && !python.works()) {
                recheckPython(p);   // pressed again after installing Python: look again, the full check is not needed
            }
            return;
        }
        turnOn(p, preset);
    }

    /**
     * <b>Turn on</b>: check the programs these choices need (installed, signed in), then the full check, then turn the
     * Magician on. Progress on the action bar; on a failure, the one thing to do. {@code preset} is the one-button
     * choice that set them (its result line names it), or null from the per-choice page.
     */
    static void turnOn(ServerPlayer p, MagicianPresets.Preset preset) {
        if (busy()) {
            say(p, BUSY, false);
            return;
        }
        MagicianPicks picks = MagicianPicks.current();
        List<String> problems = picks.problems(env, ServerSettings.dedicated());
        if (!problems.isEmpty()) {
            say(p, problems.get(0), false);
            return;
        }
        p.closeContainer();
        java.util.Set<SupportedModels.Harness> need = needed(picks);
        say(p, "✪ Setting up the Magician with " + (preset != null ? preset.says() : picks.describe())
                + ". Moddy checks everything (about 3 minutes, once); keep playing.", true);
        progress(p, 1, "checking " + need.stream().map(h -> h.label).collect(java.util.stream.Collectors.joining(
                " and ")) + " (installed, signed in)…");
        probe(p, need, (back, found) -> {
            Optional<String> todo = preset != null ? MagicianPresets.whatToDo(preset, found.get(preset.harness()))
                    : problems(picks).stream().findFirst();
            if (todo.isPresent()) {
                String line = todo.get().startsWith("✖") ? todo.get() : "✖ " + todo.get();
                progress(back, 1, "stopped.");
                say(back, line, false);
                return;
            }
            runCheck(back, preset);
        });
    }

    /** Look for Python again and say what was found: the Python line, or that powers work now. */
    static void recheckPython(ServerPlayer p) {
        probing = true;
        var server = p.level().getServer();
        java.util.UUID who = p.getUUID();
        HarnessProbe pr = probe;
        Thread t = new Thread(() -> {
            com.moddingfromamod.datapack.PythonCheck.Verdict[] py = {null};
            try {
                py[0] = pr.python();
            } catch (RuntimeException ex) {
                py[0] = null;
            } finally {
                server.execute(() -> {
                    if (py[0] != null) {
                        python = py[0];
                    }
                    probing = false;
                    ServerPlayer back = server.getPlayerList().getPlayer(who);
                    if (back != null && py[0] != null) {
                        sayPython(back, true);
                    }
                });
            }
        }, "mfam-magician-python");
        t.setDaemon(true);
        t.start();
    }

    /**
     * The Python result line: the one thing to do when Python is missing, never blocking the Magician. With
     * {@code sayGood}, a working Python says so too (after a re-check); otherwise a working Python says nothing.
     */
    static void sayPython(ServerPlayer p, boolean sayGood) {
        com.moddingfromamod.datapack.PythonCheck.Verdict v = python;
        if (v == null) {
            return;
        }
        Optional<String> line = com.moddingfromamod.datapack.PythonCheck.setupLine(v,
                com.moddingfromamod.datapack.PythonCheck.windows());
        if (line.isPresent()) {
            say(p, line.get(), false);
        } else if (sayGood) {
            say(p, "✔ Python " + v.version() + " found: the Magician can give things powers now.", true);
        }
    }

    /** The action-bar line for a step of the setup. */
    static void progress(ServerPlayer p, int step, String words) {
        p.displayClientMessage(Component.literal(MagicianPresets.progress(step, words)).withStyle(ChatFormatting.AQUA),
                true);
    }

    static void runCheck(ServerPlayer p, MagicianPresets.Preset preset) {
        if (busy()) {
            say(p, BUSY, false);
            return;
        }
        MagicianPicks picks = MagicianPicks.current();
        List<String> problems = problems(picks);
        if (!problems.isEmpty()) {
            say(p, problems.get(0), false);
            return;
        }
        checking = true;
        p.closeContainer();
        var server = p.level().getServer();
        java.util.UUID who = p.getUUID();
        progress(p, 2, "the Magician check (about 3 minutes)…");
        Magician.beginCheck();   // held until the result is applied on the server thread (no gap with the pool up)
        int[] done = {0};
        MagicianCheck.run(steps.apply(p, picks),
                step -> server.execute(() -> {
                    ServerPlayer back = server.getPlayerList().getPlayer(who);
                    done[0]++;
                    if (back != null) {
                        progress(back, 2, "the Magician check, " + done[0] + " of 6: " + (step.pass() ? "✔ " : "✖ ")
                                + step.name() + (step.pass() && done[0] == 5 ? ". Next: one small test wish…" : "."));
                    }
                }),
                report -> server.execute(() -> {
                    try {
                        finishCheck(server, who, picks, report, preset);
                    } finally {
                        checking = false;
                        Magician.endCheck();
                        stopIfOff();
                    }
                }));
    }

    private static void finishCheck(net.minecraft.server.MinecraftServer server, java.util.UUID who,
                                    MagicianPicks picks, MagicianCheck.Report report, MagicianPresets.Preset preset) {
        boolean stillSame = picks.fingerprint().equals(MagicianPicks.current().fingerprint());
        MagicianCheckRecord.shared().record(picks, report.pass() && stillSame, report.summary());
        org.slf4j.LoggerFactory.getLogger(com.moddingfromamod.ModdingFromAModMod.MODID)
                .info("magician: check {} for {}: {}", report.pass() ? "PASSED" : "FAILED", picks.fingerprint(),
                        report.summary());
        ServerPlayer back = server.getPlayerList().getPlayer(who);
        if (back == null) {
            return;
        }
        if (report.pass() && stillSame) {
            ModdySettings.Change c = ModdySettings.shared().change(SettingsCatalog.MAGICIAN, "on",
                    SettingsCommand.who(back));
            progress(back, 3, c.done() ? "on ✔" : "stopped.");
            say(back, c.done() ? (preset != null ? MagicianPresets.turnedOn(preset)
                    : "✪ The Magician is on: " + picks.describe() + ". " + report.summary()) : c.line(), c.done());
            if (c.done()) {
                sayPython(back, false);
            }
        } else if (report.pass()) {
            progress(back, 2, "stopped.");
            say(back, "✖ A choice changed while the check ran. Press Turn on again.", false);
        } else {
            progress(back, 2, "stopped.");
            say(back, MagicianPresets.checkFailed(report), false);
        }
    }

    /** With Magician off and no check running, no worker stays up (off means nothing runs). */
    static void stopIfOff() {
        if (!Magician.allowsAi() && !com.moddingfromamod.RunContext.isTestRun()) {
            com.moddingfromamod.agent.AgentSession.stop();
        }
    }

    // ── the command ────────────────────────────────────────────────────────────────────────────────────

    public static LiteralArgumentBuilder<CommandSourceStack> branch() {
        return Commands.literal(VERB)
                .executes(ctx -> open(ctx.getSource().getPlayerOrException()) ? 1 : 0)
                .then(Commands.literal(DETAIL).executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    return refusal(p).isPresent() ? (open(p) ? 1 : 0) : (openDetail(p) ? 1 : 0);
                }))
                .then(Commands.literal(PAY)
                        .then(Commands.argument("main", StringArgumentType.word())
                                .then(Commands.argument("cheap", StringArgumentType.word())
                                        .executes(ctx -> {
                                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                                            if (refusal(p).isPresent()) {
                                                say(p, refusal(p).get(), false);
                                                return 0;
                                            }
                                            pay(p, StringArgumentType.getString(ctx, "main"),
                                                    StringArgumentType.getString(ctx, "cheap"));
                                            return 1;
                                        }))));
    }

    private static List<String> lines(String... ls) {
        List<String> out = new ArrayList<>();
        for (String l : ls) {
            if (l != null && !l.isBlank()) {
                out.add(l);
            }
        }
        return out;
    }

    static void say(ServerPlayer p, String line, boolean good) {
        p.sendSystemMessage(Component.literal(line).withStyle(good ? ChatFormatting.AQUA : ChatFormatting.YELLOW));
    }
}
