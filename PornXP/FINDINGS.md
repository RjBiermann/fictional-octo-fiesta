# FINDINGS — pxp.news (2026-09 audit + 2026-09 fixes for #151, #235, #330, #331)

## Verdict: OK (fixed #330 — search: `?q=` is ignored by the live site; real endpoint is `/tags/<query>`)

## Root cause of #330 (search returns the homepage grid)
The static site ignores the `q` parameter — `?q=<anything>` always serves the homepage
newest-grid (36/36 data-id overlap with a plain `/` fetch, proven with `q=zzzznothing`).
Search is JS-driven: `/2.js` rewrites the form submit to `/tags/<encodeURIComponent(q)>`.
The provider was fetching `$mainUrl/?q=$query` → every search returned the unfiltered
newest list.

Fix: `search()` fetches `$mainUrl/tags/<query>` (`URLEncoder.encode` with `+`→`%20` to
match the site's own links); `page > 1` appends `?page=$page`.
- `/tags/Peggy%20DeVille` → exactly 1 card; `/tags/ATKGirlfriends` → 36; `/tags/Peggy` → 19
  (tag-prefix matching; partial queries work).
- Pagination: `?page=N` on the tag URL returns fresh cards (p2: 36 unique data-ids,
  0 overlap with page 1 — proven on `/tags/ATKGirlfriends`).
- **hasNext must come from `#pages`, not `true`:** a single-result tag page carries an
  empty `<div id="pages">  </div>` — its `?page=2` fetch falls back to the generic
  latest grid (still 200 + cards), which would poison pagination with unrelated/overlapping
  tonight listings. The **last** page of a multi-page tag also still links back to earlier
  pages, so `#pages a[href*=page]` is not a next-page test either. `Parse.searchHasNext`
  now returns true only for the site's own `>` next control
  (`#pages a` text `>`), which is absent on both the single-result page and the real last
  page. Patched live: `/tags/ATKGirlfriends` p119 (5 cards, no `>`) is the end; its
  `?page=120` serves the generic grid — the previous `#pages a[href*=page]` check fetched
  it anyway. p1/p2 of `/tags/ATKGirlfriends` share 0 data-ids.
- Card shape on tag pages is identical to the homepage grid (`.item_cont` /
  `.item_title` / `.item_thumb img`), so `searchCard` selectors are unchanged.
- Red→green Parse tests: `PornXP/src/test/kotlin/com/rjbiermann/ParseTest.kt` with
  real `/tags/` fixtures in `PornXP/src/test/resources/` (card parse via
  `SearchCard.parse`, `searchUrl` encoding, `searchHasNext` from `#pages`).

## Root cause of #331 (duration exposed on cards, never populated)
The video page renders duration NOWHERE (no JSON-LD/meta/no text on 5 sampled pages —
`grep -io duration` empty). Duration exists only on listing cards: `<div class="item_dur">1:01:12</div>`
(site clock `H:MM:SS` / `MM:SS`, every `.item_cont` card; homepage has 36). The current video is
NOT in its own related grid (`/videos/141232241817` → 0 self-href matches), so load()'s only source
is the card the user came from. Fix: `Parse.clockMinutes` (card text → CloudStream minutes:
`1:01:12`→61, `34:11`→34, `09:57`→9; `"`/`HD`→null) and the provider's cards are emitted with the
minutes piggybacked on the search-response url ("`<href>#<minutes>`"); `load()` / `loadLinks()`
strip the marker with `substringBefore('#')` and set `duration`. Videos opened from no card
(e.g. deep links) have no duration — site never exposes it there. Red→green in
`ParseTest.kt` (`clock_minutes`; fixture card `.item_dur` round-trip) against the existing
`tag-peggydeville-p1.html` fixture (`1:01:12`→61). Version 6 → 7.

verify.sh runner notes for #331 (site-data artifacts, not provider bugs):
- video-title selector must be `title` (FINDINGS pre-existing limit a); `.player_details h1`
  is unreachable to the harness regex-DOM (nested-block truncation; the header banner h1
  shadows it, so every sampled load "titles" as the backup-domain banner → cross-video dup).
- home `?page=1` + `?page=2` shared a literal-title pair today (two distinct videos titled
  "EMY ROSE loves it when she's being fucked in DAP, anal, hard anal", ids
  5248769x on p1 and p2) — the known site-level same-title grid artifact; sampled p1+p3
  instead, 0 href/title dups.

## Search-SAMPLE caveat for verify.sh (runner, not provider)
The harness dup bar (distinct titles across/within search pages) fails on two live-data
patterns recorded here so it isn't chased again:
- Series tags repeat literal titles: `/tags/ATKGirlfriends` pages are full of distinct
  videos all titled `Schoolgirl`, `POV Sex`, `Trooper POV`. Use a talent/series tag whose
  result titles are unique instead — current sample `/tags/DaughterSwap` p1+p2.
- Two distinct newest-grid videos share the title `Maria Alfonsina` (ids 24279286,
  64989072) and appear on generic listings — any sample that sweeps the newest grid
  today trips a within-page title dup.
- Harness NOTEs (structurally unavoidable on this site, same class as the #235 notes):
  per-video poster/plot extraction is hardcoded to a `content` attribute which pxp.news
  does not render (poster lives on `<video id="player" poster=…>`, plot in `#desc` text);
  and check 5 search↔load agreement cannot pass — the only harness-readable per-video
  title is `<title>` (`"Title – PornXP"`), whose suffix breaks normalization (no og
  metas exist). The provider itself reads `.player_details h1` / `#player[poster]` /
  `#desc` correctly.
- Related/recs on video pages: the header banner `<a>` headlines the video's own mirror
  (`//porn-xp.eu/videos/<id>`), so `a[href*=videos]` self-hits the check — use
  `a[href^=/videos]` (banner href is scheme-relative, real cards are root-relative).

## Root cause of #235 (load() title wrong)
pxp.news video pages now carry a SECOND `<h1>` in the header — a backup-domain notice:
```
<h1 style="font-size:30pt">New Backup Domain: <a href="//porn-xp.eu/videos/80377921180">porn-xp.eu</a></h1>
...
<div class="player_details"><h1>Lusty Girlfriends</h1>...
```
`selectFirst("h1")` matched the banner, so load() titled every video "New Backup Domain: porn-xp.eu". Search cards were always correct (they use `.item_title`), so posters and titles disagreed. Fix: select `.player_details h1`.

Note: the banner `<a href>` headlines `/videos/<own id>` — never use bare `a[href*=videos]` for related/recs on this site.

## Root cause of #151 (blank homepage feed)
The card markup changed: the `<a href="/videos/...">` is now an **ancestor** wrapping
`.item_title` (title is a plain div inside the anchor). The provider matched
`.item_title a`, which matches nothing → every card maps to null in `toSearchResult()`
→ empty HomePageList → blank feed.

Evidence (home + search + related all share this shape):
```
<div class="item_cont"><div class="item preview" data-id="..."><a href="/videos/434655441290">
  <div class="item_width"><div class="item_height"><div class="item_thumb"><img class="item_img"
  src="/43465544641290.jpg" ...>
  <div class="item_title">Monica - Hardcore</div>
```
34 of 36 thumbnails carry `class="item_img lazy"` with the real poster in `data-src`
(spinner in `src`) — already handled by the provider.

## Engine fingerprint
Static server-rendered HTML (`text/html`), jquery + yall lazy-loader. No JS listing.

## Search
- **BROKEN → FIXED (#330):** `?q=`/`?s=` params are IGNORED by the live site — they return
  the homepage newest-grid (36/36 overlap with `/`). The real search endpoint is
  `/tags/<urlencoded query>` (form rewritten by `/2.js`), paginating with `?page=N`; see
  the #330 root-cause section at the top. Old note kept for the record:
  `https://pxp.news/?q=red` → 200, 36 `.item_cont` cards — but unfiltered/unrelated to the query.
- Single-result tags (`/tags/Peggy%20DeVille`): 1 card, empty `#pages`; `?page=2` falls back to
  the generic latest grid — don't paginate those; `searchHasNext` reads the site's `>` next
  control in `#pages` (absent on the single-result page and on a tag's real last page).

## Video pages
Probed ≥5 pages across home/search/paginated listings, e.g. `/videos/80377921180`,
`/videos/629192201007`, `/videos/24078228504`, `/videos/322543611680` — all 200, `h1` title,
`#player video poster`, `#desc` (date + description), `.tags a` tags, related `div.item_cont`.

2026-09 recheck (#235): title source is `.player_details h1` (not the first `h1`); poster is
`<video id="player" poster="/....jpg">`; og:/JSON-LD metas are NOT rendered — video-page
identity fields live only in this element markup. Manual agreement for `/videos/322543611680`:
listing card title == load `h1` == "Alessia & Juliana Friends spanking bums".

## Related videos
Video pages contain 25–36 additional `.item_cont` cards → recommendations.

## Stream sources (per video page)
`#player source` (1–3 per page), e.g. issue video `/videos/80377921180`:
```
<source src="//ce.pornxp.sh/wZpMua0e0R5c9nHnh8H2gG10w/8037792185/360.mp4" title="360p" type="video/mp4">
<source src="//ce.pornxp.sh/VJj1OH06EdvJ9Ohc88cv0sz_w/80377921215/720.mp4" title="720p" type="video/mp4">
```
verify.sh (5 URLs): `GET stream → 206 video/mp4` each. Scheme-relative `//host` URLs —
fixUrl() resolves them.

## Headers / referer
No auth/ referer required; site serves 200 to any UA (no Cloudflare). `referer = mainUrl`
kept on links.

## Pagination
`?page=N` appended to listing URL; next pages return fresh cards (20–36).

## Risks / blockers
- verify.sh harness limits on this site (not provider bugs, evidence in PR): (a) per-video
  title/poster/plot extraction is hardcoded to a `content` attribute (og-meta), which pxp.news
  does not render → check 2 "video title missing" is structurally unavoidable; (b) the
  regex-DOM card parser truncates the nested `div.item_cont > a > ...` card at the first
  `</div>`, so unattributed card titles fall back to card text (durations) → spurious
  "duplicate" hits on common durations; (c) `?q=`/`?s=` search returns the same recent-feed
  card set (site-level — no title filtering), and two distinct videos legitimately share the
  title "Anal & MILFY #02" (/videos/17087088550, /videos/181515081820).
- For the same reason use anchor-based card selectors (`a[href*="/videos/"]`) —
  verify.sh's reader resolves `href` from the matched element's own attributes.
Partially observed: no CF, no age wall, no UA sensitivity (tested android UA + no UA).
Manual evidence collected after passing checks: title fix verified by direct grep of live
HTML; streams 206 video/mp4.
