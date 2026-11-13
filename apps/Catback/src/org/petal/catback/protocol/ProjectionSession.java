package org.petal.catback.protocol;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// phone side AAP 1.x, zero google code
public final class ProjectionSession implements Closeable, AapConnection.Listener {
    public interface Host {
        void log(String text);
        void startVideo(VideoConfig config, ProjectionSession session);
        void stopVideo();
        void audio(boolean playing, AudioConfig config, ProjectionSession session);
        void touch(float x, float y, int action);
        void key(int code);
        void evidence(String name, byte[] data);
        default void audioReady(ProjectionSession session) { }
        // rotary knob detents, + clockwise
        default void rotate(int delta) { }
        // Settings.LAYOUT_* and whether to ask for the panel's own resolution on small screens
        default String screenLayout() { return "auto"; }
        default boolean nativeVideo() { return true; }
    }
    // knob, dpad + centre/enter, hardware nav/media/phone buttons. only asked for if the car lists them
    private static final int[] WANTED_KEYS = {3, 4, 19, 20, 21, 22, 23, 24, 25, 66, 164, 85, 87, 88, 126, 127, 65536, 65537, 65538, 65540};
    private static final int[] LEGACY_KEYS = {3, 4, 85, 87, 88, 126, 127};
    private static int[] bindKeys(List<Integer> advertised) {
        if (advertised.isEmpty()) return LEGACY_KEYS;
        List<Integer> keys = new ArrayList<>();
        for (int k : WANTED_KEYS) if (advertised.contains(k)) keys.add(k);
        int[] out = new int[keys.size()];
        for (int i = 0; i < out.length; i++) out[i] = keys.get(i);
        return out;
    }
    // 1 guidance 2 system 3 media 4 telephony. media first, cheap aftermarket screens only have system so fall back
    private static int audioRank(long type) { return type == 3 ? 3 : type == 2 ? 2 : type == 1 ? 1 : 0; }
    public static final class VideoConfig {
        public int width, height, fps, index;
        // visible bit of the frame, car crops margins evenly and may nick more for its own ui
        public int contentLeft, contentTop, contentWidth, contentHeight;
        // ui units, 480 tall, width follows real aspect
        public volatile float layoutWidth = 800, layoutHeight = 480;
        // small panel: rail layout on a shorter canvas so everything draws bigger
        public volatile boolean compact;
        // 2 = render at twice the size and land at half (Supersampler), set by the service from settings
        public int supersample = 1;
        public VideoConfig(int w, int h, int f, int i) {
            width = w; height = h; fps = f; index = i;
            contentWidth = w; contentHeight = h;
        }
        // margins = VideoConfiguration 3/4, insets l/t/r/b, pixelAspectE4 = field 8 (0 square)
        public void fit(int marginW, int marginH, int[] insets, int pixelAspectE4) {
            marginW = clamp(marginW, 0, width / 2); marginH = clamp(marginH, 0, height / 2);
            int left = marginW / 2 + clamp(insets[0], 0, width / 4), top = marginH / 2 + clamp(insets[1], 0, height / 4);
            int right = marginW - marginW / 2 + clamp(insets[2], 0, width / 4);
            int bottom = marginH - marginH / 2 + clamp(insets[3], 0, height / 4);
            // cars lie, never crop more than half
            if (left + right > width / 2) { float k = (width / 2f) / (left + right); left = (int) (left * k); right = (int) (right * k); }
            if (top + bottom > height / 2) { float k = (height / 2f) / (top + bottom); top = (int) (top * k); bottom = (int) (bottom * k); }
            contentLeft = left; contentTop = top;
            contentWidth = width - left - right; contentHeight = height - top - bottom;
            // stretched pixels, lay out for what the driver actually sees
            float aspect = contentWidth / (float) contentHeight * (pixelAspectE4 > 0 ? pixelAspectE4 / 10000f : 1f);
            layoutHeight = compact ? COMPACT_HEIGHT : 480;
            float k = layoutHeight / 480f;
            layoutWidth = Math.max(560 * k, Math.min(1600 * k, layoutHeight * aspect));
        }
        private static int clamp(int value, int low, int high) { return Math.max(low, Math.min(high, value)); }
        // swap layouts mid-session (battery taps): same video stream, just the other canvas. keeps the visible aspect
        public void relayout(boolean rail) {
            float aspect = layoutWidth / layoutHeight, h = rail ? COMPACT_HEIGHT : 480, k = h / 480f;
            layoutWidth = Math.max(560 * k, Math.min(1600 * k, h * aspect));
            layoutHeight = h; compact = rail;
        }
    }
    // 800x480 panel draws 384 tall = 1.25px per unit, text and targets come out a quarter bigger
    public static final float COMPACT_HEIGHT = 384;
    public static final class AudioConfig {
        public int rate, channels, index;
        public AudioConfig(int r, int c, int i) { rate = r; channels = c; index = i; }
    }
    private static final class Window {
        private int maximum = 1, pending;
        private boolean closed;
        synchronized void configure(int count) throws IOException {
            if (count < 1 || count > 4096) throw new IOException("Invalid media ACK window " + count);
            maximum = Math.min(count, 64); pending = 0;
        }
        synchronized void acquire() throws IOException {
            long deadline = System.nanoTime() + 3_000_000_000L;
            while (!closed && pending >= maximum) {
                long left = deadline - System.nanoTime();
                if (left <= 0) throw new IOException("Head unit stopped acknowledging media");
                try { wait(Math.max(1, left / 1_000_000)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Media interrupted", e); }
            }
            if (closed) throw new IOException("Media closed");
            pending++;
        }
        synchronized void acknowledge(int count) throws IOException {
            if (count < 1 || count > 4096) throw new IOException("Invalid media ACK count");
            pending = Math.max(0, pending - count); notifyAll();
        }
        synchronized void close() { closed = true; notifyAll(); }
    }
    private static final class Channel {
        int id, kind, audioType, maxUnacked = 1;
        volatile int session = 1;
        volatile boolean opened, configured, started, rejected;
        Proto.Message descriptor;
        final Window window = new Window();
        Channel(int id, int kind, Proto.Message descriptor) { this.id = id; this.kind = kind; this.descriptor = descriptor; }
    }
    private final AapConnection connection;
    private final Host host;
    private final Map<Integer, Channel> channels = new ConcurrentHashMap<>();
    private volatile Channel video, audio;
    // other pcm sinks, best first, tried if the car turns down the one we picked
    private final List<Channel> audioFallbacks = new ArrayList<>();
    private volatile VideoConfig videoConfig;
    private volatile AudioConfig audioConfig;
    private volatile boolean authenticated, closed, videoFocused, audioFocused, mediaAudio;
    private boolean versionExchanged, discovered;
    private float touchWidth = 800, touchHeight = 480;
    public volatile long videoPackets, audioPackets, touchEvents;

    public ProjectionSession(AapConnection connection, Host host) { this.connection = connection; this.host = host; }
    public void run() throws Exception { connection.readLoop(this); }
    public long silentMs() { return connection.silentMs(); }

    @Override public void message(int channel, boolean control, boolean encrypted, int id, byte[] body) throws Exception {
        // pings every second were drowning the whole log, skip em
        if (id != 0x8004 && !(channel != 0 && id == 0) && !(channel == 0 && (id == 0x000b || id == 0x000c)))
            host.log(String.format(Locale.ROOT, "RX ch=%d id=0x%04x %s bytes=%d", channel, id, encrypted ? "TLS" : "plain", body.length));
        if (channel == 0 || control) {
            common(channel, id, body, encrypted);
            return;
        }
        if (!authenticated || !encrypted) throw new IOException("Service message before encrypted authentication");
        Channel c = channels.get(channel);
        if (c == null || !c.opened) throw new IOException("Message on unopened channel " + channel);
        if (c.rejected) return;
        Proto.Message p = new Proto.Message(body);
        if (c.kind == 3 || c.kind == 1) {
            if (id == 0x8003) setup(c, p, body);
            else if (id == 0x8004) {
                if (p.integer(1, -1) == c.session) c.window.acknowledge((int) p.integer(2, 0));
                else host.log("Ignoring old ACK on ch=" + c.id + " session=" + p.integer(1, -1) + "; current=" + c.session);
            }
            else if (id == 0x8008 && c == video) {
                videoFocused = p.integer(1, 2) == 1;
                host.log("Video focus " + (videoFocused ? "projected" : "native"));
                if (videoFocused) startVideo();
                else stopVideo();
            }
        } else if (c.kind == 4 && id == 0x8001) input(p);
        else if (c.kind == 4 && id == 0x8003 && p.integer(1, 0) != 0)
            host.log("Input binding rejected: " + p.integer(1, 0));
    }

    private void common(int channel, int id, byte[] body, boolean encrypted) throws Exception {
        if (id == 1 && channel == 0) {
            if (versionExchanged || body.length < 4) throw new IOException("Invalid version request");
            ByteBuffer in = ByteBuffer.wrap(body);
            int major = in.getShort() & 65535, minor = in.getShort() & 65535;
            boolean supported = major >= 1;
            int negotiatedMinor = major == 1 ? Math.min(7, minor) : 7;
            send(0, false, false, 2, ByteBuffer.allocate(6).putShort((short) 1)
                    .putShort((short) negotiatedMinor).putShort((short) (supported ? 0 : -1)).array());
            if (!supported) throw new IOException("Unsupported AAP version " + major + "." + minor);
            versionExchanged = true;
            host.log("Negotiated AAP 1." + negotiatedMinor + " (HU requested " + major + "." + minor + ")");
        } else if (id == 3 && channel == 0) {
            if (!versionExchanged || authenticated) throw new IOException("Unexpected TLS handshake");
            connection.handshake(body);
            if (connection.tlsReady()) host.log("TLS 1.2 handshake complete; waiting for auth complete");
        } else if (id == 4 && channel == 0) {
            if (!connection.tlsReady() || authenticated) throw new IOException("Invalid auth-complete sequence");
            if (new Proto.Message(body).integer(1, 0) != 0) throw new IOException("Head unit rejected TLS identity");
            authenticated = true;
            host.log("Authenticated; requesting head-unit services");
            send(0, false, true, 5, new Proto.Writer().text(4, "CATBACK").text(5, "PetalOS").build());
        } else if (id == 11) {
            send(channel, channel != 0, encrypted, 12, body);
        } else if (id == 15) {
            send(channel, channel != 0, encrypted, 16, Proto.EMPTY);
            close();
        } else {
            if (!authenticated || !encrypted) throw new IOException("Control message before authentication: " + id);
            if (id == 6 && channel == 0) { host.evidence("discovery.pb", body); discover(new Proto.Message(body)); }
            else if (id == 8) {
                Channel c = channels.get(channel);
                if (c == null || c.opened) throw new IOException("Unexpected channel-open response");
                long status = new Proto.Message(body).integer(1, 0);
                c.opened = true;
                if (status != 0 && c == audio) { audioRejected(c, "open status=" + status); return; }
                if (status != 0) throw new IOException("Channel " + channel + " rejected");
                if (c.kind == 3 || c.kind == 1)
                    send(channel, false, true, 0x8000, new Proto.Writer().integer(1, c.kind == 3 ? 3 : 1).build());
                else if (c.kind == 4) {
                    int[] keys = bindKeys(c.descriptor.integers(1));
                    Proto.Writer binding = new Proto.Writer();
                    for (int k : keys) binding.integer(1, k);
                    host.log("Binding car keys " + java.util.Arrays.toString(keys));
                    send(channel, false, true, 0x8002, binding.build());
                }
            } else if (id == 19 && channel == 0) {
                long state = new Proto.Message(body).integer(1, 0);
                audioFocused = state == 1 || state == 2 || state == 6 || state == 7;
                host.log("Audio focus state " + state);
                updateAudio();
            } else if (id == 9) throw new IOException("Peer closed channel " + channel);
            else if (id != 12 && id != 14 && id != 16 && id != 23)
                host.log("Unhandled common message " + id);
        }
    }

    private void discover(Proto.Message response) throws Exception {
        if (discovered) throw new IOException("Duplicate discovery response");
        discovered = true;
        host.log("Head unit: " + response.text(2, "unknown") + " / " + response.text(8, "unknown model"));
        List<Channel> selected = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (Proto.Message descriptor : response.messages(1)) {
            int id = (int) descriptor.integer(1, -1);
            if (id < 1 || id > 255 || !seen.add(id)) throw new IOException("Invalid/duplicate service channel");
            if (descriptor.has(3)) {
                Proto.Message av = descriptor.message(3);
                int codec = (int) av.integer(1, 0);
                host.log("Service " + id + ": codec=" + codec + ", video configs=" + av.messages(4).size()
                        + ", audio configs=" + av.messages(3).size() + ", audio type=" + av.integer(2, 0));
                // newer sinks skip field 1 and stick the codec in field 10 (stock DHU does this)
                if (video == null && !av.messages(4).isEmpty()) {
                    Channel c = new Channel(id, 3, av); video = c; selected.add(c);
                } else if (codec == 1 && !av.messages(3).isEmpty() && audioRank(av.integer(2, 0)) > 0) {
                    Channel c = new Channel(id, 1, av); c.audioType = (int) av.integer(2, 0); audioFallbacks.add(c);
                }
            } else if (descriptor.has(4)) {
                Channel c = new Channel(id, 4, descriptor.message(4)); selected.add(c);
                Proto.Message touch = c.descriptor.message(2);
                touchWidth = Math.max(1, touch.integer(1, 800));
                touchHeight = Math.max(1, touch.integer(2, 480));
            }
            // sensors/mic/bt/nav not opened yet
        }
        if (video == null) throw new IOException("Receiver advertises no baseline H.264 video sink");
        audioFallbacks.sort((a, b) -> audioRank(b.audioType) - audioRank(a.audioType));
        if (!audioFallbacks.isEmpty()) { audio = audioFallbacks.remove(0); selected.add(audio); }
        for (Channel c : selected) open(c);
    }
    private void open(Channel c) throws IOException {
        channels.put(c.id, c);
        host.log("Opening " + (c.kind == 3 ? "H.264 video" : c.kind == 1 ? (c.audioType == 3 ? "PCM media audio" : "PCM audio (type "
                + c.audioType + ", used for phone media)") : "input") + " channel " + c.id);
        send(c.id, true, true, 7, new Proto.Writer().integer(1, c.kind == 3 ? 1 : 2).integer(2, c.id).build());
    }
    // some cars refuse a pcm sink they advertised; projection still works without it
    private void audioRejected(Channel c, String why) throws IOException {
        host.log("Audio channel " + c.id + " (type " + c.audioType + ") unusable: " + why);
        c.rejected = true;
        audio = null; audioConfig = null;
        if (!audioFallbacks.isEmpty()) { audio = audioFallbacks.remove(0); open(audio); }
        else host.log("Continuing without car audio; phone media stays on phone speakers / Bluetooth");
    }

    private void setup(Channel c, Proto.Message response, byte[] raw) throws Exception {
        if (c.configured) throw new IOException("Duplicate media setup on " + c.id);
        long status = response.integer(1, 0);
        List<Integer> indices = response.integers(3);
        if (status != 2 || indices.isEmpty()) {
            String why = "setup status=" + status + ", maxUnacked=" + response.integer(2, 0) + ", configs=" + indices + ", raw=" + hex(raw);
            if (c == audio) { audioRejected(c, why); return; }
            throw new IOException("Media setup failed on " + c.id + ": " + why);
        }
        c.maxUnacked = (int) response.integer(2, 0);
        c.window.configure(c.maxUnacked);
        List<Proto.Message> configs = c.descriptor.messages(c.kind == 3 ? 4 : 3);
        boolean compact = false;
        if (c.kind == 3) {
            int panel = panelConfig(configs);
            int panelHeight = panel < 0 ? 0 : height((int) configs.get(panel).integer(1, 0));
            String layout = host.screenLayout();
            compact = "compact".equals(layout) || (!"wide".equals(layout) && panelHeight > 0 && panelHeight <= 480);
            // cheap boxes downscale 720p badly; on a small panel send its own size so nothing gets resampled
            if (compact && panel >= 0 && host.nativeVideo() && indices.contains(panel) && indices.get(0) != panel) {
                indices = new ArrayList<>(indices); indices.remove((Integer) panel); indices.add(0, panel);
                host.log("Preferring the panel's own resolution (configuration " + panel + ")");
            }
            host.log("Car screen layout: " + (compact ? "compact rail" : "wide") + " (setting " + layout
                    + ", panel " + (panelHeight > 0 ? panelHeight + "p" : "unknown") + ")");
        }
        for (int index : indices) {
            if (index < 0 || index >= configs.size()) continue;
            Proto.Message config = configs.get(index);
            if (c.kind == 3) {
                int resolution = (int) config.integer(1, 0);
                int width = resolution == 1 ? 800 : resolution == 2 ? 1280 : resolution == 3 ? 1920 : 0;
                int height = height(resolution);
                if (width == 0 || config.integer(10, 3) != 3) continue;
                // gearhead jbi.a(): 1 = 60fps, 2 = 30fps
                VideoConfig chosen = new VideoConfig(width, height, config.integer(2, 2) == 1 ? 60 : 30, index);
                chosen.compact = compact;
                int[] insets = insets(config);
                chosen.fit((int) config.integer(3, 0), (int) config.integer(4, 0), insets, (int) config.integer(8, 0));
                videoConfig = chosen;
                host.log("Video configuration " + index + ": " + width + "x" + height + " @" + chosen.fps
                        + ", margins=" + config.integer(3, 0) + "x" + config.integer(4, 0) + ", density=" + config.integer(5, 0)
                        + ", pixelAspectE4=" + config.integer(8, 0) + ", insets(l,t,r,b)=" + java.util.Arrays.toString(insets)
                        + ", touch=" + (int) touchWidth + "x" + (int) touchHeight);
                host.log("Drawing UI in visible area " + chosen.contentWidth + "x" + chosen.contentHeight + " at "
                        + chosen.contentLeft + "," + chosen.contentTop + "; layout " + (int) chosen.layoutWidth + "x" + (int) chosen.layoutHeight);
            } else {
                int rate = (int) config.integer(1, 0), count = (int) config.integer(3, 0);
                if (rate < 8000 || rate > 96000 || (count != 1 && count != 2) || config.integer(2, 0) != 16) continue;
                audioConfig = new AudioConfig(rate, count, index);
                host.log("Audio configuration " + index + ": " + rate + " Hz / " + count + " channels PCM16");
            }
            c.configured = true;
            if (c == video) {
                send(c.id, false, true, 0x8007, new Proto.Writer().integer(1, 0).integer(2, 1).integer(3, 1).build());
                startVideo();
            } else { host.audioReady(this); if (mediaAudio) updateAudio(); }
            return;
        }
        if (c == audio) { audioRejected(c, "no PCM16 mono/stereo configuration among " + indices); return; }
        throw new IOException("No supported media configuration on channel " + c.id);
    }
    private static int height(int resolution) { return resolution == 1 ? 480 : resolution == 2 ? 720 : resolution == 3 ? 1080 : 0; }
    // the config the panel shows 1:1 = square pixels once the car's aspect fix is applied; else the smallest
    private static int panelConfig(List<Proto.Message> configs) {
        int square = -1, smallest = -1, smallestH = Integer.MAX_VALUE;
        for (int i = 0; i < configs.size(); i++) {
            Proto.Message config = configs.get(i);
            int h = height((int) config.integer(1, 0));
            if (h == 0) continue;
            long aspect = config.integer(8, 0);
            if (square < 0 && (aspect == 0 || Math.abs(aspect - 10000) <= 400)) square = i;
            if (h < smallestH) { smallestH = h; smallest = i; }
        }
        return square >= 0 ? square : smallest;
    }
    private static String hex(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte b : data) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }

    private void startVideo() throws IOException {
        Channel v = video;
        if (closed || v == null || !v.configured || !videoFocused || v.started) return;
        v.started = true;
        send(v.id, false, true, 0x8001, new Proto.Writer().integer(1, v.session).integer(2, videoConfig.index).build());
        host.log("Starting projected H.264 surface");
        host.startVideo(videoConfig, this);
    }
    private void stopVideo() throws IOException {
        Channel v = video;
        host.stopVideo();
        if (v != null && v.started) {
            v.started = false;
            send(v.id, false, true, 0x8002, Proto.EMPTY);
            v.session++;
            v.window.configure(v.maxUnacked);
        }
    }

    // field 11 AdditionalVideoConfig (gearhead wcb), margin_configs 7 -> l=1 t=2 r=3 b=4
    private static int[] insets(Proto.Message config) {
        int[] result = new int[4];
        try {
            if (!config.has(11)) return result;
            List<Proto.Message> margins = config.message(11).messages(7);
            if (margins.isEmpty() || !margins.get(0).has(1)) return result;
            Proto.Message inset = margins.get(0).message(1);
            for (int i = 0; i < 4; i++) result[i] = (int) Math.max(0, inset.integer(i + 1, 0));
        } catch (IOException malformed) { /* just a hint, whatever */ }
        return result;
    }

