# FINDINGS-431 — hqporner: video doesn't play (issue #431)

Reported URL: https://m.hqporner.com/hdporn/39719-Maria_Ozawa_2.html (m. subdomain is just
the mobile mirror of the same page; desktop /hdporn/39719-Maria_Ozawa_2.html serves
the identical video page, HTTP 200).

## Root cause (probed)
hqporner uses **two** player-embed domains depending on video age:

- Recent videos (spot-checked homepage 127868/127867/127866/127859 and /top 100033, 101366,
  101833, 102090, 105824): `iframe src="//mydaddy.cc/video/<hash>/"` — the domain the
  provider's loadLinks selector (`iframe[src*=mydaddy]`) matches.
- The reported old video (39719) — and in general older uploads — carries a **different
  player**:

```
$ curl -sA "Mozilla/5.0 … Firefox/130.0" "https://hqporner.com/hdporn/39719-Maria_Ozawa_2.html" | grep -o 'iframe[^>]*src="[^"]*"'
→ <iframe width="560" height="350" src="//hqwo.cc/player/b73b1d8b4f15e184a5c6800d7a18a307?img=…base64…">
  (+ a go.mavrtracktor.com ad iframe — not a player)
```

`iframe[src*=mydaddy]` matches nothing → `fixUrlNull(...) ?: ""` → loadExtractor("") is a
no-op → zero links → "video doesn't play".

## hqwo.cc player = same daddy family
Player page (hqporner referer + desktop UA):

```
$ curl -sA "…Firefox/144.0…" -e "https://hqporner.com/" "https://hqwo.cc/player/b73b1d8b…?img=…" | grep -o "href='//hqwo.cc[^']*'"
→ href='//hqwo.cc/pubs/6aaaee7c0cdb5/360.mp4' : href='//hqwo.cc/pubs/6aaaee7c0cdb5/720.mp4'
```

Same `a href='<host>/pubs/<key>/<res>.mp4'` markup the existing shared `MyDaddyExtractor`
regex (`a href='([^']*)'`) already parses (mydaddy bodily → s*.bigcdn.cc/pubs; hqwo serves
pubs on its own host).

Stream check — 720.mp4 serves video:

```
$ curl -r 0-1023 -A "Mozilla/5.0" -e "https://hqwo.cc/" -D - -o /dev/null "https://hqwo.cc/pubs/6aaaee7c0cdb5/720.mp4"
→ HTTP/1.1 206 Partial Content / Content-Type: video/mp4
```

## Fix (minimal)
loadLinks selector: `iframe[src*=mydaddy]` → `iframe[src*=mydaddy], iframe[src*=hqwo]`.
The shared MyDaddyExtractor parses hqwo bodies unchanged (mainUrl only names the host; no
hard dependency on it in getUrl). No new extractor, no regex change.

## Risks
- Some videos probed from /top show **no player iframe at all in raw HTML** (100331,
  102328, 102827) — spot-check: those pages may be geo/DMCA-hidden for the probing IP;
  pre-existing condition, unrelated to #431.
- Probing region: datacenter IP (EU). hqwo stream 206-verified from it.

## Second drift found while codifying (also fixed in this PR)
Two searches seconds apart returned cards with a *different random class token*: one
response rendered every card as `<section class="box feature">`, another rendered a
subset as `<section class="box features">` (3 of them on one fetch). Any selector
pinned to the exact token `box.feature` randomly drops cards. Fix (same provider file):
`div.row section.box.feature:has(a.image)` → `div.row section:has(a.image)` in
getMainPage + search.

## Stream-key rotation (verify mechanics)
mydaddy.cc mints a fresh `/pubs/<key>/<res>.mp4` URL set on every player-page fetch and
old keys 404 within a minute or two — stream URLs are not stable across runs, so
verify ran with a script that mints all five overrides immediately before invoking
verify.sh (all five then served `HTTP 206 video/mp4`, including the hqwo one).

## Verify transcript (PASS)
verify.sh run (selectors: search/home/related `h3.meta-data-title` — stable card-anchor
regardless of the randomized section-class token; video title `h1`; plot
`meta[name=description]`; tags `h3 + p a`; actors `li.icon.fa-star-o a`):
- search p1 200 / 10 cards, p2 200 / 50 cards, no dups;
- homepage /top/1 + /top/2 → 200 / 50 cards each, no dups;
- 5 video pages (39719 + 4 others from /top and recent), player iframe match 3 each;
- streams: 5/5 → HTTP 206 video/mp4 (1 hqwo.cc + 4 bigcdn.cc);
- poster: NOTE — video pages embed no static poster (known from #237 probe);
  tags + actors present on all 5 pages; LoadResponse completeness: all fields assigned;
- RESULT: PASS.
