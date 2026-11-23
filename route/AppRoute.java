// AppRoute: lightweight AudioPolicy router with zero-CPU standby sleep, version check & loopback control channel.
// - Normal playback: routes non-music apps to the built-in speaker (excluding voice comm & ringtone usages),
//   leaving music apps (e.g. QQ Music) 100% untouched on native Bluetooth A2DP (zero quality loss).
// - VoIP calls (WeChat voice/video, MODE_IN_COMMUNICATION): dynamically locks music apps onto the whitelisted
//   Bluetooth speaker and prevents calls from routing to that speaker.
// - Cellular phone calls (RINGTONE / IN_CALL): drops split & pauses playing music within 0.25 s; auto-resumes on hang-up.
// - Standby mode ("pause" or SPLITAUDIO_STANDBY=1): unregisters policy and blocks on PAUSE_LOCK.wait() (0% CPU).
import android.media.*;
import android.os.Looper;
import java.lang.reflect.*;

public class AppRoute {
    static final Object POLICY_LOCK = new Object();
    static final Object PAUSE_LOCK = new Object();
    static Object policy = null;
    static int regBtId = -1;
    static boolean regLockMusic = false;
    static boolean pausedForCall = false;
    static volatile boolean paused = "1".equals(System.getenv("SPLITAUDIO_STANDBY"));
    static volatile boolean shutdownRequested = false;
    static volatile long lastBt = 0L;
    static int[] musicUids = new int[0];
    static int[] uids = new int[0];
    static String runningVer = "";

    static final String BT_FILTER_FILE = "/data/local/tmp/approute.btfilter";
    static final String IDLE_FILE = "/data/local/tmp/approute.idle";
    static final String VER_FILE = "/data/local/tmp/approute.ver";
    static final int DEFAULT_IDLE_MINUTES = 2;

    /** One whitelisted Bluetooth speaker entry: MAC (uppercase, or "" if unavailable) and/or product name. */
    static final class BtEntry {
        final String mac;
        final String name;
        BtEntry(String mac, String name) { this.mac = mac; this.name = name; }
    }

    static String normalizeMac(String raw) {
        if (raw == null) return "";
        String m = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (m.isEmpty() || m.indexOf(':') < 0 || m.equals("02:00:00:00:00:00") || m.equals("00:00:00:00:00:00")) return "";
        return m;
    }

    static String normalizeName(String raw) {
        return raw == null ? "" : raw.trim();
    }

    static boolean isBtOutputType(int type) {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == 26 /* BLE_HEADSET */ || type == 27 /* BLE_SPEAKER */;
    }

    static boolean isBtCommType(int type) {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || isBtOutputType(type);
    }

    /** Pure: traditional cellular/ringtone phone calls (drop split & pause music; resume after hang-up). */
    static boolean isPhoneCallMode(int mode) {
        return mode == 1 /* MODE_RINGTONE */ || mode == 2 /* MODE_IN_CALL */
            || mode == 4 /* MODE_CALL_SCREENING */ || mode == 5 /* MODE_CALL_REDIRECT */;
    }

    /** Pure: VoIP voice/video calls (e.g. WeChat): keep split active and do not pause music. */
    static boolean isVoipMode(int mode) {
        return mode == 3 /* MODE_IN_COMMUNICATION */ || mode == 6 /* MODE_COMMUNICATION_REDIRECT */;
    }

    /** Pure: parse lines "MAC|Name" into BtEntry list. Empty list means no restriction (all BT outputs match). */
    static java.util.List<BtEntry> parseBtFilter(java.util.List<String> lines) {
        java.util.ArrayList<BtEntry> out = new java.util.ArrayList<>();
        if (lines == null) return out;
        for (String line : lines) {
            if (line == null) continue;
            String s = line.trim();
            if (s.isEmpty()) continue;
            int sep = s.indexOf('|');
            String mac = normalizeMac(sep >= 0 ? s.substring(0, sep) : s);
            String name = normalizeName(sep >= 0 ? s.substring(sep + 1) : "");
            if (!mac.isEmpty() || !name.isEmpty()) out.add(new BtEntry(mac, name));
        }
        return out;
    }

