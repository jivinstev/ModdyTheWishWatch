# Building on Moddy

How to add to Moddy the Wish Watch without changing its code: plug-ins, the hooks a plug-in fills, the settings, and
the dedicated server's configuration file. Everything here is in the public mod, and every hook's behaviour with no
plug-in is the plain single-player game.

There are two kinds of hook. The **documented extension points** below are ones we invite you to build on, and
we try to keep them stable. The **internal seams** listed after them exist for our own servers; they may change
without notice, so do not build on them. A Gate A test (`ExtensionPointDocTest`) keeps this page in step with the
code: every hook the core reads is either a section here or a row in the internal list.

## How a plug-in is found

A plug-in is a class implementing `com.moddingfromamod.plugin.ModdyPlugin`:

```java
public final class EmeraldPlugin implements ModdyPlugin {
    @Override public String name() { return "emerald-wishes"; }          // for the boot log

    @Override public void register(Plugins plugins) {
        plugins.add(WishCharge.class, new EmeraldWishCharge());       // one line per hook you fill
    }
}
```

- **Listing.** Name the class, one per line, in `META-INF/services/com.moddingfromamod.plugin.ModdyPlugin` on the
  classpath. Moddy finds it with Java's `ServiceLoader`. The public mod ships that file empty.
- **When.** Every listed plug-in's `register` is called **once, from the mod's constructor**, before any world or
  server exists. Register hooks there and do nothing else: no registry access, no reading a world, no network.
- **Order.** Plug-ins register in the order `ServiceLoader` finds them (the order of the lines in the services
  file), and each hook keeps registration order.
- **`all` and `first`.** For each hook, the section below says how the core reads it:
  - **`all`**: every registration is asked, in order (help lines, command branches, world module types);
  - **`first`**: only the earliest registration answers and later ones are ignored (`WishCharge`). Two plug-ins
    filling a `first` hook is a configuration mistake; the log does not warn about it.
- **Failure.** A plug-in whose `register` throws (any `RuntimeException` or `LinkageError`), or a listed class
  that cannot be loaded, is logged (`plugin FAILED to load: …`) and skipped. The rest of Moddy starts as if it
  were absent. The boot log names every plug-in that loaded, or says `plugins: none (the public single-player mod)`.
- **Your own listeners.** A plug-in that needs Minecraft or NeoForge events (a tick handler, its own items) should
  also be an ordinary NeoForge mod and register them there. Moddy's hooks are only for the questions the core asks.

## Testing a plug-in

- **Unit tests** (JUnit): anything your hook decides without Minecraft. `Plugins` is plain Java: `new Plugins()`,
  `add`, then `all`/`first`, so a registry built in a test never touches the game's.
- **GameTests** drive the core's real entry points with your hook in place. Two seams on the shared registry
  (`Plugins.shared()`), both process-wide, so give each test a `batch` of its own and always undo them when the test
  ends (in a `GameTestListener`, on pass and on fail):
  - `Plugins.shared().replaceForTest(Hook.class, impl)`: answer as if `impl` were the only implementation
    (`null` puts the registered ones back);
  - `Plugins.shared().hideForTest(Hook.class, true)`: answer as if nothing were registered, which is how the core
    runs in the public mod (`false` shows them again).
- **The worked example** is `src/main/java/com/moddingfromamod/test/example/`: `EmeraldWishCharge` (every wish costs
  one emerald), `EmeraldPlugin` (its registration) and `EmeraldWishChargeGameTest`, which presses a recipe through
  the real `WishService.submit` twice: one emerald pays for the first, and the second is refused in the plug-in's
  words. It is test code and never ships in the jar.

## Documented extension points

### `WishCharge`
*`com.moddingfromamod.wish.WishCharge`, read with `first`.* **A wish economy**: what a wish costs, whether a player can
afford it, charging and refunding, and the words a menu, the guide book and `/moddy wishes` show about it.

- **Default (nothing registered):** every wish is free and nothing about prices is ever said. `/moddy wishes`
  answers "✨ Wish away!". Every method has this default, so implement only what you need.
- **Which charge is asked:** the running world module's own, when it returns one from `WorldModule.charge()`, else
  the first registered `WishCharge`, else the default. A level that prices only its own worlds returns its charge from
  its module, so two levels in one game never price each other's worlds.
- **What the core asks, and when:**

