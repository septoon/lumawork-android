#!/usr/bin/env python3
"""Build and verify locally; publish Android independently through SSH."""
import datetime as dt
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import uuid

SCRIPTS = Path(__file__).resolve().parent
PROJECT = SCRIPTS.parent
spec = importlib.util.spec_from_file_location('contract', SCRIPTS / 'android-release.py')
contract = importlib.util.module_from_spec(spec)
spec.loader.exec_module(contract)
require = contract.require


def run(*args, **kwargs):
    return subprocess.run([str(x) for x in args], check=True, **kwargs)


def get_json(url, optional=False):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={'Cache-Control': 'no-cache'}), timeout=30) as response:
            data = response.read(131073)
            require(len(data) <= 131072, 'Metadata too large')
            return json.loads(data)
    except urllib.error.HTTPError as e:
        if optional and e.code == 404:
            return None
        raise


def sdk_tool(name):
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:
        text = (PROJECT / 'local.properties').read_text()
        sdk = re.search(r'^sdk.dir=(.+)$', text, re.M).group(1).replace('\\:', ':').replace('\\ ', ' ')
    tools = list((Path(sdk) / 'build-tools').glob(f'*/{name}'))
    require(tools, f'{name} not found')
    return sorted(tools, key=lambda p: tuple(int(x) for x in re.findall(r'\d+', p.parent.name)))[-1]


def inspect(apk):
    signer = run(sdk_tool('apksigner'), 'verify', '--print-certs', apk, capture_output=True, text=True).stdout
    certs = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)', signer)
    require(len(certs) == 1, 'Expected one release signer')
    info = run(sdk_tool('aapt2'), 'dump', 'badging', apk, capture_output=True, text=True).stdout
    return (*parse_badging(info), certs[0].lower())


def parse_badging(info):
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", info)
    require(package, 'APK package/version not found')
    sdk = re.search(r"^(?:sdkVersion|minSdkVersion):'(\d+)'", info, re.M)
    require(sdk, 'APK minSdk missing')
    require(not re.search(r'^application-debuggable', info, re.M), 'Debug APK cannot be published')
    return package.group(1), int(package.group(2)), package.group(3), int(sdk.group(1))



