# loadExtractor is the only stream dispatch seam

Embed hosts are never handled inline inside a provider's `loadLinks`. Every embed host
family gets an `ExtractorApi` adapter in `shared/` and is dispatched by the framework's
`loadExtractor`, which matches registered adapters by `mainUrl`. The **Host registry**
(`registerHostExtractors()`) is the single registration point, called by every provider's
plugin, so host coverage never varies by provider — a drift fix ("embeds moved to a host
the plugin does not register") becomes one adapter + one line in the registry.

Rejected alternative: a custom `dispatchEmbed()` layer that tries special handlers before
falling back to `loadExtractor`. It duplicates a seam the framework already provides and
widens every provider's interface; page-derived streams (e.g. xHamster's `window.initials`)
are the honest exception and keep their custom `loadLinks` — the rule is "embed hosts use
the seam", not "everything is an embed".
