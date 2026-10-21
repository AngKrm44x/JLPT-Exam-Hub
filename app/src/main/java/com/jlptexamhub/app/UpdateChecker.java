package com.jlptexamhub.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/** Cek release terbaru GitHub. Catatan rilis (body) jadi isi popup. */
final class UpdateChecker {

    interface Listener { void onUpdate(Info info); }

    static final class Info {
        String name = "", notes = "", url = "";
        boolean mandatory;
    }

    private static volatile boolean running = false;

    static void check(Context ctx, final Listener l) {
        final String repo = BuildConfig.GITHUB_REPO;
        if (running || repo == null || repo.startsWith("GANTI_")) return;
        running = true;
        new Thread(new Runnable() {
            @Override public void run() {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL("https://api.github.com/repos/" + repo + "/releases/latest").openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    c.setRequestProperty("Accept", "application/vnd.github+json");
                    c.setRequestProperty("User-Agent", "JLPTExamApp");
                    if (c.getResponseCode() != 200) return;
                    StringBuilder sb = new StringBuilder();
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                    String line; while ((line = r.readLine()) != null) sb.append(line).append('\n');
                    JSONObject j = new JSONObject(sb.toString());
                    if (j.optBoolean("draft") || j.optBoolean("prerelease")) return;
                    String tag = j.optString("tag_name", "");
                    if (compare(tag, BuildConfig.VERSION_NAME) <= 0) return;

                    Info i = new Info();
                    i.name = j.optString("name", "").isEmpty() ? tag : j.optString("name");
                    String body = j.optString("body", "").trim();
                    i.mandatory = body.toLowerCase().contains("[wajib]");
                    i.notes = body.replaceAll("(?i)\\[wajib\\]", "").trim();
                    i.url = j.optString("html_url", "");
                    JSONArray a = j.optJSONArray("assets");
                    if (a != null) for (int k = 0; k < a.length(); k++) {
                        JSONObject as = a.getJSONObject(k);
                        if (as.optString("name").toLowerCase().endsWith(".apk")) {
                            i.url = as.optString("browser_download_url", i.url);
                            break;
                        }
                    }
                    if (!i.url.isEmpty()) l.onUpdate(i);
                } catch (Exception ignored) {
                } finally {
                    if (c != null) c.disconnect();
                    running = false;
                }
            }
        }).start();
    }

    /** Bandingkan "v1.2.3" vs "1.2" secara numerik. */
    static int compare(String a, String b) {
        int[] x = parse(a), y = parse(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0, q = i < y.length ? y[i] : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    private static int[] parse(String v) {
        v = v.trim().replaceFirst("^[vV]", "");
        String[] parts = v.split("[^0-9]+");
        int[] out = new int[parts.length];
        int n = 0;
        for (String p : parts) if (!p.isEmpty()) out[n++] = Integer.parseInt(p.length() > 9 ? "0" : p);
        return java.util.Arrays.copyOf(out, n);
    }
}
