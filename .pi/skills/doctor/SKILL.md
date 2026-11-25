---
name: doctor
description: Diagnose workspace and toolchain failures and make the fix durable — separate a missing tool from a misconfigured one, put permanent installs in the image rather than the container layer, verify in a clean shell. Use when a tool, MCP server, Playwright, emulator or SDK fails to start, after a rebuild, or when a fix "worked" but may not survive one.
---

# Doctor

## Steps
0. `free -h` first; state `available`.
1. Missing vs misconfigured: `command -v`, `<tool> --version`, `<tool> --help`, and whatever points
   at it (`~/.pi-agent/mcp.json`, `~/.pi-agent/settings.json`, the launch wrapper).
2. A permanent fix belongs where the environment is built, not in the live container: the workspace
   image definition (devcontainer features / `base_packages`, outside this repo) or an in-repo CI
   step (`.github/workflows/`, `.pi/ci/`). Apt installs and symlinks under `/opt` or
   `/usr/local/lib` vanish on the next rebuild. Name the target file in your report and hand the
   edit to the maintainer instead of patching an environment you cannot see from here.
3. Configure, don't install: point an existing binary at an existing path (`--executable-path`, a
   config entry) before adding packages.
4. Re-verify in a clean shell (`bash -lc`, restarted server) — a fix that only lives in this
   process is not a fix.
5. Report: root cause, durability verdict (survives rebuild / does not), next step.

## Traps seen
- MCP/Playwright resolves Chrome channels (`chrome`, `chromium-<build>`) before it looks at your
  config — check the resolved channel, not just the config.
- An edited launch config does not affect an already-running server; restart it.
