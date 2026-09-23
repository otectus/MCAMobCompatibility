"""Launch an isolated installer-created client profile (no account credentials).

Serves both builds of MCA: Mob Compatibility: a NeoForge 1.21.1 profile (versions/neoforge-<v>) or a
Forge 1.20.1 profile (versions/1.20.1-forge-<v>), whichever RUNTIME_ROOT holds. MCAMOB_PROBE_PROFILE
names the profile when there is more than one; MCAMOB_PROBE_NEOFORGE=<v> is kept as a shorthand for
neoforge-<v>. Java and the asset cache follow the profile's Minecraft version and can be overridden
with MCAMOB_PROBE_JAVA and MCAMOB_PROBE_ASSETS.

Usage: launch_client.py RUNTIME_ROOT GAME_DIR USER [--prepare-only] [--solo WORLD]
Only downloads SHA-1 verified artifacts declared by the Mojang and loader manifests.
"""
from pathlib import Path
import sys, json, hashlib, urllib.request, shutil, os, re, uuid

root, game = map(lambda p: Path(p).resolve(), sys.argv[1:3])
user = sys.argv[3]
home = Path.home()

versions = root / 'versions'
profile = os.environ.get('MCAMOB_PROBE_PROFILE')
if not profile and os.environ.get('MCAMOB_PROBE_NEOFORGE'):
    profile = 'neoforge-' + os.environ['MCAMOB_PROBE_NEOFORGE']
if not profile:
    loaders = [d.name for d in versions.iterdir()
               if (d / (d.name + '.json')).exists() and 'inheritsFrom' in json.loads((d / (d.name + '.json')).read_text())]
    if len(loaders) != 1:
        raise SystemExit('Set MCAMOB_PROBE_PROFILE to one of: ' + ', '.join(sorted(loaders)))
    profile = loaders[0]
loader = json.loads((versions / profile / (profile + '.json')).read_text())
mc = loader['inheritsFrom']
vanilla = json.loads((versions / mc / (mc + '.json')).read_text())

libdir = root / 'libraries'
game.mkdir(parents=True, exist_ok=True)
cache = home / '.gradle/caches/modules-2/files-2.1'


def allowed(rules):
    if not rules:
        return True
    result = False
    for rule in rules:
        target = rule.get('os', {})
        if target.get('name', 'linux') != 'linux' or target.get('arch', 'amd64') not in ('amd64', 'x86_64'):
            continue
        if rule.get('features'):
            continue
        result = rule['action'] == 'allow'
    return result


libs = {}
for lib in vanilla['libraries'] + loader['libraries']:
    if allowed(lib.get('rules', [])):
        parts = lib['name'].split(':')
        libs[':'.join(parts[:2] + parts[3:])] = lib
classpath = []
for lib in libs.values():
    a = lib.get('downloads', {}).get('artifact')
    if not a:
        continue
    dest = libdir / a['path']
    dest.parent.mkdir(parents=True, exist_ok=True)

    def valid(p):
        return p.exists() and hashlib.sha1(p.read_bytes()).hexdigest() == a['sha1']

    if not valid(dest):
        parts = lib['name'].split(':')
        local = cache / parts[0] / parts[1] / parts[2]
        found = next((p for p in (local.glob('*/*') if local.exists() else []) if valid(p)), None)
        if found:
            shutil.copy2(found, dest)
        elif a.get('url'):
            print('Download', a['path'], flush=True)
            with urllib.request.urlopen(a['url'], timeout=60) as resp:
                dest.write_bytes(resp.read())
        if not valid(dest):
            raise ValueError('Missing or mismatched library: ' + str(dest))
    classpath.append(str(dest))
classpath.append(str(versions / mc / (mc + '.jar')))
if '--prepare-only' in sys.argv:
    sys.exit(0)

index = vanilla['assetIndex']['id']
assets = os.environ.get('MCAMOB_PROBE_ASSETS') or next(
    (str(p) for p in (home / '.gradle/caches/neoformruntime/assets', home / '.gradle/caches/forge_gradle/assets')
     if (p / 'indexes' / (index + '.json')).exists()), str(root / 'assets'))
props = {'natives_directory': str(game / 'natives'), 'launcher_name': 'MCA-port-validation', 'launcher_version': '1',
         'classpath': ':'.join(classpath), 'library_directory': str(libdir), 'classpath_separator': ':',
         'version_name': loader['id'], 'auth_player_name': user, 'game_directory': str(game), 'assets_root': assets,
         'assets_index_name': index, 'auth_uuid': str(uuid.uuid3(uuid.NAMESPACE_DNS, user)).replace('-', ''),
         'auth_access_token': '0', 'user_type': 'legacy', 'version_type': 'release', 'clientid': '', 'auth_xuid': ''}


def expand(args):
    result = []
    for arg in args:
        if isinstance(arg, dict):
            if not allowed(arg.get('rules', [])):
                continue
            values = arg['value']
            values = values if isinstance(values, list) else [values]
        else:
            values = [arg]
        for value in values:
            result.append(re.sub(r'\$\{([^}]+)\}', lambda m: props[m[1]], value))
    return result


jvm = expand(vanilla['arguments']['jvm'] + loader['arguments']['jvm'])
jvm = [arg + ',' + mc + '.jar' if arg.startswith('-DignoreList=') else arg for arg in jvm]
args = expand(vanilla['arguments']['game'] + loader['arguments']['game'])
if '--solo' in sys.argv:
    args += ['--quickPlaySingleplayer', sys.argv[sys.argv.index('--solo') + 1]]
else:
    args += ['--quickPlayMultiplayer', os.environ.get('MCAMOB_PROBE_SERVER', '127.0.0.1:25576')]
args += ['--width', '960', '--height', '640']
(game / 'options.txt').write_text('renderDistance:4\nsimulationDistance:5\nmaxFps:30\nenableVsync:false\nfov:0.0\n'
                                  'joinedFirstServer:true\nskipMultiplayerWarning:true\npauseOnLostFocus:false\n'
                                  'soundCategory_master:0.0\n')
major = vanilla.get('javaVersion', {}).get('majorVersion', 21)
java = os.environ.get('MCAMOB_PROBE_JAVA') or (
    str(home / '.gradle/jdks/eclipse_adoptium-21-amd64-linux.2/bin/java') if major >= 21
    else '/usr/lib/jvm/java-17-openjdk/bin/java')
os.chdir(game)
os.execv(java, [java, '-Xmx3G', '-Dfml.earlyWindowControl=false', *jvm, loader['mainClass'], *args])
