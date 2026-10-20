# FINDINGS-767 — Porntrex quality mapping: API + live evidence (S1 of #759)

Run: 2026-10-20 (S1 of the approved #759 spec chain; S2 #768 red, S3 #769 green, S4 #770 verify).
**Deliverable:** ground truth for the quality-mapping fix — what the pinned CloudStream API
expects, what Porntrex actually emits, the exact mismatch, and the decided mapping table.
**No code changed in this story.**

## 1. API ground truth — pinned CloudStream classes.jar

`bootstrapCloudstream` classes.jar (`com.lagradost:cloudstream3:pre-release`,
sha256 `16278fc26dc9b6205b43d20cd3a0bbf5a51eb39c614a395354f484343115ccda`),
bytecode-inspected with `javap` and re-executed instruction-by-instruction:

### `ExtractorLink.quality` is a plain `Int`

`private int quality` + getter/setter (no enum, no null). The app consumes it for
quality-based sorting and renders its own tag via `Qualities.Companion.getStringByInt(q)`:
`0` → `"Auto"`, `400` → `""` (**empty label — invisible in the player**), `2160` → `"4K"`,
anything else → `"${q}p"` (so 480 → `480p`, 1080 → `1080p`). Confirms the maintainer's
approval note: pass the enum Int correctly and the app renders the quality label itself.

### `Qualities` enum constants and their Int `value`s

| constant | value | defaultPriority (= ordinal) |
|---|---|---|
| `Unknown` | **400** | 4 |
| `P144` | 144 | 0 |
| `P240` | 240 | 2 |
| `P360` | 360 | 3 |
| `P480` | 480 | 4 |
| `P720` | 720 | 5 |
| `P1080` | 1080 | 6 |
| `P1440` | 1440 | 7 |
| `P2160` | 2160 | 8 |

### `getQualityFromName` — exact matching semantics (decompiled, `ExtractorApiKt`)

```kotlin
fun getQualityFromName(qualityName: String?): Int {
    if (qualityName == null) return Qualities.Unknown.value          // 400
    val name = qualityName.lowercase(Locale.ROOT).replace("p", "").trim()
    return when (name) {
        "4k"  -> Qualities.P2160.value                               // 2160 — WHOLE-STRING equality
        else  -> name.toIntOrNull() ?: Qualities.Unknown.value       // 400 when unparseable
    }
}
```

Bytecode-verified behaviors (executed against the real jar's semantics):

| input | after lowercase → strip `p` → trim | result |
|---|---|---|
| `"480p"` | `"480"` | **480** ✔ |
| `"720p"` | `"720"` | 720 ✔ |
| `"1080p"` | `"1080"` | 1080 ✔ |
| `"2160p"` | `"2160"` | 2160 ✔ |
| `"4K"` | `"4k"` | 2160 ✔ |
| `"540p"` | `"540"` | 540 ✔ |
| `"720p HD"` | `"720 hd"` | **400 — Unknown ✗** |
| `"1080p FHD"` | `"1080 fhd"` | **400 — Unknown ✗** |
| `"2160p 4K"` | `"2160 4k"` | **400 — Unknown ✗** (not `"4k"`; the check is equality, not contains) |
| `"HD"` | `"hd"` | 400 ✗ |
| `""` (or null) | — | 400 ✗ |

**The trap:** any label with a suffix token (`HD`, `FHD`, `4K`) breaks both paths — the
whole-string `"4k"` equality and the whole-string `toIntOrNull` parse — and silently
degrades to `Unknown` (400), which the app renders as an *empty* quality tag.

## 2. Live ground truth — Porntrex flashvars (guest session, 2026-10-20)

Probed 8 videos across categories (4K porn, amateur, teen, milf, webcam) and age range
(newest listings + search-sourced older IDs, 3282282–3369587): guest video pages render
the empty shell (no flashvars, issue #171) and the full KVS player config comes from
`/embed/{id}/`. **Every embed carries the identical, static label ladder:**

| flashvar | `_text` label | URL shape | `redirect` flag |
|---|---|---|---|
| `video_url` | `'480p'` | `https://www.porntrex.com/get_file/10/<hash>/<dir1000>/<id>/<id>.mp4/?embed=true` — real media | — (none) |
| `video_alt_url` | `'720p HD'` | `<mainUrl>/video/<id>/<slug>` — HTML page | `'1'` |
| `video_alt_url2` | `'1080p FHD'` | `<mainUrl>/video/<id>/<slug>` — HTML page | `'1'` |
| `video_alt_url3` | `'2160p 4K'` | `<mainUrl>/video/<id>/<slug>` — HTML page | `'1'` |

Sample (video 3363831):

```
video_url       = 'https://www.porntrex.com/get_file/10/1f12c0c54f99449e3401e0ad9df15f49ccc69ae2bf/3363000/3363831/3363831.mp4/?embed=true'
video_url_text  = '480p'
video_alt_url   = 'https://www.porntrex.com/video/3363831/lily-lou-4k'
video_alt_url_text    = '720p HD'
video_alt_url_redirect = '1'
video_alt_url2_text   = '1080p FHD'   · video_alt_url2_redirect = '1'
video_alt_url3_text   = '2160p 4K'   · video_alt_url3_redirect = '1'
video_alt_url_hd = '1' · video_alt_url2_hd = '1' · video_alt_url3_hd = '1' · video_alt_url3_4k = '1'
```

Key facts for S2/S3:

- **The resolution is NOT in the filename** (`<id>.mp4`; `/get_file/` signed URL). The
  `<key>_text` label is the *only* quality signal on this site — URL-pattern-based
  quality inference is impossible.
- **The label ladder is static KVS boilerplate** (identical on all 8 probed videos,
  regardless of actual encodes). `480p` is the only real guest stream; the three
  alt tiers are redirect-flagged page URLs, which `PorntrexParse.qualityLinks`
  already skips correctly (issue #429 behavior — keep it).
- **KVS flags `video_alt_url*_hd` / `video_alt_url3_4k` exist** but are redundant on
  this site; the `_text` label is the canonical, always-present signal.

## 3. The exact mismatch (what the site emits vs. what the API expects)

Porntrex emits suffixed KVS labels — `720p HD`, `1080p FHD`, `2160p 4K` — for every
non-base tier. `getQualityFromName` recognizes **only** a bare `<digits>p` / `<digits>`
string or the exact whole string `4K`/`4k`; every suffixed label falls through both
branches to `Qualities.Unknown` (400). So `this.quality = getQualityFromName(label ?: "")`
(Porntrex.kt, loadLinks) maps every suffixed tier to 400: the app cannot quality-sort
them and renders an empty quality tag. The one label the current guest flow emits
(`480p`) happens to parse — but the mapping is structurally broken for every tier the
site actually advertises, which is exactly the reported #759 behavior the moment a
non-redirected (real file) alt tier appears.

## 4. Decided mapping table (label/url pattern → quality Int)

Extract the leading resolution number from the label (canonical KVS signal on this
site), validated to a sane resolution range; keep `getQualityFromName` as fallback for
the numberless `4K` form; else `Unknown`:

| emitted label (observed) | quality Int | via |
|---|---|---|
| `480p` | **480** | leading-number |
| `720p HD` | **720** | leading-number |
| `1080p FHD` | **1080** | leading-number |
| `2160p 4K` | **2160** | leading-number |
| `4K` / `4k` (numberless, KVS form) | **2160** | `getQualityFromName` fallback |
| `144p`…`1440p`, `540p`, bare `<digits>` | the number itself | leading-number (range-validated) |
| anything else / blank / missing | **400** (`Qualities.Unknown.value`) | fallback |

Rules for S2 (red tests) / S3 (wiring):

1. Map from the `PorntrexParse.qualityLinks` label (String?), not the URL — filenames
   carry no resolution.
2. Keep skipping `redirect: '1'` variants — their URLs are HTML pages, not media.
3. Keep the link `name` as `Porntrex - <label>`; the app renders its own quality tag
   from the Int (`getStringByInt`), so no label re-formatting is needed.
4. Suggested Parse shape (S2 test surface): `PorntrexParse.qualityOf(label: String?): Int` —
   leading `Regex("""(\d{3,4})""")` within 144..4320 → that Int; else
   `getQualityFromName(label ?: "")` (covers `4K`); else 400.

## Provenance

- Evidence gathered live 2026-10-20 (runner), guest session, browser UA.
- API inspected from the pinned `bootstrapCloudstream` classes.jar
  (sha256 `16278fc2…15ccda`); `getQualityFromName` semantics re-executed
  instruction-by-instruction against the decompiled control flow.
- Trigger: #767 (S1, parent #759) · Workflow run:
  https://github.com/RjBiermann/fictional-octo-fiesta/actions/runs/39365507289
