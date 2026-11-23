# Client validation: scripted, filmed checks of a pre-release build

Before a Moddy build goes to CurseForge, these scripts play it in a **real Minecraft install**, the way a player
would, and film each check. They test the jar players will get, the public repo's pre-release
(`jivinstev/ModdyTheWishWatch`), with nothing built here. They run alongside the companion mods a player is likely to
add: Epic Core API (ECA) and The Last Sword (TLS).

The engine is MMVU (`jivinstev/mc-mod-version-upgrade`):

- `real-install.py` makes a separate game folder (an "instance") with exactly these mods. It never touches the
  player's own mods or profiles.
- The **Gate C carrier** runs each `.playtest` script in the real client, step by step. It grades every step and
  takes a frame at the end of each one.
- `playtest.py --video` records the game window. It records behind a locked screen too.

## Run it

You need an MMVU checkout with jivinstev/mc-mod-version-upgrade#241 (window recording, `@type`, `@click item`,
`@click button` inside dialogs, `--prop`), `gh` signed in, and Java from the Launcher. You also need Claude Code
installed and signed in (`claude`, once), because 03 to 05 use the Claude subscription.

```bash
tools/client-validation/validate.sh install                 # newest pre-release for Minecraft 1.21.8, plus ECA, TLS, the carrier
tools/client-validation/validate.sh run                     # all six, in order, each filmed
tools/client-validation/validate.sh run 01-builder-wish     # one
```

- **Results:** each run writes `out/client-validation/<name>-<time>/`, which holds `report.md`, `report.html`, the
  frames and the `.mov`. It also writes `out/client-validation/<name>.mp4`, a 720p copy for review. `out/` is
  git-ignored.
- **Exit status:** `run` exits 0 only when every playtest passed.
- **Isolation:** Moddy runs with its own home folder (`~/.mmvu/real-install/moddy-prerelease/moddy-home`), so your
  `~/.moddingfromamod` is never read or changed. A run of the whole set starts from an empty home, like a new
  player.
- **Locked screen:** a locked Mac is fine. The display only has to stay awake, and the runner keeps it awake.
- **Older MMVU releases:** until an MMVU carrier release has `@type` and `@click item`, build the carrier from MMVU's
  source and point `CARRIER_JAR` at it:
  ```bash
  python3 tools/gatec-carrier.py prepare --mc 1.21.8 --out /tmp/carrier && (cd /tmp/carrier && ./gradlew build)
  ```
  (run it in MMVU).

## What each playtest proves

| Playtest | Proves | How it checks |
|---|---|---|
| `01-builder-wish` | The Wish Builder's pages, clicked like a player, make a creature | `✨ Your … is ready!` in chat, and a wished body (`tag=wishmob,tag=mfamrig`) nearby |
| `02-apprentice-wish` | A typed wish goes to the Apprentice, which offers ready-made picks, and the pick is made | the Apprentice's chat line and chest, `is ready`, and the creature in the world |
| `03-claude-subscription` | From the watch, **✪ Turn on the Magician** → **Use my Claude subscription** sets Claude (Opus 5.5, with Haiku 5.5 as the helper) on the Claude subscription, runs the Magician check (programs, the model checks and a real test wish) and turns the Magician on | `Setting up the Magician…`, the action bar's `Magician setup 1/3` and `2/3`, and `The Magician is on: Opus 5.5 on your Claude subscription` |
| `04-magician-check-and-switch` | The watch shows the Magician is on, and the wish box's Magic choice switches wishes to the Apprentice and back to the Magician | the **✪ Magician: on (Opus 5.5)** button opening the setup page, and the choice button changing between **Magician: infinite opportunities** and **Apprentice: faster, but more limited** |
| `05-magician-wish` | A wish typed into the watch goes to the Magician, which makes a new creature from scratch | the running timer on the action bar, `is ready` or `came true` in chat, and a wished creature in the world |
| `06-builder-last-sword` | The Builder's 🔍 Find gets another mod's item (The Last Sword) by name, and that sword kills a Warden | the sword in the inventory and in hand, and the Warden gone after melee hits |

- **Order:** 03, 04 and 05 build on each other through Moddy's home folder, so run the set in order.
- **Time:** 03 and 05 take a few minutes each, because they make real model calls.
- **Cost:** on the Claude subscription these calls use the subscription's allowance, not an API key.

## Writing more

- **Grammar:** the script grammar is MMVU's (`tools/playtest-lint.py`). Check a script with
  `python3 <MMVU>/tools/playtest.py lint FILE --jar <mod jar>`.
- **Driving the UI:** Moddy's UI is vanilla chests and dialogs, so a script clicks tiles by name
  (`@click item "Done"`) and dialog buttons by text (`@click button "Make it!"`). It types into a box with
  `@type "…"`.
- **What to check:** check what a player sees (chat lines) and what is in the world (tags, items). Don't use timing.
- **Things learned:**
  - The watch stays in the selected hotbar slot: `@key key.use` opens it, as a right-click does. Select another slot
    with `@key key.hotbar.N` before swinging something.
  - The watch's wish screen is a dialog, and its choice buttons change their text when pressed, so a script presses
    them by their current text.
  - The Apprentice's names vary between runs (Scorch, Torch, … the Dragon), so check tags, not names.
  - A flying creature can leave a 60-block radius within seconds.
