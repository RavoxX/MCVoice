@AGENTS.md

## Notes for Claude sessions

* The public backend runs on a shared production server (mail, other sites):
  read the "Public backend" bullet in AGENTS.md before touching it. SSH works
  with the local key; never use passwords.
* Pushing to `main` triggers CI; releases and deploys happen only when the
  user asks. `gh` is not installed: use the GitHub REST API (see AGENTS.md).
* Local toolchains that may be missing: Go and JDK 25 can be unpacked into
  the session scratchpad (official go.dev / Adoptium archives); the vector
  generator needs Python `cryptography` (use a venv).
