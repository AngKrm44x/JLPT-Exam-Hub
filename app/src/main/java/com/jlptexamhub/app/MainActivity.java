package com.jlptexamhub.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Arrays;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_FILE = 1001;
    private static final long UPDATE_POLL_MS = 5 * 60 * 1000L;
    /** Host selain situs sendiri yang tetap dibuka di dalam app (login, bayar, captcha). */
    private static final List<String> INAPP_HOSTS = Arrays.asList(
            "supabase.co", "jlpt-auth-bot.vercel.app", "hcaptcha.com",
            "paypal.com", "stripe.com", "tripay.co.id");

    private FrameLayout root;
    private WebView web;
    private ProgressBar progress;
    private LinearLayout offline;
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;
    private ValueCallback<Uri[]> filePathCb;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback netCb;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String siteHost;
    private boolean pageFailed = false;
    private boolean updateDialogShown = false;

    private final Runnable updateTick = new Runnable() {
        @Override public void run() {
            checkUpdate();
            ui.postDelayed(this, UPDATE_POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        siteHost = Uri.parse(BuildConfig.SITE_URL).getHost();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#F3EDE0"));
        setContentView(root);

        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-1, dp(3));
        plp.gravity = Gravity.TOP;
        root.addView(progress, plp);

        buildOfflineView();
        setupWebView();

        cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        netCb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) {
                ui.post(new Runnable() { @Override public void run() {
                    if (pageFailed) reloadFresh();
                }});
            }
        };
        try { cm.registerDefaultNetworkCallback(netCb); } catch (Exception ignored) {}

        if (b != null) web.restoreState(b); else web.loadUrl(BuildConfig.SITE_URL);
    }

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);
        s.setUserAgentString(s.getUserAgentString() + " JLPTExamApp/" + BuildConfig.VERSION_NAME);
        web.setBackgroundColor(Color.parseColor("#F3EDE0"));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        CookieManager cmgr = CookieManager.getInstance();
        cmgr.setAcceptCookie(true);
        cmgr.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return handleUrl(r.getUrl());
            }
            @Override public void onPageStarted(WebView v, String url, Bitmap f) {
                progress.setVisibility(View.VISIBLE);
                pageFailed = false;
            }
            @Override public void onPageFinished(WebView v, String url) {
                progress.setVisibility(View.GONE);
                if (!pageFailed) offline.setVisibility(View.GONE);
                CookieManager.getInstance().flush();
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) { pageFailed = true; offline.setVisibility(View.VISIBLE); }
            }
            @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                // WebView crash/OOM: bangun ulang biar ga force close.
                root.removeView(web);
                web.destroy();
                web = new WebView(MainActivity.this);
                root.addView(web, 0, new FrameLayout.LayoutParams(-1, -1));
                setupWebView();
                web.loadUrl(BuildConfig.SITE_URL);
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int p) { progress.setProgress(p); }

            @Override public void onShowCustomView(View view, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = view; customCb = cb;
                web.setVisibility(View.GONE);
                root.addView(view, new FrameLayout.LayoutParams(-1, -1));
                goImmersive(true);
            }
            @Override public void onHideCustomView() {
                if (customView == null) return;
                root.removeView(customView);
                customView = null;
                if (customCb != null) customCb.onCustomViewHidden();
                customCb = null;
                web.setVisibility(View.VISIBLE);
                goImmersive(false);
            }
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (filePathCb != null) filePathCb.onReceiveValue(null);
                filePathCb = cb;
                try {
                    startActivityForResult(p.createIntent(), REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    filePathCb = null; return false;
                }
                return true;
            }
        });
    }

    private boolean handleUrl(Uri u) {
        String scheme = u.getScheme() == null ? "" : u.getScheme();
        String host = u.getHost() == null ? "" : u.getHost();
        if (scheme.equals("http") || scheme.equals("https")) {
            if (host.equals(siteHost) || host.endsWith("." + siteHost)) return false;
            for (String h : INAPP_HOSTS) if (host.equals(h) || host.endsWith("." + h)) return false;
            // Google login diblok Google di WebView -> buka browser luar.
        }
        try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
        return true;
    }

    private void buildOfflineView() {
        offline = new LinearLayout(this);
        offline.setOrientation(LinearLayout.VERTICAL);
        offline.setGravity(Gravity.CENTER);
        offline.setPadding(dp(32), dp(32), dp(32), dp(32));
        offline.setBackgroundColor(Color.parseColor("#F3EDE0"));
        offline.setVisibility(View.GONE);
        offline.setClickable(true);

        TextView t = new TextView(this);
        t.setText("Tidak ada koneksi");
        t.setTextSize(22); t.setTextColor(Color.parseColor("#221D18"));
        t.setGravity(Gravity.CENTER);
        TextView d = new TextView(this);
        d.setText("Soal dimuat langsung dari server, jadi butuh internet.\nAkan dimuat otomatis saat tersambung.");
        d.setTextColor(Color.parseColor("#5A5046")); d.setGravity(Gravity.CENTER);
        d.setPadding(0, dp(12), 0, dp(20));
        Button btn = new Button(this);
        btn.setText("Coba lagi");
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { reloadFresh(); }
        });
        offline.addView(t); offline.addView(d); offline.addView(btn);
        root.addView(offline, new FrameLayout.LayoutParams(-1, -1));
    }

    private void reloadFresh() {
        pageFailed = false;
        String cur = web.getUrl();
        if (cur == null || cur.startsWith("data:") || cur.equals("about:blank")) web.loadUrl(BuildConfig.SITE_URL);
        else web.reload();
    }

    private void checkUpdate() {
        UpdateChecker.check(this, new UpdateChecker.Listener() {
            @Override public void onUpdate(final UpdateChecker.Info i) {
                runOnUiThread(new Runnable() { @Override public void run() { showUpdate(i); } });
            }
        });
    }

    private void showUpdate(final UpdateChecker.Info i) {
        if (updateDialogShown || isFinishing()) return;
        updateDialogShown = true;
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Update tersedia: " + i.name)
                .setMessage(i.notes.isEmpty() ? "Versi baru sudah dirilis." : i.notes)
                .setCancelable(!i.mandatory)
                .setPositiveButton("Update sekarang", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        updateDialogShown = false;
                        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(i.url))); } catch (Exception ignored) {}
                    }
                });
        if (!i.mandatory) {
            b.setNegativeButton("Nanti", new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int w) { /* muncul lagi di cek berikutnya? tidak: tunggu app dibuka ulang */ }
            });
        } else {
            b.setOnCancelListener(null);
        }
        b.show();
    }

    private void goImmersive(boolean on) {
        View d = getWindow().getDecorView();
        if (on) {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        } else {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE && filePathCb != null) {
            filePathCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            filePathCb = null;
        } else super.onActivityResult(req, res, data);
    }

    @Override public void onBackPressed() {
        if (customView != null) { web.getWebChromeClient().onHideCustomView(); return; }
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume();
        web.onResume();
        updateDialogShown = false;
        ui.removeCallbacks(updateTick);
        ui.post(updateTick);
    }

    @Override protected void onPause() {
        super.onPause();
        ui.removeCallbacks(updateTick);
        CookieManager.getInstance().flush();
        // sengaja TIDAK memanggil web.onPause(): timer ujian di web tetap jalan.
    }

    @Override protected void onSaveInstanceState(Bundle o) {
        super.onSaveInstanceState(o);
        web.saveState(o);
    }

    @Override protected void onDestroy() {
        try { cm.unregisterNetworkCallback(netCb); } catch (Exception ignored) {}
        ui.removeCallbacksAndMessages(null);
        web.destroy();
        super.onDestroy();
    }
}
