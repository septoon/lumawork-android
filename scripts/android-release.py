#!/usr/bin/env python3
"""Strict shared release contract. No credentials or server mutations."""
import datetime as dt
import re
from urllib.parse import urlsplit

APP_ID = 'ru.lumastack.lumawork'
ORIGIN = 'https://app.lumastack.ru'
# Existing certificate documented in docs/RELEASE_AND_SIGNING.md, not a new signing key.
TRUSTED_CERT = 'd0382b12ebb468da586b903a4b10dd3d8a732f9a6a09bae580822814ce1b5778'


def require(condition, message="Release validation failed"):
    if not condition:
        raise ValueError(message)


def validate(r):
    require(isinstance(r, dict), 'Expected release object')
    require(type(r.get('schemaVersion')) is int and r['schemaVersion'] == 1, 'Release validation failed')
    require(r.get('applicationId') == APP_ID, 'Release validation failed')
    require(isinstance(r.get('versionName'), str) and re.fullmatch(r'\d+\.\d+\.\d+', r['versionName']), 'Release validation failed')
    for key, lo, hi in [('versionCode', 1, 2100000000), ('size', 1, 536870912), ('minSdk', 26, 1000)]:
        require(type(r.get(key)) is int and lo <= r[key] <= hi, f'Invalid {key}')
    for key in ['sha256', 'signingCertificateSHA256']:
        require(isinstance(r.get(key), str) and re.fullmatch('[a-f0-9]{64}', r[key]), f'Invalid {key}')
    require(isinstance(r.get('changelog'), list) and 1 <= len(r['changelog']) <= 100, 'Release validation failed')
    require(all(isinstance(x, str) and x.strip() and len(x) <= 4000 for x in r['changelog']), 'Release validation failed')
    require(isinstance(r.get('publishedAt'), str) and re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ', r['publishedAt']), 'Release validation failed')
    when = dt.datetime.strptime(r['publishedAt'], '%Y-%m-%dT%H:%M:%SZ').replace(tzinfo=dt.timezone.utc)
    require(when <= dt.datetime.now(dt.timezone.utc) + dt.timedelta(minutes=5), 'Release validation failed')
    require(r.get('downloadUrl') == f"{ORIGIN}/android/releases/LumaWork-Android-{r['versionName']}-{r['versionCode']}.apk", 'Release validation failed')
    return r


def check_upgrade(release, previous):
    validate(release)
    require(release['signingCertificateSHA256'] == TRUSTED_CERT, 'Unknown release signing certificate')
    if previous:
        validate(previous)
        require(release['versionCode'] > previous['versionCode'], 'versionCode must increase')
        require(release['signingCertificateSHA256'] == previous['signingCertificateSHA256'], 'Signing certificate changed')
