# FINDINGS-648 — Neporn deep audit (re-run of lost run 21 / 2026-10-16)

Verdict: **ok** (unchanged since 2026-10-09). Tier: **plain-curl** on every surface —

- home p1 200, 24 cards (`div.list-videos div.item`)
- search `/search/anal/` 200, 24 cards; async p2 200, 24 cards, disjoint (`li.page.last` halts pagination)
- quickSearch: absent (recorded, not drift)
- 3+ video pages 200

Stream: KVS flashvars `video_url` → `get_file/.../{id}_720p.mp4/?v-acctoken=...` → 302
(`data00X.neporn.com/remote_control.php?...`) → **206 video/mp4** (session-bound bearer;
bare `get_file` without acctoken = known 403 session-bound class, not drift).

## Site facts

- latest-upload video slugs are volatile — older slugs 301 to fresh slugs. Provider uses
  fresh page hrefs, no impact.
- Model anchors on newest videos absent (`video_models: ''` in flashvars block) — actors
  optional; older scenes still expose `/models/` anchors.

## verify.sh (PASS)

- search 200/24; home p1/p2 200/24+24; 3 video pages 200; 3/3 stream URLs → 206 video/mp4;
  related `div.related-videos div.item` 22/video; tags present on all sampled pages;
  LoadResponse completeness all 7 fields assigned.
- Instrument-limited NOTEs (not site conditions, evidence above in ground truth):
  1b quick-search NOTE = quickSearch absent; actors NOTE = pydom regex-DOM engine limits
  (`a[href*=/models/]` matches live — verified by curl grep on older scenes) + newest
  videos genuinely carry no models; duration/year NOTE = same engine limits
  (`Duration: <em>12:46` and ld+json `uploadDate` verified by curl grep on all 3 samples).

## Gates

- `gradlew Neporn:test` PASS · clean `Neporn:make` yes · `check-findings.sh` PASS
- No confirmed condition → no code change, no version bump (Neporn stays 7).

History: first implement run 38961910251 completed this audit but its PR create was
rejected on the base-field guard; all changes were re-applied this run.
