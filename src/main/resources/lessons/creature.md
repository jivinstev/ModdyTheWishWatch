Rules learned from creatures that actually disappointed him. Each cost real wishes.

**Look at what you BUILT, not at what you meant.** `sculpt_creature` hands back the colour
composition by volume and any parts nobody can see. Read it before you show him — for six
consecutive revisions a frog knight drifted in colour and nothing ever said so. The knowledge was never
the problem; the absence of a mirror was.

**Get the reference RIGHT before you use it, or you will build the wrong thing confidently.** This
file previously asserted that a famous game villain was "mostly tan". That was wrong — the
game maker's own reference has his shell and head green (roughly half his visible body) with tan on
the plastron, muzzle and limbs. A live run proved the cost of the error: told "mostly tan", the
agent dutifully built a 65%-tan frog knight. **The lesson mechanism works, which is exactly why a wrong lesson is dangerous** — it is
obeyed. If you are unsure what a character looks like, say so rather than guessing a proportion.

**A part nobody can see is a part you didn't build.** A face box entirely inside the hair boxes is a
blank lump to a child, however carefully it was specified. Hair *frames* a face; it does not enclose
it. If the critique lists a buried part, move it out or shrink what covers it — do not just add more
detail on top.

**When a real character is asked for, name its colours BEFORE you sculpt** — and in what
*proportion*, not just which ones. "The frog knight is green and tan" is not enough to stop you
building an all-green one: you have to commit to something like "back and head green, belly and muzzle and
limbs tan". Proportion is what makes a creature read as itself — but state a proportion only if you
actually know it.

**A character stands the way it is DRAWN, not the way its animal does.** A comic-strip cat or a cartoon dog often
stands and walks upright on two legs like a person: two legs under a tall body, arms at the sides. Do
not start them from a four-legged body (`start_from` a cat, a dog, a wolf). `look_up_character` and the brief
say how it stands and whether it has a tail; build exactly that, and a rabbit's tail is a short puff, not a
long one. `⚠ STANCE` or `⚠ TAIL` from `sculpt_creature` means the build disagrees with the reference.

**Every eye gets a pupil, and a detail stands in front of the face, not in it.** An eye that is one
solid box reads as a blank face (an elf hero and a blue hedgehog with flat blue and green eyes). Put a smaller,
darker pupil in front of each eye, and a one-unit white highlight if the character's eyes shine; leave the
eyes solid only when his words ask for solid or dot eyes. `⚠ BLANK EYES` from `sculpt_creature` means you
forgot. Sit a face detail at least a whole unit proud of the face it is on: half a unit is rounded away.

**`MAIN COLOUR WRONG` from `sculpt_creature` means fix it before he sees it.** It fires when a
named character's main colour went missing from your build. Re-sculpt the same id with that colour as
the main one; a stand-in of your own design keeps the colours a child recognises.

**A revision can make it worse, and nothing will stop you.** Six consecutive revisions of one frog knight
each made it less like the character, because every one iterated on the previous one and none compared back to
the character. When you revise: check the new critique against the old one. If a colour that
belonged to the character has shrunk, you have broken something the child liked — say so and put it
back rather than continuing to build on it.

**Measure the body before you aim anything at it.** Use `inspect_creature` for mouth, eye and hand
positions. Fire once poured out past a frog knight's snout because the coordinates were guessed.

**Everything that sits ON the head hangs FROM the head: `"parent":"head"`.** Hair, a fringe, eyes,
brows, mouth, nose, ears, horns, a hat, glasses. The head turns to look at the child, and only the
head's children turn with it. A "copy of me" whose big hair box was not under `head` turned its face
towards him and left the hair behind, until the back of the hair was over its face. The sculpt tool
now re-hangs such a part and tells you (`ATTACHED TO THE HEAD`). Build it right the first time.

**A creature the child invented has no reference — so his words ARE the spec.** "A squishy purple
four-eyed slug" means four eyes and purple, and those are checkable. Do not quietly build two eyes.

## A creature that belongs IN a building: `at: "castle:throne"`

