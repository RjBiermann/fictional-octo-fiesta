# MissAV — probe + fix evidence (issue #274, 2026-09-10; appendix issue #302, 2026-09-11)

## Live probe
- `GET https://missav.live/en/abf-384` → 200, 231 KB (saved: `src/test/resources/missav-video-meta.html`).
- Video meta rows are `div.text-secondary` blocks, each with a label span first:
  - `<div class="text-secondary"><span>Actress:</span> <a ...>Nonoura Warm</a></div>`
  - `<div class="text-secondary"><span>Genre:</span> <a>Slut</a>, <a>Adultery</a>, ... , <a>Av Actress</a></div>`

## Defect (reproduced)
`select("div.text-secondary:contains(actress) a")` matches the Genre div too — the genre
list always contains the link "Av Actress". jsoup check on the saved page:
- buggy selector → `Nonoura Warm, Slut, Adultery, Individual, Ntr, Bukkake, Av Actress` (7 "actors", 6 fake)

## Fix (validated against saved page)
Anchor on the label span's own text: `div.text-secondary:has(> span:containsOwn(actress)) a`
- actress → `["Nonoura Warm"]`
- genre (`:has(> span:containsOwn(genre))`) → the same 6 real genres as before

## Unchanged / PASS
- mainPage dm-ID redirects preserve `?page=N` query — home pagination OK after NiceHttp follows.
- title `h1.text-base`, og:image, og:video:duration, year `time`, plot og:description — correct.
- Stream: packed eval → `/([a-f0-9\-]{36})/` → surrit playlist (surrit 403 from CI = IP gate, verify in-app).
- Recommendations: recombee HMAC POST → 200. Subtitlecat path untouched.

## Appendix (issue #302 — duration units, 2026-09-11)

### Defect
`load()` shipped `og:video:duration` content raw (seconds); CloudStream `LoadResponse.duration` is minutes.

### Live probe (meta = seconds, all 200 with plain UA)
- midv-852 → 7256 s; midv-940 → 9680 s; midv-987 → 8746 s; midv-994 → 8751 s; midv-998 → 9796 s;
  selector `head meta[property=og:video:duration]` matches on all 5 sampled pages (verified via py count = 1/page).

### Fix
Pure `MissAVParse.parseDuration(String?): Int?` (seconds → `s/60`, null/blank-safe), wired into `load()`;
shares the repo `s/60` convention with `JsonLdParse.minutes`. Unit test `MissAVParseTest` covers
7256→120, 120→2, sub-minute/absent/garbage → null.

