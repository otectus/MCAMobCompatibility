"""Launch an isolated installer-created 1.21.1 NeoForge profile (no account credentials).
Usage: launch_client.py RUNTIME_ROOT GAME_DIR USER [--prepare-only] [--solo WORLD]
Only downloads SHA-1 verified artifacts declared by Mojang/NeoForge manifests.
"""
from pathlib import Path
import sys, json, hashlib, urllib.request, shutil, os, re
root,game=map(lambda p:Path(p).resolve(),sys.argv[1:3]); user=sys.argv[3]
vanilla=json.loads((root/'versions/1.21.1/1.21.1.json').read_text())
version=os.environ.get('MCAMOB_PROBE_NEOFORGE','21.1.248')
neo=json.loads((root/f'versions/neoforge-{version}/neoforge-{version}.json').read_text())
libdir=root/'libraries'; game.mkdir(parents=True,exist_ok=True)
cache=Path('/home/otectus/.gradle/caches/modules-2/files-2.1')
def allowed(rules):
    if not rules: return True
    result=False
    for rule in rules:
        target=rule.get('os',{})
        if target.get('name','linux')!='linux' or target.get('arch','amd64') not in ('amd64','x86_64'): continue
        if rule.get('features'): continue
        result=rule['action']=='allow'
    return result
libs={}
for lib in vanilla['libraries']+neo['libraries']:
    if allowed(lib.get('rules',[])):
        parts=lib['name'].split(':'); key=':'.join(parts[:2]+parts[3:]);libs[key]=lib
classpath=[]
for lib in libs.values():
    a=lib.get('downloads',{}).get('artifact')
    if not a:continue
    dest=libdir/a['path'];dest.parent.mkdir(parents=True,exist_ok=True)
    def valid(p):return p.exists() and hashlib.sha1(p.read_bytes()).hexdigest()==a['sha1']
    if not valid(dest):
        parts=lib['name'].split(':'); local=cache/parts[0]/parts[1]/parts[2]
        candidates=list(local.glob('*/*')) if local.exists() else []
        found=next((p for p in candidates if valid(p)),None)
        if found:shutil.copy2(found,dest)
        else:
            print('Download',a['path'],flush=True)
            with urllib.request.urlopen(a['url'],timeout=60) as resp:dest.write_bytes(resp.read())
        if not valid(dest):raise ValueError('Hash mismatch: '+str(dest))
    classpath.append(str(dest))
classpath.append(str(root/'versions/1.21.1/1.21.1.jar'))
if '--prepare-only' in sys.argv:sys.exit(0)
assets=Path('/home/otectus/.gradle/caches/neoformruntime/assets')
props={'natives_directory':str(game/'natives'),'launcher_name':'MCA-port-validation','launcher_version':'1','classpath':':'.join(classpath),'library_directory':str(libdir),'classpath_separator':':','version_name':neo['id'],'auth_player_name':user,'game_directory':str(game),'assets_root':str(assets),'assets_index_name':vanilla['assetIndex']['id'],'auth_uuid':str(__import__('uuid').uuid3(__import__('uuid').NAMESPACE_DNS,user)).replace('-',''),'auth_access_token':'0','user_type':'legacy','version_type':'release','clientid':'','auth_xuid':''}
def expand(args):
    result=[]
    for arg in args:
        if isinstance(arg,dict):
            if not allowed(arg.get('rules',[])):continue
            values=arg['value'];values=values if isinstance(values,list) else [values]
        else:values=[arg]
        for value in values:
            result.append(re.sub(r'\$\{([^}]+)\}',lambda m:props[m[1]],value))
    return result
jvm=expand(vanilla['arguments']['jvm']+neo['arguments']['jvm'])
jvm=[arg+',1.21.1.jar' if arg.startswith('-DignoreList=') else arg for arg in jvm]
args=expand(vanilla['arguments']['game']+neo['arguments']['game'])
if '--solo' in sys.argv:args+=['--quickPlaySingleplayer',sys.argv[sys.argv.index('--solo')+1]]
else:args+=['--quickPlayMultiplayer',os.environ.get('MCAMOB_PROBE_SERVER','127.0.0.1:25576')]
args+=['--width','960','--height','640']
(game/'options.txt').write_text('renderDistance:4\nsimulationDistance:5\nmaxFps:30\nenableVsync:false\nfov:0.0\njoinedFirstServer:true\nskipMultiplayerWarning:true\npauseOnLostFocus:false\nsoundCategory_master:0.0\n')
java='/home/otectus/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2/bin/java'
os.chdir(game)
os.execv(java,[java,'-Xmx3G','-Dfml.earlyWindowControl=false',*jvm,neo['mainClass'],*args])
