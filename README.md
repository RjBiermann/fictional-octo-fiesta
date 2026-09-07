# CloudStream 18+ Plugin Repo

A [CloudStream3](https://github.com/recloudstream/cloudstream) extension repo of 18+ (NSFW) video providers. Each top-level directory is one Gradle subproject that compiles to a `.cs3` plugin; new providers are added here as new directories.

## Providers

EPorner, FreePornVideos, FullPorner, HQPorner, ixiporn, JavGuru, Javseen, Javtiful, MissAV, PerverZija, PornHits, PornXP, Porntrex, WatchPorn, Xhamster, XMoviesForYou

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
