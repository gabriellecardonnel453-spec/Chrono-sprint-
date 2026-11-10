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
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONException;
import org.json.JSONObject;

/** Conteneur Android léger avec passerelle GPS native (site officiel seulement). */
public class MainActivity extends Activity {
    private static final String APP_URL =
        "https://gabriellecardonnel453-spec.github.io/Chrono-sprint-/";
    private static final String APP_ORIGIN = "https://gabriellecardonnel453-spec.github.io";
    private static final int LOCATION_REQUEST = 19;

    private WebView web;
    private LocationManager locationManager;
    private LocationListener locationListener;
    private boolean wantsGps = false;
    private GeolocationPermissions.Callback waitingLocation;
    private String waitingOrigin;

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
            || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocationPermission() {
        requestPermissions(new String[] {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        }, LOCATION_REQUEST);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(236, 239, 241));
        getWindow().getDecorView()
            .setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(236, 239, 241));
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setGeolocationEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        web.addJavascriptInterface(new SprintGpsBridge(), "SprintAndroidGPS");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(
                    WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                return !(url.startsWith(APP_URL)
                    || url.equals(APP_URL.substring(0, APP_URL.length() - 1)));
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onGeolocationPermissionsShowPrompt(
                    String origin, GeolocationPermissions.Callback callback) {
                // Secours pour l'API GPS du navigateur intégré.
                if (!APP_ORIGIN.equals(origin)) {
                    callback.invoke(origin, false, false);
                    return;
                }
                if (hasLocationPermission()) {
                    callback.invoke(origin, true, false);
                } else {
                    waitingLocation = callback;
                    waitingOrigin = origin;
                    requestLocationPermission();
                }
            }
        });
        setContentView(web);
        web.loadUrl(APP_URL);
    }

    /** Les méthodes sont appelées par le JavaScript du site, et jamais en accès direct. */
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
        String escaped = JSONObject.quote(message);
        web.evaluateJavascript("window.onSprintNativeError && window.onSprintNativeError("
            + escaped + ");", null);
    }

    private void dispatchFix(Location location) {
        if (!wantsGps || web == null || location == null) return;
        try {
            JSONObject c = new JSONObject();
            c.put("latitude", location.getLatitude());
            c.put("longitude", location.getLongitude());
            c.put("accuracy", location.hasAccuracy() ? location.getAccuracy() : 999);
            if (location.hasSpeed()) {
                c.put("speed", Math.max(0.0f, location.getSpeed()));
            } else {
                c.put("speed", JSONObject.NULL);
            }
            JSONObject payload = new JSONObject();
            payload.put("coords", c);
            payload.put("timestamp", location.getTime());
            web.evaluateJavascript("window.onSprintNativePosition && window.onSprintNativePosition("
                + payload.toString() + ");", null);
        } catch (JSONException e) {
            dispatchError("Impossible de lire la position GPS");
        }
    }

    private void startNativeGps() {
        wantsGps = true;
        if (!hasLocationPermission()) {
            requestLocationPermission();
            return;
        }
        if (locationListener != null) return;
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            dispatchError("GPS Android indisponible");
            return;
        }
        final boolean gpsOn =
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        final boolean networkOn = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        if (!gpsOn && !networkOn) {
            dispatchError("Active la localisation du téléphone");
            return;
        }
        locationListener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                dispatchFix(location);
            }
            @Override public void onProviderDisabled(String provider) {
                if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    && !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    dispatchError("Localisation désactivée dans Android");
                }
            }
        };
        try {
            if (gpsOn) locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 300L, 0f, locationListener, Looper.getMainLooper());
            if (networkOn) locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 600L, 0f, locationListener, Looper.getMainLooper());
        } catch (SecurityException | IllegalArgumentException e) {
            stopNativeGps();
            dispatchError("Autorisation GPS Android manquante");
        }
    }

    private void stopNativeGps() {
        wantsGps = false;
        if (locationManager != null && locationListener != null) {
            try { locationManager.removeUpdates(locationListener); }
            catch (SecurityException ignored) { }
        }
        locationListener = null;
    }

    @Override public void onRequestPermissionsResult(
            int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code != LOCATION_REQUEST) return;
        boolean granted = hasLocationPermission();
        if (waitingLocation != null) {
            waitingLocation.invoke(waitingOrigin, granted, false);
            waitingLocation = null;
            waitingOrigin = null;
        }
        if (wantsGps) {
            if (granted) startNativeGps();
            else dispatchError("GPS refusé : autorise la position dans les réglages");
        }
    }

    @Override protected void onPause() {
        // Évite les mises à jour GPS inutiles lorsque l'application quitte l'avant-plan.
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        stopNativeGps();
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
