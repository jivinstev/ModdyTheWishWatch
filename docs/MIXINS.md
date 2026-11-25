# Mixins

Every mixin the mod applies, one row each. `MixinTableTest` (Gate A) fails a mixin listed in a config below with no
row here, a row naming a mixin no config lists, and a row whose config column is not the config that lists it.
**Adding a mixin? Add its row in the same PR**, and answer the questions the columns ask first.

## The configs

| config | registered by | applied in |
|---|---|---|
| `moddingfromamod.mixins.json` | `neoforge.mods.toml` (`[[mixins]]`) | every game the mod is installed in: a single-player world's integrated server, and its client |
| `moddingfromamod.gametest.mixins.json` | FML's `--fml.mixinConfig` on the dev runs (`build.gradle`, `runs.configureEach`); never `neoforge.mods.toml` | development runs only: Gate B's `runGameTestServer`, `runClient`, `runServer`. The jar leaves the config and its classes (`com/moddingfromamod/testing/`) out, and its build fails if either is there (`NEVER_SHIP`) |

A build may register further configs of its own beside these; the public jar has exactly the first one.

## The rules every row keeps

- **No `@Overwrite`, and no `@Redirect`.** A redirect is exclusive: two mods redirecting the same call fail at load.
  Wrap a call with MixinExtras' `@WrapOperation` (it chains with other mods' wraps), change a value with
  `@ModifyExpressionValue`/`@ModifyReturnValue`/`@ModifyVariable`, or observe with a narrow `@Inject`.
