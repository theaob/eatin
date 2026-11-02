# Eat In for Android

The game wrapped in a full-screen WebView app that turns with the phone
(package `com.onurbaykal.eatin`, Android 7.0+). The game runs offline: three.js
r128 and the two fonts are bundled. The network is only used for ads and
Google Play Games.

## Ads

AdMob, through the Google Mobile Ads SDK:

- a banner at the bottom of the screen whenever you're not playing (menu,
  results, panels); the menus get extra bottom padding so nothing hides under it
- an interstitial when leaving the results screen, after every 2nd round and at
  most once per 150 s (`AD_EVERY` and `AD_GAP` in `../index.html`)
- Google's consent form (UMP) where the law needs one, and an "Ad privacy"
  button on the menu when users must be able to change their choice

The IDs in `app/src/main/res/values/ads.xml` are Google's public **test IDs**:
they show test ads and earn nothing. Replace all three with the app ID and ad
unit IDs from the AdMob console before releasing. The page talks to the app
through `window.EatInAds` (in `MainActivity.java`); in a browser that object is
missing and the game has no ads.

## Google Play Games

Play Games Services v2 gives the app automatic sign-in, leaderboards and
achievements. The page talks to it through `window.EatInGames` (the `Games`
class in `MainActivity.java`) and hears back through
`window.eatInGames.status(signedIn, name)`; in a browser that object is missing
and nothing changes. In the app, the menu's **Leaders** button opens the Play
Games leaderboards and **Awards** opens the achievements. A player who isn't
signed in is asked to sign in when they tap either one.

Play Games stays **off** until `app/src/main/res/values/games-ids.xml` holds a
real project ID, so builds with the placeholder file behave as before.

Achievements and scores are worked out from the saved progress (`ACHIEVEMENTS`,
`STEPPED` and `BOARDS` in `../index.html`), and the page re-sends them after
every sign-in and every round, so progress from before sign-in is not lost.

### Set it up in Play Console

1. **Create the game.** Play Console > Eat In > Grow users > Play Games
   Services > Setup and management > Configuration. Choose "No, my game
   doesn't use Google APIs", name it Eat In, and create it. This makes a
   Google Cloud project for it.
2. **OAuth consent screen.** The configuration page links to the Cloud
   project; fill in the OAuth consent screen there (app name, support email,
   `https://theaob.github.io/eatin/privacy.html` as the privacy policy) and
   publish it.
3. **Credentials.** Back on the configuration page, add an Android credential
   for `com.onurbaykal.eatin` once for each key that signs a build you want
   Play Games to work in. Each needs the key's SHA-1, and a key missing here
   makes sign-in fail silently:
   - **App signing key** (what users install from Play): copy its SHA-1 from
     Test and release > App integrity > App signing.
   - **Upload key** (`signing/upload-keystore.jks`, alias `upload`):
     `C0:1E:EA:8E:00:CC:24:A8:9E:08:2E:C8:65:6F:97:34:CF:11:4D:F9`
   - **Debug key** (`debug.keystore`, for side-loaded test APKs):
     `B1:BE:DB:92:1C:29:D2:93:6A:9C:54:A5:BF:6A:DE:CA:87:4D:3E:DB`

   You can get any of these again with
   `keytool -list -v -keystore <file>`.
4. **Leaderboards.** Under Leaderboards, create these five with exactly these
   names (the name decides the resource name the app looks up):

   | Name | Score format | Order | Score sent |
   | --- | --- | --- | --- |
   | Classic | Numeric, 0 decimals | Larger is better | best Classic score |
   | Clean up | Numeric, 0 decimals | Larger is better | best Clean-up score |
   | Battle | Numeric, 0 decimals | Larger is better | best Battle score |
   | Daily city | Numeric, 0 decimals | Larger is better | every Daily city round |
   | Widest hole | Numeric, **1 decimal** | Larger is better | widest hole in m (sent as tenths) |

   Leaders opens the list of all five. Play Games keeps daily, weekly and
   all-time views of each board; its "today" resets at midnight Pacific
   time, while the Daily city changes at midnight UTC.
