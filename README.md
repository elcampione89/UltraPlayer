# Ultra Player

A remote-first IPTV player for Fire TV / Android TV, with Live TV (now/next guide), films, series, Plex and search, all managed from your own panel (`panel/`).

- One APK for every device (arm32 Firesticks, arm64 boxes, phones).
- Builds and signs on GitHub Actions; every push to `main` publishes a release with a direct APK link.
- Bump `APP_VERSION_CODE` / `APP_VERSION_NAME` in `gradle.properties` for each release, then enter the same numbers in the panel's **App update** tab.
