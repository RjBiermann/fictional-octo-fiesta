# FINDINGS-1353 — PerverZija 2001 is the `data:` playlist, not the network

Issue: [#1353](https://github.com/RjBiermann/fictional-octo-fiesta/issues/1353) — "Still
getting `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED` (2001)" after the v24 hardening of #1328.
Run: **2026-10-27**, emulator inside this workspace (`dev`, API 35 google_apis x86_64, headless
`-gpu host`), CloudStream prerelease **4.8.0-PRE** (`com.lagradost.cloudstream3.prerelease`,
media3 1.9.3 + `media3.datasource.cronet`), provider **v24** then **v26**.

## §1 Symptom reproduced on the emulator, exactly as reported

Provider v24, a gated video (`tabooheat-…-my-step-moms-hot-friend-part-1-3`, player
`pervl4`), Play Movie:

```text
CS3ExoPlayer: Using CronetDataSource for ExtractorLink(name=PerverZija,
              url=data:application/vnd.apple.mpegurl;base64,I0VYVE0zVQ…, type=M3U8)
E/ExoPlayerImplInternal: Caused by: CronetDataSource$OpenException:
    Exception in CronetUrlRequest: net::ERR_UNKNOWN_URL_SCHEME, ErrorCode=11
I/COMPACT : ERROR_CODE_IO_NETWORK_CONNECTION_FAILED (2001)
```

The XSFTK chain itself was green in the same run (`source q=1080 cdn=pervl4.xspcdn01.click
key=pervl4.xtremestream.xyz segs=536`), and the same playlist body, decoded from the log,
plays when the key/segment legs are fetched outside the app. So H1–H4 (host, key host,
`*.click` DNS/filter, NAT64) are all refuted from the device; the failure is client-side.

## §2 Root cause — the app has no `data:` datasource path

`CS3IPlayer.createVideoSource` wraps every link in an HTTP-only factory
(`CronetDataSource.Factory`, else `OkHttpDataSource.Factory`); there is no `DefaultDataSource`
scheme dispatch on the online path and no `data:` handling anywhere in the player sources
(`app-sources.jar`, 4.8.0-PRE). Cronet answers `net::ERR_UNKNOWN_URL_SCHEME` for `data:`,
which media3 maps to `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED` — **2001 for every gated
PerverZija video on every device**, independent of network, DNS, VPN, or player engine.
The #1328 H5 hypothesis ("the player's tolerance of a multi-tens-of-kB `data:` URI") was the
right suspect; its answer is "none".

## §3 Fix — host the playlist on loopback, emit an `http://` URL

The plugin runs inside the app process, so `shared/XsftkPlaylistServer.kt` binds
`127.0.0.1:0`, keeps the last 64 published playlists, and serves
`GET /<id>.m3u8` (`Content-Type: application/vnd.apple.mpegurl`) over plain HTTP.
`PerverZijaExtractor.emitXsftk` publishes each synthesized quality and emits
`http://127.0.0.1:<port>/<id>.m3u8`; the `#EXT-X-KEY` and segment URIs inside the playlist
are absolute, so the `X-XS-P: 1` header map and the CDN host choice are unchanged.
The `data:` URI, its base64 encoder and the #1338/S3 96 KiB payload budget are gone with the
mechanism they measured (the playlist is no longer carried in a URI).

## §4 Verification (emulator, provider v26)

Two gated videos on different player subdomains, same app build:

| video | player host | emitted sources | player |
|---|---|---|---|
| `tabooheat-jazmine-cruz-…-part-1-3` | `pervl4` | 3 qualities + 3 `(alt)` family copies, `http://127.0.0.1:39659/…m3u8` | `Rendered first frame`, `PLAYING`, position 36 s of 44 m 44 s |
| `jacquietmicheltv-mila-35-has-evolved-a-lot` | `pervm1` | 3 + 3, same loopback host | `Rendered first frame`, `PLAYING`, position 15 s of 44 m 41 s |

No `ERR_UNKNOWN_URL_SCHEME`, no 2001, no `COMPACT` error on either. Screenshot at 36 s shows
decoded video. Before the fix the same two videos produced 2001 on every source.

## §5 Limits

- Loopback hosting needs the plugin to run inside the app process (it does) and cleartext
  http to be permitted; CloudStream sets `android:usesCleartextTraffic="true"`.
- The host lives as long as the app process; a playlist id stays valid for the last 64
  published links, which covers player retries, seeks and browsing between videos
  (a gated video publishes up to 6 links).
- Legacy (pre-gate) videos still emit the tokenless `xs1.php` master and are untouched.
- In-app testing stays maintainer-only per ADR-0012; this run happened because the maintainer
  asked for it on the issue.
