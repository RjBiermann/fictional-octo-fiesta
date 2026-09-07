# FINDINGS — watchporn.to (2026-09 audit)

## Verdict: OK

## Search
- `/?s=red` → 200, `div.thumb.item` cards (52; watch grid-ad class — provider toSearch filters? cards all `div.thumb item`).

## Stream
- KVS flashvars: `video_url ..._720p.mp4/?v-acctoken=...` and `video_alt_url ..._1080p.mp4` → **206 video/mp4**.
- Provider already uses WebView to fetch — flashvars still present in HTML.

## Plot (audit #133)
- `p.single__content-description` on video page contains the description text (evidence: line 360 of /video/139460/... probe).
- Also present duplicated in `meta[name=description]` and `meta[property=og:description]` (tag-polluted); the `<p>` is the clean source. Year/uploadDate not exposed on page.
