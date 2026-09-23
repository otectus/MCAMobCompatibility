#!/usr/bin/env python3
"""Keep the Forge 1.20.1 and NeoForge 1.21.1 builds of MCA: Mob Compatibility in step.

The two builds live in two repositories and ship one mod: the same behaviour, config, commands,
data and tests on both loaders, differing only where the loader or the Minecraft version forces
it. This script is identical in both repositories, and so is the manifest it keeps,
parity-manifest.txt, at each repository's root. It checks three things:

  1. Inventory. Every file under src/main, src/test, src/gameTest, src/runtimeProbe and scripts/
     has a counterpart in the other repository, after the declared path rewrites (the 1.21.1
     tag and structure directories are singular, and NeoForge reads neoforge.mods.toml).
  2. Neutral files. A file the manifest lists as neutral is byte-identical in both repositories
     (line endings aside). Most of the mod is written to be neutral on purpose.
  3. Paired files. A loader-specific pair must still hash to what the manifest recorded. Changing
     either half without re-recording fails, so an edit cannot reach one loader silently.

Usage:
    python3 scripts/parity.py                   # check, finding the sibling repository
    python3 scripts/parity.py <forge> <neo>     # check two explicit repository roots
    python3 scripts/parity.py --write [...]     # after porting a change to both: record it

--write also reports any file whose classification changed (for example a neutral file that now
differs), so a reviewer sees it. It exits 1 on any drift and 0 when both sides are in step.
"""

import hashlib
import os
import sys

MANIFEST = 'parity-manifest.txt'
TRACKED = ['src/main', 'src/test', 'src/gameTest', 'src/runtimeProbe', 'scripts']
SKIP_DIRS = {'__pycache__'}

# Forge path fragment -> NeoForge path fragment. Everything else keeps its path.
REWRITES = [
    ('/tags/entity_types/', '/tags/entity_type/'),
    ('/structures/', '/structure/'),
    ('META-INF/mods.toml', 'META-INF/neoforge.mods.toml'),
]

# Files that exist on one loader only, by design. Keep this empty unless a loader truly needs it.
ONE_SIDED = set()


def to_neo(path):
    for forge, neo in REWRITES:
        path = path.replace(forge, neo)
    return path


def is_forge(root):
    return os.path.isfile(os.path.join(root, 'src/main/resources/META-INF/mods.toml'))


def find_roots(argv):
    if len(argv) >= 2:
        a, b = (os.path.abspath(p) for p in argv[:2])
        return (a, b) if is_forge(a) else (b, a)
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    if is_forge(here):
        other = os.path.join(os.path.dirname(here), '1.21.1 Ports', 'MCAMobCompatibility_1.21.1')
        return here, other
    other = os.path.join(os.path.dirname(os.path.dirname(here)), 'MCAMobCompatibility')
    return other, here


def digest(root, rel):
    with open(os.path.join(root, rel), 'rb') as f:
        data = f.read()
    return hashlib.sha256(data.replace(b'\r\n', b'\n')).hexdigest()


def inventory(root):
    files = set()
    for tracked in TRACKED:
        base = os.path.join(root, tracked)
        for dirpath, dirnames, filenames in os.walk(base):
            dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
            for name in filenames:
                files.add(os.path.relpath(os.path.join(dirpath, name), root).replace(os.sep, '/'))
    return files


def current(forge, neo):
    """(entries, problems): entries maps the Forge path to (kind, forge hash, neo hash, neo path)."""
    problems = []
    forge_files = inventory(forge)
    neo_files = inventory(neo)
    mapped = {to_neo(f): f for f in forge_files}
    for f in sorted(forge_files):
        if to_neo(f) not in neo_files and f not in ONE_SIDED:
            problems.append('only in Forge: ' + f)
    for n in sorted(neo_files):
        if n not in mapped and n not in ONE_SIDED:
            problems.append('only in NeoForge: ' + n)
    entries = {}
    for f in sorted(forge_files):
        n = to_neo(f)
        if n not in neo_files:
            continue
        fh, nh = digest(forge, f), digest(neo, n)
        entries[f] = ('neutral' if fh == nh else 'pair', fh, nh, n)
    return entries, problems


