#!/usr/bin/env python3
"""Rebuild the Android VR bindings against FCL's LWJGL 3.4.1 core (JDK 17)."""
import argparse
import hashlib
from pathlib import Path
import subprocess
import tempfile
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
EPOCH = (2026, 11, 7, 0, 0, 0)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--javac', default='javac')
    parser.add_argument('--output', type=Path, default=ROOT / 'FCL/src/main/assets/quest_vr/3.4.1')
    args = parser.parse_args()
    version = subprocess.check_output([args.javac, '-version'], stderr=subprocess.STDOUT).decode().strip()
    if not version.startswith('javac 17.'):
        raise SystemExit('Use JDK 17 javac to reproduce the packaged binaries: ' + version)
    runtime = ROOT / 'FCL/src/main/assets/app_runtime/lwjgl/3.4.1'
    dependencies = [runtime / name for name in ('lwjgl.jar', 'lwjgl-vulkan.jar', 'lwjgl-3.4.1-merged-modules.jar')]
    dependencies.append(ROOT / 'LWJGL/3.3.3/libs/3.3.3/jsr305.jar')
    for dependency in dependencies:
        if not dependency.is_file():
            raise FileNotFoundError(dependency)
    import os
    with tempfile.TemporaryDirectory(prefix='fcl-vr-lwjgl-') as temporary:
        temporary = Path(temporary)
        source = temporary / 'source'
        classes = temporary / 'classes'
        with zipfile.ZipFile(HERE / 'sources.zip') as archive:
            for entry in archive.infolist():
                target = (source / entry.filename).resolve()
                if not target.is_relative_to(source.resolve()):
                    raise ValueError('Invalid source archive path')
            archive.extractall(source)
        files = sorted(source.rglob('*.java'))
        arguments = ['--release', '8', '-encoding', 'UTF-8', '-classpath', os.pathsep.join(map(str, dependencies)), '-d', str(classes)]
        arguments += [str(path) for path in files]
        argfile = temporary / 'javac.args'
        argfile.write_text('\n'.join('"' + argument.replace('\\', '/') + '"' for argument in arguments), encoding='utf-8')
        subprocess.run([args.javac, '@' + str(argfile)], check=True)
        args.output.mkdir(parents=True, exist_ok=True)
        for module, license_name in [('openxr', 'QuestCraft-LWJGL-LICENSE.md'), ('opengles', 'LWJGL-LICENSE.md')]:
            entries = {path.relative_to(classes).as_posix(): path.read_bytes() for path in (classes / 'org/lwjgl' / module).rglob('*.class')}
            entries['META-INF/LICENSE.txt'] = (source / license_name).read_bytes()
            entries['META-INF/MANIFEST.MF'] = b'Manifest-Version: 1.0\r\nImplementation-Title: FCL Quest VR compatibility bindings\r\nImplementation-Version: 3.4.1-compat.1\r\nLWJGL-Core-Version: 3.4.1\r\n\r\n'
            jar = args.output / ('lwjgl-' + module + '.jar')
            with zipfile.ZipFile(jar, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
                for name, data in sorted(entries.items()):
                    info = zipfile.ZipInfo(name, EPOCH)
                    info.compress_type = zipfile.ZIP_DEFLATED
                    archive.writestr(info, data)
            print(jar.name, hashlib.sha256(jar.read_bytes()).hexdigest())

if __name__ == '__main__':
    main()
