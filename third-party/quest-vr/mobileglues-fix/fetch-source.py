import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import subprocess
import tempfile
import urllib.request
import zipfile


HERE = Path(__file__).resolve().parent


def sha256(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def inside(path, parent):
    return path == parent or parent in path.parents


def get_archive(entry, cache, archive_dir):
    filename = entry['name'] + '-' + entry['revision'] + '.zip'
    target = cache / filename
    if archive_dir:
        candidates = [archive_dir / filename, archive_dir / (entry['name'] + '.zip')]
        if entry['name'] == 'MobileGlues':
            candidates.append(archive_dir / 'source.zip')
        target = next((path for path in candidates if path.is_file()), None)
        if target is None:
            raise FileNotFoundError('Missing cached archive: ' + filename)
    elif not target.exists():
        with tempfile.NamedTemporaryFile(dir=cache, suffix='.download', delete=False) as stream:
            temporary = Path(stream.name)
            try:
                with urllib.request.urlopen(entry['archive_url'], timeout=60) as response:
                    shutil.copyfileobj(response, stream)
            except BaseException:
                stream.close()
                temporary.unlink(missing_ok=True)
                raise
        try:
            if sha256(temporary) != entry['archive_sha256']:
                raise ValueError('Archive SHA256 mismatch: ' + filename)
            temporary.replace(target)
        finally:
            temporary.unlink(missing_ok=True)
    if sha256(target) != entry['archive_sha256']:
        raise ValueError('Archive SHA256 mismatch: ' + filename)
    return target


def extract_archive(archive, destination):
    destination = destination.resolve()
    with zipfile.ZipFile(archive) as source:
        roots = set()
        members = []
        seen = set()
        for member in source.infolist():
            name = member.filename.rstrip('/')
            parts = name.split('/')
            mode = member.external_attr >> 16
            if not name or '\\' in name or ':' in name or name.startswith('/'):
                raise ValueError('Invalid ZIP path: ' + member.filename)
            if any(part in ('', '.', '..') for part in parts):
                raise ValueError('Invalid ZIP path: ' + member.filename)
            if stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR):
                raise ValueError('Unsupported ZIP member: ' + member.filename)
            roots.add(parts[0])
            if len(parts) == 1:
                if not member.is_dir():
                    raise ValueError('Expected an archive root directory')
                continue
            target = destination.joinpath(*parts[1:]).resolve()
            key = os.path.normcase(str(target))
            if not inside(target, destination) or key in seen:
                raise ValueError('Duplicate or escaping ZIP path: ' + member.filename)
            seen.add(key)
            members.append((member, target, mode))
        if len(roots) != 1:
            raise ValueError('Expected exactly one archive root directory')
        for member, target, mode in members:
            if member.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with source.open(member) as data, target.open('xb') as output:
                    shutil.copyfileobj(data, output)
                if os.name != 'nt' and mode:
                    target.chmod(mode & 0o777)


def main():
    parser = argparse.ArgumentParser(description='Fetch and patch the pinned MobileGlues source outside the launcher tree.')
    parser.add_argument('--work-dir', required=True, type=Path)
    parser.add_argument('--archive-dir', type=Path, help='Use only verified local ZIPs; do not access the network.')
    args = parser.parse_args()
    work = args.work_dir.resolve()
    if inside(work, HERE.parents[2]):
        parser.error('--work-dir must be outside the launcher source tree')
    if shutil.which('git') is None:
        parser.error('Git is required to apply the source patch')
    provenance = json.loads((HERE / 'provenance.json').read_text(encoding='utf-8'))
    entries = provenance['sources']
    output = work / ('MobileGlues-' + entries[0]['revision'])
    if output.exists():
        parser.error('Source output already exists: ' + str(output))
    work.mkdir(parents=True, exist_ok=True)
    cache = work / 'archives'
    cache.mkdir(exist_ok=True)
    archive_dir = args.archive_dir.resolve() if args.archive_dir else None
    with tempfile.TemporaryDirectory(prefix='mobileglues-', dir=work) as temporary:
        source = Path(temporary) / output.name
        source.mkdir()
        for entry in entries:
            subdirectory = PurePosixPath(entry['source_subdirectory'])
            target = source.joinpath(*subdirectory.parts).resolve()
            if subdirectory.is_absolute() or not inside(target, source.resolve()):
                raise ValueError('Invalid source subdirectory')
            if target != source and target.exists() and any(target.iterdir()):
                raise ValueError('Dependency directory is not empty: ' + str(target))
            extract_archive(get_archive(entry, cache, archive_dir), target)
        for entry in provenance['patched_files']:
            file = source / 'MobileGlues-cpp' / entry['path']
            if sha256(file) != entry['original_sha256']:
                raise ValueError('Unexpected original source: ' + entry['path'])
        environment = os.environ.copy()
        for name in ('GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE', 'GIT_COMMON_DIR'):
            environment.pop(name, None)
        environment['GIT_CEILING_DIRECTORIES'] = str(source.parent)
        command = ['git', '-c', 'core.autocrlf=false', '-c', 'core.eol=lf', 'apply', '--whitespace=nowarn']
        patch = str(HERE / 'mobileglues-shader-count.patch')
        subprocess.run(command + ['--check', patch], cwd=source, env=environment, check=True)
        subprocess.run(command + [patch], cwd=source, env=environment, check=True)
        for entry in provenance['patched_files']:
            if sha256(source / 'MobileGlues-cpp' / entry['path']) != entry['sha256']:
                raise ValueError('Patched source SHA256 mismatch: ' + entry['path'])
        source.rename(output)
    print('Verified source: ' + str(output / 'MobileGlues-cpp'))


if __name__ == '__main__':
    main()
