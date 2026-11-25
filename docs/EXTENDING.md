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
- **The contract test world** is `src/main/java/com/moddingfromamod/test/contract/` (#63): `ContractWorld` stands in
  for a world jar, built only from what a world jar may use (the documented hooks and `MakeFacts`, never a Builder
  id). It makes its own rating from the facts (a toy formula inside the test plug-in: the core needs none), and its
  GameTests drive the core's real entry points with it in place. A core change that would break a world
  jar goes red there, in the core's own CI. Test code; never in the jar.
- **Testing a mod of your own against this one.** Two jars carry what a separate mod's tests need, built from this
  repository and never shipped:
  - `./gradlew testKitJar` (`build/test-kit/`): the GameTest harness (`testing/`), its test-only mixin config,
    `CorpusSource` and `UsesCorpus`;
  - `./gradlew testsJar` (`build/core-tests/`): this mod's unit-test classes, its GameTest and `test/` classes, and the
    public corpus resources those tests read off the classpath.

  Compile your tests against the mod jar and both. In a dev run the harness and the GameTest classes share packages
  with the mod, and a package split across two mods stops the boot, so put them in the same mod as this jar's entries
  (one merged dev jar), not beside it.

## Reading what a make is: `MakeFacts`

A read-only API, not a hook: a world (or any plug-in) asks it what a make is, to decide something of its own.
`com.moddingfromamod.wishbuilder.MakeFacts` is pure and Minecraft-free; `com.moddingfromamod.wish.MakeFactsLookup`
reads what the server holds. Nothing is stored or stamped anywhere: facts are computed when asked, so a thing carried
from one world to another has the same facts in both.

| Ask | For |
|---|---|
| `MakeFacts.of(BuilderSpec)` | a Builder make, an Apprentice tile's make, any spec still being picked (rated by its picks so far, never cautiously) |
| `MakeFacts.ofStoredSpec(JsonObject)` | a kept make's stored spec |
| `MakeFactsLookup.forRecipe(server, player, recipe)` | a recipe id: `builder:<id>` (his kept Builder make; once a Magician wish changed it, the thing as it is now, never below its spec), a taught or library recipe, `<wish>@<part>` |
| `MakeFactsLookup.forThing(server, kind, id)` | a thing on a shelf, from its stored design (what the Magician made) |
| `MakeFacts.ofCommands(commands, artifacts)`, `MakeFacts.ofArtifact(kind, json)`, `MakeFacts.combine(list)` | the pure pieces the two above are made of |

The answer is a `MakeFacts(kind, numbers, tags, known)`. **Facts only**: the core never rates, scores or ranks a
make, and never combines its facts into one figure. A world that wants a rating makes its own from them (the contract
test world below does, with a toy formula of its own).

- **`kind`**: `creature`, `ride`, `item`, `machine`, `magic_block`, `building`, `picture`, `event`, `rule`,
  `supplies` (the Builder's kinds), or `other` (a command or datapack the core cannot place).
- **`tags`**, a closed vocabulary (`MakeFacts.TAGS`), every one derived from fields the entries already carry (a
  creature's powers and temperament, an item effect's `applies_to` and parameters in the item catalogue, a creature
  ability's parameters in the effects catalogue, an event's motion and what it leaves, the kind). Nothing is hand-rated,
  so a Builder entry added later is rated with no decision from you:
  - `fights`: can hurt a creature (an effect that lands on what he hits, lightning or a blast at the spot, an ability
    that catches or commands others, a guard, anything hostile);
  - `hostile`: a creature that may attack players; `boss`: a creature with a boss bar;
  - `changes_blocks`: puts, takes or turns blocks where it acts (an effect taking a block, a turn-into, a blast that
    may break blocks, an event that leaves something or wrecks what it passes);
  - `builds`: puts a built thing in the world (a building, a picture's board);
  - `summons`: makes other creatures appear; `gives_items`: hands items over (supplies, prizes, a creature's treats);
  - `world_rule`: changes a rule for the whole world;
  - `decorative`: does nothing but be there (a picture, a "just for fun" item);
  - `harmless`: none of `fights`, `hostile`, `boss`, `changes_blocks`, `summons`, `world_rule`.
- **`numbers`**, measured, in real game units, the unit in the name (`MakeFacts.NUMBERS`). Read one with
  `facts.number(name)`, an `OptionalDouble`: a number is there only where the make really carries it, and **absent
  means not known, never 0**. The names are a stable vocabulary: additive only, never renamed or given another unit
  once shipped (`MakeFactsTest` pins them), so a world must cope with a number it is not given.

  | Number | Unit | Present for |
  |---|---|---|
  | `healthHalfHearts` | health points (one is half a heart) | a Builder creature or ride (what it is made with: a body's own; a ready-made species' record in this world, else a new creature's), a stored creature |
  | `attackDamageHalfHearts` | health points | a Builder creature or ride (as health), a stored creature; a Builder item with a base item: hitting with it in the main hand, read at run time from the item's own attribute modifiers and the player's base attack damage (nothing of the game's numbers is copied into the mod, so another version or another mod's item answers for itself) |
  | `movementSpeedAttribute` | the game's `movement_speed` attribute value (a zombie is 0.23) | a stored creature |
  | `scaleTimes` | times its normal size | a Builder creature or ride, a stored creature |
  | `builderSizeStep` | the Builder's size pick, 1 to 4 | a Builder creature, ride, building or event |
  | `builderStrengthStep` | the Builder's strength pick, 1 to 3 | a Builder item power, snack machine or magic block |
  | `countMade` | things made at once | a Builder creature, ride, item, machine or magic block; a recipe that spawns creatures |
  | `amountItems` | items | Builder supplies |
  | `radiusBlocks` | blocks: the largest radius an effect of it takes | an item or creature effect with a `RADIUS` |
  | `durationTicks` | ticks (20 a second): the longest an effect of it lasts | an item or creature effect with `SECONDS` |
  | `extentBlocks` | blocks: its longest side | a stored building |

  `combine` (several things one recipe makes) keeps each number's largest value; a number none of them carries stays
  absent.
- **`known`** is false when the thing could not be read (gone from its store, a newer Moddy's spec, a command the core
  does not know): the facts are then `MakeFacts.cautious(kind)`: every tag such a thing could carry, and no numbers.

Treat a tag you do not know as matching nothing, and a kind you do not know as allowed: the vocabulary and the kinds
may grow, and a world that hides or locks by tag never has to change when the core adds Builder content.
`MakeFactsTest` (Gate A) pins both vocabularies and reads every reviewed Builder entry and every ready-made.

## Telling a wished body from any other: `Wished`

`com.moddingfromamod.world.Wished.isWished(entity)` is the one documented way to ask "did a player's wish make this
body?", whatever made it: a Builder, Apprentice or Magician make, "Make another one", a recipe or a hotbar fire (an
arena giving wished creatures a role, a world leaving its natural spawns alone). Read it rather than a tag: the markers
under it may change. Cheap (two tag lookups), on the server or the client. `Wished.byTags(tags)` is the same decision
on a set of tags. Today it reads two markers:

| Marker | Put on | Meaning |
|---|---|---|
| `wishmob` | every sculpted wish creature, and a rig's carrier (the living entity that holds a rig creature's stats), at spawn and again on every load | a creature a wish made |
| `moddy_wished` | a plain mob that joined the world inside a spawn scope: a wish's command (the Magician's `summon`, a recipe's command), a Builder or Magician event, or **any datapack function**, run by a tick or a schedule | a mob the wishes' spawn budget counts |

A rig's display parts are parts of a body, never one, and are never wished. A mob the world spawns by itself, or a
child's own spawn egg, carries neither marker. Note the last row: a mob **your world's own datapack function** spawns
is inside the same scope and is marked too; a world that spawns mobs from its own functions tags them itself and checks
its own tag first.

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
| `refuses(ask)` | **what a new charge implements.** `ask` is a `WishAsk` (below). Asked as a wish starts, before anything runs or opens: typed words before the Apprentice's page or the re-wish reader opens (so a world that refuses typed words right now refuses them before any page), a Builder or Apprentice make before it is built, a recipe press, "Make another one" and a hotbar fire before the rate limiter. `Optional.empty()` lets it start; a value refuses it and **is the line the player reads**, said once by the core and nothing else with it. Your line is written by a world, so it passes the text sweep a chat line passes; a line that does not is replaced by "✨ That wish can't be made here right now. Try a different wish!". `Optional.of("")` refuses and says nothing more (you said your own words; a Builder make then adds "Your wish bank is empty. It fills up again soon."). **A child in distress is never refused**: the core lets that wish through whatever you answer (`ask.distress()` tells you). **Cheap and free of side effects**: the core may ask more than once for one wish (again when what answers turns out different), and a menu will ask while it draws, once per tile per draw; do your bookkeeping in `charge(ask, wishId)`. The default asks `refusesAtSubmit` and maps `true` to `Optional.of("")` | server |
| `refusesAtSubmit(player, distress, text, recipe)` | still supported, asked by the default `refuses`, so a charge written before `refuses` behaves as it did; `true` refuses (say why to the player yourself). Never refuse a child in `distress`. `recipe` is true for a wish served with no model that was named by what started it: a button, a "make another one" or a hotbar fire (of a Builder make too, whose `text` is then the thing's own name), `/moddy try`, a Builder or Apprentice make (whose `text` starts `Builder: `); false for typed words, also when a cached recipe his words matched serves them | server |
| `restLeft(ask)` | `ask` is a `WishAsk` (below); at a hotbar proxy it is `WishAsk.replay(player, recipe)`: `PRESET`, its recipe id, `REPLAY`, also when the proxy only lands in his inventory and no wish is in flight. How many ticks that wish still rests for that player, from now: `0` ready, `-1` (the default) you do not say. Asked right after every fire of a hotbar proxy, whatever came of it, and the proxy's vanilla cooldown sweep is set to your answer: the rest a fire you allowed started, the time left on one you refused because it is still resting (so the sweep never restarts), `0` for one you refused for another reason; `-1` keeps the core's one second. Asked again whenever a proxy is put in his inventory (added, given back after a knock-out, at login, after a respawn or a change of dimension), where an answer above `0` puts the sweep back at what is left, because the game keeps no cooldown across a logout or a respawn and his client loses its own on any respawn or change of dimension. **A query with no side effects**: answering must never start, restart or change a rest, since the core asks whenever it likes. Refuse a resting wish through `refuses` (or `refusesAtSubmit`) as before | server |
| `waiveIfEmpty(player, wishId)` | a wish let through on nothing because of distress: never charge it | server |
| `charge(ask, wishId)` | a wish served with no model starts (a recipe, a hotbar fire, "Make another one", a Builder or Apprentice make), after the rate limiter: a fire the limiter refuses never reaches it. Where a charge takes its price and starts a rest (keyed by `ask.recipe()`). The default asks `charge(player, wishId, agent)` | server |
| `charge(player, wishId, agent)` | still supported, asked by the default `charge(ask, wishId)`: `agent` is false | server |
| `atPickup(wish)` | the AI picks a wish up, after the safety and world-rule checks | **an agent thread**: do not touch the world directly; hop to the server thread (`server.submit(…).join()`) as the example does |
| `chargeAgain` / `afterAgain` / `refundAgain` | the three "again" presses (`Again.GIVE_ONE`, `PART`, `WHOLE`): charged before anything is handed over, refunded if nothing landed. "Give me one" asks `chargeAgain(player, kind, ask)`, told which thing (`WishAsk.replay` of the key `/moddy give` takes: an item's, or a creature's for its egg); its default asks `chargeAgain(player, kind)` | server |
| `refundUndone(player, wish)` | 👎 undid a wish; return the line saying the new balance, or `null` | server |
| `onStatus(before, after)` | every status change in the wish book (refund an ungranted charge here) | server |
| `priced`, `wishPrice`, `guideValues`, `panelTitle`, `composeTitle`, `wishesCommand`, `preview`, `loreLine`, `showBalance` | when a menu, the guide book or `/moddy wishes` is drawn | server |

- **`WishAsk`** (`com.moddingfromamod.wish.WishAsk`) is the value a hook that takes one is asked about, the same shape
  at every such hook: `player()` (UUID), `source()` (`WishSource`), `recipe()` (the id `/moddy again` takes, also the
  `builder:<id>` of the kept make a Builder edit changes, or the key `/moddy give` takes for "Give me one"; `""` when
  none), `magic()`, `text()` (the wish's words: typed words, a recipe's words, a Builder make's `Builder: …`) and
  `distress()`. The magic is told truthfully at each start: `BUILDER` for a Builder make (and a Builder change on a
  thing the Magician made); `APPRENTICE` for typed words the Apprentice's page or the re-wish reader will answer, and
  for a make an Apprentice tile pressed; `REPLAY` for a thing made again with no model (a recipe button, a cached
  recipe his typed words matched, "Make another one", a hotbar fire, "Give me one"); `MAGICIAN` for typed words the
  Magician's model will grant. It is immutable and gains fields over time, so build one with
  `WishAsk.of(player, source, magic)` (the kind of magic is always named, there is no default) or
  `WishAsk.replay(player, recipe)` and the `with…` methods, never its constructor.
- A charge that cannot be paid returns `new Paid(true, () -> "the words to say")` (or `AgainPaid(true, token)`);
  the core then starts nothing and says the line.
- **Example:** `EmeraldWishCharge` above, about forty lines.

### `WorldMakes`
*`com.moddingfromamod.world.WorldMakes`, read with `first`.* **What a world lets be made here, what it keeps for
later, and how big** (#14). Asked about one make at a time, given its `MakeFacts` (above) and the same `WishAsk` a
charge is told, and decided by the world alone: the core rates nothing and compares no number except to fit a make to
the limits the world gave.

- **Default (nothing registered):** every make is allowed and nothing is fitted: the game exactly as before.
- **Which one is asked:** the running world module's own, when `WorldModule.makes()` returns one, else the first
  registered `WorldMakes`, else the default (as with `WishCharge`).
- **The answer** is a `MakeView` (a record, so a later answer is a new state or field, never a new signature):

| Answer | Build it with | What the player sees |
|---|---|---|
| allowed | `MakeView.ALLOWED` | the make, as it is |
| hidden | `MakeView.HIDDEN` | nothing: the tile is not on this world's Builder pages or watch (it stays on his shelf for every other world); pressed anyway (a replay, a hotbar fire, a stale page) it says "✨ That can't be made in this world. Try something else!" and makes nothing |
| locked | `MakeView.locked("Unlocks after you finish the lighthouse")` | a grey tile with your words under its name, and the same words when pressed, made nothing, charged nothing. Your words are swept as a chat line is; words that do not pass (or none) become "🔒 That one is locked in this world for now." |
| fit | `MakeView.fit(Map.of("countMade", 3.0, "builderSizeStep", 3.0))` | the make, fitted within the limits (fewer at once, a smaller size, a gentler strength) and one short line from the core: "✨ Made a bit smaller for this world.", "✨ Made fewer for this world." or "✨ Made a bit gentler for this world." The tune dialog's sliders and size names stop at the limit; a page whose tiles cannot be fitted drops them, and a tile whose way on leads to nothing allowed is culled from its page |

- **Limits are data**: a `MakeFacts.NUMBERS` key → the most this world allows of it, numbers only. A key the core does
  not know is ignored; a limit on a number a make does not carry does nothing. `limits(player)` may also state limits
  up front for every make (`new WorldLimits(map, stamp)`); they join each answer's own, the tighter of each winning.
  The `stamp` is any short string that changes whenever your limits change, so a reader can tell two sets apart
  cheaply.
- **What it costs:** nothing on a server where no world answers (no walk, no thread, nothing kept). Where one does, the
  facts of every Builder tile (the make pressing it heads for, that make at its lowest knobs, what lies ahead of it) are
  worked out once per Builder tree (keyed by the tree's own fingerprint) on one background daemon thread started when
  the server has started; a page draw then only compares your answer and limits with them, reading no make. A page the
  walk has not reached yet (a second pick on a multi-pick page) shows its tiles locked with "⏳ Getting this page
  ready…" for a moment and is drawn again when it is ready; it is never drawn unfitted.
- **How a make is fitted**: through `BuilderAllowlist.capped(spec, limits)`, the one path a Builder make is "made as
  THIS world makes it". Each number is declared once beside the knob that lowers it (`MakeFit.FACTS`), and a clamp is
  never trusted: the next smaller value of the knob is tried and kept only when the make's own facts then fit. A number
  no knob lowers cannot be fitted: a make over it is locked in the core's words, "🔒 That one is too much for this world
  right now.", never made wrong.

| Number | Fitted by |
|---|---|
| `scaleTimes`, `builderSizeStep` | a smaller size (a creature's named sizes, else the 1–4 step) |
| `builderStrengthStep`, `durationTicks`, `radiusBlocks` | a lower strength |
| `countMade` | fewer at once (a Builder make; a recipe's `wishmob spawn <id> <count>`) |
| `amountItems` | fewer in a bundle of supplies |
| `healthHalfHearts`, `attackDamageHalfHearts` | a discrete search, nothing assumed about which way they move: every lower combination of an item's material (its tier, stepped down), the size and the strength steps is tried, the most kept first, and the first whose re-derived facts fit is made. An item none fits is locked. A creature or ride none fits is made as it is and **fitted on the live creature** (below); a stored thing's own record is never changed |
| `movementSpeedAttribute`, `extentBlocks` | nothing: not clampable, so over the limit is locked |

- **Fitted on the live creature** (`wish/LiveFit`): the last step for a creature's health and attack damage. At spawn
  (and when the creature is loaded or rebuilt), after its species has set its numbers, the core puts ONE attribute
  modifier per stat on the entity that holds the stats (a wish creature, or a rig's carrier; never a display part),
  `ADD_VALUE`, worked out to bring the value to the limit. Each has a fixed id, so a world module keeps its own
  modifiers on ids of its own and is left alone:

  | Stat | Modifier id |
  |---|---|
  | `minecraft:max_health` | `moddingfromamod:world_fit/max_health` |
  | `minecraft:attack_damage` | `moddingfromamod:world_fit/attack_damage` |

  It is never stacked (fitting again removes it first and works it out afresh), and the value is read again after it
  is put on. It is the entity's own: its save keeps it in this world, and it is never written into the make, the
  species, the shelf or any wish store, so the same make comes out at its own strength in another world. It covers every
  creature made here: Builder, Apprentice, "Make another one" and the Magician's. The player is told once, as for any
  fitted make.
- **Where the core asks:** every Builder and Apprentice make and every Builder change (`BuilderMake.make`, whatever page
  drew its tile); every tile of the Builder's pages and its tune dialog, per draw; each ranked make on the Apprentice's page, as it opens; a recipe, "Make another one" and a
  hotbar fire (`WishService.submit`, by the facts of what the recipe makes, fitted at fire); "Give me one"; each thing on
  his watch (so a Magician result the world locks stays on his shelf and is shown locked, and one it hides is not on
  this world's watch).
- **Order and the rules that do not bend:** the safety layers and a world's rules file run first, so a refused wish is
  never fitted into an allowed one; nothing is charged for a make that is hidden or locked; **a child in distress is
  never stopped or fitted** (as with the charge); the answer is a VIEW, worked out when asked and never written to his
  shelf or any store, because the shelf follows him to every world.
- **The Apprentice's tiles** (`wishbuilder/ApprenticeFit`): fitted AFTER ranking and outside the matcher, so its
  ranking, gold-set floors and tree fingerprint are the same in every world. Each ranked make is asked through the
  same gate and fitting its press goes through: one this world hides, or that no fit could bring within its limits, is
  dropped; one it locks is shown locked with its words; one it fits is drawn as it is, made fitted when pressed and told
  once. The order is the matcher's. With no world answering the page is exactly today's.
- **Not yet** (a later change): the Magician's tool arguments are fitted from limits carried in its brief.
- **Cheap, and free of side effects:** a menu asks once per tile per draw, on the server thread. Default methods only.
- **Example:** `ContractWorld` (the contract test world below): hostile makes hidden, a make its own toy rating puts at
  4 or more locked, everything else fitted to three at once, size step 3, 40 health and 6 attack damage.

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
- **The module's methods** (`start`, `stop`, `onLogin`, `onLogout`, `onTick`, `keepTicking`, `helpLines`, `fallbackRules`, `charge`, `makes`) are all
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
