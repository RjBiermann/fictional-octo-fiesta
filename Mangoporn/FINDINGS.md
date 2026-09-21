# FINDINGS — Mangoporn (issue #443)

Source port: https://github.com/Kraptor123/Cs-GizliKeyif/tree/master/Mangoporn

## Site

- `https://mangoporn.net` — WordPress "Dooplay" theme, plain HTTP 200, no Cloudflare
  challenge, no age gate observed (plain curl, UA Firefox/144).

## Probe transcripts (curl, 2026-02-14)

### Home `/` and category pages `/movies/page/N/`, `/genre/<slug>/`
- 200, ~245 KB. Cards: `div.items > article` (36 per page).
  - title/link: `div.data h3 a[href]`
  - poster: `div.poster img` — lazy-loaded: real URL in `data-wpfc-original-src`,
    `src` is a blank gif (wp-fastest-cache). Fallback `src` needed.
- `mainPage` categories: `movies` (Latest Release), `movies/random` (Random —
  provider rewrites to `/movies/page/<random 1..2000>/`), `genre/<slug>` slugs,
  `year/<y>`, `trending/`, `ratings/`, `xxx/studios/<slug>` etc. (full table in
  MangoAyarlar.kt). Verified `/movies/page/2/` and `/genre/asian/` → 200 with 36 articles.

### Search `/?s=<query>` and `/page/2/?s=<query>`
- 200, ~256 KB. Result cards: `article` with `div.image a[href]`,
  `div.image img[data-wpfc-original-src|src|data-src]`, title `div.details a`.
- Provider fetches pages 1–2, stops on empty/duplicate page. Verified page 1 has 36 articles.

### Load `/movies/<slug>/`
- 200, ~297 KB.
  - title: `div.data > h1` → "Cum Gushers"
  - poster: `div.poster > img[data-wpfc-original-src]` (blank-gif `src`, so
    wpfc attribute is mandatory)
  - year: `span.textco a[rel=tag]` inside year link `/year/2006/` → 2006
  - duration: `span.duration` — **absent on probed pages** (provider handles null)
  - plot: `div.wp-content[itemprop=description] > p` → present
  - actors: `div.persons a[href*=/pornstar/]` → 14 links (Pornstars box)
  - tags: `span.valors a[href*=/genre/]` → "Cumshot" (Genre box, same `span.valors` markup)
  - recommendations: `div.sbox.srelacionados article` → "Similar titles", 12 items
- Stream tabs: `div#pettabs > ul a[href]` — probed movie: luluvid.com (Lulustream),
  playmogo.com (Dood), playmate.to, mixdrop.my, voe.sx, plus rapidgator/nitroflare/frdl.io
  file-locker links the provider filters out.

## Stream hosts seen (map to extractors)

| host | extractor |
|---|---|
| luluvid.com/e/… | LULUBASE family (shared) |
| playmogo.com/e/… | DoodStream family |
| playmate.to/embed/… | Playmate (shared) |
| mixdrop.my/e/… | MixDrop (framework) |
| voe.sx/e/… | Voe (framework) |

## Port decisions

- Provider MainAPI + settings UI (`MangoAyarlar`) ported near-verbatim; settings
  dialog needs `appcompat`/`recyclerview`/`material` (deps copied per issue).
- Extractors: the shared/ host table (Extractorlar.kt) already contains byte-equivalent
  ports of the same upstream code (Filemoon/Byse resolver incl. PoW+captcha path,
  Streamwish, VidHidePro, DoodStream, StreamTAPE, Player4Me, LULUBASE, VidNest,
  Playmate, Vidguardto, Turtleviplay). Per ADR-0002 no extractor duplication:
  plugin registers shared hosts first, then the provider-only extras (CloudWish
  incl. its packed-JS unpack, Javclan/Javggvideo/swhoi/MixDropis/Javmoon/StbP2P/
  Playerupnone/MixDropAG/MixDropMy/Turboplayers + mirror rows).
- VidHidePro body uses framework `getPacked/getAndUnpack` (present in the pinned
  pre-release jar) instead of shared `PackedJs` — ported as-is.
- Framework `DoodLaExtractor` hardcodes `dood.la` pass_md5 host handling; the
  provider's DoodStream override (myvidplay.com base) is required for playmogo — kept.