"A boss on his throne", "a guard at the gate", "a dragon perched up on his castle": build the
building with a named **spot** (`build_structure` `spots`, see the building lessons), then pass
**`at: "<building id>:<spot>"`** to `sculpt_creature` or `define_creature` — or run
`wishmob spawn <id> at castle:throne`. He is put on the spot **after** the building is finished,
facing the way the spot faces. **To keep him THERE** — a boss on his throne, a guard dog by the door,
a knight at the gate, a cow in each pen — follow it with `modify_mob` (`target` his id, `scope`
one) and **`stay: 0`** (or a few blocks for a stall or a pen): otherwise he wanders off a minute after
the reveal. Pushed or lured away, a stayer walks back, even after a reload. `stay` and
`follow` are opposites; a pet that goes with him never gets `stay`. Never stand him at the building's anchor: that
is its foundations, and a creature conjured there suffocates. In a wish that built something and
names no spot, the creature goes to that building's `front`. If the building is not standing, he
appears where the child aimed instead, and the tool tells you why. A REAL mob (`summon`, a pet
you then `modify_mob`) goes on a spot with `wishspot castle:roof run summon minecraft:… ~ ~ ~` —
never at coordinates you worked out by hand. **A building needs no marked spot for the obvious places
**: every one has `top` (its roof), `inside`, `front`, `beside`, `behind`, the four compass sides
and `around` — "a dragon on my roof" is `at: "castle:top"`, "a herd around my farm" is
`at: "farm:around"`. And the thing can be a creature or him: "a baby dragon on my dragon's back" is
`at: "<dragon id>:top"`, "a puppy beside me" is `at: "me:beside"`.
A roofed room he sits in gets lights IN it (lanterns, glowstone, sea lanterns or
`candle[lit=true]` within a few blocks of the spot), built with the room: light drops one level per
block and stops at walls, so a castle's lights one storey up leave his throne room black, and a
boss on his throne came back as a dark outline. If `build_structure` says **DARK INSIDE**
for the spot, light it before you put him there.

## Which creature? YOU say, in `plan_wish`'s `about`

Nothing decides this before you any more. "make him faster", "and bluer", "put a hat on my
horse" are about one of his creatures: the catalogue says what he owns, and the wish note's FACTS
say what is in his view and what he touched last — use them, then say the id in `about` and
change THAT one with `modify_mob`/`sculpt_creature`. But a creature in his view is not what he
said: a wish that names a new thing (a shield, a lamp, a pet he does not have) is `about: 'new'`.

## Only one that is OUT IN THE WORLD can be hit, ridden or removed

The catalogue lists every creature DESIGN he has, and marks each one: "1 out in the world", or
"none out in the world now — design only". A wish that acts on one — kill it, hit it, ride it, feed
it, take him to it, make it bigger or better — can only mean one that is **out**: a "design only"
T-Rex is not there to remove, and offering it lost a live wish (he picked the design-only
one, the removal found nothing, and the T-Rex he meant kept stomping). If only one that fits is
out, that is the one — act on it, do not ask. If several are out and the wish removes one, call
`ask_which` with only those (`about: 'instance'`). A wish for ANOTHER one ("make me another T-Rex",
"spawn my dragon again") is about the design, out or not: `ask_which` with `about: 'design'` if you
must ask.

## A PET is a real mob plus `modify_mob`, not a sculpt or a datapack

A pet that goes everywhere with him, a scary mob made gentle, an animal he can ride, or one that
sniffs out ore for him: each names a mob the game already has.
Summon it, then make ONE `modify_mob` call:

- `follow: true`: it keeps to him.
- `friendly: true`: it never attacks a player, whatever it is.
- `rideable: true`: he climbs on by using it (a free hand, or holding his watch) and it goes
  where he looks.
- `finds: 'diamonds'` (or gold, carrots, treasure, or a block id): it walks to the nearest one and
  sparkles over it.

Add `scale` for "tiny" or "baby". Do not sculpt a lookalike of a mob that exists, and do not write
a datapack for a pet.

## It LEAVES something behind, or DROPS something for him: `apply_effect` `trail` / `drops`

"A frost wolf that leaves frost where it steps", "a slug that leaves moss behind it": `apply_effect`
`trail` on its tag, with `BLOCK` a thin thing it walks over (`minecraft:snow` for ice or frost, a
flower, a carpet). The trail fades by itself and never replaces anything. "A hen that lays golden
eggs for me", "a sheep that drops cookies": `apply_effect` `drops` with `ITEM` and how often. It
stops while a few lie unclaimed, so it never floods his world. Give these only when he ASKED for
something to be left or dropped: a pet that was not asked to leave anything leaves nothing.
A REAL mob takes them too: summon it wearing a tag (`summon minecraft:bee ~ ~ ~ {Tags:["honey_bee"]}`)
and pass that tag to `apply_effect`.

