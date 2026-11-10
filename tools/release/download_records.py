"""Download the newest status/result artifact per name, including across reruns."""
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import sys
import urllib.error
import urllib.request
import zipfile


def newest(artifacts):
    selected = {}
    for artifact in artifacts:
        name = artifact["name"]
        if not re.fullmatch(r"mc-[0-9.]+-status|release-result-[0-9.]+", name):
            continue
        previous = selected.get(name)
        if previous is None or (artifact["created_at"], artifact["id"]) > (previous["created_at"], previous["id"]):
            selected[name] = artifact
    return selected


def unpack_records(data, destination):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        entries = archive.infolist()
        for entry in entries:
            path = PurePosixPath(entry.filename)
            if path.is_absolute() or len(path.parts) != 1 or ".." in path.parts or "\\" in entry.filename:
                raise ValueError("unsafe artifact path")
        destination.mkdir(parents=True, exist_ok=False)
        for entry in entries:
            if entry.is_dir():
                continue
            name = entry.filename
            if not re.fullmatch(r"(?:[a-z0-9.-]+\.json|expected-loaders\.txt)", name):
                continue
            if entry.file_size > 1024 * 1024:
                raise ValueError("oversized release record")
            target = destination / name
            with target.open("xb") as output:
                output.write(archive.read(entry))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def api(path, binary=False):
    request = urllib.request.Request("https://api.github.com/repos/" + os.environ["GH_REPO"] + "/" + path,
        headers={"Authorization": "Bearer " + os.environ["GH_TOKEN"], "Accept": "application/vnd.github+json", "User-Agent": "MCVoice-release-records"})
    try:
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=60) as response:
            data = response.read()
    except urllib.error.HTTPError as error:
        if not binary or error.code != 302 or not error.headers["Location"].startswith("https://"):
            raise
        # Signed artifact storage never receives the GitHub credential.
        with urllib.request.urlopen(error.headers["Location"], timeout=60) as response:
            data = response.read()
    return data if binary else json.loads(data)


def main():
    run, output = sys.argv[1:3]
    if not run.isdigit() or not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", os.environ["GH_REPO"]):
        raise ValueError("invalid repository/run")
    artifacts = []
    page = 1
    while True:
        batch = api(f"actions/runs/{run}/artifacts?per_page=100&page={page}")["artifacts"]
        artifacts.extend(batch)
        if len(batch) < 100:
            break
        page += 1
    selected = newest(artifacts)
    if not selected:
        raise ValueError("no release records")
    root = Path(output)
    for name, artifact in sorted(selected.items()):
        if artifact["expired"] or artifact["workflow_run"]["id"] != int(run) or artifact["workflow_run"]["head_sha"] != os.environ["SOURCE_SHA"]:
            raise ValueError("expired or wrong-source release artifact")
        unpack_records(api(f'actions/artifacts/{artifact["id"]}/zip', binary=True), root / name)
        print(name, artifact["id"], artifact["created_at"], flush=True)
    (root / "artifact-selection.json").write_text(json.dumps(selected, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
