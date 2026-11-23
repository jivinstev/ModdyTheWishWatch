# Datapacks: the most-used tool, and the least-checked

`author_datapack` is what you reach for more than any other building tool. Measured across every
wish ever granted: **55 live datapacks written, 3 verified.** This is the gap where *"I told him it
worked and it did not"* comes from.

## ALWAYS verify_datapack, immediately, every time

A pack can compile, load, report success and be **completely dead**: a bad selector, a particle id
that does not exist, a function Minecraft dropped at parse time, a recipe rejected for one wrong key.
Every one of those is invisible — no error, nothing wrong in the world, and a feature that simply
never happens.

Pass the `entries` you expect. Without them a pack that registered **no functions at all** still
reads as loaded.

It also counts the **recipes** your pack wrote against the recipes the server accepted. `wrote 3,
ACCEPTED 3` means live; `wrote 3, ACCEPTED 0` means every one was rejected and is doing nothing.

## "It loaded" is not "it works". Verify the BEHAVIOUR too

`verify_datapack` proves the pack is alive. It cannot prove the thing the child asked for happens.
Those are different claims and only the second one is the wish.

So test the behaviour the way the child will: **make the trigger happen, then look**.

**Do not test it with a summoned item and then tell him it works for a dropped one.** That mistake
cost a whole session. The agent summoned an oak log, saw a bottle appear, and told the child it was
working — while the child, dropping logs by hand with Q, got nothing. A summoned entity and a
dropped one are not the same entity, in the same state, in the same place.

**A check that FAILS is not a check that passed.** In the same session a `data get` came back *"No
entity was found"* and the agent answered the child *"It IS working — the magic is just too fast!"*.
It had no evidence for that sentence. If the check found nothing, say you could not tell.

## A power on HIM "forever" is not a datapack

A power he wants to keep ("forever", "from now on", "anymore") is one call, not a pack. Use
`modify_player` when one of its powers fits — and it now has the stats too: `no_fall_damage`,
`heavy_falls`, `no_knockback`, `never_hungry` (also "sprint without getting hungry"), `swim_fast` and
`fast_mining`. Otherwise use one `run_command`: `effect give @s <effect> infinite <level minus 1> true`,
or for another stat `attribute @s <attribute> base set <value>`.

**Both survive his death.** An infinite effect comes back with him when he respawns, and so does an
attribute base value. A tick function that re-applies the effect every tick is the slow way to write
the same thing: a pack to author, compile and verify, for a wish that one command grants.

## An extra DROP is `change_drops`, not a loot table

A mob that drops a prize, a block that sometimes drops something extra, leaves that drop more fruit
and structure chests with extra food are each one `change_drops` call. Do not write a loot
table in a pack: a loot table REPLACES the vanilla one, so the zombie that now drops diamonds has
stopped dropping everything else. `change_drops` adds on top, and it reports whether the rule
actually LOADED.

## "When this happens, do that" is `set_rule`, not a tick loop

Anything shaped *when something happens, something else happens* is one `set_rule` call that
lasts forever: a kind of mob appearing, dying or landing; him jumping, sneaking or sprinting; a mob
coming near him or him standing on a block; sunrise, sunset, the rain starting; a thrown thing
hitting something; a fish caught; an explosion; or every few seconds. Do not write a datapack with
a scoreboard clock for these. `do` runs as the thing it happened to, so `@s` is that mob. Two
things commands cannot do are fields: `push` (a launch or a knockback) and `grow` (bone meal on the
plants around the spot). An explosion rule marked `harmless` still booms but breaks nothing. Then
make it happen once and read `list: true`, which shows the last error a rule's commands hit.

A timer rule that gives items (`every` + `give`) is rarely what a child means: it fills his
inventory and then the floor, forever. When he wants "more cookies", offer a button instead
(`author_datapack` with `reuse_action: give`), so he gets one when he asks.

## Select a creature by its TAG, never by its entity type

Every creature you make carries two tags the mod puts on it: `wishmob`, and `wishmob_<id>` for that
one species. **That is the handle.** `@e[tag=wishmob_frog_knight]` is how you find the Frog Knight,
and `sculpt_creature`, `list_creatures` and `wishmob spawn` all hand you the selector so you never
have to invent one.

An entity-type filter is not a handle, and adding one alongside the tag buys you nothing:

- `@e[tag=wishmob_frog_knight,type=...]` — the tag already found exactly that creature. The type adds no
  precision at all, and every extra condition in a selector is one more thing that can stop matching.
- `@e[type=...]` with **no** tag is worse: it matches *every* creature you have ever made, not the one
  the child means. If you really do want all of them, `@e[tag=wishmob]` says so, and says it plainly.

