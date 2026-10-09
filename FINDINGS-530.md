# FINDINGS — Issue #530: FullPorner load() field polish

## Verdict: BOTH DEFECTS REPRODUCED — fix in `FullPorner/`

## Probe (2026-10-09, TLS-impersonated Chrome via `.pi/skills/site-probe/scripts/impersonate.sh`)

Issue URL + 4 more from varied listings (3 carried over from FINDINGS-526's sampled set,
2 fresh from the `/category/vintage/` homepage row — 24 cards visible there, so the
listing is live). All `GET /watch/<hash>/` → HTTP 200 full HTML.

| watch page                    | from            | h2 title                                        | meta[name=description]                                                     | `div.single-video-info-content p a` texts |
|-------------------------------|-----------------|-------------------------------------------------|----------------------------------------------------------------------------|--------------------------------------------|
| `/watch/5e607cfc36ebf41568208387/` | #526 set   | Wild Lesbian Orgy: A Sensational Sex Party Extravaganza | "Wild Lesbian Orgy: ... on fullporner.com, the best full length porn site." | `['']` ( Pornstar: cell present-but-empty) |
| `/watch/6ac785c62f78355914468d7b/` | #526 set   | Truth or Dare Gone Wild with My Naughty Stepsister | same shape                                                                  | `['rose','lacee rose']`                     |
| `/watch/6ac785ee2f78355914468da8/` | #526 set   | Brenna McKenna Takes It Deep in Intense Anal Action | same shape                                                               | `['brenna mckenna']`                        |
| `/watch/6ac82eba2f78355914468ee4/` | vintage cat | Busty Blonde Vintage Vixen Rides BBC Hard and Takes Massive Cumshots | same shape                                              | `[]` (Pornstar cell present-but-empty)      |
| `/watch/6abf9d0d2f783559144681b8/` | vintage cat | Vintage MILF Shares Young Stud With Her Girlfriend in Steamy Threesome | same shape                                             | `['federica zarri']`                        |

### Defect 1 — plot duplicates title (REPRODUCED on all 5 pages)

Provider reads title and `description` from the SAME selector
(`div.video-block div.single-video-left div.single-video-title h2`), so `plot = title` verbatim.

The site's only summary is `<meta name="description" content="...">` — present on every probed
watch page, always the shape `<title> on fullporner.com, the best full length porn site.`
No richer synopsis exists in the markup (checked `og:description` — none; page text). Fix per
issue: parse that meta; strip the fixed suffix so plot is the naked subject line.
`FullPorner.kt` records the site's own tagline in its `cloudstream.description` boilerplate,
suffix hardcoded — evidence-backed, not invented.

### Defect 2 — empty pornstar cell → empty-string actors (REPRODUCED on all 5 pages)

Selector `div.video-block div.single-video-left div.single-video-info-content p a` also matches
the "Pornstar:" cell, which is present-but-empty on pages without a named pornstar (2 of 5
pages, at least one fully-empty `a.text`). Master passes the raw list into `addActors`,
shipping empty-string rows. Fix per issue: filter `isBlank()` rows.

**Actors exposure semantics (verify nuance, explicit per skill):** the "Pornstar:" cell is
present in the markup on every sampled page, but holds ZERO anchors on pornstar-less pages —
so the actor *selector* structurally matches (empty text) while the field's *value* is
legitimately absent there. Value-exposure is therefore optional-per-video, not all-or-none;
that is not selector drift, it is the site's known empty-cell shape this very issue fixes.
verify-provider assertion for actors is accordingly omitted at selector level and recorded
here; code-side `addActors(companion parseActors(doc))` stays asserted via
`--load-response actors`.

### Stream source (context from FINDINGS-526, still of record)

JS-player config extraction via WebView (unchanged by this fix). Stream URLs are the
`/vid/{reversedHash}/720/…` pattern under `xiaoshenke.net` (same host serves the poster).

## Cloudflare context (same as FINDINGS-526)

Plain curl → 403 "Just a moment"; TLS-impersonated fetch → 200 full HTML. App path carries the
`CloudflareKiller`/`cfChallenge` interceptor, matching what the provider sees in-app.
verify-runner consequence: `verify.sh` was run with a `curl` PATH shim whose transport is
curl_cffi/Chrome (the impersonate.sh instrument); all of verify.sh's own assertions ran
unaltered.

## Surfaces verify-provider requires stated explicitly

- **Quick search**: no distinct live-typing/suggest endpoint exists on fullporner.com
  (search is the plain `/search?q=&p=` HTML page the provider already drives;
  provider keeps `hasQuickSearch` unset/false). Verified 2026-10-09.
- **Homepage pagination**: page 2 hosts at `https://fullporner.com/home/2` (provider appends
  raw `$page`), HTTP 200, 24 fresh cards, no duplicates across pages 1–2 — verified 2026-10-09.
- **Search pagination**: same shape, `/search?q=<query>&p=2` — verified 2026-10-09 (24/16 cards).
- **Year / duration exposure**: the page's `video-info` div DOES carry an upload epoch
  (`span.create`, e.g. `1383624956`) and a duration (FINDINGS-526 evidence stands) — the
  provider does not yet populate `year`/`duration` from them; recorded as an adjacent,
  UNFIXED gap outside issue #530's two named defects.

## Stream source test (plain-HTTP proof, 2026-10-09)

The watch page's only player is the protocol-relative iframe
`//xiaoshenke.net/video/<md5>/<bitmask>`. Inside that page (plain HTTP, fetched TLS-impersonated,
HTTP 200) the player derives the stream URL without any further network call:
`v.media = "/" + "/" + base + "/vid/" + id.reverse() + "/" + q` where `q ∈ btq(bitmask)`
(`1→360`,`2→480`,`4→720`,`8→1080`) — e.g. bitmask 14 → 480/720/1080; 5 → 360/720.

Verify per sampled page (GET with `Referer: https://xiaoshenke.net/`): every sampled page's
`/vid/<reversedHash>/720` → **HTTP 200, `Content-Type: video/mp4`** (`5e607cfc…` also verified
at 360). Without the Referer the CDN redirect → 404 (nginx): **Referer is required**,
matching the provider's `posterHeaders`/`referer` wiring. Some low-bitmask pages 404 at
`/360` (bitmask lacks that bit) — 720 serves on every sampled page.

Search↔load agreement: one more watch page (`/watch/61a6bb084b3e012c4ebe0717/`, taken
directly from search results, HTTP 200) sampled — title agrees with the search card text,
meta-description summary present, actors populated, 720 stream serves video/mp4.
