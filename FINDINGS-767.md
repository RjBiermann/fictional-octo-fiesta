# FINDINGS-767 — Porntrex quality mapping, S1: pinned-API + live evidence (sub-issue of #759)

Run date: 2026-10-20. Scope: story S1 of the #759 quality-mapping spec — ground truth **before
any code change**. Method: (a) `./gradlew bootstrapCloudstream` classes.jar disassembled with
`javap -c` (`Qualities`, `Qualities$Companion`, `ExtractorApiKt`), enum Int values additionally
**runtime-verified** by loading the pinned jar (`jshell`); (b) live probe of porntrex.com guest
surfaces with curl (browser UA), 8 videos sampled across latest-updates / milf / vintage /
4k-porn categories (IDs 3363545, 3363831, 3364205, 3366836, 3368006, 3368050, 3368064, 3368260).

## Part 1 — Pinned CloudStream API (`cloudstream3:pre-release` classes.jar)

### `ExtractorLink.quality` is an `Int`

`public int getQuality()` / `setQuality(int)` on `com.lagradost.cloudstream3.utils.ExtractorLink`
(a `var`). The app's source-priority/sorting machinery consumes this Int; the display label is
**rendered from the Int** (below), not read from `ExtractorLink.name`.

### `Qualities` enum — constants and Int values (runtime-verified on the pinned jar)

| constant | `value` | | constant | `value` |
|----------|---------|-|----------|---------|
| `Unknown` | **400** | | `P720`  | 720 |
| `P144`  | 144 | | `P1080` | 1080 |
| `P240`  | 240 | | `P1440` | 1440 |
| `P360`  | 360 | | `P2160` | 2160 |
| `P480`  | 480 | | | |

Note **Unknown = 400**, which sorts *between* 360 and 480 — an unrecognized label is not
"neutral", it actively ranks below 480p.

### `getQualityFromName(String?): Int` — exact grammar (bytecode-derived)

```
if (input == null)                -> Qualities.Unknown.value (400)
s = input.lowercase(Locale.ROOT).replace("p", "").trim()
if (s == "4k")                    -> Qualities.P2160.value (2160)
return s.toIntOrNull() ?: Qualities.Unknown.value (400)
```

That is the entire branch set (verified against the full `javap -c` listing — the only string
comparison in the method is `areEqual(s, "4k")`). Test vectors, including every string the
story asks about:

| input | result | recognized? |
|-------|--------|-------------|
| `"1080p"` | 1080 | yes (bare `NNNp`) |
| `"540p"` | 540 | yes — **any** digit-run + `p`, enum constant not required |
| `"4k"` / `"4K"` (exact) | 2160 | yes |
| `"1080p FHD"` | **400** | **no** — "1080 fhd" doesn't parse as Int |
| `"2160p 4K"` | **400** | **no** — "2160 4k" ≠ "4k", not an Int |
| `"720p HD"` | **400** | no |
| `"HD"` | **400** | no |
| `""` (empty) | **400** | no (same as Unknown) |
| `null` | **400** | no |

### App-side label rendering — `Qualities.Companion.getStringByInt(Int): String`

```
0 or null -> "Auto";  400 (Unknown) -> "";  2160 -> "4K";  else -> "<N>p"
```

Confirms the maintainer's approval note: once the correct enum Int is passed, the app renders
the quality label itself (720 → "720p", 2160 → "4K", 400 → blank). The provider's link `name`
does not need to carry the label for the app UI.

### S2 testability constraint (discovered this run, load-bearing for the red→green cycle)

`getQualityFromName` lives in the `ExtractorApiKt` facade, whose `<clinit>` builds the whole
extractor registry and needs `dev.whyoleg.cryptography` classes (unresolvable on the repo's
unit-test classpath — verified: compile passes, runtime throws `NoClassDefFoundError`). The
`Qualities` enum itself **is** loadable in unit tests (its `<clinit>` needs only the stdlib —
verified both in jshell and in a Gradle test run). Consequence: the S2 mapping test must
assert against `Qualities` constants / an in-repo mapping function, **not** call
`getQualityFromName` at test runtime.

## Part 2 — Live probe (2026-10-20, guest surfaces)

