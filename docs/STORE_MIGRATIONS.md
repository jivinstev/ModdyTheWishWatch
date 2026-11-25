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
- **A mob's undo record** (`mob/MobState`, the `"before"` of each entry of a handle's history in `mobs.json`)
  carries its own `"v"` inline too (`MobState.RECORD_VERSION`; none is v1). A record newer than this build is refused
  (`MobState.Newer`): its undo is not applied, and the table that met it is never written back over the file. The file
  is `"version"` 2 from #115 on (`MobHandleStore.VERSION`), so an older build never writes over it.
- **A species record** carries its own `"v"`, inline in the record (`Species.RECORD_VERSION`; a record without one is
  v1). A migration that applies to records saved before it applies only to records below its version, never to a
  record inferred from its values, and the record is written back at the current version at the next save, so it runs
  once per record. An older build ignores the field and reads the record as it always did.

## The register

| Store | Version | Change | Which records | Written back | Counted | Ends |
|---|---|---|---|---|---|---|
| species (record) | v2 | **The library-copy rule** (#99): an UNOWNED copy of a library creature takes its shipped health, attack damage, armour and knockback resistance whenever it is read (`species/LibraryCopies`, run in `SpeciesRegistry.load`). Nothing else about it moves | every unowned record whose id the library serves; never an owned creature or a fork (`marshal.ForkPolicy`: editing a library creature always forks it, so an unowned copy holds nothing a player chose) | yes, at the next save of the store; each record is then written at v2 | `SpeciesRegistry.load` logs how many copies took their shipped line | never, because a library creature's shipped line may change again in any release, and the copies a world made on first spawn must follow it |
| mob undo record (`mobs.json`, each history entry's `"before"`) | v2 | **Armour and knockback resistance are captured** (#115): `modify_mob` can set them (#101), so its undo record now holds both, and an undo puts them back. A v1 record holds neither: it reads them as null, "not captured", and an undo leaves them as they are while still putting back everything it did capture | every record written from #115 on; a v1 record is read as it is, never inferred | yes: the table is written at the next change, each record at v2 (its armour and knockback stay unknown) | not counted: nothing in a v1 record changes, only its version stamp | never, because a v1 record may stay in a player's save for as long as that mob lives |
