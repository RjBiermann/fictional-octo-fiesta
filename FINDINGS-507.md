# FINDINGS — Issue #507: uncensored homepage rows for all JAV providers

Probed 2026-09-30 (plain curl, residential runner). All 6 JAV providers in this repo
were audited for an existing "Uncensored"-type homepage row, then each row URL was
fetched live.

## Inventory

| Provider | Uncensored row in `mainPage` | Row URL | Live probe (page 1) |
|---|---|---|---|
| JavGuru   | yes — "Uncensored" | `https://jav.guru/category/jav-uncensored/` | 200, ~52 video hits |
| Javtiful  | yes — "Uncensored" | `https://javtiful.com/uncensored` | 200, ~291 hits |
| Javmost   | yes — "Uncensored (uncensor::category)" | showlist2 JSON API | 200, ~182 hits |
| Javbangers| yes — "Uncensored" | `https://www.javbangers.com/categories/uncensored/` | 200, ~169 hits |
| MissAV    | yes — "Uncensored Leak" plus uncensored studios (FC2, Madou, HEYZO, 1pondo, Caribbeancom, 10musume, Pacopacomama, Gachinco, Tokyo Hot, XXX-AV…) | `https://missav.live/dm628/en/uncensored-leak` | 200, ~44 hits |
| **Javseen** | **none** — gap this issue fills | — | — |

## Javseen: uncensored surface on javseen.tv

- Homepage nav exposes exactly one uncensored link: `https://javseen.tv/tag/uncensored/`
  (transcript: `<a href="https://javseen.tv/tag/uncensored/"`).
- `https://javseen.tv/category/uncensored/` is NOT a video grid — it renders the
  "Video Categories" panel page (0 video cards). Not usable as a row.
- The bare-slug path `https://javseen.tv/uncensored/` (the form the existing rows
  `/solowork/`, `/cosplay/` etc. use) has no uncensored panel: its ajax endpoint answers
  `{"status":0,"html":"","pagination":"","total":0}`.
- The tag page itself (`/tag/uncensored/`) serves skeleton placeholders (`fp-sk-*`,
  `sk-pulse`) in the HTTP body; the browser fills the list with
  `fetch('?ajax=browse_videos')` — evidence in the page's inline script:
  `fetch('?ajax=browse_videos', { signal: controller.signal })`.

### Tag ajax endpoint (the provider-usable surface)

```
$ curl 'https://javseen.tv/tag/uncensored/?ajax=browse_videos'
{"status":1, "html":"…<ul class=\"videos\"> <li id=\"video-286999\"> …",
 "pagination":"…<a href=\"/tag/uncensored/2\"…", "total":30,
 "next_url":"https://javseen.tv/tag/uncensored/2/"}
```

Page 2:

```
$ curl 'https://javseen.tv/tag/uncensored/?ajax=browse_videos&page=2'
{"status":1, "html":"…<li id=\"video-286035\">…", "total":30,
 "next_url":"https://javseen.tv/tag/uncensored/3/"}
```

- Card markup is the same JSON `{status, html}` envelope the provider already parses —
  selector `li[id^=video-]` with `a.thumbnail`, `span.video-title`, and poster `img`
  inside. No parse change needed; only the row URL and pagination URL differ.
- Pagination: `?ajax=browse_videos&page=N` — NOT the `/recent/{N}/?ajax=category_videos`
  form used by the existing category-tag rows (`/tag/uncensored/recent/2/` 404s; the
  bare-slug page-2 form returns status 0). The tag surface is open-ended — page 3+ keeps
  returning fresh 30-card pages with `next_url` advancing (`/4/`, `/5/`, `/6/`).
- `?ajax=category_videos` on the tag URL returns the full HTML page (not JSON), and the
  panel's own script uses `browse_videos` — so the tag row must use `browse_videos`.
- Referer is not load-bearing anywhere on the ajax surface (probed 2026-10-02):
  `/solowork/recent/2/?ajax=category_videos` returns an identical 30-card, status-1 body
  with the row URL, the paginated `{cat}/recent/2/`, or no `Referer` at all — the
  round-1 header change is behavior-neutral, and the tag row works for the same reason.

## Other 5 providers

No code change: each already has a live uncensored row (table above). Verified only —
drift fix is out of scope for this issue.

Page 2 of the Javseen tag row shares zero card overlap with page 1 — re-verified live
2026-10-02 (30 cards each, empty intersection).

## verify.sh sweep (old uncensored rows, untouched providers)

All five pre-existing uncensored row URLs fetched live, all HTTP 200 with video cards
(inventory table above; MissAV's original `dm263` in an earlier draft was a doc error —
it is Monthly Hot and 301s to `dm817`; the row URL the provider actually uses (`dm628`)
loads 200). No selector drift found; no other provider changed.

## Risks / blockers

None code-side. The Javseen uncensored tag is open-ended: probes 2026-10-02 confirmed
pages 2–5 each return 30 cards with `next_url` advancing (`/6/` on page 5) — `"total":30`
in the ajax envelope is the per-page count, not a tag total. Rows paginate indefinitely;
small risk if the tag is ever sparsely populated.
