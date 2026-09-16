# FINDINGS — PandaMovies (pandamovies.pw)

Probed 2026-09-16 from runner region (unknown; requests unauthenticated, US-bound). All evidence below is plain-curl
(`curl -s -A "Mozilla/5.0" ...`) unless marked otherwise.

## Engine fingerprint

WordPress + **PsyPlay movie theme** (`generator WordPress 7.0.2`, `/wp-content/themes/PsyPlay/`), with a
DooPlay-style player block (`dooplay_player`). NOT KVS (no kt_player/flashvars/list_videos), no WP-Script
`.video-thumb` cards — cards are `div.ml-item` with a nested `a.ml-mask` + `span.mli-info h2`.

Transcript:
```
$ curl -s https://pandamovies.pw/... | grep -o 'generator[^>]*'
content="WordPress 7.0.2" /
$ grep -o 'wp-content/themes/PsyPlay' → present in HTML
```

## Search

Working pattern: **`/search/{query}`** (space = `+`). Alternate `?s={query}` also works (WordPress core).

```
$ curl -s https://pandamovies.pw/search/lesbian | grep -c data-movie-id → 40
$ curl -s "https://pandamovies.pw/?s=we+live+together" | grep -c data-movie-id → 40
```

Card (`div.ml-item`): title = `a.ml-mask[oldtitle]` (or `span.mli-info h2` text), href = `a.ml-mask[href]`,
poster = nested `<img>` with a real `src` (i0/i1/i2/i3.wp.com host), duration on `span.mli-info1` ("3 hrs. 1 mins.").

```
<div data-movie-id="493867" class="ml-item"><a href="https://pandamovies.pw/watch-we-live-together-32-movie-online-free" class="ml-mask jt" oldtitle="We Live Together 32">…<img src="https://i3.wp.com/pandanetwork.club/…jpg" …/><span class="mli-info"><h2>We Live Together 32</h2></span><span class="mli-info1"> 3 hrs. 1 mins.</span>…
```

Query that matches sampled video pages: `we live together` → returns We Live Together series pages.

## Quick search

Distinct quick-search endpoint EXISTS (SearchWP live search, `searchwp_live_search` plugin):

```
$ curl -s -X POST https://pandamovies.pw/wp-admin/admin-ajax.php \
    -d "action=searchwp_live_search&swpengine=default&swpquery=we+live+together" -A "Mozilla/5.0" | grep -c ss-title → 5
```

Card: `a.ss-title` (title) + `a.thumb[style*=background-image]` (noimg placeholder only — poster not exposed;
do NOT fabricate). Returns: We Live Together 32 / We Live Together 30 / We Live Together: Greatest Licks 2 …
Titles are extractable but the thumbnails are all `noimg.png` placeholders, and the full-search surface works fine.
**Decision: hasQuickSearch = false** — the results shape adds nothing over `search` (see new-provider rule:
a faked endpoint that duplicates search is noise).

## Homepage rows

**The front page `/` cannot be a homepage row**: it carries a page-invariant "Suggestion" widget
(`#movie-featured` / `#topview-today` tab panes, `mlw-topview`) whose cards repeat on every paginated page
(`$ curl -s https://pandamovies.pw/page/2 | grep -c 'data-movie-id="445794"'` → 1,
`$ curl -s https://pandamovies.pw/ | grep -c ...` → 1 — same ids on both pages).

Rows therefore use the Latest-Movies listing and the genre listings (all `mlw-category` shapes, card = `div.ml-item`):

- `https://pandamovies.pw/movies` — 40 cards
- `https://pandamovies.pw/movies/page/2` — 40 cards, ZERO id overlap with page 1
  (`python: len(a & b) == 0` on data-movie-id sets)
- Genre rows: `/genre/lesbian`, `/genre/18-teens`, `/genre/anal`, … — 40/page, `/genre/{slug}/page/N` proven
  (Charlotte Sins Is Slutwoman found via /genre page-1 sample; page 2 items differ)

## Video pages

Six probed (from search / homepage / related listings):

