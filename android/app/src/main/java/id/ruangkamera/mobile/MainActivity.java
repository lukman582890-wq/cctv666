package id.ruangkamera.mobile;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.ui.PlayerView;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(9, 17, 25);
    private static final int PANEL = Color.rgb(17, 29, 40);
    private static final int INPUT = Color.rgb(10, 20, 29);
    private static final int LINE = Color.rgb(42, 60, 75);
    private static final int CYAN = Color.rgb(67, 209, 201);
    private static final int MUTED = Color.rgb(159, 183, 200);
    private static final String DEFAULT_IP = "";

    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final List<String> streamCandidates = new ArrayList<>();
    private final List<OnvifClient.Preset> presets = new ArrayList<>();
    private EditText ipInput, portInput, userInput, passwordInput, presetNameInput;
    private TextView statusText;
    private Button connectButton;
    private Spinner presetSpinner;
    private PlayerView playerView;
    private ExoPlayer player;
    private OnvifClient.Session session;
    private String cameraIp, cameraUser, cameraPassword;
    private int streamIndex = -1;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(13, 32, 36));
        getWindow().setNavigationBarColor(BG);
        buildScreen();
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        playerView.setUseController(false);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) setStatus("Kamera terhubung");
                else if (playbackState == Player.STATE_BUFFERING) setStatus("Menerima video dari kamera…");
            }

            @Override public void onPlayerError(PlaybackException error) {
                int next = streamIndex + 1;
                if (next < streamCandidates.size()) {
                    streamIndex = next;
                    playCandidate(next);
                } else {
                    setStatus("Video gagal dibuka. Periksa RTSP, jalur stream, dan kata sandi kamera.");
                }
            }
        });
        ipInput.setText(getPreferences(0).getString("cameraIp", DEFAULT_IP));
        userInput.setText(getPreferences(0).getString("cameraUser", "admin"));
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private GradientDrawable rounded(int color, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(14));
        d.setStroke(dp(1), stroke);
        return d;
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private EditText field(String hint, int inputType, boolean secret) {
        EditText v = new EditText(this);
        v.setSingleLine(true);
        v.setTextSize(15);
        v.setTextColor(Color.WHITE);
        v.setHintTextColor(Color.rgb(122, 146, 164));
        v.setHint(hint);
        v.setInputType(inputType | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : 0));
        v.setPadding(dp(13), dp(5), dp(13), dp(5));
        v.setBackground(rounded(INPUT, LINE));
        v.setBackgroundTintList(ColorStateList.valueOf(LINE));
        return v;
    }

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    private LinearLayout row() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    private LinearLayout panel() {
        LinearLayout v = column();
        v.setPadding(dp(16), dp(15), dp(16), dp(16));
        v.setBackground(rounded(PANEL, LINE));
        return v;
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout page = column();
        page.setPadding(dp(16), dp(18), dp(16), dp(28));
        scroll.addView(page);

        TextView title = label("Ruang Kamera", 23, Color.WHITE, true);
        page.addView(title);
        TextView subtitle = label("Terhubung langsung ke kamera di jaringan rumah", 13, MUTED, false);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-1, -2);
        subParams.topMargin = dp(4);
        subParams.bottomMargin = dp(15);
        page.addView(subtitle, subParams);

        playerView = new PlayerView(this);
        playerView.setBackgroundColor(Color.BLACK);
        playerView.setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
        LinearLayout.LayoutParams videoParams = new LinearLayout.LayoutParams(-1, dp(230));
        videoParams.bottomMargin = dp(12);
        playerView.setLayoutParams(videoParams);
        playerView.setBackground(rounded(Color.BLACK, LINE));
        page.addView(playerView);

        LinearLayout statusRow = row();
        View dot = new View(this);
        GradientDrawable dotShape = new GradientDrawable();
        dotShape.setShape(GradientDrawable.OVAL);
        dotShape.setColor(Color.rgb(125, 141, 151));
        dot.setBackground(dotShape);
        statusRow.addView(dot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        statusText = label("Belum terhubung", 13, MUTED, false);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(0, -2, 1f);
        statusParams.leftMargin = dp(8);
        statusRow.addView(statusText, statusParams);
        LinearLayout.LayoutParams statusRowParams = new LinearLayout.LayoutParams(-1, -2);
        statusRowParams.bottomMargin = dp(14);
        page.addView(statusRow, statusRowParams);

        LinearLayout cameraPanel = panel();
        cameraPanel.addView(label("Hubungkan kamera", 19, Color.WHITE, true));
        TextView note = label("RTSP untuk video, ONVIF untuk PTZ. HP dan kamera harus berada di Wi-Fi yang sama.", 12, MUTED, false);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
        noteParams.topMargin = dp(5);
        noteParams.bottomMargin = dp(13);
        cameraPanel.addView(note, noteParams);

        cameraPanel.addView(label("Alamat IP kamera", 12, MUTED, false));
        ipInput = field("Contoh: 192.168.1.100", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, false);
        addField(cameraPanel, ipInput, 8, 11);

        LinearLayout creds = row();
        LinearLayout usernameCol = column();
        usernameCol.addView(label("Pengguna kamera", 12, MUTED, false));
        userInput = field("admin", InputType.TYPE_CLASS_TEXT, false);
        usernameCol.addView(userInput, new LinearLayout.LayoutParams(-1, dp(46)));
        LinearLayout.LayoutParams usernameParams = new LinearLayout.LayoutParams(0, -2, 1.25f);
        usernameParams.rightMargin = dp(8);
        creds.addView(usernameCol, usernameParams);

        LinearLayout portCol = column();
        portCol.addView(label("Port ONVIF", 12, MUTED, false));
        portInput = field("otomatis", InputType.TYPE_CLASS_NUMBER, false);
        portCol.addView(portInput, new LinearLayout.LayoutParams(-1, dp(46)));
        creds.addView(portCol, new LinearLayout.LayoutParams(0, -2, 0.75f));
        LinearLayout.LayoutParams credsParams = new LinearLayout.LayoutParams(-1, -2);
        credsParams.bottomMargin = dp(10);
        cameraPanel.addView(creds, credsParams);

        cameraPanel.addView(label("Kata sandi kamera", 12, MUTED, false));
        passwordInput = field("Dipakai saat tersambung; tidak disimpan", InputType.TYPE_CLASS_TEXT, true);
        addField(cameraPanel, passwordInput, 8, 11);

        connectButton = button("Hubungkan", true);
        cameraPanel.addView(connectButton, new LinearLayout.LayoutParams(-1, dp(48)));
        connectButton.setOnClickListener(v -> connect());
        page.addView(cameraPanel);

        LinearLayout ptzPanel = panel();
        LinearLayout.LayoutParams ptzParams = new LinearLayout.LayoutParams(-1, -2);
        ptzParams.topMargin = dp(12);
        ptzPanel.setLayoutParams(ptzParams);
        ptzPanel.addView(label("Kontrol PTZ", 18, Color.WHITE, true));
        TextView ptzNote = label("Tahan arah untuk bergerak. Lepaskan untuk berhenti.", 12, MUTED, false);
        LinearLayout.LayoutParams ptzNoteParams = new LinearLayout.LayoutParams(-1, -2);
        ptzNoteParams.topMargin = dp(3);
        ptzPanel.addView(ptzNote, ptzNoteParams);
        GridLayout pad = new GridLayout(this);
        pad.setColumnCount(3);
        pad.setRowCount(3);
        pad.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        String[] arrows = {"", "↑", "", "←", "■", "→", "", "↓", ""};
        int[][] move = {{0,0},{0,1},{0,0},{-1,0},{0,0},{1,0},{0,0},{0,-1},{0,0}};
        for (int i = 0; i < arrows.length; i++) {
            Button b = button(arrows[i].isEmpty() ? " " : arrows[i], false);
            b.setTextSize(18);
            GridLayout.LayoutParams gp = new GridLayout.LayoutParams(GridLayout.spec(i / 3), GridLayout.spec(i % 3));
            gp.width = dp(54);
            gp.height = dp(44);
            gp.setMargins(dp(3), dp(3), dp(3), dp(3));
            pad.addView(b, gp);
            if (!arrows[i].isEmpty() && i != 4) bindMove(b, move[i][0], move[i][1], 0);
            if (i == 4) b.setOnClickListener(v -> stopPtz());
            if (arrows[i].isEmpty()) b.setEnabled(false);
        }
        LinearLayout.LayoutParams padParams = new LinearLayout.LayoutParams(-2, -2);
        padParams.gravity = Gravity.CENTER_HORIZONTAL;
        padParams.topMargin = dp(9);
        ptzPanel.addView(pad, padParams);

        LinearLayout zoom = row();
        zoom.setGravity(Gravity.CENTER);
        Button zoomIn = button("Zoom +", false);
        Button zoomOut = button("Zoom −", false);
        zoom.addView(zoomIn, new LinearLayout.LayoutParams(dp(108), dp(42)));
        LinearLayout.LayoutParams zoomOutParams = new LinearLayout.LayoutParams(dp(108), dp(42));
        zoomOutParams.leftMargin = dp(8);
        zoom.addView(zoomOut, zoomOutParams);
        bindMove(zoomIn, 0, 0, 1);
        bindMove(zoomOut, 0, 0, -1);
        LinearLayout.LayoutParams zoomParams = new LinearLayout.LayoutParams(-1, -2);
        zoomParams.topMargin = dp(3);
        ptzPanel.addView(zoom, zoomParams);

        presetSpinner = new Spinner(this);
        presetSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new ArrayList<>(List.of("Preset kamera"))));
        LinearLayout.LayoutParams spinParams = new LinearLayout.LayoutParams(-1, dp(46));
        spinParams.topMargin = dp(12);
        ptzPanel.addView(presetSpinner, spinParams);
        LinearLayout presetRow = row();
        presetNameInput = field("Nama preset baru", InputType.TYPE_CLASS_TEXT, false);
        presetRow.addView(presetNameInput, new LinearLayout.LayoutParams(0, dp(46), 1f));
        Button savePreset = button("Simpan", false);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(dp(92), dp(46));
        saveParams.leftMargin = dp(7);
        presetRow.addView(savePreset, saveParams);
        LinearLayout.LayoutParams presetRowParams = new LinearLayout.LayoutParams(-1, -2);
        presetRowParams.topMargin = dp(7);
        ptzPanel.addView(presetRow, presetRowParams);
        Button gotoPreset = button("Panggil preset", false);
        LinearLayout.LayoutParams gotoParams = new LinearLayout.LayoutParams(-1, dp(44));
        gotoParams.topMargin = dp(7);
        ptzPanel.addView(gotoPreset, gotoParams);
        savePreset.setOnClickListener(v -> savePreset());
        gotoPreset.setOnClickListener(v -> gotoPreset());
        page.addView(ptzPanel);

        TextView privacy = label("Kata sandi hanya disimpan sementara selama aplikasi berjalan. Akses kamera dibatasi ke alamat lokal.", 11, MUTED, false);
        LinearLayout.LayoutParams privacyParams = new LinearLayout.LayoutParams(-1, -2);
        privacyParams.topMargin = dp(12);
        privacyParams.leftMargin = dp(2);
        privacyParams.rightMargin = dp(2);
        page.addView(privacy, privacyParams);

        setContentView(scroll);
    }

    private void addField(LinearLayout parent, EditText field, int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(46));
        p.topMargin = dp(top);
        p.bottomMargin = dp(bottom);
        parent.addView(field, p);
    }

    private Button button(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTextColor(primary ? Color.rgb(4, 30, 34) : Color.WHITE);
        b.setBackground(rounded(primary ? CYAN : Color.rgb(34, 51, 66), primary ? CYAN : LINE));
        return b;
    }

    private boolean isPrivateIpv4(String value) {
        String[] parts = value.trim().split("\\.");
        if (parts.length != 4) return false;
        int[] p = new int[4];
        try {
            for (int i = 0; i < 4; i++) {
                if (parts[i].isEmpty() || (parts[i].length() > 1 && parts[i].startsWith("0"))) return false;
                p[i] = Integer.parseInt(parts[i]);
                if (p[i] < 0 || p[i] > 255) return false;
            }
        } catch (NumberFormatException e) { return false; }
        return p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] >= 16 && p[1] <= 31);
    }

    private void connect() {
        cameraIp = ipInput.getText().toString().trim();
        cameraUser = userInput.getText().toString().trim();
        cameraPassword = passwordInput.getText().toString();
        int onvifPort = 0;
        try { if (!portInput.getText().toString().trim().isEmpty()) onvifPort = Integer.parseInt(portInput.getText().toString().trim()); }
        catch (NumberFormatException ignored) { portInput.setError("Port tidak valid"); return; }
        if (!isPrivateIpv4(cameraIp)) { ipInput.setError("Masukkan IP kamera di jaringan lokal"); return; }
        if (cameraUser.isEmpty()) { userInput.setError("Masukkan nama pengguna kamera"); return; }
        final int requestedPort = onvifPort;
        getPreferences(0).edit().putString("cameraIp", cameraIp).putString("cameraUser", cameraUser).apply();
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(passwordInput.getWindowToken(), 0);
        connectButton.setEnabled(false);
        connectButton.setText("Menghubungkan…");
        setStatus("Mencari RTSP dan ONVIF kamera…");
        streamCandidates.clear();
        presets.clear();
        network.execute(() -> {
            OnvifClient.Session found = OnvifClient.discover(cameraIp, cameraUser, cameraPassword, requestedPort);
            List<String> candidates = new ArrayList<>(found.streamUris);
            candidates.add(OnvifClient.makeRtspUri(cameraIp, cameraUser, cameraPassword, "/live/ch00_1"));
            candidates.add(OnvifClient.makeRtspUri(cameraIp, cameraUser, cameraPassword, "/live/ch00_0"));
            runOnUiThread(() -> {
                session = found;
                for (String candidate : candidates) if (!streamCandidates.contains(candidate)) streamCandidates.add(candidate);
                passwordInput.setText("");
                streamIndex = 0;
                playCandidate(0);
                connectButton.setEnabled(true);
                connectButton.setText("Hubungkan lagi");
                loadPresets();
            });
        });
    }

    private void playCandidate(int index) {
        if (index < 0 || index >= streamCandidates.size() || player == null) return;
        streamIndex = index;
        String candidate = streamCandidates.get(index);
        setStatus(index == 0 ? "Membuka stream langsung dari kamera…" : "Mencoba jalur stream kamera…");
        try {
            RtspMediaSource.Factory factory = new RtspMediaSource.Factory().setForceUseRtpTcp(true).setTimeoutMs(8000);
            player.setMediaSource(factory.createMediaSource(MediaItem.fromUri(URI.create(candidate).toString())));
            player.prepare();
            player.play();
        } catch (Exception e) {
            int next = index + 1;
            if (next < streamCandidates.size()) playCandidate(next);
            else setStatus("Alamat RTSP kamera tidak valid.");
        }
    }

    private void bindMove(Button button, int pan, int tilt, int zoom) {
        button.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                sendMove(pan, tilt, zoom);
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                stopPtz();
                return true;
            }
            return true;
        });
    }

    private void sendMove(int pan, int tilt, int zoom) {
        OnvifClient.Session current = session;
        if (current == null || current.ptzEndpoint == null || current.profileToken == null) {
            setStatus("Kamera belum menyediakan PTZ ONVIF.");
            return;
        }
        network.execute(() -> {
            try { OnvifClient.move(current, pan, tilt, zoom); }
            catch (Exception e) { runOnUiThread(() -> setStatus(e.getMessage())); }
        });
    }

    private void stopPtz() {
        OnvifClient.Session current = session;
        if (current == null || current.ptzEndpoint == null || current.profileToken == null) return;
        network.execute(() -> {
            try { OnvifClient.stop(current); }
            catch (Exception e) { runOnUiThread(() -> setStatus(e.getMessage())); }
        });
    }

    private void loadPresets() {
        OnvifClient.Session current = session;
        if (current == null || current.ptzEndpoint == null || current.profileToken == null) return;
        network.execute(() -> {
            try {
                List<OnvifClient.Preset> loaded = OnvifClient.getPresets(current);
                runOnUiThread(() -> {
                    presets.clear(); presets.addAll(loaded);
                    List<String> labels = new ArrayList<>();
                    if (presets.isEmpty()) labels.add("Belum ada preset kamera");
                    else for (OnvifClient.Preset p : presets) labels.add(p.name);
                    presetSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
                });
            } catch (Exception e) { runOnUiThread(() -> setStatus("Preset ONVIF tidak tersedia.")); }
        });
    }

    private void savePreset() {
        OnvifClient.Session current = session;
        String name = presetNameInput.getText().toString().trim();
        if (current == null || name.isEmpty()) { setStatus("Hubungkan kamera dan isi nama preset terlebih dahulu."); return; }
        network.execute(() -> {
            try {
                OnvifClient.savePreset(current, name);
                runOnUiThread(() -> { presetNameInput.setText(""); setStatus("Preset disimpan di kamera."); loadPresets(); });
            } catch (Exception e) { runOnUiThread(() -> setStatus("Gagal menyimpan preset ONVIF.")); }
        });
    }

    private void gotoPreset() {
        int index = presetSpinner.getSelectedItemPosition();
        OnvifClient.Session current = session;
        if (current == null || index < 0 || index >= presets.size()) { setStatus("Pilih preset kamera terlebih dahulu."); return; }
        String token = presets.get(index).token;
        network.execute(() -> {
            try { OnvifClient.gotoPreset(current, token); }
            catch (Exception e) { runOnUiThread(() -> setStatus("Gagal memanggil preset ONVIF.")); }
        });
    }

    private void setStatus(String message) { runOnUiThread(() -> statusText.setText(message)); }

    @Override protected void onStop() {
        super.onStop();
        if (player != null) player.pause();
    }

    @Override protected void onDestroy() {
        if (player != null) { player.release(); player = null; }
        network.shutdownNow();
        super.onDestroy();
    }
}
