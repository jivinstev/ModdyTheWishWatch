# What we have learned about world events

Short, and every line is here because a disaster built by hand went wrong without it (a mod repo
built eleven of them; each rule below was a bug there).

## An event is ONE `stage_event`, never commands or a datapack

A wave, a flood, a meteor, a rain of things, a quake, a landslide, a countdown before a blast: all of
them are `stage_event`. Hand-written `fill` and `summon` commands cannot move over time, cannot put
the world back, and cannot tell a chest from dirt. `stage_event` remembers every block it changes,
never touches a chest, and gives it all back on `remove_event`. A sinkhole, a pit or the ground
swallowing something is an `impact_stamp` crater too, not a `fill ... air`.

## Pick the motion by what MOVES, not by the word he used

- **Something rolls across the land** → `moving_front` (a wave, a wall of sand, a tide of slime).
- **Something rises, stays, then goes away** → `layer` (a flood, a lake of lava, deep snow).
- **Something falls from far away and hits** → `falling_body`, and what it leaves is a `then`. A
  meteor always falls: a crater alone, with nothing coming down, is a hole, not a meteor.
- **A hole, a volcano cone or a hill appears** → `impact_stamp` with `form` crater, cone or mound.
- **Lots of little things fall over an area** → `from_the_sky` with `drop` a creature or a block.
- **The ground cracks** → `spreading_cracks`. **A hill comes down** → `slide`.
- **"Get out before it blows"** → `countdown` with `at_zero` the design that happens.
- **What is already there turns into something else** → `transform` ("turn the ground to gold",
  "make all the trees pink", "everything around me turns to ice"). Nothing lands on top: the ground,
  or the block/`#tag` in `replaces` (`#leaves` for trees), becomes `material` where it stands. A
  gold CASTLE is a building, not a transform — transform only changes what was already there.
- **Many of one thing, there to stay** → `crowd` ("lots of sheep in my field", "twenty pigs all round me",
  "a circle of iron golems", "a pile of cakes"). `drop` is the creature or item, `count` the number he
  said, `pattern` cluster / ring / around_me. Undo takes the whole crowd away at once. ONE mob, or a
  mob you will then change with `modify_mob`, is still `summon`; a crowd of HIS wished creature is
  `define_creature` with `count`.
- **Something SPINS** → `vortex`. Its `shape` is how it spins: `funnel` (the default) for a tornado, twister,
  dust devil, waterspout or fire whirl; `drain` for a whirlpool, a maelstrom or quicksand; `spiral` for a
  hurricane or a cyclone storm. Do NOT fake one with a `moving_front` or a `none` with `lift`.
- **Only shaking, wind or a glow, no blocks** → `none` with a `catch`.

The material is a block. A thing he named that is not a block is the nearest block in colour and
feel, and you tell him which one you used.

## What it does to what it catches is `catch`, and it is what makes it feel real

A wave that does not carry anything is a moving wall of water. `{where: inside, do: [carry, lift]}`
for anything that engulfs; `{where: near, reach: 20, every: 2, do: [shake]}` for a quake;
`{where: near, reach: 8, to: 20, do: [lift]}` for a blast. `hurt` never takes him below one heart
and `status` is only the kind effects — do not promise him more. Those are the HELPFUL ones (speed,
jump_boost, slow_falling, glowing, night_vision, water_breathing, resistance, …); slowing, blinding or
poisoning him is refused, so a chilly avalanche is told in words, not given as slowness.

## A vortex: pick the size by what he wants it to PICK UP, and say no more than his words

A vortex needs only `motion: vortex` and `size`; everything else has a good default. Add a field only when his
words name it:

- **Size is what it can do.** little lifts only light things (dropped items, chickens, cats) and tears up flowers;
  medium lifts people, animals, boats and minecarts; big lifts horses and his rides and tears up trees; gigantic
  (a "category 4", "EF5", "the biggest ever") lifts iron golems and rips through his builds — given back when it
  passes. "Picks up the cows" is at least medium; "picks up my horse" at least big.
- **His size word comes first**: giant, huge, enormous, massive and ginormous are all `gigantic`, never `big`.
- **A storm that spins is a hurricane** (`spiral`), whatever it is called — a cyclone storm, a typhoon, a spinning
  storm. A funnel is for a twister: something narrow that touches the ground.
- **`path` only when he says how it moves**: one spot → `stay`, about → `wander`, a straight line or "rips
  through" → `line`, round something → `circle` (with `at` for the building), "chases me" → `follow`. Unsaid, a
  whirlpool and a hurricane stay, a small tornado wanders and a big one cuts a swath.
- **`material` only for a kind of twister** (fire, sand, snow, leaves, candy floss, ender, water): it is what the
  spinning walls are DRAWN in. A plain tornado or a whirlpool needs none. A fire one sets alight what it catches.
- **Cats (or any creature) spinning round in it** is `drop: cat`: they spin in it, land and stay. Twin tornadoes are
  `count: 2`.