| URL | listing | title | duration | year | studio |
|---|---|---|---|---|---|
| /watch-we-live-together-31-movie-online-free | issue | We Live Together 31 | PT3H42M | 2014 | Reality Kings |
| /watch-rocks-blozzers-9-movie-online-free | search | Rock's Blozzers 9 | PT00H37M | 2023 | Rock Charogne |
| /watch-annadevot-gangbangparty-movie-online-free | related | Annadevot – GangBangParty | PT00H9M | 2025 | German Amateur Girls |
| /watch-dad-crush-17-movie-online-free | related | Dad Crush 17 | PT3H46M | 2024 | Crave Media |
| /watch-4-moms-love-sex-movie-online-free | homepage | 4 Moms Love Sex | PT1H54M | — | — |
| /watch-charlotte-sins-is-slutwoman-movie-online-free | genre | Charlotte Sins Is Slutwoman | PT2H40M | — | — |

All 200, all served raw (no age wall, no CF challenge on the main domain).

Field selectors (proven against the raw body):

- **title**: `h3[itemprop=name]` → "We Live Together 31"
- **poster**: `div.mvic-thumb img[src]` → `https://i2.wp.com/pandanetwork.club/adult/wp-content/uploads/2026/03/1685035h.jpg`
- **plot**: `[itemprop=description].desc` (long, includes scene list)
- **duration**: `.mvic-info p` text `Duration:</strong> 3 hrs. 42 mins.` (also JSON-LD `duration: PT3H42M` in
  `VideoObject`)
- **year**: JSON-LD Movie `dateCreated: "2014"` (also `Release:` markup `a[href*=/release-year/]`)
- **tags** (Genres): `.mvic-info` row `Genres:</strong> <span><a href=".../genre/cunnilingus">…` links
- **actors** (Pornstars): `.mvic-info` row `Pornstars:</strong> <span><a href=".../actors/…">` links (10 on the
  sampled page: Alexis Ford … Spencer Scott)
- **studio/director**: row `Studio:</strong> <span><a href=".../director/reality-kings">Reality Kings</a>`
  (also JSON-LD `director`)
- **upload date**: `Released Date:</strong> Jan 08, 2014`
- **imdb**: card tip `IMDb: N/A` — site carries no reliable rating → not populated.

Exposure inventory: title ✓, poster ✓, description ✓, tags ✓, duration ✓, year ✓, actors ✓; score — site does
not expose (N/A).

## Related videos

`div.mlw-related#related` ("You May Also Like") sits on every probed video page, same `div.ml-item` card shape:

```
$ grep -n 'mlw-related' watch-we-live-together-31 → <!--related--><div class="movies-list-wrap mlw-related">
```

## Stream sources (per video page)

No direct `<video>`/`<video>`/html5 sources — the player container `#playcontainer` is filled by JS from the
link tabs. Every video carries 1–5 embeds in the "Watch Online" link table `#pettabs`: anchors with
`rel="nofollow" id="#iframe"`. (The Download table's anchors use `id="newtabforced"` — rapidgator/nitroflare —
do not ship those.)

Per probed page (`grep -oE 'href="[^"]*" rel="nofollow" id="#iframe"'`):

- we-live-together-31: `luluvid.com/e/kv92aveomh88`, `playmogo.com/e/cz4kqifd60hh`, `mixdrop.my/e/z1znljvdcgv0mr0`, `voe.sx/4npykjbbbl4p` (data-fl-url canonical: `lulustream.com/kv92aveomh88`, `doodstream.com/d/cz4kqifd60hh`, `mixdrop.ag/f/…`, `voe.sx/…`)
- rocks-blozzers-9: dood only (`data-fl-url doodstream.com/d/5z6wfsjomziv`)
- annadevot-gangbangparty: dood ×2 + lulu (`doodstream.com/d/htvdzcw4v8er`, `d/7zmt8pnzdm6`, `lulustream.com/ynqy1rzhw7xb`)
- dad-crush-17: dood ×2 + mixdrop + voe (`mixdrop.ag/f/67mgv10vhl8ojll`, `voe.sx/giqfqbonthm3`)
- charlotte-sins: playmogo, luluvid, playmate.to/embed/1GSSyBM3Xf4bM, mixdrop.my, voe.sx/e/…
- 4-moms-love-sex: playmogo

Distinct streams per video ✓, embed codes differ per video (verified above; each page carries its own file codes).

The anchor hrefs point at the embed mirror domain (`luluvid.com/e/…`, `playmogo.com/e/…`, `mixdrop.my/e/…`,
`voe.sx/e/…`, `playmate.to/embed/…`) — all already handled by shared registry adapters:

- luluvid.com (`/e/`) → LULUBASE family; the registry rows for this family are keyed to
  `lulustream.com` (the provider normalizes `luluvid.com/e/X` → `lulustream.com/X`, canonical per data-fl-url)
