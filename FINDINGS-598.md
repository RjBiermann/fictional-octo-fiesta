# FINDINGS-598 — Deep verification pass on the 8 `ok-drift` providers (S2 of the #593 spec, audit round 14)

Run date: 2026-10-14. Issue #598; spec #593 (S2), maintainer-approved re-verdict rule: both
historical drift records are closed (PandaMovies #555 fix merged in PR #563, 2026-10-11;
Mangoporn #564 fix merged in PR #566 from `devloop/issue-564`) — **cleared →
provider migrates ok-drift → ok citing the merged fix; still drifted → a new post-fix
condition gets its own issue** (one per provider, S4 rule / issue #597); no existing
record is reopened.

Deepest pass on every one of the 8: **every homepage row's page-1/page-2 pagination,
quick search surface, ≥3 listing-guard faces (home/search/category), 2–3 video pages per
provider, and stream URL resolution where the chain is not server-side challenge-gated.**
No Kotlin was changed on this run (verdict-only); `audits/` schema untouched.

Explicit absent surfaces are recorded per provider below — nothing left unstated.

## Re-verdict summary

| Provider | Was | Now | Historical record | Outcome |
| --- | --- | --- | --- | --- |
| PandaMovies | ok-drift | **ok** | #555 closed (fix #563 merged, v5) | cleared by live evidence |
| Mangoporn | ok-drift | **ok** | #564 closed (fix #566 merged, v6) | cleared by live evidence |
| XMoviesForYou | ok-drift | **ok** | #535 closed (fix #547 merged, v14) | cleared by live evidence |
| Javtiful | ok-drift | **ok** | #534 closed (fix #548 merged, v13) | cleared by live evidence |
| Javbangers | ok-drift | **ok** | #475 closed (site limitation documented; provider matches live) | cleared by live evidence |
| Film1k | ok-drift | **ok** | #496 closed (turbovid branch removed by fixes #500/#501, v11) | cleared by live evidence |
| EPorner | ok-drift | **ok** | #537 closed (fix #543 merged, v18) | cleared by live evidence |
| ixiporn | ok-drift | ok-drift (stays) | #468 closed standing | condition unchanged — same standing domain drift, **not** a new post-fix condition → no new issue |

`audits/findings.json`: verdicts/reasons/surfaces/last_deep refreshed for all 8
(last_deep 2026-10-14); historical counters (`drift_confirmed`, `chronic`,
`standing_issue`, `suspected_excluded`) untouched — where a drift-counter note says
"removal-candidate decision stays with maintainers", it stays with maintainers.

## Per-provider evidence

### PandaMovies → ok (record #555, closed via #563)

Tier: plain-curl (`.pw` 302 → `.org` 200 throughout — the standing #463 origin-down
condition remains cleared; nothing blocked).

| Surface | Result |
| --- | --- |
| Search p1 `?s=sex` | 200, 27 `article.card` cards via `card__th` hrefs |
| Search p2 `?s=sex&paged=2` | 200, 27 cards, **disjoint from p1** |
| Home row page 2 (`/page/2/`) | 200, 27 cards, **disjoint from p1** |
| WP pagination marker | `nav.pg` + `a.next` present |
| Video pages (2) | 200 each: `span.bc__c` title, `img.hlm-screen__poster`, plot `div.entry.vid__txt`, year in ld+json/`st--year` |
| Embeds | `section.hlm[data-servers]` JSON: LuluStream/luluvido, DoodStream/playmogo, MixDrop/mixdrop.ag, VOE, PlayMate — all in the HostRegistry adapter set (playmogo=dood, luluvido=lulu) |
| Quick search | `wp-json/turbosearch/v1/suggest?q=sex` @ .org → 200 JSON (`posts[]` with title/url/thumb) |

Note: card counts (27) differ from the counts captured at fix-merge time (49/35) —
surface sizes changed on the site, grammar did not. Provider `Parse` matches live.

### Mangoporn → ok (record #564, closed via #566)

Tier: plain-curl for mangoporn.net; embed chain verified on the TLS-impersonated tier.

| Surface | Result |
| --- | --- |
| Search p1/p2 `?s=sex` | 200, 27 `card__th` cards each, **disjoint** |
| Home row page 2 | 200, 27 cards, **disjoint** |
| Video pages (3) | 200 each (`sex-is-for-lovers`, `sex-on-the-job-5`, `sex-romance-4`), `section.hlm[data-servers]` JSON embeds live |
| Embed hosts on v3 | PlayMate/playmate.to, DoodStream/playmogo ×3, LuluStream/luluvido ×2, VOE/voe.sx, MixDrop/mixdrop.ag ×2 |
| Stream resolution | luluvido `/e/<id>` TLS-200 page → packed Dean-Edwards script → unpacked `file:"https://kjiijhmbhkje.tnmr.org/hls2/.../master.m3u8?..."` → master.m3u8 fetch **200 `application/vnd.apple.mpegurl`** (plain 403 on the embed page = CF on datacenter IP; TLS tier is the known instrument fix, same class as the standing canary pair) |
| Quick search | `wp-json/turbosearch/v1/suggest?q=sex` → 200 JSON (`q`, `posts[]`, `terms[]`) — provider's `quickSearch` override verified |

### XMoviesForYou → ok (record #535, closed via #547)

Tier: TLS-impersonated (plain curl = CF challenge page; known session/leech
ok-suspected exclusion class, standing #449/#466/#473).

| Surface | Result |
| --- | --- |
| Home hero carousel | 30 `a[data-video-card]` cards, **30/30 provider-parseable** (title in `h3`, href on root anchor, poster `img`) |
| Home grid | 24 `a.group.flex.flex-col` cards, **24/24 parseable** |
| Search p1 `/search?q=cat` | 200, 24 `a.card` cards — title/poster/href all captured (studio-bracket prefix preserved) |
| Search p2 `?q=cat&page=2` | 200, 24 cards, **disjoint from p1** |
| Video page | 200: h1/og:title, `postId`, JSON-LD BlogPosting, runtime/`span.material-symbols-outlined:contains(calendar_month)` chip |
| Filehost anchors | `streamtape.com/v/<code>` present in the player area (provider's `loadLinks` matches it; shared StreamTape adapter is registered) |
| Related | `/api/related/<postId>` → JSON 200 |
| Quick search | **No dedicated quick-search endpoint observed on the site** and the provider does not override `quickSearch` — absent surface, not drift |
| Stream final hop | `streamtape.com/get_video?...` answered `{"status":500}` from the datacenter probe IP on repeated fresh tokens — host-side behavior on this IP, not provider grammar drift (the embed page itself is 200 and serves valid `captchalink`/`norobotlink` tokens) |

All three live card shapes are covered by the merged `XMoviesForYouParse.CARD`
(`a[data-video-card], a.card, a.group.flex.flex-col`).

### Javtiful → ok (record #534, closed via #548)

Tier: plain-curl.

| Surface | Result |
| --- | --- |
| `/videos` (Newest) p1/p2 | 200, 23 `article.video-card` each, **disjoint**, live `a.pagination__link` `Next` anchors with real hrefs (`/videos?page=2`, `/videos?page=3`) |
| `/videos?sort=most_viewed` | 200, 23 cards |
| `/category/milf` p1/p2 | 200, 23+23 **disjoint**, real page-3 `Next` hrefs |
| Search p1 `?q=hard` | 200, 23 cards, live `Next` → `/search?page=2&q=hard` |
| Search p2 | 200, 23 cards, **disjoint from p1** |
| Video pages (4 probed: 36273, 106190, 107075, 781-embed) | 200 each; JSON-LD VideoObject (name/duration/uploadDate/embedUrl distinct); `div.watch-detail` rows for Categories/Tags/Added-on (year extraction path) |
| Related | 20/20 `article.video-card` cards provider-parseable |
| Streams | `/#/embed/<id>` pages carry `script#watch-config` JSON `playerSources` — `fast-stream.jav.si/p/<token>` verified **200 with `video/mp4` 206** on `Referer: https://javtiful.com/` |

Note: the site root `/` is an SPA infinite feed whose `?page=2` duplicates page 1 —
but **the SPA root is not a provider row**; `mainPage` targets `/videos`,
`/videos?sort=…`, `/category/*` and `/uncensored`, which all server-paginate with real
`pagination__link` markup. The run13-era impression ("pagination__link dead / titles
dead") came from probing the SPA root instead of the provider's actual rows.
`a.video-card__title` carries the title as **both** a `title` attribute and inner text
on every listing surface — the merged `videoCard` Parse matches live on all faces.

### Javbangers → ok (record #475, closed standing)

Tier: plain-curl.

| Surface | Result |
| --- | --- |
| `/latest-updates/` p1/p2 | 200, 30 `div.video-item` cards each, **disjoint** (`a.thumb` href + `title` attr + `img.cover` data-original) |
| `/categories/milf/` | 200, 24 cards on provider grammar |
| Search p1 `/search/milf/` | 200, 24 cards |
| Search p2 | **404** — standing site limitation (#475, closed); provider declares `hasNext = false` for search by design, so in-app behavior matches the live site exactly |
| Video pages (2) | 200: `h1`, `div.block-details`, Tags row, related 10 blocks |
| Streams | `flashvars = {...}` with quoted-key grammar → `video_url`/`video_alt_url` (Unquoted-key KVS variation handled by shared `KvsFlashvars`) → `get_file` → **302 → origin11-direct.cdntrex.com → 206 `video/mp4`** on `Referer: https://www.javbangers.com/` |

### Film1k → ok (record #496, closed via #500/#501)

Tier: TLS-impersonated (plain = CF 403 — the standing `film1k-challenge-pair` canary
matches: plain 403 / TLS 200).

| Surface | Result |
| --- | --- |
| Home p1/p2 | 200 each, 24 `article.loop-post` cards, **disjoint** (9 same-URL hrefs appear doc-wide from the Top-10 sidebar `<ul>`, outside articles — provider `parseList` selects articles only) |
| Search p1 `?s=all+about+anna` | 200, 24-article results template (search hits) |
| Search `?s=italiane` | 200 with "No articles" (legitimately empty result set) |
| Search p2 `/page/2/?s=` | 200 **wrapping a "Page not found" template** — matches the provider's p>1 → empty contract (the site does not paginate search) |
| Video page `/all-about-anna-2005.html` | 200: `h1.title`, og:image/og:title/og:description, Runtime/Genres/Actors `strong:` siblings present |
| Player strip | `#aa-videos-op` present; both on-page `player.abyssplayer.com/?v=` occurrences (`<source src>` + lazy iframe) fall **inside** the player strip — the #558 promo-aside scoping holds |
| Byse chain | `film1k.xyz` root 403 (CF) but `/api/videos/<code>/embed/captcha/` **200 JSON** (`pow_nonce`, `pow_difficulty=16`, `pow_token`) — the PoW-protected playback chain is live; final stream URL is gated behind the server-side challenge and was not bypassed |
| Turbovid family | still DNS-dead (condition historical #496) — harmless: the fix removed the turbovid loadLinks branch, so no dead code path remains |

### EPorner → ok (record #537, closed via #543)

Tier: plain-curl.

| Surface | Result |
| --- | --- |
| Home p1/p2 | 200, 65/64 `p.mbtit > a` video cards, essentially disjoint (3 overlap ids from duplicated rail placements — not a provider-grammar concern) |
| Search p1 `/search/cowgirl/1/` | 301 → `/tag/cowgirl/` (the documented two-leg #537 collapse) — the merged provider routes p≥2 through `/tag/<q>/<n>/` |
| `/tag/cowgirl/1/` vs `/2/` | 200, 65 vis 65, **disjoint** |
| `/cat/vintage/` | 200, 61 cards |
| Video pages (3) | 200: h1, og:image thumb, related `div#relateddiv` 24 blocks |
| Stream chain | embed page (200) → `EP.video.player.hash` present → 8×8-chunk base36 conversion → `/xhr/video/<id>?hash=...` → **200** with `labelShort`/`src` quality ladder — 2160p mp4 URL verified **206 `video/mp4`**, `srcFallback` HLS master.m3u8 fetch **200 `application/vnd.apple.mpegurl`** |
| Age-wall | **Not observed this run.** Every surface returned full content on the plain tier (run13's 2026-10-10 full-site wall did not reproduce from the same datacenter IP class). Recorded as the suspected account/IP-geo wall class (standing #447/#462 `suspected_excluded`) — not reproduced, so not replayed as a Blocked verdict |

### ixiporn → ok-drift (stays; record #468 closed standing)

Tier: plain-curl.

| Surface | Result |
| --- | --- |
| Origin | `ixiporn.org` still double-redirects (→ `/` @ `.live`) — **the standing #468 condition is unchanged** |
| Home p1/p2 | 200, 30 cards each, **disjoint** |
| Search `/search/lingerie/` p1–p3 | 200 each, p1∩p2 disjoint |
| Video pages (3) | 200 each, `meta[itemprop=contentUrl]` streams on `cdn2.ixifile.xyz` / `ixifile.xyz` — all **206 `video/mp4`** on `Referer: https://ixiporn.live/` |
| Quick search | **No quick-search endpoint markers on the site**; provider has no `quickSearch` override — absent surface |

Same standing condition, not a new post-fix condition → per the re-verdict and S4
rules: no record reopened, **no new issue filed**.

## Findings lifecycle

- Search of open + recently-closed findings: no standing condition changed state in a
  way that requires filing; #468 (ixiporn) is a re-confirmation of an unchanged,
  already-recorded condition.
- **New findings this run: none.** `false_positives: []`.

## Raw probe HTML

Raw probe HTML (surfaced from the run sandbox) deletes from the repo per the
FINDINGS-only ground-truth rule; tables above are the ground truth.
