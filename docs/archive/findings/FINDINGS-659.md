# FINDINGS-659 — PandaMovies (pandamovies.pw) deep pass (approved #631 spec, one provider)

Run: 2026-10-17 (UTC 23:40–2026-10-18 00:20). Issue **#659** (sub-issue of epic #631). Self-contained run: full-surface probe with verdict vocabulary, findings lifecycle, delivery gates. **Verdict: `ok` (retained) — 0 confirmed conditions, no code fix, no version bump (stays 5).**

Instrument note: TLS-impersonated curl was **unavailable in this sandbox** (no `curl_cffi`/curl-impersonate); the escalation ladder's third rung (browser diagnosis, headless Chromium) covered the two surfaces plain curl could not. Tier recorded per surface below.

## Phase 0 — calibration

Canaries **3/3 match** (`audits/canaries.json`, plain-curl expectations; TLS tier not exercised — unavailable in this sandbox, not an instrument downgrade of the observed surfaces):

| canary | expected (plain) | observed |
|---|---|---|
| film1k-challenge-pair | 403 | 403 ✔ |
| eporner-healthy | 200 | 200 ✔ |
| pandamovies-redirect-origin | 302 | 302 → 200 @ pandamovies.org ✔ |

## Probe evidence (escalation ladder: plain curl → TLS-impersonated [unavailable] → browser diagnosis)

All plain-curl unless noted. Fresh captures in `/tmp/gh-aw/agent/` (not committed).

### Listing faces — p1/p2 disjointness (the drift sentinel)

| Surface | p1 | p2 | p1∩p2 href overlap |
|---|---|---|---|
| Home `/` + `/page/2` | 200 — main grid `section.hb--main` 27 `article.card` + Featured widget `section.sec` 9 cards | 200 — 27 cards, main grid only (no widget on p2) | **0** (raw unfiltered view also 0) |
| Search `/search/lesbian` + `/page/2` | 200, 27 cards | 200, 27 cards | **0** |
| Genre `/genre/lesbian` + `/page/2` | 200, 27 cards | 200, 27 cards | **0** |

- `/page/2/` (trailing slash) 301-normalizes to `/page/2` — both forms 200; NiceHttp follows (no provider impact).
- **Widget-row shadow, same-page only:** the Featured widget (`section.sec`, 9 cards) repeats one main-grid card on p1 (`watch-i-cant-help-it-5` twice raw). The provider's `Parse.cards` excludes `section.sec` via `closest()` and dedupes by href — absorbed; no cross-page rotation (the Mangoporn #657 drift A class is **not** present here: p1 widget ∩ p2 main = ∅).
- Card counts (27/27/27) differ again from fix-time 49/35 and last-deep 27 — surface sizes shift, **grammar did not** (`article.card` + `card__th`/`h2|h3.card__t`/`img.card__img` intact on every face).

### Search

`/search/{slug}` p1/p2 healthy (above); `?s=` equivalent untouched; `Parse.queryMismatch` zero-overlap guard not triggered by any sampled healthy page.

### quickSearch surface — site-side endpoint migration (recorded, not provider drift)

