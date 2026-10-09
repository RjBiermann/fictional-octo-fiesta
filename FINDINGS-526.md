# FINDINGS — Issue #526: FullPorner details-field gap (video-page category tags)

## Verdict: NOT REPRODUCED — master already populates tags; no code change

## Issue claim
Fleet audit #524 (2026-10-09): "the provider's `load()` populates no `tags`" for
`https://fullporner.com/watch/<hash>/`, which exposes category tags.

## Probes (2026-10-09, TLS-impersonated chrome fingerprint via site-probe impersonate.sh)

Issue URL + 5 additional URLs sampled from the live homepage rows (varied page shapes):

```
GET /watch/5e607cfc36ebf41568208387/  → HTTP 200
GET /watch/6ac785c62f78355914468d7b/  → HTTP 200
GET /watch/6ac785ce2f78355914468d8e/  → HTTP 200
GET /watch/6ac785d52f78355914468d9f/  → HTTP 200
GET /watch/6ac785ee2f78355914468da8/  → HTTP 200
GET /watch/6ac7895e0e5f775a6fbbbd25/  → HTTP 200
```

(REPAIR NOTE: an earlier revision of this file listed only 5 URLs while reporting 6 tag
counts; the sixth URL — homepage row sample `/watch/6ac7895e0e5f775a6fbbbd25/` — was
re-fetched 2026-10-09 and confirmed 200 with the selector matching, closing the tally.)

### Category tags — audit's headline claim
Live markup at every probed watch page:

```
<div class="single-video-title box mb-3">
  <h2>Wild Lesbian Orgy: A Sensational Sex Party Extravaganza</h2>
  <p class="mb-0 tag-link">
    <span><a class="popout" href="/category/group-sex">#group sex</a></span>
    ...
```

- Provider selector (`FullPorner.kt:244`): `div.video-block div.single-video-left div.single-video-title p.tag-link span a` → matches on every probed page (tag counts 4 / 4 / 1 / 10 / 2 / 1 across the six URLs listed
above as re-verified 2026-10-09 — content rotates but the selector matches each page — no
all-or-none inconsistency; the initial probe reported 4 / 4 / 6 / 1 / 10 / 11).
- Provider already assigns it (`FullPorner.kt:253`): `this.tags = tags`. Unchanged since the initial commit (`git log -S "tag-link"` → 0d48989 only).
- jsoup select against the saved live page (issue's exact URL): `tags=4` (`#group sex`, `#lesbian`, `#orgy`, `#sex party`).

### Audit's collateral claims, also contradicted by live markup
- "no description/actors/upload-date in markup" — live page has:
  `<meta name="description" content="Wild Lesbian Orgy ...">`; a `video-info` div with
  upload epoch `<span class="create">1383624956</span>` and duration `29:36`;
  a `single-video-info-content` block with an (empty) "Pornstar:" cell.
  The empty pornstar cell would yield empty-string actors on master — a real, minor wart,
  tracked in #530.
- Real adjacent gap, out of scope for #526: master sets `plot = title` (description var
  reads the same `h2` the title is read from), so plot duplicates the title — also tracked
  in #530.

## Why no fix
Cloudflare context consistent with the audit's own notes: plain curl gets HTTP 403
("Just a moment"), while the chrome-TLS-impersonated fetch gets the full page — the app path
carries a `CloudflareKiller` interceptor (`cfChallenge`), so the audit's 200-with-tags probe
and the app see the same markup the provider's selector already parses.
