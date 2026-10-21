"""Run one production validation scenario against an installer-created runtime.

run_scenario.py <runtime-dir> <scenario>
  scenario: two | solo | minimal | missing | guards
The runtime dir holds: server/ (the loader installer's server install), client/ (its client
install), mods-base/ (release jar, runtime-probe jar, MCA, Guard Villagers, on Forge Architectury,
and every native-character mod the platform has a build of: Villager Recruits and Villager Workers
on Forge), mods-guards/ (the same with Guard Villagers as the only native mod; optional),
mods-minimal/ (the same without Guard Villagers), mods-missing/ (MCA and Guard Villagers without
this mod), java.txt (the java binary), launcher (the path of launch_client.py)
and run-args.txt (the server's argument files, e.g. "@user_jvm_args.txt @libraries/.../unix_args.txt").
Clients run under `gamescope --backend headless`. Results land in <runtime-dir>/results/<scenario>/.
The worlds, servers and clients are disposable; never point this at a world you want to keep.
"""
import os, sys, time, shutil, subprocess, glob, re
from pathlib import Path

rt = Path(sys.argv[1]).resolve(); scenario = sys.argv[2]
java = (rt / 'java.txt').read_text().strip()
launcher = (rt / 'launcher').read_text().strip()
server = rt / 'server'; out = rt / 'results' / scenario
shutil.rmtree(out, ignore_errors=True); out.mkdir(parents=True)

def reset_server(mods):
    for d in ('world', 'logs', 'mods', 'config', 'crash-reports'):
        shutil.rmtree(server / d, ignore_errors=True)
    shutil.copytree(mods, server / 'mods')
    (server / 'eula.txt').write_text('eula=true\n')
    (server / 'server.properties').write_text('online-mode=false\nserver-port=25576\nserver-ip=127.0.0.1\n'
        'level-type=minecraft\\:flat\ndifficulty=peaceful\nspawn-protection=0\nmax-players=4\n'
        'generate-structures=false\nspawn-monsters=false\nview-distance=6\nsimulation-distance=5\n')

def start_server():
    args = (rt / 'run-args.txt').read_text().split()
    log = open(out / 'server-stdout.log', 'w')
    p = subprocess.Popen([java, '-Xmx3G', *args, 'nogui'], cwd=server, stdin=subprocess.PIPE, stdout=log,
                         stderr=subprocess.STDOUT, text=True)
    deadline = time.time() + 300
    while time.time() < deadline:
        latest = server / 'logs' / 'latest.log'
        if latest.exists() and re.search(r'Done \(', latest.read_text(errors='replace')):
            return p
        if p.poll() is not None:
            raise SystemExit('server exited early: %s' % p.returncode)
        time.sleep(2)
    raise SystemExit('server did not start')

def stop_server(p):
    try:
        p.stdin.write('stop\n'); p.stdin.flush()
        p.wait(120)
    except Exception:
        p.kill()
    shutil.copy2(server / 'logs' / 'latest.log', out / 'server.log')

def client(user, mods, extra=(), timeout=900):
    game = rt / 'games' / user
    shutil.rmtree(game, ignore_errors=True); game.mkdir(parents=True)
    shutil.copytree(mods, game / 'mods')
    env = dict(os.environ)
    cmd = ['gamescope', '--backend', 'headless', '-W', '960', '-H', '640', '--', 'python3', launcher,
           str(rt / 'client'), str(game), user, *extra]
    log = open(out / ('%s-stdout.log' % user), 'w')
    return game, subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT, env=env), time.time() + timeout

def wait_clients(clients):
    for game, p, deadline in clients:
        try:
            p.wait(max(1, deadline - time.time()))
        except subprocess.TimeoutExpired:
            p.kill(); p.wait()
    for game, p, deadline in clients:
        name = game.name
        if (game / 'logs' / 'latest.log').exists():
            shutil.copy2(game / 'logs' / 'latest.log', out / ('%s.log' % name))
        for shot in glob.glob(str(game / 'screenshots' / 'probe-*.png')):
            shutil.copy2(shot, out / ('%s-%s' % (name, os.path.basename(shot))))

if scenario == 'two':
    reset_server(rt / 'mods-base'); s = start_server()
    cs = [client('ProbeOne', rt / 'mods-base'), client('ProbeTwo', rt / 'mods-base')]
    wait_clients(cs); stop_server(s)
elif scenario == 'guards':
    reset_server(rt / 'mods-guards'); s = start_server()
    wait_clients([client('ProbeGuards', rt / 'mods-guards')]); stop_server(s)
elif scenario == 'minimal':
    reset_server(rt / 'mods-minimal'); s = start_server()
    wait_clients([client('ProbeMinimal', rt / 'mods-minimal', timeout=600)]); stop_server(s)
elif scenario == 'missing':
    reset_server(rt / 'mods-base'); s = start_server()
    wait_clients([client('ProbeMissing', rt / 'mods-missing', timeout=240)]); stop_server(s)
elif scenario == 'solo':
    # A disposable single-player world: the dedicated server writes it, the client opens a copy.
    reset_server(rt / 'mods-base'); s = start_server(); stop_server(s)
    game = rt / 'games' / 'ProbeSolo'
    shutil.rmtree(game, ignore_errors=True); (game / 'saves').mkdir(parents=True)
    shutil.copytree(server / 'world', game / 'saves' / 'probe-solo')
    shutil.copytree(rt / 'mods-base', game / 'mods')
    log = open(out / 'ProbeSolo-stdout.log', 'w')
    p = subprocess.Popen(['gamescope', '--backend', 'headless', '-W', '960', '-H', '640', '--', 'python3', launcher,
                          str(rt / 'client'), str(game), 'ProbeSolo', '--solo', 'probe-solo'], stdout=log, stderr=subprocess.STDOUT)
    wait_clients([(game, p, time.time() + 900)])
for f in sorted(out.glob('*.log')):
    lines = [l.rstrip() for l in open(f, errors='replace') if 'RUNTIME_PROBE' in l or 'Disconnected' in l
             or 'disconnect' in l.lower() and 'proxy_pair' in l]
    if lines:
        print('==', f.name)
        for l in lines: print('  ', l[-260:])
