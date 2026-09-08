# FINDINGS — tube.perverzija.com (2026-09 audit)

## Re-probe for issue #161 fix (2026-02, builder)
- `span.runtime` → 0 matches on the live page (selector dead; issue #161 confirmed).
- JSON-LD `<script type="application/ld+json">` VideoObject exposes `"duration":"PT34M59S"` on the same page.
- Recommendations: `div.srelacionados` gone from the page — no related-videos section exposed anymore; not a data gap.
- Fix: parse ISO-8601 duration from JSON-LD (PT#H#M#S → total minutes) instead of the dead span.
- verify.sh: search 200 ×69 `div.col-md-3`; video page 200, `iframe[src*=xtremestream]` ×1; canned script still cannot do the two-hop stream (sends no Referer, xs1.php 403s) — manual chain evidence: player page 200 → `video_id` → `xs1.php?data=<id>` with player referer → 200 body starting `#EXTM3U` (854x480 variant listed).
- Gradle gate (2026-02): `./gradlew PerverZija:make` BUILD SUCCESSFUL (jitpack issue below no longer reproduces in CI).

## Verdict: BROKEN (extractor domain mismatch)

## Search — OK
- `/?s=red` → 200, `div.col-md-3` ×69, `div.title a` hrefs OK.

## Video page
- `iframe src="https://pervl1.xtremestream.xyz/player/index.php?data=a667f4e7b0c8a3babe331569d3eac6bd"` (pervl1!).

## Stream — extractor not matched
- Provider registers ONLY `PerverZijaExtractor` with `mainUrl=https://pervl2.xtremestream.xyz`. Player iframe serves from **pervl1** → loadExtractor finds no matching extractor → no stream.
- Player page: `m3u8_loader_url = https://pervl1.xtremestream.xyz/player/xs1.php?data=`; xs1.php serves m3u8 **only with referer of the player domain** (`pervl1.xtremestream.xyz/player/index.php` OK, `pervl2...` OK, `tube.perverzija.com` 403, none 403). q=480/720/1080 all 200.

## Fix
Register extractor for both subdomains (pervl1 + pervl2), keep referer on extractor mainUrl.

## Re-probe for issue #99 fix (2026-09, builder)
- Player subdomain VARIES per video: observed pervl1, pervl2, pervl3, pervl6, pervm1, j2 across search results. Registering fixed subdomains is insufficient.
- xs1.php accepts referer from ANY *.xtremestream.xyz/player/index.php (pervl6 stream with pervl1 referer → 200); tube.perverzija.com or none → 403.
- Fix applied: provider calls PerverZijaExtractor().getUrl(iframe, ...) directly in loadLinks (extractor derives link referer from the iframe URL), so any pervlN/pervmN/jN subdomain works. No loadExtractor mainUrl matching needed.

## Reviewer round-1 verification (independent, 2026-09)
- Reproduced: search ×1 (200, `div.col-md-3`), 4+ varied video pages; iframes on pervm1, pervl2, pervl1, j2 subdomains (subdomain set wider than pervlN — confirms fixed-subdomain registration is insufficient).
- Exact code-emitted referer form `https://<sub>.xtremestream.xyz/` (url.substringBefore("/player/")+"/") → xs1.php`q=720` returns 200 `application/vnd.apple.mpegurl` body; no referer → 403. Fix validated.
- Non-video pages (e.g. /2257-exemption-statement/, discord iframe) skipped by the `contains("xtremestream.xyz")` guard — correct.
- verify.sh transcript not produced: canned verify.sh cannot pass here (it sends no Referer on the stream GET, so xs1.php 403s). Manual curl evidence substituted.

## Gradle gate: BLOCKED from CI (environment)
`./gradlew PerverZija:make` fails at root-project configuration: jitpack no longer serves
`com.github.recloudstream:gradle:-SNAPSHOT` (gradle--32895aedb6-1.pom → 404; maven-metadata lists
only `-32895aedb6-1` whose pom is also 404). Failure is repo-wide (root buildscript classpath),
affects baseline master identically — not caused by this change. Needs a root build.gradle.kts
version bump (out of scope for an ai-fix per repo rules).
