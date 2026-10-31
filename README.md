# Eat In

A Hole.io-style browser game in blocky voxel 3D: steer a hole around a city,
swallow everything smaller than you, and grow into a black hole before the
round ends.

Four maps (City, Beach, Theme park, Snow) with landmarks, four modes
(Classic, Clean-up, Battle, Daily city), power-ups, combos, coins and hole
skins, daily challenges, a collection book, music and a shareable result card.
The shared daily leaderboard only works when the game runs as a Claude
artifact; elsewhere it is hidden.

## Play

The whole game is one file, `index.html` (Three.js r128 loaded from cdnjs and
fonts from Google Fonts). Open it in a browser, or serve the folder:

```
python3 -m http.server
```

## Android

`android/` wraps the game in an offline, full-screen WebView APK. See
[android/README.md](android/README.md) for how to build and install it.

## Website

`.github/workflows/pages.yml` publishes https://theaob.github.io/eatin/ on
every push to `main` (set Settings > Pages > Source to "GitHub Actions"):

- `/` is the landing page with the store links and screenshots (`site/`)
- `/play/` is the game itself (`index.html`)
- `/privacy.html` is the privacy policy (`privacy.html`)

The game's Share button puts that address on the result card and in the
share text (`SITE_URL` in `index.html`).
