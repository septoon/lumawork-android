#!/usr/bin/env python3
"""Runs through root SSH. Holds the same lock as frontend deployment through APK verification and metadata commit."""
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import tempfile
import urllib.request

STAGE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('contract', STAGE / 'android-release.py')
contract = importlib.util.module_from_spec(spec)
spec.loader.exec_module(contract)
require = contract.require
ROOT = Path('/var/www/app.lumastack.ru/android')


def atomic_json(path, data):
    fd, name = tempfile.mkstemp(prefix='.metadata-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as stream:
            json.dump(data, stream, ensure_ascii=False, indent=2)
            stream.write('\n'); stream.flush(); os.fsync(stream.fileno())
        os.chmod(name, 0o644)
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def main():
    require(os.geteuid() == 0, 'Publisher requires the configured root SSH alias')
    require(STAGE.stat().st_uid == 0 and STAGE.stat().st_mode & 0o777 == 0o700, 'Unsafe upload directory')
    require(ROOT.is_dir() and not ROOT.is_symlink() and (ROOT / '.publisher-ready').is_file(), 'Server preparation required')
    require('--exclude=/android/releases/' in Path('/usr/local/sbin/deploy-app-lumastack').read_text(), 'Frontend must preserve Android')
    require(not (ROOT / 'releases').is_symlink(), 'Release validation failed')
    data = contract.validate(json.loads((STAGE / 'latest.json').read_text()))
    apk_name = data['downloadUrl'].rsplit('/', 1)[1]
    apk = STAGE / apk_name
    require(apk.is_file() and not apk.is_symlink(), 'Release validation failed')
    require(apk.stat().st_size == data['size'] and hashlib.sha256(apk.read_bytes()).hexdigest() == data['sha256'], 'Release validation failed')
    with open('/run/lock/app-lumastack-deploy.lock', 'a+') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        latest = ROOT / 'latest.json'
        previous = json.loads(latest.read_text()) if latest.exists() else None
        contract.check_upgrade(data, previous)
        releases = ROOT / 'releases'
        releases.mkdir(exist_ok=True)
        destination = releases / apk_name
        if destination.exists():
            require(not destination.is_symlink() and hashlib.sha256(destination.read_bytes()).hexdigest() == data['sha256'], 'Immutable APK already exists with different content')
        else:
            temporary = releases / f'.{apk_name}.upload'
            shutil.copyfile(apk, temporary)
            os.chmod(temporary, 0o644)
            os.replace(temporary, destination)
        # Verify all public bytes before metadata is visible. No redirects or HTTP fallback.
        request = urllib.request.Request(data['downloadUrl'], headers={'Cache-Control': 'no-cache'})
        with urllib.request.urlopen(request, timeout=120) as response:
            require(response.url == data['downloadUrl'] and int(response.headers['Content-Length']) == data['size'], 'Release validation failed')
            hashed = hashlib.sha256(); size = 0
            while chunk := response.read(65536):
                size += len(chunk)
                require(size <= data['size'], 'Release validation failed')
                hashed.update(chunk)
            require(size == data['size'] and hashed.hexdigest() == data['sha256'], 'Public APK verification failed')
        history_path = ROOT / 'history.json'
        history = json.loads(history_path.read_text()) if history_path.exists() else []
        require(isinstance(history, list), 'Release validation failed')
        for row in history:
            contract.validate(row)
        if previous:
            atomic_json(ROOT / 'previous.json', previous)
            if not any(x['versionCode'] == previous['versionCode'] for x in history):
                history.append(previous)
        history = [data] + [r for r in history if r['versionCode'] != data['versionCode']]
        atomic_json(releases / f'{data["versionCode"]}.json', data)
        atomic_json(history_path, sorted(history, key=lambda r: r['versionCode'], reverse=True))
        atomic_json(latest, data)
        with urllib.request.urlopen(urllib.request.Request(contract.ORIGIN + '/android/latest.json', headers={'Cache-Control': 'no-cache'}), timeout=30) as response:
            require(json.load(response) == data, 'Public metadata verification failed (previous.json retained)')
    print('Android publication verified. Previous releases retained; iOS unchanged.')
    # Only our unique upload directory, never production releases.
    for file in STAGE.iterdir():
        if file.is_file():
            file.unlink()
    pycache = STAGE / '__pycache__'
    if pycache.exists():
        shutil.rmtree(pycache)
    STAGE.rmdir()


if __name__ == '__main__':
    main()