## HARMLESS is `friendly`; FOLLOWING is `companion`. They are not the same thing

A sculpted creature's `base`, its instinct, is what it does on its own, and `companion` means it FOLLOWS the
nearest player everywhere. It does not mean harmless. Two players sculpted onto a court "as
companions, so they don't attack" walked off the court to follow the child, and his next wish was
that nobody was playing.

- To make a creature harmless, give it the `friendly` power. Without `friendly` (or `skittish`) it
  attacks players.
- Anything that belongs to a PLACE (on a stage, in a pond, behind a counter, in a pen) is
  `wild` + `friendly`, so it stays where you put it.
- Use `companion` ONLY when he asked for it to follow him, or asked for his own pet or sidekick.
  The same goes for `modify_mob follow: true`.
- `guardian` fights monsters near it and never a player; `hunter` hunts players and animals;
  `coward` runs away from players.

The tool's reply says when a creature will follow him or attack him. Read that line.

A REAL mob takes the same `base`, `powers` and `abilities` through `modify_mob`, with the same
meanings: "an army of real endermen that fights for me" is real endermen + `modify_mob` with
`friendly: true` and `base: "guardian"`. A real mob keeps its own body (it teleports, it hates
water), so the body powers (`flies`, `hops`, `naps`, `boss`, `explodes`) need a sculpted creature.
Staging a crowd of them with `stage_event`? Put those fields in its `traits`, so every one ARRIVES
that way, and change them later with the `@event:` handle its reply gives you.

You never need to make a creature immune to its own ability. When its datapack makes fire,
explosions, poison, lightning or frost, it is made immune automatically.

## WHO it hurts: say it with `TARGET`

A breath, a bite, an aura, a poison cloud or a knock-back from `apply_effect` (`breath_cone`,
`on_hit`, `inflict_nearby`, `lingering_zone`, `impulse`) lands on whoever its `TARGET` says. Leave
`TARGET` out and it lands on everything EXCEPT players, so the child is never caught unless the wish
says so.

- "its bite poisons YOU", "breathes frost at ME", "launch me into the sky": `TARGET: players`.
- "breathes fire at the monsters", "zaps the bad guys", "stomps on enemies": `TARGET: monsters`.
- "poisons the cows": `TARGET: cows`. "hits everybody near it": `TARGET: everyone`.
- Never steer it by writing `@s[tag=!...]` or `type=` into `COMMAND`. `TARGET` is the only switch.
- These effects run on the OTHERS near it, never on the creature itself: `effect give @s
  minecraft:regeneration` in `on_hit` heals the monsters it is fighting, and no effect fires when
  the creature ITSELF is hurt. A creature that heals itself is the sculpt ability:
  `abilities: ["heal"]`. On a real mob you summoned, give it the regeneration directly (`effect
  give <its tag> minecraft:regeneration infinite 0 true`).
- A summoned lightning bolt, an explosion or a fire block hits EVERYTHING near where it lands, him
  included. To zap only the monsters, hurt the victim itself: `damage @s 6 minecraft:lightning_bolt`
  with a particle, never `summon minecraft:lightning_bolt`.

Its own moves (slam, ranged, charge) and its bite follow its instincts: a `friendly` creature never
hurts a player, and its thrown balls fly straight through him. A creature that should fight the
zombies and leave him alone is `friendly` + `guardian`, never a creature without `friendly`.
What `ranged` throws is `throws` in the sculpt: `"throws": "minecraft:fire_charge"` for "it throws
fireballs" (they set the monster alight). Left out, it throws a snowball, whatever the wish said.

## WHEN it does it: landing, walking, his jump

The moment a wish names is part of the wish. Pick the trigger that IS that moment, never one that
also fires the rest of the time:

- "when it lands", "when it comes down from a jump": `apply_effect` `on_land`. It fires only when it
  comes back down, never while it stands about. "The ground shakes when it lands" is `on_land` with
  NO `COMMAND`: left out, the landing jolts everything near it into the air, which is the shake. A
  COMMAND replaces that jolt (two T-rex grants wrote slowness, or a screen shake for him, and no
  monster moved).
- "as it walks", "with every step", "stompy feet": `apply_effect` `on_step`. It fires every few blocks
  it walks and never while it stands still. Leave `COMMAND` out for steps you only see and hear.
