package id.ruangkamera.mobile;

import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class OnvifClient {
    static final class Session {
        final String ip;
        final String user;
        final String password;
        String deviceEndpoint;
        String ptzEndpoint;
        String profileToken;
        final List<String> streamUris = new ArrayList<>();
        Session(String ip, String user, String password) { this.ip = ip; this.user = user; this.password = password; }
    }

    static final class Preset {
        final String token, name;
        Preset(String token, String name) { this.token = token; this.name = name; }
    }

    private static final String SOAP_NS = "http://www.w3.org/2003/05/soap-envelope";
    private static final String TDS_NS = "http://www.onvif.org/ver10/device/wsdl";
    private static final String TRT_NS = "http://www.onvif.org/ver10/media/wsdl";
    private static final String TPTZ_NS = "http://www.onvif.org/ver20/ptz/wsdl";
    private static final String TT_NS = "http://www.onvif.org/ver10/schema";

    private OnvifClient() {}

    static Session discover(String ip, String user, String password, int requestedPort) {
        Session session = new Session(ip, user, password);
        int[] ports = requestedPort > 0 ? new int[]{requestedPort} : new int[]{8899, 80, 8080};
        String capabilities = null;
        for (int port : ports) {
            String endpoint = "http://" + ip + ":" + port + "/onvif/device_service";
            try {
                capabilities = soap(endpoint, "http://www.onvif.org/ver10/device/wsdl/GetCapabilities",
                        "<tds:GetCapabilities><tds:Category>All</tds:Category></tds:GetCapabilities>", session);
                session.deviceEndpoint = endpoint;
                break;
            } catch (Exception ignored) { }
        }
        if (session.deviceEndpoint == null) return session;

        String media = serviceAddress(capabilities, "Media");
        session.ptzEndpoint = serviceAddress(capabilities, "PTZ");
        if (media.isEmpty()) media = session.deviceEndpoint.replace("/onvif/device_service", "/onvif/media_service");
        try {
            String profiles = soap(media, TRT_NS + "/GetProfiles", "<trt:GetProfiles/>", session);
            Matcher profile = Pattern.compile("<[^>]*Profiles\\b[^>]*\\btoken=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(profiles);
            if (!profile.find()) return session;
            session.profileToken = unescape(profile.group(1));
            String body = "<trt:GetStreamUri><trt:StreamSetup><tt:Stream>RTP-Unicast</tt:Stream><tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport></trt:StreamSetup><trt:ProfileToken>" + xml(session.profileToken) + "</trt:ProfileToken></trt:GetStreamUri>";
            String response = soap(media, TRT_NS + "/GetStreamUri", body, session);
            String uri = unescape(tag(response, "Uri"));
            if (uri.startsWith("rtsp://") || uri.startsWith("rtsps://")) {
                String safe = addCredentials(uri, session);
                if (safe != null) session.streamUris.add(safe);
            }
        } catch (Exception ignored) { }
        return session;
    }

    private static String serviceAddress(String xml, String service) {
        String block = block(xml, service);
        String address = unescape(tag(block, "XAddr"));
        if (address.isEmpty()) return "";
        try {
            URI uri = URI.create(address);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) return "";
            if (!privateIpv4(uri.getHost())) return "";
            return address;
        } catch (Exception e) { return ""; }
    }

    static String makeRtspUri(String ip, String user, String password, String path) {
        String prefix = "rtsp://";
        if (user != null && !user.isEmpty()) prefix += Uri.encode(user) + ":" + Uri.encode(password == null ? "" : password) + "@";
        return prefix + ip + ":554" + (path.startsWith("/") ? path : "/" + path);
    }

    private static String addCredentials(String value, Session session) {
        try {
            URI uri = URI.create(value);
            if (!privateIpv4(uri.getHost())) return null;
            String host = uri.getHost();
            int port = uri.getPort();
            String authority = Uri.encode(session.user) + ":" + Uri.encode(session.password) + "@" + host + (port > 0 ? ":" + port : "");
            return uri.getScheme() + "://" + authority + uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        } catch (Exception e) { return null; }
    }

    static String withoutCredentials(String value) {
        try {
            URI uri = URI.create(value);
            if (!privateIpv4(uri.getHost())) return value;
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "")
                    + (uri.getRawPath() == null ? "" : uri.getRawPath())
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        } catch (Exception e) { return value; }
    }

    static void move(Session s, int pan, int tilt, int zoom) throws Exception {
        String body = "<tptz:ContinuousMove><tptz:ProfileToken>" + xml(s.profileToken) + "</tptz:ProfileToken><tptz:Velocity><tt:PanTilt x=\"" + pan + "\" y=\"" + tilt + "\"/><tt:Zoom x=\"" + zoom + "\"/></tptz:Velocity><tptz:Timeout>PT2S</tptz:Timeout></tptz:ContinuousMove>";
        soap(s.ptzEndpoint, TPTZ_NS + "/ContinuousMove", body, s);
    }

    static void stop(Session s) throws Exception {
        String body = "<tptz:Stop><tptz:ProfileToken>" + xml(s.profileToken) + "</tptz:ProfileToken><tptz:PanTilt>true</tptz:PanTilt><tptz:Zoom>true</tptz:Zoom></tptz:Stop>";
        soap(s.ptzEndpoint, TPTZ_NS + "/Stop", body, s);
    }

    static List<Preset> getPresets(Session s) throws Exception {
        String body = "<tptz:GetPresets><tptz:ProfileToken>" + xml(s.profileToken) + "</tptz:ProfileToken></tptz:GetPresets>";
        String response = soap(s.ptzEndpoint, TPTZ_NS + "/GetPresets", body, s);
        List<Preset> list = new ArrayList<>();
        Matcher m = Pattern.compile("<[^>]*Preset\\b([^>]*)>([\\s\\S]*?)</[^>]*Preset\\s*>", Pattern.CASE_INSENSITIVE).matcher(response);
        while (m.find()) {
            Matcher token = Pattern.compile("\\btoken=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(m.group(1));
            if (token.find()) {
                String name = unescape(tag(m.group(2), "Name"));
                list.add(new Preset(unescape(token.group(1)), name.isEmpty() ? "Preset " + (list.size() + 1) : name));
            }
        }
        return list;
    }

    static void savePreset(Session s, String name) throws Exception {
        String body = "<tptz:SetPreset><tptz:ProfileToken>" + xml(s.profileToken) + "</tptz:ProfileToken><tptz:PresetName>" + xml(name) + "</tptz:PresetName></tptz:SetPreset>";
        soap(s.ptzEndpoint, TPTZ_NS + "/SetPreset", body, s);
    }

    static void gotoPreset(Session s, String token) throws Exception {
        String body = "<tptz:GotoPreset><tptz:ProfileToken>" + xml(s.profileToken) + "</tptz:ProfileToken><tptz:PresetToken>" + xml(token) + "</tptz:PresetToken></tptz:GotoPreset>";
        soap(s.ptzEndpoint, TPTZ_NS + "/GotoPreset", body, s);
    }

    private static String soap(String endpoint, String action, String body, Session s) throws Exception {
        if (endpoint == null || endpoint.isEmpty()) throw new IllegalStateException("Layanan ONVIF tidak tersedia.");
        URI target = URI.create(endpoint);
        if (!privateIpv4(target.getHost())) throw new IllegalArgumentException("Alamat ONVIF bukan jaringan lokal.");
        String envelope = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><s:Envelope xmlns:s=\"" + SOAP_NS + "\" xmlns:tds=\"" + TDS_NS + "\" xmlns:trt=\"" + TRT_NS + "\" xmlns:tt=\"" + TT_NS + "\" xmlns:tptz=\"" + TPTZ_NS + "\"><s:Header>" + wsse(s.user, s.password) + "</s:Header><s:Body>" + body + "</s:Body></s:Envelope>";
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(5000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8; action=\"" + action + "\"");
        byte[] data = envelope.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(data.length);
        try (OutputStream output = connection.getOutputStream()) { output.write(data); }
        int code = connection.getResponseCode();
        InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String response = "";
        if (stream != null) {
            try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int n;
                while ((n = input.read(buffer)) >= 0) output.write(buffer, 0, n);
                response = output.toString("UTF-8");
            }
        }
        connection.disconnect();
        if (code < 200 || code >= 300 || response.matches("(?s).*<[^>]*Fault\\b.*")) throw new IllegalStateException("Kamera menolak perintah ONVIF.");
        return response;
    }

    private static String wsse(String user, String password) throws Exception {
        String created = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US) {{ setTimeZone(TimeZone.getTimeZone("UTC")); }}.format(new Date());
        byte[] nonce = new byte[16]; new SecureRandom().nextBytes(nonce);
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(nonce);
        sha1.update(created.getBytes(StandardCharsets.UTF_8));
        byte[] digest = sha1.digest((password == null ? "" : password).getBytes(StandardCharsets.UTF_8));
        return "<wsse:Security s:mustUnderstand=\"1\" xmlns:s=\"" + SOAP_NS + "\" xmlns:wsse=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\" xmlns:wsu=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\"><wsse:UsernameToken><wsse:Username>" + xml(user) + "</wsse:Username><wsse:Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">" + Base64.getEncoder().encodeToString(digest) + "</wsse:Password><wsse:Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary\">" + Base64.getEncoder().encodeToString(nonce) + "</wsse:Nonce><wsu:Created>" + created + "</wsu:Created></wsse:UsernameToken></wsse:Security>";
    }

    private static String tag(String value, String name) {
        if (value == null) return "";
        Matcher m = Pattern.compile("<[^>]*" + Pattern.quote(name) + "[^>]*>([\\s\\S]*?)</[^>]*" + Pattern.quote(name) + "\\s*>", Pattern.CASE_INSENSITIVE).matcher(value);
        return m.find() ? m.group(1).replace("<![CDATA[", "").replace("]]>", "").trim() : "";
    }

    private static String block(String value, String name) {
        Matcher m = Pattern.compile("<[^>]*" + Pattern.quote(name) + "\\b[^>]*>([\\s\\S]*?)</[^>]*" + Pattern.quote(name) + "\\s*>", Pattern.CASE_INSENSITIVE).matcher(value);
        return m.find() ? m.group(1) : "";
    }

    private static String unescape(String v) { return v.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'"); }
    private static String xml(String v) { return (v == null ? "" : v).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;"); }

    private static boolean privateIpv4(String host) {
        if (host == null) return false;
        String[] p = host.split("\\.");
        if (p.length != 4) return false;
        try {
            int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
            return (a == 10) || (a == 192 && b == 168) || (a == 172 && b >= 16 && b <= 31);
        } catch (NumberFormatException e) { return false; }
    }
}