| Method | Asked | Thread |
|---|---|---|
| `refusesAtSubmit(player, distress, text, recipe)` | as a wish is submitted, before anything runs; `true` refuses it (say why to the player yourself). Never refuse a child in `distress`: the core passes it so you can let that wish through. `recipe` is true for a wish served with no model: a button, a "make another one" (of a Builder make too, whose `text` is then the thing's own name), a Builder make (whose `text` starts `Builder: `) | server |
| `waiveIfEmpty(player, wishId)` | a wish let through on nothing because of distress: never charge it | server |
| `charge(player, wishId, agent)` | a wish served with no model starts (a button, a recipe, a Builder make): `agent` is false | server |
| `atPickup(wish)` | the AI picks a wish up, after the safety and world-rule checks | **an agent thread**: do not touch the world directly; hop to the server thread (`server.submit(…).join()`) as the example does |
| `chargeAgain` / `afterAgain` / `refundAgain` | the three "again" presses (`Again.GIVE_ONE`, `PART`, `WHOLE`): charged before anything is handed over, refunded if nothing landed | server |
| `refundUndone(player, wish)` | 👎 undid a wish; return the line saying the new balance, or `null` | server |
| `onStatus(before, after)` | every status change in the wish book (refund an ungranted charge here) | server |
| `priced`, `wishPrice`, `guideValues`, `panelTitle`, `composeTitle`, `wishesCommand`, `preview`, `loreLine`, `showBalance` | when a menu, the guide book or `/moddy wishes` is drawn | server |

- A charge that cannot be paid returns `new Paid(true, () -> "the words to say")` (or `AgainPaid(true, token)`);
  the core then starts nothing and says the line.
- **Example:** `EmeraldWishCharge` above, about forty lines.

### `WorldModuleType`
*`com.moddingfromamod.world.WorldModuleType`, read with `all`.* **A kind of world**: code that runs for one world and
that no world-rules entry can express (its own bases, onboarding on join, a tick, extra help lines). The module
itself implements `com.moddingfromamod.world.WorldModule`.

- **Default:** every world runs with no module.
- **Which module runs:** read once, when the server has started (`ServerStartedEvent`), on the server thread. The
  JVM property `-Dmoddingfromamod.world.module=<id>` names one (`none` says no module); with no property, the first
  registered type whose `claims(server)` is true runs (this world was made as one of its worlds: its world type, its
  packs in the world's data configuration, a marker in its saved data; asked per world, so one game can hold a level's
  world and a plain one); then the first whose `impliedWhenUnnamed()` is true. An id nobody registered is logged as unknown and the
  world runs with none. Two types with the same `id()`: the first registered wins.
- **The module's methods** (`start`, `stop`, `onLogin`, `onLogout`, `onTick`, `keepTicking`, `helpLines`, `fallbackRules`, `charge`) are all
  called on the server thread, by one set of handlers in the core, so a module is a plain object a GameTest can
  drive. `start` returns the world's `Bases` (where each player's home is) or `null`; `helpLines(player)` adds
  lines after 💡 Help & links and after the respawn-loop rescue offer.
- **A server's upkeep on `Bases`** (all optional defaults): `reserveService(id)`, `readyService(id)` and
  `holdService(id)` give a server's own monitoring player a base of its own, put back to bare before each wish it
  makes and kept loaded while it runs; `rest(owner, now)` retires one orphaned base for an operator. A world that does
  not override them keeps no service bases (the monitoring player wishes from spawn) and refuses a rest. This is how
  a dedicated server reaches a level's bases without naming the level's own classes.
- **`keepTicking()`** (default `null`): why an EMPTY dedicated server must keep ticking right now. Vanilla stops
  ticking a server nobody has been on for `pause-when-empty-seconds`, and `onTick` with it; return a reason while
  your module has work that must run with nobody on, and `null` again as soon as it is done, so the server rests.
  Asked every tick, on the server thread: keep it a field read. For example, idle terrain pre-generation
uses it.
- **No hot reload:** a module is code and changes with a restart. What changes live is the world's rules file;
  a module reads its own rule with `WorldRules.first(type)`, and adds a rule type of its own with
  `WorldRuleType` below.
- **Example:**