- "it stomps on the monsters while I ride it": `on_step` with `COMMAND: damage @s 6` and `TARGET: monsters`.
  It fires as he rides it along. `on_land` fires only after a jump, so while he just rides it does nothing,
  and with no `COMMAND` a landing only lifts them and hurts nobody.
- A mount that should leap higher than a normal one: his jump already makes a ridden creature jump. Make
  it higher with jump boost on the creature (`effect give @s minecraft:jump_boost infinite 4 true`
  from a `periodic`), never by giving it a jump of its own.
- A `slam`, an aura (`inflict_nearby`) or the `guardian` instinct goes off whenever something is near,
  standing still or not. None of them is right for "only when it lands" or "only when it walks": use
  `on_land` or `on_step` on a `wild` creature.
- In these triggers' `COMMAND`, `@s` is ALREADY each one it lands on. Never write `@e[...]` inside it,
  and never invent a damage type: `damage @s 4` is the hit.

## HOW it sounds: `voice`, in the SCULPT

A creature with no `voice` makes NO sound at all: no noise while it is about, no cry when it is hit,
nothing when it dies, no footsteps. Give every creature one, in the same `sculpt_creature` call:

- `voice` is the vanilla mob it borrows its sounds from, so pick the mob that sounds like it: a dog or
  a pup `wolf`, a cat `cat`, a bird `parrot` or `chicken`, a bear `polar_bear`, a big heavy beast
  `ravager`, a dragon `ender_dragon`, a frog `frog`, a goat `goat`, a horse `horse`, something eerie
  `warden` or `ghast`. Bigger creatures are pitched deeper for you.
