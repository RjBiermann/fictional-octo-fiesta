# FINDINGS-1500 — the "failing" set was the harness firing reload into a live load

Issue: [#1500](https://github.com/RjBiermann/fictional-octo-fiesta/issues/1500) — `Load check
(load): [PerverZija WatchPorn]`, first failure of that kind. Run:
[45024201161](https://github.com/RjBiermann/fictional-octo-fiesta/actions/runs/45024201161),
2026-11-22, CloudStream prerelease **4.9.1-PRE**, upstream jar SHA `unknown` (live unpinned
`pre-release`). Evidence: the run's `load-check-logcat` artifact (4.6 MB logcat) and the
green run
[44906380824](https://github.com/RjBiermann/fictional-octo-fiesta/actions/runs/44906380824)
from 2026-11-21, same plugin set, same provider code.

Verdict: **not a provider defect.** ADR-0012's emulator tier is an instrument, and here the
instrument produced a plugin set no provider change can explain.

## §1 What the failing set actually looked like

Neither plugin failed. Neither plugin loaded. Both simply never reported:

```text
$ grep -c "Loaded plugin" logcat.log        # 27 expected, 2 rounds' worth of lines
35
$ cat loaded-plugins.txt | wc -l             # PerverZija.cs3 and WatchPorn.cs3 absent
25
$ grep -iE "Failed to load" logcat.log
                        (no lines)
$ grep -E "Adding (PerverZija|WatchPorn) .* (MainAPI|ExtractorApi)" logcat.log | tail
11-22 12:52:23.399 I/PluginInstance( 4391): Adding PerverZija (https://tube.perverzija.com) MainAPI
11-22 12:52:23.436 I/PluginInstance( 4391): Adding WatchPorn (https://watchporn.to) MainAPI
```

So both `load()` calls entered (they registered their `MainAPI`) and never returned: no
`Loaded plugin … successfully`, no `Failed to load …`. Upstream logs exactly one of those two
lines when `loadPlugin` returns (`PluginManager.kt`), so the two load coroutines were still
inside `load()` for the whole five-minute window — and the app process stayed alive
(`pid 4391` logs at 12:52:41 and 12:52:52).

Both providers are unchanged in this period and both compile clean (`compile.log`, 0
warnings); the artifacts are structurally identical to the 25 that loaded
(`manifest.json` + a ~343 kB `classes.dex`, same shape as `HQPorner.cs3`).

## §2 Two load rounds were running at once

`emulator-load.sh` launched the app (`monkey … LAUNCHER`), slept 8 s, and *then*
unconditionally fired upstream's hot-reload intent (`am start -d cloudstreamapp:`). The app
loads every local plugin by itself on startup, and the `.cs3` files were pushed before the
launch — so the run had two rounds:

| time | event (run 45024201161) |
|---|---|
| 12:52:12.78 | app launched |
| 12:52:15.61 | startup round starts: `Plugin update done!`, `Files in '…/plugins' folder: 27` |
| 12:52:15.8–18.3 | 12 plugins report `Loaded plugin … successfully`; the other 15 (incl. `PerverZija`, `WatchPorn`) are still registering extractors (277 `Adding … ExtractorApi` lines in that 4 s window) |
| 12:52:22.20 | harness fires the intent → `Reloading all local plugins!` |
| 12:52:22.31–22.54 | `unloadPlugin` for all 27 — including the two `.cs3` whose round-1 `load()` had **not returned** |
| 12:52:22.72 | round 2 starts, `Files in '…/plugins' folder: 27` again |
| 12:52:23.93 | 25 of 27 report; `PerverZija` and `WatchPorn` do not, and never do |

The green run a day earlier shows the same harness with a different race outcome: the startup
round had already completed (13:03:58 → 13:04:02, 27/27) before the intent landed at
13:04:04, so the check passed. Its reload round then stalled after the *first*
`Unloading plugin:` line and never loaded anything again — which is why 27/27 came from the
startup round alone there.

That is the whole differential: the same code passes when the intent lands after the startup
round, and loses whichever plugins are mid-`load()` when it lands during one.

## §3 Root cause

Upstream's `___DO_NOT_CALL_FROM_A_PLUGIN_hotReloadAllLocalPlugins` is a *hot reload for an app
that is already running* (`DeployWithAdbTask` pushes into the running app, then fires the
intent). Firing it into an in-flight startup load unloads plugin instances whose `load()` has
not returned — `unloadPlugin` removes the instance from `PluginManager.plugins` — and then
`loadPlugin` re-enters `load()` for the same `.cs3`. That the two `load()` calls then run
concurrently, one on an instance the app considers unloaded, is an inference from upstream's
source, not something this logcat shows: the log only shows `Adding … MainAPI` with no
matching verdict. The two registrations that were mid-flight (the last two in flight at
12:52:22) never came back; the other 25 did.

Nothing here is PerverZija- or WatchPorn-specific: they are simply the `.cs3` files whose
startup `load()` had not returned when the unload ran. Re-running the same harness would name
a different pair.

## §4 Fix (instrument, ADR-0012)

`emulator-load.sh` no longer fires the reload intent at all. The harness pushes the `.cs3`
files *before* it launches the app, on a freshly installed APK, so the app's own startup round
is the only round the check needs — and the "already running when the files land" case the
intent exists for cannot arise here. Firing it into a live startup round is the bug, not a
repair for it, and 2026-11-21's sibling evidence (its reload round stalled after a single
`Unloading plugin:` line) says the path it exercises is not one this tier wants by default.

A plugin that still never reports while others do remains a failing entry: a `load()` that
never returns is a real verdict, so the change does not excuse hangs — it only stops the
harness creating them. A round that reports **nothing at all** (no `Loaded plugin …` and no
`Failed to load …` line) names no provider, so the timed-out path reports it as
unattributable (`unattributable.txt`, #1357's infrastructure class) instead of listing every
expected plugin as failing.

Guards:

- `reported_count` counts verdict lines only, never a bare `Loading plugin: …` (every plugin
  prints one before it reports, so counting those would read a stalled round as progress).
- `no_verdicts` is the predicate the timed-out path decides on, and `test-parse.sh` pins the
  seam on all three inputs: an empty log and a log with round activity but no verdict read as
  no verdicts, a log with a verdict does not. Red without either helper
  (`reported_count: command not found`), so the cases are load-bearing rather than vacuous.
- Replayed offline against both captured logs: the 2026-11-22 startup round printed its first
  verdict at 12:52:18.051 — launch+5.4 s, round start+2.4 s — so the round speaks long before
  the registration wait's 300 s deadline, and at the moment the red run fired the intent
  (12:52:21.9) the log already held 12 verdicts (the 35 in the finished 5-minute logfile are a
  whole-run count, not what the gate would have seen at its first poll); the 2026-11-21 log
  holds 27. Both are `no_verdicts`-false, so neither would have been reloaded. The live
  confirmation is the next scheduled load-check run.

## §5 Not fixed here

- The reload path itself is upstream's, and this tier no longer exercises it. The 2026-11-21
  run's reload round stalled after one unload, so the path is unhealthy in this app build;
  nothing here fixed it, and nothing here needs it (see §4).
- `jar-sha.txt` in the same run is empty, and the failure issue therefore says `Upstream jar
  SHA: unknown`. The jar itself was fetched (`bootstrapCloudstream: …/classes.jar ->
  ~/.m2/repository/com/lagradost/cloudstream3/pre-release/cloudstream3-pre-release.jar`), but
  the workflow's evidence step looks for `classes*.jar`, finds nothing, and
  `sha256sum '' | cut` swallows the failure inside the pipe — so the step stays green with an
  empty value. Filed as its own backlog item; it does not affect this verdict.
