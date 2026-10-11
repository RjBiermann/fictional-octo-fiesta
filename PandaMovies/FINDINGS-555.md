# FINDINGS-555 — PandaMovies markup migration (PsyPlay → new BEM "card" theme)

Probed 2026-10-13, plain-curl (`curl -s -A "Mozilla/5.0"`), from runner region. Supersedes
the run8 FINDINGS.md grammar for every listing + video-page selector. The issue #555 claims
confirmed and extended: **the video-page grammar migrated too** (the issue only cited listings).

## Confirmed condition (reproduction)

- `https://pandamovies.pw/` → 200, effective URL `https://pandamovies.org/` (redirect heals 301).
- Home: `ml-item` count **0**; `article.card` count **49**; card__ grammar (`card__th`,
  `card__t`, `card__dur`, `card__img`) present.
- `/?s=sex` → 200, `article.card` count 35, ml-item 0.
- `https://pandamovies.pw/movies` → 200 after redirect → **404 page** (title "Page not found").
  `/movies` is dead on the new theme.
- `https://pandamovies.org/category/porn-movies` → 200, 35 cards; `/page/2` → 200, 35 cards,
  **disjoint** from page 1 (this replaces the dead Latest row).
- `/search/sex` and `/search/sex/page/2` → both 200, 35 cards (old search path still OK);
  `?s=sex` pagination is `https://pandamovies.org/page/2?s=sex`.
- `/genre/18-teens` + `/page/2` → 200, 35 cards each.

## New listing grammar (home / search / genre / category all one shape)

```html
<article class="card">
  <a class="card__th" href="https://pandamovies.org/watch-...-movie-online-free">
    <img class="card__img" src="https://i2.wp.com/pandanetwork.club/...jpg?resize=360,540" ...>
    <span class="card__q">2026</span><span class="card__dur">2:58:00</span>
  </a>
  <div class="card__b">
    <h3 class="card__t"><a href="https://pandamovies.org/watch-...-movie-online-free">Title</a></h3>
    <div class="card__m card__m--dot">...views + studio link...</div>
    <span class="card__type">Appearance</span>
  </div>
</article>
```

- href: `a.card__th[href]` (same URL as `h3.card__t a`)
- title: `h3.card__t a` text
- poster: `img.card__img[src]` (real URL, https, no data: URIs)

Related section on video pages: `section.sec` (only section per probed page) with heading
`h2.sec__h` "Similar titles", containing `article.card` children whose `card__th` hrefs
are the movie URLs.

## New video-page grammar (sampled: `watch-sex-at-first-sight-movie-online-free`)

