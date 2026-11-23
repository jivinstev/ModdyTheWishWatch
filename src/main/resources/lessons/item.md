# What we have learned about making things he holds

Short, and every line is here because something went wrong without it.

## A NEW thing is `about: 'new'` — even with his creature in view

Measured live, 4 of 4: four wishes for NEW things to hold were each bound to a creature he
owned, because it happened to be in front of him. If his words name a thing none of his things is, the
wish is `about: 'new'` in `plan_wish`, whatever the facts say is in his view. "Another" one of an
item he owns is a new copy (`start_from` it), not a change to his creature.

## Changing a thing he has: `start_from`, never a second one

This is the one that was measured, and it was the whole of what was wrong with items.

Asked to make a thing he already owns hit harder, an agent renamed it `… MEGA` — and the child ended
up holding **two** of them. Asked next to give it a second power as well, he got a **third**, which
had the new power and had lost the extra damage. By the end of five wishes he had three, none of
them the colour he had asked for, and the one he actually wanted did not exist.

None of that was a bad decision. The item's id used to be built out of its own name and effects, so
changing either **made a different item by construction**. That is fixed; this is how you use it.

1. **`inspect_item`** — what he already has, by id: its name, icon, colour, count, and what it does.
2. **`make_item`** with `start_from` set to that id, and **only the fields that change**.

Everything you leave out is kept.

```
   a new colour                    ->  start_from + colour
   stronger, longer, bigger        ->  start_from + params
   "…and can it do X too"          ->  start_from + effect: the WHOLE list, old + new
   more of them, a whole pile      ->  start_from + count
```

**An edit changes the one already in his hand — do not hand him another.** `make_item` rewrites
the stacks he is holding to the new version and says so; if you then run the give as well, he ends
up with two of it, the old one and the new one, which is the exact thing a change must not do. Hand
one over only when the result tells you to (he was not holding one), or when he asked for more.

**A refused change is not a reason to make a new one.** When `make_item start_from=…` is refused —
the real item underneath cannot do what was asked (a fishing rod cannot be thrown) — the thing he
has is unchanged and stays his. **Tell him what cannot change and what it can do instead.** Making a
fresh item to get round the refusal leaves him two: the old one, untouched, and a second he did not
ask for. For the rest of that wish a `make_item` without `start_from` is refused unless it passes
`as_new: true`, which you send only when he really asked for a separate, new item as well.

**Leaving a field out is not the same as guessing it.** `make_item` demands every parameter on a new
item precisely because a default cannot tell an ambiguous wish from an explicit one. With
`start_from` the unmentioned fields are not defaulted — they are inherited from a thing he already
owns, which is a different claim and a true one.

## Adding an effect means listing the old one too

`effect` is the whole list, not a delta. A wish that ADDS a second power to something that already
has one is `effect: "<the old one>+<the new one>"` — naming only the new one is how a wand stops
doing the thing it was made for, and he will notice before you do.

Parameters are merged **per effect**, so you only need to send the ones that change. Sending
`{"lightning":{"HEARTS":"12"}}` on an item that also ignites keeps the burn exactly as it was.

## One call, not two halves

A child's wish often has more than one part, and that is normal. When he describes a weapon that
also DOES something — it hurts, and it gives him something, or it hurts and it lights the place up —
both halves are the wish, and the weapon half is not optional scenery. Build them in ONE call
(`hurt+rich`, `hurt+light`) — do not pick the nearer half, and do not go and `author_datapack` the
other half separately.

## It is a thing he HOLDS, so say what to do with it

