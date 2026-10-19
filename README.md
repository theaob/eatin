# Eat In

A Hole.io-style browser game in blocky voxel 3D: steer a hole around a city,
swallow everything smaller than you, and grow into a black hole before the
round ends.

## Play

The whole game is one file, `index.html` (Three.js r128 loaded from cdnjs and
fonts from Google Fonts). Open it in a browser, or serve the folder:

```
python3 -m http.server
```

## Android

`android/` wraps the game in an offline, full-screen WebView APK. See
[android/README.md](android/README.md) for how to build and install it.
