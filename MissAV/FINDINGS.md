# FINDINGS — missav.live (2026-09 audit / 2026-01 issue #129 refresh)

## Verdict: OK (CF risk on stream CDN — surrit.com 403s runner IPs)

## Search
- `https://missav.live/en/search/red` → 200 with cards (`div.thumbnail.group` / `grid grid-cols-2`) linking `/en/<slug>`.
- Home `dm170/en/weekly-hot` → cards `div.thumbnail.group` with video slugs (`/en/snos-334`); `dm169` in provider is 301-redirected to `dm170` (dmNK counter rotates; NiceHttp follows). Real slugs from listings prove URL shape: `/en/cawb-022`, `/en/dvaj-756`, `/en/jac-241`, `/en/fc2-ppv-4973050`, `/en/sone-740`.

## Video page / stream
- Video page (all 5 probed) contains packed eval; unpack yields `https://surrit.com/<36-char-uuid>/playlist.m3u8` (snos-334: `c659215e-5e61-4cd1-9534-3eed843d768a`). Provider flow (`getAndUnpack` → `[a-f0-9-]{36}`) matches live page.
- **No server-rendered `og:video:url` / raw `<source>` / `.m3u8` literal in HTML** — stream URL only exists after JS eval. verify.sh stream extraction therefore cannot find it from server HTML; stream validates in-app only.
- **surrit.com returns 403 CF challenge for this runner IP** (all referer variants).

## Metadata (Data-complete fields) — issue #129 evidence
- Plot: `<head><meta property="og:description" content="…">`; also `og:title`, `og:image` (`https://fourhoi.com/<slug>/cover-n.jpg`), `og:video:release_date` (`2026-08-07` → year), `og:video:actor`, `og:video:director`.
- Duration: `<head><meta property="og:video:duration" content="9575">` (seconds; og:video + player both expose it).
- Year: `<time datetime="2026-08-07T00:00:00+08:00">`.

## Related / recommendations
- **Related section is NOT server-rendered.** The watch-next rail is Alpine.js fed by Recombee (`resec: watch-next`, `x-for`, `div.thumbnail.group` template shells only — 1 per page).
- Data source (leaked in site's own `app.1aad5686.js`):
  - `window.recombeeClient = new ApiClient("missav-default","Ikkg568nlM51RHvldlPvc2GzZPE9R4XGzaH9Qj4zK9npbbbTly1gj9K4mgRn0QlV",{baseUri:"client-rapi-missav.recombee.com"})`
  - `POST https://client-rapi-missav.recombee.com/missav-default/recomms/items/{dvd_id}/items/?scenario=…&frontend_timestamp=…&frontend_sign=<HMAC-SHA1 of /missav-default<path> using above token>`
  - body: `{targetUserId, count, scenario, returnProperties:true, includedProperties:["title","duration","dm"]}`
  - Replay transcript (replicated from Kotlin-mirror logic, 2 items shown):
    `POST …/recomms/items/snos-334/items/ → 200 {"recommId":"a1537576…","recomms":[{"id":"erk-062-uncensored-leak","values":{"title":"れのん","duration":5965,"dm":0}},{"id":"skmj-194","values":{"duration":10391,"dm":32}},…]}`
  - URL shaping (site JS `itemUrl(item)`): `dm ? /dm{dm}/en/{dvd_id} : /en/{dvd_id}`; poster `https://fourhoi.com/{dvd_id}/cover-t.jpg`.
  - Recommendation target page loads: `GET /en/skmj-194 → 301` (NiceHttp follows; slug resolves).

## Headers / referer
- Plain `Mozilla/5.0` UA suffices for pages, search, and the recombee API. Stream CDN requires browser env (CF-challenged for runner).

## Pagination
- `?page=N` on search/listings (provider already implements).

## Risks / blockers
- surrit.com Cloudflare-challenges datacenter IPs — stream heard-URL check cannot run in CI; verify in-app (on-device should pass).
- Bundled recombee JWT (token in public JS) may rotate — recommendations would then degrade to empty list (per-item try/catch keeps load() from breaking).
