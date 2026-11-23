# FINDINGS-1545 — three plugins' `load()` stalled inside the shared registry

Issue: [#1545](https://github.com/RjBiermann/fictional-octo-fiesta/issues/1545) — `Load check
(load): [Javbangers MissAV ixiporn]`. Run:
[45202927338](https://github.com/RjBiermann/fictional-octo-fiesta/actions/runs/45202927338),
2026-11-23, CloudStream prerelease **4.9.1-PRE**, upstream jar SHA `unknown` (live unpinned
`pre-release`). Evidence: that run's `load-check-logcat` artifact (6.0 MB logcat) compared with
the green run
[44906380824](https://github.com/RjBiermann/fictional-octo-fiesta/actions/runs/44906380824)
from 2026-11-21 (`docs/archive/findings/FINDINGS-1500.md` covers the reload-era failure
[45024201161](https://github.com/RjBiermann/fictional-octo-fiesta/actions/runs/45024201161),
a different mechanism that is already fixed).

Verdict: **not a provider defect.** The three plugins were still inside `load()`, in the shared
`registerHostExtractors()` table, when the five-minute window ended — it was not a reload, not a
crash, and not log loss. The tier turned "no verdict" into a failing set, which is the instrument
bug this issue fixes.

## §1 The failing set had no verdict of any kind

```text
$ grep -c "Loaded plugin .* successfully" logcat.log      # 27 expected
24
$ grep -c "PluginManager.*Failed to load" logcat.log
0
$ grep -c "FATAL EXCEPTION" logcat.log                    # app alive throughout, pid 4121
0
$ grep -E "Adding (Javbangers|MissAV|ixiporn) .* MainAPI" logcat.log
11-23 15:20:07.758 I/PluginInstance( 4121): Adding ixiporn (https://ixiporn.org) MainAPI
11-23 15:20:07.823 I/PluginInstance( 4121): Adding Javbangers (https://www.javbangers.com) MainAPI
11-23 15:20:08.050 I/PluginInstance( 4121): Adding MissAV (https://missav.live) MainAPI
```

All three entered `load()` and registered their `MainAPI`. Upstream prints exactly one verdict
per plugin when `loadPlugin` returns (`PluginManager.kt`), so those three `load()` calls never
returned. Every provider in this repo's `load()` is the same two lines — `registerMainAPI(...)`
then `registerHostExtractors()` — so the three were stuck in the **shared** registry, and §2
places each of them in it.

The victims differ on every run (`PerverZija`/`WatchPorn` on 11-22, these three on 11-23, none on
11-21) and the three providers are structurally identical to the 24 that reported (same
manifest + dex shape, unchanged code, clean compile). Provider code is not the differentiator.

## §2 Where they stopped, from the registry line counts

`registerHostExtractors()` instantiates the whole shared table row by row and logs
`Adding <name> (<url>) ExtractorApi` for each. The table had 152 rows at that commit
(`shared/src/main/kotlin/com/kraptor/HostRegistry.kt`), and every plugin that finishes registers
all of them, so the arithmetic is exact:

| run | shared rows | expected lines | logged |
|---|---|---|---|
| 11-21 green | 138 | 27 × 138 = 3 726 (+1 provider-local) | 3 727 |
| 11-23 red | 152 | 27 × 152 = 4 104 (+1 provider-local) | 3 927 (+1) |

Counted per registry row, the red run is:

```text
 9 rows logged 27×   112 rows logged 26×   28 rows logged 25×   3 rows logged 24×
 missing: 9×0 + 112×1 + 28×2 + 3×3 = 177   ← exactly the 4 104 − 3 927 deficit
```

A `load()` that stops cannot skip a row and continue: stopping at row R removes exactly one
instance from every row at or after R. That signature is three stops, at rows ~10, ~122 and
~150 (152 rows total, `27 → 26` after the first stop, `26 → 25` after the second, `25 → 24`
after the third), and the step pattern's sum matches the deficit line for line. The green run's
counts are the control: all 138 rows at exactly 27, no steps.

This also rules out the alternative reading — a lossy `adb logcat` — which would scatter counts
(a row at 25 next to a row at 27) instead of stepping them, and which the green run shows does
not happen at this burst rate (3 727 lines, none lost).

The three stops are at three *different* rows, so this is not one poisoned registry row or one
class's static initializer: it is a stall while instantiating extractor classes, under 27
concurrent plugin loads each initializing the whole 152-row table through its own
`PathClassLoader`. `load()` runs on the plugin-load coroutines, not the main thread (the app kept
serving its activity, relaunching `MainActivity` at 15:20:24.672), so nothing app-side breaks the
stall — and the process showed no OOM, GC storm or ANR in the buffer.

## §3 Why waiting could not resolve it

The harness waits 300 s, and the app does run another round after that activity relaunch
(`___DO_NOT_CALL_FROM_A_PLUGIN_loadAllLocalPlugins` at 15:20:24.748) — but it is a no-op for a
file already in `PluginManager.plugins`:

```text
11-23 15:20:24.969 I/PluginManager( 4121): Plugin with name Javbangers already exists
11-23 15:20:25.035 I/PluginManager( 4121): Plugin with name ixiporn already exists
11-23 15:20:25.062 I/PluginManager( 4121): Plugin with name MissAV already exists
```

`plugins` is keyed by file path and filled *before* `load()` is called, so that skip covers a
finished load and an in-flight one alike — all 27 files printed `already exists` in that round.
Nothing in the app re-reports a plugin it already holds: a missing verdict is permanent for the
launch, and a second round cannot repair it.

## §4 Fix (instrument, ADR-0012)

1. **Silence is never a provider verdict.** Attribution now comes only from verdict lines the log
   prints (`Loaded plugin … successfully`, or a `Failed to load …` that names the file). A plugin
   with no verdict is written to `stalled-plugins.txt` and reported as unattributable
   (`unattributable.txt`, #1357's class) — no `ai-fix`, no dispatched implementation run — because
   from the log alone a dropped line, a stalled `load()` and a plugin the app refused are not
   distinguishable. This is the same rule #1500 applied to a *whole*-round silence, extended to a
   partial one; the old "expected minus loaded" loop is gone.
2. **The round settles on verdict coverage, not on "all loaded".** The wait loop breaks as soon as
   every expected plugin has a verdict, so a genuine failure is reported when it happens instead
   of after a 300 s timeout, and a stalled plugin is the only thing that can hold the loop open.
3. **A stall is made diagnosable.** On the stalled path the tier takes a best-effort `kill -3`
   thread dump (`thread-dump.log`, appended to the logcat tail): the next occurrence shows the
   exact frame the three `load()` calls are parked in, which is what this evidence could not give.
4. **The verdicts no longer depend on a lagging reader.** The round emits ~4 000 logcat lines in
   ~1.5 s (~370 KiB), which is more than logd's default 256 KiB per buffer, and `adb logcat`
   reads through the adb bridge while the guest is busy. That is not what went wrong here (§2),
   but it is a second, silent way to lose a verdict, so the capture sets 64 MiB buffers
   (`adb logcat -G 64M`, sizes recorded in `logcat-buffer-sizes.txt`) and parses verdicts from
   the union of the live stream and a final `adb logcat -d` dump (`verdicts.log`).
5. **`extract_failed` reads upstream's catch-all.** A `load()` that throws is logged as
   `Failed to load <absolute path>: <stack>`, not the manifest branch's
   `Failed to load plugin  X.cs3: <reason>`; only the latter was parsed, which is why a real
   provider failure could previously only be inferred from silence.

Guards: `test-parse.sh` pins `extract_failed` (both shapes), `unsettled` (one plugin loaded, one
named-failed, one silent → only the silent one is unsettled) and `crashed` (`Process: <pkg>, PID:`
identifies this app's death). Red before the parser change (`FAIL: named failures must yield
plugin stems`), green after.

Replayed offline against the captured artifacts with the new helpers: the 11-23 log yields 24
loaded, 0 named failures, 3 unsettled → unattributable (human), no provider set; the 11-21 log
yields 27 loaded, 0 unsettled → green. The live confirmation is the next scheduled run.

## §5 Not fixed here

- The stall itself is inside the app/ART: 27 concurrent plugin loads each initializing a 152-row
  extractor table through their own class loaders. Nothing in this repo can serialize or shrink
  that (ADR-0002 makes every plugin register the whole shared table), and the thread dump added
  above is what will identify the parked frame on the next occurrence.
- Upstream's `already exists` skip means a missed verdict is never re-reported in the same
  launch. Re-deriving verdicts from the app's own plugin store instead of a log line would need
  `adb root` reads of app-private prefs or a debuggable build; not attempted here.
- `jar-sha.txt` is empty in this run (`Upstream jar SHA: unknown`) — filed separately as #1501,
  unrelated to this verdict.
