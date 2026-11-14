# MultiStremio — CloudStream extension

A CloudStream plugin that combines Stremio addons: merged catalogs, cross-addon search, metadata and episodes, subtitles, streams, stream filtering, and sorting. Users can also add homepage rows from other installed CloudStream extensions, then hide or reorder all rows together. Stremio catalog row titles show the catalog name without repeating the addon name.

## Homepage row integration

Open **Settings → Catalog rows** and choose **Add rows from installed extensions**. Select any homepage row from another installed extension. Added rows appear in the same row manager as Stremio catalogs and can be shown, hidden, reordered, or removed independently. The source extension must remain installed; if it is removed or changes the row definition, MultiStremio marks the saved row as unavailable.

MultiStremio delegates each selected row request to its original extension provider, including pagination. It does not copy the source extension's catalog implementation or stream logic.

## Install in CloudStream

After this repository has been pushed to GitHub, add this repository manifest in CloudStream → Settings → Extensions → Add repository:

`https://raw.githubusercontent.com/Rashedul0103/Cloudstream-extension-/main/repo.json`

The manifest points to the generated `plugins.json` index in the `builds` branch. After adding the repository, install **MultiStremio** from the extension list.

## Build and publishing

The GitHub Actions workflow in `.github/workflows/build.yml` runs on pushes to `main` (and can also be started manually). It builds the `.cs3` plugin and publishes it together with `plugins.json` to the `builds` branch. The first GitHub Actions run must be checked before treating the extension as installable.

## Catalog layout

Open the plugin settings and choose **Catalog rows (visibility & order)**. Select a row to show or hide it, move it up or down, send it to the top or bottom, or open its parent addon's settings. Addon-wide enable/disable and the separate “Catalogs & search” usage switch remain under **Addons**. Row order and visibility are saved locally on the device.

## Status

Beta.
