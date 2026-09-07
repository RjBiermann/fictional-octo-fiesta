# FINDINGS — ixiporn (fix probe, issue #120)

Date: 2026-09-07 · UA: Firefox 130 desktop

- Video pages live on `ixiporn.live` (search/home on `ixiporn.org`, absolute hrefs to .live, `fixUrl`-safe).
- Stream source: `<meta itemprop="contentUrl" content="...mp4">` inside `div.video-player` — casing is `contentUrl` (lowercase `rl`), confirmed on two pages:
  - /mona-darling-bts-2026-moodx-hindi-porn-web-series-episode-4 → `https://cdn2.ixifile.xyz/5/Mona.Darling.BTS.S01E04.mp4`
  - /big-boobs-desi-maid-2026-niksindian-uncut-porn-video → `https://cdn2.ixifile.xyz/5/Perfect%20Big%20Boobs%20Desi%20Maid%20gets%20Rough%20Pounding%20Niks.mp4`
- Stream healthy: HTTP 206, `video/mp4`.
- Search: `https://ixiporn.org/?s=indian` → 200, 30 × `div.video-block`.
- Fix: `meta[itemprop=contentURL]` → `meta[itemprop=contentUrl]` (jsoup attr match is case-sensitive); version 16 → 17.
