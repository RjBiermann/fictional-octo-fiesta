# CloudStream 18+ Plugin Repo

A [CloudStream3](https://github.com/recloudstream/cloudstream) extension repo of 18+ (NSFW) video providers. Each top-level directory is one Gradle subproject that compiles to a `.cs3` plugin; new providers are added here as new directories.

## Providers

| Provider | Site |
| --- | --- |
| [AllClassicPorn](AllClassicPorn/) | allclassic.porn |
| [Cat3Film](Cat3Film/) | cat3film.com |
| [Cat3Movie](Cat3Movie/) | cat3movie.org |
| [EPorner](EPorner/) | www.eporner.com |
| [Eroticmv](Eroticmv/) | eroticmv.com |
| [Film1k](Film1k/) | www.film1k.com |
| [FreePornVideos](FreePornVideos/) | www.freepornvideos.xxx |
| [FullPorner](FullPorner/) | fullporner.com |
| [HQPorner](HQPorner/) | hqporner.com |
| [JavGuru](JavGuru/) | jav.guru |
| [Javbangers](Javbangers/) | www.javbangers.com |
| [Javmost](Javmost/) | www.javmost.ws |
| [Javseen](Javseen/) | javseen.tv |
| [Javtiful](Javtiful/) | javtiful.com |
| [Mangoporn](Mangoporn/) | mangoporn.net |
| [MissAV](MissAV/) | missav.live |
| [Neporn](Neporn/) | neporn.com |
| [PandaMovies](PandaMovies/) | pandamovies.pw |
| [PerverZija](PerverZija/) | tube.perverzija.com |
| [PornXP](PornXP/) | pxp.news |
| [Porntrex](Porntrex/) | www.porntrex.com |
| [Sexfilm](Sexfilm/) | en.sex-film.biz |
| [WatchPorn](WatchPorn/) | watchporn.to |
| [XMoviesForYou](XMoviesForYou/) | xmoviesforyou.com |
| [Xhamster](Xhamster/) | xhamster.com |
| [ixiporn](ixiporn/) | ixiporn.org |

Per-provider audit verdicts and history live in [`audits/findings.json`](audits/findings.json); architecture decisions and agent docs live under [`docs/`](docs/).

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
