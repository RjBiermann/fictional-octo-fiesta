# Pipeline-run emulator load check; ADR-0001's in-app clause superseded

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

- **Load check** — registration only: install the prerelease app, push every `.cs3`, fire the
  reload intent, assert `PluginManager: Loaded plugin X successfully` per plugin.
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