5. **Achievements.** Under Achievements, create these (an icon for each is
   required; at least 5 are needed to publish):

   | Name | Description | Type |
   | --- | --- | --- |
   | First round | Finish your first round. | Standard |
   | Winner | Win a round. | Standard |
   | Last hole standing | Win a Battle. | Standard |
   | Spotless | Finish a Clean-up. | Standard |
   | Black hole | Grow 20 m wide. | Standard |
   | Supermassive | Grow 40 m wide. | Standard |
   | High score | Score 10,000 points in one round. | Standard |
   | Combo master | Reach a 75 combo. | Standard |
   | Sightseer | Swallow a landmark. | Standard |
   | Golden snack | Swallow a golden object. | Standard |
   | Rival eater | Swallow a rival hole. | Standard |
   | World tour | Play on every map. | Standard |
   | New look | Buy a skin. | Standard |
   | Solid gold | Buy the Solid gold skin. | Standard |
   | Collector | Fill your whole collection book. | Standard |
   | Regular | Play 50 rounds. | Incremental, 50 steps |
   | Bottomless | Swallow 10,000 things. | Incremental, 10000 steps |
   | Challenger | Finish 30 daily challenges. | Incremental, 30 steps |

   If you rename one, rename its `achievement_<key>` string to match the
   key in `ACHIEVEMENTS`/`STEPPED`, or the other way round.
6. **IDs into the app.** On the configuration page press **Get resources**,
   copy the Android XML, paste it over `app/src/main/res/values/games-ids.xml`
   and push. Check the names in it match the placeholder file.
7. **Testers, then publish.** Add your Google account under Testers so you
   can sign in before the Play Games project is published. When it all works,
   Review and publish the Play Games project (separately from the app
   release).


This is a normal Gradle project. Google's Maven repository is needed for the
Android plugin and the ads SDK, so it builds anywhere except Claude's cloud
container, which can't reach it.

- **GitHub Actions** (`.github/workflows/android.yml`) builds on every push that
  touches the game or `android/`. The run's artifact holds `app-debug.apk`
  (signed with the runner's own debug key) and `app-release.aab` (unsigned).
  Pushes (not pull requests) also replace the `android-latest` pre-release on
  GitHub with the same two files, which is the easiest way to get the APK onto
  a phone. That APK has a different key from earlier builds, so uninstall the
  old app first, or re-sign it with `debug.keystore` as below.
- **Locally** with JDK 17 and the Android SDK (or Android Studio):
  `./gradlew assembleDebug bundleRelease`.

`sync.py` runs as part of the build: it copies `../index.html` into
`app/src/main/assets/` and swaps the three.js CDN script and Google Fonts link
for the bundled copies in `vendor/`. Pass `-PversionCode=N -PversionName=X` to
set the version; CI uses 100 plus the run number as `versionCode`, above
every old apktool build.

## Signing

Keys stay out of the repo. A local build picks them up when present:

- `debug.keystore` in this folder (password `android`) signs the debug APK, so
  it installs over earlier builds and keeps saved progress
- `../signing/keystore.properties` (or `KEYSTORE_PROPS`) signs the release bundle

To sign CI output yourself:

```
java -jar uber-apk-signer.jar -a app-debug.apk --ks debug.keystore \
  --ksAlias androiddebugkey --ksPass android --ksKeyPass android
jarsigner -keystore <upload keystore> app-release.aab <alias>
```

## Layout

- `app/src/main/AndroidManifest.xml`: follows the phone rotation, full screen, notch-aware
- `app/src/main/java/com/onurbaykal/eatin/MainActivity.java`: the WebView
  activity (JS and localStorage on, sound without a tap, immersive mode, pauses
  in background), the ads and Play Games
- `app/src/main/res/`: app name, theme, launcher icon, ad IDs, Play Games IDs
- `vendor/`: three.min.js and the font files
- `app/src/main/assets/`: generated by `sync.py`, do not edit