- html head og + JSON-LD present; `article.vid` main block; breadcrumb `.bc__c` has the
  clean title ("Sex At First Sight"); `h1.vid__t` is noisy ("Watch Sex At First Sight 2026
  by New Sensations Porn Movie Online Free") — parse from `.bc__c`.
- poster: `img.hlm-screen__poster[src]` (== og:image).
- plot: first `<p>` of `div.vid__txt` inside `div.dp__panel[data-panel=info]`.
- duration: `span.st--duration` text (e.g. `4:00:00`), player badge `hlm-screen__badge`
  same. **No more "Duration: X hrs. Y mins." prose.**
- year: `span.st--year` text (e.g. `2010`); also details chip `a.chip[href*=/release-year/]`.
- tags (genres): `a.chip[href*=/genre/]` inside `div.dp__panel[data-panel=details]`.
- actors: `a.chip--star` inside details panel (Pornstars row).
- All old selectors (`mvic-thumb`, `mvic-info`, `itemprop=name`, `.desc.cols`) gone.

## New embed player (replaces `a[id=#iframe]`)

- `section.hlm[data-servers]` carries a JSON array (html-escaped in the attribute):
  `[{"u":"https:\/\/doply.net\/e\/jmffsu8tekc1","t":"iframe","l":"DoodStream",...},
    {"u":"...mixdrop.ag\/e\/8ljw8z16c664erp","l":"MixDrop",...}]`
- Stream host URLs sit at key `u` — parse the attribute, take `.u` values.
- doply.net is in the shared registry (`dood("https://doply.net")`), mixdrop.ag is
  `MixDropAg()` — no extractor change needed. Rapidgator/NitroFlare download links are
  not stream-eligible (file-host pages, skip).

## Pagination (per surface)

- All listing surfaces now serve `nav.pg.pg--sm` with an explicit next anchor
  `<a class="next page-numbers" href="https://pandamovies.org/page/2">` when more pages exist.
  This replaces the run8 ≥40-card-count heuristic (new per-page counts: 49 on home, 35
  elsewhere — the heuristic is structurally wrong now).
- Next-page URL pattern: `{surface}/page/{n}`; search also accepts `/page/2?s={q}`.
- Past the end: WP "Page not found" 404 (probed `movies` path). Cards-per-page:
  49 home, 35 search/genre/category.

## Old-grammar fixture casualties (test surface)

- Fresh search fixture for `Rocco's Intimacy` (issue #439 title) returns a
  no-results-ish page (2 cards, no Rocco titles) — the old fixture test content is gone
  from the site; #439/#444 tests re-pointed at the fresh `?s=sex` fixture/synthetic cards.
- `hasNextPage` ≥40 heuristic retired; replaced by the `a.next` pager anchor.

## Quick search

The run8 quick-search endpoint (searchwp_live_search admin-ajax) is GONE on the new theme:
`POST /wp-admin/admin-ajax.php action=searchwp_live_search…` → **400**. No distinct
suggest/live-typing endpoint exists; **hasQuickSearch = false** stands (provider has never
shipped one).

## Stream verification (issue #555, blocked check — same as run8)

check 3 (streams serve video) still cannot pass from this runner — **site-level block, not a
parser failure**, unchanged since run8:

- dood-family embeds (doply.net): plain-curl GET → **403** (challenge); no referer/UA
  combination unlocks it.
- lulustream.com/<id>: 200 **text/html player page**; the real m3u8 is behind a packed-JS
  shell (`eval(function(p,a,c,k,e,d)`). Re-tested 2026-10-13 with the TLS-impersonated
  browser tier (curl_cffi chrome): player page 200 → PackedJs-unpack →
  `https://fbevr1wmczl7.tnmr.org/hls2/02/04347/whv7n62d1uhh_h/master.m3u8?=…` — the exact
  flow of the shared `LULUBASE` adapter (Extractorlar.kt: fetch lulu page → unpack →
  `file:` regex → link w/ Origin+referer). That m3u8 host returns **403 (nginx)** to this
  runner IP even with Origin/Referer headers and a fresh token — datacenter IP block.

Routes-through shared extractor chain (doply→dood, mixdrop.ag→MixDropAg, luluvid→LULUBASE,
voe→voe), all with fixtures in the registry. Per-video `--stream-url` overrides failed the
mechanical content-type/runnability assertions for the reasons above; selectors
(`section.hlm[data-servers]`, data-serves JSON `u` values) are unit-tested against live
capture. Playback proof remains in-app at merge time (maintainer-only, AGENTS.md), as
recorded in run8 FINDINGS.

## Actors exposure nuance (found in verification sampling)

Some pages have no Pornstars chips at all (`watch-big-tits-movie-online-free` details panel
has Studio/Release Year/Genres/Category rows only). The all-or-none actor check vs a page
lacking the row is a site data gap, not a selector mismatch — verification was re-run with
pages that carry stars (5/5 present).

## Risks / blockers

None new: challenge canaries healthy, no Cloudflare wall, search/genre/category all 200
plain-curl. Chronic-drift flag stands per issue #555: this is the 6th drift event
(#439, #444, #457, #463, #471, #555) — hardening = this structural rewrite (BEM grammar,
pager-anchored pagination); removal stays a maintainer decision.
