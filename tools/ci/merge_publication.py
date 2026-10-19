#!/usr/bin/env python3
"""Retain build truth even when the isolated publication job fails early."""
import json
from pathlib import Path
import sys


def merge(builds, publications, result):
    for file in builds.glob('*.json'):
        row = json.loads(file.read_text())
        if row.get('status') != 'pass':
            continue
        candidate = publications / file.name
        if candidate.is_file() and not candidate.is_symlink():
            published = json.loads(candidate.read_text())
            if all(published.get(k) == row.get(k) for k in ('minecraft', 'loader', 'jar', 'commit', 'status')):
                for key in ('maven', 'maven_sha256'):
                    if key in published:
                        row[key] = published[key]
        if 'maven' not in row:
            row['maven'] = 'failed: publication job ' + result + '; no publication result recorded'
        file.write_text(json.dumps(row))
    if publications.is_dir():
        for file in publications.glob('publish-*.log'):
            if file.is_file() and not file.is_symlink():
                (builds / file.name).write_bytes(file.read_bytes()[-400000:])


if __name__ == '__main__':
    merge(Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3])
