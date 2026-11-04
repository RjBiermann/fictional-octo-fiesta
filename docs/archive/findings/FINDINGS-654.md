# EVIDENCE — issue #654 (Film1k audit + fix, deep pass)

Probe instruments: plain curl → 403 `cf-mitigated: challenge` on every www.film1k.com
URL (unchanged since #408/#558). Every film1k.com transcript below is curl_cffi
Chrome-TLS impersonation (tier `tls-impersonated`); film1k.xyz,
player.abyssplayer.com, enc-dec.app answer plain requests with a browser UA. The
verify.sh run on this runner was FAIL×4 on the CF challenge page (known datacenter
blocker — FINDINGS-558 precedent); the equivalent mechanical bar below is
TLS-impersonated: ALL PASS.

## Surfaces (fresh 2026-10-17)

- **home** p1+p2: 200, 24 `article.loop-post` cards each, **article-scoped disjoint**
  (0 overlap; doc-wide 33 hrefs each with 9 shared = Top-10 sidebar, outside articles).
- **search** `?s=taboo` p1: 200, 24 articles, title "You searched for taboo | ...".
  **`/page/2/?s=taboo` now serves real page-2 results — 24 articles, title
  "You searched for taboo | Page 2 of 3 | ...", disjoint from p1**, p3 also real
  (23 articles, disjoint from p2). The #408 "search no longer paginates" contract is
  observed **reversed** (site healed). P2 of a 3-page search = canonical
  `/page/{N}/?s=`. The old `?paged=2` boost is not needed.
- **exhausted search**: `/page/4/?s=taboo` and `/page/9/?s=` → HTTP 404 wrap,
  `<title>Page not found | ...`. **The 404-wrap is NOT query-empty — it seeds
  8 `article.loop-post` cards**, so `parseList` alone cannot distinguish exhaustion;
  a bare has-articles hasNext would paginate the 404s forever. Fixture
  `film1k_search_notfound_p4.html`.
- **no-results search** (`?s=zzzz…`): 200 "You searched for …", 0 articles — stays clean.
- **quickSearch**: no distinct quick-search endpoint presented → recorded absent (not drift).
- **empty search** `?s=` — surface change vs the 2026-10-14 note: now serves the
  home page fallback (24 articles, card-identical to home p1) instead of "No articles"
  (that template is gone from `?s=`). Benign for the provider (real searches only);
  recorded, not fixed against.

## Video pages (4 sampled, Distinct bar ALL PASS)

tick-tock-2000, beyond-taboo-1984, cannibal-taboo-2006, taboo-1980: title/plot/poster/
**embed-family** all 4-way distinct. Year in og:title. Player strip `#aa-videos-op`
present on all pages and scoping holds: strip contents == doc-wide hits on all 4
(zero promo aside pollution today).

**Host mix shifted:** 3 of 4 sampled pages now resolve to **abyssplayer.com**
(beyond-taboo `?v=7ZEujlb3R`, cannibal-taboo `?v=STP1x9oQv`, taboo `?v=LTjOBeDQK`),
1 of 4 stays byse (tick-tock `4wsa1vlemk0e` — same code as 2026-10-10). Both
loadLinks branches are implemented, so this is rebalance, not drift.

## Stream/embed resolution

- **byse chain (tick-tock) replayed green end-to-end** from a python
  curl_cffi/`dec-abyss`-free port of the shared solver: captcha
  (`pow_difficulty 16`) → verify `{"status":"ok"}` → playback
  (AES-256-GCM, key `parts[ver-1] ‖ parts[31-ver-1]`) → master.m3u8
  `edge2-waw-sprintcdn.r66nv9ed.com/... 4wsa1vlemk0e_x/master.m3u8` →
  **200 `#EXTM3U` (720p profile listed)**. Provider-side chain unchanged (goldens
  in BysePowTest); my first port run (--wrong state-init constants) 400'd into a
  server-detected `pow_failed` — the challenge is live and discriminating.
- **abyss chain live:** player.abyssplayer.com embeds 200 with the `const datas`
  blob; `enc-dec.app/api/dec-abyss` maps all three probed tokens to real source
  sets (360p/720p/1080p for LTjOBeDQK; 360p×3 ; 360p/480p). sora.m3u8/CDN GETs
  from this datacenter IP: **404/403 rotating-tunnel artifact** (matches the
  2026-09-10 finding "Retrying on 403/timeout picks a live tunnel"). Per the
  verdict vocab that is **Suspected probe-IP only** — never a standalone finding
  without a residential differential; not filed (consistent with
  `suspected_excluded` #448/#465 practice).
- turbovid family: still DNS-dead historically (#496 closed; branch stays removed,
  fixture kept). Not re-probed as an embed surface (no turbovid markup on sampled
  pages).

## Metadata info-block drift (site-side, optional fields)

The `<strong>Runtime</strong>/: Genres:/: Actors:` info block now exists on **some**
video pages only (tick-tock 93 mins, beyond-taboo 85 min; taboo-1980 and
cannibal-taboo-2006 carry an AI-FAQ block instead and no Runtime/Genres marker at
all). Provider parsing is null-safe for every optional field, so nothing breaks;
recorded so verify.sh 2a's all-or-none bar reads as a site characteristic, not drift.
Year stays in og:title on all pages.

## Fix this run (v13)

- `Film1kParse.searchPageUrl(baseUrl, q, page)` — p1 `/?s=`, p>1 `/page/{N}/?s=`
  (fresh fixture `film1k_search_taboo_p2.html`).
- `search()` paginates again: p>1 requests the canonical WP path; exhausted pages
  gate on the new `Film1kParse.isNotFoundTemplate(doc)` (the 404 wrap seeds 8
  articles — bare article-count hasNext would loop 404s); no-results stays
  200/empty (loop terminates naturally).
- Tests red → green (searchPageUrl ×2, fixture card parse, isNotFoundTemplate ×2).

## verify.sh

`verify.sh` from this runner: FAIL — every URL 403 `cf-mitigated: challenge`
(instrument-blocked, same as #558/#408/#593-S2 runs). Mechanical bar rerun over
the sanctioned TLS-impersonated instrument: home p1/p2 disjoint 24/24, search p1+2
disjoint 24/24, 4 video pages Distinct (title/plot/poster/embed) 4/4, related
20/20 per page distinct, search↔load agreement (taboo-1980 in `?s=taboo`,
title matches), byse chain green, abyss enc-dec chain live. **ALL PASS.**
Live verify.sh without a CF block remains OUTSTANDING for the maintainer.
