# FINDINGS — yesporn.vip (S3: load, streams, metadata; issue #1652, 2026-11-27)

Parent epic: #1424 (add the `YesPornVip` provider) — story 3 of 3, provider `version = 3`.

## Verdict: shippable — 3 titles from 3 channels × 3 qualities verified, no blockers

Streams resolve and play on **76685 (Brazzers)**, **76391 (DigitalPlayground)** and
**75971 (bangbros)**, three qualities each, with no cookies and no special headers. The
KVS `function/0/…trim.mp4` media caveat from the plan is **cleared** (see below): the
resolver that works is the site's own download-dialog chain, which serves non-trim files.

## Streams — the working chain

Verified with plain curl (Firefox UA, **no cookies**, `Range: bytes=0-64`):

| video | channel | 480p | 720p | 1080p |
|---|---|---|---|---|
| 76685 `the-threesome-tutorial-fhd` | Brazzers | `206 video/mp4` | `206 video/mp4` | `206 video/mp4` |
| 76391 `sweet-as-sugar-episode-3` | DigitalPlayground | `206 video/mp4` | `206 video/mp4` | `206 video/mp4` |
| 75971 `wheelies-during-anal-fucking` | bangbros | `206 video/mp4` | `206 video/mp4` | `206 video/mp4` |

Chain (two hops, both cookieless):

```
GET /view_video_download.php?id=<id>&format=<480|720|1080>  → 200, one a.btn.download
    href="https://yesporn.vip/get_file/5/<hash>/<dir>/<id>/<file>.mp4/?download_filename=…&download=true[&br=…]"
GET that href                                              → 302
GET cdn3.yesporn.vip/remote_control.php?…                  → 206 video/mp4 (ranged)
```

- The `format=` argument is a real ladder, not a label: `480` serves the tokenless base
  file (`76685.mp4`), `720`/`1080` serve `_720p`/`_1080p` variants.
- The 1080 file for 76685 is 802,029,995 bytes (`Content-Range: bytes 0-1023/802029995`).
- The signed query tail is load-bearing: `get_file` **without** it answers 404, with it
  answers 302. The provider emits it verbatim.
- No cookies were sent on any hop. The download button carries
  `data-attach-session="PHPSESSID"`, but the endpoint does not enforce it (verified
  3 videos × 3 formats, cookieless) — per ADR-0008 no cookie is harvested or replayed.

## KVS `function/0/…trim.mp4` caveat — cleared, recorded not Blocked

The detail page's flashvars point at **trim** variants:

```
video_url:        'function/0/https://yesporn.vip/get_file/5/<h>/76000/76685/76685_trim.mp4/'
video_alt_url:    'function/0/https://…/76685_720p_trim.mp4/'   (video_alt_url_text: '720p')
video_alt_url2:   'function/0/https://…/76685_1080p_trim.mp4/?br=3125'
```

Every one of them 404s at the origin — with the `function/0/` prefix
(`https://yesporn.vip/function/0/https://…`), without it, with `?download=true` appended,
and from the `/embed/76685` page as well (3/3 formats, 2026-11-27):

```
GET …/76685_trim.mp4/          → 404 text/html
GET …/76685_720p_trim.mp4/     → 404 text/html
GET …/76685_1080p_trim.mp4/    → 404 text/html
```

So the media caveat is **not** a provider blocker — the download chain above serves the
same titles at the same resolutions. It is the effective resolver; the flashvars branch
stays wired as a fallback (probed per candidate, dead ones skipped, so it can only ever add
a working link) because "which files exist on the origin" is a per-title server fact, not a
constant.

## Walled pages — sample and drop rule

`GET /login-required/ → 200` with only the login modal (2,053 bytes, no `html` shell, no
JSON-LD, no `video_id`): the same surface the download dialog's **Upgrade** button opens
(`data-href="https://yesporn.vip/login-required/"`). Captured as
`YesPornVip/src/test/resources/walled-sample.html`.

Rule: a page with neither a `VideoObject` JSON-LD block nor a `video_id` flashvar returns
`metadata = null` → `load()` returns null → the card is dropped. No
metadata-without-streams rows. (Unknown ids answer `404` with a 384-byte stub — same
outcome.)

