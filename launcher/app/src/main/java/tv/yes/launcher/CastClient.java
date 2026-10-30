package tv.yes.launcher;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * What the Google Cast devices on the network (Google Home, Nest, TVs) are
 * playing, and Play / Pause for it. Local only: mDNS discovery
 * (_googlecast._tcp) and the Cast V2 protocol on port 8009 (TLS with the
 * device's self-signed certificate, length-prefixed protobuf CastMessages with
 * JSON payloads). Runs only while Home is visible: devices are asked once when
 * Home shows and then every 30 s, never in the background. This box itself is
 * skipped.
 */
final class CastClient {

    /** One device's media session. */
    static final class Playing {
        String deviceName, host, transportId;
        int port, mediaSessionId;
        String title, by = "";
        boolean paused;

        boolean sameAs(Playing o) {
            return o != null && Objects.equals(host, o.host) && Objects.equals(title, o.title)
                    && Objects.equals(by, o.by) && paused == o.paused;
        }
    }

    interface Listener {
        /** Main thread; null when nothing is playing anywhere. */
        void onCastChanged(Playing p);
    }

    private static final String TAG = "CastClient";
    private static final String SERVICE = "_googlecast._tcp";
    private static final long CHECK_MS = 30000L;
    private static final String NS_CONN = "urn:x-cast:com.google.cast.tp.connection";
    private static final String NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat";
    private static final String NS_RECEIVER = "urn:x-cast:com.google.cast.receiver";
    private static final String NS_MEDIA = "urn:x-cast:com.google.cast.media";

    private static final class Device {
        String name, host;
        int port;
    }

    private final NsdManager nsd;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Map<String, Device> devices = Collections.synchronizedMap(new LinkedHashMap<>());
    private final ArrayDeque<NsdServiceInfo> toResolve = new ArrayDeque<>();
    private boolean resolving;
    private Listener listener;
    private Playing current;
    private NsdManager.DiscoveryListener discovery;

    private final Runnable check = new Runnable() {
        @Override public void run() {
            checkNow();
            main.postDelayed(this, CHECK_MS);
        }
    };

    CastClient(Context c) {
        nsd = c.getApplicationContext().getSystemService(NsdManager.class);
    }

    void start(Listener l) {
        if (listener != null) return;
        listener = l;
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onServiceFound(NsdServiceInfo info) {
                main.post(() -> queueResolve(info));
            }

            @Override public void onServiceLost(NsdServiceInfo info) { }
            @Override public void onDiscoveryStarted(String type) { }
            @Override public void onDiscoveryStopped(String type) { }

            @Override public void onStartDiscoveryFailed(String type, int code) {
                Log.w(TAG, "discovery failed: " + code);
            }

            @Override public void onStopDiscoveryFailed(String type, int code) { }
        };
        try {
            nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (RuntimeException e) {
            Log.w(TAG, "discovery unavailable: " + e.getMessage());
            discovery = null;
        }
        main.post(check);
    }

    void stop() {
        listener = null;
        main.removeCallbacks(check);
        toResolve.clear();
        if (discovery != null) {
            try {
                nsd.stopServiceDiscovery(discovery);
            } catch (RuntimeException ignore) { }
            discovery = null;
        }
    }

    /** NsdManager resolves one service at a time before Android 14: queue them. */
    private void queueResolve(NsdServiceInfo info) {
        toResolve.add(info);
        resolveNext();
    }

    @SuppressWarnings("deprecation")
    private void resolveNext() {
        if (resolving || toResolve.isEmpty() || listener == null) return;
        resolving = true;
        nsd.resolveService(toResolve.poll(), new NsdManager.ResolveListener() {
            @Override public void onServiceResolved(NsdServiceInfo info) {
                Device d = new Device();
                d.host = info.getHost().getHostAddress();
                d.port = info.getPort();
                byte[] fn = info.getAttributes().get("fn");
                d.name = fn != null ? new String(fn, StandardCharsets.UTF_8) : info.getServiceName();
                boolean isNew = devices.put(d.host, d) == null;
                main.post(() -> {
                    resolving = false;
                    if (isNew && listener != null) {
                        main.removeCallbacks(check);
                        main.postDelayed(check, 1000L);   // one check for a burst of new devices
                    }
                    resolveNext();
                });
            }

            @Override public void onResolveFailed(NsdServiceInfo info, int code) {
                main.post(() -> {
                    resolving = false;
                    resolveNext();
                });
            }
        });
    }

