# Store migrations

Every change Moddy makes to data a player already has on disk is listed here: what it changes, how it is versioned,
how many records it changed is counted, and whether it ever stops. A downstream census declares a migration by its row
here. A new migration adds its row in the same change.

Moddy runs in a player's own single-player game, and the saves are theirs, so a read-time path that puts an old record
right is **permanent**: it is never removed. (This repository has no compat-shim register; this page is where such a
path is recorded, with its end condition.)

## How a store is versioned

- **The store** (its file) carries `"store"` and `"v"` in an envelope (`store/WishStoreFile`). A file written by a newer
  Moddy than this one is **refused** and never written over (a downgrade never loses a newer player's data).
- **A species record** carries its own `"v"`, inline in the record (`Species.RECORD_VERSION`; a record without one is
  v1). A migration that applies to records saved before it applies only to records below its version, never to a
  record inferred from its values, and the record is written back at the current version at the next save, so it runs
  once per record. An older build ignores the field and reads the record as it always did. A record NEWER than this
  build knows is read as best it can and latches the store: nothing is saved over it (`SpeciesRegistry.load`).

`StoreMigrationsDocTest` (Gate A) keeps this page in step with the code: every species record version above 1 that
this build writes has its row, and each row names a migration class that exists.

## The register

| Store | Version | Change | Which records | Written back | Counted | Ends |
|---|---|---|---|---|---|---|
| species (record) | v2 | **The library-copy rule** (#99): an UNOWNED copy of a library creature takes its shipped health, attack damage, armour and knockback resistance whenever it is read (`species/LibraryCopies`, run in `SpeciesRegistry.load`). Nothing else about it moves | every unowned record whose id the library serves; never an owned creature or a fork (`marshal.ForkPolicy`: editing a library creature always forks it, so an unowned copy holds nothing a player chose) | yes, at the next save of the store; each record is then written at v2 | `SpeciesRegistry.load` logs how many copies took their shipped line | never, because a library creature's shipped line may change again in any release, and the copies a world made on first spawn must follow it |
| species (record) | v3 | **The stat-line repair** (#104, `species/StatLineRepair`, run in `SpeciesRegistry.load`): damage 0 on a creature with the `guardian` or `hunter` instinct, or with the `friendly` power, becomes its size band's damage (`species/PowerBand`, measured on the body it draws). A vehicle keeps its 0. Nothing else: never its health, look, name, powers, moves or temper | only records below v3: saved under the old tool wording ("Use 0 for a friendly character"). A record at v3 was made under #101's wording and is never repaired, whatever its damage | yes, at the next save; every record below v3 is then written at v3, so it runs once per record | `SpeciesRegistry.load` logs how many records were below v3 and names the repaired ones | never, because players' saves are theirs and an old world may be opened by any later release |