## Metadata fields (detail page, video 76685)

| field | source | value on 76685 |
|---|---|---|
| title | JSON-LD `name` (fallback `h1.title`, then `video_title`) | `The Threesome Tutorial FHD` |
| poster | first `div.block-screenshots a.screen-img img` (`data-original`); fallback JSON-LD `thumbnailUrl`, then `og:image` | `…/76000/76685/390x218/1.jpg` |
| plot | `meta[name=description]` | "Watch The Threesome Tutorial FHD with Serena Santos, … Big…" |
| tags | JSON-LD `keywords` (fallback `video_tags`) | big tits, latina, threesome, brunette |
| categories | JSON-LD `genre` (fallback `video_categories`) | Threesome, Brunette, Latina, Big tits |
| actors | JSON-LD `actor[].name` (fallback `video_models`) | Serena Santos, Vanessa Sky, Tyler Nixon |
| duration | JSON-LD `duration: PT0H8M00S` via shared `JsonLdParse.minutes` | **8** minutes |
| year | JSON-LD `uploadDate` (`2026-11-21T11:44:44-05:00`), fallback `meta[property=video:release_date]` | **2026** |

Actors are the video's **models** (`video_models` == JSON-LD `actor`), not the site member
the page also links (`/members/15878/`). `a[href*="/models/"]` on the page lists the same
three names (verification: `field 'actors': present on all 3 sampled pages`).

### Poster caveat (verification check 5 is a NOTE here, deliberately)

This site serves **two different files** for a video's artwork: the listing cards and the
detail page's screenshot tab use `…/390x218/1.jpg` (21 KB), while `og:image` and the JSON-LD
`thumbnailUrl` use `…/preview.jpg` (212 KB) — different files, not size variants of one path
(hand-checked on 61519: card `390x218/1.jpg` vs `og:image` `preview.jpg`).

The provider emits the **card** artwork so a card and its load screen agree in-app
("Distinct" bar). verify.sh's check-5 proxy compares the search card against
`meta[property=og:image]`, so an overlapping sample would report a poster mismatch that is a
site property, not a provider defect — hence the sampled titles do not overlap the search
query and check 5 prints its NOTE. Title/poster agreement was verified by hand instead:

```
card  : 61519 "Lisa Ann, Phoenix Marie - Brazzers Live 36: Milfmania 2" / 390x218/1.jpg
load  : og:title same / first screenshot == card poster     → TITLE AGREE: True POSTER AGREE: True
og:image (unused hero): …/61000/61519/preview.jpg
```

## Related videos — 36 cards, deduped against the title

`#list_videos_related_videos_items` ("Related Videos") carries **36** `div.thumb` cards on
all three sampled titles, all unique, and the title being watched is not in its own strip
(76685 / 76391 / 75971, live 2026-11-27). The strip's `i.yp-fresh data-show="12"` only
controls how many are visible before expansion — all 36 are in the same HTML, so all 36
become `recommendations`. The separate "Recommended Videos" aside
(`#list_videos_recommended_videos_items`, 16 cards) is a different strip and is not mixed
in; it repeats 7 cards from the related strip (union = 52 cards / 45 unique), which is why
`related()` is scoped by id rather than by `div.thumb_rel`.

## Other notes for the verification record

