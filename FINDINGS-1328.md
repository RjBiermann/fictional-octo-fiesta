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
