# FINDINGS-460 — Issue #460: audit probe of all 24 provider sites (2026-09-24 audit run)

Instruments, in escalation order (ADR-0008): plain curl with a Chrome UA, one bypass
attempt per wall. No browser used; no wall was unlocked. No provider code changed on
this run — artifacts are FINDINGS-460.md (this file) + unlabeled finding issues.

## Verdict table

| Provider | Base URL | HTTP | Home SSR | Search | Video/streams | Verdict |
|---|---|---|---|---|---|---|
| AllClassicPorn | allclassic.porn | 200 | yes | 200, 60×`a.th.item` | video page 200, `itemprop=name`, KVS flashvars present | OK |
| Cat3Film | cat3film.com | 200 | yes | `_ajax/search` 200 JSON | watch 200, `data-ep=419`, `/api/v1/episodes/419/sources` → hls JSON | OK |
| Cat3Movie | cat3movie.org | 200 | yes | `/search/<slug>` 200, `article.thumb` cards | watch 200 (`post_id:30436`, `data-nonce`), player.php?sv=1 → embedfree.site iframe | OK |
| EPorner | www.eporner.com | 200 | **no** | gate page | — | Blocked: age-verification wall (camera/photo account verify, no unlock). Recurrence — see issue #447 (closed) |
| Eroticmv | eroticmv.com | 200 | yes | `/?s=` 200, `article.post-item` | og:video:url base64 → vidcdn2.eroticmv.com/...m3u8 → 200 | OK |
| Film1k | www.film1k.com | **403** | Cloudflare challenge | — | — | Blocked: challenge, no unlock. Recurrence — issue #448 (closed), audit #233 |
| FreePornVideos | freepornvideos.xxx | **403** | Cloudflare challenge | — | — | Blocked: challenge, no unlock. Recurrence — issue #449 (closed) |
| FullPorner | fullporner.com | **403** | Cloudflare challenge | — | — | Blocked: challenge, no unlock. First report |
| HQPorner | hqporner.com | 200 | yes (61×`<section class="box feature">`) | 200, sections | iframe → mydaddy.cc → `//s45.bigcdn.cc/pubs/<key>/<res>.mp4` links resolve | OK |
| JavGuru | jav.guru | 200 | yes | 200, 24×`div.inside-article` | video page 200, `a.wp-btn-iframe__shortcode[data-localize]` present | OK |
| Javbangers | www.javbangers.com | 200 | yes | 200, 75×`div.video-item` | video page 200 (no trailing slash), KVS flashvars `video_url` get_file | OK |
| Javmost | www.javmost.ws | 200 | yes | `showlist2/1/1/search/` 200 JSON | video page 200, `YWRzMQo` constant + 4×`select_part` present | OK |
| Javseen | javseen.tv | 200 | yes | ajax search 200 JSON, `li#video-…` | video 200, embed 200 (`data-embed` base64: turbovid/streamwish/dood/mycloudz/streamtape) | OK |
| Javtiful | javtiful.com | 200 | yes (`article.front-video-card`) | 200 | video page 200, `frontWatchConfig` JSON `playerSources` present | OK |
| Mangoporn | mangoporn.net | **522** | — | — | — | Blocked: Cloudflare 522, origin down (2 tries). Recurrence — issue #456 (closed) |
| MissAV | missav.live | 200 | yes (`div.grid.grid-cols-2 > div` present) | `/en/search/amateur` 200 | — | OK (homepage probes) |
| Neporn | neporn.com | 200 | yes | async search 200, video cards | video page 200, KVS flashvars `video_url` get_file with v-acctoken | OK |
| PandaMovies | pandamovies.pw | **522** | — | — | — | Blocked: Cloudflare 522, origin down (2 tries). Recurrence #457 (closed), prior #425/#439/#444 — **Chronic (4+ closed broken/drift issues)** |
| PerverZija | tube.perverzija.com | 200 | yes | `/?s=` 200, cards | all 4 mainPage routes 200 | OK |
| PornXP | pxp.news | 200 | yes | `/tags/amateur` 200, 36×`item_cont` | — | OK |
| Porntrex | www.porntrex.com | 200 | yes | async search 200, 85×`video-preview-screen video-item` | — | OK |
| Sexfilm | en.sex-film.biz | 200 | yes | DLE search 200, 24×`div.short` cards | video page 200, `filmcdm` iframe present | OK |
| WatchPorn | watchporn.to | 200 | yes | async search 200, 40×`div.thumb.item` | video page 200, KVS flashvars + ld+json | OK |
| XMoviesForYou | xmoviesforyou.com | 200 home / **403 search** | home SSR | search → Cloudflare challenge | — | Home OK; search Blocked: challenge. Recurrence — issue #450 (closed, unfixed) |
| Xhamster | xhamster.com | 200 | yes | search 200 (results thin but present, 288 KB body) | video page 200, xhcdn mp4 keys in inline initPlayer JSON | OK |
| ixiporn | ixiporn.org | 200 | yes | via redirects, `video-block` cards ×26 | — | OK with **drift**: mainUrl `.org` double-redirects `.org → .info → .live` (each request pays 2 hops) |

## Blocked reasons (per CONTEXT.md vocabulary)

- `EPorner` — age-verification wall requiring a camera/photo account verification
  (`openModalAgeVer('create')`), no unlock cookie found (`age_verified`, `agegate`,
  `ep_age`, `agever` all ineffective). Problem: `client-rendered challenge behind the
  wall` — the wall itself is server-rendered and unconditional on plain HTTP.
  This is the **second** appearance of this condition (issue #447).
- `Film1k` / `FreePornVideos` / `FullPorner` / `XMoviesForYou` (search) — "Just a
  moment…" CF turnstile challenge page, `challenges.cloudflare.com` script nonce
  present. `challenge, no unlock` for plain HTTP; per-ADR-0008 no browser escalation
  shipped without an app-reproducible unlock.
- `Mangoporn` / `PandaMovies` — CF 522 (origin unreachable), not a parsing problem.

## Drift / broken history per finding provider (closed issues on its name)

- PandaMovies: #425, #439, #444, #457 → **4 = Chronic** (removal is a maintainer decision, `ai-remove-site`).
- EPorner: #447 (age wall), plus measure-only audit #293/#294/#269 → broken history 1 (this run = 2nd occurrence).
- Film1k: #448 + audit #233 → broken history 1 (this run = 2nd occurrence).
- FreePornVideos: #449 → broken history 1 (this run = 2nd occurrence).
- XMoviesForYou: #450 → broken history 1 (this run = 2nd occurrence).
- Mangoporn: #456 → broken history 1 (this run = 2nd occurrence).
- FullPorner: none (first report).
- ixiporn: #217 (audit) → no broken history; this run's redirect cost is drift-only.

All previously closed broken-provider problems re-probed this run are noted as
recurrence — the evidence lives in the linked issue bodies.