    static java.util.List<BtEntry> readBtFilter(java.io.File f) {
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        if (f == null || !f.isFile()) return new java.util.ArrayList<>();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            for (String l; (l = r.readLine()) != null; ) lines.add(l);
        } catch (Throwable e) { log("btfilter read fail " + e); }
        return parseBtFilter(lines);
    }

    /** Pure: does a Bluetooth output device (mac + product name) match the whitelist? Empty whitelist matches everything. */
    static boolean matchesBtFilter(java.util.List<BtEntry> filter, String devMac, String devName) {
        if (filter == null || filter.isEmpty()) return true;
        String m = normalizeMac(devMac);
        String n = normalizeName(devName);
        for (BtEntry e : filter) {
            if (!e.mac.isEmpty() && !m.isEmpty()) {
                if (e.mac.equalsIgnoreCase(m)) return true;
            } else if (!e.name.isEmpty() && !n.isEmpty()) {
                if (e.name.equalsIgnoreCase(n)) return true;
            }
        }
        return false;
    }

    /** Pure: read first trimmed line from a small text file, or "" when missing/empty. */
    static String readFirstLine(java.io.File f) {
        if (f == null || !f.isFile()) return "";
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            String l = r.readLine();
            return l == null ? "" : l.trim();
        } catch (Throwable e) { return ""; }
    }

    /** Pure: parse auto-pause idle minutes from file (0 = disabled, default 2, clamped to 0..1440). */
    static int readIdleMinutes(java.io.File f) {
        String l = readFirstLine(f);
        if (l.isEmpty()) return DEFAULT_IDLE_MINUTES;
        try {
            int v = Integer.parseInt(l);
            return Math.max(0, Math.min(1440, v));
        } catch (Throwable e) { return DEFAULT_IDLE_MINUTES; }
    }

    /** Pure: should the router enter standby sleep after the target Bluetooth device has been gone for idleMinutes? (0 = never) */
    static boolean shouldExitOnIdle(int idleMinutes, long now, long lastSeenBt) {
        return idleMinutes > 0 && now >= lastSeenBt && (now - lastSeenBt) >= idleMinutes * 60_000L;
    }

    static boolean writeTextAtomically(String path, byte[] data) {
        java.io.File tmp = new java.io.File(path + ".new");
        java.io.File dst = new java.io.File(path);
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) {
            fos.write(data);
            fos.flush();
        } catch (Throwable e) { log("write fail " + path + ": " + e); return false; }
        tmp.setReadable(true, false);
        if (!tmp.renameTo(dst)) {
            tmp.delete();
            return false;
        }
        return true;
    }

    /** Clean up legacy multi-focus / mixer flag files left by older versions so HyperOS Sound Assistant handles multi-audio natively. */
    static void cleanupLegacyFlags() {
        java.io.File mfOk = new java.io.File("/data/local/tmp/approute.mf_ok");
        if (mfOk.exists()) {
            try {
                Object as = audioService();
                as.getClass().getMethod("setMultiAudioFocusEnabled", boolean.class).invoke(as, false);
            } catch (Throwable ignored) {}
            sh("settings put system multi_audio_focus_enabled 0");
            log("restored multi_audio_focus_enabled=0 for native Sound Assistant");
        }
        String[] legacy = {
            "/data/local/tmp/approute.mf_ok",
            "/data/local/tmp/approute.nomf",
            "/data/local/tmp/approute.nomickeep",
            "/data/local/tmp/approute.vol",
            "/data/local/tmp/approute.volpkg"
        };
        for (String p : legacy) {
            try { new java.io.File(p).delete(); } catch (Throwable ignored) {}
        }
    }

    public static void main(String[] a) throws Exception {
        if (a.length > 1 && a[0].equals("ask")) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < a.length; i++) sb.append(i > 1 ? " " : "").append(a[i]);
            askMain(sb.toString());
            return;
        }
        Looper.prepareMainLooper();
        runningVer = readFirstLine(new java.io.File(VER_FILE));
        cleanupLegacyFlags();
        java.util.ArrayList<Integer> mList = new java.util.ArrayList<>();
        java.util.HashSet<Integer> music = new java.util.HashSet<>();
        for (String m : a[0].split(",")) {
            String s = m.trim();
            if (!s.isEmpty()) {
                int u = Integer.parseInt(s);
                if (music.add(u)) mList.add(u);
            }
        }
        musicUids = new int[mList.size()];
        for (int i = 0; i < mList.size(); i++) musicUids[i] = mList.get(i);
        uids = new int[a.length - 1];
        for (int i = 1; i < a.length; i++) uids[i - 1] = Integer.parseInt(a[i]);
        log("start ver=" + (runningVer.isEmpty() ? "?" : runningVer.substring(0, Math.min(8, runningVer.length())))
            + " music=" + music + " routed=" + uids.length + (paused ? " standby=1" : ""));
        startServers();

        java.io.File btFile = new java.io.File(BT_FILTER_FILE);
        java.io.File idleFile = new java.io.File(IDLE_FILE);
        long btMtime = Long.MIN_VALUE, idleMtime = Long.MIN_VALUE;
        java.util.List<BtEntry> btFilter = new java.util.ArrayList<>();
        int idleMin = DEFAULT_IDLE_MINUTES;

        lastBt = System.currentTimeMillis();
        while (!shutdownRequested) {
            // Standby mode: block with 0% CPU until resumed or shut down
            if (paused) {
                unregister();
                synchronized (PAUSE_LOCK) {
                    while (paused && !shutdownRequested) {
                        try { PAUSE_LOCK.wait(); } catch (InterruptedException ignored) {}
                    }
                }
                if (shutdownRequested) break;
                lastBt = System.currentTimeMillis();
                log("resumed from standby");
            }

            long now = System.currentTimeMillis();
            long curBtMtime = btFile.isFile() ? btFile.lastModified() : -1L;
            if (curBtMtime != btMtime) {
                btMtime = curBtMtime;
                btFilter = readBtFilter(btFile);
                log("btfilter entries=" + btFilter.size());
            }
            long curIdleMtime = idleFile.isFile() ? idleFile.lastModified() : -1L;
            if (curIdleMtime != idleMtime) {
                idleMtime = curIdleMtime;
                idleMin = readIdleMinutes(idleFile);
                log("idle minutes=" + idleMin);
            }

            AudioDeviceInfo[] allOut = outputs();
            AudioDeviceInfo spk = null;
            for (AudioDeviceInfo d : allOut) {
                if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) { spk = d; break; }
            }
            AudioDeviceInfo targetBt = findTargetBtDevice(allOut, btFilter);
            boolean bt = targetBt != null;
            if (bt) lastBt = now;

            int mode = audioMode();
            boolean phoneCall = isPhoneCallMode(mode);
            boolean voip = isVoipMode(mode);

            // Prevent calls / WeChat voice-video from routing to a whitelisted Bluetooth speaker
            if ((phoneCall || voip) && !btFilter.isEmpty()) {
                avoidBtCommDevice(btFilter, allOut);
            }

            // Cellular phone calls: drop split & pause playing music; auto-resume after hang-up
            if (phoneCall && !pausedForCall && bt && musicPlaying(music)) {
                sh("cmd media_session dispatch pause"); pausedForCall = true; log("phone call -> music paused");
            }
            if (!phoneCall && pausedForCall) {
                pausedForCall = false;
                if (!musicPlaying(music)) { sh("cmd media_session dispatch play"); log("phone call over -> music resumed"); }
            }

            // Split policy:
            // - Normal playback (!voip): route only non-music apps to built-in speaker, leaving music apps 100% on native BT A2DP.
            // - VoIP (WeChat voice/video): dynamically add Group 1 to lock music apps onto targetBt so earpiece mode doesn't pull music.
            // - Cellular phone calls: drop split & pause music; resume after hang-up.
            boolean want = bt && spk != null && !phoneCall;
            if (want) {
                boolean needReg;
                synchronized (POLICY_LOCK) { needReg = policy == null || regBtId != targetBt.getId() || regLockMusic != voip; }
                if (needReg) {
                    if (policy != null) unregister();
                    try {
                        registerBidirectional(spk, targetBt, voip);
                        log("target bt active (id=" + targetBt.getId() + ", voip=" + voip + ") -> rule on");
                    } catch (Throwable e) {
                        log("register fail " + e);
                        synchronized (POLICY_LOCK) { policy = null; regBtId = -1; regLockMusic = false; }
                    }
                }
            }
            if (!want && policy != null) {
                unregister();
                log((phoneCall ? "phone call" : !bt ? "target bt off" : "no speaker") + " -> rule off");
            }

            // Idle timeout: transition into zero-CPU standby sleep (keep process ready for instant no-Wi-Fi resume)
            if (shouldExitOnIdle(idleMin, now, lastBt)) {
                unregister();
                paused = true;
                log("target bt gone " + idleMin + "min -> standby sleep");
                continue;
            }
            waitModeChange(2000, phoneCall, voip);
        }
        unregister();
        log("shutdown -> exit");
        System.exit(0);
    }

    /** Lightweight wait loop: wakes up within 250 ms on pause/shutdown or call/VoIP mode change, zero focus-stack polling. */
    static void waitModeChange(long ms, boolean wasPhoneCall, boolean wasVoip) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        do {
            if (paused || shutdownRequested) return;
            int curMode = audioMode();
            if (isPhoneCallMode(curMode) != wasPhoneCall || isVoipMode(curMode) != wasVoip) return;
            Thread.sleep(250);
        } while (System.currentTimeMillis() < end);
    }

    /** Find the active/connected whitelisted Bluetooth speaker AudioDeviceInfo, or null if none matches. */
    static boolean activeQueryFailedLogged = false;
    static AudioDeviceInfo findTargetBtDevice(AudioDeviceInfo[] allOut, java.util.List<BtEntry> filter) {
        try {
            Object as = audioService();
            AudioAttributes media = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build();
            java.util.List<?> active = null;
            for (Method m : as.getClass().getMethods()) {
                if (!m.getName().equals("getDevicesForAttributes")) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 1 && pt[0] == AudioAttributes.class) {
                    active = (java.util.List<?>) m.invoke(as, media);
                    break;
                }
                if (pt.length == 2 && pt[0] == AudioAttributes.class && pt[1] == boolean.class) {
                    active = (java.util.List<?>) m.invoke(as, media, Boolean.FALSE);
                    break;
                }
            }
            if (active != null && !active.isEmpty()) {
                for (Object attr : active) {
                    int type = (Integer) attr.getClass().getMethod("getType").invoke(attr);
                    if (!isBtOutputType(type)) continue;
                    Object rawAddr = attr.getClass().getMethod("getAddress").invoke(attr);
                    String addr = rawAddr == null ? "" : rawAddr.toString();
                    AudioDeviceInfo info = findDeviceInfo(allOut, type, addr);
                    String name = info != null && info.getProductName() != null ? info.getProductName().toString() : "";
                    if (matchesBtFilter(filter, addr, name)) return info;
                }
                if (!isVoipMode(audioMode())) return null;
            }
        } catch (Throwable e) {
            if (!activeQueryFailedLogged) { activeQueryFailedLogged = true; log("active media query fallback: " + e); }
        }
        for (AudioDeviceInfo d : allOut) {
            if (!isBtOutputType(d.getType())) continue;
            String addr = "";
            try { addr = d.getAddress(); } catch (Throwable ignored) {}
            String name = d.getProductName() == null ? "" : d.getProductName().toString();
            if (matchesBtFilter(filter, addr, name)) return d;
        }
        return null;
    }

    static AudioDeviceInfo findDeviceInfo(AudioDeviceInfo[] allOut, int type, String addr) {
        String norm = normalizeMac(addr);
        AudioDeviceInfo fallback = null;
        for (AudioDeviceInfo d : allOut) {
            if (d.getType() != type) continue;
            String da = "";
            try { da = normalizeMac(d.getAddress()); } catch (Throwable ignored) {}
            if (!norm.isEmpty() && norm.equalsIgnoreCase(da)) return d;
            if (fallback == null) fallback = d;
        }
        return fallback;
    }

    /** If the system routed voice communication to a whitelisted Bluetooth speaker, clear it back to the phone. */
    static boolean commClearErrLogged = false;
    static void avoidBtCommDevice(java.util.List<BtEntry> btFilter, AudioDeviceInfo[] allOut) {
        try {
            Object as = audioService();
            Object comm = null;
            for (Method m : as.getClass().getMethods()) {
                if (m.getName().equals("getCommunicationDevice") && m.getParameterTypes().length == 0) {
                    comm = m.invoke(as);
                    break;
                }
            }
            if (comm == null) return;
            int type = (Integer) comm.getClass().getMethod("getType").invoke(comm);
            if (!isBtCommType(type)) return;
            Object rawAddr = comm.getClass().getMethod("getAddress").invoke(comm);
            String addr = rawAddr == null ? "" : rawAddr.toString();
            AudioDeviceInfo info = findDeviceInfo(allOut, type, addr);
            String name = info != null && info.getProductName() != null ? info.getProductName().toString() : "";
            if (!matchesBtFilter(btFilter, addr, name)) return;
            for (Method m : as.getClass().getMethods()) {
                if (m.getName().equals("clearCommunicationDevice") && m.getParameterTypes().length == 0) {
                    m.invoke(as);
                    log("cleared whitelisted bt communication device");
                    return;
                }
            }
        } catch (Throwable e) {
            if (!commClearErrLogged) { commClearErrLogged = true; log("comm device guard err " + e); }
        }
    }

    static java.util.List<?> playbackConfigs() throws Exception {
        Object as = audioService();
        return (java.util.List<?>) as.getClass().getMethod("getActivePlaybackConfigurations").invoke(as);
    }

    static boolean musicPlaying(java.util.Set<Integer> music) {
        try {
            java.util.List<?> l = playbackConfigs();
            for (Object c : l) {
                int uid = (Integer) c.getClass().getMethod("getClientUid").invoke(c);
                int st = (Integer) c.getClass().getMethod("getPlayerState").invoke(c);
                if (st == 2 /* STARTED */ && (music == null || music.contains(uid))) return true;
            }
        } catch (Throwable e) { log("musicPlaying err " + e); }
        return false;
    }

    static String sh(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            String out = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream())).readLine();
            p.waitFor();
            return out == null ? "" : out.trim();
        } catch (Throwable e) { return ""; }
    }

    // Control channel (127.0.0.1:47821, token + /proc/net/tcp* peer uid check)
    static final int VOL_PORT = 47821;
    static final String TOKEN_FILE = "/data/local/tmp/approute.token";
    static final int OWNER_UID = parseUid(System.getenv("SPLITAUDIO_UID"));
    static final int UID_UNKNOWN = -1, UID_AMBIGUOUS = -2;
    static final java.util.regex.Pattern WHITESPACE = java.util.regex.Pattern.compile("\\s+");
    static final int MAX_CONN = 8;
    static final int MAX_LINE = 4096;
    static String controlToken = null;
    static String lastDenied = "";
    static volatile boolean uidEnforced = false;
    static final Object CMD_LOCK = new Object();
    static final java.util.concurrent.atomic.AtomicInteger activeConn = new java.util.concurrent.atomic.AtomicInteger();

    static int parseUid(String s) {
        try { return s == null ? -1 : Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return -1; }
    }
    static boolean allowed(int peerUid, int ownerUid) { return peerUid == 0 || peerUid == 2000 || (ownerUid >= 0 && peerUid == ownerUid); }
    static int percent(int idx, int max) { return max <= 0 ? 0 : Math.max(0, Math.min(100, Math.round(idx * 100f / max))); }
    static int idxForPercent(int pct, int min, int max) {
        int p = Math.max(0, Math.min(100, pct));
        if (max <= min) return min;
        return Math.max(min, Math.min(max, min + Math.round((max - min) * p / 100f)));
    }

    // ---- check 1: the token ----
    static String validToken(String t) {
        if (t == null) return null;
        t = t.trim();
        if (t.length() < 16 || t.length() > 128) return null;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) return null;
        }
        return t;
    }

    static String authToken(String line) {
        if (line == null) return null;
        String l = line.trim();
        if (!l.startsWith("auth ")) return null;
        String t = l.substring(5);
        if (t.isEmpty()) return null;
        for (int i = 0; i < t.length(); i++) if (Character.isWhitespace(t.charAt(i))) return null;
        return t;
    }

    static boolean tokenMatches(String presented, String expected) {
        if (presented == null || expected == null || expected.isEmpty()) return false;
        return java.security.MessageDigest.isEqual(presented.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                                   expected.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static String readTokenFile(java.io.File f) {
        String l = readFirstLine(f);
        return l.isEmpty() ? null : l;
    }

    static String loadToken() {
        String t = validToken(System.getenv("SPLITAUDIO_TOKEN"));
        return t != null ? t : validToken(readTokenFile(new java.io.File(TOKEN_FILE)));
    }

    // ---- check 2: the uid on the other end ----
    static java.util.List<String> readLines(String path, int port) {
        java.util.ArrayList<String> l = new java.util.ArrayList<>();
        String up = String.format(java.util.Locale.ROOT, ":%04X", port), lo = up.toLowerCase(java.util.Locale.ROOT);
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path))) {
            for (String x; (x = r.readLine()) != null; ) if (x.contains(up) || x.contains(lo)) l.add(x);
        } catch (Throwable e) {}
        return l;
    }

    static int hexPort(String addr) {
        int i = addr.lastIndexOf(':');
        if (i < 0) return -1;
        try { return Integer.parseInt(addr.substring(i + 1), 16); } catch (NumberFormatException e) { return -1; }
    }

    static boolean loopback(String addr) {
        int i = addr.lastIndexOf(':');
        if (i < 0) return false;
        String ip = addr.substring(0, i).toUpperCase(java.util.Locale.ROOT);
        if (ip.length() == 8) return ip.endsWith("7F");
        if (ip.length() == 32) return ip.equals("00000000000000000000000001000000")
            || (ip.startsWith("0000000000000000FFFF0000") && ip.endsWith("7F"));
        return false;
    }

    static int uidOfConnection(java.util.List<String> lines, int clientPort, int serverPort) {
        int found = UID_UNKNOWN;
        for (String line : lines) {
            String[] f = WHITESPACE.split(line.trim());
            if (f.length < 8 || !f[0].endsWith(":")) continue;
            if (f[3].equals("06")) continue;
            if (hexPort(f[1]) != clientPort || hexPort(f[2]) != serverPort) continue;
            if (!loopback(f[1]) || !loopback(f[2])) continue;
            long uid;
            try { uid = Long.parseLong(f[7]); } catch (NumberFormatException e) { continue; }
            if (uid < 0 || uid > Integer.MAX_VALUE) continue;
            if (found == UID_UNKNOWN) found = (int) uid;
            else if (found != uid) return UID_AMBIGUOUS;
        }
        return found;
    }

    static String uidProblem(int peer, int owner, boolean enforced) {
        if (peer == UID_UNKNOWN) return enforced ? "peer not found in /proc/net/tcp*" : null;
        if (peer == UID_AMBIGUOUS) return "peer uid ambiguous";
        return allowed(peer, owner) ? null : "uid " + peer;
    }

    static int peerUid(java.net.Socket s) {
        int client = s.getPort(), server = s.getLocalPort();
        int uid = uidOfConnection(readLines("/proc/net/tcp6", client), client, server);
        return uid != UID_UNKNOWN ? uid : uidOfConnection(readLines("/proc/net/tcp", client), client, server);
    }

    // ---- the server ----
    static boolean selfTestUidLookup(java.net.ServerSocket ss) {
        for (int i = 0; i < 3; i++) {
            try (java.net.Socket c = new java.net.Socket()) {
                c.connect(new java.net.InetSocketAddress("127.0.0.1", ss.getLocalPort()), 1000);
                ss.setSoTimeout(1000);
                try (java.net.Socket a = ss.accept()) {
                    if (a.getPort() == c.getLocalPort()) return peerUid(a) >= 0;
                }
            } catch (Throwable e) { return false; }
            finally { try { ss.setSoTimeout(0); } catch (Throwable e) {} }
        }
        return false;
    }

    static void startServers() {
        controlToken = loadToken();
        if (controlToken == null) { log("control port closed: no usable SPLITAUDIO_TOKEN in the environment or in " + TOKEN_FILE); return; }
        Thread vt = new Thread(AppRoute::volServer); vt.setDaemon(true); vt.start();
    }

    static void volServer() {
        try (java.net.ServerSocket ss = new java.net.ServerSocket(VOL_PORT, 8, java.net.InetAddress.getByName("127.0.0.1"))) {
            uidEnforced = selfTestUidLookup(ss);
            log("control port 127.0.0.1:" + VOL_PORT + (OWNER_UID >= 0 ? ", owner uid " + OWNER_UID : ", shell and root only")
                + (uidEnforced ? ", uid checked in /proc/net/tcp*" : ", token only"));
            while (true) {
                final java.net.Socket s = ss.accept();
                if (activeConn.incrementAndGet() > MAX_CONN) { activeConn.decrementAndGet(); closeQuietly(s); continue; }
                Thread t = new Thread(() -> { try { serve(s, OWNER_UID, controlToken, uidEnforced); } finally { activeConn.decrementAndGet(); } });
                t.setDaemon(true); t.start();
            }
        } catch (Throwable e) { log("control server err " + e); }
    }

    static void closeQuietly(java.net.Socket s) { try { s.close(); } catch (Throwable e) {} }

    static String readLineLimited(java.io.InputStream in, int max) {
        try {
            StringBuilder sb = new StringBuilder();
            for (int c; (c = in.read()) != -1; ) {
                if (c == '\n') return sb.toString();
                if (sb.length() >= max) return null;
                sb.append((char) c);
            }
        } catch (java.io.IOException e) {}
        return null;
    }

    static void serve(java.net.Socket s, int owner, String expected, boolean enforced) {
        try {
            s.setSoTimeout(1500);
            java.io.InputStream in = new java.io.BufferedInputStream(s.getInputStream(), 1024);
            String presented = authToken(readLineLimited(in, MAX_LINE));
            if (expected == null || presented == null || !tokenMatches(presented, expected)) { refuse(s, "bad or missing token"); return; }
            String why = uidProblem(peerUid(s), owner, enforced);
            if (why != null) { refuse(s, why); return; }
            String line = readLineLimited(in, MAX_LINE);
            String out;
            synchronized (CMD_LOCK) { out = volCmd(line == null ? "" : line.trim()); }
            s.getOutputStream().write((out + "\n").getBytes()); s.getOutputStream().flush();
        } catch (Throwable e) { log("control conn err " + e); }
        finally { closeQuietly(s); }
    }

    static void refuse(java.net.Socket s, String why) {
        if (!why.equals(lastDenied)) { lastDenied = why; log("control: denied (" + why + ")"); }
        try {
            s.getOutputStream().write("denied\n".getBytes()); s.getOutputStream().flush();
            s.shutdownOutput();
            s.setSoTimeout(300);
            byte[] junk = new byte[1024];
            long end = System.currentTimeMillis() + 500; int total = 0;
            while (total < 4096 && System.currentTimeMillis() < end) { int n = s.getInputStream().read(junk); if (n <= 0) break; total += n; }
        } catch (Throwable e) {}
    }

    static String askRouter(int port, String token, String cmd) throws java.io.IOException {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 2000);
            s.setSoTimeout(3000);
            s.getOutputStream().write(("auth " + token + "\n" + cmd + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            return new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream())).readLine();
        }
    }

    static void askMain(String cmd) {
        String token = validToken(readTokenFile(new java.io.File(TOKEN_FILE)));
        if (token == null) { System.out.println("err no token in " + TOKEN_FILE + " (was the router started with approute.sh?)"); System.exit(2); return; }
        String r = null; int rc = 0;
        try { r = askRouter(VOL_PORT, token, cmd); } catch (Throwable e) { r = "err " + e; rc = 2; }
        System.out.println(r == null ? "no_reply" : r);
        if (rc != 0) System.exit(rc);
    }

    static Object audioService() throws Exception {
        return Class.forName("android.media.IAudioService$Stub").getMethod("asInterface", android.os.IBinder.class)
            .invoke(null, Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "audio"));
    }

    static int volMin = 0, volMax = -1;
    static boolean volRange(Object as) {
        if (volMax > 0) return true;
        try {
            int max = (Integer) as.getClass().getMethod("getStreamMaxVolume", int.class).invoke(as, 3 /* STREAM_MUSIC */);
            int min = 0;
            try { min = (Integer) as.getClass().getMethod("getStreamMinVolume", int.class).invoke(as, 3); } catch (Throwable e) { log("no stream min volume: " + e); }
            if (max <= min) { log("odd music volume range " + min + ".." + max); return false; }
            volMin = min; volMax = max;
            log("music volume range " + volMin + ".." + volMax);
            return true;
        } catch (Throwable e) { log("volume range err " + e); return false; }
    }

    static String volCmd(String line) {
        String[] p = line.split(" ");
        if (p[0].equals("state")) {
            if (paused) return "paused";
            synchronized (POLICY_LOCK) { return policy != null ? "on" : "off"; }
        }
        if (p[0].equals("mode")) return "legacy";
        if (p[0].equals("ver")) return runningVer.isEmpty() ? "unknown" : runningVer;
        if (p[0].equals("pause")) {
            paused = true;
            unregister();
            log("control: pause -> standby sleep");
            return "ok";
        }
        if (p[0].equals("resume")) {
            lastBt = System.currentTimeMillis();
            paused = false;
            synchronized (PAUSE_LOCK) { PAUSE_LOCK.notifyAll(); }
            log("control: resume");
            return "ok";
        }
        if (p[0].equals("shutdown")) {
            shutdownRequested = true;
            paused = false;
            Thread killer = new Thread(() -> {
                try { Thread.sleep(80); } catch (InterruptedException ignored) {}
                synchronized (PAUSE_LOCK) { PAUSE_LOCK.notifyAll(); }
            });
            killer.setDaemon(true);
            killer.start();
            log("control: shutdown");
            return "ok";
        }
        if (p[0].equals("setbt")) {
            if (p.length < 2) return "err";
            byte[] data;
            if (p[1].equals("-")) {
                data = new byte[0];
            } else {
                try { data = java.util.Base64.getDecoder().decode(p[1]); }
                catch (IllegalArgumentException e) { return "err"; }
            }
            return writeTextAtomically(BT_FILTER_FILE, data) ? "ok" : "err";
        }
        if (p[0].equals("setidle")) {
            if (p.length < 2) return "err";
            int v;
            try { v = Integer.parseInt(p[1]); } catch (NumberFormatException e) { return "err"; }
            if (v < 0 || v > 1440) return "err";
            return writeTextAtomically(IDLE_FILE, (v + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)) ? "ok" : "err";
        }
        if (!p[0].equals("vol") || p.length < 2) return "err";
        if (p[1].equals("get")) {
            try {
                Object as = audioService();
                if (!volRange(as)) return "pass";
                int idx = getSpeakerVolIdx(as);
                return "ok " + percent(idx, volMax);
            } catch (Throwable e) { log("vol get err " + e); return "pass"; }
        }
        if (p[1].equals("set")) {
            if (p.length < 3) return "err";
            int pct;
            try { pct = Integer.parseInt(p[2]); } catch (NumberFormatException e) { return "err"; }
            if (pct < 0 || pct > 100) return "err";
            try {
                Object as = audioService();
                if (!volRange(as)) return "pass";
                int idx = idxForPercent(pct, volMin, volMax);
                setSpeakerVolIdx(as, idx);
                return "ok " + percent(idx, volMax);
            } catch (Throwable e) { log("vol set err " + e); return "pass"; }
        }
        return "err";
    }

    static int getSpeakerVolIdx(Object as) throws Exception {
        Class<?> ada = Class.forName("android.media.AudioDeviceAttributes"), vic = Class.forName("android.media.VolumeInfo"), vib = Class.forName("android.media.VolumeInfo$Builder");
        Object spk = ada.getConstructor(int.class, int.class, String.class).newInstance(2, 2 /* built-in speaker */, "");
        Object q = vib.getMethod("build").invoke(vib.getConstructor(int.class).newInstance(3 /* STREAM_MUSIC */));
        Object cur = as.getClass().getMethod("getDeviceVolume", vic, ada, String.class).invoke(as, q, spk, "com.android.shell");
        return (Integer) vic.getMethod("getVolumeIndex").invoke(cur);
    }

    static void setSpeakerVolIdx(Object as, int idx) throws Exception {
        Class<?> ada = Class.forName("android.media.AudioDeviceAttributes"), vic = Class.forName("android.media.VolumeInfo"), vib = Class.forName("android.media.VolumeInfo$Builder");
        Object spk = ada.getConstructor(int.class, int.class, String.class).newInstance(2, 2 /* built-in speaker */, "");
        Object b = vib.getConstructor(int.class).newInstance(3);
        vib.getMethod("setVolumeIndex", int.class).invoke(b, idx);
        vib.getMethod("setMinVolumeIndex", int.class).invoke(b, volMin);
        vib.getMethod("setMaxVolumeIndex", int.class).invoke(b, volMax);
        as.getClass().getMethod("setDeviceVolume", vic, ada, String.class).invoke(as, vib.getMethod("build").invoke(b), spk, "com.android.shell");
    }

    static int audioMode() {
        try {
            Object as = audioService();
            return (Integer) as.getClass().getMethod("getMode").invoke(as);
        } catch (Throwable e) { return 0; }
    }

    static AudioDeviceInfo[] outputs() throws Exception {
        return (AudioDeviceInfo[]) AudioManager.class.getMethod("getDevicesStatic", int.class).invoke(null, AudioManager.GET_DEVICES_OUTPUTS);
    }

    // Native AudioPolicy keeps at most 20 criteria per mix (MAX_CRITERIA_PER_MIX), max 50 mixes.
    // Speaker mixes reserve 2 criteria for excluding voice communication & ringtone usages (18 UIDs per mix).
    static final int CHUNK_MUSIC = 20;
    static final int CHUNK_SPEAKER = 18;
    static boolean excludeErrLogged = false;

    static void tryExcludeCommUsages(Class<?> rb, Object rbi) {
        try {
            Method ex = rb.getMethod("excludeRule", AudioAttributes.class, int.class);
            ex.invoke(rbi, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build(), 1 /* RULE_MATCH_ATTRIBUTE_USAGE */);
            ex.invoke(rbi, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build(), 1 /* RULE_MATCH_ATTRIBUTE_USAGE */);
        } catch (Throwable e) {
            if (!excludeErrLogged) { excludeErrLogged = true; log("excludeRule fallback: " + e); }
        }
    }

    static void registerBidirectional(AudioDeviceInfo spk, AudioDeviceInfo targetBt, boolean lockMusicToBt) throws Exception {
        Class<?> pb = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
        Object pbi = pb.getConstructor(android.content.Context.class).newInstance((Object) null);
        Class<?> rb = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        Class<?> ruleCls = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> mb = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        Class<?> mixCls = Class.forName("android.media.audiopolicy.AudioMix");
        AudioFormat fmt = new AudioFormat.Builder().setSampleRate(48000)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build();

        int mixes = 0, totalMusic = 0, totalOther = 0;

        // Group 1: ONLY during VoIP (WeChat voice/video), explicitly lock music apps onto targetBt A2DP.
        // During normal playback (!lockMusicToBt), leave music apps 100% on native BT A2DP (zero quality loss).
        if (lockMusicToBt && targetBt != null && musicUids.length > 0) {
            int nm = Math.min(musicUids.length, CHUNK_MUSIC * Math.max(0, 50 - mixes));
            for (int i = 0; i < nm; i += CHUNK_MUSIC) {
                Object rbi = rb.getConstructor().newInstance();
                for (int k = i; k < Math.min(nm, i + CHUNK_MUSIC); k++)
                    rb.getMethod("addMixRule", int.class, Object.class).invoke(rbi, 4 /* RULE_MATCH_UID */, Integer.valueOf(musicUids[k]));
                Object rule = rb.getMethod("build").invoke(rbi);
                Object mbi = mb.getConstructor(ruleCls).newInstance(rule);
                mb.getMethod("setFormat", AudioFormat.class).invoke(mbi, fmt);
                mb.getMethod("setRouteFlags", int.class).invoke(mbi, 1 /* ROUTE_FLAG_RENDER */);
                mb.getMethod("setDevice", AudioDeviceInfo.class).invoke(mbi, targetBt);
                pb.getMethod("addMix", mixCls).invoke(pbi, mb.getMethod("build").invoke(mbi));
                mixes++;
            }
            totalMusic = nm;
        }

        // Group 2: route every other app's media to the built-in speaker while excluding VoIP voice & ringtone usages
        int no = Math.min(uids.length, CHUNK_SPEAKER * Math.max(0, 50 - mixes));
        if (no < uids.length) log("too many apps, routing first " + no + " of " + uids.length);
        for (int i = 0; i < no; i += CHUNK_SPEAKER) {
            Object rbi = rb.getConstructor().newInstance();
            for (int k = i; k < Math.min(no, i + CHUNK_SPEAKER); k++)
                rb.getMethod("addMixRule", int.class, Object.class).invoke(rbi, 4 /* RULE_MATCH_UID */, Integer.valueOf(uids[k]));
            tryExcludeCommUsages(rb, rbi);
            Object rule = rb.getMethod("build").invoke(rbi);
            Object mbi = mb.getConstructor(ruleCls).newInstance(rule);
            mb.getMethod("setFormat", AudioFormat.class).invoke(mbi, fmt);
            mb.getMethod("setRouteFlags", int.class).invoke(mbi, 1 /* ROUTE_FLAG_RENDER */);
            mb.getMethod("setDevice", AudioDeviceInfo.class).invoke(mbi, spk);
            pb.getMethod("addMix", mixCls).invoke(pbi, mb.getMethod("build").invoke(mbi));
            mixes++;
        }
        totalOther = no;

        if (mixes == 0) throw new RuntimeException("no mixes");
        log("mixes=" + mixes + " music=" + totalMusic + " other=" + totalOther + " lockMusic=" + lockMusicToBt);
        Object p = pb.getMethod("build").invoke(pbi);
        Method reg = AudioManager.class.getDeclaredMethod("registerAudioPolicyStatic", Class.forName("android.media.audiopolicy.AudioPolicy"));
        reg.setAccessible(true);
        synchronized (POLICY_LOCK) {
            int r = (Integer) reg.invoke(null, p);
            if (r != 0) throw new RuntimeException("registerAudioPolicy=" + r);
            policy = p;
            regBtId = targetBt != null ? targetBt.getId() : -1;
            regLockMusic = lockMusicToBt;
        }
    }

    static void unregister() {
        Object old;
        synchronized (POLICY_LOCK) {
            old = policy;
            policy = null;
            regBtId = -1;
            regLockMusic = false;
        }
        if (old == null) return;
        try {
            Method un = AudioManager.class.getDeclaredMethod("unregisterAudioPolicyAsyncStatic", Class.forName("android.media.audiopolicy.AudioPolicy"));
            un.setAccessible(true); un.invoke(null, old);
        } catch (Throwable e) { log("unregister fail " + e); }
    }

    static void log(String s) { System.out.println(new java.text.SimpleDateFormat("MM-dd HH:mm:ss").format(new java.util.Date()) + " " + s); }
}