```java
plugins.add(WorldModuleType.class, new WorldModuleType() {
    @Override public String id() { return "lighthouse"; }
    @Override public WorldModule create() {
        return new WorldModule() {
            @Override public String id() { return "lighthouse"; }
            @Override public Bases start(MinecraftServer server) { return null; }   // no bases
            @Override public void onLogin(ServerPlayer p) {
                p.sendSystemMessage(Component.literal("Welcome to the lighthouse!"));
            }
        };
    }
    @Override public boolean impliedWhenUnnamed() { return true; }   // run without the JVM property
});
```

### `WorldRuleType`
*`com.moddingfromamod.world.WorldRuleType`, read with `all`.* **A rule type of your own in a world's rules file**, so
a world's settings for your plug-in live beside its other rules, are checked when the file is read, and change live
when the file changes.

- **Default:** the rule library is the built-in types. A rules file naming any other `type` is refused whole
  (`type must be one of …`), and the world keeps its last good rules, or the closed fallback at start.
- **When:** `key()`, `single()` and `params(…)` are asked every time a rules file is parsed (server start, each live
  reload, tools that check a file), from any thread. Keep them pure.
- **What the core checks for you:** the entry's `id`, that it has no unknown keys, and that it carries no `effects`
  and no `message` (a plug-in rule denies nothing itself; refuse in your own code, in your own words). With
  `single()` true, the default, a second rule of your type refuses the file.
- **What you check:** `params(raw, where, err)` gets the entry's `params` object (empty when absent). Return the
  checked values as plain numbers, strings, booleans, lists and maps; add a line to `err`, starting with `where`, for
  anything wrong. Any line refuses the file. A check that throws refuses it too.
- **Reading it back:** `WorldRules.first("your_key")` (or `all("your_key")`) on the world's current rules gives your
  `WorldRule`; its `params()` are what you returned. Its `type()` is `PLUGIN` and `pluginType()` is your key.
- **The key** is `[a-z][a-z0-9_]*` and not a built-in type's. A bad or taken key, or the same key registered twice,
  is reported as an error on every parse, so no file loads until it is fixed.
- **Example:**

```java
plugins.add(WorldRuleType.class, new WorldRuleType() {
    @Override public String key() { return "lighthouse_hours"; }
    @Override public Map<String, Object> params(JsonObject raw, String where, List<String> err) {
        int from = raw.has("from") ? raw.get("from").getAsInt() : -1;
        if (from < 0 || from > 23) err.add(where + ": lighthouse_hours.from must be an hour, 0-23");
        return Map.of("from", from);
    }
});
```

```json
{"id": "open", "type": "lighthouse_hours", "params": {"from": 6}}
```

### `ChildBranch`
*`com.moddingfromamod.wish.ChildBranch`, read with `all`.* **A player command**, as a branch of `/moddy`. Players see
exactly two commands, `/wish` and `/moddy`; a new player verb is always a branch of `/moddy`, never a new
top-level command.

- **Default:** `/moddy` has only the core's branches.
- **When:** `branch()` is called each time commands are registered (server start, and `/reload`), on the server
  thread, so return a fresh builder each time. Branches are added after the core's, in plug-in order; do not reuse
  a core branch's name.
