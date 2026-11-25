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

- **A store guarded by its own `"version"`** (a file of its own shape, not the envelope: `mobs.json`, the mint marks,
  the hand-over notes, the artifact memory) is written only when `WishStoreFile.writeBlocker` finds no newer version
  on disk.

`StoreMigrationsDocTest` (Gate A) keeps this page in step with the code (#126). It reads the source for every
versioned store: every enveloped store (a `STORE` name beside its `SCHEMA` or `VERSION`), every store guarded by its
own `"version"`, and every versioned record. Every version above 1 that a store writes has its row here, named by the
store; a row for a version nothing writes, or for a store nothing writes, fails; and each row names the class that
reads the older shape. A version-1 store needs no row: there is nothing older for it to read, apart from an
unenveloped file from before the envelope, which `WishStoreFile` reads as `Legacy` for every store alike.

## The register

| Store | Version | Change | Which records | Written back | Counted | Ends |
|---|---|---|---|---|---|---|
| species (record) | v2 | **The library-copy rule** (#99): an UNOWNED copy of a library creature takes its shipped health, attack damage, armour and knockback resistance whenever it is read (`species/LibraryCopies`, run in `SpeciesRegistry.load`). Nothing else about it moves | every unowned record whose id the library serves; never an owned creature or a fork (`marshal.ForkPolicy`: editing a library creature always forks it, so an unowned copy holds nothing a player chose) | yes, at the next save of the store; each record is then written at v2 | `SpeciesRegistry.load` logs how many copies took their shipped line | never, because a library creature's shipped line may change again in any release, and the copies a world made on first spawn must follow it |
| species (record) | v3 | **The stat-line repair** (#104, `species/StatLineRepair`, run in `SpeciesRegistry.load`): damage 0 on a creature with the `guardian` or `hunter` instinct, or with the `friendly` power, becomes its size band's damage (`species/PowerBand`, measured on the body it draws). A vehicle keeps its 0. Nothing else: never its health, look, name, powers, moves or temper | only records below v3: saved under the old tool wording ("Use 0 for a friendly character"). A record at v3 was made under #101's wording and is never repaired, whatever its damage | yes, at the next save; every record below v3 is then written at v3, so it runs once per record | `SpeciesRegistry.load` logs how many records were below v3 and names the repaired ones | never, because players' saves are theirs and an old world may be opened by any later release |
| paintings | v2 | **A picture may carry its owner** (`paint/PaintRegistry`): each record may hold `ownerId`, so one child's picture is not every child's. Additive: a v1 record has no owner and is read as shared, exactly as before. Bumped so an older build refuses the file rather than saving it again without the owners | every record of a v1 `pictures` file: read as shared | yes, at the next save of the store (the file at v2; a v1 picture stays shared) | not counted: nothing in a v1 record changes | never, because a v1 picture has no owner to recover, and reading it as shared is the only honest reading |
| item-specs | v1 | **The designs leave `items.json`** (#585, `item/ItemSpecStore` `migrateFromLegacy`): the designs shared `items.json` with the catalogue and whichever saved last won. An unstamped `items.json` holding designs is read once and moved into `item-specs.json` (and the looks into `item-icons.json`) | the designs in a legacy `items.json` | yes, at once: both new files are written, and the legacy file is left for the catalogue | logged (`migrating item designs out of …`) | never, because a world saved before the split may be opened at any time |
| item-specs | v2 | **The look moves out of the design** (`item/ItemIconStore`): a design no longer holds `icon` and `colour`, which live in `item-icons.json`. A v1 design's inline look is read into the icon store. Bumped because a v1 build requires `icon` and would have saved its empty map over icon-less designs | every v1 design | yes, the store converges on load and is written at the current version (`ItemSpecStore` load) | logged (`converging item designs (v1 -> v3, …)`) | never, because a v1 world may be opened at any time |
| item-specs | v3 | **`convert` and `change_block` become one effect, `turn_into`** (`item/ItemSpecStore` `turnInto`): a design's effects say `turn_into` with FROM/TO/RADIUS (convert's INPUT/OUTPUT, change_block's BLOCK, flat or grouped). The compiled packs are not rebuilt: an old item does exactly what it did until he edits it. Bumped because a v2 build would refuse the item at its next edit | every v2 design with a `convert` or `change_block` effect | yes, the store converges on load (as above) | logged (as above) | never, because a v2 world may be opened at any time |
| artifact memory | v2 | **A turn-by-turn memory replaces the hand-over note** (`wish/ArtifactMemoryStore`, successor to `wish/HandoverStore`, whose notes are v1): an old note is read once and folded in as ONE synthetic turn (`ArtifactMemoryStore.migrate`), because the merged note cannot be split back into the turns it was made from | each thing with a `handover/` note and no `memory/` file, at its first read | yes, its memory file is written at once; the old note is left as it was | not counted | never, because a world with hand-over notes may be opened at any time |
