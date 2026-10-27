# Pipeline-run emulator load check; ADR-0001's in-app clause superseded

Partially supersedes ADR-0001. Compiling against the unpinned `pre-release` classes.jar
(bootstrapCloudstream, cold CI runs) proves API compatibility but nothing proves a plugin
actually registers and loads inside the real CloudStream app — the one gap neither builds,
Parse tests, nor live-site Verification close. We decided a scheduled, fleet-wide
**load check** runs on a GitHub-hosted emulator (KVM on ubuntu runners): all plugins loaded
in one boot, asserted at logcat/`uiautomator` level only. Full in-app UX/stream testing
stays maintainer-only, and the load check is never **Geo-gated** evidence — it shares the
runner's IP, so it inherits the same regional blind spot as any scraper. Per-issue emulator
verification through `pipeline.verify` is parked until the fleet load check has run history;
when wired, it only runs on rounds that touched plugin code.

Considered alternatives: full UI flow testing per provider in-app (rejected — duplicates
Verification's job through a flakier UI harness); loading the emulator from `main` with no
schedule (rejected — the check must also catch upstream jar refreshes that never touch our
commits); citing emulator passes for Geo-gated sites (rejected — same-IP, no added signal).

Token precision: the scheduled check runs with zero AI — bootstrap, compile, emulator,
logcat grep are pure bash/Gradle/adb, no LLM step. AI spend happens only on failure fixes:
failures dedupe onto one open issue per failing plugin set (daily runs append evidence,
they never open a fresh issue per day), and fix runs ride devloop's existing caps
(`max_per_day`, timeouts) with no new knobs. A failure issue carries the failing plugin
list, logcat tail, app version, and upstream jar SHA so the fix run starts from evidence
and never re-probes.

Attribution guard (#1362): a plugin set is only reportable when the app actually examined
it — its own listing of the pushed plugin folder is non-empty, or at least one plugin is
named as loaded/failed. A run where the folder counts 0 files is an **instrument** fault
(missing all-files grant, wrong package, wrong push path) and files a single
`instrument-fault` sentinel instead of the provider set, keeping the dedup key stable. The
measured failure is why: 26 `adb push`es succeeded, the app logged `folder: 0`, and
expected-minus-loaded named all 26 providers — an ai-fix issue fired at code that was never
loaded. A second defect in the same run, downstream of the grant, was ours too: the success
line is `I/PluginManager( pid): Loaded plugin <file>.cs3 successfully`, so a regex requiring
a `PluginManager: ` message prefix saw a fully-loaded fleet as zero and reported it failing.
Instrument evidence is never provider evidence, in either direction.
