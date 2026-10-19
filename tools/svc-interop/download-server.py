#!/usr/bin/env python3
"""Fetch official test-server artifacts and require their API-provided digests."""
import hashlib
import json
from pathlib import Path
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

AGENT = 'MCVoice CI (https://github.com/RavoxX/MCVoice)'


def request(url):
    if urllib.parse.urlsplit(url).scheme != 'https':
        raise ValueError('HTTPS required')
    return urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': AGENT}), timeout=60)


def metadata(url):
    with request(url) as response:
        return json.load(response)


def verified_download(url, target, algorithm, expected):
    if algorithm not in ('sha256', 'sha512') or not re.fullmatch(r'[0-9a-fA-F]{%d}' % (64 if algorithm == 'sha256' else 128), expected):
        raise ValueError('missing or invalid download checksum')
    digest = hashlib.new(algorithm)
    staging = target.with_suffix('.download')
    try:
        with request(url) as response, staging.open('wb') as out:
            total = 0
            while chunk := response.read(1024 * 1024):
                total += len(chunk)
                if total > 128 * 1024 * 1024:
                    raise ValueError('test artifact too large')
                digest.update(chunk)
                out.write(chunk)
        if digest.hexdigest() != expected.lower():
            raise ValueError('test artifact checksum mismatch')
        staging.replace(target)
    finally:
        staging.unlink(missing_ok=True)


def main():
    mc = sys.argv[1]
    if not re.fullmatch(r'\d+(?:\.\d+){1,2}', mc):
        raise ValueError('invalid Minecraft version')
    try:
        builds = metadata(f'https://fill.papermc.io/v3/projects/paper/versions/{mc}/builds')
        paper = next(b['downloads']['server:default'] for b in builds if b.get('channel', '').upper() == 'STABLE')
        url, checksum = paper['url'], paper['checksums']['sha256']
    except (urllib.error.URLError, StopIteration):
        builds = metadata(f'https://api.papermc.io/v2/projects/paper/versions/{mc}/builds')
        build = builds['builds'][-1]
        paper = build['downloads']['application']
        url = f'https://api.papermc.io/v2/projects/paper/versions/{mc}/builds/{build["build"]}/downloads/{paper["name"]}'
        checksum = paper['sha256']
    verified_download(url, Path('paper.jar'), 'sha256', checksum)
    query = urllib.parse.urlencode({'loaders': json.dumps(['bukkit', 'paper']), 'game_versions': json.dumps([mc])})
    svc = metadata('https://api.modrinth.com/v2/project/simple-voice-chat/version?' + query)[0]
    file = next(f for f in svc['files'] if f['primary'])
    Path('plugins').mkdir(exist_ok=True)
    verified_download(file['url'], Path('plugins/voicechat-bukkit.jar'), 'sha512', file['hashes']['sha512'])
    Path('svc-version.txt').write_text(svc['version_number'])
    print('Verified Paper SHA-256 and SVC SHA-512 before execution')


if __name__ == '__main__':
    main()
