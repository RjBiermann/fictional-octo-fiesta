# CloudStream 18+ Plugin Repo

A [CloudStream3](https://github.com/recloudstream/cloudstream) extension repo of 18+ (NSFW) video providers. Each top-level directory is one Gradle subproject that compiles to a `.cs3` plugin; new providers are added here as new directories.

## Providers

Data as of `audits/findings.json` @ 2026-10-10.

| Provider | Site | Verdict | Tier |
| --- | --- | --- | --- |
| [AllClassicPorn](AllClassicPorn/) | allclassic.porn | ok | plain-curl |
| [Cat3Film](Cat3Film/) | cat3film.com | ok | plain-curl |
| [Cat3Movie](Cat3Movie/) | cat3movie.org | ok | plain-curl |
| [EPorner](EPorner/) | www.eporner.com | ok-drift | plain-curl |
| [Eroticmv](Eroticmv/) | eroticmv.com | ok | plain-curl |
| [Film1k](Film1k/) | www.film1k.com | ok-drift | tls-impersonated |
| [FreePornVideos](FreePornVideos/) | www.freepornvideos.xxx | ok | tls-impersonated |
| [FullPorner](FullPorner/) | fullporner.com | ok | tls-impersonated |
| [HQPorner](HQPorner/) | hqporner.com | ok | plain-curl |
| [JavGuru](JavGuru/) | jav.guru | ok | plain-curl |
| [Javbangers](Javbangers/) | www.javbangers.com | ok-drift | plain-curl |
| [Javmost](Javmost/) | www.javmost.ws | ok | plain-curl |
| [Javseen](Javseen/) | javseen.tv | ok | plain-curl |
| [Javtiful](Javtiful/) | javtiful.com | ok-drift | plain-curl |
| [Mangoporn](Mangoporn/) | mangoporn.net | ok-drift | plain-curl |
| [MissAV](MissAV/) | missav.live | ok | plain-curl |
| [Neporn](Neporn/) | neporn.com | ok | plain-curl |
| [PandaMovies](PandaMovies/) | pandamovies.pw | ok-drift | plain-curl |
| [PerverZija](PerverZija/) | tube.perverzija.com | ok | plain-curl |
| [PornXP](PornXP/) | pxp.news | ok | plain-curl |
| [Porntrex](Porntrex/) | www.porntrex.com | ok | plain-curl |
| [Sexfilm](Sexfilm/) | en.sex-film.biz | ok | plain-curl |
| [WatchPorn](WatchPorn/) | watchporn.to | ok | plain-curl |
| [XMoviesForYou](XMoviesForYou/) | xmoviesforyou.com | ok-drift | tls-impersonated |
| [Xhamster](Xhamster/) | xhamster.com | ok | plain-curl |
| [ixiporn](ixiporn/) | ixiporn.org | ok-drift | plain-curl |

Verdict legend: `ok` = healthy on the last fleet audit; `ok-drift` = healthy overall with one or more standing drift/broken-provider conditions (details in `audits/findings.json` for each provider).

CI builds every provider on push and publishes `plugins.json` to the `builds` branch.

## Build

```bash
./gradlew <ProviderName>:make           # build one provider (.cs3)
./gradlew <ProviderName>:deployWithAdb  # build + install to a connected device
```

For local testing on Android 11+, grant the CloudStream app "All Files Access":

```bash
adb shell appops set --uid com.lagradost.cloudstream3.prerelease MANAGE_EXTERNAL_STORAGE allow
```

## References

- https://github.com/phisher98/CXXX
- https://github.com/Kraptor123/Cs-GizliKeyif
- https://cloudstream.miraheze.org/wiki/18%2B
- https://cloudstream.miraheze.org/wiki/List_of_extensions

## License

Everything in this repo is released into the public domain. The template, gradle plugin and plugin system are heavily based on [Aliucord](https://github.com/Aliucord).
