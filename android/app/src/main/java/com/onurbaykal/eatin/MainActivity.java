package com.onurbaykal.eatin;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.window.OnBackInvokedDispatcher;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;

import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;
import com.google.android.gms.games.AuthenticationResult;
import com.google.android.gms.games.PlayGames;
import com.google.android.gms.games.PlayGamesSdk;
import com.google.android.gms.games.leaderboard.LeaderboardVariant;
import com.google.android.gms.tasks.Task;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;

import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONObject;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Full-screen WebView that loads assets/index.html (the game), plus AdMob.
 *
 * The page drives the ads through window.EatInAds (see Bridge) and hears back
 * through window.eatInAds.banner(heightPx), .done(), .rewarded(place, earned)
 * and .privacy(required).
 * Ads only start after the UMP consent flow says they may be requested.
 *
 * Google Play Games Services goes through window.EatInGames (see Games) and
 * reports sign-in back through window.eatInGames.status(signedIn, name). It
 * stays off until res/values/games-ids.xml has a real app_id.
 */
public class MainActivity extends Activity {
    private WebView web;
    private FrameLayout root;
    private AdView banner;
    private InterstitialAd interstitial;
    private ConsentInformation consent;
    private final AtomicBoolean adsStarted = new AtomicBoolean(false);
    private boolean wantBanner;
    private boolean interstitialShowing;
    /** Rewarded placements the page may ask for: place -> ad unit string. */
    private static final String[][] REWARDED = {
        { "double", "admob_rewarded_double_id" },
        { "revive", "admob_rewarded_revive_id" },
    };
    private final Map<String, RewardedAd> rewarded = new ConcurrentHashMap<>();
    private final Map<String, Boolean> rewardedLoading = new ConcurrentHashMap<>();
    private boolean fullScreenShowing() { return interstitialShowing || rewardedShowing; }
    private volatile boolean rewardedShowing;
    private Games games;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setBackgroundColor(Color.BLACK);
        web.addJavascriptInterface(new Bridge(), "EatInAds");
        web.addJavascriptInterface(new ShareBridge(), "EatInShare");
        games = new Games();
        web.addJavascriptInterface(games, "EatInGames");
        web.addJavascriptInterface(new ReminderBridge(), "EatInReminder");
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        web.loadUrl("file:///android_asset/index.html");
        hideBars();
        requestConsent();
        games.start();
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
    }

    // ---------- back button ----------

    /**
     * Back goes to the page first (window.eatInBack): it closes an open panel, pauses or
     * resumes a round, or leaves the results. Only on the main menu does the app go to
     * the background, as a launcher activity would.
     */
    private void handleBack() {
        if (web == null) { moveTaskToBack(true); return; }
        web.evaluateJavascript("window.eatInBack ? window.eatInBack() : false",
            handled -> { if (!"true".equals(handled)) moveTaskToBack(true); });
    }

    /** Android 12 and older; 13+ uses the callback registered in onCreate. */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (Build.VERSION.SDK_INT >= 33) super.onBackPressed();
        else handleBack();
    }

    @SuppressWarnings("deprecation")
    private void hideBars() {
        // IMMERSIVE_STICKY | LAYOUT_STABLE | LAYOUT_HIDE_NAVIGATION | LAYOUT_FULLSCREEN | HIDE_NAVIGATION | FULLSCREEN
        getWindow().getDecorView().setSystemUiVisibility(0x1706);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    // ---------- consent ----------

    private void requestConsent() {
        consent = UserMessagingPlatform.getConsentInformation(this);
        ConsentRequestParameters params = new ConsentRequestParameters.Builder().build();
        consent.requestConsentInfoUpdate(this, params,
            () -> UserMessagingPlatform.loadAndShowConsentFormIfRequired(this, err -> {
                hideBars();
                tellPage("privacy(" + privacyRequired() + ")");
                if (consent.canRequestAds()) startAds();
            }),
            err -> { if (consent.canRequestAds()) startAds(); });
        // Consent given in an earlier session: no need to wait for the update.
        if (consent.canRequestAds()) startAds();
    }

    private boolean privacyRequired() {
        return consent != null && consent.getPrivacyOptionsRequirementStatus()
            == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED;
    }

    // ---------- ads ----------

    private void startAds() {
        if (adsStarted.getAndSet(true)) return;
        new Thread(() -> {
            MobileAds.initialize(this, status -> {});
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (wantBanner) loadBanner();
                loadInterstitial();
                for (String[] r : REWARDED) loadRewarded(r[0]);
            });
        }).start();
    }

    private void loadBanner() {
        if (!adsStarted.get() || banner != null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int widthDp = Math.min(728, (int) (dm.widthPixels / dm.density));
        AdSize size = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this, widthDp);
        banner = new AdView(this);
        banner.setAdUnitId(getString(R.string.admob_banner_id));
        banner.setAdSize(size);
        banner.setAdListener(new AdListener() {
            @Override
            public void onAdLoaded() {
                if (wantBanner && banner != null) {
                    banner.setVisibility(View.VISIBLE);
                    tellPage("banner(" + size.getHeight() + ")");
                }
            }
        });
        banner.setVisibility(View.GONE);
        root.addView(banner, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        banner.loadAd(new AdRequest.Builder().build());
    }

    private void dropBanner() {
        if (banner == null) return;
        root.removeView(banner);
        banner.destroy();
        banner = null;
    }

    private void setBanner(boolean on) {
        wantBanner = on;
        if (!on) {
            if (banner != null) banner.setVisibility(View.GONE);
            tellPage("banner(0)");
        } else if (banner == null) {
            loadBanner();
        } else if (banner.getResponseInfo() != null) {
            banner.setVisibility(View.VISIBLE);
            tellPage("banner(" + banner.getAdSize().getHeight() + ")");
        }
    }

    private void loadInterstitial() {
        if (!adsStarted.get() || interstitial != null) return;
        InterstitialAd.load(this, getString(R.string.admob_interstitial_id), new AdRequest.Builder().build(),
            new InterstitialAdLoadCallback() {
                @Override
                public void onAdLoaded(@NonNull InterstitialAd ad) { interstitial = ad; }
                @Override
                public void onAdFailedToLoad(@NonNull LoadAdError e) { interstitial = null; }
            });
    }

    /** Returns false when no ad is ready; then the page carries on at once. */
    private boolean showInterstitial() {
        InterstitialAd ad = interstitial;
        if (ad == null || fullScreenShowing()) return false;
        interstitial = null;
        interstitialShowing = true;
        ad.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override
            public void onAdDismissedFullScreenContent() { finished(); }
            @Override
            public void onAdFailedToShowFullScreenContent(@NonNull com.google.android.gms.ads.AdError e) { finished(); }
            private void finished() {
                interstitialShowing = false;
                hideBars();
                tellPage("done()");
                loadInterstitial();
            }
        });
        ad.show(this);
        return true;
    }

    private String rewardedUnit(String place) {
        for (String[] r : REWARDED) if (r[0].equals(place)) return getString(getResources().getIdentifier(r[1], "string", getPackageName()));
        return null;
    }

    private void loadRewarded(String place) {
        String unit = rewardedUnit(place);
        if (!adsStarted.get() || unit == null || rewarded.get(place) != null || Boolean.TRUE.equals(rewardedLoading.get(place))) return;
        rewardedLoading.put(place, true);
        RewardedAd.load(this, unit, new AdRequest.Builder().build(), new RewardedAdLoadCallback() {
            @Override
            public void onAdLoaded(@NonNull RewardedAd ad) { rewardedLoading.put(place, false); rewarded.put(place, ad); }
            @Override
            public void onAdFailedToLoad(@NonNull LoadAdError e) { rewardedLoading.put(place, false); rewarded.remove(place); }
        });
    }

    /**
     * Shows the rewarded ad for a placement. The page always hears back once through
     * window.eatInAds.rewarded(place, earned): earned is true only when the player
     * watched long enough for the reward.
     */
    private void showRewarded(String place) {
        if (rewardedUnit(place) == null) return;   // only known placements, which are plain words
        RewardedAd ad = rewarded.get(place);
        if (ad == null || fullScreenShowing()) { tellPage("rewarded('" + place + "',false)"); return; }
        rewarded.remove(place);
        rewardedShowing = true;
        final boolean[] earned = { false };
        ad.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override
            public void onAdDismissedFullScreenContent() { finished(); }
            @Override
            public void onAdFailedToShowFullScreenContent(@NonNull com.google.android.gms.ads.AdError e) { finished(); }
            private void finished() {
                rewardedShowing = false;
                hideBars();
                tellPage("rewarded('" + place + "'," + earned[0] + ")");
                loadRewarded(place);
            }
        });
        ad.show(this, item -> earned[0] = true);
    }

    private void tellPage(String call) {
        if (web != null) web.evaluateJavascript("window.eatInAds && window.eatInAds." + call, null);
    }

    /** Called from the page's JavaScript, on a background thread. */
    private class Bridge {
        @JavascriptInterface
        public void showBanner() { runOnUiThread(() -> setBanner(true)); }

        @JavascriptInterface
        public void hideBanner() { runOnUiThread(() -> setBanner(false)); }

        @JavascriptInterface
        public boolean interstitialReady() { return interstitial != null && !fullScreenShowing(); }

        /** Is a rewarded ad loaded for this placement ("double", "revive")? */
        @JavascriptInterface
        public boolean rewardedReady(String place) { return rewarded.get(place) != null && !fullScreenShowing(); }

        @JavascriptInterface
        public void showRewarded(String place) { runOnUiThread(() -> MainActivity.this.showRewarded(place)); }

        @JavascriptInterface
        public void showInterstitial() {
            runOnUiThread(() -> { if (!MainActivity.this.showInterstitial()) tellPage("done()"); });
        }

        @JavascriptInterface
        public boolean privacyOptionsRequired() { return privacyRequired(); }

        @JavascriptInterface
        public void showPrivacyOptions() {
            runOnUiThread(() -> UserMessagingPlatform.showPrivacyOptionsForm(MainActivity.this, err -> {
                hideBars();
                if (consent.canRequestAds()) startAds();
            }));
        }
    }

    /**
     * window.EatInShare.share(pngBase64, text): the WebView has no Web Share API,
     * so the share card goes out through the Android share sheet instead.
     */
    private class ShareBridge {
        @JavascriptInterface
        public boolean share(String pngBase64, String text) {
            try {
                File dir = new File(getCacheDir(), "shared");
                if (!dir.isDirectory() && !dir.mkdirs()) return false;
                File png = new File(dir, "eat-in-result.png");
                try (FileOutputStream out = new FileOutputStream(png)) {
                    out.write(Base64.decode(pngBase64, Base64.DEFAULT));
                }
                Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".share", png);
                Intent send = new Intent(Intent.ACTION_SEND)
                        .setType("image/png")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_TEXT, text)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                send.setClipData(ClipData.newRawUri(null, uri));
                runOnUiThread(() -> startActivity(Intent.createChooser(send, "Share your round")));
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    /**
     * window.EatInReminder: the streak reminder (see Reminders). ask() requests the
     * notification permission on Android 13+ and answers through
     * window.eatInReminder.allowed(granted).
     */
    private static final int RC_NOTIFY = 9002;

    private class ReminderBridge {
        @JavascriptInterface
        public boolean allowed() { return Reminders.allowed(MainActivity.this); }

        @JavascriptInterface
        public void ask() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33 && !Reminders.allowed(MainActivity.this)
                    && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[] { android.Manifest.permission.POST_NOTIFICATIONS }, RC_NOTIFY);
                } else {
                    tellReminder(Reminders.allowed(MainActivity.this));
                }
            });
        }

        @JavascriptInterface
        public void schedule(double at, String title, String text) {
            Reminders.schedule(MainActivity.this, (long) at, title, text);
        }

        @JavascriptInterface
        public void cancel() { Reminders.cancel(MainActivity.this); }
    }

    private void tellReminder(boolean ok) {
        if (web != null) web.evaluateJavascript("window.eatInReminder && window.eatInReminder.allowed(" + ok + ")", null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == RC_NOTIFY) { hideBars(); tellReminder(Reminders.allowed(this)); }
    }

    /**
     * window.EatInGames: Google Play Games Services v2. The page names
     * achievements and leaderboards by key; the IDs come from games-ids.xml
     * ("achievement_<key>", "leaderboard_<key>"), and unknown or empty ones are
     * skipped. Everything is a no-op until the player is signed in, and the page
     * re-sends its progress when status(true) arrives, so nothing is lost.
     */
    private class Games {
        private static final int RC_UI = 9001;
        private final boolean configured = getString(R.string.app_id).matches("[1-9][0-9]{5,}");
        private volatile boolean signedIn;
        private String name = "";

        void start() {
            if (!configured) return;
            PlayGamesSdk.initialize(getApplicationContext());   // the manifest removes its auto-init provider
            // Signs in automatically when the player has used Play Games before.
            PlayGames.getGamesSignInClient(MainActivity.this).isAuthenticated()
                .addOnCompleteListener(t -> signedIn(t, null));
        }

        private void signIn(Runnable then) {
            PlayGames.getGamesSignInClient(MainActivity.this).signIn()
                .addOnCompleteListener(t -> signedIn(t, then));
        }

        private void signedIn(Task<AuthenticationResult> t, Runnable then) {
            signedIn = t.isSuccessful() && t.getResult().isAuthenticated();
            hideBars();
            if (!signedIn) { tell(); return; }
            PlayGames.getPlayersClient(MainActivity.this).getCurrentPlayer()
                .addOnCompleteListener(p -> {
                    if (p.isSuccessful() && p.getResult() != null) name = p.getResult().getDisplayName();
                    tell();
                });
            if (then != null) then.run();
        }

        private void tell() {
            if (web != null) web.evaluateJavascript("window.eatInGames && window.eatInGames.status("
                + signedIn + "," + JSONObject.quote(name == null ? "" : name) + ")", null);
        }

        /** The Play Games ID for a key, or null when it is not set up. */
        private String id(String kind, String key) {
            if (key == null || !key.matches("[a-z0-9_]+")) return null;
            int res = getResources().getIdentifier(kind + "_" + key, "string", getPackageName());
            if (res == 0) return null;
            String v = getString(res).trim();
            return v.isEmpty() ? null : v;
        }

        /** Runs the call on the UI thread, signing in first when asked to. */
        private void run(boolean askSignIn, Runnable r) {
            runOnUiThread(() -> {
                if (!configured || isFinishing()) return;
                if (signedIn) r.run();
                else if (askSignIn) signIn(r);
            });
        }

        private void show(Task<Intent> t) {
            t.addOnSuccessListener(i -> startActivityForResult(i, RC_UI));
        }

        @JavascriptInterface
        public boolean available() { return configured; }

        @JavascriptInterface
        public boolean signedIn() { return signedIn; }

        @JavascriptInterface
        public void signIn() { run(true, this::tell); }

        @JavascriptInterface
        public void submitScore(String key, double score) {
            String id = id("leaderboard", key);
            if (id != null && score > 0) run(false, () -> PlayGames.getLeaderboardsClient(MainActivity.this).submitScore(id, (long) score));
        }

        @JavascriptInterface
        public void unlock(String key) {
            String id = id("achievement", key);
            if (id != null) run(false, () -> PlayGames.getAchievementsClient(MainActivity.this).unlock(id));
        }

        /** For incremental achievements: raises progress to at least steps (never lowers it). */
        @JavascriptInterface
        public void setSteps(String key, double steps) {
            String id = id("achievement", key);
            if (id != null && steps >= 1) run(false, () -> PlayGames.getAchievementsClient(MainActivity.this).setSteps(id, (int) Math.min(steps, Integer.MAX_VALUE)));
        }

        /** key "" shows every leaderboard; daily shows today's scores first. */
        @JavascriptInterface
        public void showLeaderboards(String key, boolean daily) {
            String id = id("leaderboard", key);
            run(true, () -> show(id == null
                ? PlayGames.getLeaderboardsClient(MainActivity.this).getAllLeaderboardsIntent()
                : PlayGames.getLeaderboardsClient(MainActivity.this).getLeaderboardIntent(id,
                    daily ? LeaderboardVariant.TIME_SPAN_DAILY : LeaderboardVariant.TIME_SPAN_ALL_TIME,
                    LeaderboardVariant.COLLECTION_PUBLIC)));
        }

        @JavascriptInterface
        public void showAchievements() {
            run(true, () -> show(PlayGames.getAchievementsClient(MainActivity.this).getAchievementsIntent()));
        }
    }

    // ---------- lifecycle ----------

    @Override
    public void onConfigurationChanged(@NonNull Configuration c) {
        super.onConfigurationChanged(c);
        // The adaptive banner is sized for the old width: load a new one.
        if (banner != null) {
            dropBanner();
            tellPage("banner(0)");
            if (wantBanner) loadBanner();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        hideBars();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (banner != null) banner.pause();
        // Freeze the round (shows the pause card) before the WebView stops running.
        if (web != null) web.evaluateJavascript("window.eatInPause && window.eatInPause()", null);
        if (web != null) { web.onPause(); web.pauseTimers(); }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) { web.onResume(); web.resumeTimers(); }
        if (banner != null) banner.resume();
    }

    @Override
    protected void onDestroy() {
        dropBanner();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
