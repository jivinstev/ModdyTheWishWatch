# Review: Minecraft 1.21.11 (version branch neoforge-1.21.11)

An independent review by a FRESH agent that was given only this version's changes: every file that differs
from the parent branch (neoforge-1.21.10) at their merge-base (`git diff <merge-base>..neoforge-1.21.11`), read against the parent's
file, its STUBS.json and .port/1.21.11/renames.tsv. One row per STUBS.json entry, per `changed` file, per
resource group (`resources`: a directory with the digest of its changed files -- an edit makes it a new
row) and for renames.tsv (`renames`, with its digest); the files the rename rows alone made, the build
settings tools/fork-branches.py derives and added helper classes need no row; a file deleted here is a `deleted`
row unless drops.txt or a mixin_remove key accounts for it. Verdict `ok` (the change keeps
the behaviour, or the difference is right and the note says why) or `not-ok`. A hook item of the behaviour
inventory is accounted for only by a row whose note names its id, or by an `item` row for the id; a
reviewer may add `missing` (a file that should exist) and `shared` (a shared helper) rows. `reviewer` names
the reviewing agent, distinct from the fixer; `commit` is the commit reviewed (`git rev-parse --short HEAD`).
tools/fork-port.py adds a `?` row for every item it finds unreviewed; it never changes a verdict.

- reviewer: keep-current round fresh reviewer (ModdyTheWishWatch@1.21.11_review)
- date: 2026-11-27
- commit: f4889c4
- re-check: fresh re-checker agent of the keep-current weekly round (week 2026-11-26), neither the reviewer nor the fixer, 2026-11-27: the 1 row the fix changed (f4889c4) re-checked; 0 not-ok left

