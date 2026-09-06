# FINDINGS — Sexfilm

## Engine fingerprint
DLE (DataLife Engine) based site - identified by dle_root, dle_admin, dle_login_hash variables in JavaScript.

## Search
Working: POST to `/index.php?do=search` with data parameters `do=search`, `subaction=search`, `story={query}`
```bash
curl -s "https://en.sex-film.biz/index.php?do=search" -d "do=search&subaction=search&story=test" | grep "div.short"
```

## Video page
URL pattern: `/{ID}-{title}.html` (e.g., `/11696-devil-in-her.html`)
Selectors:
- Title: `h1#s-title`
- Poster: `div.fleft img[data-src]`
- Description: `div#s-desc`
- Duration: `ul.flist-col li:contains(Duration)`
- Actors: `ul.flist-col li:contains(Casting) a`

## Stream source
JavaScript creates iframes on click with these patterns:
```javascript
var s2 = document.createElement("iframe");
s2.src = "https://filmcdm.top/e/7edxqm7txakn";
```
Three iframe URLs per video:
- Player 1: filmcdm.top domain
- Player 2: playmogo.com domain  
- Player 3: morencius.com domain

## Headers / referer
Critical: iframe URLs require referer header from the original video page to work.

## Pagination
Page pattern: `/movies/page/{page}/` and `/porno-video/page/{page}/`

## Risks / blockers
All iframe domains (filmcdm.top, playmogo.com, morencius.com) block direct requests without proper referer headers. Cloudflare protection requires referer from original site.
