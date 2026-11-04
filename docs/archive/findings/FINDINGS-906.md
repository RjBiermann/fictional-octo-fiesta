# FINDINGS-906 — PerverZija failing-class census through the XSFTK chain (S1 of #903)

Run: 2026-10-23 (`05-implement`, census story #906; parent #903, epic "PerverZija residual
2004 — failing-class hardening"). **No provider or `shared/` code changed in this story** —
deliverables are the census result and the failing/failing-shaped fixtures that #907 (`S2`)
consumes. Version stays 16.

Site: `https://tube.perverzija.com` — 336 unique video pages sampled from the live listings
(`/featured-scenes/`, `/studio/` p1–p3, `/full-movie/`, `/tag/classic/`, `/tag/4k-quality/`,
`/?s=anal`), including the reported URL from #903.

Method (per video, plain HTTP from the CI runner, mobile UA, no JS):

1. video page → first `iframe` with `xtremestream.xyz` in its src;
2. player page with the **in-app referer** (`https://tube.perverzija.com/`) → boot line;
3. XSFTK family: `op=manifest` → XOR-SHA-256 decode → qualities → synthesized playlist →
   `op=key` (header `X-XS-P: 1`) → seg-0 AES-128-CBC (`IV=BE(0)`) → MPEG-TS sync check;
4. legacy family (no boot line): `xs1.php?data=<id>` master → first variant → seg-0 fetch →
   MPEG-TS sync check (legacy segments are served **unencrypted**).

## Census result — **335 / 336 green, 1 fail**

| family | count | share | chain | verdict |
|---|---|---|---|---|
| XSFTK (gated, `XSFTK.boot(...)` in player HTML) | 319 | 94.9% | boot → XOR manifest → `_enc` seg-0 AES → TS | green |
| legacy video.js (`m3u8_loader_url = …/player/xs1.php`, no boot line) | 16 | 4.8% | `xs1.php` master → `<q>/` seg-0 (plain TS) | green |
| alternate embed host (`mixdrop.to`, no xtremestream iframe) | 1 | 0.3% | — | **fail — upstream file dead** |

XSFTK manifest quality counts: 1 quality ×34, 2 ×39, 3 ×173, 4 ×73.

**The reported URL is green from CI** (`playboyplus-natalia-starr-giddy-up`): boot
`71eee742…` / `ba68f705…`, manifest 3238 B → `1080,720,480`, `op=key` 16 B, `_enc` seg-0
decrypts to clean MPEG-TS. This re-confirms the plan-round-1 probe: the site/extractor HTTP
chain does not fail for this video.

## The two player generations are distinct CDN layouts (key finding)

For the same video id, the two URL shapes are mutually exclusive:

| video family | `/<id>/<q>/<q><i>.html` (legacy) | `/<id>/<q>_enc/<q><i>.html` (gated) |
|---|---|---|
| gated (reported `71eee742…`, 720) | **404** (decoy) | 200, AES-CBC |
| legacy (`60ad8380…`, 480) | 200, plain MPEG-TS (`47 40 11 10`) | **404** |

So the tokenless `xs1.php` master is a **decoy only on gated pages**. On the 16 legacy pages it
is live and playable — the shared fallback in `PerverZijaExtractor.getUrl` (boot == null →
`xs1.php`) is correct for them and **must not be removed** by the #908 hardening. The decoy risk
is only a gated page whose boot line is missed by the regex → fallback emits a single tokenless
link → every segment 404s → ExoPlayer 2004. That is the owner's exact symptom shape ("one
source, no qualities"), but none of the 319 gated pages sampled missed the current boot regex.

## Target shapes from the spec

- **(a) boot-miss decoy page → single tokenless `xs1.php` link** — real, but inverse of the
  spec's assumption: all boot-miss pages are the legacy generation, and their `xs1.php` chain is
  live. Fixture captured: legacy player HTML (boot-miss) + `xs1.php` master + variant playlist.
  The hardening should keep the legacy fallback and add structural gating detection
  (`proto_api`/`ftk`/`XSFTK` present → never fall back to `xs1.php`, plus boot-format tolerance),
  not drop the legacy path.
- **(b) single-quality / odd-quality manifest** — real and common: 34/319 (10.7%) expose exactly
  one quality (mostly 360/480 full-movie titles, e.g. `wicked-hercules-2002` → `360`).
  `qualities`/`variantPlaylist` already handle them from CI → guard against regression.
- **(c) alternate iframe host dropped by `loadLinks`** — real but 1/336:
  `allanal-lana-anal-rebel-rhyderanal-cravings/` embeds `//mixdrop.to/e/enl4jzdjt76r6q` instead
  of xtremestream; `loadLinks`'s `contains("xtremestream.xyz")` guard drops it (zero sources).
  The mixdrop file itself is dead at source (`WE ARE SORRY`, reproduced ×2 through
  `mxdrop.click`), so this is a latent host-tolerance gap, not a fixable playback failure —
  same evidence class as FINDINGS-631. No fixture shipped for it (not playable upstream).

## Spec traceability — no real failing-video fixture exists

The #906 spec asked for fixtures captured from at least one **failing** video. The census shows
that no such video is available: 335/336 sampled pages are green through their live chain, and
the single failure (shape c) is an alternate `mixdrop.to` embed whose upstream file is dead
(`WE ARE SORRY`), so it cannot yield a playable capture at any layer. This is a literal spec
deviation forced by site evidence, recorded here for #907: shapes **(a)** and **(b)** are shipped
as real fixtures, while shape **(c)** is evidence-only (dead upstream, no media fixture possible).

## Fixtures shipped (under `PerverZija/src/test/resources/`)

| file | source | shape |
|---|---|---|
| `perverzija_player_legacy_bootmiss.html` | `blackedraw-serena-santos-wild-night` (legacy) | boot-miss player page, single tokenless `xs1.php` loader (newline runs collapsed from the 1.0 MB live response; markup preserved apart from one duplicate `logotitle`/`logourl` pair removed for Sonar S1534) |
| `perverzija_legacy_master.m3u8` | same video, `xs1.php?data=60ad8380…` | legacy master, 480/720/1080 variants |
| `perverzija_legacy_variant480.m3u8` | same video, `xs1.php?…&q=480` | legacy media playlist (plain `<q>/` segments) |
| `perverzija_player_reported_xsftk.html` | reported URL from #903 | gated player page (boot line verbatim) |
| `perverzija_manifest_reported.bin` | same video | 3238 B XOR manifest → 1080/720/480 |
| `perverzija_player_singleq_xsftk.html` | `wicked-hercules-2002` | gated player page, single-quality case |
| `perverzija_manifest_singleq.bin` | same video | 13700 B XOR manifest → `360` only |

Live capture metadata (ids/tokens/URLs) is recorded in this file; tokens carry the site's
epoch expiry and are only used as XOR inputs, so they do not need to be fresh for tests.

## What the census does not settle (hand-off to #908/#909)

The owner's in-app 2004 is **not reproducible at the HTTP layer** on the reported URL or on any
sampled gated/legacy video. What remains unexplained is above HTTP: (i) whether ExoPlayer
accepts the XSFTK `data:application/vnd.apple.mpegurl;base64,…` playlist on-device, and (ii)
whether the link `referer`/headers reach the legacy master's variant requests. The census proves
the site side is healthy for both families, so #908's fallback chain should avoid depending on a
single in-app assumption per family while keeping the legacy `xs1.php` path for the 16 real
legacy videos.

## Re-probe commands used

```console
$ curl -s -A 'Mozilla/5.0 (Linux; Android 10)' -H 'Referer: https://tube.perverzija.com/' \
    'https://perv.xtremestream.xyz/player/index.php?data=60ad83801910ec976590f69f638e0d6d' \
    | grep -c 'XSFTK.boot'                       # 0  (legacy page)
$ curl -s -A 'Mozilla/5.0' -H 'Referer: https://perv.xtremestream.xyz/' \
    'https://perv.xtremestream.xyz/player/xs1.php?data=60ad83801910ec976590f69f638e0d6d'
# 200 #EXTM3U master, variants q=480/720/1080
$ curl -s 'https://perv.xspcdn01.click/cdn/down/60ad83801910ec976590f69f638e0d6d/480/4800.html'
# 200 175028 bytes, first bytes 47 40 11 10 (plain MPEG-TS)
$ curl -s 'https://pervl3.xspcdn01.click/cdn/down/71eee742e4c6e094e6af364597af3f05/720/7200.html'
# 404 513 bytes (decoy; gated video — only /720_enc/7200.html serves)
```
