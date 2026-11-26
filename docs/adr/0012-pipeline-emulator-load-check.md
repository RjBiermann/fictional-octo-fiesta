# Pipeline-run emulator load check; ADR-0001's in-app clause superseded

> Amended 2026-11-26 (#1608): the stall stays unattributable — no `ai-fix`, no implementation
> dispatch — but its names now reach the filed record. The issue keeps the class token as its
> title, so the lane keeps one human issue for the class, while its body and every daily evidence
> comment list the plugins with no verdict. A stall that names *every* expected plugin is the
> whole-round silence of the 11-22 amendment (the app never reported), so it keeps the class
> placeholder instead — listing all 27 would read as a failing set. A stall recorded without names
> cannot show a repeat: `FreePornVideos` stalled on 2026-11-25 and 11-26 (26/27 loaded) and the
> record said only `unattributable`.

> Amended 2026-11-23 (#1545): the reload intent stays off, but it was not the only way a plugin
> loses its verdict — run 45202927338 reported three plugins (`Javbangers`, `MissAV`, `ixiporn`)
> while 24/27 reported, with no exception, no crash and no reload intent in the run. Their
> `load()` calls were still inside the shared `registerHostExtractors()` table when the window
> ended: the round's `Adding … ExtractorApi` counts step down three times at three different
> rows, and the three steps sum to exactly the lines that are missing (177), so nothing was
> lost — three registrations stopped (`docs/archive/findings/FINDINGS-1545.md`). Two rules
> follow. The round now settles on verdict *coverage* rather than on "all loaded", and only a
> verdict line the log prints (`Loaded plugin … successfully`, `Failed to load …`) may name a
> provider: a plugin with no verdict is reported as **stalled** — unattributable, a human
> decision, never an `ai-fix` dispatch — with a best-effort `kill -3` thread dump so the parked
> frame is visible next time. The capture also sets 64 MiB log buffers and parses the union of
> the live stream and a final `adb logcat -d` dump, since the round's ~370 KiB burst already
> exceeds logd's 256 KiB default ring. The assertion itself is unchanged.

> Amended 2026-11-22 (#1500): the load tier no longer fires the reload intent at all.
> The app loads every local plugin by itself on startup and the `.cs3` files are pushed
> *before* the launch, so the app's startup round is the round the tier asserts; the
> "already running when the files land" case upstream's intent exists for cannot occur in
> a harness that starts the app itself. Firing it into an in-flight startup load unloads
> plugin instances whose `load()` has not returned and re-enters `load()` for the same
> file — run 45024201161 lost exactly the two plugins that were mid-load (`PerverZija`,
> `WatchPorn`) that way and reported a failing set no provider code explains
> (`docs/archive/findings/FINDINGS-1500.md`). A round that reports no verdict at all names
> no provider, so the tier reports it as unattributable (#1357's infrastructure class)
> instead of listing every expected plugin as failing. The assertion itself is unchanged.

Partially supersedes ADR-0001. Compiling against the unpinned `pre-release` classes.jar
(bootstrapCloudstream, cold CI runs) proves API compatibility but nothing proves a plugin
actually registers and loads inside the real CloudStream app — the one gap neither builds,
Parse tests, nor live-site Verification close. We decided a scheduled, fleet-wide
**load check** runs on a GitHub-hosted emulator (KVM on ubuntu runners): all plugins loaded
in one boot, asserted at logcat/`uiautomator` level only. Broader in-app UX testing stays
maintainer-only. The load check is never **Geo-gated** evidence: it shares the runner's IP, so
it inherits the same regional blind spot as any scraper. Per-issue emulator
verification through `pipeline.verify` is parked until the fleet load check has run history;
when wired, it only runs on rounds that touched plugin code.

The lane carries **two tiers** today (2026-10-27):

- **Load check** — registration only: install the prerelease app, push every `.cs3`, let the
  app's own startup load register them (no reload intent — see the 2026-11-22 amendment),
  assert `PluginManager: Loaded plugin X successfully` per plugin.
- **Player probe** — site-free: a generated 2 s H.264/TS fixture served on the runner loopback
  (`10.0.2.2` from the emulator) is opened through the app's `cloudstreamplayer://` deep link and
  must report `Rendered first frame`. This is the only tier that observes the app's player
  datasource, where a `data:` source dies as `net::ERR_UNKNOWN_URL_SCHEME` → ExoPlayer 2001.

A third tier — a **Playback scenario** (one provider's real video, dispatched) — is designed and
in review; it does not ship here because the hosted runner's software renderer still kills the
emulator mid-run, and until an emulator death is classified as infrastructure rather than a
provider failure a red scenario would misattribute the cause. Its rules, when it lands: required
evidence for a playback-symptom `ai-fix` and for a Chronic provider before a removal decision,
never a per-PR gate; harness code owned by the agent lane; ≤5 providers per dispatched run.

Decisions that follow:

- **Budget.** Job ceiling 90 min, above the sum of the step ceilings (setup 20, load check 25,
  probe 6, digest/upload), so a slow run trips a step and still uploads diagnostics, digest and
  artifact. The nightly runs tiers 1–2 only (zero AI).
- **GPU mode.** `-gpu auto`: on a hosted runner the emulator resolves it to `swangle` (software
  ANGLE). `swiftshader_indirect` aborts the emulator on the first rendered frame (empty hierarchy
  *and* empty screencap, clean dmesg); `angle_indirect` is not a valid option for this build
  (it logs "switching to 'auto'").
- **Run history.** Until 2026-10-27 the lane had never reached the emulator: the job had no
  Android SDK, no KVM access, no token for the APK lookup, and a hardcoded stable package name
  while the APK installs as `…cloudstream3.prerelease`. The parked per-issue emulator
  verification above stays parked, but on a lane that now actually runs.

Considered alternatives: full UI flow testing per provider in-app (rejected — duplicates
Verification's job through a flakier UI harness); loading the emulator from `main` with no
schedule (rejected — the check must also catch upstream jar refreshes that never touch our
commits); citing emulator passes for Geo-gated sites (rejected — same-IP, no added signal);
playing a provider's real stream on the nightly (rejected — site reachability, datacenter-IP
walls and minutes per provider).

Token precision: the scheduled check runs with zero AI — bootstrap, compile, emulator,
logcat grep are pure bash/Gradle/adb, no LLM step. AI spend happens only on failure fixes:
failures dedupe onto one open issue per failing plugin set (daily runs append evidence,
they never open a fresh issue per day), and fix runs ride devloop's existing caps
(`max_per_day`, timeouts) with no new knobs. A failure issue carries the failing plugin
list, logcat tail, app version, and upstream jar SHA so the fix run starts from evidence
and never re-probes.
