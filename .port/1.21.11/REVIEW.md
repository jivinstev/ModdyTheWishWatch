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

- reviewer: fresh reviewer agent of the keep-current weekly round (week 2026-11-27, review job ModdyTheWishWatch@1.21.11_review), not the fixer; rows the fixes 44ae457 and ff344af changed re-checked by a fresh re-checker (job ModdyTheWishWatch@1.21.11_recheck), the access-transformer row gone with the file (the parent has none)
- date: 2026-11-28
- commit: ff344af

| kind | file | verdict | note |
|---|---|---|---|
| changed | build.gradle | ok | mcDefault and its comment 1.21.10 -> 1.21.11 only; nothing else changed against the parent |
| changed | corpus/apprentice/content-index.json | ok | content snapshot regenerated from 1.21.11 registries: the spears, nautilus armour, netherite horse armour, camel_husk, nautilus, parched, zombie_nautilus added; nothing removed; data only |
| changed | src/main/java/com/moddingfromamod/agent/WishAgent.java | ok | logAdminCommands read and turned off through 1.21.11 GameRules.LOG_ADMIN_COMMANDS on the overworld rules (the server-wide set); same behaviour |
| changed | src/main/java/com/moddingfromamod/agent/tools/SetRuleTool.java | ok | Identifier.tryParse and level 2 -> LevelBasedPermissionSet.GAMEMASTER: the 1.21.11 spellings of the same id check and permission |
| changed | src/main/java/com/moddingfromamod/builder/BuildRegistry.java | ok | ResourceLocation -> Identifier, key.location() -> identifier(): renames only |
| changed | src/main/java/com/moddingfromamod/compat/SavedDataTypes.java | ok | SavedDataType.Factory now hands a ServerLevel (javap of NeoForge 21.11.45); requireNonNull(ctx).registryAccess() is levelOrThrow()'s same lookup and same null failure |
| changed | src/main/java/com/moddingfromamod/compat/Target.java | ok | MINECRAFT 1.21.11 and its javadoc; the compiler is handed the right target |
| changed | src/main/java/com/moddingfromamod/datapack/DatapackCommands.java | ok | javadoc names the 1.21.11 id max_command_sequence_length; comment only |
| changed | src/main/java/com/moddingfromamod/datapack/DatapackDialect.java | ok | javadoc adds 94 on 1.21.11; code reads the format from SharedConstants, unchanged |
| changed | src/main/java/com/moddingfromamod/datapack/DatapackVerifier.java | ok | Identifier, identifier() and withPermission(GAMEMASTER) for 2: renames, same checks |
| changed | src/main/java/com/moddingfromamod/datapack/PackParseCheck.java | ok | getFunctionCompilationLevel() -> getFunctionCompilationPermissions() (the same source ServerFunctionLibrary parses with on 1.21.11), Identifier |
| changed | src/main/java/com/moddingfromamod/datapack/QuarantineGameTest.java | ok | MAX_COMMAND_CHAIN_LENGTH -> MAX_COMMAND_SEQUENCE_LENGTH set and restored on every path as before; Identifier |
| changed | src/main/java/com/moddingfromamod/datapack/QuarantineRule.java | ok | javadoc names max_command_sequence_length; comment only |
| changed | src/main/java/com/moddingfromamod/datapack/ShotBeam.java | ok | javadoc names max_command_sequence_length; comment only |
| changed | src/main/java/com/moddingfromamod/event/EventDirector.java | ok | Identifier/identifier() for the stored dim string; the stored shape ("minecraft:overworld") is unchanged, so saved events still read |
| changed | src/main/java/com/moddingfromamod/item/OnBlockShapeGameTest.java | ok | reflective setClientLoaded (gone) -> ServerGamePacketListenerImpl.markClientLoaded() (javap: present), null-guarded, commented; the spawnInvulnerableTime fallback kept; moved entity packages |
| changed | src/main/java/com/moddingfromamod/item/TriggerCorpusMatrixGameTest.java | ok | client-loaded via p.connection.markClientLoaded() (mock players carry a connection, MockPlayerNetwork); the 1.21.1 spawnInvulnerableTime fallback dropped, which no 1.21.11 build can reach; Zombie package move |
| changed | src/main/java/com/moddingfromamod/item/WishItemCatalogue.java | ok | k.location() -> k.identifier() for enchantment and attribute keys; same strings |
| changed | src/main/java/com/moddingfromamod/lite/StockHotbarProbe.java | ok | rig command gamerule doDaylightCycle -> advance_time (1.21.11 id, no alias) |
| changed | src/main/java/com/moddingfromamod/lite/WatchPinStage.java | ok | KEEP_INVENTORY through GameRules.set; horse package animal.horse -> animal.equine. Nit (P4): "KEEP_INVENTORY,false" lacks the space after the comma at line 407 |
| changed | src/main/java/com/moddingfromamod/mob/PrimedTntLimit.java | ok | key.identifier() in a log line; rename |
| changed | src/main/java/com/moddingfromamod/mob/SpawnPerfProbe.java | ok | RULE_DAYLIGHT -> ADVANCE_TIME on the overworld rules; same probe setup |
| changed | src/main/java/com/moddingfromamod/testing/StarterShow.java | ok | SPAWN_MOBS/ADVANCE_TIME/ADVANCE_WEATHER renames; doFireTick false -> FIRE_SPREAD_RADIUS_AROUND_PLAYER 0 (1.21.11 replaced the boolean with this int; 0 is no spread), commented. Nit (P4): ",false" spacing on three lines |
| changed | src/main/java/com/moddingfromamod/testing/gt/GameTestAdapter.java | ok | Identifier; SetGameRules now takes a GameRuleMap, built with RANDOM_TICK_SPEED 0 for the still batches: same environment |
| changed | src/main/java/com/moddingfromamod/vanilla/PlayerRescueGameTest.java | ok | setClientLoaded(true) -> connection.markClientLoaded(); the comment kept |
| changed | src/main/java/com/moddingfromamod/vanilla/VanillaRigSurvivalGameTest.java | ok | updateDataBeforeSync() after each tick (javap: LivingEntity.updateDataBeforeSync, invisibility follows effects there on 1.21.11), commented; the test still proves the carrier is re-hidden; production runs it in the real sync |
| changed | src/main/java/com/moddingfromamod/wish/CommandBlockGuardGameTest.java | ok | MinecraftServer.isCommandBlockEnabled is gone on 1.21.11 (javap); the gamerule COMMAND_BLOCKS_WORK is its replacement; Identifier |
| changed | src/main/java/com/moddingfromamod/wish/CommandDialect.java | ok | GameRuleIds.correct added beside the other version rewrites: old camelCase game-rule ids (and the agent's learned names) become 1.21.11 ids, inverted rules flipped, doFireTick left to be refused; pure, GameRuleIdsTest/ShippedGameRulesTest; Identifier |
| changed | src/main/java/com/moddingfromamod/wish/CommandGuard.java | ok | GAMERULES admits advance_time/advance_weather (bare and minecraft:) beside the camelCase pair GameRuleIds renames before execution; every other rule still refused; new 1.21.11 root stopwatch gets a DENY row with its reason (CommandPolicyGameTest) |
| changed | src/main/java/com/moddingfromamod/wish/CommandTrial.java | ok | permission 2 -> LevelBasedPermissionSet.GAMEMASTER, identifier(); renames |
| changed | src/main/java/com/moddingfromamod/wish/WatchPinGameTest.java | ok | withPermission(4) -> OWNER; KEEP_INVENTORY read, set and restored as before |
| changed | src/main/java/com/moddingfromamod/wish/WishExecutor.java | ok | Identifier/identifier() and the wish source stack at GAMEMASTER (was 2); same permission |
| changed | src/main/java/com/moddingfromamod/wish/WishHotbarGameTest.java | ok | KEEP_INVENTORY read/set/restored in finally as before; Identifier |
| changed | src/main/java/com/moddingfromamod/wishbuilder/BuilderAbilityGameTest.java | ok | ADVANCE_TIME set and restored; entity package moves (monster.zombie.Zombie, animal.chicken.Chicken); Identifier |
| changed | src/main/java/com/moddingfromamod/world/DatapackScope.java | ok | COMPILER_BOILERPLATE matches the 1.21.11 compiler's gamerule max_command_sequence_length (McTarget.maxChainRule on >=1.21.11; all 152 precompiled and 11 starter packs carry it); an old-spelled line is renamed by CommandDialect at install |
| changed | src/main/java/com/moddingfromamod/world/ScopedPacksGameTest.java | ok | the refused world-wide gamerule sample spelled advance_time; still a refusal case |
| changed | src/main/java/com/moddingfromamod/world/WorldModuleHost.java | ok | dimension().identifier(): rename, same string compared |
| changed | src/test/java/com/moddingfromamod/datapack/CbScriptCorpusTest.java | ok | expected pack format 94 for 1.21.11 (matches the vendored McTarget row {1,21,11}->{94,1}) |
| changed | src/test/java/com/moddingfromamod/datapack/DatapackCommandsTest.java | ok | the boilerplate sample spelled max_command_sequence_length |
| changed | src/test/java/com/moddingfromamod/testing/GateShardsTest.java | ok | PublicCommandsReadmeGameTest added to the ci-shard list, so the moved README check runs on every PR |
| changed | src/test/java/com/moddingfromamod/wish/CommandGuardTest.java | ok | asserts the 1.21.11 ids allowed and the snake_case others refused, keeps the camelCase pair allowed and camelCase others refused; nothing weaker than before |
| changed | src/test/java/com/moddingfromamod/world/CommandCoordsTest.java | ok | world-wide sample spelled advance_time; still refused |
| changed | tools/client-validation/playtests/01-builder-wish.playtest | ok | setup gamerules advance_time / spawn_mobs (1.21.11 ids; the old ones fail on this client) |
| changed | tools/client-validation/playtests/02-apprentice-wish.playtest | ok | setup gamerules advance_time / spawn_mobs |
| changed | tools/client-validation/playtests/03-claude-subscription.playtest | ok | setup gamerules advance_time / spawn_mobs |
| changed | tools/client-validation/playtests/05-magician-wish.playtest | ok | setup gamerules advance_time / spawn_mobs |
| changed | tools/client-validation/playtests/06-builder-last-sword.playtest | ok | setup gamerules advance_time / spawn_mobs |
| changed | versions/1.21.11.properties | ok | renamed from 1.21.10.properties: minecraft 1.21.11, range [1.21.11,1.21.12), NeoForge 21.11.45; the rest unchanged |
| deleted | src/test/java/com/moddingfromamod/wish/CommandTree.java | ok | moved to src/main/java/.../testing/CommandTree.java (excluded from the jar by SHIP_EXCLUDES) for the GameTest |
| deleted | src/test/java/com/moddingfromamod/wish/CommandsReadme.java | ok | moved to src/main/java/.../testing/CommandsReadme.java; its parsing keeps a unit test (testing/CommandsReadmeTest) |
| deleted | src/test/java/com/moddingfromamod/wish/PublicCommandsReadmeTest.java | ok | replaced by PublicCommandsReadmeGameTest (ci shard): on 1.21.11 Commands' static PermissionCheck needs Bootstrap, which plain JUnit cannot run; the GameTest checks the same README/tree equality and also the live dispatcher; the reason is written in its javadoc |
| deleted | versions/1.21.10.properties | ok | renamed to versions/1.21.11.properties (this version's keys) |
| renames | .port/1.21.11/renames.tsv @3c6363b0dc39 | ok | permission ints -> LevelBasedPermissionSet/Permissions by level, hasImpulse -> needsSync, playNotifySound -> mv.NotifySound helper, getRule(...).set/get -> GameRules.set/get, server.getGameRules -> overworld().getGameRules, location() -> identifier(), and each RULE_* to its 1.21.11 field per GameRuleRegistryFix; every mapped field exists (build compiles); doFireTick deliberately unmapped (an int now, handled by hand in StarterShow) |
| resources | src/main/resources/assets/wishpack/items @c5e0390fa1af | ok | hotbar.json regenerated against the 1.21.11 client: 1224 -> 1234 select cases (the new items); generated |
| resources | src/main/resources/items/hotbar-badge.json @916f53e22f70 | ok | minecraft 1.21.11 and the seven spears added to flatBlocks; generated, HotbarBadgeTest guards staleness |
| resources | src/main/resources/precompiled/manifest.json @ce71cda1fb02 | ok | re-check at ff344af: unchanged and right; its fingerprint (af447157...) covers templates and compiler and equals the re-recorded store's, 152 entries as in the store |
| resources | src/main/resources/starter-library/datapacks/breath_cone_pet_dragon @26bc847ea2b8 | ok | starter pack rebuilt by the 1.21.11 compiler: pack.mcmeta 88.0 -> 94.1 is the only difference (unzipped and diffed); its reset still writes gamerule max_command_sequence_length, the 1.21.11 id |
| resources | src/main/resources/starter-library/datapacks/colour_cycle_rainbow_axolotl @788b3784ed61 | ok | starter pack rebuilt by the 1.21.11 compiler: pack.mcmeta 88.0 -> 94.1 is the only difference (unzipped and diffed); its reset still writes gamerule max_command_sequence_length, the 1.21.11 id |
| resources | src/main/resources/starter-library/datapacks/double_jump_boots_jump @acd0e21e9d47 | ok | starter pack rebuilt by the 1.21.11 compiler: pack.mcmeta 88.0 -> 94.1 is the only difference (unzipped and diffed); its reset still writes gamerule max_command_sequence_length, the 1.21.11 id |
| resources | src/main/resources/starter-library/datapacks/lightning_sword_lightning @a19db6439d5d | ok | starter pack rebuilt by the 1.21.11 compiler: pack.mcmeta 88.0 -> 94.1 is the only difference (unzipped and diffed); its reset still writes gamerule max_command_sequence_length, the 1.21.11 id |
| resources | src/main/resources/starter-library/datapacks/lucky_block_outcome_table @18162c225ff0 | ok | starter pack rebuilt by the 1.21.11 compiler: pack.mcmeta 88.0 -> 94.1 is the only difference (unzipped and diffed); its reset still writes gamerule max_command_sequence_length, the 1.21.11 id |
| resources | src/main/resources/starter-library/datapacks/on_land_trex @524f72f48244 | ok | starter pack rebuilt by the 1.21.11 compiler: pack.mcmeta 88.0 -> 94.1 is the only difference (unzipped and diffed); its reset still writes gamerule max_command_sequence_length, the 1.21.11 id |
| resources | src/main/resources/starter-library/datapacks/periodic_trex @16303ba4af59 | ok | starter pack rebuilt by the 1.21.11 compiler: pack.mcmeta 88.0 -> 94.1 is the only difference (unzipped and diffed); its reset still writes gamerule max_command_sequence_length, the 1.21.11 id |
| resources | src/main/resources/starter-library/datapacks/slushie_machine_load_and_pour @b6c79415d098 | ok | starter pack rebuilt by the 1.21.11 compiler: pack.mcmeta 88.0 -> 94.1 is the only difference (unzipped and diffed); its reset still writes gamerule max_command_sequence_length, the 1.21.11 id |
| resources | src/main/resources/starter-library/datapacks/slushie_plain @ca445fb61950 | ok | starter pack rebuilt by the 1.21.11 compiler: pack.mcmeta 88.0 -> 94.1 is the only difference (unzipped and diffed); its reset still writes gamerule max_command_sequence_length, the 1.21.11 id |
| resources | src/main/resources/starter-library/datapacks/teleport_wand_teleport @4a347880170d | ok | starter pack rebuilt by the 1.21.11 compiler: pack.mcmeta 88.0 -> 94.1 is the only difference (unzipped and diffed); its reset still writes gamerule max_command_sequence_length, the 1.21.11 id |
| resources | src/main/resources/starter-library/datapacks/tiny_cookie_grow @84d797bba417 | ok | starter pack rebuilt by the 1.21.11 compiler: pack.mcmeta 88.0 -> 94.1 is the only difference (unzipped and diffed); its reset still writes gamerule max_command_sequence_length, the 1.21.11 id |
| resources | src/main/resources/tools/cbscript.SOURCE @381c969c8e60 | ok | records java-port-1.21.11 f9b9aa5 and its tree hash; the vendored diff is McTarget only: pack format {1,21,11}->{94,1} and maxChainRule max_command_sequence_length on >=1.21.11 |
| item | main:com/moddingfromamod/mixin/RigBundleBroadcastMixin.java#injection:@Inject method=broadcast(Lnet/minecraft/network/protocol/Packet;)V @At(value="HEAD") | ok | main:com/moddingfromamod/mixin/RigBundleBroadcastMixin.java#injection:@Inject method=broadcast(Lnet/minecraft/network/protocol/Packet;)V @At(value="HEAD"): inherited from 1.21.10 (871ba8a), which retargeted it to TrackedEntity.sendToTrackingPlayers(Packet) @At HEAD, cancellable; javap of NeoForge 21.11.45 shows ChunkMap$TrackedEntity.sendToTrackingPlayers(Packet), and the shadowed entity/seenBy fields, so it applies (defaultRequire 1); docs/MIXINS.md row names the new target |
| resources | src/main/resources/precompiled/ready-mades.json.gz @c0589815851b | ok | re-check of ff344af (full re-record): 152 entries whose skeleton sources and keys are identical to neoforge-1.21.10's store (152/152), the eaten powers' minecraft:food can_always_eat back (120 occurrences, as 1.21.10 and main); outputs are the 1.21.11 compiler's (pack_format 94, max_command_sequence_length in all 152); the 128 entries whose source was already right compile byte-identically to the old store |