**The same goes for an exclusion**, and this is the one that bites hardest. Sparing your creature from
its own damage cone is `tag=!wishmob`, not a type filter — because a search that stops matching goes
quiet, while an *exclusion* that stops matching does not. It keeps firing, on the thing you wrote it
to protect.

A selector that matches nothing is the quietest failure there is: it compiles, it loads,
`verify_datapack` passes, and the ability runs every tick doing precisely nothing.

**On a clock or a tick, every `@e` needs a TAG (or a `distance=`).** A tagged `@e[tag=…]` is
answered from just the things wearing that tag; an `@e` with only `type=`, `scores=` or nothing at
all makes Minecraft look at every entity in the world each time it runs — and one creature is dozens
of entities. To track anything that is not one of your creatures (a mob you knocked back, whoever ate
the cookie), tag it when you start (`tag @s add <pack>_on`), select it by that tag, and remove the
tag when you are done. `verify_datapack` names any selector that checks every entity.

## If it triggers on a position or a volume, the volume is the bug

The commonest way a tick rule does nothing is that the child is standing outside it. Check:

- **Is the box actually around the machine?** One session's box was `x[-1881..-1869]` and the child
  was standing at `x=-1867` — two blocks outside, so nothing he dropped ever counted.
- **Is it aimed at the machine that still exists?** If the machine was rebuilt or moved, coordinates
  baked into the pack point at where it used to be.
- **Be generous.** A volume a bit too big is invisible; a volume a bit too small looks like a broken
  machine.

## Say WHERE the output goes, and do not change it silently

If your pack makes something, decide where it lands and **tell the child**, in the same breath.
Spawning an item at the player's feet means it vanishes into his inventory and he thinks nothing
happened. Children have asked, in so many words, for a machine's output to land in a chest rather
than in their pockets — so if you pick a place, say which one. If a later version moves the output,
say so; he is still looking where you told him to look the first time.

## Never delete his world to make a feature work

`fill ... minecraft:air replace #minecraft:logs` removes blocks and **drops nothing** — it is a
deletion, not a harvest. One pack used it to "suck in trees" and silently erased the forest the child
had wished for fifteen minutes earlier. If you want the items, use `destroy`; if you want the blocks
gone, be sure that is what he asked for.

## Never put a clickable link in chat

A `clickEvent` / `click_event` in a `tellraw`, a title, a sign or a book runs **as the child who clicks it**,
and a child is permission 0: `/function`, `/give` and `/summon` answer *"Unknown or incomplete command"*. On a
Bedrock phone the link becomes a pop-up button that fails the same way. So a "[Click for Fireballs]" link is
dead for him on both. **Hand him the item, or say it in words** ("Throw a Fireball snowball!"). The compiler
strips any click it finds and keeps the words.

## Reuse, do not duplicate

Re-authoring the SAME `feature_id` updates the pack. A new id every time leaves a pile of old packs
all still running, all still firing their rules — which is how two versions of one feature end up
fighting over the same items.

## Give the button a PICTURE: pass `icon` to `add_template`

Every wish you teach becomes a button on Moddy's panel, and that button needs a picture. Pass
`icon` — a vanilla item id — and pick the thing a child would point at and say *"that one"*:
`minecraft:cake`, `minecraft:cow_spawn_egg`, `minecraft:bricks`, `minecraft:chest`.

Without it the picture is **guessed from the words**, and a recipe id you invented
(`sparkly_cheese_island`, `mega_lava_fort`) matches no keyword at all — so it lands on the same
amethyst shard as every other unmatched wish. A row of identical shards is not a menu: a child
scanning it is reading nothing, and has to hover every cell in turn to find out what any of them is.

It must be a **real** vanilla item id. A made-up one is dropped and the guess happens anyway, which
looks exactly like not passing one.

## Another player is a PLACE TO GO, never someone a wish does something to

With more than one child in the world, another player may be a **reference** — somewhere to go, or
something a compass points at — and never a **subject**: nothing is given to them, taken from them,
done to them, moved, or put around them. The same rule runs in `run_command`, in `add_template`, in
this compiler, and again every time a button or a function runs, so there is no way round it — and
**never build something else (a datapack, a button, an item) to get round a refusal.** If you were
told no, tell him kindly: *"I can't reach another player here."*

Where the world allows visiting, exactly these are allowed, with `@s` the child who
presses or uses it:

- `tp @s <name>` — take me to them
- `execute at <name> run tp @s ~ ~ ~` — the same, written the other way
- a compass that points at them: `make_item` with `effect: locate`, `PLACE: player:<name>` (or
  `player` for the nearest other player)

The other child is told *"✨ <name> came to visit!"*, and the same visit again within a minute is
turned away. **"Teleport them to me" is refused everywhere.** On a world where visiting is off even
these are refused — say so kindly.
