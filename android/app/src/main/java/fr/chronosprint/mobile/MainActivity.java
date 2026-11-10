package fr.chronosprint.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;

/**
 * Sprint hors connexion : le site HTML est inclus dans l'APK et servi depuis
 * une origine HTTPS virtuelle, sans chargement depuis GitHub ni accès réseau.
 * Le GPS vient du capteur GNSS du téléphone, jamais de la triangulation réseau.
 */
public class MainActivity extends Activity {
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final String HOME = ORIGIN + "/index.html";
    private static final int LOCATION_REQUEST = 19;

    private WebView web;
    private LocationManager locationManager;
    private LocationListener locationListener;
    private boolean wantsGps = false;
    private boolean inForeground = true;
    private long lastFixNanos = 0;

    private boolean hasFinePermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED;
    }

    private void askPermission() {
        requestPermissions(new String[] {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        }, LOCATION_REQUEST);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(236,239,241));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        locationManager = (LocationManager)getSystemService(Context.LOCATION_SERVICE);

        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(236,239,241));
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setGeolocationEnabled(false); // Uniquement la passerelle GPS native.
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);

        web.addJavascriptInterface(new SprintGpsBridge(), "SprintAndroidGPS");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !HOME.equals(request.getUrl().toString());
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (!url.startsWith(ORIGIN + "/")) return blocked();
                String path = url.substring((ORIGIN + "/").length());
                // Aucune URL arbitraire n'est exposée à l'interface JavaScript native.
                if (!("index.html".equals(path) || "icon-512.png".equals(path)
                    || "manifest.webmanifest".equals(path))) return blocked();
                String mime = path.endsWith(".png") ? "image/png" :
                    path.endsWith(".webmanifest") ? "application/manifest+json" : "text/html";
                try {
                    WebResourceResponse response = new WebResourceResponse(
                        mime, path.endsWith(".png") ? null : "UTF-8", getAssets().open(path));
                    HashMap<String,String> headers = new HashMap<>();
                    headers.put("Cache-Control", "no-store");
                    headers.put("X-Content-Type-Options", "nosniff");
                    response.setResponseHeaders(headers);
                    return response;
                } catch (IOException e) {
                    return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                        Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
                }
            }
            private WebResourceResponse blocked() {
                return new WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden",
                    Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        setContentView(web);
        web.loadUrl(HOME);
    }

    private class SprintGpsBridge {
        @JavascriptInterface public void start() {
            runOnUiThread(() -> startNativeGps());
        }
        @JavascriptInterface public void stop() {
            runOnUiThread(() -> stopNativeGps());
        }
    }

    private void dispatchError(String message) {
        if (web == null) return;
        web.evaluateJavascript("window.onSprintNativeError && window.onSprintNativeError("
            + JSONObject.quote(message) + ");", null);
    }

    private void dispatchFix(Location location) {
        if (!wantsGps || !inForeground || web == null || location == null) return;
        long fixNanos = location.getElapsedRealtimeNanos();
        long ageNanos = SystemClock.elapsedRealtimeNanos() - fixNanos;
        if (fixNanos <= lastFixNanos || ageNanos < 0 || ageNanos > 4_000_000_000L) return;
        if (!LocationManager.GPS_PROVIDER.equals(location.getProvider())) return;
        lastFixNanos = fixNanos;
        try {
            JSONObject c = new JSONObject();
            c.put("latitude", location.getLatitude());
            c.put("longitude", location.getLongitude());
            c.put("accuracy", location.hasAccuracy() ? location.getAccuracy() : 999);
            c.put("speed", location.hasSpeed() ? Math.max(0f,location.getSpeed()) : JSONObject.NULL);
            c.put("speedAccuracy",
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy()
                    ? location.getSpeedAccuracyMetersPerSecond() : JSONObject.NULL);
            JSONObject payload = new JSONObject();
            payload.put("coords", c);
            // Heure de capture dérivée de l'horloge monotone Android (pas de réseau).
            payload.put("timestamp", System.currentTimeMillis() - ageNanos/1_000_000L);
            web.evaluateJavascript("window.onSprintNativePosition && window.onSprintNativePosition("
                + payload.toString() + ");", null);
        } catch (JSONException e) {
            dispatchError("Donnée GPS invalide");
        }
    }

    private void startNativeGps() {
        wantsGps = true;
        if (!hasFinePermission()) {
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
                wantsGps=false;
                dispatchError("Active « Position précise » pour Sprint dans les réglages");
                return;
            }
            askPermission();
            return;
        }
        if (locationListener != null) return;
        if (locationManager == null || !locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            dispatchError("Active GPS et Position précise dans les réglages");
            return;
        }
        lastFixNanos = 0;
        locationListener = new LocationListener() {
            @Override public void onLocationChanged(Location location) { dispatchFix(location); }
            @Override public void onProviderDisabled(String provider) {
                if (LocationManager.GPS_PROVIDER.equals(provider))
                    dispatchError("GPS désactivé dans les réglages Android");
            }
        };
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 200L, 0f,
                locationListener, Looper.getMainLooper());
        } catch (SecurityException | IllegalArgumentException e) {
            stopNativeGps();
            dispatchError("Autorise la position précise pour Sprint");
        }
    }

    private void stopNativeGps() {
        wantsGps = false;
        if (locationManager != null && locationListener != null) {
            try { locationManager.removeUpdates(locationListener); }
            catch (SecurityException ignored) {}
        }
        locationListener = null;
    }

    @Override public void onRequestPermissionsResult(
        int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == LOCATION_REQUEST && wantsGps) {
            if (hasFinePermission()) startNativeGps();
            else { wantsGps = false; dispatchError("Autorise « Position précise » dans Android"); }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        inForeground = true;
        if (wantsGps && locationListener == null && hasFinePermission()) startNativeGps();
    }
    @Override protected void onPause() {
        inForeground = false;
        if (locationManager != null && locationListener != null) {
            try { locationManager.removeUpdates(locationListener); }
            catch (SecurityException ignored) {}
            locationListener = null;
        }
        super.onPause();
    }
    @Override public void onBackPressed() {
        // Pas de navigation hors de l'interface installée.
        super.onBackPressed();
    }
    @Override protected void onDestroy() {
        stopNativeGps();
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
