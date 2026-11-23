# Moddy the Wish Watch

Moddy is a wish watch for Minecraft. You tell it what you wish for (a creature, a building, an item, a picture,
a rule for your world) and it makes it, right there in your game.

- **Single-player.** Moddy is a NeoForge mod for **Minecraft 1.21.8**. Your game is both client and server, so
  there is nothing else to install or host.
- **The Apprentice and the Builder** work with no AI and no account: you pick from ready-made parts, or type a
  wish in your own words and the Apprentice matches it to things it can make. Both run on your computer, offline.
- **Requirements:** NeoForge for Minecraft 1.21.8 and nothing else. Builder and Apprentice powers are compiled when
  the mod is built; Python 3 is needed only for the Magician's own powers.
- **Magician mode is optional.** If you have Claude Code or Muse Code, wishes can be granted by an AI model on your
  own subscription. It is off until you set it up, and it never starts a program or calls a model while it is off.
  API keys are read only from environment variables.

## Turn on the Magician

1. Install **Claude Code** or **Muse Code** and sign in to your subscription (or set `ANTHROPIC_API_KEY` /
   `MUSE_API_KEY` where Minecraft starts).
2. In game, open your wish watch and press **✪ Turn on the Magician**.
3. Press the button for your subscription: **Use my Claude subscription**, **Use my Muse Code subscription**, or
   **Use API keys**. Moddy checks the program is installed and signed in, runs the Magician check (every safety
   grader and one small test wish, about 3 minutes, once) and turns the Magician on. If something is missing, it says
   the one thing to do.

The Magician writes its own **powers** (what an item or creature does) as small datapacks, compiled by a bundled
compiler that runs **Python 3**. The setup checks for a working Python 3 (it recognises Python 2 and the Windows
Microsoft Store shortcut that only opens the Store). Without one the Magician still turns on and makes creatures,
buildings, items and pictures, and one line says what to do: install Python 3 from python.org (on Windows, tick "Add
python.exe to PATH"), then press the button again.

**Choose models myself** on the same page picks each model, how it is paid for and its fallbacks.

## Build it

You need Java 21.

```bash
./gradlew build            # compile, the unit tests, and the mod jar in build/libs/
./gradlew runClient        # play it in a development client
./gradlew runGameTestServer -Pshard=ci   # the headless game tests a pull request runs
```

Drop the jar from `build/libs/` into your NeoForge 1.21.8 `mods/` folder to play it in your own game.

## Commands

Every command this build registers. A player only ever needs the watch; the rest are what its buttons and links run.

**For players** (anyone in the world):

| Command | What it does |
|---|---|
| `/wish [words]` | Opens the watch; with words, makes a wish. |
| `/moddy` | Opens the watch. |
| `/moddy help` | The world's intro and how to use Moddy, again. |
| `/moddy now` | "What do I do now?": the watch's guide page. |
| `/moddy library` | Your wishes and the ready-made ones. |
| `/moddy wishbox <words>` | What the wish box's "Make my wish" runs. |
| `/moddy apprentice <words>` | Answer these words with Apprentice magic (Builder makes, no AI). |
| `/moddy builder` | The Builder's menus: pick ready-made parts and tune them. |
| `/moddy status` | How many wishes are made, waiting and left. |
| `/moddy stop [id]` | Stop the wish being made. |
| `/moddy undo [id]` | Undo your last wish (or the one named). |
| `/moddy no [why]` | "That's not what I wished for": takes the last wish back and says why. |
| `/moddy redirect <id> <what>` | Change a wish while it is being made. |
| `/moddy again <recipe>` | Make another of a ready-made wish. |
| `/moddy give <thing>` | Give me that thing again (a copy of something you already have). |
| `/moddy try <id>` | Your own copy of what any wish made, by its id. |
| `/moddy go <id>` | Take me to something I made. |
| `/moddy back` | Take me back to where I was. |
| `/moddy hide <tile>` | Take one thing off your watch (wishing it again brings it back). |
| `/moddy which <id>` | Your answer when Moddy asks "which one do you mean?". |
| `/moddy wishes` | The wish bank, where a world has one: how many, and when the next comes. |
| `/moddy report <player> [what]` | Tell the people who run the world about someone. It does nothing to that player. |
| `/moddy rescue` | To a safe spot by your home, fireproof for a few seconds. |
| `/moddy credits` | Who made the world you are standing in. |

**For the host** (the single-player owner, or whoever opened the world to LAN):

| Command | What it does |
|---|---|
| `/moddy magician` | The Magician setup (the watch's ✪ tile opens it): one button per subscription found, or choose models yourself. |
| `/moddy setting [key value]` | Moddy's settings: bare lists them; with a key and a value, changes one. |

**Run by buttons, not typed** (permission 2: the watch's saved buttons, world rules and datapacks run them):

| Command | What it does |
|---|---|
| `/wishbuild <id>` | Put up a saved building. |
| `/wishspot <thing>:<spot> run …` | Run a command at a named spot ("on my roof"). |
| `/wishput <thing> [count]` | Put a thing you wished for down at that spot. |
| `/wishmob …` | A wished creature: spawn, list, reload, its parts and paint. |
| `/wishsummon <entity> [spec]` | Summon a mob with a wish's changes (size, name, powers). |
| `/wishpaint <id>` | Hang a saved picture again. |
| `/paint …` | Put a picture on the panel you are looking at. |
| `/wishdatapack <id> [entry]` | Load a saved datapack wish and run its entry. |
| `/wishvisit <id>` | Take a player to something they made. |
| `/moddyevent …` | Make a kept event happen (a world rule's or an item's). |

`PublicCommandsReadmeTest` builds this jar's real command tree and fails when this list misses a command or names one
it does not register.

## Build on it

[`docs/EXTENDING.md`](docs/EXTENDING.md) covers how to add to Moddy without changing its code: plug-ins, the
hooks a plug-in fills, and the settings.

## Licence

The code is licensed under the [Mozilla Public License 2.0](LICENSE). Art and presets are
[CC BY 4.0](LICENSE-ASSETS.md). "Moddy" and "Moddy the Wish Watch" are trademarks of Moddy LLC; see
[TRADEMARKS.md](TRADEMARKS.md) for what a fork may call itself. Bundled third-party components and their terms
are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and [NOTICE.md](NOTICE.md).

Questions and bug reports go to GitHub Issues.