def read_manifest(root):
    path = os.path.join(root, MANIFEST)
    if not os.path.isfile(path):
        return None
    recorded = {}
    with open(path, encoding='utf-8') as f:
        for line in f:
            line = line.rstrip('\n')
            if not line or line.startswith('#'):
                continue
            kind, rest = line.split(' ', 1)
            if kind == 'neutral':
                h, f_path = rest.split(' ', 1)
                recorded[f_path] = ('neutral', h, h, to_neo(f_path))
            else:
                fh, nh, pair = rest.split(' ', 2)
                f_path, n_path = pair.split(' -> ')
                recorded[f_path] = ('pair', fh, nh, n_path)
    return recorded


def render(entries):
    neutral = sum(1 for e in entries.values() if e[0] == 'neutral')
    lines = ['# MCA: Mob Compatibility parity manifest: the Forge 1.20.1 and NeoForge 1.21.1 builds.',
             '# Identical in both repositories. Regenerate with `python3 scripts/parity.py --write`',
             '# only after a change has been made on both loaders. Do not hand-edit.',
             '# %d neutral (byte-identical) files, %d loader-specific pairs.'
             % (neutral, len(entries) - neutral), '']
    for f, (kind, fh, nh, n) in sorted(entries.items()):
        if kind == 'neutral':
            lines.append('neutral %s %s' % (fh, f))
        else:
            lines.append('pair %s %s %s -> %s' % (fh, nh, f, n))
    return '\n'.join(lines) + '\n'


def main():
    args = [a for a in sys.argv[1:] if a != '--write']
    write = '--write' in sys.argv[1:]
    forge, neo = find_roots(args)
    for root, name in ((forge, 'Forge'), (neo, 'NeoForge')):
        if not os.path.isdir(root):
            print('%s repository not found at %s; pass both roots explicitly.' % (name, root))
            return 1
    entries, problems = current(forge, neo)
    if write:
        if problems:
            print('Inventory drift; fix it before recording:')
            for p in problems:
                print('  ' + p)
            return 1
        before = read_manifest(forge) or {}
        for f, entry in sorted(entries.items()):
            if f in before and before[f][0] != entry[0]:
                print('reclassified %s: %s -> %s' % (f, before[f][0], entry[0]))
        text = render(entries)
        for root in (forge, neo):
            with open(os.path.join(root, MANIFEST), 'w', encoding='utf-8', newline='\n') as f:
                f.write(text)
        neutral = sum(1 for e in entries.values() if e[0] == 'neutral')
        print('Recorded %d files: %d neutral, %d loader-specific pairs.'
              % (len(entries), neutral, len(entries) - neutral))
        return 0

    forge_manifest, neo_manifest = read_manifest(forge), read_manifest(neo)
    if forge_manifest is None or neo_manifest is None:
        problems.append('%s is missing; run with --write once both sides agree.' % MANIFEST)
    else:
        if open(os.path.join(forge, MANIFEST), 'rb').read() != open(os.path.join(neo, MANIFEST), 'rb').read():
            problems.append('the two copies of %s differ' % MANIFEST)
        for f, (kind, fh, nh, n) in sorted(entries.items()):
            recorded = forge_manifest.get(f)
            if recorded is None:
                problems.append('not in the manifest: %s' % f)
            elif recorded[0] == 'neutral' and kind != 'neutral':
                problems.append('neutral file differs between loaders: %s' % f)
            elif recorded[0] == 'pair' and (fh != recorded[1] or nh != recorded[2]):
                side = ' and '.join(s for s, changed in (('Forge', fh != recorded[1]), ('NeoForge', nh != recorded[2]))
                                    if changed)
                problems.append('loader-specific pair changed on %s since it was recorded: %s' % (side, f))
        for f in sorted(set(forge_manifest) - set(entries)):
            problems.append('recorded but gone: %s' % f)
    if problems:
        print('Parity drift between %s and %s:' % (forge, neo))
        for p in problems:
            print('  ' + p)
        return 1
    neutral = sum(1 for e in entries.values() if e[0] == 'neutral')
    print('In step: %d files, %d neutral and %d loader-specific pairs as recorded.'
          % (len(entries), neutral, len(entries) - neutral))
    return 0


if __name__ == '__main__':
    sys.exit(main())
