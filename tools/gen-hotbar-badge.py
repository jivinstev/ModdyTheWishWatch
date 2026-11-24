#!/usr/bin/env python3
"""The Moddy-watch badge a wish wears on the HOTBAR, and the one item definition that draws any vanilla
picture under it. Re-run after a version bump, or to change the look:

    python3 tools/gen-hotbar-badge.py [--look corner|inside|behind] [--client-jar PATH]

What it writes (all committed; nothing is generated at run time):
  src/main/resources/assets/moddingfromamod/textures/item/hotbar/badge*.png (+ .mcmeta: the watch animates)
  src/main/resources/assets/moddingfromamod/models/item/hotbar/badge*.json
  src/main/resources/items/hotbar-badge.json      IDS ONLY: the look, the block items drawn flat, the exceptions
  src/main/resources/assets/wishpack/items/hotbar.json   the definition that draws a vanilla picture under the badge,
                                                  every entry written BY RULE (nothing of Mojang's is copied); the
                                                  pushed pack carries the same bytes (pack/HotbarBadge.java).
The client jar (the Gradle cache's) is read to measure where the rule is wrong, never copied.

The looks (docs/evidence-2755/overlay/README.md has the photographs):
  corner  a small watch in the top-left corner, over the picture (SHIPPED)
  inside  the watch's face behind the picture and its gold case in front of it: the picture in the watch
  behind  the whole watch behind the picture
"""
import argparse
import json
import pathlib
import sys
import zipfile

from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / 'src/main/resources'
TEX = RES / 'assets/moddingfromamod/textures/item'
MODELS = RES / 'assets/moddingfromamod/models/item'
WATCH = TEX / 'wish_watch.png'
FRAME = 32

# Mirrors pack/HotbarBadge.java: which layers each look has.
LOOKS = {'corner': ('front',), 'inside': ('back', 'front'), 'behind': ('back',)}