- **Quick search**: none — `hasQuickSearch = false` (no suggest endpoint was ever probed;
  decision 3 of the plan's round 2). check 1b is therefore a NOTE.
- **Sleeping selectors**: `year` has no DOM selector in this theme (the stats row prints
  `11/21/2026` without a class hook), so verify.sh was run without
  `--video-year-selector`; the year comes from JSON-LD / `meta[property=video:release_date]`
  and check 6 asserts the assignment in code.
- **No `audits/findings.json` edit** (ADR-0013). Nothing under `shared/` was touched — the
  KVS flashvars grammar is used as-is and no unregistered embed host was hit (the streams
  are the site's own `get_file` URLs, not third-party embeds).
- **Canonicalising 301**: `/video/76685/the-threesome-tutorial-fhd-bzua6a/` 301s to
  `…-ebk44f/` (the plan noted `…-2av635/`; the slug rotates — the page's own canonical links
  disagree too, e.g. `/embed/76685` declares `…-2av635/`). The provider keys on the video id,
  so every slug spelling loads.

## Verification

```
.pi/skills/verify-provider/scripts/verify.sh \
  --search-url 'https://yesporn.vip/search/brazzers%20live/' \
  --search-url 'https://yesporn.vip/search/brazzers%20live/?from_videos=21' \
  --search-selector 'div.thumb' --search-title-selector 'div.title' \
  --home-url 'https://yesporn.vip/latest-updates/' \
  --home-url 'https://yesporn.vip/latest-updates/2/' --home-selector 'div.thumb' \
  --video-url 'https://yesporn.vip/video/76685/the-threesome-tutorial-fhd-ebk44f/' \
  --video-url 'https://yesporn.vip/video/76391/sweet-as-sugar-episode-3-ebk44f/' \
  --video-url 'https://yesporn.vip/video/75971/wheelies-during-anal-fucking-ebk44f/' \
  --stream-url '<1080 get_file URL of 76685>' \
  --stream-url '<1080 get_file URL of 76391>' \
  --stream-url '<1080 get_file URL of 75971>' \
  --stream-selector 'div#kt_player' \
  --video-title-selector 'meta[property=og:title]' \
  --video-poster-selector 'meta[property=og:image]' \
  --video-plot-selector 'meta[name=description]' \
  --video-tags-selector 'a[href*="/tags/"]' \
  --video-actors-selector 'a[href*="/models/"]' \
  --video-duration-selector 'div.count-item' \
  --related-selector 'div.ralated-holder div.thumb' \
  --provider-src YesPornVip \
  --load-response 'recommendations,tags,plot,duration,year,actors,posters'
```

`--stream-url` overrides are the download-chain URLs from the table above: the static page
carries the dead trim URLs, so the play-time chain is what the app resolves. The 480/720
links of all three titles were asserted separately (`206 video/mp4`, see the table).

Run 2026-11-27 used these signed 1080 URLs (tokens rotate per request — re-resolve with the
chain above rather than replaying them):

```
76685  https://yesporn.vip/get_file/5/0b1de4aa359b3ccb63e60a6ffbed33c85a6f66dd1c/76000/76685/76685_1080p.mp4/?download_filename=the-threesome-tutorial-fhd-ebk44f_1080p.mp4&download=true&br=3320
76391  https://yesporn.vip/get_file/5/725c2f8783e437b632559df8e063efb99bb428db06/76000/76391/76391_1080p.mp4/?download_filename=sweet-as-sugar-episode-3-ebk44f_1080p.mp4&download=true&br=3446
75971  https://yesporn.vip/get_file/5/e08121d6368400b5239b9f062027687c4e8db02c3a/75000/75971/75971_1080p.mp4/?download_filename=wheelies-during-anal-fucking-ebk44f_1080p.mp4&download=true&br=4219
```

Result: **PASS** — search 2/2 pages (20 cards each, no duplicates), home 2/2 pages, 3 video
pages, 3 streams `206 video/mp4`, tags/actors/duration present on all 3, related strip
non-empty and self-free, check 6 all seven `LoadResponse` fields assigned. NOTEs: quick
search absent; no `--video-year-selector`; check 5 (see the poster caveat above).

Unit level: `./gradlew YesPornVip:test` green — 4 new S3 test classes
(`MetadataTest`, `DownloadLinkTest`, `FlashvarsTest`, `RelatedTest`, `WalledTest`) over live
captures `video-76685.html`, `video-76685-download-{480,720,1080}.html`,
`walled-sample.html`; `./gradlew YesPornVip:make` clean.

## Risks

- The download chain depends on the download dialog staying open to guests; if it is ever
  gated, the flashvars trim branch is the only remaining path and it is dead today
  (Blocked condition). Watch for drift in `/view_video_download.php` — it is the single
  stream surface.
- `format=` fallback detection is filename-based (`_<q>p`; tokenless = 480). If the site ever
  starts answering a lower rung for a higher `format=`, the provider skips that format
  instead of mislabelling it (never a fabricated quality), so a short ladder degrades to
  fewer links, not wrong ones.