- **OLD (documented in `PandaMovies/FINDINGS.md`, 2026-09-16):** `POST /wp-admin/admin-ajax.php action=searchwp_live_search…` → now **400, body `0`** (WP "bad action" — the SearchWP live-search plugin is gone).
- **NEW:** the site now runs the **Turbo Search** plugin: `GET /wp-json/turbosearch/v1/suggest?q=lesbian` → **200 JSON** (`q`, `posts[]` with `id/title/url/thumb/meta`). Visible in page config: `window.tsrCfg={"url":"…/wp-json/turbosearch/v1/suggest",…}` (turbo-search v1.6.2).
- **Provider impact: none** — the provider ships no `quickSearch` override (`hasQuickSearch` false by the documented FINDINGS decision: the endpoint duplicates search's shape). Absent surface per spec §1: recorded, not drift. verify.sh check 1b ran the explicit-absent NOTE path.

### Video pages (4 sampled ≥3 required)

`watch-i-cant-help-it-5` (home p1), `watch-a-close-reunion` (genre p1), `watch-lesbian-analingus` (search p1), `watch-yasmine-de-leon-unleashed` (home p1) — all 200; grammar intact on all four: `span.bc__c` clean title, `img.hlm-screen__poster`, plot `div.vid__txt p`, details panel `div.dp__panel[data-panel=details]` (`a[href*=/genre/]` tags ×12–21, `a.chip--star` actors ×6–8, `a[href*=/release-year/]` year), `section.hlm[data-servers]` embed JSON.

- **Duration chip variance (recorded, not drift):** `span.st--duration` present on 3/4 samples, **absent** on `watch-i-cant-help-it-5` (a 2026-10-17 fresh release renders views/date/year only). Provider `Parse.clockMinutes(null)` → null; LoadResponse duration is optional — code-correct. verify.sh ran the explicit NOTE path (exposure stated here).
- Duration grammar confirmed on present samples: vid2 `st--duration` `2:12:00` → H:MM:SS clock (240-min fixture form unchanged).

### Stream/embed resolution (the deep-pass focus)

Embed hosts served by `data-servers` across the 4 pages: **luluvido.com/e/… (LuluStream), playmogo.com/e/… (DoodStream), voe.sx/e/… (VOE), mixdrop.ag/e/… (MixDrop)** — all four map to registered shared-registry rows (`lulu("https://luluvido.com")` L125, `dood("https://playmogo.com")` L113, `Voe()`, `MixDropAg()`); `Parse.embeds` decode/mapping verified against the live payloads (voe `/e/` strip, luluvid→lulustream mapping not exercised — site serves `luluvido.com` directly, registry row handles it).

| Host | plain curl | browser diagnosis (headless Chromium) |
|---|---|---|
| mixdrop.ag/e/gjnzm6vgtwwlr8 | 200 → mxdrop.click real player page | — |
| voe.sx/e/rrosv2vp6vfs | 200 (JS-redirect shell, VOE-normal) | — |
| luluvido.com/e/g1rbir4hiuac | **403** `cf-mitigated: challenge` ("Just a moment…") | challenge **auto-passes**; 51 KB LuluStream player shell serves |
| playmogo.com/e/6vowpgb0uwzp | **403** `cf-mitigated: challenge` | challenge **auto-passes**; DoodStream player serves — **correct movie title** ("I Cant Help It 5 - DoodStream.com") |

- **MixDrop chain resolved end-to-end** (plain tier): embed page 200 → Dean-Edwards packed script → `wurl` → `*.mxcontent.net/v2/<code>.mp4?s=…` per video ×3 → **206 `video/mp4` each**, distinct paths across videos. (Tokens are single-session — live codes, not committed.)
- luluvido/playmogo full mp4 resolution was not mechanically provable from this probe IP (TLS-impersonation unavailable; the challenge is the known datacenter-IP class). Per spec §2 this is **`Suspected`, never a standalone finding** — residential differential decides. Standing evidence carries: the same luluvido host fully resolved to a 200 m3u8 at the TLS tier in the round-14 pass (FINDINGS-598 Mangoporn section, shared adapter), and this run's browser tier proves both hosts alive and serving the correct player content.
- `Parse.embeds` PostVideo mapping, download-row exclusion (Rapidgator/NitroFlare are separate anchors), and `distinct()` dedupe all exercised live — no variance.

## Verdict

**`ok` retained** — every provider-facing surface healthy on the `.org` origin; no confirmed condition; the fix-policy term never fired. PandaMovies stays **Chronic** (`chronic: true`, `drift_confirmed: 4`): the removal-candidate decision stays with maintainers — this run's evidence supports retention (grammar holds across all faces 3 deep-passes in a row: 2026-10-13 fix, 2026-10-14 clear, 2026-10-17 re-confirm).

## Gates

- `./gradlew bootstrapCloudstream` → PASS (mavenLocal populated, sha-pinned)
- `./gradlew PandaMovies:test` → **PASS** (grammar fixtures + shared tests green)
- `./gradlew PandaMovies:make` → PASS (`.cs3` builds; no code change, version stays 5)
- `.pi/skills/verify-provider/scripts/verify.sh` → **PASS** — search ×2 (27/27 no dups), home ×2 via the provider-effective scoped selector `section.hb--main article.card` (27/27 no dups), quicksearch absent (explicit FINDINGS note), video ×3 with `section.hlm[data-servers]` matches, `--stream-url` chain evidence 206 video/mp4 ×3 distinct, related 12/page, tags/actors/year on 3/3, check-5 search↔load agreement (lesbian-analingus card ↔ breadcrumb), LoadResponse completeness
- `findings-lint (check-findings.sh)` → **PASS**

## Findings lifecycle

- **0 new issues filed** — dedup against standing #463 (CF origin-down record, CLEARED — not refiled: the only challenge class observed is the datacenter-IP embed-host kind, `Suspected` per spec §2) and closed #555 (grammar record — holds, not refiled).
- `audits/findings.json`: **only the PandaMovies entry** updated (surfaces incl. quick_search endpoint migration + stream tier note, `last_deep` → 2026-10-17) + one `last_runs` entry (run27). Historical counters (`drift_confirmed: 4`, `chronic: true`) untouched.
- `PandaMovies/FINDINGS.md`: deep-pass addendum (stale searchwp quickSearch doc superseded; embed-host CF class + browser-tier diagnosis; duration-chip variance).
- No Kotlin changes, no CI changes, no raw probe HTML committed.