    // touch coords -> ui units. full-frame panels include the cropped margins, else panel = visible area
    private float[] layoutPoint(long touchX, long touchY) {
        VideoConfig v = videoConfig;
        if (v == null) return new float[]{touchX * 800f / touchWidth, touchY * 480f / touchHeight};
        float fx = touchX / touchWidth, fy = touchY / touchHeight;
        boolean fullFrame = Math.abs(touchWidth - v.width) <= 1 && Math.abs(touchHeight - v.height) <= 1
                && (v.contentWidth != v.width || v.contentHeight != v.height);
        if (fullFrame) {
            fx = (fx * v.width - v.contentLeft) / v.contentWidth;
            fy = (fy * v.height - v.contentTop) / v.contentHeight;
        }
        return new float[]{fx * v.layoutWidth, fy * v.layoutHeight};
    }

    private void input(Proto.Message event) throws IOException {
        if (event.has(3)) {
            Proto.Message touch = event.message(3);
            List<Proto.Message> locations = touch.messages(1);
            if (!locations.isEmpty()) {
                int index = (int) touch.integer(2, 0);
                if (index < 0 || index >= locations.size()) return;
                Proto.Message point = locations.get(index);
                int action = (int) touch.integer(3, 0);
                float[] layout = layoutPoint(point.integer(1, 0), point.integer(2, 0));
                float x = layout[0], y = layout[1];
                touchEvents++;
                host.touch(x, y, action);
                host.log("Touch " + action + " at " + (int) x + "," + (int) y);
            }
        }
        // relative events: the rotary knob, keycode 65536, signed detents
        if (event.has(6)) {
            for (Proto.Message turn : event.message(6).messages(1)) {
                int code = (int) turn.integer(1, 0), delta = (int) turn.integer(2, 0);
                if (code == 65536 && delta != 0) { host.rotate(delta); host.log("Rotary " + delta); }
            }
        }
        if (event.has(4)) {
            for (Proto.Message key : event.message(4).messages(1)) {
                if (key.integer(2, 0) == 0) {
                    int code = (int) key.integer(1, 0);
                    host.key(code); host.log("Key released " + code);
                }
            }
        }
    }