def frames(im):
    return [im.crop((0, i * FRAME, FRAME, (i + 1) * FRAME)) for i in range(im.height // FRAME)]


def part(p, y):
    """'.' clear, 'o' the watch's face (inside the case, below the crown), else the case."""
    r, g, b, a = p
    if a == 0:
        return '.'
    if r > 150 and b < 100 or r + g + b < 150 or y < 12:
        return 'G'
    return 'o'


def split(frame):
    face, case = Image.new('RGBA', frame.size), Image.new('RGBA', frame.size)
    for y in range(frame.height):
        for x in range(frame.width):
            p = frame.getpixel((x, y))
            c = part(p, y)
            if c != '.':
                (face if c == 'o' else case).putpixel((x, y), p)
    return face, case


def half(im):
    """A pixel-art 2:1 reduction that keeps the colourful pixel of each 2x2, so the face still reads."""
    out = Image.new('RGBA', (im.width // 2, im.height // 2))
    for y in range(out.height):
        for x in range(out.width):
            ps = [im.getpixel((2 * x + i, 2 * y + j)) for i in (0, 1) for j in (0, 1)]
            ps = [p for p in ps if p[3] > 0]
            if ps:
                ps.sort(key=lambda p: -(max(p[:3]) - min(p[:3])))
                out.putpixel((x, y), ps[0])
    return out


def outlined(im):
    """A one-pixel black edge around what is drawn, so the badge reads over any picture."""
    edge = Image.new('RGBA', im.size)
    for y in range(im.height):
        for x in range(im.width):
            if im.getpixel((x, y))[3]:
                continue
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    u, v = x + dx, y + dy
                    if 0 <= u < im.width and 0 <= v < im.height and im.getpixel((u, v))[3]:
                        edge.putpixel((x, y), (0, 0, 0, 255))
    return Image.alpha_composite(edge, im)


def corner(frame, box):
    small = half(frame)
    small = small.crop(box)
    out = Image.new('RGBA', frame.size)
    out.paste(small, (0, 0), small)
    return outlined(out)


def strip(fs):
    out = Image.new('RGBA', (FRAME, FRAME * len(fs)))
    for i, f in enumerate(fs):
        out.paste(f, (0, i * FRAME))
    return out


def layers(look):
    fs = frames(Image.open(WATCH).convert('RGBA'))
    if look == 'corner':
        # One crop box for every frame, so the badge does not jiggle as the face swirls.
        box = None
        for f in fs:
            b = half(f).getbbox()
            box = b if box is None else (min(box[0], b[0]), min(box[1], b[1]), max(box[2], b[2]), max(box[3], b[3]))
        return {'front': strip([corner(f, box) for f in fs])}
    if look == 'inside':
        parts = [split(f) for f in fs]
        return {'back': strip([p[0] for p in parts]), 'front': strip([p[1] for p in parts])}
    return {'back': strip(fs)}


# Mirrors HotbarBadge.FORWARD: in a slot a block item is a cube turned toward you, and a flat badge at the
# slot's middle depth sinks behind its near corner (photographed on a chest, docs/evidence-2755/overlay). So the
# front badge is drawn half a block nearer; the back one half a block further.
FORWARD = 8


def model_json(sprite, ns='moddingfromamod'):
    z = -FORWARD if sprite.endswith('_back') else FORWARD
    return json.dumps({"parent": "minecraft:item/generated",
                       "textures": {"layer0": ns + ":item/" + sprite},
                       "display": {"gui": {"translation": [0, 0, z]}}}, indent=2) + "\n"


# ── The vanilla pictures, BY RULE: nothing of Mojang's is copied ──────────────────────────────────────
# A vanilla item is drawn under the badge by the model a RULE names (HotbarBadge.ruleModel, mirrored here):
#   minecraft:item/<path>                 an item that is not a block, or a block listed in flatBlocks
#   minecraft:block/<path>_inventory      a block whose id ends _fence, _wall or _button
#   minecraft:block/<path>                any other block
# The client jar is READ here (never committed) to find the ids, which are blocks, and where the rule is wrong;
# the committed file holds ids only. An id the rule cannot draw (a special, tinted or conditional definition) goes
# on `exceptions` and its proxy keeps its own picture and the glint.
INVENTORY_SUFFIXES = ('_fence', '_wall', '_button')


def rule_model(path, block, flat):
    if not block or flat:
        return 'minecraft:item/' + path
    if path.endswith(INVENTORY_SUFFIXES):
        return 'minecraft:block/%s_inventory' % path
    return 'minecraft:block/' + path


def measure(jar):
    """(every id, the flat blocks, the exceptions) for the client jar, by comparing the rule with each definition."""
    with zipfile.ZipFile(jar) as z:
        names = set(z.namelist())
        ids, flat, skip = [], [], []
        for n in sorted(names):
            if not (n.startswith('assets/minecraft/items/') and n.endswith('.json')):
                continue
            path = n[len('assets/minecraft/items/'):-5]
            ids.append('minecraft:' + path)
            model = json.loads(z.read(n))['model']
            block = 'assets/minecraft/blockstates/%s.json' % path in names
            want = lambda m: model == {"type": "minecraft:model", "model": m}
            if want(rule_model(path, block, False)):
                continue
            if block and want(rule_model(path, block, True)):
                flat.append('minecraft:' + path)
                continue
            skip.append('minecraft:' + path)
    if len(ids) < 1000:
        raise SystemExit('only %d item definitions in %s: is it a client jar?' % (len(ids), jar))
    return ids, flat, skip


def definition(cases, look, ns):
    """Mirrors pack/HotbarBadge: [back], the picture named by custom_model_data.strings[0], [front]."""
    models = []
    if 'back' in LOOKS[look]:
        models.append({"type": "minecraft:model", "model": ns + ":item/hotbar/badge_back"})
    models.append({"type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 0,
                   "cases": [{"when": k, "model": {"type": "minecraft:model", "model": m}} for k, m in cases],
                   "fallback": {"type": "minecraft:empty"}})
    if 'front' in LOOKS[look]:
        models.append({"type": "minecraft:model", "model": ns + ":item/hotbar/badge"})
    return {"model": {"type": "minecraft:composite", "models": models}}


def target_mc():
    """The Minecraft this build targets, as compat/Target.java says (the test reads the same jar)."""
    import re
    src = (ROOT / 'src/main/java/com/moddingfromamod/compat/Target.java').read_text(encoding='utf-8')
    return re.search(r'MINECRAFT = "([^"]+)"', src).group(1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--look', choices=sorted(LOOKS), default='corner')
    # written by every build (build.gradle's additionalMinecraftArtifacts)
    ap.add_argument('--client-jar', default=str(ROOT / 'build/minecraft' / ('client-' + target_mc() + '.jar')))
    ap.add_argument('--preview', help='write the look\'s layers here instead of into the mod')
    a = ap.parse_args()

    ls = layers(a.look)
    if a.preview:
        for k, im in ls.items():
            im.save(pathlib.Path(a.preview) / ('badge%s.png' % ('' if k == 'front' else '_back')))
        return
    (TEX / 'hotbar').mkdir(parents=True, exist_ok=True)
    (MODELS / 'hotbar').mkdir(parents=True, exist_ok=True)
    for f in list((TEX / 'hotbar').glob('*')) + list((MODELS / 'hotbar').glob('*')):
        f.unlink()
    meta = (TEX / 'wish_watch.png.mcmeta').read_text()
    for k, im in ls.items():
        name = 'badge' if k == 'front' else 'badge_back'
        im.save(TEX / 'hotbar' / (name + '.png'), optimize=True)
        (TEX / 'hotbar' / (name + '.png.mcmeta')).write_text(meta)
        (MODELS / 'hotbar' / (name + '.json')).write_text(model_json('hotbar/' + name))

    jar = pathlib.Path(a.client_jar)
    rules = RES / 'items/hotbar-badge.json'
    out = RES / 'assets/wishpack/items/hotbar.json'
    if jar.exists():
        ids, flat, skip = measure(jar)
        version = jar.parent.name
    elif rules.exists() and out.exists():
        print('no client jar at %s: kept the committed ids' % jar, file=sys.stderr)
        d = json.loads(rules.read_text())
        flat, skip, version = d['flatBlocks'], d['exceptions'], d['minecraft']
        cases = json.loads(out.read_text())['model']['models']
        ids = sorted({c['when'] for m in cases if m['type'] == 'minecraft:select' for c in m['cases']} | set(skip))
    else:
        raise SystemExit('no client jar at %s and nothing committed' % jar)
    # Which ids are blocks is not committed: a case already says it by its model (item/ or block/).
    blocks = set()
    if jar.exists():
        with zipfile.ZipFile(jar) as z:
            names = set(z.namelist())
        blocks = {i for i in ids if 'assets/minecraft/blockstates/%s.json' % i.split(':')[1] in names}
    else:
        for m in json.loads(out.read_text())['model']['models']:
            if m['type'] == 'minecraft:select':
                blocks |= {c['when'] for c in m['cases'] if c['model']['model'].startswith('minecraft:block/')}
        blocks |= set(flat)
    cases = [(i, rule_model(i.split(':')[1], i in blocks, i in flat)) for i in ids if i not in skip]
    rules.write_text(json.dumps({
        "_comment": "The hotbar wish's Moddy badge (pack/HotbarBadge.java, tools/gen-hotbar-badge.py). IDS ONLY: "
                    "which look ships, the block items the rule draws flat (minecraft:item/<path>), and the items the "
                    "rule cannot draw (their proxy keeps its own picture and the glint). Measured against the client "
                    "jar of the pinned Minecraft; HotbarBadgeTest fails when it is stale.",
        "look": a.look, "minecraft": version, "flatBlocks": flat, "exceptions": skip}, indent=1) + "\n")
    out.write_text(json.dumps(definition(cases, a.look, 'moddingfromamod'), separators=(',', ':')) + "\n")
    print('look %s: %s; %d vanilla pictures by rule (%d flat blocks), %d exceptions'
          % (a.look, ', '.join(ls), len(cases), len(flat), len(skip)))


if __name__ == '__main__':
    main()