- **No mixin targets another mod's class.** Every target is Minecraft's (or NeoForge's patched Minecraft), so no row
  needs an optional mod present.
- `defaultRequire: 1`: a target that moved (a Minecraft update, or another mod overwriting the method) fails the boot by
  name rather than going quiet. The price is that a mod which `@Overwrite`s one of these targets conflicts loudly;
  that is the trade the configs make on purpose.
- Accessors and invokers (`@Accessor`, `@Invoker`) change nothing; they reach a private field or method by name.

## `moddingfromamod.mixins.json`

All `common` (both sides load them; every target below is server-side logic, so on a client they run only in the
integrated server). Needed in single-player: yes for every row, which is why each is in the public jar.

| mixin | config | target | injection | purpose |
|---|---|---|---|---|
| `AnvilNameMixin` | `moddingfromamod.mixins.json` | `AnvilMenu#setItemName` | `@Inject` HEAD, cancellable | an anvil rename passes the placed-text check (`chat/ChatGuard#placed`); a refused name is not taken |
| `BatRideMixin` | `moddingfromamod.mixins.json` | `Bat#customServerAiStep`, `#tick`, `#setResting` | `@Inject` HEAD (two cancellable) | a bat carrying a ridden flying creature does not flutter off or rest on its own |
| `BookTextMixin` | `moddingfromamod.mixins.json` | `ServerGamePacketListenerImpl#updateBookContents`, `#signBook` | `@Inject` HEAD, cancellable | a book's pages and title pass the placed-text check |
| `CarrierGlowFlagMixin` | `moddingfromamod.mixins.json` | `Entity#setSharedFlag` | `@Inject` HEAD, cancellable | a rig carrier (or hit proxy) never raises its shared glowing flag, so only the rig glows |
| `DisplayLightInvoker` | `moddingfromamod.mixins.json` | `Display#getPackedBrightnessOverride`, `#setBrightnessOverride` | `@Invoker` | the display rig sets a display's light by its field, not a save/load round trip |
| `DisplayPoseInvoker` | `moddingfromamod.mixins.json` | `Display` transformation and interpolation setters | `@Invoker` | the display rig poses a display by its fields |
| `ItemDisplayItemInvoker` | `moddingfromamod.mixins.json` | `Display.ItemDisplay#getItemStack`, `#setItemStack` | `@Invoker` | the display rig sets an item display's item by its field |
| `FunctionNoiseMixin` | `moddingfromamod.mixins.json` | `ServerFunctionManager#tick`, `#execute` | `@Inject` HEAD/RETURN | which datapack function is running, for the noise meter (`datapack/NoiseMeter`) |
| `ExecutionContextHardStopMixin` | `moddingfromamod.mixins.json` | `ExecutionContext#incrementCost` | `@Inject` TAIL | a wish datapack's function that will not end is stopped inside its tick |
| `FunctionSpawnScopeMixin` | `moddingfromamod.mixins.json` | `ServerFunctionManager#execute` | `@Inject` HEAD/RETURN | a WISH datapack's function (its namespace a wish wrote, `datapack/WishPacks`, #80) runs its summons and gives inside a wish scope (`mob/SpawnScope`); a world's, vanilla's or another mod's function runs with none |
| `GiveCommandMixin` | `moddingfromamod.mixins.json` | `GiveCommand#giveItem` → `Inventory#add` | `@WrapOperation` | a wish's `/give` goes through the one delivery path (`item/delivery/WishDelivery`) while a wish scope is open; otherwise the original call, untouched |
| `ItemCombinerMenuAccessor` | `moddingfromamod.mixins.json` | `ItemCombinerMenu#player` | `@Accessor` | the anvil check reads whose menu it is |
| `ParticleNoiseMixin` | `moddingfromamod.mixins.json` | `ParticleCommand#sendParticles` | `@Inject` HEAD, cancellable | a throttled wish datapack's particle bursts are dropped |
| `PlaySoundNoiseMixin` | `moddingfromamod.mixins.json` | `PlaySoundCommand#playSound` | `@Inject` HEAD, cancellable | a throttled wish datapack's sounds are dropped |
| `ProjectileHitInvoker` | `moddingfromamod.mixins.json` | `Projectile#hitTargetOrDeflectSelf` | `@Invoker` | a projectile that reached a rig's hit proxy lands on the carrier |
| `ProxyProjectileMixin` | `moddingfromamod.mixins.json` | `Projectile#canHitEntity` | `@Inject` HEAD, cancellable | a projectile may hit a hit proxy exactly when it may hit the carrier |
| `RigMoveRotationMixin` | `moddingfromamod.mixins.json` | `ServerEntity#sendChanges` | `@Inject` HEAD | a rig atom that moved is sent its rotation with the move, so a client's easing never freezes a part mid-turn; on by default (`vanilla/RigMoveRotation.ON`; `rigrotwithmove=off` is the arm), and with it off it changes nothing |
| `RigBundleBroadcastMixin` | `moddingfromamod.mixins.json` | `ChunkMap$TrackedEntity#broadcast` | `@Inject` HEAD, cancellable | while the tracker's pass is held, a rig atom's packet is held for the players that see it, to go out in its rig's bundle (`vanilla/RigBundle`); every other entity, and any broadcast outside the pass, untouched |
| `RigBundleTickMixin` | `moddingfromamod.mixins.json` | `ChunkMap#tick` | `@Inject` HEAD and RETURN | holds the tracker's pass and, when it ends, sends each viewer what its rigs sent as ONE bundle, which a client applies on one tick, so a beat's moves cannot split across two client ticks and leave parts off their body for the whole beat (`vanilla/RigBundle`; `rigbundle=off` is the A/B arm) |
| `RigTrackerCadenceMixin` | `moddingfromamod.mixins.json` | `ServerEntity#sendChanges` | `@Inject` HEAD, cancellable | a rig atom's move goes out on the rig's beat, every 3rd tick, and only its changed data off the beat, so every part of a body moves on the same ticks (`vanilla/RigCadence`); and a look change (light, tint, item) sent while a pose is easing re-arms that ease for the ticks it has left, so it cannot jump the pose (`RigCadence.REARM`). Every other entity untouched |
| `RigDisplayEaseMixin` | `moddingfromamod.mixins.json` | `Display#onSyncedDataUpdated` | `@Inject` TAIL | on the server, a display remembers whether its pose ease restarted and whether a field that rebuilds a client's render state changed, for `RigTrackerCadenceMixin` (`vanilla/RigEaseState`); it only remembers, and does nothing on a client |
| `ItemDisplayLookKeys` | `moddingfromamod.mixins.json` | `Display.ItemDisplay` item and item-context keys | `@Accessor` | `RigDisplayEaseMixin` knows an item display's item change rebuilds its render state |
| `RigWalkAnimationMixin` | `moddingfromamod.mixins.json` | `LivingEntity#aiStep` | `@Inject` TAIL | a rig carrier keeps its walk animation on the server (1.21.5 moved it client-side), which the rig is animated from |
| `SignTextMixin` | `moddingfromamod.mixins.json` | `SignBlockEntity#updateSignText` | `@Inject` HEAD, cancellable | a sign's words pass the placed-text check where the world sweeps placed text |
| `EntityTagSetMixin` | `moddingfromamod.mixins.json` | `Entity#<init>` | `@Inject` RETURN | an entity's tag set reports its changes, feeding the `@e[tag=…]` index |
| `EntityLookupIndexMixin` | `moddingfromamod.mixins.json` | `EntityLookup#add`, `#remove` | `@Inject` TAIL | each entity lookup keeps an index of the tags its entities carry |
| `SelectorParserTagsMixin` | `moddingfromamod.mixins.json` | `EntitySelectorParser#parse` | `@Inject` HEAD/RETURN | a parsed selector remembers its text and the tags it requires |
| `SelectorIndexMixin` | `moddingfromamod.mixins.json` | `EntitySelector#addEntities` | `@WrapMethod` | `@e[tag=X]` with no position is answered from the entities carrying X; anything else calls the original; kept unscoped (see below) |
| `LevelEntityGetterAdapterAccessor` | `moddingfromamod.mixins.json` | `LevelEntityGetterAdapter#visibleEntities` | `@Accessor` | the selector index reaches a level's entity lookup |

**Why `SelectorIndexMixin` answers every positionless tagged `@e`, other mods' datapacks included.** It was
proven to give the same entities as the original in both directions (`-Ptestprops=selectorindex=verify` over all of
Gate B, both real worlds and targeted wishes: 0 mismatches, `docs/SELECTOR_INDEX.md`), and anything it does not
recognise calls the original. Scoping it to our own datapacks would add a second path that answers the same question
differently, so it stays as it is.

## `moddingfromamod.gametest.mixins.json`

Test-only: needed in single-player no, on a server no; Gate B needs them. `GameTestInfo` exists only in a test run, and
the sound ledger is switched on only by a test or the behaviour harness (a dev `runServer`).

| mixin | config | target | injection | purpose |
|---|---|---|---|---|
| `GameTestBatchOrderMixin` | `moddingfromamod.gametest.mixins.json` | `GameTestBatchFactory#divideIntoBatches` | `@ModifyReturnValue` | on a coverage-mapping run only (`testing/gt/CoverageMap`), batches run in the order of their environment names, so a class's batches and its settle window run together and in the same place every run; any other run keeps the game's own order |
| `GameTestCrashGuardMixin` | `moddingfromamod.gametest.mixins.json` | `GameTestInfo#tick` | `@WrapMethod` | one test's exception fails that test, not the whole run (1.21.8's `fail` takes only a `GameTestException`); names the test the stall watchdog sees |
| `GameTestWallClockMixin` | `moddingfromamod.gametest.mixins.json` | `GameTestInfo#startTest`, `#tickInternal` | `@Inject` HEAD; `@ModifyExpressionValue` on the `timeoutTicks` read | a test's `timeoutTicks` is also that much wall-clock time at 20 ticks a second |
| `SoundSentMixin` | `moddingfromamod.gametest.mixins.json` | `ServerCommonPacketListenerImpl#send` | `@Inject` HEAD | every sound packet to a player, for the sound ledger (`seam/SoundLedger`); a no-op unless a test switched the ledger on. Moved here from the public config: nothing in a player's game turns the ledger on |

