package id.ruangkamera.mobile;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.text.InputType;

import java.net.URI;

public final class MainActivity extends Activity {
    private static final String PREFS = "viewer";
    private static final String DEFAULT_ADDRESS = "http://192.168.1.3:8787";
    private LinearLayout root;
    private EditText addressInput;
    private WebView webView;
    private String allowedOrigin;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(13, 32, 36));
        getWindow().setNavigationBarColor(Color.rgb(10, 18, 26));
        showSetup();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private void showSetup() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(36), dp(24), dp(24));
        root.setBackgroundColor(Color.rgb(10, 18, 26));

        TextView title = text("Ruang Kamera", 26, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);
        TextView subtitle = text("Penampil kamera lokal tanpa iklan", 15, Color.rgb(169, 190, 205));
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-1, -2);
        subParams.topMargin = dp(8);
        root.addView(subtitle, subParams);

        TextView instructions = text("Jalankan viewer di PC, lalu masukkan alamat LAN PC yang tampil di jendela aplikasi. HP dan PC harus memakai Wi-Fi yang sama.", 15, Color.rgb(208, 221, 230));
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(-1, -2);
        infoParams.topMargin = dp(28);
        root.addView(instructions, infoParams);

        addressInput = new EditText(this);
        addressInput.setSingleLine(true);
        addressInput.setTextColor(Color.WHITE);
        addressInput.setHintTextColor(Color.rgb(133, 154, 169));
        addressInput.setHint("Contoh: http://192.168.1.3:8787");
        addressInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addressInput.setText(getPreferences(0).getString("address", DEFAULT_ADDRESS));
        addressInput.setSelectAllOnFocus(true);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(-1, -2);
        inputParams.topMargin = dp(18);
        root.addView(addressInput, inputParams);

        Button open = new Button(this);
        open.setText("Buka viewer");
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, -2);
        buttonParams.topMargin = dp(14);
        root.addView(open, buttonParams);
        open.setOnClickListener(v -> openViewer());

        TextView note = text("Aplikasi ini tersambung ke viewer di PC melalui jaringan rumah. Jangan buka port viewer ke internet.", 13, Color.rgb(146, 170, 187));
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
        noteParams.topMargin = dp(20);
        root.addView(note, noteParams);
        setContentView(root);
    }

    private boolean isPrivateIpv4(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) return false;
        int[] ip = new int[4];
        try {
            for (int i = 0; i < 4; i++) {
                if (parts[i].isEmpty() || (parts[i].length() > 1 && parts[i].startsWith("0"))) return false;
                ip[i] = Integer.parseInt(parts[i]);
                if (ip[i] < 0 || ip[i] > 255) return false;
            }
        } catch (NumberFormatException e) { return false; }
        return ip[0] == 10 || (ip[0] == 192 && ip[1] == 168)
                || (ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31);
    }

    private void openViewer() {
        String value = addressInput.getText().toString().trim();
        try {
            URI uri = new URI(value);
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                    || !isPrivateIpv4(uri.getHost()) || uri.getPort() < 1 || uri.getPort() > 65535
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath()))) {
                throw new IllegalArgumentException();
            }
            String base = "http://" + uri.getHost() + ":" + uri.getPort();
            getPreferences(0).edit().putString("address", base).apply();
            allowedOrigin = base;
            ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(addressInput.getWindowToken(), 0);
            showWebView(base);
        } catch (Exception e) {
            addressInput.setError("Gunakan http://IP-LAN-PC:8787");
        }
    }

    private void showWebView(String base) {
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !allowedOrigin.equals(request.getUrl().getScheme() + "://" + request.getUrl().getHost() + ":" + request.getUrl().getPort());
            }
        });
        setContentView(webView);
        webView.loadUrl(base + "/");
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else if (webView != null) { webView.destroy(); webView = null; showSetup(); }
        else super.onBackPressed();
    }
}