- **Who may run it:** whoever may run `/moddy` (every player). Check anything stricter in your own `executes`.
  The AI never runs `/moddy` (it is refused in the agent's command policy), so your branch is a player's only.
- **Example:**

```java
plugins.add(ChildBranch.class, () -> Commands.literal("lighthouse")
        .executes(ctx -> {
            ctx.getSource().sendSuccess(() -> Component.literal("The light is on."), false);
            return 1;
        }));
```

### `SettingsOverride`
*`com.moddingfromamod.config.SettingsOverride`, read with `all`.* **Set and lock settings** on a server you run: the
values win over every other layer (see "Settings" below), and every key an override names is **locked**: hidden
from menus and refused by the action that would change it, however the request arrives.

- **Default:** no override; settings come from the layers below.
- **When:** asked every time a setting is read, from any thread, so `keys()` and `value(key)` must be fast and
  thread-safe (answer from fields you filled in `register`, never from disk).
- `keys()` names every key you set or lock; a key ending `.*` names every key with that prefix. `value(key)` returns
  your value, or empty to lock a key without setting it (the layers below then supply the value, and nobody can
  change it). `refusal(key)` is what a player who tries is told.
- **Example:**

```java
plugins.add(SettingsOverride.class, new SettingsOverride() {
    @Override public Set<String> keys() { return Set.of("magician.enabled"); }
    @Override public Optional<String> value(String key) {
        return "magician.enabled".equals(key) ? Optional.of("off") : Optional.empty();
    }
});
```

## Internal seams (do not build on these)

These hooks are read by the public core but exist for our own servers. Each one's javadoc says so. They may
change or disappear without notice.

| Hook | Read with | What our servers plug in |
|---|---|---|
| `ClientAdapter` (`net/`) | `first` / `all` | the Bedrock module: how a menu, a chest or a dialog is drawn for a Bedrock player (adapt-only) |
| `ClientChannels` (`net/`) | `first` / `all` | the modded client's and our proxy's network channels |
| `PlayerIdentity` (`net/`) | `first` | our proxy's player identity (moved accounts, unforwarded logins) |
| `HealthHook` (`health/`) | `all` | a dedicated server's health thread and health endpoints |
| `BusWiring` (`plugin/`) | `all` | a dedicated server's own event listeners (operator commands, AFK kick, join timings) |
| `AlwaysMagician` (`wish/`) | `all` | a monitoring test player, whose wishes always go to the AI |
| `PlayerRekey` (`store/`) | `all` | a plug-in's own per-player stores (a level's islands, its wish bank's book), moved or re-read when our proxy re-keys a player to a new id |

## Settings

Every setting is read through `config/ModdySettings`, and the first layer that sets a key wins:

1. a `SettingsOverride` (a plug-in; locks the key);
2. on a **dedicated server**, its configuration file (below);
3. in **single-player** (and the LAN host's game), the host's own choices, stored in `settings.json` in Moddy's home
   folder and changed with `/moddy setting <key> <value>` or a settings menu;
4. the default.

Only the host changes settings: a LAN guest is refused ("Only the host changes Moddy's settings."), and on a
dedicated server every change is refused ("This server's settings come from its configuration file."). The
refusal is in the action itself, so a typed command or a stale menu is refused the same way. `/moddy setting`
with no arguments lists each setting, its value and who sets it.

| Key | What it is | Default | A player may pick |
|---|---|---|---|
| `magician.enabled` | Magician mode, the optional AI tier. Off: the Apprentice and the Builder only; no AI program is started and no model is called. Turns **on** only for picks that passed the Magician check (below); changing a pick turns it off again | `off` | `on`, `off` |
| `magician.model` | the wish model (grants the wish, effort low) | not chosen | `opus-5.5`, `muse-spark-1.2`, `muse-spark-1.2-contributor`, `muse-wonder-1.0` |
| `magician.mode` | how the wish model is paid for | not chosen | `subscription`, `api` |
| `magician.cheapModel` | the helper model (safety graders, routing, lookups) | not chosen | `haiku-5.5`, `muse-ember-1.0` |
| `magician.cheapMode` | how the helper model is paid for | not chosen | `subscription`, `api` |
| `magician.fallback` | what grants wishes when the wish model is out of quota, rate-limited, or its sign-in or key fails: an ordered, comma-separated list of `model:mode` (below) | `none` | `none`, or any list of `opus-5.5`, `muse-spark-1.2`, `muse-spark-1.2-contributor`, `muse-wonder-1.0` each with `:subscription` or `:api` |
| `magician.cheapFallback` | the same for the helper model | `none` | `none`, or a list of `haiku-5.5`, `muse-ember-1.0` each with `:subscription` or `:api` |
| `agent.model` | the model that grants wishes, when no `magician.model` is picked | the mod's own choice | `opus`, `default` |
| `agent.effort` | how hard the model thinks | low | nothing (an override only) |
| `safety.mode` | the child-safety layer | on | nothing: it has no off switch anywhere a player or a file reaches |
| `helper.route.describer`, `helper.route.researcher`, `helper.route.plan`, `helper.route.wishText`, `helper.route.worldRules`, `helper.route.readyMade`, `helper.route.character` | which provider each helper job may call and how it is paid (below) | the helper model's pick and its fallbacks | nothing: an override or the server's file only, and only to narrow or reorder the helper chain |

API keys are never a setting: Claude reads `ANTHROPIC_API_KEY` and Muse reads `MUSE_API_KEY` from the environment.

The four `magician.*` picks come from one table, `config/SupportedModels`; nothing else can be picked. The host sets
them in the Magician setup (the watch's **✪ Turn on the Magician** tile, or **`/moddy magician`**). Its first page has
one button per program found (**Use my Claude subscription**: Opus 5.5 and Haiku 5.5; **Use my Muse Code
subscription**: Wonder and Ember; **Use API keys**, only with a key set), which sets all four picks and runs everything
below in one go (`config/MagicianPresets`); **Choose models myself** keeps a page per pick. Either way it checks that Claude Code and Muse Code are installed and answer,
offers only what works on this machine (an API key only when its variable is set), and runs **the Magician check**
before turning Magician on: the input grader allows an ordinary wish and refuses one block row of the public safety
set, the plan grader grades a plan, the text and datapack sweeps pass a friendly line and stop a bad one, and one
small wish is granted for real; the check reports what the helper model cost and, when the wish model runs on Muse
Code, what the wish itself cost (from Muse Code's own usage records; a subscription's tokens are shown, not priced).

**Which program runs the wish model.** Claude Code runs Opus. Muse Code runs Spark and Wonder (`agent/MuseWorker`,
): `muse exec` with the tools locked to Moddy's own (`--preset empty`, shell, writes and web tools off), its own
config directory holding only Moddy's tool server (your own Muse Code MCP servers, rules and skills never reach it),
on your own `muse login` (your sign-in file is copied in, and a refreshed token is written back to it) or, in API-key
mode, on `MUSE_API_KEY` sent on its standard input. Wonder through your own key is offered with its price shown
(it has no Contributor tier there and costs more than Opus); a Muse Code subscription is the cheaper way to run it.

**When a model runs out: the fallback chain** (`agent/ModelFallback`). The wish model's pick and then
`magician.fallback`, in order, is a chain; so is the helper model's pick and then `magician.cheapFallback`. Set it in
the Magician setup (**If the wish model runs out** / **If the helper model runs out**: each model and way to pay is a
tile, a paid one shows its price before you pick it, and the order you pick them is the order they are tried) or with
`/moddy setting magician.fallback muse-spark-1.2-contributor:api,opus-5.5:api`.

- **What moves to the next entry**: the quota or window spent (a Muse Code 5-hour or weekly window, a Claude usage
  limit, no credit), a rate limit, a sign-in or key that is missing or refused, no active subscription, a plan that
  does not include the model. **Anything else does not**: an error that is the wish's, not the account's, keeps the
  same entry.
- **Sticky**: a spent entry is skipped until the reset its message names (a Muse Code 429 says when), or for a
  cooldown (an hour for a quota with no reset time or no subscription, half an hour for a failing sign-in, two
  minutes for a rate limit, or the wait it asked for), or until you sign in again. The skips are kept in
  `model-fallback.json` in Moddy's home folder, so a restart does not pay a failed start to find out again.
- **Never mid-wish**: the choice is made when a wish worker starts. A worker whose window runs out mid-wish ends; the
  wish it held goes back to the queue and the next worker, on the next entry, grants it.
- **You are told**, in chat: *"Moddy switched models: your Muse Code window is spent until 18:00, so wishes now use
  Muse Spark 1.2 Contributor with your API key (MUSE_API_KEY), $0.10 in / $0.20 out per million tokens."*, and
  *"Moddy is back on Muse Wonder 1.0 on your Muse Code sign-in."* when it comes back. With every entry spent, wishes
  wait and you are told why.
- **The helper calls** (safety grading, routing, lookups) run the Muse entries of their chain the same way (so a
  helper on your Muse Code subscription falls to your key when the window is spent); their last step is still the
  job's pinned Haiku. Helper switches are logged, not said in chat.
- An entry this machine cannot run is passed over with its reason: its key's variable not set, no `muse login`, its
  program not installed, or (on a dedicated server) a Claude subscription.

**Every helper job follows the helper pick, and nothing else** (`config/HelperRoute`). The picture
describer, the reference researcher, the plan grader, the wish-text grader, world rules, the ready-made nudge and the
character lookup each call only the provider, and only the way to pay, that the helper model's pick and
`magician.cheapFallback` name: pick Haiku on your Claude subscription and no helper ever calls Muse, even with
`MUSE_API_KEY` set; pick Ember on your Muse Code sign-in and none ever calls Anthropic, or bills your Muse key. A job
whose usual model is from the provider you did not pick does without it (a Claude-only setup describes pictures with
Haiku, a Muse-only one researches with Ember). Each job's route is a list of steps, tried in turn:
`muse-subscription` (Muse Code on its sign-in), `muse-key` (`MUSE_API_KEY`), `claude-key` (the Claude CLI on
`ANTHROPIC_API_KEY`) and `claude-subscription` (the Claude CLI on its sign-in, with any key removed from its
environment). A server's `helper.route.<job>` may narrow or reorder that route; it never adds a step the picks did not
choose. With no picks in charge (an override that locks them) the setting is the whole route, and unset it is what
the job always did.

What a Claude Code worker meets is read from its own log, so a sign-in, key or usage-limit refusal at start moves
the chain; a Claude usage limit that arrives in the middle of a detached Claude session is not seen yet.

## The dedicated server's configuration file

On a dedicated server nobody changes settings in game. The operator writes
`config/moddingfromamod-server.toml` (NeoForge's server config; Moddy creates it with these defaults on first start)
and restarts. A single-player game never creates it.

```toml
[magician]
    enabled = false          # Magician mode. Off: no AI program is ever started
    provider = "claude"      # "claude" (Claude Code) or "muse" (Muse Code)
    mode = "api"             # "api" (a key from the environment) or "subscription" (Muse only)
    model = "opus-5.5"       # the main model
    cheapModel = "haiku-5.5" # graders, routing and lookups
    fallback = "none"        # e.g. "muse-spark-1.2-contributor:api,opus-5.5:api": tried in turn when the model runs out
    cheapFallback = "none"   # e.g. "muse-ember-1.0:api"

[helpers]                    # each helper job's route; "default": the cheap model's pick and fallbacks, unchanged
    describer = "default"    # e.g. "muse-key,claude-key"; "none": no model call for this job
    researcher = "default"
    plan = "default"
    wishText = "default"
    worldRules = "default"
    readyMade = "default"
    character = "default"
```

| Key | Default | Accepted |
|---|---|---|
| `magician.enabled` | `false` | `true`, `false` |
| `magician.provider` | `"claude"` | `claude`, `muse` |
| `magician.mode` | `"api"` | `api`, `subscription` (Muse only) |
| `magician.model` | `"opus-5.5"` | claude: `opus-5.5`; muse: `muse-spark-1.2`, `muse-spark-1.2-contributor`, `muse-wonder-1.0` |
| `magician.cheapModel` | `"haiku-5.5"` | `haiku-5.5`, `muse-ember-1.0` |
| `magician.fallback` | `"none"` | `none`, or a comma-separated list of main `model:mode` (the fallback chain above); a Claude `:subscription` entry is refused on a server, and an `:api` entry needs its key in the environment |
| `magician.cheapFallback` | `"none"` | `none`, or a list of cheap `model:mode` |
| `helper.route.describer`, `helper.route.researcher`, `helper.route.plan`, `helper.route.wishText`, `helper.route.worldRules`, `helper.route.readyMade`, `helper.route.character` (the `[helpers]` section) | `"default"` | `default`, `none`, or an ordered list of `muse-subscription`, `muse-key`, `claude-key`, `claude-subscription`. It narrows or reorders the steps the cheap model and its fallbacks choose and never adds one; a misspelt step keeps Magician off |

What the server refuses. Each refusal keeps Magician **off** and logs one line at start saying why
(`settings: Magician stays OFF: …`); nothing falls back silently to another model:

- `enabled` is anything but `true`;
- an unknown provider, mode, model or cheap model (a misspelt one too), or a fallback entry that is not a
  supported `model:mode`, a Claude `:subscription` entry, or an `:api` entry without its key in the environment
  (never a silently shorter chain);
- **Claude's subscription mode** (`provider = "claude"`, `mode = "subscription"`): Claude on a server needs an
  Anthropic API key. Muse Code's subscription mode is allowed: the wish worker runs `muse exec` on the server
  account's own `muse login`;
- `mode = "api"` without the provider's key in the server's **environment** (`ANTHROPIC_API_KEY` or `MUSE_API_KEY`);
  and `cheapModel = "haiku-5.5"` always needs `ANTHROPIC_API_KEY` (no Claude subscription on a server);
- the **start-up check**: a file that passes every rule above runs the Magician check once the server has started
  (a stand-in player makes the test wish). Magician turns on only if every step passes; the log names each step.

What the file cannot do: hold an API key (keys come only from environment variables), or turn the safety layer
off (there is no such key).
