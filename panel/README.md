# Ultra Player panel

A small PHP panel for your Ultra Player build. It uses no database: everything is kept in `data/panel.json`.

## Install (cPanel)
1. Upload the files in this folder (`index.php`, `api.php`, `inc.php`) to a folder on your site, e.g. `public_html/ultraplayer/`.
2. Open `https://yourdomain/ultraplayer/` and create your admin login.
3. Check that the app's `PANEL_URL` (in `gradle.properties`) points to `https://yourdomain/ultraplayer/api.php`.

The panel creates `data/` (blocked from the web) and `uploads/` (APK downloads) the first time it runs. PHP 7.4 or newer is fine.

## Tabs
- **Servers**: the DNS list. The app's login screen shows these as a dropdown, so users only type their username and password.
- **App update**: version code, an APK link or upload for each CPU type (arm32 for Firesticks, arm64, universal), what's new, and a force option. Any app with a lower version code gets the pop-up.
- **Messages**: pop-ups shown when the app starts. "Once" messages are shown once per device.
- **Settings**: allow custom URLs, allow M3U login, show or hide the Plex tab, and change your password.

## Releasing an update
1. Bump `APP_VERSION_CODE` and `APP_VERSION_NAME` in `gradle.properties` and push. Or run the **Build APKs** workflow by hand and type the version in.
2. GitHub Actions builds the APKs and attaches them to a release.
3. In the panel's **App update** tab, enter the same version code and paste the release APK links, or upload the files. Then save.
