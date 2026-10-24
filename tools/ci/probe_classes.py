#!/usr/bin/env python3
"""Validate every javap argument and extract inspected archives without path escapes."""
from pathlib import Path, PurePosixPath
import os
import re
import shutil
import stat
import subprocess
import sys
import unicodedata
import zipfile


def identifier(value):
    if not value:
        return False
    def start(char):
        return char == '$' or unicodedata.category(char).startswith('L') or unicodedata.category(char) in ('Nl', 'Sc', 'Pc')
    return start(value[0]) and all(start(c) or unicodedata.category(c) in ('Mn', 'Mc', 'Nd') for c in value[1:])


def valid_class(value):
    return bool(value) and all(identifier(part) for part in value.split('.'))


def validate(name, access):
    if not valid_class(name):
        raise ValueError('invalid Java binary class name: ' + repr(name))
    if access not in ('public', 'protected'):
        raise ValueError('access must be public or protected')


def javap(classpath, name, access, lines=3000):
    validate(name, access)
    result = subprocess.run(['javap', '-cp', str(classpath), '-'+access, name],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors='replace')
    return '\n'.join(result.stdout.splitlines()[:lines])


def extract(archive, destination):
    destination = Path(destination).resolve()
    destination.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as source:
        for entry in source.infolist():
            path = PurePosixPath(entry.filename)
            if (path.is_absolute() or '..' in path.parts or '\\' in entry.orig_filename
                    or re.match(r'^[A-Za-z]:', entry.filename) or stat.S_ISLNK(entry.external_attr >> 16)):
                raise ValueError('unsafe archive entry: ' + repr(entry.filename))
            target = destination.joinpath(*path.parts).resolve()
            if not target.is_relative_to(destination):
                raise ValueError('archive entry escaped extraction root')
            # JVM metadata/non-Java names never become javap arguments or native filenames.
            if path.suffix == '.class' and not identifier(path.stem):
                print('Skipped invalid class entry: ' + repr(entry.filename))
                continue
            if entry.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with source.open(entry) as data, target.open('wb') as output:
                    shutil.copyfileobj(data, output)


def archive_classes(directory, pattern):
    directory = Path(directory).resolve()
    matcher = re.compile(pattern)
    selected = {}
    for outer in sorted(directory.glob('x[0-9]*')):
        if not outer.is_dir() or outer.is_symlink():
            continue
        for file in sorted(outer.rglob('*.class')):
            if file.is_symlink() or any(p.is_symlink() for p in file.parents if p != directory):
                continue
            root = outer
            for parent in reversed(file.relative_to(outer).parents):
                if parent != Path('.') and parent.name.endswith('_x'):
                    root = outer / parent
            name = '.'.join(file.relative_to(root).with_suffix('').parts)
            if valid_class(name) and matcher.search(name):
                selected.setdefault(name, root)
    return sorted(selected.items())[:150]


def main():
    mode = sys.argv[1]
    if mode == 'validate':
        access = os.environ.get('ACCESS', 'public')
        names = os.environ.get('CLASSES', '').split()
        for name in names:
            validate(name, access)
        if access not in ('public', 'protected'):
            raise ValueError('invalid access')
        print('\n'.join(names))
    elif mode == 'javap':
        print(javap(sys.argv[2], sys.argv[3], sys.argv[4]))
    elif mode == 'extract':
        extract(sys.argv[2], sys.argv[3])
    elif mode == 'archive':
        for name, root in archive_classes(Path.cwd(), os.environ.get('FILTER', '')):
            print('::group::'+name)
            print(javap(str(root)+os.pathsep+'../client.jar', name, 'public', 120))
            print('::endgroup::')
    else:
        raise ValueError('unknown probe operation')


if __name__ == '__main__':
    main()