- Direct `/video/{id}/{slug}/` pages: 200 but **guest shell** — no `flashvars`, generic title
  (consistent with `Porntrex/FINDINGS.md` issue-#171 drift). `/embed/{id}/`: 200, full KVS
  flashvars (~7.8 KB). The embed shell is the only guest surface.
- **`<key>_text` labels — uniform on 8/8 videos** (including 4K-category videos):

  | flashvar | label | URL | playable? |
  |----------|-------|-----|-----------|
  | `video_url` | `'480p'` | `get_file/{srv}/{32-hex}/{folder7}/{id}/{id}.mp4/?embed=true` | **yes — the only direct stream** |
  | `video_alt_url` | `'720p HD'` | the video page URL (text/html) | no — `video_alt_url_redirect: '1'` |
  | `video_alt_url2` | `'1080p FHD'` | the video page URL | no — `redirect: '1'` |
  | `video_alt_url3` | `'2160p 4K'` | the video page URL | no — `redirect: '1'` (+ `4k: '1'`) |

- The three-tier ladder (`720p HD` / `1080p FHD` / `2160p 4K`, all redirect-flagged) appears on
  **every** probed video regardless of actual resolution — embed-shell boilerplate; the
  `_hd: '1'` / `_4k: '1'` flags are present on all of them and do not gate anything.
- `get_file` resolution: `302` → `https://pcdn.cdntrex.com/porntrex/.../{id}.mp4?expires=...&md5=...`
  — **no quality component in the served filename**.
- URL surgery test: rewriting the signed get_file path `{id}.mp4` → `{id}_720p.mp4` returns
  **404** (the signature covers the filename) — higher tiers are **not** reachable by editing
  the guest stream URL.
- Historical shape for contrast: the issue-#256 era KVS grammar shows alts as
  `get_file/.../2491818_720p.mp4` — quality-in-filename exists on KVS generally but is **not**
  emitted on today's Porntrex guest embed. Citation, corrected on review round 1: that shape
  is attested by the hand-written inline flashvar literal in
  `Porntrex/src/test/kotlin/com/byayzen/PorntrexParseTest.kt:46` (synthetic test input, not
  captured site evidence) — the committed `porntrex_embed_page.html` fixture has no such
  pattern (its alts are redirect-flagged page URLs, same shape as today's live embed; its
  only `_720p` occurrence is the `preview_720p.mp4.jpg` preview image).

## Part 3 — The exact mismatch (site vs. pinned API)

> What the site emits: `480p` (only playable tier) plus the compound labels `720p HD`,
> `1080p FHD`, `2160p 4K` on every non-playable tier.
> What the API grammar accepts: bare `NNNp` and exactly `4k`.
>
> → **Every compound label falls through to `Unknown` (400)**, which sorts *below 480p*
> (400 < 480) and renders as blank in the app. The only label that survives
> `getQualityFromName` is `'480p'` (→ 480, correct). So a mapping fix that keeps passing
> `label` through `getQualityFromName` ships a **400 for every tier above 480p** — this is
> the mechanism behind "all resolutions are affected" (maintainer, round 2): whichever way
> S2 sources the higher tiers (today: redirect-page tiers; future: real per-tier streams),
> label-based mapping cannot produce a recognized Int for them.

## Part 4 — Decided mapping table (S2 contract: label/url pattern → quality Int)

| emitted label | quality Int | source |
|---------------|-------------|--------|
| `'480p'` (base `video_url`, direct stream) | `480` (`Qualities.P480.value`) | bare `NNNp` — native |
| `'720p HD'` | `720` (`Qualities.P720.value`) | compound — must be mapped explicitly |
| `'1080p FHD'` | `1080` (`Qualities.P1080.value`) | compound — must be mapped explicitly |
| `'2160p 4K'` | `2160` (`Qualities.P2160.value`) | compound — must be mapped explicitly |
| `'540p'` / any bare `NNNp` | `NNN` | native `getQualityFromName` parse |
| `'4k'` (bare, exact) | `2160` | native |
| `null` / `''` / unrecognized | `400` (`Qualities.Unknown.value`) | API default |

Rule for S2: map the three known compound labels to `Qualities` enum values **first**, then
for bare `NNNp`/`4k` apply the Part-1 grammar **inlined in the mapping function** (lowercase
→ strip all `p` → trim → `"4k"` → 2160, else `toIntOrNull() ?: Qualities.Unknown.value`),
default `Unknown`. The fallback must **not** delegate to `getQualityFromName` anywhere in
test-executed code: invoking it triggers `ExtractorApiKt` class initialization, whose
`<clinit>` builds the full extractor registry and needs `dev.whyoleg.cryptography` (absent
from the repo test classpath — the testability constraint above), which would make the
mapping function itself un-unit-testable and crash the S2 red→green cycle on exactly the
native-parse rows (`'540p'` → 540, bare `'4k'` → 2160). The grammar is bytecode-verified in
this note, so an inline copy is behaviorally identical and keeps the mapping function
unit-loadable; adding the cryptography runtime to `testImplementation` instead would be a
root-build change affecting every provider — out of scope for a single-provider fix.
Pass the Int on
`ExtractorLink.quality`; the display `name` may keep or drop the site label — the app renders
the badge from the Int (`getStringByInt`). If S2 adds link emission for redirect tiers, they
must resolve to real media URLs first (page URLs are never streams), and the guest-surface
constraint above applies (no URL surgery; signed get_file 404s on filename edits).

## Boundaries / risks

- Runtime divergence: the user's installed app runs its own copy of `getQualityFromName`;
  stable releases ship only an APK (no inspectable classes.jar), so this note pins the
  **pre-release** API the repo compiles against (the surface extension authors can target).
  In-app confirmation of the rendered labels is maintainer-only at merge time.
- Member-only surfaces (logged-in full video page) were not probed — member flashvars may
  expose real per-tier `get_file` URLs; if so, the same mapping table applies to their labels.