def main():
    check_only = '--check-only' in sys.argv[1:]
    notes = [x for x in sys.argv[1:] if x != '--check-only']
    notes = '\n'.join(notes).strip() or 'Обновление «Инженер» для Android'
    previous = get_json(contract.ORIGIN + '/android/latest.json', optional=True)
    if previous:
        contract.validate(previous)
        # Verify actual previous public APK and its signature, not just its claimed fingerprint.
        with tempfile.TemporaryDirectory() as temp:
            old_apk = Path(temp) / 'previous.apk'
            run('curl', '--fail', '--silent', '--show-error', '--max-time', '180', '--proto', '=https', previous['downloadUrl'], '-o', old_apk)
            require(old_apk.stat().st_size == previous['size'] and hashlib.sha256(old_apk.read_bytes()).hexdigest() == previous['sha256'], 'Previous APK invalid')
            package, code, name, sdk, cert = inspect(old_apk)
            require(package == contract.APP_ID and code == previous['versionCode'] and cert == previous['signingCertificateSHA256'] == contract.TRUSTED_CERT, 'Previous APK signer mismatch')
    else:
        # Refuse to silently abandon an older distribution contract.
        legacy = get_json(contract.ORIGIN + '/android-release.json', optional=True)
        require(legacy is None, 'Legacy Android release exists: explicitly migrate its APK/signature before bootstrap')
        require(os.environ.get('ALLOW_BOOTSTRAP') == '1', 'First publication requires ALLOW_BOOTSTRAP=1 (only HTTP 404 allows bootstrap)')
    gradle = (PROJECT / 'app/build.gradle.kts').read_text()
    default_code = int(re.search(r'releaseVersionCode.*?\?: (\d+)', gradle).group(1))
    default_name = re.search(r'releaseVersionName.*?\?: "([\d.]+)"', gradle).group(1)
    local_apk = PROJECT / 'app/build/outputs/apk/release/app-release.apk'
    local_code, local_name = 0, default_name
    if local_apk.is_file():
        local_package, local_code, local_name, _, local_cert = inspect(local_apk)
        require(local_package == contract.APP_ID and local_cert == contract.TRUSTED_CERT, 'Existing local APK has unknown identity/signature')
    code = max(default_code, (previous['versionCode'] + 1) if previous else 1, local_code + 1)
    if previous:
        parts = previous['versionName'].split('.')
        parts[-1] = str(int(parts[-1]) + 1)
        name = '.'.join(parts)
    elif local_code:
        parts = local_name.split('.')
        require(len(parts) == 3 and all(p.isdigit() for p in parts), 'Existing APK versionName is not X.Y.Z')
        parts[-1] = str(int(parts[-1]) + 1)
        name = '.'.join(parts)
    else:
        name = default_name
    name = os.environ.get('RELEASE_VERSION', name)
    require(re.fullmatch(r'\d+\.\d+\.\d+', name), 'RELEASE_VERSION must be X.Y.Z')
    env = {**os.environ, 'RELEASE_VERSION_NAME': name, 'RELEASE_VERSION_CODE': str(code)}
    jbr = Path('/Applications/Android Studio.app/Contents/jbr/Contents/Home')
    if not env.get('JAVA_HOME') and jbr.is_dir():
        env['JAVA_HOME'] = str(jbr)
        env['PATH'] = str(jbr / 'bin') + os.pathsep + env['PATH']
    run(SCRIPTS / 'build-apk.sh', 'apk', env=env)
    apk = PROJECT / 'app/build/outputs/apk/release/app-release.apk'
    package, actual_code, actual_name, sdk, cert = inspect(apk)
    require((package, actual_code, actual_name) == (contract.APP_ID, code, name), 'Built APK identity differs from release')
    data = {'schemaVersion': 1, 'applicationId': package, 'versionName': name, 'versionCode': code,
            'downloadUrl': f'{contract.ORIGIN}/android/releases/LumaWork-Android-{name}-{code}.apk',
            'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'size': apk.stat().st_size,
            'publishedAt': dt.datetime.now(dt.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
            'changelog': [line for line in notes.splitlines() if line.strip()], 'minSdk': sdk, 'signingCertificateSHA256': cert}
    contract.check_upgrade(data, previous)
    output = PROJECT / '.build/android-release' / str(code)
    output.mkdir(parents=True, exist_ok=True)
    filename = data['downloadUrl'].rsplit('/', 1)[1]
    shutil.copy2(apk, output / filename)
    (output / 'latest.json').write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
    print(f'APK verified: {name} ({code}); SHA-256 {data["sha256"]}', flush=True)
    if check_only:
        print(f'Local verification only. Artifacts: {output}')
        return
    host = os.environ.get('SSH_HOST', 'lumastack-selectel')
    require(re.fullmatch(r'[a-zA-Z0-9_.@-]+', host) and not host.startswith('-'), 'Invalid SSH_HOST')
    # Server preparation is explicit: no sudo, frontend staging, or MCP binary chunk uploads here.
    run('ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', host,
        "test -f /var/www/app.lumastack.ru/android/.publisher-ready && grep -q -- '--exclude=/android/releases/' /usr/local/sbin/deploy-app-lumastack")
    require(run('ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', host, 'id -u', capture_output=True, text=True).stdout.strip() == '0', 'Publisher requires configured root SSH')
    stage = f'/var/tmp/lumawork-android-{uuid.uuid4().hex}'
    run('ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', host, f'mkdir -m 700 {shlex.quote(stage)}')
    for source in [output / filename, output / 'latest.json', SCRIPTS / 'android-release.py', SCRIPTS / 'publish-android-remote.py']:
        run('scp', '-q', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', source, f'{host}:{stage}/')
    run('ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', host, f'python3 {stage}/publish-android-remote.py')
    published = get_json(contract.ORIGIN + '/android/latest.json')
    require(published == data, 'Public metadata differs after publication')
    print(f'Опубликован Android {name} ({code})\nAPK: {data["downloadUrl"]}\nМетаданные: {contract.ORIGIN}/android/latest.json')


if __name__ == '__main__':
    try:
        main()
    except (Exception, KeyboardInterrupt) as error:
        print(f'Публикация остановлена: {error}', file=sys.stderr)
        sys.exit(1)