## Booted beside other mods

Measured 2026-11-21: the public jar (1.0.1, store-shaped, this config only) on a NeoForge 21.8.57 dedicated
server installed from NeoForge's own installer, beside JEI 24.2 (build 6), Jade 19.1.1, FerriteCore 8.0.4, Lithium 0.18.1,
Architectury 17.0.8, Cloth Config 19.0.147, AppleSkin 3.0.8, Waystones 21.8.5, Balm 21.8.10 and Clumps 26.0 (build 1) (the
NeoForge 1.21.8 releases on Modrinth that day). It reached `Done` in 7.4 s and stopped cleanly; 0 ERROR lines, no
mixin apply failure or conflict, and the only WARN not in the same mods' boot without Moddy was Moddy's own "Magician
is off". 24 of the 26 mixins applied at boot, beside the others' on the same classes (`Entity` with Balm, Jade and
Lithium; `LivingEntity` with Lithium); the other two (`BookTextMixin`, `SoundSentMixin`) target the player connection classes, which load only
when a player joins. Re-run it after adding a mixin: install a server, put the jar and the mods in `mods/`, boot, and
read `logs/debug.log` for `Mixing … from moddingfromamod.mixins.json` and any mixin error.

**With a client and a join, 2026-11-23: `./tools/boot-beside.sh`** (a dev server plus an xvfb dev client that
joins it, both with the same 10 mods, Jade at 19.3.1). The client joined. 27 of our public mixins applied on the server
and 23 on the client, including the two the server-only boot could not reach (`BookTextMixin` and
`ExecutionContextHardStopMixin`, both on the player connection). Our private config's mixins also applied (12 server, 8
client). There were no mixin apply failures or conflicts on either side. Every ERROR line came from somewhere else:
NeoForge's `@OnlyIn` warnings about Architectury, AppleSkin and Cloth Config; no audio device under xvfb; and the
sandbox refusing the Mojang session servers. Two things the first runs taught, both now in the script:
- NeoForge stops on a "Warnings while loading mods" screen when another mod uses `@OnlyIn`, and the quick-join waits
  behind it forever; the script presses "Proceed" with xdotool.
- The build's `-Psmokejars` check read the floor of a jar's Minecraft range as its version, so it refused Balm
  (`[1.21.7,)`, built for 1.21.8). It now checks that 1.21.8 is inside the declared range.

`SoundSentMixin` is in the GameTest-only config, so neither boot applies it.
