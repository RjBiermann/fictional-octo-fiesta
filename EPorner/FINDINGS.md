# FINDINGS — eporner.com (2026-09 audit)

## Verdict: OK (no code change)

## Search
- `https://www.eporner.com/search/red/` → HTTP 200; selector `div#vidresults div.mb` matches 28 results (`p.mbtit a`, `div.mbimg img`).
- Pagination `/search/{q}/{page}/` intact.

## Video page
- `/video-sQPApLnGpP4/big-red/` 200; `h1`, `meta[property=og:image]`, `span.vid-length`, related `div#relateddiv div.mb` all present.

## Stream
- Provider flow reproduced with curl: `/embed/{id}/` → `EP.video.player.hash = '2e1700dd...'` → md5 8-chunk base36 → `/xhr/video/{id}?hash=...` → JSON `sources.mp4["1080p HD"].src = https://vid-s13-n50-fr-cdn.eporner.com/...16007210-1080p.mp4`
- STREAM CHECK: `curl -r 0-1000` → **206 video/mp4** (works with and without Referer).

## Risks
- gvideo/`xhr` URLs are signed/time-based — expected; player regenerates.

## Update (2026-02 fix, issue #174): actors

- `span.valor` gone from video pages: `grep -c 'span.valor'` → 0 matches on live page
  (https://www.eporner.com/video-goBad5wkYV3/…). Cast markup removed entirely
  (`grep -ioP 'class="[^"]*(cast|starring|actress)[^"]*"'` → 0 matches).
- "Starring:" now appears only in `og:description`, e.g.:
  `og:description" content="Watch 橘メアリー [Uncensored], … Squirt - Mary Tachibana. Starring: Mary Tachibana. Duration: 136:17, …"`
- Not every video carries it — 1 of 5 probed pages had "Starring:"; others end at the
  model/title text with no Starring clause. Parser must no-op gracefully.
- Fix: parse actors from `og:description` text between "Starring:" and ". Duration",
  comma-split; keep `span.valor a` as a no-cost first choice.
