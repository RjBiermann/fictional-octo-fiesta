# Pipeline Verification — XMoviesForYou (issue #653)

Live checks against https://xmoviesforyou.com (2026-10-16), run via
`verify-provider/scripts/verify.sh`. Full transcript: run PR + FINDINGS-653.md.
**Result: PASS (all mechanically expressible checks).**

**Instrument tier: TLS-impersonated.** Plain curl is CF-challenged (403, canary-consistent,
FINDINGS-535-era repeat). verify.sh was driven through a `curl_cffi`-chrome shim on PATH
(tier-2 instrument: same plain HTTP, browser TLS) — one shim touch this run, both
documented: the shim **must not honor `-A` UA overrides** (an overriding UA pairs the TLS
fingerprint with a mismatched UA and the site 403s again); verify.sh's default UA is thus
dropped in favor of the impersonated browser UA. No other instrument change;
`verify_selfcheck.sh` untouched.

| check | result |
|---|---|
| Search q=faketaxi + q=milf, p1+p2 each (/{search} →302→ /new-search URL served fresh) | 200; `a.card` 24 ×4 pages; no dupes within or across pages ✓ |
| Home `/` + `/?page=2` (`a.group.flex.flex-col` paginating grid row) | 200; 24 ×2; no dupes across pages ✓ |
| Quick search | FINDINGS records **no distinct endpoint** (`/api/search`, `/api/suggest` 404 on TLS tier; `/search/suggest` = full-page fallback) — NOTE expected |
| Video ×3 (home→home, home→home, search→search listings) | 200; `a[href*=streamtape]` 1 each; h1 title each ✓ |
| Streams ×3 (shared StreamTAPE chain: `/v/<id>` → expires/ip/token → `get_video` 302 → tapecontent) | 206 `video/mp4`, fresh tokens, paths distinct ✓ |
| Field exposure | tags, actors present on all 3 ✓; duration NOTE — FINDINGS records **site does not expose duration** ✓; year NOTE (chip is present live on all 4 FINDINGS-sampled pages; not pydom-expressible with `:contains`) |
| Search ↔ load agreement | sampled `q=milf` card ↔ `/milfty-lindsay-lee-…` load page: title (card h3 = h1) and poster path agree ✓ |
| LoadResponse completeness | recommendations, tags, plot, year, actors, posters all populated in the provider source ✓ |

**Not mechanically verified this run — live-evidenced in FINDINGS-653.md instead:**

- **4th sampled video** (`/milfaf-justine-jakobs-the-missing-towel`, bigwarp.io + mixdrop.ag
  only): no single pydom-expressible selector matches both anchor families on one page
  (`a[href*='streamtape.com']`-style dotted attr values fail pydom's class-token regex;
  comma-chained selectors return -1), so the page is outside the verify invocation. Its
  mixdrop chain was resolved and served manually: mixdrop.ag/f → mxdrop.click/e packer →
  `MDCore.wurl` → `*.mxcontent.net/v2/<id>.mp4` **206**. Its bigwarp.io anchor 403s (CF,
  probe-IP). This is exactly the page whose mixdrop link the pre-fix provider *did* emit but
  whose family coverage motivated the registry rows.
- **dood.to chain**: Turnstile captcha shell from this IP — suspected probe-IP class, chain
  not resolvable from the runner; registered anyway (framework row), dead-host caveat.
- **Carousel cards**: page-1-only, covered by the live probe (30 anchors, repeats, all
  facets parse) + fixture tests; verify.sh cannot express per-page href dedupe
  (FINDINGS-535 hero-carousel addendum, unchanged).

Related videos: API-served (`/api/related/<postId>` 200 ×4, `title`/`slug`/`thumbnail_url`)
— no DOM `--related-selector` exists; provider populates `recommendations` from that API
(check 6 asserted it in source). Unchanged from FINDINGS-535.
