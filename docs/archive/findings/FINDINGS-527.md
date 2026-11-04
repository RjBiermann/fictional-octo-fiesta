# FINDINGS — Issue #527: HQPorner details field gap (video-page category tags)

## Verdict: NOT REPRODUCED — master already populates tags; no code change

## Issue claim
Fleet audit #524 (2026-10-09, FINDINGS-524.md): HQPorner
(`https://hqporner.com/hdporn/128161-in_thickness_and_in_girth.html`) exposes category
tags on the video page but "the provider's `load()` populates `duration` but no `tags`"
(`cats: 1080p, big dick, big tits, brunette, cumshot`).

## Probe (2026-10-09, HEAD 34c5b60)

```
GET https://hqporner.com/hdporn/128161-in_thickness_and_in_girth.html → 200 (desktop UA, pinned by the provider)
```

Live markup around the category block:

```html
<section>
  <h3>This video belongs to the following categories</h3>
  <p>
    <a href="/category/1080p-porn" class="tag-link click-trigger">1080p</a>
    <a href="/category/big-dick" class="tag-link click-trigger">big dick</a>
    <a href="/category/big-tits" class="tag-link click-trigger">big tits</a>
    <a href="/category/brunette" class="tag-link click-trigger">brunette</a>
    <a href="/category/cumshot" class="tag-link click-trigger">cumshot</a>
    <a href="/category/shaved-pussy" class="tag-link click-trigger">shaved pussy</a>
  </p>
</section>
```

Provider side (`HQPorner/src/main/kotlin/com/kraptor/HQPorner.kt`, unchanged since the
initial commit — `git log -S "section h3 + p a"` → `0d48989` only):

```kotlin
val tags = document.select("section h3 + p a").map { it.text() }
...
this.tags = tags
```

jsoup select over the saved live page (run as a temp JUnit probe against
`HQPorner/src/test` with the fixture, transcript from the test XML):

```
SELECTOR1 section h3 + p a          → [1080p, big dick, big tits, brunette, cumshot, shaved pussy]
SELECTOR2 section:has(h3) p a.tag-link → [1080p, big dick, big tits, brunette, cumshot, shaved pussy]
```

Six tags come back, a superset of the audit's own five (`shaved pussy` was dropped by
the audit's probe). `duration` is populated too (`DurationParse.minutes`, 41m 43s).
Probe was repeated 2026-10-09; site healthy, no Cloudflare wall to distinguish here.

## Why no fix
The claim is a false positive, second occurrence of the details-field-audit family after
#526 (FullPorner, same day). The selector has existed since the initial commit and returns
the six live tags. The fixture + probe were deleted after capture; this file records them.

## Related note
The homepage-h1/mobile-UA 302 behavior that breaks naive probes on this site is documented
in HQPorner/FINDINGS-431.md and the pinned-desktop-UA comments in the provider.
