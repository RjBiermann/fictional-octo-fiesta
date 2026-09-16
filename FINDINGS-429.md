# FINDINGS-429 — Porntrex quality field (issue #429)

**Issue:** "If the provider can determine video quality, it should be passed down to
cloudstream app in the fields. Ex. Porntrex quality is not working with cloudstream."

## Probe (live, this run)

`GET /embed/2913997/` (the guest surface, per FINDINGS.md #171/#256/#275):

```
video_url:      'https://www.porntrex.com/get_file/28/58549f.../2913997/2913997.mp4/?embed=true'
video_url_text: '480p'
video_alt_url:      '.../video/2913997/...'   video_alt_url_text:  '720p HD'   + _redirect: '1'
video_alt_url2:     '.../video/2913997/...'   video_alt_url2_text: '1080p FHD' + _redirect: '1'
video_alt_url3:     '.../video/2913997/...'   video_alt_url3_text: '2160p 4K'  + (no _redirect flag line captured this run — variant checked in code, skipped only when _redirect: '1' present)
```

Embed surface: exactly one real media stream (`video_url`, 480p); the alt variants are
page-URL redirect stubs and are already skipped by `PorntrexParse.qualityLinks` (issue #256).
On fully-rendered direct pages the alt variants carry real per-quality `get_file` hashes
(#256 evidence) — labels there are '360p', '480p', '720p HD', '1080p HD'.

## Diagnosis

`Porntrex.loadLinks` (v12) already parses the per-variant label and appends it to the
link **name** (`"Porntrex - 480p"`) — but it never sets the `ExtractorLink.quality`
field, which is what the CloudStream app reads for its quality field/dropdown. Result:
the app sees `quality = Qualities.Unknown` regardless of label. That is the reported gap.

## Fix (version 12 → 13)

Set `this.quality = getQualityFromName(label ?: "")` in the `loadLinks` callback —
cloudstream's own util (already used by shared Extractorlar.kt / HostAdapters.kt pillars)
maps '480p'/'720p HD'/'1080p FHD' etc. to the numeric quality. The label string itself
already lives in the link name. No parsing change, no shared/ change: the bug is entirely
in Porntrex's own callback body.

## Verification

- `gradlew Porntrex:test` — qualityLinks fixture tests unchanged/green (parsing untouched).
- `gradlew Porntrex:make` — clean build.
- verify-provider script run against the live site (results appended below).

## Verification (verify.sh, this run, live) — PASS except documented site-native churn

- search `/search/massage/` + async page 2: 200, 85 `p.inf` cards each, no dupes
  (`p.inf` per the #275 note — the full-card selector truncates to the quality badge text)
- home `/categories/busty/` + async page 2: 200, 120 cards each; FAIL on 9 hrefs shared
  between page1/page2 — **site-native**, reproduced directly from the site's own front page
  vs its own `/2/` URL (16 shared hrefs, same cards), the documented churn of the #275/#309
  runs. Provider's getMainPage is untouched by this PR.
- 5 video URLs via `/embed/{id}/` (2491818, 2913997, 3074115, 1122515, 1231055): all 200,
  `div#kt_player` 1× each; streams `get_file/.../{id}.mp4/` → **206 video/mp4 on all 5**
- quick-search: NOTE, per #275 the endpoint is album-suggestion only, `hasQuickSearch = false`
- LoadResponse fields: recommendations,tags,plot,duration,actors,posters all assigned
- Kindle of the fix itself from the live flashvars: embed `video_url_text: '480p'` →
  `getQualityFromName('480p')` = the 480p quality value, now carried in `ExtractorLink.quality`
  (previously always Unknown); full-page alt labels ('720p HD', '1080p HD') map the same way.

`gradlew Porntrex:test Porntrex:make` — BUILD SUCCESSFUL (version 12 → 13).
