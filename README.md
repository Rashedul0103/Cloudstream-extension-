# MultiStremio — CloudStream extension

A CloudStream plugin that combines Stremio addons: merged catalogs, cross-addon search, metadata and episodes, subtitles, streams, stream filtering, and sorting.

## Install in CloudStream

After this repository has been pushed to GitHub, add this repository manifest in CloudStream → Settings → Extensions → Add repository:

`https://raw.githubusercontent.com/Rashedul0103/Cloudstream-extension-/main/repo.json`

The manifest points to the generated `plugins.json` index in the `builds` branch. After adding the repository, install **MultiStremio** from the extension list.

## Build and publishing

The GitHub Actions workflow in `.github/workflows/build.yml` runs on pushes to `main` (and can also be started manually). It builds the `.cs3` plugin and publishes it together with `plugins.json` to the `builds` branch. The first GitHub Actions run must be checked before treating the extension as installable.

## Status

Beta. The first GitHub Actions build must be checked before treating the extension as installable.
