# FINDINGS-535 — xmoviesforyou.com card-selectors refresh (fix run)

Issue #535: listing selectors dead on new markup (home + search return 0); watch page OK.
Fix-destination probe, 2026-10-09, branch `devloop/issue-535`. Probe instrument: plain curl
= **403 Cloudflare challenge** on every URL (matches issue + FINDINGS-524 run-8 note and the
Film1k precedent); all evidence below is **TLS-impersonated curl** (`site-probe/scripts/
impersonate.sh`, curl_cffi chrome) — same plain HTTP, chrome TLS fingerprint. As of this
probe plain curl reached nothing, so verify.sh in this run was driven through the same
impersonation tier via a `curl` PATH shim (recorded in the verification note).

One discrepancy with the issue's audit note, recorded as measured: home page 1 still serves
**24 cards on the old `a.group.flex.flex-col` grammar** (grid section) — the old selector is
not 0 on today's home. It is dead on **search** (which redirects to a rebuilt `/new-search`
with a different card shape) and misses the home **hero carousel** (`a[data-video-card]`,
30 anchors / 10 unique slugs). The audit's "0 matches" held for search and for the carousel
row; the additive fix below covers all three shapes either way.

## Engine fingerprint

Custom Laravel-style app (Tailwind utility classes, `material-symbols-outlined` icon font,
`const postId = "…"` inline JS, JSON REST at `/api/related/<postId>`). No CMS theme match.
Hero-carousel cards carry a `data-carousel-repeat` attribute; posters are direct CDN file
URLs at `https://xmoviescdn.online/<yyyy>/<mm>/<slug>-xmoviesforyou[-<hash>].jpg|webp`.

## Search

- `GET /search?q=faketaxi` → **302 → `/new-search?q=faketaxi`** → 200 (24 cards). The app's
  NiceHttp follows redirects, so the provider URL string can stay; recorded for evidence.
- Old card grammar `a.group.flex.flex-col`: **0 matches** on the new-search page.
- Live card: root anchor `a.card` →
  ```html
  <a href="/faketaxi-mackenzie-page-do-you-know-who-i-am" class="card">
    <div class="thumb"><img src="https://xmoviescdn.online/2026/01/….jpg" alt="[FakeTaxi] …" width="480" height="270"><span class="runtime">HD</span></div>
    <div class="meta"><div>
      <h3 class="title" title="[FakeTaxi] Mackenzie Page (Do You Know Who I Am? / 01.29.2026)">…</h3>
      <div class="sub"><span class="studio">FakeTaxi</span> &bull; <span>29th January 2026</span></div>
    </div></div>
  </a>
  ```
- Extraction: title = card `h3` text (`h3.title[title]` attr carries the same string),
  href = card anchor's own href, poster = first `img src` (real CDN file, no `data:` URIs,
  no lazyload attribute on this theme).
- Transcript: `impersonate.sh 'https://xmoviesforyou.com/search?q=faketaxi'` → 200, 24
  `a.card` anchors, `h3.title` count 24.
- Search↔load agreement: `/faketaxi-mackenzie-page-do-you-know-who-i-am` is returned by
  `/search?q=faketaxi` and loads; its h1 matches the card title.

## Quick search

**No distinct quick-search endpoint.** Probed `/api/search?q=fake` → 404,
`/api/suggest?q=fake` → 404, `/new-search?q=fake&format=json` → 200 but HTML (no JSON
surface). Provider keeps `hasQuickSearch = false` (unchanged — the provider never declared
it, the app falls back to full search).

## Homepage

Rows the provider's getMainPage renders are the category pages (unchanged list) plus the
root; card grammar per surface, measured 2026-10-09:

| surface | card grammar | count | notes |
|---|---|---|---|
| `/` (page 1) hero carousel | root anchor `a[data-video-card]` | 30 anchors / **10 unique slugs** (items repeat across carousels — dedupe by href required) | `h3` text title, first `img src` poster |
| `/` grid section | root anchor `a.group.flex.flex-col gap-2` | 24 | same extraction; **old selector still alive here** |
| `/?page=2` | `a.group.flex.flex-col` only | 24 | carousel absent on p2+ |
| `/category/<slug>` | `a.group.flex.flex-col` | 24 | grammar unchanged |
| combined selector | `a[data-video-card], a.card, a.group.flex.flex-col` | matches all surfaces | shapes share `h3` + first-`img` + root-anchor href |

None of the shapes need sub-selectors beyond those: title from card `h3` text, poster from
card's first `img`, link from the root anchor itself. Existing provider title post-step
(bracket-studio strip `[.*?]`) still applies and matches live titles
(`[Pawged] Kali Roses (PAWG Kali Roses / 09.25.2026)` → `Kali Roses (PAWG Kali Roses / 09.25.2026)`).

## Video pages (sampled 5, varied listings)

All 200 via TLS tier. Selectors in the provider's `load()` all match live:

| page | listing it came from | h1 title (matches card) | og:image | date chip `calendar_month` parent | `/category/` links | `/pornstar/` links | stream anchors (host set) | `const postId` |
|---|---|---|---|---|---|---|---|---|
| /pawged-kali-roses-pawg-kali-roses | home hero carousel | `[Pawged] Kali Roses (PAWG Kali Roses / 09.25.2026)` | cdn webp | «25th September 2026» | Interracial, Tattoo, Blonde | 1 | streamtape | 602771055391678 |
| /publicbang-serena-gomes-anal-in-public-with-serena-gomes | home grid | same-source title | cdn webp | date chip present | present | present | streamtape | 440669493221225 |
| /faketaxi-mackenzie-page-do-you-know-who-i-am | search | `[FakeTaxi] Mackenzie Page (…)` | cdn jpg | date chip present | present | present | mixdrop.ag/f/8lqq9w3li83l4o + streamtapeadblockuser.art/e/P77dQ8KKMZS0601 | **758927** (short-form posts) |
| /5kporn-mandy-spears-mandy-speared | category | title ok | cdn webp | present | present | present | streamtape | 289147677924019 |
| /bjraw-maisey-monroe-maiseys-throat-training | related API | title ok | cdn webp | present | present | present | streamtape | 660864140751377 |

- title: `h1` (single, first). poster: `#player img src`… measured live page carries
  `meta[property=og:image]` = real CDN file; `#player img` not re-confirmed on every page —
  the provider's `?:` chain (`#player img ?: og:image`) still resolves on all 5 (og:image
  fallback), and on pages where `#player img` exists it is the same CDN asset family.
  no `data:`-URI posters on any sampled page.
- plot: `div.prose p` present on sampled pages ✓.
- year: `span.material-symbols-outlined:contains(calendar_month)` parent text carries
  absolute dates («25th September 2026») ✓ (regex `\b\d{4}\b` still finds the year).
  Note: h1 title bracket also carries a `/ mm.dd.yyyy` date — unchanged behavior.
- **Site does not expose duration**: no minutes/PT-duration value anywhere on the sampled
  watch pages (chips carry date + download badges; the search-card `span.runtime` holds the
  quality badge «HD», not a length). Provider populates no duration — consistency confirmed
  rather than a gap. Runtime anchors STREAMTAPE/MIXDROP/DOODSTREAM are the download chips,
  not a duration.
- tags: 3+ `a[href*='/category/']` anchors per page (chip-labeled with
  `category` icon inside an overlay panel) ✓. actors: `a[href*='/pornstar/']` ✓
  (sampled pages 1 anchor each — studios mostly epononymous-scenes; not site-wide absence).

## Related videos

`GET /api/related/<postId>` (works **with or without Referer** — replayed both) → 200 JSON:
`{"data":[{"id",…,"title","slug","thumbnail_url",…}, …]}` — 12 items on the sampled page.
Provider's `RelatedData`/`related` flow unchanged and live. Important measured wrinkle:
**postId is NOT a stable-width integer** — `758927` on the faketaxi page vs
`602771055391678` (15 digits) elsewhere; and the related entry's own
`const postId` equals the listed item's `id` — the provider's `postId.isNotEmpty() &&
postId.length > 3` guard already tolerates short ids ✓ (no change needed; recorded because
`length > 3` is the only guard).

## Stream sources (per video page)

Embed-page anchors inside the `Download` chips (`STREAMTAPE`/`MIXDROP`/`DOODSTREAM`), all
absolute hrefs on the watch page DOM — the provider's `loadLinks` ladder
(`a[href*='streamtape.com'], a[href*='mixdrop'], a[href*='dood'], a[href*='bigwarp']`)
matches the sampled pages; extractor adapters are the repo's shared `HostRegistry`
(streamtape / mixdrop / dood). One nuance re-proven live: the faketaxi page carries
`streamtapeadblockuser.art/e/…` (an alternate streamtape host) — the inline selector
`streamtape.com` only catches the `.com` variant there. **Not changed this run**: the page
also serves a `.com` link, and widening the selector is an extractor-registration question,
not a listing fix; recorded for the next audit.

Content-type chain (m3u8/mp4) is served by the extractor hosts, not by this site — embed
hosts were not replayed this run; verification used the anchor-extraction chain plus the
FINDINGS chain evidence, per verify-provider `--stream-url` override semantics.

## Headers / referer

- Site pages: **plain curl = 403 CF challenge**; TLS-impersonated chrome = 200. No cookie
  set/action needed beyond browser-TLS at these URLs; no age gate. NiceHttp's OkHttp TLS
  stack is established compliant with this site (the provider's load() flows worked in-app
  per the issue itself).
- `/api/related/<id>`: works without Referer (replayed bare → 200).

## Pagination

- category pages (the main rows): `?page=N` — `/category/milf?page=2` → 200, 24 cards,
  **0 overlap** with page 1. Past-last: `/category/milf?page=999` → 200 with **0 cards**.
- search: `/search?q=X&page=N` → follows redirect to `/new-search?q=X&page=N` → 200, 24
  cards per page, **0 overlap** page1/page2 (`/search?q=faketaxi` ↔ page 2 sampled).
  Past-last: **HTTP 400** (not 404, not empty) on page 999.
- home hero carousel: page-1-only (carousel absent at `?page=2`); home grid paginates as
  above. Provider's flat `hasNext = true` falls back to an empty final row — tolerated
  (pre-existing behavior, not this issue's scope), except the search **400** becomes an
  empty/failed final page request in-app — same class of tolerance, unchanged here.

## Risks / blockers

- **Cloudflare challenge on plain-HTTP fetches** (403 at every probed URL — home, category,
  search, watch, API). TLS-impersonated clients succeed everywhere, and the issue reports
  in-app load() flows working, so the in-app NiceHttp stack passes (no Blocked verdict).
  Probe region: US datacenter runner IP (same instrument as FINDINGS-524 run 8).
- Past-last-page behavior: category empty page / search 400 — see Pagination.
