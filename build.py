"""Build the two-dex LSPosed module using JDK 17, Python 3.10+ and Android SDK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parent

def run(args):
    result = subprocess.run([str(a) for a in args], cwd=ROOT, capture_output=True,
                            text=True, encoding='utf-8', errors='replace')
    if result.returncode:
        raise RuntimeError(result.stdout + '\n' + result.stderr)
    if result.stdout.strip():
        print(result.stdout.strip())

def tool(name):
    found = shutil.which(name)
    if not found and os.environ.get('JAVA_HOME'):
        candidate = Path(os.environ['JAVA_HOME']) / 'bin' / (name + ('.exe' if os.name == 'nt' else ''))
        if candidate.exists():
            found = str(candidate)
    if not found:
        raise RuntimeError(f'{name} not found; install JDK 17 and configure PATH or JAVA_HOME')
    return found

def sdk_paths():
    sdk = os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME')
    if sdk:
        sdk = Path(sdk)
        def version(path):
            return tuple(int(x) for x in re.findall(r'\d+', path.name))
        platforms = sorted((sdk / 'platforms').glob('android-*'), key=version)
        builds = sorted((sdk / 'build-tools').glob('*'), key=version)
        if platforms and builds:
            return platforms[-1] / 'android.jar', builds[-1]
    return ROOT / 'tools/plat/android.jar', ROOT / 'tools/bt'

def dex_classes(data):
    count, offset = struct.unpack_from('<II', data, 96)
    strings = struct.unpack_from('<I', data, 60)[0]
    types = struct.unpack_from('<I', data, 68)[0]
    names = []
    for i in range(count):
        type_index = struct.unpack_from('<I', data, offset + i * 32)[0]
        string_index = struct.unpack_from('<I', data, types + type_index * 4)[0]
        pos = struct.unpack_from('<I', data, strings + string_index * 4)[0]
        while data[pos] & 128:
            pos += 1
        pos += 1
        names.append(data[pos:data.index(b'\0', pos)].decode('utf-8'))
    return names

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--debug', action='store_true', help='Use .private/debug.keystore for device testing')
    parser.add_argument('--signing-config', type=Path, default=ROOT / '.private/signing.json')
    args = parser.parse_args()
    java, javac = tool('java'), tool('javac')
    platform, bt = sdk_paths()
    suffix = '.exe' if os.name == 'nt' else ''
    required = [platform, bt / ('aapt2' + suffix), bt / ('zipalign' + suffix),
                bt / 'lib/d8.jar', bt / 'lib/apksigner.jar']
    for path in required:
        if not path.is_file():
            raise RuntimeError(f'Missing {path}; configure ANDROID_SDK_ROOT with platform 35+ and build-tools 35+')
    if args.debug:
        key = ROOT / '.private/debug.keystore'
        alias, password = 'androiddebugkey', 'pass:android'
    else:
        if not args.signing_config.is_file():
            raise RuntimeError('Release signing configuration missing; see README.txt')
        config = json.loads(args.signing_config.read_text(encoding='utf-8'))
        key = (args.signing_config.parent / config['keystore']).resolve()
        password_file = (args.signing_config.parent / config['password_file']).resolve()
        if not password_file.is_file():
            raise RuntimeError('Signing password file missing')
        alias, password = config['alias'], 'file:' + str(password_file)
    if not key.is_file():
        raise RuntimeError('Signing keystore missing')
    version = json.loads((ROOT / 'version.json').read_text(encoding='utf-8'))
    build = ROOT / 'build'
    # Only clean this project's generated build directory.
    if build.is_symlink() or build.resolve().parent != ROOT.resolve():
        raise RuntimeError('Unsafe build directory')
    if build.exists():
        shutil.rmtree(build)
    classes = build / 'classes'
    classes.mkdir(parents=True)
    sources = sorted((ROOT / 'src').rglob('*.java')) + sorted((ROOT / 'stubs').rglob('*.java'))
    run([javac, '-encoding', 'UTF-8', '--release', '11', '-cp', platform, '-d', classes] + sources)
    pkg = classes / 'com/zcode/iosnettype'
    for kind, files in (
        ('loader', sorted(pkg.glob('MainHook*.class'))),
        ('view', sorted(p for p in pkg.glob('*.class') if not p.name.startswith('MainHook')))):
        if not files:
            raise RuntimeError(f'No {kind} classes')
        output = build / kind
        output.mkdir()
        run([java, '-cp', bt / 'lib/d8.jar', 'com.android.tools.r8.D8', '--release',
             '--min-api', '33', '--lib', platform, '--classpath', classes, '--output', output] + files)
        names = dex_classes((output / 'classes.dex').read_bytes())
        if not names or not all(n.startswith('Lcom/zcode/iosnettype/') for n in names):
            raise RuntimeError('Compile-only stubs leaked into APK')
    unsigned = build / 'base.apk'
    run([bt / ('aapt2' + suffix), 'link', '-o', unsigned, '--manifest', ROOT / 'AndroidManifest.xml',
         '-I', platform, '--min-sdk-version', '33', '--target-sdk-version', '34',
         '--version-code', str(version['code']), '--version-name', version['name']])
    with zipfile.ZipFile(unsigned) as archive:
        entries = {name: archive.read(name) for name in archive.namelist()}
    entries['classes.dex'] = (build / 'loader/classes.dex').read_bytes()
    entries['assets/view.dex'] = (build / 'view/classes.dex').read_bytes()
    entries['assets/xposed_init'] = b'com.zcode.iosnettype.MainHook\n'
    merged = build / 'merged.apk'
    with zipfile.ZipFile(merged, 'w') as archive:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (2020, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_STORED if name == 'resources.arsc' else zipfile.ZIP_DEFLATED
            archive.writestr(info, data)
    aligned = build / 'aligned.apk'
    run([bt / ('zipalign' + suffix), '-f', '-p', '4', merged, aligned])
    dist = ROOT / 'dist'
    dist.mkdir(exist_ok=True)
    flavor = 'debug' if args.debug else 'release'
    apk = dist / f'iOSNetType-{version["name"]}-{flavor}.apk'
    run([java, '-jar', bt / 'lib/apksigner.jar', 'sign', '--ks', key, '--ks-pass', password,
         '--ks-key-alias', alias, '--v4-signing-enabled', 'false',
         '--out', apk, aligned])
    run([java, '-jar', bt / 'lib/apksigner.jar', 'verify', '--verbose', apk])
    run([bt / ('zipalign' + suffix), '-c', '-p', '4', apk])
    with zipfile.ZipFile(apk) as archive:
        allowed = {'AndroidManifest.xml', 'resources.arsc', 'classes.dex', 'assets/view.dex', 'assets/xposed_init'}
        unexpected = [n for n in archive.namelist() if n not in allowed and not n.startswith('META-INF/')]
        if unexpected:
            raise RuntimeError(f'Unexpected packaged files: {unexpected}')
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    apk.with_suffix('.apk.sha256').write_text(f'{digest}  {apk.name}\n', encoding='ascii')
    print(f'Built {apk.name} ({apk.stat().st_size} bytes)')

if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, ValueError) as error:
        print('Build failed:', error, file=sys.stderr)
        sys.exit(1)