`held` (hold it and use it), `thrown` (goes off where it lands), `placed` (put it down and it keeps
working), `magic_block` (put it down and it becomes a BLOCK that does something by itself), `on_hit` (he swings it and the wish happens to what he hit), `arrow` / `on_shot` (he shoots
it: where it lands / the instant he fires), `on_consume` (he eats or drinks it, and the wish comes
after), `on_block` (his shield blocks a hit, and the wish rides that), `on_throw_land` (a real
throwable lands), `on_use_done` (a horn, brush or spyglass, when he is done), `on_mine` (he mines with it), `worn_*` (he puts it on and
it works while he wears it), `plain` (it does nothing).
Pick from what he said, and tell him which one it is when you hand it over — a wand he does not know
to use is a wand that does not work.

**Name no mouse button and no key.** Children play on Java AND on Bedrock — a phone, a tablet
or a controller — so "right-click", "left-click", "press L", "hold W" or "Shift" mean nothing to half
of them. In everything he reads (Moddy's lines, an item's lore, a sign) say what he DOES: "use it",
"hold it and use it", "hit something with it", "throw it", "put it on", "open your wish watch".

**A real item's own use stays; the wish rides on it.** When you build on a real item that
already DOES something when he uses it — a bow draws and shoots, food is eaten, a potion is drunk, a
shield blocks, a snowball is thrown — the wish must not take that away. A `held` wish on one of
those used to cancel it: the bow would not draw, the shield would not block. On a bow, crossbow or
trident the wish rides the shot, and there are two moments to choose between: `arrow` is WHERE IT
LANDS (the arrow carries the wish and it happens at the impact), and `on_shot` is THE MOMENT HE
FIRES (a beam leaves the weapon to the first thing in its way and the wish happens there, at once —
the laser, the ray). His arrow still flies either way. Food and drink are `on_consume`: he eats or
drinks it the way he always does, and the wish happens when he has FINISHED — a magic cookie is still
a cookie. A shield is `on_block`: he raises it and blocks as always, and the wish happens each time
it stops a hit; to send a blocked arrow flying back the way he faces, add the `reflect` effect. A
real throwable (a snowball, an egg, a pearl, a wind charge) is `on_throw_land`: vanilla throws it,
once, and the wish happens where it lands. A horn, a brush or a spyglass is `on_use_done`: he uses
it as always and the wish happens when he is done.
`make_item` moves a shape that would take the use away
onto the one that rides it and tells you; when no shape rides that use yet it refuses, and the thing
is made from scratch instead — say so to him in plain words.

**Some things are meant to do nothing, and that is `plain`.** Around any machine, most of what he
names is stuff to put IN it, or stuff to collect: an ingredient, a spare part, a set he wants a full
row of. Giving one of those a behaviour is not generous, it is wrong — he did not ask for a bag of
flour that heals him. If he expects something to HAPPEN when he uses it, pick a shape that fires;
if the thing simply IS, pick this one and say so when you hand it over.

**A weapon is a shape, not a decoration.** When he names a thing you swing — a hammer, an axe,
claws, a bat — or says hit, whack, smack, punch, bonk or attack, that is `on_hit`. Answering it with
`held` gives him something he has to use from his hand instead, which works well enough that nobody
notices it is the wrong thing.
**The shape comes from what the thing IS, not from the verb he used.** Anything he swings is
`on_hit` even when he says it shoots, fires or blasts something: the power lands on whatever he
hits with it. Something he AIMS (a wand, a staff, a blaster) is `held`, and a bow, crossbow or
trident rides its own shot.

**A thing he WEARS is a shape too: `worn_head`, `worn_chest`, `worn_legs`, `worn_feet`.** Boots,
a helmet, a hat, a chestplate, leggings, wings, a cape — when he says he puts it ON, it goes on his
body and its effect works for as long as he wears it, and stops when he takes it off. A wearable
answered with `held` is a charm he has to use from his hand, and that is exactly the wrong thing he got
before this shape existed.
- Made from scratch it is drawn on him as leather in its colour. If he names what it is MADE of —
  gold boots, a diamond helmet, netherite leggings — build it on that piece with `base_item`
  (`minecraft:golden_boots`), so it looks like that piece on him and in his hand.
