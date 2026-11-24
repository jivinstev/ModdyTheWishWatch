# The starter library's packs and their recipes

The starter library (`src/main/resources/starter-library/`) is the wish library that ships inside the jar: the first
row of wishes every child sees. Most of it is source a test can read (`species/`, `buildings/`, `items/`, ...). Its
datapacks are not: `datapacks/<id>/<id>.zip` is a compiled pack, made by a tool from a granted wish.

So every pack keeps a **`recipe.json`** beside its zip. It records the exact tool calls that made the pack, so any
version can rebuild it from the tree after a compiler or Minecraft change instead of patching the zip by hand.

## The recipe

```json
{
  "recipe": "starter-pack",
  "v": 1,
  "id": "periodic_trex",
  "calls": [
    {
      "tool": "apply_effect",
      "args": {
        "effect": "periodic",
        "tag": "trex",
        "params": { "COMMAND": "effect give @s minecraft:jump_boost infinite 5 true" }
      }
    }
  ],
  "compiler": "521e75eacb968528454fefd8e6914602c0fc9187",
  "date": "2026-11-24",
  "note": "optional: anything a rebuild should know"
}
```

- **`id`**: the pack id, the same as its directory and its zip.
- **`calls`**: the tool calls that made the pack, in order, each with its **full** `args` exactly as the tool took
  them. `tool` is `apply_effect` (a creature's ready-made ability) or `make_item` (an item). The last call must make
  the pack `id`: for `apply_effect` that is `<effect>_<tag>`; for `make_item` it is the id the tool hands back.
- **`compiler`**: the full commit of the bundled CBScript compiler the pack was compiled with, from
  `src/main/resources/tools/cbscript.SOURCE`.
- **`date`**: the day the pack was made, `YYYY-MM-DD`.
- **`note`**: optional.

An item's recipe is its design: its one `make_item` call must say what `items/<id>/item.json` and `icon.json` say.
An item rebuilt under an id today's naming would not mint (`slushie_plain`, which the slushie machine pours by id) says
`"start_from": "<its own id>"`; the rebuild then edits the starter's own design, which keeps the id.

The code is `library/StarterRecipe` (reading, writing and the checks, Minecraft-free).

## Adding a starter pack

Whoever turns a granted wish into a starter copies the pack's zip **and writes its recipe at the same time**, from
the wish's action log: every `apply_effect` or `make_item` call that made the pack, with its full arguments, the
compiler commit and the date. Then list both files in `starter-library/index.txt`.

`StarterRecipeTest` (Gate A) fails on a starter pack with no recipe, a recipe for another pack, or an item recipe that
disagrees with the item's design.

## Rebuilding every pack

```bash
./gradlew runGameTestServer -Pgametests=StarterRegenGameTest -Ptestprops="regenStarters=/tmp/starter-packs"
```

`StarterRegenGameTest` replays every recipe through the real tools, fails if a tool refuses one or makes a different
pack, logs for each pack whether its functions match the shipped ones (`STARTER_REGEN:` lines), and writes each fresh
zip into the directory given. A version branch that bumps the compiler runs it and copies the zips over
`datapacks/<id>/<id>.zip`, then updates each recipe's `compiler` and `date`.
