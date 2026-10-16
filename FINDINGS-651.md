# FINDINGS-651 — PerverZija deep audit (self-contained run of approved #631 spec)

Verdict: **ok** (unchanged since 2026-10-13). Tier: **plain-curl** on every surface —
canaries 3/3 (film1k plain 403, eporner 200, pandamovies 302→200@.org); no challenge
encountered anywhere, escalation ladder not needed.

- **Home pagination (5 rows)** — featured-scenes / studio / full-movie / tag/4k-quality /
  tag/classic, p1 & p2 all 200, 64 cards each (`div.col-md-3.col-xs-6`), 0 href overlap
  p1↔p2, 0 within-page dupes → carousel-dedup home contract holds on every row.
  Site fact (instrument-relevant): each listing page also contains a hidden grid-clone
  (`div.video-item` ×64/consult coordinates with `div.item-thumbnail` + sidebar
  `div#wpp-*` Popular-Posts widgets); the provider's selector `div.col-md-3:not(#sidebar)`
  does not touch the clones (zero app impact), so the card surface is parsed, not the clone surface.
- **Search** `/?s=anal` p1/p2 — 200, 64 item rows each (`div.post_ajax_tm` two-column
  item: thumb col + title col), 0 overlap/dupes.
- **quickSearch — absent (recorded, not drift).** No suggest endpoint; `?do=suggest` 404;
  admin-ajax only carries `wpfp_meta` votes; "quick view" is a client-side lightbox
  (`data-embed` attr) not an endpoint.
- **Video pages ≥3 (29 sampled this run)** — h1, og:image, og:description (tag-glue
  style), `strong>Tags:`/`strong>Stars:` anchors intact, `dt_rating_vgs` absent (score
  null correct), `div.xs-related-item` ×15–18 per page, titles distinct except
  1 site-native repeat (veronica-leal page: 'Avery Lust – Salvaging Her Birthday' twice;
  jewelz/p4k/veronica pages: 0 dups), never the host title.
- **JSON-LD note (no drift):** `saswp-schema-markup-output` now emits the VideoObject as
  a JSON **array** (`[{"@type":"VideoObject",...}]`). `shared/JsonLdParse` is regex-based
  on the raw string — name/duration/datePublished still match; nothing in the provider
  parses ld+json structurally. Recorded so a future parser migration doesn't trip on it.

## Stream chain (extractor path, direct-call PerverZijaExtractor)

- Player iframe subdomain variety across 29 video pages:
  perv., pervl1–pervl9, pervm1, j2 — and **`moviekh.xtremestream.xyz`** (observed on
  `/private-xtreme-22-a-is-for-anal-2005/`). All handled: the provider calls
  `PerverZijaExtractor().getUrl(iframe, …)` directly and the referer derives from the
  iframe URL, so *any* `*.xtremestream.xyz` subdomain works (no registry match needed).
- Master hop: `xs1.php?data=<token>` (no q) served `#EXTM3U` on **18/18** checked tokens.
- End-to-end (variant q-hop + first segment): **9/18 sampled tokens live** — MPEG-TS
  payload (0x47 sync, FFmpeg) served as `text/html` at `xspcdn*.click` under the
  player-domain referer. The other 9 tokens die at the variant/segment hop (404/403 or
  "Could not find video playlist for quality") — **per-token upstream CDN deadness,
  site-side**, identical in class to previously recorded dead-origin tokens; not provider
  grammar (masters still serve, extractor handles them).
- **Mixdrop embed (1/29 video pages):** `/allanal-lana-anal-rebel-rhyderanal-cravings/`
  embeds `//mixdrop.to/e/enl4jzdjt76r6q` instead of xtremestream. Live probe: the embed
  302 → `mxdrop.click/e/…` → 200 "WE ARE SORRY — We can't find the video you are looking
  for" ⇒ **the file is dead at the source**. The provider's `contains("xtremestream.xyz")`
  guard therefore yields 0 links for a video whose only embed is already unplayable — no
  **confirmed** condition (no healthy site-served stream is missed). Latent fleet gap
  recorded: `shared/HostRegistry` has no `mixdrop.to` row (only mixdrop.is/my/ag) — filed
  as its own finding so a future live mixdrop file becomes player-reachable after the row
  lands. Verdict stays **ok**; findings-only, **no code change, version stays 13**.

## verify.sh (PASS on every mechanically-expressible surface)

7 invocations, `--header 'Referer: https://pervl7.xtremestream.xyz/'` (code-emitted referer
form, accepted across subdomains):

- search p1+200×64 (`div.post_ajax_tm`, p2 disjoint) + featured-scenes row p1/p2 64+64 → PASS
- studio / 4k-quality / full-movie / classic rows p1/p2, each 200 ×64 disjoint → PASS ×4
- deep video run (4 videos: veronica-leal/ld019, lilly-veroni/nf103, hardx-creampies,
  jewelz-blu): search 200×64, home 64+64, video pages 200 ×4, `iframe[src*=xtremestream]`
  1/page, related `div.xs-related-item` ×18/video, tags `a[href*=tag]` + actors
  `a[href*=stars]` present on all 4, LoadResponse fields (recommendations/tags/plot/
  duration/year/actors) assignments found → PASS except one line:

  `FAIL stream path shared by different videos: /player/xs1.php video0..3` — **instrument
  limitation, not a site/provider condition** (same class as the canned-script stream
  limits recorded on this provider's three prior audits): pydom's `path` key strips
  scheme, host and query, and this host's HLS identity lives entirely in `?data=<token>`
  (and the subdomain), so all masters normalize to `/player/xs1.php`. Manual
  substitution (curl transcript in PerverZija/FINDINGS.md): all four master URLs serve
  `#EXTM3U` with distinct data tokens (fba9d881…/46fc943e…/f69543a0…/c14a2a57…) across
  perv./pervl7/j2 hosts.
- Title/poster agreement NOTEs (instrument): search cards carry the WP responsive
  thumbnail (`…-365x235.jpg`) while the load page exposes og:image full-size — path-equal
  compare impossible; poster agreement excluded via pointer selector, poster exposure
  asserted directly on the 4 video pages (og:image on 4/4). Year/duration left as NOTES —
  exposure asserted by curl evidence (JSON-LD `datePublished`/`duration` on sampled pages).

## Gates

- `./gradlew :PerverZija:test` — 65 tests × debug+release, 0 failures (after
  `bootstrapCloudstream` pulled the sha-pinned pre-release classes.jar)
- `./gradlew clean :PerverZija:make` — BUILD SUCCESSFUL
- `.pi/skills/audit-providers/scripts/check-findings.sh` — PASS
- No confirmed condition → no code change, no version bump (PerverZija stays 13).

History: 2026-10-13 deep audit (Xxx, run 17/#631 spec) verdict ok → 2026-10-16 re-run
(this run 38969803445, issue #651) verdict ok, no drift.