- **A hurricane brings rain and thunder** to the players near it (never the whole world's sky) for as long as it
  lasts, and its lightning is for show: it burns nothing.

## A wave or a vortex WRECKS, unless his words ask for a safe one

A tornado, a hurricane, a whirlpool and a wave smash through what they reach, as a meteor and a landslide do: his
builds break and stay broken until `remove_event` (which rebuilds every block; a chest is never touched), and what
they catch is hurt and may die — never his pets, the creatures he wished, or him below one heart. That is the
default, so say nothing for it. Only his words make one safe: "that doesn't break my house", "safe for my builds" →
`spares: ["builds"]`; "don't hurt the animals" → `spares: ["creatures"]`; "a safe tornado", "harmless", "a pretend
one" → both. Tell him it wrecks, and that undo builds it all back.

## How a vortex LOOKS comes from its material — set `look` only when his words describe it

Its material already picks the look: a tornado of wind, a dust devil, a snow twister, a candy floss twister and a
whirlpool are see-through swirling `mist`; a fire whirl is whipping `streaks`; a waterspout, a leaf whirlwind, an
ender vortex and a hurricane are `solid` blocks. So say nothing for it. Only when he describes the look itself does
`look` go in: "a see-through ghost tornado" → `mist`; "zoomy cartoon wind lines" → `streaks`; "a tornado made of
glass blocks" → `solid`.

## A meteor is a rock of `material` with lumps of `core`, sized from his words

Pick both blocks from what he said: a gold meteor is gold with raw gold lumps, an ice one packed ice
with blue ice lumps, a plain one magma_block with blackstone lumps. Its `size` sets how big
the rock is, and the crater it leaves is its own design with its own `size`, so "a GINORMOUS crater"
is a gigantic crater even under a medium rock. Match each to his words.

## A follow-on is a second design, staged FIRST with `now: false`

"A meteor that makes a crater, or a tidal wave if it lands in the sea" is three designs: the crater
(`now: false`), the wave (`now: false`), then the meteor with
`then: [{if: water_near, design: <wave>}, {if: on_land, design: <crater>}]`. A countdown's `at_zero`
is the same: stage what happens at zero first.

## When it happens

- **Now, where he aimed**: the default.
- **Every few days**: `every_days`. Flipping day and night hurries it; that is on purpose.
- **When something happens** (the boss dies, he steps on a block): stage the design with `now: false`,
  then `set_rule` with `do: ["moddyevent stage <name>"]`. Only `stage` is allowed there.
- **From an item**: `make_item` with the `event` effect and `DESIGN` the design's name.

Only his words make it repeat. Without "every", "whenever", "each time" or "when X happens", it
happens once, now. A rule he did not ask for goes on firing long after the wish, and he cannot see
it to turn it off.

## Where: what he aimed at, or a place he NAMED with `at`

Without `at` it happens where he was looking when he wished. When his words name a place he is not
looking at — "a meteor smashes into my castle", "it snows on my tower", "lava floods round my base",
"a giant wave hits my fort" — pass **`at: "<thing>:<spot>"`** (a building's id, a creature, or `me`):

- `top` (the default when you give just the building) — it lands ON it: the meteor hits the roof, the
  shower falls on the roof and round it;
- `around` — the ground beside it, its middle: a flood rises round it, a crack runs past it;
- a wave given just the building is born beyond it and rolls over it toward him.

The camera keeps that building in the shot. If the building is not standing, the tool says so and
stages nothing — never fall back to coordinates.

## Size is a budget, and "the whole world" is still a place

little, medium, big and gigantic are hard limits on how many blocks it may touch. A wish for
something that swallows the world is gigantic, which is a big place, not the world: say so to him in
one line rather than pretending.

## Changing one he has: `inspect_event`, then the same name

"Make the wave bigger" is `inspect_event` with its `name`, change `size`, and `stage_event` again under
the SAME name. A new name makes a second event he did not ask for.

"Make it last longer" is `seconds` (it keeps the event, and what it catches, going at least that
long), not a bigger `size`: a bigger size is a bigger event, which he did not ask for.

This holds even when the change makes the name wrong ("make it ice instead" on `gold_meteor`). If you
give it a name that fits, then `remove_event` the OLD design by `name`, and its old follow-on too
(the crater), so he is left with the ice meteor and not the gold one as well. "Instead" means the old
one goes.

## Does it stay? What it LEAVES stays, what only PASSES goes

Leave `afterwards` out and the motion decides: a crater, cracks, a landslide, whatever fell from the sky
(the chickens, the cats, the snow) and the damage a wave or a vortex did stay until he undoes them; a
flood, a countdown and shaking go away by themselves, and so does a wave or a vortex that spares his builds. Set it only when his words say so ("for ten seconds", "then it goes
away" → `restore`; "leave the hole" → `until_undo`). Do not choose `restore` to be careful: undo
always puts everything back, and an earthquake whose cracks mend themselves is not the wish.

## "It blows up" is never TNT

Summoned TNT cannot be undone, is not tied to the countdown, and lands wherever the command was
aimed. A blast is a design: a countdown whose `at_zero` stages an `impact_stamp` crater (with `fire`
blocks and a `near` lift and shake) where the blast should be. A crater, crack or slide that STAYS
breaks whatever it reaches, his builds included; one that puts itself back only cuts natural ground.
So to blow up his tower, put the crater on the tower and leave `afterwards` alone, and tell him
undo builds it back. A chest is never broken either way.

## Undo

`remove_event` with the `id` from the reply puts back every block — a crater is filled, a flood
drained, the chickens it rained taken away. A wish to undo it all, or make things how they were, is
`remove_event` for each id under EVENTS in what he owns; filling holes by hand leaves the rest.

## The picture comes first

`finish_building` photographs the moment you call it, and an event takes a few seconds to build up,
so the picture shows it just starting. Judge the wish on what `stage_event` said happened, not on how
far along the picture caught it.