    private void checkNow() {
        final List<Device> list;
        synchronized (devices) {
            list = new ArrayList<>(devices.values());
        }
        if (list.isEmpty()) return;
        exec.execute(() -> {
            Set<String> self = ownAddresses();
            Playing playing = null, paused = null;
            int asked = 0;
            for (Device d : list) {
                if (self.contains(d.host)) continue;
                asked++;
                Playing p = status(d);
                if (p == null) continue;
                if (!p.paused) {
                    playing = p;
                    break;
                }
                if (paused == null) paused = p;
            }
            Playing shown = playing != null ? playing : paused;
            Log.d(TAG, "asked " + asked + " of " + list.size() + " devices: "
                    + (shown == null ? "nothing playing" : (shown.paused ? "paused on " : "playing on ") + shown.deviceName));
            publish(shown);
        });
    }

    /** Pause when playing, play when paused; then shows the device's new state. */
    void toggle(Playing p) {
        final Playing target = p;
        exec.execute(() -> {
            try (Conn c = new Conn(target.host, target.port)) {
                c.send(target.transportId, NS_CONN, new JSONObject().put("type", "CONNECT"));
                int id = c.nextId();
                c.send(target.transportId, NS_MEDIA, new JSONObject()
                        .put("type", target.paused ? "PLAY" : "PAUSE")
                        .put("requestId", id)
                        .put("mediaSessionId", target.mediaSessionId));
                JSONObject r = c.await(NS_MEDIA, id);
                Playing next = parseMedia(target, r);
                publish(next);
            } catch (IOException | JSONException e) {
                Log.w(TAG, "toggle failed: " + e.getMessage());
            }
        });
    }

    private void publish(Playing p) {
        main.post(() -> {
            if (listener == null) return;
            if (p == null ? current == null : p.sameAs(current)) return;
            current = p;
            listener.onCastChanged(p);
        });
    }

    /** The device's media session when an app with media is running on it, or null. */
    private Playing status(Device d) {
        try (Conn c = new Conn(d.host, d.port)) {
            c.send("receiver-0", NS_CONN, new JSONObject().put("type", "CONNECT"));
            int id = c.nextId();
            c.send("receiver-0", NS_RECEIVER, new JSONObject().put("type", "GET_STATUS").put("requestId", id));
            JSONObject r = c.await(NS_RECEIVER, id);
            JSONObject status = r.optJSONObject("status");
            JSONArray apps = status == null ? null : status.optJSONArray("applications");
            String transportId = null, appName = null;
            for (int i = 0; apps != null && i < apps.length(); i++) {
                JSONObject a = apps.optJSONObject(i);
                if (a == null || a.optBoolean("isIdleScreen", false) || !hasMedia(a)) continue;
                transportId = a.optString("transportId", null);
                appName = a.optString("displayName", null);
                break;
            }
            if (transportId == null) return null;
            c.send(transportId, NS_CONN, new JSONObject().put("type", "CONNECT"));
            id = c.nextId();
            c.send(transportId, NS_MEDIA, new JSONObject().put("type", "GET_STATUS").put("requestId", id));
            Playing base = new Playing();
            base.deviceName = d.name;
            base.host = d.host;
            base.port = d.port;
            base.transportId = transportId;
            base.title = appName;
            return parseMedia(base, c.await(NS_MEDIA, id));
        } catch (IOException | JSONException e) {
            Log.d(TAG, d.name + ": " + e.getMessage());
            return null;
        }
    }

    private static boolean hasMedia(JSONObject app) {
        JSONArray ns = app.optJSONArray("namespaces");
        for (int i = 0; ns != null && i < ns.length(); i++) {
            JSONObject n = ns.optJSONObject(i);
            if (n != null && NS_MEDIA.equals(n.optString("name"))) return true;
        }
        return false;
    }

    /** MEDIA_STATUS -> the session, or null when idle. Keeps base's title when the status has no metadata. */
    private static Playing parseMedia(Playing base, JSONObject r) {
        JSONArray st = r.optJSONArray("status");
        JSONObject s = st == null || st.length() == 0 ? null : st.optJSONObject(0);
        if (s == null) return null;
        String state = s.optString("playerState");
        if (!"PLAYING".equals(state) && !"PAUSED".equals(state) && !"BUFFERING".equals(state)) return null;
        Playing p = new Playing();
        p.deviceName = base.deviceName;
        p.host = base.host;
        p.port = base.port;
        p.transportId = base.transportId;
        p.mediaSessionId = s.optInt("mediaSessionId", base.mediaSessionId);
        p.paused = "PAUSED".equals(state);
        p.title = base.title;
        JSONObject media = s.optJSONObject("media");
        JSONObject md = media == null ? null : media.optJSONObject("metadata");
        if (md != null) {
            String title = md.optString("title", "");
            if (!title.isEmpty()) {
                p.title = title;
                p.by = md.optString("artist", md.optString("subtitle", ""));
            }
        }
        if (p.title == null || p.title.isEmpty()) p.title = p.deviceName;
        return p;
    }

