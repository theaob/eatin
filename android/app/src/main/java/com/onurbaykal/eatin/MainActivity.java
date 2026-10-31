package com.onurbaykal.eatin;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.webkit.JavascriptInterface;
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
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Full-screen WebView that loads assets/index.html (the game), plus AdMob.
 *
 * The page drives the ads through window.EatInAds (see Bridge) and hears back
 * through window.eatInAds.banner(heightPx), .done() and .privacy(required).
 * Ads only start after the UMP consent flow says they may be requested.
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
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        web.loadUrl("file:///android_asset/index.html");
        hideBars();
        requestConsent();
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
        if (ad == null || interstitialShowing) return false;
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
        public boolean interstitialReady() { return interstitial != null && !interstitialShowing; }

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
