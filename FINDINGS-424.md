# FINDINGS-424 — JavGuru: poster blank in library

Issue: When a JSAVGuru video is saved to the CloudStream library, the poster shows blank.

## Probe (live, 2026-09-16)

- Video page `https://jav.guru/1052503/` loads fine (193 532 bytes) with browser UA.
- Load-page poster selector `div.large-screenshot img` **is** present and `src` is populated
  (no lazy `data-src`): `https://cdn.javmiku.com/wp-content/uploads/2026/09/1dldss554pl.jpg`.
- Search/home posters also use `cdn.javmiku.com` `src` (no `data-src` anywhere).

### The real problem: the poster host

All JavGuru imagery moved to `cdn.javmiku.com`, a **Cloudflare-challenged zone**:

```
curl cdn.javmiku.com/.../1dldss554pl.jpg                         -> 403 (Cloudflare managed challenge, "Just a moment…")
curl ... with Referer https://jav.guru/                          -> 403
curl ... full browser UA + referer                               -> 403
curl https://jav.guru/wp-content/uploads/2026/09/1dldss554pl.jpg -> 200 (with Referer+UA)
```

So the same `/wp-content/uploads/…` path serves **200 from the main domain** `jav.guru`
(UA + Referer required, which `posterHeaders = mainHeaders` already supplies), and a
Cloudflare JS challenge from the CDN host. No HTTP client in CloudStream – and no image
loader – can pass a managed challenge, hence blank posters (in library and anywhere the
image must be loaded cold).

## Fix

Rewrite the poster image host `cdn.javmiku.com` → `jav.guru` in a pure Parse function
(`JavGuruParse.parsePosterUrl`) applied at every poster surface (search/home cards,
load poster, recommendations). Stream extraction and page HTML are untouched; pages are
fetched from `jav.guru` already.

## Verification results (2026-09-16)

- Unit tests: 44/44 green incl. new `poster host rewritten to jav dot guru` (red → green).
- `gradlew JavGuru:make` → JavGuru.cs3 built, version 27 → 28.
- verify.sh: search (1/page + page-2 `s=big+ tits` 24 cards) 200, homepage `/` + `/page/2/`
  24×24 distinct, 5 varied video pages 200 with `h1` + tags + year, 5 provider-resolved
  m3u8 streams all **206 application/vnd.apple.mpegurl**. RESULT: PASS.
  - Script NOTEs: poster check prints "no poster" because verify.sh's `field` only extracts
    `content` attrs (og:image shape) and can't read `img@src`; poster load was proven directly:
    **all 5 sampled load pages' rewritten poster URLs return `200 image/jpeg`**, while the
    original `cdn.javmiku.com` URLs return **403 on all 5** (probe above).
  - Pre-existing script bars unchanged for this site (JavGuru/FINDINGS.md): most-watched-rank
    row markup (`article.rank-item` vs homepage `div.inside-article`) can't share one
    `--home-selector`; og:meta absent; actors selector uses `:has(...)`/`:matchesOwn` which the
    mini-selector parser can't express (parseActors covered by unit tests instead).
- Poster fix evidence (per video): old=403 → new=200 image/jpeg for 1052503, 864222,
  1052322, 1052741, 1052744.