    private static Set<String> ownAddresses() {
        Set<String> out = new HashSet<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) out.add(a.getHostAddress());
            }
        } catch (Exception ignore) { }
        return out;
    }

    // ---------- Cast V2 wire format -------------------------------------

    /** One short-lived connection: requests go out as sender-0, PINGs are answered. */
    private static final class Conn implements Closeable {
        private final SSLSocket socket;
        private final DataInputStream in;
        private final OutputStream out;
        private int requestId;

        Conn(String host, int port) throws IOException {
            socket = (SSLSocket) trustAll().getSocketFactory().createSocket();
            socket.connect(new InetSocketAddress(host, port), 2000);
            socket.setSoTimeout(3000);
            socket.startHandshake();
            in = new DataInputStream(socket.getInputStream());
            out = socket.getOutputStream();
        }

        int nextId() {
            return ++requestId;
        }

        void send(String destination, String namespace, JSONObject payload) throws IOException {
            byte[] msg = encode("sender-0", destination, namespace, payload.toString());
            byte[] len = {(byte) (msg.length >>> 24), (byte) (msg.length >>> 16), (byte) (msg.length >>> 8), (byte) msg.length};
            out.write(len);
            out.write(msg);
            out.flush();
        }

        /** Reads until the reply on namespace with this requestId (3 s at most). */
        JSONObject await(String namespace, int id) throws IOException, JSONException {
            long deadline = System.currentTimeMillis() + 3000L;
            while (System.currentTimeMillis() < deadline) {
                String[] m = read();   // {source, namespace, payload}
                if (m[2] == null) continue;
                JSONObject o = new JSONObject(m[2]);
                if (NS_HEARTBEAT.equals(m[1]) && "PING".equals(o.optString("type"))) {
                    send(m[0], NS_HEARTBEAT, new JSONObject().put("type", "PONG"));
                } else if (namespace.equals(m[1]) && o.optInt("requestId", -1) == id) {
                    return o;
                }
            }
            throw new IOException("no reply on " + namespace);
        }

        private String[] read() throws IOException {
            int len = in.readInt();
            if (len < 0 || len > 1 << 20) throw new IOException("bad frame " + len);
            byte[] buf = new byte[len];
            in.readFully(buf);
            String[] m = new String[3];
            int[] pos = {0};
            while (pos[0] < len) {
                long key = varint(buf, pos);
                int field = (int) (key >>> 3), wire = (int) (key & 7);
                if (wire == 0) {
                    varint(buf, pos);
                } else if (wire == 2) {
                    int n = (int) varint(buf, pos);
                    String s = field == 2 || field == 4 || field == 6
                            ? new String(buf, pos[0], n, StandardCharsets.UTF_8) : null;
                    if (field == 2) m[0] = s;
                    else if (field == 4) m[1] = s;
                    else if (field == 6) m[2] = s;
                    pos[0] += n;
                } else if (wire == 1) {
                    pos[0] += 8;
                } else if (wire == 5) {
                    pos[0] += 4;
                } else {
                    throw new IOException("bad wire type " + wire);
                }
            }
            return m;
        }

        @Override public void close() throws IOException {
            socket.close();
        }
    }

    /** CastMessage: protocol_version=0, source_id, destination_id, namespace, payload_type=STRING, payload_utf8. */
    private static byte[] encode(String source, String destination, String namespace, String payload) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x08);
        b.write(0);
        string(b, 2, source);
        string(b, 3, destination);
        string(b, 4, namespace);
        b.write(0x28);
        b.write(0);
        string(b, 6, payload);
        return b.toByteArray();
    }

    private static void string(ByteArrayOutputStream b, int field, String s) {
        byte[] v = s.getBytes(StandardCharsets.UTF_8);
        b.write(field << 3 | 2);
        int n = v.length;
        while (n >= 0x80) {
            b.write((n & 0x7F) | 0x80);
            n >>>= 7;
        }
        b.write(n);
        b.write(v, 0, v.length);
    }

    private static long varint(byte[] buf, int[] pos) throws IOException {
        long v = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (pos[0] >= buf.length) throw new IOException("truncated");
            int b = buf[pos[0]++];
            v |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return v;
        }
        throw new IOException("bad varint");
    }

    /** Cast devices present a self-signed certificate; this only ever talks to them, on the LAN. */
    private static SSLContext trustAll() throws IOException {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String type) { }
                @Override public void checkServerTrusted(X509Certificate[] chain, String type) { }
                @Override public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, null);
            return ctx;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
}