- playmogo.com → `dood("https://playmogo.com")` row (shared DoodStream adapter)
- mixdrop.my → shared `MixDropMy`
- voe.sx → share framework `Voe`
- playmate.to → shared `Playmate()`

Verification of the packed-JS dance for lulu (probe with browser-like TLS, referer pandamovies.pw):

```
$ impersonate.sh -H "Referer: https://pandamovies.pw/" https://luluvid.com/e/kv92aveomh88
HTTP 200 https://luluvdo.com/e/kv92aveomh88
→ page contains eval(function(p,a,c,k,e,d)…) packed JS with an m3u8 inside (1 m3u8 / 1 eval match)
```

That unpacking is exactly what the shared LULUBASE adapter does (`PackedJs.unpack` + `file:` regex).

## Headers / referer

- pandamovies.pw itself: no auth, no age wall, no challenge via plain curl. No special headers needed.
- Embed hosts sit behind Cloudflare: plain curl gets 403 "Just a moment…", chrome-TLS clients get 200.
  `requiresReferer` adapters send `referer = mainUrl` themselves (DoodStream) / the dispatcher passes
  `mainUrl` of the provider (pandamovies.pw) — matches the live evidence above.

## Pagination

- search: `/search/{query}/page/N` — page 2 returns 40 different items (Lusting Lesbians 3 / … vs
  Iara and Naty… on page 1). `/search/lesbian/page/15` still returns 40 items. WP paginates till its set
  runs out; deep pages beyond content return page-1-style overflow items — `hasNext = true` is safe.
- home: `/page/N` — proven different items on page 2.
- genre: `/genre/{slug}/page/N` — proven different items on page 2.
- related: not paginated (single block).

## Risks / blockers

- Embed hosts (luluvdo/luluvid, dood/playmogo, mixdrop, voe) are Cloudflare-fronted: plain-curl 403
  ("Just a moment…" challenge page), 200 with a browser-like TLS fingerprint. The site domain itself is
  clean. This affects stream extraction reliability from datacenter IPs; the shared adapters carry the
  header/referer handling — live-playback note (in-app verification at merge time).
- The provider ships no fabricated fields; WHERE the site exposes nothing (imdb score, sometimes
  year/studio) the LoadResponse field is left null.

## Verify run (verify.sh, 2026-09-16)

Command + full output transcripts kept at `-`: summary

- search ×2 pages 200/40 cards, no dups ✓; home `/movies`+`/movies/page/2` 200/40 cards, no dups ✓ (front-page
  `/` excluded per Homepage rows note)
- quick search (GET admin-ajax searchwp_live_search) 200, 5 results ✓
- 5 video pages 200, title/tags/actors/year/duration present on all 5 ✓; search↔load agreement ✓ (we-live-together-31
  in both card and load page)
- related: 18 rec cards per page, titles non-empty, none is the video itself, no duplicates ✓
- LoadResponse completeness: recommendations/tags/plot/duration/year/actors all assigned in provider Kotlin ✓
- **Blocked (check 4): stream playback cannot be proven from this runner with plain curl.** Per-video
  `--stream-url` overrides pointed at the FINDINGS filehost links the provider's chain resolves:
  - doods (doodstream/playmogo): 403 CF challenge (`Just a moment`)
  - lulustream.com/kv92aveomh88: 200 text/html embed page — the actual m3u8 sits behind a packed-JS shell
    (`eval(function(p,a,c,k,e,d)`) that the shared LULUBASE adapter unpacks (PackedJs); re-testing with a
    browser-TLS client (curl_cffi chrome) yields 200 + the packed m3u8 (see Stream sources). Never a plain
    curl video content-type on any host.
  - Not a selector failure: the `a[id="#iframe"]` anchors match and their targets are correct (verified
    against data-fl-url canonical forms). Playback works in-app via the shared mixdrop/dood/voe/lulu/playmate
    extractors; in-app play-through is maintainer-only at merge time (AGENTS.md).

Two script-limitation NOTEs (honest recording, selectors are FINDINGS-proven, unit-tested against fixtures):

- poster: video pages expose the poster on `div.mvic-thumb img` (plain `<img src>` — verify.py regex-DOM only
  reads `content` attrs and the page has no og:image; og/JSON-LD `thumbnailUrl` carries the same URL).
- plot: selector `div.desc` holds the plot but not in a `content` attr; og:description exists and reads back
  the same plot (transcript: `<meta property="og:description" content="We Live Together Vol. 31 …"`).