- Wings or a cape that let him fly are `worn_chest` built on `minecraft:elytra`: they glide.
- **"Double jump" cannot be done.** A true double jump is not possible: the game never tells us he
  pressed jump in mid-air. Give him `worn_feet` + `jump` — a super bounce that lands soft — and say
  so in words he understands: *"these boots make you jump REALLY high, and you land softly."*

**When what he asked for IS an enchantment, use the `enchantments` property, not a datapack.**
More drops, faster digging, a throw that comes back, better fishing, water freezing under his feet:
vanilla already does each of these, so give the real item (`base_item`) the enchantment
(`"properties": {"enchantments": {"fortune": 3}}`). It works in the slot the enchantment belongs to
(a pickaxe's in his hand, frost walker on his feet), and it follows vanilla's rules — several arrows
at once is `multishot`, which only a crossbow fires, so build that one on `minecraft:crossbow`.

**A bow that fires several arrows in one go is a CROSSBOW with `multishot`**: that is the
real game doing exactly what he asked, so build `minecraft:crossbow` with `{"enchantments":
{"multishot": 1}}` and tell him it is a crossbow — a bow cannot fire three. Never a datapack.

**A tool that breaks MORE than the block he hits is the `mine_area` property**: `3x3` (a
hammer, the face he hit), `5x5`, `vein` (the whole ore vein or tree trunk at once) or `hole` (a
shovel that digs a round hole), on the real pickaxe, shovel or axe. Each extra block drops and wears
the tool as if he mined it by hand. An ordinary pickaxe wish ("a super fast pickaxe") is
`enchantments` efficiency, never `mine_area`.

**When a bow or crossbow should LAUNCH something else, it is the `projectile` property.** What
flies out of a real launcher is part of what the launcher IS: build it on `minecraft:bow` or
`minecraft:crossbow` with `"properties": {"projectile": "fireball"}` (or `egg`, `snowball`,
`wind_charge`), shape `plain`, and `infinity` so he can draw it without running out. The shot keeps
the bow's own draw and range. An `arrow` effect that makes eggs or fire where the arrow LANDS is not
the same wish: he watches an arrow fly.

**When he wants HIS pets to come to him, it is the `call_pets` effect.** It brings the wolves,
cats, parrots or horses he has tamed (KIND names which) and never makes a new animal: a fresh wolf
is somebody else's dog, not his. On a real goat horn it is shape `on_use_done`, so he blows the horn
and they come; on a made whistle or bell, `held`.

**When it takes him HOME, it is the `teleport_home` effect.** Home is the bed he last slept in (or
his respawn point), and the world's spawn if he has none, so tell him: sleep in a bed to choose
home. On a real ender pearl it is shape `on_throw_land`: he throws the pearl and goes home instead
of to where it lands, with no pearl damage. On a made thing he clicks, `held`.

**When a real bucket should stay FULL, it is the `never_empty` property.** Build it on the real
bucket (`minecraft:water_bucket`, `lava_bucket`, `powder_snow_bucket`, `milk_bucket`) with shape
`plain` and `"properties": {"never_empty": 1}`. He pours or drinks exactly as with any bucket, and it
is still full afterwards. A made thing that places water on a click is not a bucket: it can't fill a
cauldron, and it doesn't pour where a bucket pours.

**When it is a bag or pouch for ONE KIND of thing — only snacks, only arrows, only flowers — it is
the `holds_only` property on a real bundle**: `base_item` `minecraft:bundle`, shape `plain`,
`"properties": {"holds_only": "food"}` (or arrows, flowers, seeds, ores, gems, fish, …). Anything
else stays on his cursor. A bundle holds one stack's worth, whatever it is called: an enormous bag is
not possible, so tell him that plainly.

**When it FINDS a place — a compass or a pointer that shows him the way to the nearest village,
temple, stronghold, biome or cave — it is the ready-made `locate` effect**, with `PLACE` the
place he named (village, ancient_city, stronghold, temple, jungle, ocean, cave, …). Build it on a
real compass: `base_item` `minecraft:compass`, shape `held`. The needle turns to the real nearest one
and stays; he is told how far and shown the way. A place that is not in the list, or none near
enough, is said plainly — never a plain compass pointing at spawn, and never a village you build. Do not
run `/locate` yourself and hand him a compass pinned to that one spot: the effect finds the nearest
one again every time he uses it, wherever he has walked to.

**When it gets STRONGER at a certain time, it is the `stronger_when` property**. Stronger
at night, in a thunderstorm, deep underground, in the Nether or underwater: build it on the real
sword or pickaxe with shape `plain` and `"properties": {"stronger_when": {"when": "night", "stat":
"attack_damage", "amount": 6}}` (`stat` `mining_speed` for digging, 1 = twice as fast; `when`
`below_y` with `"y": 0` for "really deep"). While he holds it and the time is right it hits harder
or digs faster, and it goes back to normal after. Never a clock datapack that gives him strength:
that is the effect on HIM, not the sword, and it stays when he puts the sword away.

**When he eats or drinks it FASTER — a soup he can gulp, a snack that is gone in a moment, a
drink through a giant straw — it is the `eat_speed` property on the real food or drink**. A
straw or a cup is how it LOOKS (its name and icon), not a separate tool, and never a status effect: `base_item` the real one
(`minecraft:bread`, `minecraft:mushroom_stew`, `minecraft:potion`…), `"properties": {"eat_speed": 4}`
(2 is twice as fast, 8 a single gulp). The food still fills him the way it always did. A cake is a
block he eats from, not a thing he holds: for a fork or spoon that gobbles CAKE, it is the
`cake_bites` property (how many bites one click takes, up to 7) on the thing he holds.

**When it LEAVES something behind him as he walks — fire, flowers, snow, light — it is the `trail`
property**: build it on the real thing he holds or wears (a sword, boots) with shape `plain`
and `"properties": {"trail": "fire"}`. It works while it is in his hand or on his body; a flower only
lands where a flower can grow, and a fire trail keeps him from burning. Freezing WATER into a path
under his feet is not a trail: that is real boots with the `frost_walker` enchantment.

**When it SHOWS him where an ore is — a treasure map, special glasses, a detector — it is the
`reveals` property**: build it on the real thing he holds or wears, shape `plain`,
`"properties": {"reveals": {"ore": "diamond", "how": "glow"}}`. `glow`: the nearest ores within 8
blocks shine through the stone while he has it. `beep`: a bell only he hears, faster and higher as he
gets closer. Never a clock datapack that summons glowing markers: it lingers after he puts it away.

**When it shows him the way to ANOTHER PLAYER — "a map to my friend", "a compass to <name>" — it is a
compass, never a map**: `make_item` with `effect: locate`, `PLACE: player:<name>` (or `player` for
the nearest other player). A real map cannot follow a moving player, so do not hand over a map and call it
done. It only works where the world allows visiting; elsewhere say kindly *"I can't reach
another player here."*

**When his bag TIDIES itself or throws out rubbish, those are ready-made**: `sort_inventory`
stacks like things together and lays them out in groups (blocks, food, tools, the rest) when he uses
it; `drop_junk` drops dirt, cobblestone, gravel, rotten flesh, seeds and the like at his feet. For a
bag that throws the junk out BY ITSELF when he is full, it is the `drops_junk: 1` property instead,
and for one that keeps everything sorted BY ITSELF ("automatically"), the `auto_sort: 1` property.
The junk list is fixed: if what he calls junk is something else, say so. His hotbar is never moved.

**When a fishing rod CATCHES something special — gems, a surprise, loot — it is the `catches` property
on a real rod**: `base_item` `minecraft:fishing_rod`, shape `plain`, `"properties":
{"catches": {"what": "minecraft:emerald", "chance": 25}}`. `what` is one item id, `"random: id, id, …"`
for a different one each time, `treasure_chest` (a chest full of buried treasure) or `enchanted_book`;
`chance` is how often it comes up instead of the fish (about 25 for "sometimes", 100 for "always"). He
still casts and reels in the real rod. Never a datapack that gives him the item when a stat counts a catch.

**When it PULLS him somewhere — a grappling hook, a swinging vine — it is the ready-made `grapple`
effect on a thing he THROWS**: `base_item` a snowball, an egg or a trident, shape
`on_throw_land`. Where it lands, he is swung there through the air (onto the top of a wall it hits)
and lands without fall damage. On a real fishing rod it is the `grapple: 1` property instead: cast
onto a block, reel in, and he is pulled to the bobber. Never a click that just lifts him straight up.

**When it TURNS one thing INTO another, it is `turn_into`** — a block he points at, the
blocks where it lands, or a thing in his bag: one ready-made effect, not a datapack. You name `FROM`
(what changes; a list is fine, and `any_block` when he said "blocks" — ground and building material,
never a chest) and `TO` (what it becomes; `minecraft:air` makes blocks disappear, `furnace` makes each
thing what a furnace makes of it). WHICH it works on is the SHAPE's, never yours: a `machine` takes one
FROM out of his inventory and gives him the TO; a thrown, shot or swung thing changes the matching
blocks where it lands; a HELD one changes the block he points at when it matches FROM, and otherwise
turns a FROM in his inventory into the TO. A material is both: `TO` gold is a gold block in the world
and a gold ingot in his hand. Held with `RADIUS` 0 it changes exactly the block he is looking at; only
say a bigger radius when he asked for a big area (a thrown thing needs at least 1; a machine needs none).
**Several blocks, one per use, is still ONE item.** "All the colours", "a different flower each
time": `TO` (and place's `BLOCK`) takes a list with how to choose in front, `"cycle: minecraft:red_wool,
minecraft:orange_wool, …"` for the next one each time in that order, or `"random: …"`. Never a set
of items, one per colour: he asked for one thing that does them all.
**"This", "these blocks", "the ground": read IN VIEW WHEN HE WISHED first**. Your brief lists what he
was looking at and carrying the moment he wished. For a turn-into whose FROM he did not name, FROM is the block at
his crosshair (or the most of what is in view; what he carries, for "the stuff in my bag"), never a guess at
"stone": "turn these blocks into gold" while he faces andesite is `FROM` `minecraft:andesite`. `find_content` with
`around: true` searches only those ("which stone does he have").

**A machine he FILLS is `load_and_pour`, not `turn_into`**. `turn_into` swaps one thing for
one thing. When the machine needs two or three things in before it makes anything, when more of the
main ingredient should make a bigger one, when add-ins belong in the name, or when he wants it put in
a chest beside the machine, reach for `load_and_pour` on the `machine` shape. `MAX` is how many of the
FIRST ingredient it holds: 1 means one size, 3 or more gives Small, Medium and Big. `WHERE` is `chest`
only when he said a chest; a chest machine with no chest beside it says so instead of pouring. The
machine already says what it takes, what is missing and (on a sneak-pull) how it works, so do not
promise him a different set of words. If the ingredient or the result is not in the game yet,
`make_item` it FIRST and name that id — and when it is something to eat or drink (it nearly always
is), make it with shape `on_consume` and effect `food`, or he is handed a slushie he cannot drink. There is no Create on this server, so a wish that names Create
still gets this machine, and say so plainly.

## Supplies come WITH the thing: `kit`, never a raw `give`

When a wish hands him things to USE something with — the melon and cocoa for his smoothie bar, the
seeds for a planter, the arrows for a bow, the fuel for a cooker — pass them as that thing's `kit`
in the same `make_item` call: `"kit": "minecraft:melon 4, minecraft:cocoa_beans 8"` (a made item's
id works too; up to 6 things, 1–64 of each). They are handed over right after it, and handed over
AGAIN whenever he re-wishes it — on its own, or as one part of a bigger wish.

**Never a separate `give` in `run_command` for those.** A give beside the thing belongs to nothing:
his wish is saved as a wish and its PARTS, and a part re-wishes only what the part itself makes — so
re-wishing the bar would come back with no melon. A machine he breaks and gets handed back comes back
WITHOUT its kit, on purpose: the supplies were for using, not for every re-placing.

## A block that DOES something is `magic_block`, and its dials are `block`

When he wants a BLOCK — something he puts down, can see and can break — whose job is to do
something to the world around it, that is shape `magic_block`, not `placed`. `placed` is an
invisible marker on a two-second beat, which reads as the wish having failed when he asked for a
block. Build it from the parts, never from the name of the thing:
- **when** — `every` (on its own, on a beat), `near` (when something comes close), `stepped_on`
  (when something stands or LANDS on it), `broken` (once, when he breaks it — the item is used up).
- **who** — everyone, players, mobs, hostile, animals, items. Anything that hurts reaches `hostile`
  unless he said otherwise, so his own animals are safe; say who it hits when you hand it over.
- **radius** 1–16, **where** `here` (at the block, to everything near) or `on_each` (at each one it
  finds — a lightning bolt on every monster), **look** a real block id.
- The effects are the ordinary ones plus three made for this: `pull` (sucks things in toward it),
  `fill_blocks` (fills the empty space round it — water, snow, cobwebs — and never eats a block
  that is already there), `outcome_table` (a surprise each time: mostly presents, now and then a
  creature, rarely a small bang). Lifting things up is `fly`; bouncing is `fly` on `stepped_on`.

Three worked examples, the ones on the wish buttons:
- *a lucky block* — `when: broken`, `outcome_table` with the presents he would love, a silly
  surprise creature, `look: minecraft:gold_block`.
- *a black hole block* — `when: every`, `seconds: 0.25`, `who: everyone`, `pull` STRENGTH huge.
- *a bouncy cloud* — `when: stepped_on`, `fly` (SECONDS 1, RATE 9) plus `protect`, `look:
  minecraft:white_wool`. Landing on it from a height does not hurt: the slow fall starts before he
  touches it.

A NEW BLOCK of his own that does nothing else — he names a block, not a power — is still
`magic_block`, with NO effect and a `look`: he gets a block he can put down and break, which is what
"a block" means to him. `plain` is a keepsake in his hand and cannot be placed.

A block made of a chest, a furnace or anything unbreakable is refused. A block he can walk through
is `walk_through: true` — but then it cannot go off when broken, because there is nothing to break.
Undoing the item takes every placed one down, and takes back everything `fill_blocks` filled.

## A thing that belongs SOMEWHERE: `at`

When his words put the thing in a place — "a slushie machine in my kitchen", "my swords in the chest in
my castle", "a healing totem on the roof", "cars in my garage" — it does not go in his hand. Pass
**`at: "<thing>:<spot>"`** to `make_item` (a building's id, a creature or `me`; every building has
`inside`, `top`, `front`, `beside`, `behind`, `around` and the compass sides without marking any):

- a thing he PUTS DOWN (`placed`, `machine`, `magic_block`) is put down there and works as if he had
  placed it;
- anything else goes into a chest or barrel at that spot, or lies there for him to pick up.

Then DO NOT also hand it over, unless he asked for one in his hand too. A REAL game thing (a cake, a
chest, a minecart) is `run_command` with the same `at`: `summon minecraft:minecart ~ ~ ~` with
`at: "<garage id>:inside"` — `~ ~ ~` is that spot, and a thing put at a named place is never tidied away
as a test prop.

## The picture: pick the THING, not a shape

`make_item`'s `icon` menu is grouped (animals, food, toys, vehicles…) and has over a hundred real
things in it. If the thing he named is on it, use that name — a round shape for a creature that has
its own picture is the wrong answer even though it is round. A name ending in `*` keeps its own
colours and ignores `colour`. A two-part picture takes `accent` for its second colour (a body and its
spots, a car and its windows); leave it out and a lighter shade of `colour` is used. If nothing fits,
pick the closest picture AND pass `icon_wanted` with the thing he actually asked for — that is how
the missing pictures get drawn next.

## Some wishes are already in the game

A few wishes that sound like new magic are things a real item already does, and the real item is the
whole answer: a trident with loyalty comes back, frost walker boots freeze the water under his feet,
horses and rabbits follow a golden carrot. `make_item`'s description lists them. The thing he gets is
sometimes not the thing he named — boots, not a wand — so tell him what it is and how to use it.

## If nothing in the catalogue fits, write it

`author_datapack` is the right answer when no ready-made effect does what he asked. What is **not**
the right answer is picking the nearest one: a thing that ALMOST does what he asked is the answer he
will notice. And when you cannot make it do the thing at all — a rope he swings on, a rod that
reels in something vanilla never gives, a bag that tidies itself — SAY SO in his words, and say what
you made instead. A quiet substitute reads to him as the wish working wrong.

---

*No example here is taken from a refinement ladder, deliberately, and a test enforces it. This file
is appended to every `inspect_item` and `make_item` result — so an example lifted from a fixture
hands the agent the worked answer to the test it is about to sit, and the run then measures recall
of this page rather than whether the lesson generalises. Describe the shape of the mistake; never
the case that found it.*

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

## Where a thing you hand him lands — and what to tell him

Everything a wish hands over — `wishdatapack <id> give`, a plain `give` in `run_command`,
`give_items`, an egg — goes ONE way: into his **hotbar** (onto a matching stack first, then a free
slot), and when the hotbar is full into his **Wish Bag**, the bag pinned in **hotbar slot 2** beside
Moddy. Never his main inventory, never the floor. The result of the command tells you where each
thing went (`Handed over: … → hotbar slot 8; … → his Wish Bag`), and Moddy has already told him by
name. So never say "check your inventory": if you mention it, say the slot or "your Wish Bag", the
way the result does. A thing in the bag comes out when he uses the bag and takes it.

What an item, a machine, a rule or a creature gives by ITSELF later — a vending machine's pull, a
coffee maker's coffee, a machine's refund — is not a hand-over: it goes to his hotbar, then his
main inventory, then the floor at his feet, never the Wish Bag, and Moddy says nothing. The
item's own message and sound are what tell him.

## Checking "he has it": name it by its KEY, and only what THIS wish handed over

`{"kind":"item_in_inventory","what":"boomerang_plain"}` — the feature id `make_item` printed — counts
what THIS wish handed over that he still has, in his hotbar, inventory or Wish Bag. The item id
(`minecraft:trident`) or its name works too. Something he already had, or an older wish's, does not
count, so hand it over first. For a thing you CHANGED in his hand without handing it over, check the
effect instead (`datapack_active` with its feature id).

## What you summon to KEEP gets `keep: true`; a test prop never does

When the wish ends, every non-living thing your `run_command` calls summoned — minecarts, boats,
armour stands, dropped items — is tidied away as a test prop (three cars parked "forever" in a
garage were deleted that way, right after the reveal promised they would stay). So the call that
summons something he should KEEP — a car, a boat at his dock, a scarecrow, a decoration — passes
`keep: true`. The call that summons a stand-in to test on (a NoAI pig, an armour stand to aim at, an
egg to feed a machine) leaves it out, and those are cleared for you. Keep the two in separate calls.
Anything you summon to TRY the wish on is a test prop even when he might enjoy it (a target, a mob to
hit): a present is what he asked for. And `keep` cannot keep a mob frozen with NoAI, a dropped item
or a thing in flight — a statue he asked for stays by its `CustomName`, and an item he should have
goes in his hand with `give`. A living mob with its AI on is never tidied, keep or not.