- A wish that names a MOMENT ("roars when you hit it", "howls", "cries when it dies", "thuds as it
  walks") is `sounds` on top of the voice: `{"hurt": "minecraft:entity.ravager.roar"}`. Moments:
  `ambient` (now and then while it is about), `hurt`, `death`, `step`. Spell real 1.21.8 ids: it is
  `entity.wolf.growl`, never `entity.wolf_growl` (and there is no wolf howl any more); the tool refuses
  one the game does not have.
- A wish for a quiet, sneaky or silent creature is `voice: "silent"`: no sound, not even footsteps.
- With `start_from` it keeps the base's voice. A starter base has its own animal's voice, so a
  creature built on the wolf barks unless you give it another.
- Its four moments are the voice's job. Do not build them from a `periodic` `playsound`: that plays
  on a clock, whether it is hit or not. A `periodic` sound is only for a rhythm ("roars every ten
  seconds").

## HOW it lives: `behaves_like`, the vanilla mob it lives like

What a creature does when nobody rides or fights it is its LIFE, and you choose it with ONE word:
`behaves_like`, the vanilla mob it lives like. Its body does not decide it. A dragon is
`behaves_like: "dragon"` on any body: it circles high over its home, perches up high, and swoops and
breathes fire when it fights. A dog or a fox is `"wolf"` (trots, keeps with its pack, pounces), a
grazing beast is `"cow"`, a steed is `"horse"` (it comes over to its rider), a bird or a bug is
`"bee"` (flits low), a creature that climbs walls is `"spider"`, one with a bow is `"skeleton"` (keeps
its distance and shoots), a little townsperson is `"villager"`, a heavy brute is `"ravager"` (charges),
and a fish is `"cod"`. Name the mob whose LIFE it has, not the one it looks like. A flying life brings
`flies` with it. Its temperament (`base`, `friendly`) still says WHO it fights; `behaves_like` says
HOW it gets about and HOW it fights. On a real mob it changes nothing: a real wolf already lives like
a wolf.

Name it on EVERY `sculpt_creature`, the first sculpt and every re-sculpt: without one a
creature lives its carrier's life, and the carrier is picked for its size (every flier rides a bat).
A creature that FLIES lives a flying life: `"dragon"`, `"bee"`, `"horse"` for one he rides, or
`"vehicle"` for one he flies in. A flier you name `"wolf"` or `"cow"` is given a flying life instead,
and the reply says so. That holds for a PERSON who flies too (a flying hero in a suit, a fairy, a
witch on a broom): with `flies` they are `"bee"` (zips about low) or `"dragon"` (soars high), never
`"villager"`, which walks. A sea creature (fins and no legs) that does not fly
lives like `"cod"`, and one you name a land life swims instead, unless it is a boat or a submarine
(`"vehicle"`). The same goes for `define_species`.

A VEHICLE is not a creature, and its life is `behaves_like: "vehicle"`: a car, a truck, a bus, a
tractor, a plane, a helicopter, a rocket, a boat, a submarine, a train. It stays parked where he left
it until he climbs in and drives it, a flying one comes down and parks, and it is rigid — its wings do
not flap and its wheels do not walk — so name its parts for what they are and leave the life to
`vehicle`. It is driven (`rideable` comes with it) and never fights. Give a flying one `flies`, and
it flies only while he steers it. A living thing he rides (a dragon, a horse, a giant eagle) is NOT a
vehicle: it keeps a creature's life.

## One he RIDES: the `rideable` power, in the SCULPT (2026-10-11)

A creature you sculpt for him to ride ("a T-Rex I can ride", "a giant wolf to ride on") gets
`rideable` in its `powers` when you sculpt it, beside `friendly` so it never bucks or bites him.
That keeps riding in the creature itself: his button, a copy from the library, every one you spawn
of it can be ridden. `modify_mob rideable: true` on the one standing there is also saved to it.
Tell him how: use it with an empty hand or his watch to climb on, look where to go, walk forward,
sneak to get off. He sits on the top of its body over its middle, so a creature to ride has a
back there. One that also has `flies` flies with him: look up and walk forward to climb, look down to
dive, stop walking to hover, and if he gets off up high he floats down.

**Name no mouse button and no key.** Children play on Java AND on Bedrock — a phone, a tablet
or a controller — so "right-click", "left-click", "press L", "hold W" or "Shift" mean nothing to half
of them. In everything he reads (Moddy's lines, an item's lore, a sign) say what he DOES: "use it",
"hold it and use it", "hit something with it", "throw it", "put it on", "open your wish watch".

## Ready-made PARTS with a STYLE, and legs for bugs and spiders

`sculpt_creature`'s `kits` build the fiddly parts for you, and most come in looks you pick with `style`:

- horns: `{"kit":"horn","style":"antler"}` for a deer or a moose, `"unicorn"` for ONE horn on the
  forehead, `"ram"` for horns that curl round the ears;
- wings: `"feather"` (a bird, an angel), `"bat"`, `"butterfly"` (a fairy, a moth), `"dragon"`;
- tails: `"fluffy"` (a fox, a squirrel), `"fish"` (a mermaid), `"peacock"` (a fan), `"lion"` (a tuft);
- head kits, `on` the head: `ears` (style `pointy`, `round`, `floppy`, `long` for a bunny), `snout`,
  `beak`, `trunk`, `tusk`, `mane`.

**A bug, a bee or a spider: legs from ONE row, never one leg per corner.** Build a small body (and a
head), then `{"kit":"leg_row","on":"body","count":3}` for six legs (a bee, an ant, a beetle) or
`"count":4` for a spider's eight. Hand-placed legs came out as four stumps at the corners, which is a
table, not a spider.

## A creature is ONE piece: parts OVERLAP at every joint

An ear, a leg or a horn that only lines up with the body edge, or sits a few pixels off it, hangs
in the air beside the creature, and that is what he sees. Make every part **overlap** the part it
belongs to by a pixel or two. Touching edges are not enough, because the joint opens up when the
creature moves.

`sculpt_creature` checks this. A hairline gap is closed for you and reported. Anything wider comes
back as **NOT SCULPTED YET**, with each loose piece, the part it is nearest and the exact move,
e.g. `[0, -3, -4]`, that joins it. Make that move. A gap you really mean, like something meant
to float, goes through if you send the same sculpt again unchanged.

With `start_from`, a part you REPLACE (a new `body` or `head`) carries what hangs on it: the ears
stay on the head and the legs stay under the body, wherever you put the new box. That is for an
EDIT. A replacement much smaller or bigger than the base's part is a new shape, and it comes back
once, naming both boxes. Keep the base's size and adjust it, or send the parts that hang on it too. A NEW part you
add must still be placed on the base's own coordinates, so look at where the base's parts are
before you add one.

**To change a base part's SIZE, `reshape` it — don't re-send its box**. A longer body, a
bigger head, shorter legs or longer ears is one line each: `"reshape": [{"part": "body", "scale":
[1, 1, 1.4]}, {"part": "leg", "scale": [1, 0.7, 1]}, {"part": "ear", "move": [0, 2, 0]}]`. It keeps
the part's colour, skin and joints, and everything hanging on it moves with it. `part` can be a
prefix (`leg` = every leg); a part on the ground keeps its feet on the ground. Send a box in `parts`
only for a part the base does not have.

## How it MOVES and RESTS is drawn for you — choose it, and NAME the parts (–)

You never write keyframes. The rig animates a creature from its own boxes, so what you choose is a
power and a NAME:

- **It flies** → `flies`. Its wings BEAT in the air and twitch on the ground. Only boxes whose name
  has `wing` in it (or the `wing` kit) flap — a wing called `sail` or `leftArm2` is a statue. A
  wing you sculpt by hand is a panel THIN TOP TO BOTTOM (long out to the side, deep front to back,
  a pixel or two tall), the way a wing is held in flight: it beats up and down, so its flat face
  must face up and down to push the air.
- **It swims** (a shark, a fish, a mermaid, an octopus) → name its parts `tail…`, `fin…`,
  `tentacle…`. In water the tail sweeps, fins paddle, tentacles open and close, and it tips its nose
  up or down the way it is going. On land it walks.
- **It hops** (a bunny, a frog, a kangaroo, "bouncy") → `hops`. It gathers, leaps and lands instead
  of walking. Any creature that jumps plays the leap; only `hops` makes it its way of getting about.
- **It naps / sleeps / is sleepy** → `naps`. It lies down when it has been idle a long while and
  curls up asleep, eyes shut, at night. Name its eyes `eye…`/`pupil…` so they can close.
- **"Sit!" / "stay" / "sits when I tell it to"** → `modify_mob` with `stay: 0`. A creature that stays
  SITS while it stays, and lies down after a long sit. Never give a pet `follow` and `stay` together.

A kitten that "sits when I tell it to stay and naps at night" is `naps` now, and `stay: 0` when he
says stay.

## Give it a SKIN: `pattern` on the body and head (2026-10-10)

**A creature made of plain coloured boxes reads as painted blocks.** Every ready-made creature has a
skin — the wolf's fur, the turtle's shell rings, the frog's speckle — and that is most of why it looks
made. When you sculpt from nothing, give the BIG parts one too: `"pattern": "fur"` (or `scales`,
`spots`, `stripes`, `speckle`, `gradient`, `plaid`, `checker`) with a `"pattern_colour"` a shade
darker or lighter than its `colour`. Fur for anything furry, scales for dragons, fish and lizards,
spots for a frog, a ladybird or a mushroom cap, stripes for a tiger or a bee, gradient for a
glowing jelly. It costs no extra parts. Measured: fewer than one part in fifty carried one, so the
creatures a child saw textured were only the ones a wish had started from. The sculpt answer now says
`◻ FLAT` when the biggest parts have none.

**Re-skinning one he already has** is the same field on an edit: `start_from` him, and send each
part to change by NAME with no `from`/`to` — `{"name": "shell", "pattern": "scales",
"pattern_colour": "#2E4A1A"}`. It keeps that part exactly where it is and changes only what you
sent. (Re-sending the whole box works too, but a name alone is enough.)

## A face has to DO something

**A mascot's mood is part of who it is.** A scowling cheerful mascot clown is wrong in the same way an
all-green frog knight is wrong. This is not decoration you add if there is room — for a character with a
face it is one of the first things a child reads, and the last thing they forgive.

**The measured failure is not the wrong expression. It is NO expression.** Across 119 archived
creatures, **52 of the 59 that built a mouth built it as a single box** — and a single box cannot
curve. Every mascot clown came out with a flat red bar named `smile`, two flat dark eyes and no brows at
all, and that reads as stern. The agent was never picking the wrong mood; it was spending one box on
a thing that needs three.

So this is a BUDGET rule as much as a design one:

- **A mouth is three or more boxes, stepped.** Corners above the middle is a smile; corners below it
  is a frown; all level is a slot, whatever you called it. One box is never an expression.
- **Brows are a pair, and they are what carry anger.** A brow whose INNER end sits lower than its
  outer end is a scowl. Raised at the inner end is worried or sad. No brows at all is not neutral —
  it is blank, and blank reads as stern on anything with dark eyes.
- **Build the brow angle out of POSITION, not out of rotation.** Two small boxes per brow, one
  higher than the other, is unambiguous. A single box with a Z-roll looks like the same idea and is
  a trap: the sign of a roll about Z is the thing this project has got backwards more than any
  other, and it is what makes a cheerful character scowl. Measured — a mascot clown and a purple dinosaur
  mascot both built perfectly good five-box smiles and then put `rotation:[0,0,±12]` on their brows,
  and both came out angry; a cartoon hedgehog with flat brows in the same run read fine.
- **Eyes: rounder and larger reads friendlier; narrow horizontal slits read fierce.** A pupil that
  is small relative to the eye reads startled.
- **Tilt is allowed and cheap, but position does most of the work.** Both express; position is the
  one that cannot come out backwards under a renderer.

**Say what the mood is in your note.** "The mascot clown — cheerful, so the mouth curves up over three boxes
and the brows are relaxed" is checkable later. `look_up_character` returns an `expression` for real
characters; use it. If it says UNKNOWN, decide from the child's own words — "a friendly clown", "a
scary dragon" — and say which you chose.

**Nothing here is a template.** There is no face kit and that is on purpose: a prefab face would put
every character in the game into the same six expressions, and you are the only thing in this
pipeline that knows what this particular character looks like. These are the shapes that READ as
each mood; how you build them is yours.

## Getting the reference right for a real character

**LOOK IT UP — you have a tool for this.** `look_up_character` fetches what a real character
actually looks like, from sourced pages, and hands you colours in proportion, the parts that make
them recognisable, their size and their expression. Call it BEFORE sculpting anything named after a
REAL character — a video-game hero or villain, a cartoon or TV character, a brand's mascot, a meme
character — whenever the child's words name one, even one you think you know well. It is fast, because the lookup is already running in the
background by the time you ask.

**It gives you facts, not a shape — the shape is yours.** It used to hand back a rough box blockout
too, and that was measured making things WORSE: on Tralalelo Tralala a facts-only reference built 8
shark parts and a blockout-led one built 4, fewer than building with no reference at all. A dozen
boxes from something that never saw the character either is not a head start, it is a smaller box to
think inside. Take the colours, the parts and the mood; build the silhouette yourself.

(This file used to say the opposite — "you have no web access, and since you cannot check, say how
sure you are". That was true when it was written and became false when the tool shipped. The agent
duly stopped looking anything up: adoption went from 3 of 7 named creatures to **0 of 7**. Left on
the record because a confident, stale instruction is obeyed exactly as readily as a correct one, and
this is the fourth time it has happened here.)

**Memory is where this went wrong before.** This file once asserted a famous game villain was "mostly
tan" from recall; it was wrong, and a live run showed the agent building a 65%-tan frog knight
because it was told to. So prefer the tool, and when it returns nothing usable — a meme with no canonical design, say —
**say how sure you are in the design note** rather than inventing certainty. "A meme penguin — no
canonical design I could source; built from the name" is a useful, honest answer.

**When reference lookup is switched on, prefer better sources — but never refuse a wish because the
best source is missing.** Ranked:

1. the **owner** — the company that made the character, the brand's own site
2. a **dedicated wiki with editorial standards** — the series' own dedicated wiki, Wikipedia
3. a **general fandom wiki** for the property
4. **anything else** — meme aggregators, video descriptions, fan art

The lower you go, the more you cross-check: two independent sources agreeing beats one confident
page. Fan art is evidence of what people *think* it looks like, which for a meme character may be
the only truth there is.

**Some characters have no canonical design, and saying so is the right answer.** A character named after a
viral song may be nothing but the song; a brainrot meme may be one whose whole existence is other people's drawings. For those,
take the *consensus* — the features that recur across the images you find — and tell the child what
you decided rather than implying an official version exists.

**Record what you used.** Name the source in your design note. When a creature comes out wrong, the
first question is always "what were you looking at", and it has to be answerable months later.

**State PROPORTIONS, not just colours.** "The frog knight is green and tan" will still get you an
all-green frog knight. "Back and head green — about half of him — with belly, muzzle and limbs tan" will not.
Proportion is what makes a creature read as itself.

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

## Taught a REAL mob you changed with `modify_mob`? The button is `wishsummon`

A button is a list of commands, and `modify_mob` is a tool — so do **not** re-write what you did as
`summon … {attributes:[…]}`. That translation is how a child's Great Big Shark came back as an
ordinary shark on every re-wish: Minecraft accepted the summon and silently ignored the attributes.

Teach the typed replay instead, with the **same JSON you gave `modify_mob`**:

```
wishsummon hybrid-aquatic:great_white_shark {"scale":4,"health":300,"damage":12,"name":"The Great Big Shark"}
```

It summons the mob where the wish is aimed and applies that exact change with `modify_mob`'s own
code, then checks the size and health really took. A sculpted creature is different: its button is
`wishmob spawn <species>`.

## "It works" is something the SERVER saw, not something you meant

The child was told *"2 mobs playing pickleball"* worked about companions that had already walked off
the court, and *"he follows me"* about a frog knight that did not. So in `finish_building`, a claim with
`works:true` counts as checked only when it carries a `check` the server runs first:

```
{"claim":"2 players on the court","works":true,"check":{"kind":"creature_count","what":"pickleball_player","min":2,"near":"wish","radius":8}}
{"claim":"he follows me","works":true,"check":{"kind":"follows","what":"frog_knight"}}
{"claim":"his fire doesn't hurt him","works":true,"check":{"kind":"stays_healthy","what":"frog_knight"}}
```

A creature check counts THIS wish's own creatures wherever they are: "The frog knight appears" and
"he follows me" both hold after he has followed him away from the spot. The radius only counts OTHER
creatures. Creature checks are watched for three seconds, so a mob that wanders off, or a pet that is not set to
follow him, fails — and you are sent back once to fix it or say `works:false`. A claim with no check
is recorded as "tried", not as checked. He sees NO checklist: a claim that works says nothing
extra, so what you want him to hear goes in `say`; only a broken bit is told, in one sentence. A `companion` walks to the nearest player: it will not stay
on a court.

## A QUESTION about a creature: look before you answer

*"the frog knight stopped following me, why?"* was answered *"he wandered too far and got lost"* — invented.
There were two frog knights, `follow` had been set on a llama, and one of them had been killed; one
`list_creatures` would have shown it. On a question, call `list_creatures` / `inspect_creature` (or
`look_around`) FIRST and answer from what you saw. The answer is refused until you have looked.

## "TOO MANY" means FEWER (2026-10-10)

**"too many bloops", "to meny zombies", "less slimes", "there are way too many" asks you to take some
AWAY, never to add more.** Children spell it *to many*, *to meny*, *2 many*: it is still *too many*.
Count what is there (`list_creatures`, or look), then remove SOME with `run_command`:
`kill @e[tag=wishmob_<id>,limit=N,sort=furthest]` for his own creature, `kill @e[type=…,limit=N,sort=furthest]`
for an ordinary mob, keeping the ones nearest him. `remove_creature` takes away EVERY one of that kind,
so use it only when he says "get rid of them all". Read live: "to meny bloop" got ten MORE bloops.

## HOW MANY: creatures come in WAVES — never invent a limit, never say "the game would crash"

**Many of one ordinary mob, there to stay — "lots of sheep in my field", "a flock of chickens", "twenty
pigs all round me", "a circle of iron golems" — is ONE `stage_event` call with motion `crowd`**, not summon
lines: he gets them spaced out where he aimed (or round him), framed in the reveal, and one undo
takes the whole crowd back. `summon` is for ONE mob, or for mobs you will then change with
`modify_mob`. The waves below are the same numbers either way.

A wish for an enormous number of creepers got twenty summon lines and a warning that the game would explode.
The twenty was a guess and the crash was made up; nothing measured either. The real numbers are
enforced by the game itself wherever a creature appears, so **summon what was asked and let the wand
decide**: by default one wish brings up to **100** ordinary mobs, **50** that explode (creeper, TNT minecart, end
crystal), **25** that fly (phantom, ghast, vex, blaze, bee, bat, parrot, allay) and **1** boss
(wither, warden, dragon, elder guardian); a sculpted creature is priced by its boxes, so a big one
comes in a smaller wave than a small one. A world may set its own numbers, so trust the result over
these. Past that the rest are simply not made, and the result
hands you the exact words — *"My wand holds 50 creepers at a time — here come the first 50! Wish
'more' and the next wave rolls in!"*. Say those, or the same truth in your own words. A world already
full of wished creatures says so too: some have to go before more can come. Never tell him the game
would crash, explode or break — it would not; the wand has a size.

**When this world has limits, make the strongest version they allow.** If the brief shows THIS WORLD'S LIMITS and the
player asks for more than they allow, do not refuse and do not make something weaker than you must: make the strongest
creature within the limits ("the strongest dragon ever" is made with the most health, damage and size the limits allow), and say so in your own words. The tools the brief names set a number above a limit to the
limit and tell you which; pass that on to him once. Nothing else is fitted when it is made (a learned recipe, modify_mob, run_command, a datapack): write
its numbers within the limits yourself (the creature is still held to them where it stands, but what you wrote is what its
recipe keeps), and never tell him a limit was applied unless a tool said so.