| kind | file | verdict | note |
|---|---|---|---|
| changed | build.gradle | ok | mcDefault and its comment name 1.21.11; nothing else changed |
| changed | corpus/apprentice/content-index.json | ok | snapshot regenerated from 1.21.11's registries: adds only its new content (spears, nautilus armour, netherite horse armour, camel_husk, nautilus, zombie_nautilus, parched); nothing removed |
| changed | src/main/java/com/moddingfromamod/agent/WishAgent.java | ok | logAdminCommands -> LOG_ADMIN_COMMANDS via the level's rules; game rules read/written through 1.21.11's registry GameRules (gamerules package; server.overworld().getGameRules(), get/set by key), same rule, same value |
| changed | src/main/java/com/moddingfromamod/agent/tools/SetRuleTool.java | ok | ResourceLocation -> Identifier, ResourceKey.location() -> identifier(): 1.21.11's names, same ids; permission ints -> LevelBasedPermissionSet (2 = GAMEMASTER, 4 = OWNER), the same level |
| changed | src/main/java/com/moddingfromamod/builder/BuildRegistry.java | ok | ResourceLocation -> Identifier, ResourceKey.location() -> identifier(): 1.21.11's names, same ids |
| changed | src/main/java/com/moddingfromamod/compat/SavedDataTypes.java | ok | 1.21.11's SavedDataType.Factory hands the ServerLevel itself (javap: create(ServerLevel)); requireNonNull(ctx).registryAccess() is levelOrThrow()'s behaviour |
| changed | src/main/java/com/moddingfromamod/compat/Target.java | ok | MINECRAFT = 1.21.11 (the compiler's CBSCRIPT_MC) |
| changed | src/main/java/com/moddingfromamod/datapack/DatapackCommands.java | ok | comment only: the compiler boilerplate's 1.21.11 rule id |
| changed | src/main/java/com/moddingfromamod/datapack/DatapackDialect.java | ok | comment only: adds 94 on 1.21.11 (code reads the live pack version) |
| changed | src/main/java/com/moddingfromamod/datapack/DatapackVerifier.java | ok | ResourceLocation -> Identifier, ResourceKey.location() -> identifier(): 1.21.11's names, same ids; permission ints -> LevelBasedPermissionSet (2 = GAMEMASTER, 4 = OWNER), the same level |
| changed | src/main/java/com/moddingfromamod/datapack/PackParseCheck.java | ok | ResourceLocation -> Identifier, ResourceKey.location() -> identifier(): 1.21.11's names, same ids; getFunctionCompilationLevel -> getFunctionCompilationPermissions, the same source reload parses with |
| changed | src/main/java/com/moddingfromamod/datapack/QuarantineGameTest.java | ok | ResourceLocation -> Identifier, ResourceKey.location() -> identifier(): 1.21.11's names, same ids; max_command_sequence_length set and restored on every exit path as before; game rules read/written through 1.21.11's registry GameRules (gamerules package; server.overworld().getGameRules(), get/set by key), same rule, same value |
| changed | src/main/java/com/moddingfromamod/datapack/QuarantineRule.java | ok | comment only: the rule's 1.21.11 id |
| changed | src/main/java/com/moddingfromamod/datapack/ShotBeam.java | ok | comment only: the rule's 1.21.11 id |
| changed | src/main/java/com/moddingfromamod/event/EventDirector.java | ok | ResourceLocation -> Identifier, ResourceKey.location() -> identifier(): 1.21.11's names, same ids; the saved dim string is unchanged, so staged events round-trip |
| changed | src/main/java/com/moddingfromamod/item/OnBlockShapeGameTest.java | ok | moved entity classes (monster.zombie, projectile.arrow); permission ints -> LevelBasedPermissionSet (2 = GAMEMASTER, 4 = OWNER), the same level; setClientLoaded reflection -> connection.markClientLoaded() (javap: on ServerGamePacketListenerImpl in 1.21.11), null-guarded, with a why comment; spawnInvulnerableTime fallback kept |
| changed | src/main/java/com/moddingfromamod/item/TriggerCorpusMatrixGameTest.java | ok | Zombie package move; permission ints -> LevelBasedPermissionSet (2 = GAMEMASTER, 4 = OWNER), the same level; client-loaded via connection.markClientLoaded() (commented); the dropped catch-branch was the 1.21.1 spawn-timer fallback this tree never runs |
| changed | src/main/java/com/moddingfromamod/item/WishItemCatalogue.java | ok | ResourceLocation -> Identifier, ResourceKey.location() -> identifier(): 1.21.11's names, same ids |
| changed | src/main/java/com/moddingfromamod/lite/StockHotbarProbe.java | ok | rig command doDaylightCycle -> advance_time (1.21.11 keeps no alias) |
| changed | src/main/java/com/moddingfromamod/lite/WatchPinStage.java | ok | Horse moved to animal.equine; keepInventory via KEEP_INVENTORY, same values (nit: `KEEP_INVENTORY,false` lacks the file's space after the comma) |
| changed | src/main/java/com/moddingfromamod/mob/PrimedTntLimit.java | ok | ResourceLocation -> Identifier, ResourceKey.location() -> identifier(): 1.21.11's names, same ids (log line only) |
| changed | src/main/java/com/moddingfromamod/mob/SpawnPerfProbe.java | ok | doDaylightCycle -> ADVANCE_TIME on the overworld's rules; game rules read/written through 1.21.11's registry GameRules (gamerules package; server.overworld().getGameRules(), get/set by key), same rule, same value |
| changed | src/main/java/com/moddingfromamod/testing/StarterShow.java | ok | game rules read/written through 1.21.11's registry GameRules (gamerules package; server.overworld().getGameRules(), get/set by key), same rule, same value; doFireTick false -> FIRE_SPREAD_RADIUS_AROUND_PLAYER 0 (1.21.11 replaced the boolean with this radius; 0 = no spread), commented; ResourceLocation -> Identifier, ResourceKey.location() -> identifier(): 1.21.11's names, same ids |
| changed | src/main/java/com/moddingfromamod/testing/gt/GameTestAdapter.java | ok | ResourceLocation -> Identifier, ResourceKey.location() -> identifier(): 1.21.11's names, same ids; SetGameRules built from a GameRuleMap (1.21.11's native shape), still random_tick_speed 0 for the still batches |
| changed | src/main/java/com/moddingfromamod/vanilla/PlayerRescueGameTest.java | ok | setClientLoaded(true) -> connection.markClientLoaded(), the 1.21.11 home of the flag; comment kept |
| changed | src/main/java/com/moddingfromamod/vanilla/VanillaRigSurvivalGameTest.java | ok | adds updateDataBeforeSync() after each tick (javap: LivingEntity.updateDataBeforeSync, 1.21.11 moved invisibility there); the test asserts the same visibility, commented |
| changed | src/main/java/com/moddingfromamod/wish/CommandBlockGuardGameTest.java | ok | isCommandBlockEnabled -> the COMMAND_BLOCKS_WORK game rule (1.21.11 moved the switch to a rule); ResourceLocation -> Identifier, ResourceKey.location() -> identifier(): 1.21.11's names, same ids |
| changed | src/main/java/com/moddingfromamod/wish/CommandDialect.java | ok | adds GameRuleIds.correct (pure, GameRuleIdsTest): old camelCase rule ids rewritten to 1.21.11's, so pre-1.21.11 packs and the agent's habits keep working (KI-8's fix); ResourceLocation -> Identifier, ResourceKey.location() -> identifier(): 1.21.11's names, same ids |
| changed | src/main/java/com/moddingfromamod/wish/CommandGuard.java | ok | gamerule allow-list widened only to the same two rules under 1.21.11 ids (bare or minecraft:), camelCase kept because GameRuleIds renames after the guard; new 1.21.11 root `stopwatch` gets a DENY row (CLAUDE.md: every new command needs a CommandGuard row); documented |
| changed | src/main/java/com/moddingfromamod/wish/CommandTrial.java | ok | ResourceLocation -> Identifier, ResourceKey.location() -> identifier(): 1.21.11's names, same ids; permission ints -> LevelBasedPermissionSet (2 = GAMEMASTER, 4 = OWNER), the same level |
| changed | src/main/java/com/moddingfromamod/wish/WatchPinGameTest.java | ok | keepInventory set/restored via KEEP_INVENTORY; permission ints -> LevelBasedPermissionSet (2 = GAMEMASTER, 4 = OWNER), the same level |
| changed | src/main/java/com/moddingfromamod/wish/WishExecutor.java | ok | ResourceLocation -> Identifier, ResourceKey.location() -> identifier(): 1.21.11's names, same ids; permission ints -> LevelBasedPermissionSet (2 = GAMEMASTER, 4 = OWNER), the same level |
| changed | src/main/java/com/moddingfromamod/wish/WishHotbarGameTest.java | ok | ResourceLocation -> Identifier, ResourceKey.location() -> identifier(): 1.21.11's names, same ids; keepInventory set and restored in finally as before |
| changed | src/main/java/com/moddingfromamod/wishbuilder/BuilderAbilityGameTest.java | ok | ADVANCE_TIME set/restored; moved Zombie and Chicken classes; ResourceLocation -> Identifier, ResourceKey.location() -> identifier(): 1.21.11's names, same ids |
| changed | src/main/java/com/moddingfromamod/world/DatapackScope.java | ok | COMPILER_BOILERPLATE matches the 1.21.11 compiler's `gamerule max_command_sequence_length` (starter zips confirm it is what the compiler writes) |
| changed | src/main/java/com/moddingfromamod/world/ScopedPacksGameTest.java | ok | the refused-gamerule probe uses the 1.21.11 id, still refused |
| changed | src/main/java/com/moddingfromamod/world/WorldModuleHost.java | ok | ResourceLocation -> Identifier, ResourceKey.location() -> identifier(): 1.21.11's names, same ids |
| changed | src/test/java/com/moddingfromamod/datapack/CbScriptCorpusTest.java | ok | adds the 1.21.11 -> 94 row beside 1.21.10's |
| changed | src/test/java/com/moddingfromamod/datapack/DatapackCommandsTest.java | ok | boilerplate line uses the 1.21.11 rule id |
| changed | src/test/java/com/moddingfromamod/testing/GateShardsTest.java | ok | PublicCommandsReadmeGameTest added to the ci-shard must-haves (the README check moved to Gate B, commented) |
| changed | src/test/java/com/moddingfromamod/wish/CommandGuardTest.java | ok | same assertions under 1.21.11 ids, plus camelCase still allowed for the two rules only and still refused for others |
| changed | src/test/java/com/moddingfromamod/world/CommandCoordsTest.java | ok | world-wide gamerule example uses the 1.21.11 id, still refused |
| changed | tools/client-validation/playtests/01-builder-wish.playtest | ok | setup rules advance_time / spawn_mobs (1.21.11 ids) |
| changed | tools/client-validation/playtests/02-apprentice-wish.playtest | ok | setup rules advance_time / spawn_mobs (1.21.11 ids) |
| changed | tools/client-validation/playtests/03-claude-subscription.playtest | ok | setup rules advance_time / spawn_mobs (1.21.11 ids) |
| changed | tools/client-validation/playtests/05-magician-wish.playtest | ok | setup rules advance_time / spawn_mobs (1.21.11 ids) |
| changed | tools/client-validation/playtests/06-builder-last-sword.playtest | ok | setup rules advance_time / spawn_mobs (1.21.11 ids) |
| changed | versions/1.21.11.properties | ok | renamed from 1.21.10's: minecraft 1.21.11, range [1.21.11,1.21.12), NeoForge 21.11.45; header comment is the author's, unchanged |
| deleted | src/test/java/com/moddingfromamod/wish/CommandTree.java | ok | moved to src/main/java/.../testing/CommandTree.java (testing/ never ships) for the GameTest; accounted |
| deleted | src/test/java/com/moddingfromamod/wish/CommandsReadme.java | ok | moved to src/main/java/.../testing/CommandsReadme.java; its pure halves still tested in Gate A (testing/CommandsReadmeTest) |
| deleted | src/test/java/com/moddingfromamod/wish/PublicCommandsReadmeTest.java | ok | replaced by wish/PublicCommandsReadmeGameTest (ci shard): same assertions incl. no moddyop, plus a live-dispatcher check; Commands' static init needs Bootstrap in 1.21.11, the why is in its javadoc |
| deleted | versions/1.21.10.properties | ok | renamed to versions/1.21.11.properties (this tree builds one target) |
| renames | .port/1.21.11/renames.tsv @3c6363b0dc39 | ok | renames.tsv: permission ints -> LevelBasedPermissionSet / Permissions, hasImpulse -> needsSync, playNotifySound -> mv/NotifySound (one small shim, same packet to that player alone), getRule().get/set -> get/set by key, server rules -> overworld's, location() -> identifier(), RULE_* -> 1.21.11 GameRules names; each matches 1.21.11's API, no behaviour change |
| resources | src/main/resources/META-INF/accesstransformer.cfg @d69e504efc24 | ok | re-checked at f4889c4: the file is deleted, as in the parent (neoforge-1.21.10 has no access transformer); nothing in the tree names RenderType's constructor, LegacyRenderType or an access transformer (git grep; build.gradle and neoforge.mods.toml declare none), so nothing relied on it; the 'fork layer' wording is gone with it |
| resources | src/main/resources/assets/wishpack/items @c5e0390fa1af | ok | hotbar.json regenerated (one line, the badge model) for 1.21.11's item list |
| resources | src/main/resources/items/hotbar-badge.json @916f53e22f70 | ok | minecraft 1.21.11; the seven spears added to flatBlocks; WishHotbarGameTest checks the rule against the item registry |
| resources | src/main/resources/precompiled/manifest.json @ce71cda1fb02 | ok | fingerprint of the recompile from bundle java-port-1.21.11 f9b9aa5; entries 152 unchanged |
| resources | src/main/resources/precompiled/ready-mades.json.gz @9cfd51b0bd34 | ok | recompiled ready-mades from the 1.21.11 bundle (PrecompiledFingerprintTest, Gate A) |
| resources | src/main/resources/starter-library/datapacks/breath_cone_pet_dragon @26bc847ea2b8 | ok | rebuilt by the 1.21.11 compiler: unzipped and diffed, only pack_format 88 -> 94 (min/max 94.1) and `gamerule maxCommandChainLength` -> `max_command_sequence_length` |
| resources | src/main/resources/starter-library/datapacks/colour_cycle_rainbow_axolotl @788b3784ed61 | ok | rebuilt by the 1.21.11 compiler: unzipped and diffed, only pack_format 88 -> 94 (min/max 94.1) and `gamerule maxCommandChainLength` -> `max_command_sequence_length` |
| resources | src/main/resources/starter-library/datapacks/double_jump_boots_jump @acd0e21e9d47 | ok | rebuilt by the 1.21.11 compiler: unzipped and diffed, only pack_format 88 -> 94 (min/max 94.1) and `gamerule maxCommandChainLength` -> `max_command_sequence_length` |
| resources | src/main/resources/starter-library/datapacks/lightning_sword_lightning @a19db6439d5d | ok | rebuilt by the 1.21.11 compiler: unzipped and diffed, only pack_format 88 -> 94 (min/max 94.1) and `gamerule maxCommandChainLength` -> `max_command_sequence_length` |
| resources | src/main/resources/starter-library/datapacks/lucky_block_outcome_table @18162c225ff0 | ok | rebuilt by the 1.21.11 compiler: unzipped and diffed, only pack_format 88 -> 94 (min/max 94.1) and `gamerule maxCommandChainLength` -> `max_command_sequence_length` |
| resources | src/main/resources/starter-library/datapacks/on_land_trex @524f72f48244 | ok | rebuilt by the 1.21.11 compiler: unzipped and diffed, only pack_format 88 -> 94 (min/max 94.1) and `gamerule maxCommandChainLength` -> `max_command_sequence_length` |
| resources | src/main/resources/starter-library/datapacks/periodic_trex @16303ba4af59 | ok | rebuilt by the 1.21.11 compiler: unzipped and diffed, only pack_format 88 -> 94 (min/max 94.1) and `gamerule maxCommandChainLength` -> `max_command_sequence_length` |
| resources | src/main/resources/starter-library/datapacks/slushie_machine_load_and_pour @b6c79415d098 | ok | rebuilt by the 1.21.11 compiler: unzipped and diffed, only pack_format 88 -> 94 (min/max 94.1) and `gamerule maxCommandChainLength` -> `max_command_sequence_length` |
| resources | src/main/resources/starter-library/datapacks/slushie_plain @ca445fb61950 | ok | rebuilt by the 1.21.11 compiler: unzipped and diffed, only pack_format 88 -> 94 (min/max 94.1) and `gamerule maxCommandChainLength` -> `max_command_sequence_length` |
| resources | src/main/resources/starter-library/datapacks/teleport_wand_teleport @4a347880170d | ok | rebuilt by the 1.21.11 compiler: unzipped and diffed, only pack_format 88 -> 94 (min/max 94.1) and `gamerule maxCommandChainLength` -> `max_command_sequence_length` |
| resources | src/main/resources/starter-library/datapacks/tiny_cookie_grow @84d797bba417 | ok | rebuilt by the 1.21.11 compiler: unzipped and diffed, only pack_format 88 -> 94 (min/max 94.1) and `gamerule maxCommandChainLength` -> `max_command_sequence_length` |
| resources | src/main/resources/tools/cbscript.SOURCE @381c969c8e60 | ok | bundle java-port-1.21.11 f9b9aa5 (moddy-cbscript's carry-up merge of master b73b5b8) |
| item | main:com/moddingfromamod/mixin/RigBundleBroadcastMixin.java#injection:@Inject method=broadcast(Lnet/minecraft/network/protocol/Packet;)V @At(value="HEAD") | ok | RigBundleBroadcastMixin targets ChunkMap$TrackedEntity#sendToTrackingPlayers(Packet) (1.21.10's rename of broadcast, unchanged here); javap of NeoForge 21.11.45 shows that method and seenBy, and docs/MIXINS.md has its row: the hook still fires |