### Verify follow-up (verify.sh run)
- search ×2 pages, home ×2 pages: 200, 12 cards (fetch + selector count pass; card-distinct/agreement
  assertions silent —/tmp/verify_pydom.py `blocks()` regressions on Alpine pages whose attributes contain `>`
  (e.g. `x-init="$nextTick(() => {initLozad()})"`): its `[^>]*` open-tag regex truncates and swallows the card
  divs on the search/home pages. Same selector matches 12/page (count check OK). Tooling limitation, not a
  provider defect.
- video pages ×5 (852/940/987/994/998): 200, title/poster/plot/tags/actors/year present on all.
- duration NOTE from script is the same tooling quirk: check 2a reads inner text (`field … text`),
  meta tags carry no inner text. Selector + content proven manually above.
- Streams: packed eval in page → `/([a-f0-9-]{36})/` → surrit playlist per video (5 distinct UUIDs
  extracted live via the same transform `getAndUnpack` applies). surrit serves the runner
  `HTTP 403 text/html — "Attention Required! | Cloudflare"` on every path/UA (consistent with the
  audit's ×6 attempts incl. IPv4/HTTP1.1/cookies): runner-IP block, extraction schema correct.
  **Blocked from CI — verify stream playback in-app.**

## Appendix (issue #327 — "Newly Added" row pagination duplicates, 2026-09-11)

### Defect
mainPage row 4 was `$mainUrl/en/new?sort=published_at`. With `sort=published_at`, page 2 echoes
6 of page 1's 12 cards (abf-383, hmn-910, love-017, mikr-122, siro-5734, snos-299 — stable across
fetches, p2∩p3 = 0, so duplication is scoped to the sorted URL). verify.sh duplicate-home bar FAILs.

### Live probe
- `/en/new?sort=published_at&page=1|2` → 12 cards each, **p1∩p2 = 6** (reproduced).
- `/dm539/en/new?page=1|2` (unsorted, after 301 /en/new → /dm539/en/new) → 12 cards each,
  **p1∩p2 = 0**. Selector `div.grid.grid-cols-2 > div, div.thumbnail.group` matches both pages.

### Fix
Row URL → `"$mainUrl/en/new" to "Newly Added"` (one line). Version 14 → 15. No selector change;
NiceHttp follows the dm-redirect and the `?page=N` query survives (unchanged since #274 check).

### Verify evidence
- `MissAV:make` + `MissAV:test` green (duration/tags/actors regressions; no new test — the fix is a
  URL constant, a unit test would only re-assert the string literal).
- verify.sh: same pydom tooling FAILs as #302/#328 appendixes (selector regex returns -1 / crashes
  on Alpine `x-init="...>..."` attributes — tooling, not provider). Manual mechanical checks on the
  provider's real selectors instead:
  - home new row p1/p2: 12 + 12 cards, **p1∩p2 = 0** ✓ (duplicate-home bar)
  - search milf p1/p2: 0 overlap ✓
  - video ×5 (fc2-ppv-4963689, milf-091, roe-469, jjbk-086, abf-384): 200, h1 title, packed eval +
    surrit uuid present on all
  - Streams: surrit 403 Cloudflare IP gate from CI as before — **verify stream playback in-app**.
- No other mainPage row changed.

## Appendix (issue #328 — Actor: row dropped, 2026-09-11)

### Live probe
- roe-469 → 200; meta rows include BOTH `<span>Actress:</span>` (Toko Yoshinaga) and
  `<span>Actor:</span>` (Tooru Ozawa). "actress" does not contain "actor" as substring, so the
  #274 selector `:has(> span:containsOwn(actress))` matched only the Actress row — the male
  actor row was dropped. Same rows present on midv-852/940 (actress + actor); milf-091 /
  jjbk-086 pages have no people rows at all (site omits them there, not a parse gap).

### Fix
parseActors selector now `div.text-secondary:has(> span:containsOwn(actress)) a, div.text-secondary:has(> span:containsOwn(actor)) a`
(one jsoup select, document order preserved). TDD: fixture gained an Actor row; red test
`actor row is included alongside actress` failed pre-fix, green after. Version 13 → 14.

### Verify evidence (2026-09-11)
- `MissAV:make` clean; `MissAV:test` green (2 actors tests + tags/duration regression).
- search ×2, home ×2: 200, ~11 cards each, page1∩page2 = 0 (verify.sh pydom again returns -1 on
  the `div.grid.grid-cols-2 > div` selector — same tooling limitation as the #302 appendix;
  manual regex count + dedupe on the saved HTML used instead).
- video pages ×5 (roe-469, midv-852, midv-940, milf-091, jjbk-086): 200; title/year/duration
  (120/161/138/117/193 min)/plot present on all; actors present on the 3 pages that have
  people rows (both names on roe-469 and midv pages), absent where the site has none.
- Streams: packed eval → surrit UUIDs extracted live (5 distinct), surrit serves the runner
  403 text/html (same Cloudflare IP gate as #302). **Blocked from CI — verify stream
  playback in-app.**
- verify.sh check 5/6 FAILs are tooling (pydom field/cards can't read Alpine-flavored search
  cards; load-response string passed whole = "unknown field") — code half asserted manually:
  poster/year/tags/plot/duration/recommendations/addActors all populated in load().
