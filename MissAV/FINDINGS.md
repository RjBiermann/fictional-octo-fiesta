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
