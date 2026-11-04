# FINDINGS-1328 — PerverZija XSFTK chain evidence + device-side diagnostics (S1 of #1328)

Run: 2026-10-25 (`05-implement`, story S1 / #1336; parent #1328, epic "PerverZija playback
hardening (recurrence #3)"). Re-probed live **2026-10-25 13:47–13:53Z**. This story makes
**no behaviour change**: emitted playlists are byte-identical, and the only code change is
`Log.d` diagnostics on the real emission path (`PerverZijaExtractor.emitXsftk`). Provider
`version` is not hand-edited (the #921/#1334 PR-time bumper owns it).

Site / reported URL: `https://tube.perverzija.com/evolvedfightslez-jazmine-cruz-vs-nadia-white/`
(owner answer in #1328 round 1). Probe method: plain HTTP from the CI runner, mobile UA, no JS
(ADR-0008), referer `https://tube.perverzija.com/` on the player page.

**Re-probe result: green end-to-end, unchanged from the round-2 probe.** Every byte count,
quality/segment count and the 45-host roster reproduced. The only moved value is the boot
token, which is epoch-bound and was refreshed (`f86cc1fe4cf6ab1482bb038e.1792904400` →
`990f24577c695f4db84aba8e.1792944000`); the video id did not change.

## §1 Probe transcript

```text
probe date: 2026-10-25 13:47–13:53Z  (round-2 probe: 2026-10-25 ~02:1xZ)

GET video page                                  → 200, 369 361 B
iframe  pervl6.xtremestream.xyz/player/index.php?data=55ed523b2ca4371a31d7dbd7a2877bea
GET player page (referer tube.perverzija.com/)  → 200, 3 177 B
                                                  XSFTK ×2, ftk_engine, ftk/xsad, videojs ×8
                                                  no m3u8_loader_url, no xs1.php
boot    XSFTK.boot("55ed523b2ca4371a31d7dbd7a2877bea",
                   "990f24577c695f4db84aba8e.1792944000", player)
op=manifest                                     → 200, application/octet-stream, 11 062 B
XOR SHA-256("M|<id>|<token>")                   → qualities 1080 (394 segs), 720 (381), 480 (372)
                                                → cdn roster 45 hosts, 2 families
                                                  (pervl6.xspcdnNN / pervl6.xscasacdnNN), all *.click
op=key  (X-XS-P: 1)                             → 200, 16 B
                                                  403 without the header ("forbidden-hdr")
                                                  403 with X-XS-P: 0
                                                  200 without referer (with X-XS-P: 1)
segment .../720_enc/7200.html from 45/45 hosts  → 200, 1 680 352 B
                                                  sha256 c17ea1e3… identical on all 45
segment .../1080_enc/10800.html from 45/45 hosts → 200, 3 352 608 B
                                                  sha256 9252ebf3… identical on all 45
Range: bytes=0-1023 (720)                       → 206, 1 024 B
                                                  Content-Range: bytes 0-1023/1680352
AES-128-CBC decrypt, IV = BE(0)                 → clean MPEG-TS, 100 % 0x47 sync
                                                  17833 / 8938 / 4851 beats (1080 / 720 / 480)
```

Synthesized playlist sizes from this probe (current `cdn[0]`, current token — the values the
new `source … bytes=` diagnostics report; raw here, base64 is what ships in the `data:` URI):

| quality | EXTINF lines | raw bytes | base64 bytes |
|---|---|---|---|
| 1080 | 394 | 44 052 | 58 736 |
| 720 | 381 | 41 920 | 55 896 |
| 480 | 372 | 40 933 | 54 580 |

### Round-2 column (numbers preserved; this re-probe did not overwrite them)

| hop | round-2 probe (2026-10-25 ~02:1xZ) | this probe (2026-10-25 13:47–13:53Z) |
|---|---|---|
| video page | 200, 369 361 B | 200, 369 361 B |
| player page | 200, 3 177 B | 200, 3 177 B |
| boot id | `55ed523b2ca4371a31d7dbd7a2877bea` | same |
| boot token | `f86cc1fe4cf6ab1482bb038e.1792904400` | `990f24577c695f4db84aba8e.1792944000` (refreshed) |
| `op=manifest` | 200, 11 062 B | 200, 11 062 B |
| qualities | 1080/394, 720/381, 480/372 | same |
| CDN roster | 45 hosts, 2 families | same (verbatim in §2) |
| `op=key` | 200/16 B with `X-XS-P: 1`; 403 without | same, plus 403 on `X-XS-P: 0`; 200 without referer |
| segment | 720, 200, identical size across 45/45 | 720 + 1080, 200, identical bytes across 45/45 |
| Range | 206 | 206, 1 024 B |
| decrypt | clean MPEG-TS, 100 % sync | same beat counts |

## §2 CDN roster (verbatim, manifest order)

```text
pervl6.xspcdn01.click
pervl6.xspcdn02.click
pervl6.xspcdn03.click
pervl6.xspcdn04.click
pervl6.xspcdn05.click
pervl6.xspcdn06.click
pervl6.xspcdn07.click
pervl6.xspcdn08.click
pervl6.xspcdn09.click
pervl6.xspcdn10.click
pervl6.xscasacdn01.click
pervl6.xscasacdn02.click
pervl6.xscasacdn03.click
pervl6.xscasacdn04.click
pervl6.xscasacdn05.click
pervl6.xscasacdn06.click
pervl6.xscasacdn07.click
pervl6.xscasacdn08.click
pervl6.xscasacdn09.click
pervl6.xscasacdn10.click
pervl6.xspcdn11.click
pervl6.xspcdn12.click
pervl6.xspcdn13.click
pervl6.xspcdn14.click
pervl6.xspcdn15.click
pervl6.xspcdn16.click
pervl6.xspcdn17.click
pervl6.xspcdn18.click
pervl6.xspcdn19.click
pervl6.xspcdn20.click
pervl6.xspcdn21.click
pervl6.xspcdn22.click
pervl6.xspcdn23.click
pervl6.xspcdn24.click
pervl6.xspcdn25.click
pervl6.xspcdn26.click
pervl6.xspcdn27.click
pervl6.xspcdn28.click
pervl6.xspcdn29.click
pervl6.xspcdn30.click
pervl6.xspcdn31.click
pervl6.xspcdn32.click
pervl6.xspcdn34.click
pervl6.xspcdn33.click
pervl6.xspcdn35.click
```

- **Family split:** `xspcdnNN` = 35 hosts (01–35); `xscasacdnNN` = 10 hosts (01–10).
- **Order is manifest order, not sorted** — note `xspcdn34` before `xspcdn33` near the end.
  A future host selector must not assume monotonic numbering.
- **Hosts are interchangeable in this probe:** all 45 returned identical bytes for 720
  (`sha256 c17ea1e3…`, 1 680 352 B) and all 45 for 1080 (`sha256 9252ebf3…`, 3 352 608 B).
  The two families differ only by hostname; there is no per-family content difference.
- **Every host is under `*.click`.** A device-side TLD/ad-block/DNS rule on `.click` therefore
  removes the whole roster at once — exactly the H3 failure shape below.

## §3 Hypotheses — what produces the device-side 2001, and what separates it

| # | hypothesis | why it produces 2001 (not 2004) | device-side evidence that separates it |
|---|---|---|---|
| H1 | `variantPlaylist` pins `root.cdn[0]` for every segment (`playableSources` passes `cdnHost = null`) | one dead/blocked/unresolvable CDN host fails every segment connection of every quality → immediate connect failure, before any HTTP status exists | new logcat `source q=… cdn=<host>` shows the pinned host. If that host is the only one and is unreachable from the device while another roster host resolves/connects (e.g. the same `_enc` path tried in VLC), H1. If all `source` lines name the same host but the device browser plays the site's own player, also H1 (or H2). |
| H2 | the key URI host (player subdomain) is a single host per video | the `#EXT-X-KEY` fetch is a separate connection; a blocked player subdomain fails the key leg at connect level | logcat names `key=<player host>` on every `source` line. If segment hosts are healthy but the player host is not (VLC/MX Player can fetch the segments but not the key, or `op=key` never returns 200 from the device), H2. |
| H3 | device DNS / Private DNS / VPN / ad-blocker blocks `*.click` or the roster subdomains | every segment host is under `.click`; a TLD-level block makes all qualities fail identically on all videos | compare the same video on the same device/browser over Wi-Fi vs mobile data, and with Private DNS/VPN/ad-block off. If the site's web player also fails on the same network while the HTTP chain is green from CI, H3. Browser resolves/plays → not H3. |
| H4 | IPv6-only network with broken NAT64 for these hosts | A records exist but AAAA/NAT64 can fail; connect fails before TLS/HTTP | switch the device to IPv4-only / a different network. If the failure follows the network's IPv6 path and disappears on IPv4, H4. Compare `ping`/browser for both families if available. |
| H5 | the synthesized `data:application/vnd.apple.mpegurl;base64,…` URI playlist is rejected or size-limited by the in-app player | the play path never starts; 2001 is the player's generic network-layer error rather than an HTTP status | the decisive external-player split: if VLC/MX Player plays a link the app cannot, the chain is healthy and the fault is how the app consumes the `data:` playlist / its `X-XS-P: 1` header. Try another in-app player (libVLC/MPV) and a video with fewer segments; the playlist sizes in §1 are the baseline. |

## §4 Limits — what CI cannot prove

- **In-app playback.** Every check above is plain HTTP. We can prove the site answers, the
  AES key/IV contract decrypts, and the synthesized playlist is well-formed; we cannot prove
  ExoPlayer accepts the `data:` URI on a particular device. In-app verification stays
  **maintainer-only at merge time** and is never an agent deliverable.
- **`X-XS-P: 1` as consumed by the player.** CI proves the server contract (200 with the
  header, 403 without). Whether the header reaches the key request through the app's
  `ExtractorLink.headers` map on the device is a runtime layer CI does not exercise.
- **`data:` playlist acceptance / size limits.** The in-band playlist path is the one
  assumption #845 already flagged as unverifiable from CI. §1 records the sizes so a device
  report can distinguish a whole-playlist rejection from a per-segment connect failure.
- **Device DNS/TLS/IP stack.** H3/H4 live entirely on the device/network; CI cannot reproduce
  a Private DNS, VPN, ad-blocker, or broken NAT64 path. The new diagnostics only make the
  failing layer legible in `logcat` (`stage=player|manifest|playlist`, `source … cdn=… key=…
  segs=… bytes=…`, `skip q=… reason=…`); they do not test the network.

## §5 In-band playlist budget (S3 / #1338)

> **Superseded by #1353 — see `FINDINGS-1353.md`.** The 96 KiB budget (and the `data:`
> URI it measured) was removed with the `data:` carriage; the playlist is now hosted on
> loopback. This section stays as history of #1338.

Run 2026-10-25 (`05-implement`, story S3 of #1328). Code change is one documented constant
(`XsftkParse.PLAYLIST_BUDGET_BYTES = 96 * 1024`) plus one diagnostic log line on the real
emission path (`PerverZijaExtractor.emitXsftk`). **Emitted bytes are unchanged** — an
over-budget source is still emitted (log, never drop).

### Measured sizes

Live probe from §1, plus what the builder actually produces from the shipped captures
(`PerverZijaPlaylistBudgetTest` / replay):

| source | quality | segments | raw playlist | base64 | `data:` URI (base64 + 42 B prefix) |
|---|---|---|---|---|---|
| live probe (§1) | 1080 | 394 | 44 052 | 58 736 | 58 778 |
| live probe (§1) | 720 | 381 | 41 920 | 55 896 | 55 938 |
| live probe (§1) | 480 | 372 | 40 933 | 54 580 | 54 622 |
| `perverzija_manifest_reported.bin` (#906) | 1080 | 80 | 9 100 | 12 136 | 12 178 |
| `perverzija_manifest_reported.bin` | 720 | 76 | 8 510 | 11 348 | 11 390 |
| `perverzija_manifest_reported.bin` | 480 | 74 | 8 299 | 11 068 | 11 110 |
| `perverzija_proto_manifest.bin` (#845) | 720 | 777 | 84 912 | 113 216 | 113 258 |
| `perverzija_proto_manifest.bin` | 480 | 734 | 80 439 | 107 252 | 107 294 |
| `perverzija_manifest_singleq.bin` (#906) | 360 | 1 411 | 155 613 | 207 484 | 207 526 |

The reported fixture was pruned to 80 segments when it was captured for the repo, so the
394-segment live maximum is reproduced in the test by cycling that fixture's own durations:
**raw 44 046 / base64 58 728 / URI 58 770** — the measured shape within 8 B, and the value
the budget is sized against.

### The constant and its rationale

```kotlin
/** Upper bound for one synthesized playlist carried in a `data:` URI. */
const val PLAYLIST_BUDGET_BYTES = 96 * 1024
```

- 96 KiB ≈ 2× the largest measured payload (58 778 B URI), so a normal video never trips it.
- **Diagnostic ceiling, not a gate.** `emitXsftk` logs
  `source q=<label> playlist bytes=<data-uri bytes> OVER BUDGET (98304)` and still emits.
- Two shipped fixtures are already over budget on real long videos — 720 × 777 =
  113 258 B and 360 × 1 411 = 207 526 B — which is exactly why "drop it" is out of scope:
  dropping would trade a diagnosable oversized payload for a missing source.
- Bounds H5 (playlist rejected / size-limited by the in-app player): if the device failure
  is size-related, `logcat` now names the size against a documented threshold.

### Contract pinned by `PerverZijaPlaylistBudgetTest` (10 tests, green)

1. budget constant; every buildable quality of the reported fixture under budget; the
   measured 394-segment maximum asserted exactly (>40 kB, < budget, largest payload)
2. `#EXT-X-TARGETDURATION:` ≥ the largest `#EXTINF` for every quality (the reported
   fixture's `td=8` undershoots its own 8.341667 s segment → synthesized target 9)
3. `#EXT-X-MEDIA-SEQUENCE:0` for every quality, no `IV=` (IV = media sequence number)
4. exactly one `#EXT-X-KEY:METHOD=AES-128,URI="…op=key…"` line, no `IV=`; key host = the
   player host (not the `*.click` CDN), and the segment host differs
5. `#EXT-X-ENDLIST` is the last line (VOD, not live)
6. segment URLs in index order `0..n-1`, no gaps, no duplicates, one per `#EXTINF`
7. a quality with missing/empty `durs` still yields the other qualities
   (`perverzija_manifest_singleq.bin` covers the single-quality side)

### Still unverifiable from CI

**In-app `data:` playlist consumption is still unverifiable from CI.** The budget makes an
oversized payload legible in `logcat`; whether ExoPlayer on a given device accepts a
multi-tens-of-kB `data:application/vnd.apple.mpegurl;base64,…` URI remains maintainer-only
evidence at merge time, and the S4 device request asks for it (H5's decisive split: an
external player that plays a link the app cannot).

## §6 S2 outcome — multi-CDN fallback in the live chain (S4 / #1339)

Live replay **2026-10-26 04:00–04:12Z**, same method as §1 (plain HTTP from the CI runner,
mobile UA, no JS, theme referer on the player page). Two gated videos from different listing
rows and different player subdomains, plus one legacy video for the untouched-family check:

| video | player host | `op=manifest` | qualities (segments) | roster / families | primary probes | chosen host | alt probes | alt-family host | dead candidates |
|---|---|---|---|---|---|---|---|---|---|
| `evolvedfightslez-jazmine-cruz-vs-nadia-white` (#1328 URL) | `pervl6` | 11 062 B | `1080/394`, `720/381`, `480/372` | 45 / `xspcdn` 35 + `xscasacdn` 10 | **1** | `pervl6.xspcdn01.click` | **1** | `pervl6.xscasacdn01.click` | **0** |
| `blacked-kelsi-monroe-fit-babe-kelsi-handles-his-bbc-at-the-gym` (`/featured-scenes/`) | `pervl4` | 10 332 B | `2160/291`, `1080/215`, `720/219`, `480/221` | 45 / `xspcdn` 35 + `xscasacdn` 10 | **1** | `pervl4.xspcdn01.click` | **1** | `pervl4.xscasacdn01.click` | **0** |

- **Roster reuse: the 45-host shape held on both videos.** Both manifests listed 45 hosts in
the same two families, `xspcdnNN` (35) and `xscasacdnNN` (10); only the player-subdomain
prefix moved (`pervl6.*` vs `pervl4.*`). Manifest order reproduced the §2 quirk (`xspcdn34`
before `xspcdn33`); no host was dropped by dedupe.
- **Probe counts actually spent: 2 per gated video (4 total).** One primary probe on the
highest buildable quality (`1080` / `2160`), accepted by `cdn[0]` on the first try; one alt
probe on the first host of the other family, also accepted on the first try. The budgets
(`MAX_PROBES` 6 / `MAX_ALT_PROBES` 3) were never approached.
- **No candidate was dead from CI.** Both `cdn[0]` and the other family's `cdn[0]` answered
`206` to `Range: bytes=0-1023`. That is **not** direct evidence for H1: if the device still
fails on these URLs, the divergence is device-side (H3/H4/H5) or the per-video key host (H2),
not a primary host that CI can see as dead.
- **The alt family is the same media.** Segment 0 of the chosen and alt hosts was byte-identical
on both videos (`sha256 9252ebf3d09cfee4…`, 3 352 608 B for `pervl6`; `sha256 a107b649b5cdf151…`,
54 528 B for `pervl4`), matching the §1 conclusion that the roster is interchangeable.
- **S2 did not touch the legacy family.** `playerPlan.Legacy` returns before `emitXsftk`, so no
CDN selection runs. The legacy replay (VERIFICATION `## #1328 — playback hardening (S4)` §3)
kept its pre-gate master → variant → plain-TS chain: `j2.xspcdn01.click` segment 0,
100 % MPEG-TS sync at 480/720/1080.
- **Instrument plateau unchanged.** The emitted source is still a `data:` URI, not a fetchable
m3u8, so the playlist is byte-comparable but not replayable by `verify.sh`; the manual chain
above is the substitute, exactly as recorded for #909.

## §7 Next device report

This block is for the maintainer to paste into a future report. This lane does **not** post it
as a comment and does **not** ask the reporter directly. The three answers settle H1 vs H3 vs
H5; without them, a remaining 2001 is indistinguishable at the code layer.

```text
PerverZija playback evidence request (one failing attempt, please):

(a) Attach `adb logcat -s kraptor_PerverZija kraptor_PerverZijaExtract` from around one
    failing attempt. The S1 lines name the failing stage (`stage=player|manifest|playlist|cdn`),
    the selected host (`source q=… cdn=… key=… segs=… bytes=…`) and any skipped quality
    (`skip q=… reason=…`).

(b) Open the same video URL in VLC or MX Player on the same device and network.
    Plays there → in-app consumption (H5); fails there too → reachability/DNS (H1/H3/H4).

(c) Report the device's Private DNS / VPN / DNS-filter state and whether the `cdn=` host from
    (a) resolves, e.g. `adb shell ping -c1 pervl6.xspcdn01.click` (or the matching
    `xscasacdn` host). Also report Settings → Player (ExoPlayer / libVLC / MPV).

Why this matters: every segment host is under `*.click`; a device-side TLD/filter block
removes the whole roster at once (H3), which no Kotlin change can fix. If an external player
plays a link the app cannot, the chain is healthy and the fault is the in-app `data:` playlist
path (H5).
```