    // car audio on/off, grabs or drops focus then starts/stops pcm
    public synchronized void setMediaAudio(boolean enabled) {
        mediaAudio = enabled;
        try {
            if (!authenticated || audio == null) { host.log("No PCM audio sink available"); return; }
            send(0, false, true, 18, new Proto.Writer().integer(1, enabled ? 1 : 4).build());
            updateAudio();
        } catch (Exception e) { fail(e); }
    }
    private synchronized void updateAudio() throws IOException {
        Channel a = audio;
        if (a == null || !a.configured) return;
        if (mediaAudio && audioFocused && !a.started) {
            a.started = true;
            send(a.id, false, true, 0x8001, new Proto.Writer().integer(1, a.session).integer(2, audioConfig.index).build());
            host.audio(true, audioConfig, this);
        } else if ((!mediaAudio || !audioFocused) && a.started) {
            host.audio(false, audioConfig, this);
            a.started = false;
            send(a.id, false, true, 0x8002, Proto.EMPTY);
            a.session++;
            a.window.configure(a.maxUnacked);
        }
    }

    public void video(byte[] nal, long timestampUs, boolean codecConfig) throws IOException {
        Channel v = video;
        if (closed || v == null || !v.started) return;
        int generation = v.session;
        try { v.window.acquire(); }
        catch (IOException e) { throw new IOException("Video channel " + v.id + " session " + v.session + ": " + e.getMessage(), e); }
        if (!v.started || v.session != generation) { v.window.acknowledge(1); return; }
        byte[] body = codecConfig ? nal : ByteBuffer.allocate(nal.length + 8).putLong(timestampUs).put(nal).array();
        connection.send(v.id, false, true, codecConfig ? 1 : 0, body);
        videoPackets++;
    }
    // capture threads use this so a dead send ends the session instead of blowing up the loop
    public void pcmQuiet(byte[] data, long timestampUs) {
        try { pcm(data, timestampUs); } catch (Exception e) { fail(e); }
    }
    public void pcm(byte[] data, long timestampUs) throws IOException {
        Channel a = audio;
        if (closed || a == null || !a.started) return;
        int generation = a.session;
        try { a.window.acquire(); }
        catch (IOException e) { throw new IOException("Audio channel " + a.id + " session " + a.session + ": " + e.getMessage(), e); }
        if (!a.started || a.session != generation) { a.window.acknowledge(1); return; }
        connection.send(a.id, false, true, 0, ByteBuffer.allocate(data.length + 8).putLong(timestampUs).put(data).array());
        audioPackets++;
    }
    private void send(int channel, boolean control, boolean encrypted, int id, byte[] body) throws IOException {
        if (!(channel == 0 && (id == 0x000b || id == 0x000c))) host.log(String.format(Locale.ROOT, "TX ch=%d id=0x%04x %s bytes=%d", channel, id, encrypted ? "TLS" : "plain", body.length));
        connection.send(channel, control, encrypted, id, body);
    }
    public void fail(Exception error) {
        if (!closed) host.log("Session failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
        try { close(); } catch (IOException ignored) { }
    }
    // host already stopped video/audio itself, just cut the wire. calling back into the host from a
    // teardown thread could kill the NEXT session's encoder if the car reconnected quick
    public void closeLink() {
        if (closed) return;
        closed = true;
        for (Channel c : channels.values()) c.window.close();
        host.log("Session closed; video=" + videoPackets + ", audio=" + audioPackets + ", touch=" + touchEvents);
        try { connection.close(); } catch (IOException ignored) { }
    }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        for (Channel c : channels.values()) c.window.close();
        host.stopVideo(); host.audio(false, audioConfig, this);
        host.log("Session closed; video=" + videoPackets + ", audio=" + audioPackets + ", touch=" + touchEvents);
        connection.close();
    }
}
