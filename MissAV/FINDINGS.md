# MissAV — probe + fix evidence (issue #274, 2026-09-10)

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
