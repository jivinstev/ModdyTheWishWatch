# Moddy the Wish Watch

Moddy is a wish watch for Minecraft. You tell it what you wish for (a creature, a building, an item, a picture, a rule for your world) and it makes it, right there in your game.

By **Moddy LLC**. Questions and problem reports: [GitHub Issues](https://github.com/jivinstev/ModdyTheWishWatch/issues).

**Scope:** NeoForge, Minecraft 1.21.8. **Single-player:** install it in your own game. Your game is both client and server, so there is nothing else to install or host. It is not made for a dedicated server.

## Three ways to wish

- **The Builder**: pick from ready-made parts and presets.
- **The Apprentice**: type a wish in your own words, and it matches it to things it can make. Its small sentence model ships inside the jar, so it works offline with no account. (On an Intel Mac or a Windows-on-Arm PC it matches by keywords only: the model runs on Windows and Linux PCs and on Apple-silicon Macs.)
- **The Magician** (optional, off until you set it up): wishes made from scratch by an AI model on your own subscription. API keys are read only from environment variables, never from a file.

## Turn on the Magician

1. Install **Claude Code** or **Muse Code** and sign in to your subscription.
2. In game, open your wish watch and press **✪ Turn on the Magician**.
3. Press the button for your subscription. Moddy checks everything and tells you when it's ready (a few minutes, once).

## What it runs on your computer

- With the Magician **off** (the default), the game makes no connection to the internet.
- With the Magician **on**, it starts the Claude Code or Muse Code program you set up, and the agent may look things up on en.wikipedia.org, commons.wikimedia.org and minecraft.wiki, or fetch a skin a wish asks for.
- Builder and Apprentice makes, powers included, need nothing else: their datapacks are compiled when the mod is built and ship inside the jar.
- The Magician's own powers (new datapacks written for a wish) are compiled inside the game by the compiler built into the mod. Nothing else is installed or started for them.

## Requirements

- NeoForge for Minecraft 1.21.8. No other mod, and no Python.

## How releases are made

Every release is built from https://github.com/jivinstev/ModdyTheWishWatch by its own build, passes automated checks (the unit tests, a headless game server running game tests, a real game client), and is played by a person before it is published here.

## Licence

The code is MPL-2.0 and the art and presets CC BY 4.0, copyright Moddy LLC. "Moddy" and "Moddy the Wish Watch" are trademarks of Moddy LLC. Bundled third-party components and their terms are listed in the jar and in the source.

---

### Try Moddy online

Want to see Moddy before you install anything, or wish together with friends? Moddy also runs on our own servers. Join with Minecraft Java 1.21.8 or newer at **`moddy.world`**: it's free, and nothing needs installing. Everyone gets the Builder and the Apprentice there, and players 18 or older can sign up for free at [playmoddy.com](https://playmoddy.com) to use the Magician too, with no Claude Code or Muse Code of their own to set up.

Curious how it works? Videos and write-ups are at [playmoddy.com](https://playmoddy.com).
