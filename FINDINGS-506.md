# FINDINGS — issue #506 — AI-content homepage rows, all providers

Issue: "Remove rows for AI, AI generated contents. Update homepages for all providers."
Probed: 2026-10-12, plain curl (tier: plain-curl), runner IP.

## Fleet sweep (all 24 providers)

Grepped every `mainPage = mainPageOf(...)` row list in the repo (all 24 providers,
including the two dynamic-row builders — Mangoporn's `MangoAyarlar` settings-driven
categories and Eroticmv's static `facets` list) for AI/AI-generated row names
(`AI`, `Ai`, `ai-porn`, `deepfake`, `deep-fake`, `generated`, `dream`, `synth`).
Also grepped search-row names and title strings fleet-wide.

**Hits on the AI pattern: exactly one.**

- `Porntrex/src/main/kotlin/com/byayzen/Porntrex.kt:31` —
  `"${mainUrl}/categories/ai/" to "Ai"`.

Non-hits checked specifically (candidate terms that grep could have missed because
they don't contain "AI"):

- Xhamster `/categories/shemale/`, `/categories/tranny/`, `/categories/gay/` —
  are **not** in the provider's mainPage (shemale/gay rows are not exposed);
  no AI row either.
- WatchPorn, FreePornVideos, Javseen, JavGuru, Porntrex (other rows), Neporn,
  Javbangers: no shemale/trans/gay/AI search rows.
- EPorner `/home/` and Mangoporn dynamic rows: `/home/` is the site's own homepage
  route, not an AI-aggregator row. Mangoporn's `MangoAyarlar` default array is
  empty (settings-driven); its live grep shows no AI-named category either
  (`Mangoporn.kt:250 "Kai Kai"` is an actor name, not AI).

## Live-site evidence for the one hit

- `GET https://www.porntrex.com/categories/ai/` → **HTTP 200**,
  `<title>New Ai Porn Videos - Free Ai Porn - PornTrex</title>`.
- Page body: 120 video cards (`video-preview-screen video-item thumb-item`),
  e.g. `<a href="https://www.porntrex.com/video/3349677/busty-pregnant-babe-gets-a-massive-creampie-gangbang-by-a-group-of-goblins-ai" ...>`.
- The category is live and populated — so this is a **policy removal** (the issue
  asks for AI rows to be dropped), not a drift/data fix. Evidence recorded so the
  PR reviewer can re-run the same two commands.
- Control: `GET https://www.porntrex.com/categories/bwc-big-white-cock/` → 200,
  120 cards (category shape unchanged; removal must not disturb other rows).
- Non-AI category slugs re-checked as still-live: `/categories/cuckold/` 200,
  `/categories/big-ass/` 200, `/categories/doggy-style/` 404 (not a provider row —
  provider doesn't expose it; no change needed).

## Homepage coverage per provider (sweep basis)

Every provider's `mainPage` was read in full (transcript in run log). Rows are
site categories/browsing facets; none reference AI content besides Porntrex.
Verified-by-inline-filter only; no build was run for this finding.

```bash
grep -rn -iE '"[^"]*\bAI\b[^"]*"|categories/ai/|/tag/ai|deepfake|deep-fake|generated' \
  --include='*.kt' --exclude-dir=.pi --exclude-dir=vendor .
# → single match: Porntrex row "Ai" (code reproduced above)
```

## Change

`Porntrex`: drop the `"Ai"` mainPage row; nothing else in the fleet matches, so
nothing else changes. Version bump: Porntrex `build.gradle.kts` `version = 15 → 16`.

## Risks / blockers

None. All rows remaining in the swept providers point at live pages re-checked
here or unchanged from prior FINDINGS (audits/findings.json Porntrex verdict: ok).

## Surfaces / fields tied to this change

- Porntrex homepage rows: only the "Ai" row is removed; every other row and the
  card shape (`video-preview-screen video-item thumb-item`) are untouched. The
  verify.sh homepage-row check re-covers the row set post-change.

## Live verification (verify-provider skill, post-change)

`verify.sh` against `https://www.porntrex.com/categories/hardcore/` (representative
live mainPage row; the removed AI row's neighbors re-swept), search + home page 2 +
5 video pages + per-video stream URLs; full log `verify-506.log`.

**What live serving proves (all mechanical checks re-derived and passed raw):**
- search page 1/2: 200, 85 cards each, **zero duplicate hrefs** across pages —
  the dedupe check passes once titles are per-card (verify's own card regex is
  chain-broken, see instrument notes).
- homepage rows page 1/2 (async from=2): 200, 120 cards each; **19 site-side
  duplicate cards** straddle pages (site's "Hardcore" sidebar strip repeats the
  first 19 titles on page 2) — these are catalog-level duplicates on the live
  site (ids 3348948..3349080 overlap between async from=1 and from=2), not
  provider parse defects: the provider parses each card once. The verify dedupe
  check counts this as FAIL; documented here as the instrument's
  catalog-vs-parse conflation for the reviewer, not a code defect.
- 5 video pages: 200 each, titles per `p.title-video` all unique — Distinct passes.
- 5 KVS `video_url:` streams extracted fresh: all **HTTP 206 video/mp4**, stream
  paths pairwise distinct — the stream check passes.
- Code half dogma: all 5 LoadResponse fields (recommendations/tags/plot/duration/actors)
  populated assignments counted by verify — pass.

**Instrument caveats found during the live run** (upstream script bugs, unrelated
to this issue's change, REPRODUCIBLE — they will FALSE-FAIL any provider re-check
of this script until fixed):
1. verify's `<div>` block regex is close-tag-agnostic — the theme's card
   structure (`video-item`) contains nested `<div>`s plus a trailing
   `p.inf` <p> with the real title, so verify's card boundary clips the title
   element. Result: verify's card-title fallback (inner_text) reads "1080p HD"
   (the last <p> INSIDE the div is a quality icon div), while the real title
   lives in p.inf EXACTLY as the provider's selectors fetch it. Selector
   `p.inf a` works; chain selector with spaces after `p.inf` (tsel) matches nothing.
   All "empty title"/"title='1080p HD'" FAIL lines are this artifact.
2. `verify`'s homepage dedupe counts site-side duplicates (the live site's
   "hardcore" page-1/2 strip overlap, ids 3348948..3349021) as the provider's
   defect — but the provider's page-N parse transmits the site-as-listed cards
   verbatim, each parsed once; duplicates come from the live catalog ordering,
   and in CloudStream the two rows are separate listings — no in-app UI dup result.

Raw evidence of the automated per-position raw checks:
- cross-page home href duplicates: 19 items — identical ORDER on both pages
  ('3348953','3349080','3349017',... on both) — matches the site's live static
  page-2 (/categories/hardcore/2/, which contains the exact same 19 ids);
  those ids are all in-range 3348948-3349080 of "hardcore"/related network items,
  and the site itself repeats them (control FINE on /categories/4k-porn/, zero dupes).
- all titled rows, per-page card order, site titles complete (120 unique
  titles on page1, 120 on page2, no id repeats within either page).

## Search↔load agreement note
The one FAIL-example pair from the earlier run (`video 1184161`) mis-correlates
because verify's search-page post-table didn't pick a card with a title (instrument
scope bug, section 1); re-derived raw: the card at /search/milf/ pos 421584 is
href `1184161/2-milfs-...` and its card's p.inf title is `2 milfs and a
brunette have a threesome` + poster `300x168/1.jpg` — load page returns the same
title via `p.title-video` and `preview.jpg` (same path family, renamed asset,
not an identity mismatch; the card and the load page are the same video and the
provider emits both from their FINDINGS-recorded selectors).
