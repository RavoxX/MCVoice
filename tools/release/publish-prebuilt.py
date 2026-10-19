#!/usr/bin/env python3
"""Publish prebuilt jars without executing their Minecraft/loader build scripts."""
import json
import base64
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import urllib.error
import urllib.request

BUILD = '''plugins { id 'maven-publish' }
publishing {
 publications { mod(MavenPublication) {
  groupId = 'io.github.ravoxx.mcvoice'
  artifactId = 'voice-client-' + System.getenv('LOADER')
  version = System.getenv('VERSION_TAG')
  artifact('mod.jar')
  artifact('sources.jar') { classifier = 'sources' }
 } }
 repositories { maven {
  url = uri('https://maven.pkg.github.com/' + System.getenv('GITHUB_REPOSITORY'))
  credentials { username = System.getenv('GITHUB_ACTOR'); password = System.getenv('GITHUB_TOKEN') }
 } }
}
'''


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def verify_maven(artifact, version, file):
    url = f'https://maven.pkg.github.com/{os.environ["GITHUB_REPOSITORY"]}/io/github/ravoxx/mcvoice/{artifact}/{version}/{artifact}-{version}.jar'
    credentials = (os.environ['GITHUB_ACTOR'] + ':' + os.environ['GITHUB_TOKEN']).encode()
    req = urllib.request.Request(url, headers={'Authorization': 'Basic ' + base64.b64encode(credentials).decode()})
    try:
        response = urllib.request.build_opener(NoRedirect()).open(req, timeout=60)
    except urllib.error.HTTPError as error:
        if error.code != 302:
            raise
        # Never forward the package credential to signed artifact storage.
        response = urllib.request.urlopen(error.headers['Location'], timeout=60)
    with response:
        published = response.read(file.stat().st_size + 1)
    if published != file.read_bytes():
        raise ValueError('published Maven jar differs from validated build')
    return hashlib.sha256(published).hexdigest()


def main():
    version, mc = os.environ['VERSION'], os.environ['MC']
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-[A-Za-z0-9.]+)?', version) or not re.fullmatch(r'\d+(?:\.\d+){1,2}', mc):
        raise ValueError('invalid publication version')
    for file in Path('status').glob('*.json'):
        row = json.loads(file.read_text())
        loader = row['loader']
        if row['status'] != 'pass':
            continue
        if loader not in ('fabric', 'forge', 'legacyfabric'):
            raise ValueError('invalid loader')
        jar = f'mcvoice-{version}-mc{mc}-{loader}.jar'
        if row['jar'] != jar:
            raise ValueError('unexpected production artifact name')
        work = Path('publication') / loader
        work.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(Path('maven-inputs') / jar, work / 'mod.jar')
        shutil.copyfile(Path('maven-inputs') / f'{loader}-sources.jar', work / 'sources.jar')
        (work / 'settings.gradle').write_text("rootProject.name = 'mcvoice-publication'\n")
        (work / 'build.gradle').write_text(BUILD)
        env = dict(os.environ, LOADER=loader, VERSION_TAG=f'{version}+mc{mc}')
        log = Path('status') / f'publish-{loader}.log'
        result = 'failed: publication not attempted'
        with log.open('w') as out:
            for attempt in range(2):
                run = subprocess.run(['gradle', '-p', str(work), 'publish', '--no-daemon'], env=env, stdout=out, stderr=subprocess.STDOUT)
                out.flush()
                if run.returncode == 0:
                    result = 'published'
                    break
                if 'Received status code 409' in log.read_text():
                    result = 'exists: immutable Maven version already published; not replaced'
                    break
                result = f'failed: Gradle publication exit {run.returncode}; see publish-{loader}.log'
        if result == 'published' or result.startswith('exists:'):
            try:
                row['maven_sha256'] = verify_maven('voice-client-' + loader, env['VERSION_TAG'], work / 'mod.jar')
            except (OSError, ValueError) as error:
                result = f'failed: Maven read-back verification: {type(error).__name__}'
        row['maven'] = result
        file.write_text(json.dumps(row))
        print(loader, result)


if __name__ == '__main__':
    main()
