package org.petal.catback.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.petal.catback.R;
import org.petal.catback.car.CarData;
import org.petal.catback.host.AppDisplay;
import org.petal.catback.host.CarAppHost;

// the car ui, ported from design/car-ui/index.html. 480 tall x visible width, silk panels, glass only on the island.
// touches hit-test against last frame's regions
public final class CarUi {
    private static CarUi instance;
    public static synchronized CarUi get(Context context) {
        if (instance == null) instance = new CarUi(context.getApplicationContext());
        return instance;
    }
    // the phone's dev preview gets its own copy: draw() rewrites width + hit regions, sharing one with a live car
    // would move where the driver's taps land. never gets session(true) so nothing live runs in it
    private static CarUi preview;
    public static synchronized CarUi preview(Context context) {
        if (preview == null) preview = new CarUi(context.getApplicationContext());
        return preview;
    }

    // dark roles, accent ~ system_accent1_200
    // 50% surface over frosted wall then silk
    public static final int PANEL = 0x80191B23, SHEET = 0xCC1D1F28;
    public static final int ACCENT = 0xFFB9A5FF, ACCENT_CONTAINER = 0xFF3B3156,
            TEXT = 0xFFF5F5F8, TEXT2 = 0xFFB9BAC4, ON_ACCENT = 0xFF17112E, CHIP = 0x14FFFFFF;
    // empty slots (no art, no avatar, nothing chosen) stay neutral, accent is for the live turn + the one action
    static final int PLACEHOLDER = 0x1AFFFFFF;
    private static final float BAR_TOP = 52, BAR_BOTTOM = 460;
    // canvas height in ui units: 480 wide layout, ProjectionSession.COMPACT_HEIGHT on small panels
    private float H = 480;
    private static final float[] OMEGA = {5.1f, 6.3f, 4.4f, 7.0f, 5.7f, 6.9f}; // IslandConstants.BAR_OMEGA

    private final Context context;
    private final CarData data;
    final Typeface regular, medium, semibold, bold;
    final TextPaint measure = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint cardTitle = new TextPaint(Paint.ANTI_ALIAS_FLAG), cardBody = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG),
            image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Silk navSilk = new Silk(), mediaSilk = new Silk(), messageSilk = new Silk(),
            sheetSilk = new Silk(), drawerSilk = new Silk();
    private final IslandGlass glass = new IslandGlass();
    private final Backdrop backdrop;
    private final Island island = new Island();
    private final RenderNode scene = new RenderNode("car-scene");
    private final RectF r = new RectF(), r2 = new RectF();
    private final Matrix matrix = new Matrix();
    private final long start = SystemClock.elapsedRealtime();
    private final List<Hit> hits = new ArrayList<>(), pending = new ArrayList<>(), sceneHits = new ArrayList<>();
    // only re-record the static scene when it actually changes, eq + island draw on top
    private long sceneSignature = Long.MIN_VALUE;
    private int sceneVersion;
    private float eqX, eqY, eqH; private boolean eqVisible, eqPlaying;
    private int frameCanvasW, frameCanvasH, frameLeft, frameTop; private float frameSx = 1, frameSy = 1;
    // supersample factor of the frame being drawn; live app displays + their touch maths stay at output pixels
    private float ssaa = 1;
    // frame skipping: if the last frame wasn't animating and nothing has changed since, the next one would come
    // out identical, so the encoder repeats it instead of the gpu redrawing it
    private volatile boolean dirty = true;
    private boolean animating = true;
    private int sigCw, sigCh, sigLeft, sigTop, sigW, sigH; private float sigLayout;
    private final GlassBaker baker = new GlassBaker();
    // island glass but lighter tint, 50% see-through
    private static final int PANEL_GLASS = 0x8C14161E;
    private int batteryLevel = -1; private long batteryAt = -100000;
    private final java.util.HashMap<Integer, Drawable> icons = new java.util.HashMap<>();
    private final java.util.HashMap<Bitmap, BitmapShader> shaders = new java.util.HashMap<>();
    private String picking; // null, MAPS or MEDIA
    private float width = 800;
    // drawer: swipe right opens, left/back/scrim closes. 0 shut 1 open
    private static final float SLOP = 24, SWIPE = 80, TILE_W = 132, TILE_H = 150, TILE_GAP = 12;
    private boolean drawerOpen, dragging, scrolling;
    private float drawer, drawerVel, drawerScroll, downX, downY, downScroll, lastX;
    // drawer carousel: weighted scroll that flings and then snaps hard onto the column nearest where it'd coast
    // to; the centred app is the selected one. stiff spring, a touch under critical so it lands with a nudge
    private static final float DRAWER_K = 190f, DRAWER_D = 24f, DRAWER_FRICTION = 4.5f, DRAWER_MAX_V = 4000f;
    private float drawerScrollVel, drawerTarget, drawerFlingV;
    private long drawerDownAt, drawerMoveAt;
    private boolean drawerMoved;
    private int drawerFocus, drawerRows = 1, drawerColumns;
    private List<CarData.App> drawerApps = new ArrayList<>();
    private final RectF drawerFocusRect = new RectF();
    // flicking again while it still coasts the same way stacks speed, so regripping keeps it going
    private float drawerCarryV;
    // a-z strip: only letters that have apps (bigger targets in a car), # for digits/symbols. sorted copy of
    // carApps rebuilt when that list changes; pill springs to the centred app's letter
    private List<CarData.App> drawerSource;
    private final List<Character> drawerLetters = new ArrayList<>();
    private int[] letterStart = new int[0];
    private final RectF stripBox = new RectF();
    private float stripCell, stripLeft, pillX = Float.NaN, pillVel;
    private boolean scrubbing, stripFocus;
    // big letter over the carousel while jumping letters
    private char bubbleLetter; private long bubbleAt = -100000;
    private static final long BUBBLE_MS = 700;
    // knob speed: detents close together = jump a letter each. latched a little past the last detent so the
    // pause while you regrip doesn't drop you back to app by app; turning the other way unlatches at once
    private static final long FAST_DETENT_MS = 140, LETTER_LATCH_MS = 800;
    private long lastDetentAt = -100000; private int lastDetentDir, fastStreak; private boolean letterLatch;
    private long drawerAt = SystemClock.elapsedRealtime();
    private final RectF drawerRow = new RectF();
    // live bits, only while a car's actually connected: nav app in the maps panel, phone app in a floating window
    private volatile boolean session;
    private CarAppHost mapHost;
    private AppDisplay mapDisplay;
    private String mapSource;
    private CarAppPanel carPanel;
    private final RectF mapsBox = new RectF();
    private int mapW, mapH, mapDpi, winW, winH, winDpi;
    private AppDisplay window;
    private CarData.App windowApp;
    private boolean windowOpen, windowFull;
    private float windowAnim, windowVel, fullAnim, fullVel;
    private long windowAt = SystemClock.elapsedRealtime();
    private final RectF windowRect = new RectF(), windowContent = new RectF(), windowTarget = new RectF();
    // 0 nothing, 1 map, 2 app window, 3 car app list scroll, 4 volume hud
    private int gesture;
    private boolean gestureMoved;
    private float gestureX, gestureY, tapX, tapY;
    // messages carousel, index never wraps so looping round just keeps sliding the same way. only the driver moves it,
    // no auto-advance: a card that slides away mid-read is worse than one that sits still
    private static final long CAROUSEL_MS = 420;
    private final RectF msgBox = new RectF();
    private int carouselIndex;
    private float carouselFrom;
    private long carouselAt = SystemClock.elapsedRealtime();
    // phone's "remove animations" (animator scale 0): springs snap, no wobble, eq holds still. re-read every couple of s
    private boolean still; private long stillAt = -100000;
    private boolean still() {
        long nowMs = SystemClock.elapsedRealtime();
        if (nowMs - stillAt > 2000) {
            stillAt = nowMs;
            still = android.provider.Settings.Global.getFloat(context.getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        }
        return still;
    }
    // battery % tapped (or knob-pressed) five times in a row swaps rail <-> panels. set by whoever owns the
    // video config: the projection service for a car, the preview for the phone
    public volatile Runnable layoutToggle;
    private static final long BATTERY_TAP_GAP_MS = 1500;
    private int batteryTaps; private long batteryTapAt = -100000;
    private void batteryTap() {
        long nowMs = SystemClock.elapsedRealtime();
        batteryTaps = nowMs - batteryTapAt < BATTERY_TAP_GAP_MS ? batteryTaps + 1 : 1;
        batteryTapAt = nowMs;
        Runnable toggle = layoutToggle;
        if (batteryTaps >= 5 && toggle != null) { batteryTaps = 0; toggle.run(); invalidate(); }
    }
    // 4: wifi glyph only when the projection actually runs over wifi
    private volatile boolean overWifi;

    private static final class Hit {
        final RectF rect, visual; final Runnable action;
        int rail = -1; // compact rail slot this selects
        Hit(RectF rect, RectF visual, Runnable action) {
            this.rect = new RectF(rect); this.visual = visual == null ? null : new RectF(visual); this.action = action;
        }
        // a real control the knob can land on, not a scrim or a wobble-only panel
        boolean focusable() { return action != null && visual != null; }
    }

    // compact: rail on the left, one page at a time. 0 maps 1 music 2 messages
    private static final float RAIL_W = 84;
    // rail slims to icons only after a few seconds with no touch / knob / key, any input brings the labels back.
    // the page grows into the space; glass + live map hold their last size while it moves (see panel, live map)
    private static final float RAIL_SLIM = 50;
    private static final long RAIL_IDLE_MS = 5000;
    // underdamped (~.35 of critical) so it overshoots and boings both ways, settles in about half a second
    private static final float RAIL_K = 300, RAIL_D = 12;
    private long inputAt = SystemClock.elapsedRealtime(), railFrameAt = SystemClock.elapsedRealtime();
    private boolean railSlim; private float railSpring = 1, railVel;
    private void poke() { inputAt = SystemClock.elapsedRealtime(); }
    private boolean railWantsSlim() {
        // not while the knob's parked on the rail, you'd be steering by labels that just vanished
        return compact && SystemClock.elapsedRealtime() - inputAt > RAIL_IDLE_MS && !(focusShown && focusZone == 0);
    }
    // 1 full rail, 0 slim; overshoots past both ends mid-boing
    private float railK() { return railSpring; }
    private boolean railMoving() { return Math.abs(railSpring - (railSlim ? 0 : 1)) > .002f || Math.abs(railVel) > .01f; }
    private void stepRail() {
        long nowMs = SystemClock.elapsedRealtime();
        float dt = Math.min(.1f, (nowMs - railFrameAt) / 1000f), target = railSlim ? 0 : 1; railFrameAt = nowMs;
        for (float left = dt; left > 0; left -= .004f) {
            float step = Math.min(.004f, left);
            railVel += (RAIL_K * (target - railSpring) - RAIL_D * railVel) * step; railSpring += railVel * step;
        }
        if (still || (Math.abs(target - railSpring) < .002f && Math.abs(railVel) < .01f)) { railSpring = target; railVel = 0; }
    }

    // volume hud: the rom's bezel blob (VolumeBlob), oozing out of the right edge. shows on any media volume
    // change while a car's connected (or the preview's vol keys), hides 1.6s after the last one like the phone's
    private static final long VOLUME_SHOW_MS = 1600;
    private final VolumeBlob volume = new VolumeBlob();
    private float volLevel;
    private int volSteps = 15;
    private boolean volMuted, volScrub;
    private long volAt = -100000, volFrameAt = SystemClock.elapsedRealtime();
    private android.content.BroadcastReceiver volumeReceiver;
    private static final long PAGE_MS = 420;
    private boolean compact;
    private int page, pageFrom;
    private long pageAt = -100000;
    private final RectF pageBox = new RectF(), railBox = new RectF();
    // live map rides along with its page during a swing: offset, fade, and no taps while it's on its way out
    private float mapsShift, mapsAlpha = 1; private boolean mapsLeaving;
    private final Paint feather = new Paint();
    private float featherTop = Float.NaN, featherBottom, featherBoxTop, featherBoxBottom;
    private final Silk railSilk = new Silk();
    private float railEqX, railEqY; private boolean railEqVisible;
    private int eqColor = ACCENT, railEqColor = ACCENT;
    // music page cover: how far it's faded in (0 wallpaper + panels, 1 full-screen cover) and its scrims
    private float ambient, coverShadeH;
    private Shader coverTop, coverBottom;
    private final Paint coverPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    // selected rail item over the cover
    private static final int BLEND_ON = 0x2EFFFFFF;
    // knob/dpad focus, hidden until the knob's used and hidden again by touch (android touch mode).
    // compact has two zones: 0 the rail (turning switches page), 1 the page's own controls
    private boolean focusShown;
    private int focusZone, focusIndex, savedFocus, overlayStart = -1;
    private final Paint focusCore = new Paint(Paint.ANTI_ALIAS_FLAG), focusGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    // tapped thing squashes and boings back, damped spring
    private static final float WOBBLE_SECONDS = .35f;
    private final RectF wobbling = new RectF();
    private long wobbleAt = -100000;
    // sheet waits a beat after a pick so you see the wobble, 0 = nothing
    private long closeSheetsAt;
    private void closeSheetsSoon() { closeSheetsAt = SystemClock.elapsedRealtime() + 260; }

    // only true on the platform-signed rom build; never changes at runtime so check it once
    private final boolean displayHost;
    // CAL apps that said no at the handshake, don't keep knocking — fall back for these from here on
    private final java.util.Set<String> calRejected = new java.util.HashSet<>();

    private CarUi(Context context) {
        this.context = context;
        data = CarData.get(context);
        displayHost = AppDisplay.available(context);

        backdrop = new Backdrop(context);
        // static scene rasterised once into its own gpu layer, only repainted when it's re-recorded
        scene.setUseCompositingLayer(true, null);
        regular = font(400); medium = font(500); semibold = font(600); bold = font(700);
        feather.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
        focusCore.setStyle(Paint.Style.STROKE); focusCore.setStrokeWidth(2.5f); focusCore.setColor(ACCENT);
        focusGlow.setStyle(Paint.Style.STROKE); focusGlow.setStrokeWidth(7f); focusGlow.setColor(ACCENT & 0x55FFFFFF);
        focusGlow.setMaskFilter(new BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL));
        island.measure = (string, size, style) -> {
            style(text, style == 2 ? bold : style == 1 ? semibold : regular, size);
            return text.measureText(string);
        };
    }
    private Typeface font(int weight) {
        try {
            return new Typeface.Builder(context.getAssets(), "fonts/Commissioner-VF.ttf")
                    .setFontVariationSettings("'wght' " + weight).setWeight(weight).build();
        } catch (RuntimeException e) { return Typeface.create("sans-serif", weight >= 600 ? Typeface.BOLD : Typeface.NORMAL); }
    }

    // input
    // layout units, AAP 0 down 1 up 2 move. tap fires on release unless the finger wandered (swipe/drag)
    public synchronized void touch(float x, float y, int action) {
        dirty = true;
        // the volume blob is its own thing: dragging it doesn't count as using the ui, so the rail stays slim
        if (action == 0 && volume.touchable() && volume.bounds.contains(x, y)) { gesture = 4; volScrub = true; volumeFrom(y); return; }
        if (action != 0 && gesture == 4) {
            volumeFrom(y);
            if (action == 1) { volScrub = false; gesture = 0; volAt = SystemClock.elapsedRealtime(); }
            return;
        }
        if (action == 0) poke();
        if (action == 0) {
            focusShown = false;
            if (drawerOpen && picking == null && drawerColumns > 0 && stripHit(x, y)) { scrubbing = true; scrubTo(x); return; }
            gesture = 0; gestureMoved = false; gestureX = x; gestureY = y;
            boolean free = !drawerOpen && picking == null;
            if (free && windowOpen && window != null && windowContent.contains(x, y)) { gesture = 2; windowTouch(x, y, 0); return; }
            boolean inMaps = free && !(windowOpen && windowRect.contains(x, y)) && mapsBox.contains(x, y);
            if (inMaps && mapDisplay != null) { gesture = 1; mapTouch(x, y, 0); return; }
            if (inMaps && mapHost != null) gesture = carPanel != null && carPanel.listBox.contains(x, y) ? 3 : 1;
            downX = lastX = x; downY = y; downScroll = drawerScroll; dragging = true;
            scrolling = drawerOpen && drawerRow.contains(x, y) && !Float.isNaN(drawerScroll);
            if (scrolling) {
                drawerDownAt = drawerMoveAt = SystemClock.elapsedRealtime(); drawerMoved = false; drawerFlingV = 0;
                drawerCarryV = Math.abs(drawerScrollVel) > 300 ? drawerScrollVel : 0; // catch it, but remember
                drawerScrollVel = 0;
            }
            return;
        }
        if (scrubbing) {
            // the carousel follows the finger letter by letter; cars without move events still jump on tap
            scrubTo(x);
            if (action == 1) scrubbing = false;
            return;
        }
        if (action == 2) {
            if (gesture == 2) { windowTouch(x, y, 2); return; }
            if (gesture == 1 && mapDisplay != null) { mapTouch(x, y, 2); return; }
            if ((gesture == 1 || gesture == 3) && (gestureMoved || Math.abs(x - downX) > SLOP || Math.abs(y - downY) > SLOP)) {
                if (gesture == 1 && mapHost != null) mapHost.scroll((gestureX - x) * frameSx / ssaa, (gestureY - y) * frameSy / ssaa);
                else if (gesture == 3 && carPanel != null) carPanel.listScroll += gestureY - y;
                gestureX = x; gestureY = y; gestureMoved = true;
                return;
            }
            if (dragging) { if (scrolling) dragDrawer(x); lastX = x; }
            return;
        }
        if (action != 1) return;
        int g = gesture; gesture = 0;
        if (g == 2) { windowTouch(x, y, 1); return; }
        if (g == 1 && mapDisplay != null) { mapTouch(x, y, 1); return; }
        if ((g == 1 || g == 3) && (gestureMoved || Math.abs(x - downX) > SLOP || Math.abs(y - downY) > SLOP)) {
            // no move events from this car, do the whole drag on lift
            if (!gestureMoved && g == 1 && mapHost != null) mapHost.scroll((downX - x) * frameSx / ssaa, (downY - y) * frameSy / ssaa);
            else if (!gestureMoved && g == 3 && carPanel != null) carPanel.listScroll += downY - y;
            dragging = false;
            return;
        }
        tapX = x; tapY = y;
        boolean tracked = dragging, wasScrolling = scrolling; dragging = false;
        float dx = tracked ? x - downX : 0, dy = tracked ? y - downY : 0;
        if (Math.abs(dx) > SLOP || Math.abs(dy) > SLOP) {
            // some cars never send moves, use the lift spot
            if (scrolling) releaseDrawer(x);
            else if (!drawerOpen && picking == null && msgBox.contains(downX, downY) && Math.abs(dy) > 1.2f * Math.abs(dx))
                stepCarousel(dy < 0 ? 1 : -1);
            else if (Math.abs(dx) > SWIPE && Math.abs(dx) > 1.5f * Math.abs(dy) && picking == null && !mapsBox.contains(downX, downY)) {
                if (dx > 0 && !drawerOpen) openDrawer(); else if (dx < 0 && drawerOpen) drawerOpen = false;
            }
            scrolling = false;
            return;
        }
        scrolling = false;
        if (wasScrolling) settleDrawer(0); // a tiny nudge still clicks back onto a column
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (hit.rect.contains(x, y)) {
                if (hit.visual != null) { wobbling.set(hit.visual); wobbleAt = SystemClock.elapsedRealtime(); }
                if (hit.action != null) hit.action.run();
                return;
            }
        }
    }
    private float carouselPos() {
        if (still) return carouselIndex;
        float t = Math.min(1, (SystemClock.elapsedRealtime() - carouselAt) / (float) CAROUSEL_MS);
        float e = t < .5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2; // ease in out cubic
        return carouselFrom + (carouselIndex - carouselFrom) * e;
    }
    private boolean carouselMoving() { return SystemClock.elapsedRealtime() - carouselAt < CAROUSEL_MS + 20; }
    private void stepCarousel(int by) {
        if (data.showing.size() < 2) return;
        carouselFrom = carouselPos(); carouselIndex += by; carouselAt = SystemClock.elapsedRealtime();
    }
    private void windowTouch(float x, float y, int action) {
        if (window == null || windowContent.width() < 1) return;
        window.touch(clamp((x - windowContent.left) / windowContent.width()) * winW, clamp((y - windowContent.top) / windowContent.height()) * winH, action);
    }
    private void mapTouch(float x, float y, int action) {
        if (mapDisplay == null || mapsBox.width() < 1) return;
        mapDisplay.touch(clamp((x - mapsBox.left) / mapsBox.width()) * mapW, clamp((y - mapsBox.top) / mapsBox.height()) * mapH, action);
    }
    private static float clamp(float v) { return Math.max(0, Math.min(1, v)); }
    private void mapClick() {
        if (mapHost != null) mapHost.click((tapX - mapsBox.left) * frameSx / ssaa, (tapY - mapsBox.top) * frameSy / ssaa);
    }

    // projection service flips this, nothing live runs in the phone-side preview
    public synchronized void session(boolean on, boolean wifi) {
        boolean starting = on && !session;
        session = on; overWifi = on && wifi; dirty = true;
        if (starting) {
            // what's already in the shade goes straight onto the car, not just whatever arrives from now on
            org.petal.catback.car.PetalNotificationListener.rescan(context);
            listenVolume(true);
        }
        if (!on) {
            listenVolume(false);
            closeMaps();
            if (window != null) window.close();
            window = null; windowApp = null; windowOpen = false; windowAnim = 0; windowVel = 0;
        }
    }
    private void closeMaps() {
        if (mapHost != null) mapHost.close();
        if (mapDisplay != null) mapDisplay.close();
        mapHost = null; mapDisplay = null; mapSource = null; mapW = mapH = mapDpi = 0;
    }
    private boolean liveMaps() { return mapWant(session ? data.chosenApp(CarData.MAPS) : null) != null; }
    // car app lib first if the app has that and hasn't already said no; else the phone screen, rom build only.
    // no display-host capability and no (or rejected) CAL = nothing live, panel falls back to the notif turn card
    private String mapWant(CarData.App app) {
        if (app == null) return null;
        if (app.carService != null && !calRejected.contains(app.pkg)) return app.pkg + "#car";
        return displayHost ? app.pkg + "#display" : null;
    }

    private void openWindow(CarData.App app) {
        if (!session || !displayHost) return; // sideloaded build has no capability for this, tap does nothing
        if (window != null && !app.pkg.equals(window.pkg)) { window.close(); window = null; }
        if (window == null) { window = new AppDisplay(context, app.pkg); winW = winH = winDpi = 0; }
        windowApp = app; windowOpen = true; island.dismiss();
    }
    private void openDrawer() {
        data.refreshAppsSoon(); drawerOpen = true; island.dismiss();
        drawerFocus = 0; drawerScroll = Float.NaN; drawerScrollVel = 0; // first draw parks the first app in the middle
        stripFocus = false; scrubbing = false; letterLatch = false; fastStreak = 0; pillX = Float.NaN; bubbleAt = -100000;
    }
    // a-z: sorted copy + where each letter starts
    private static char letterOf(String label) {
        String t = label == null ? "" : label.trim();
        if (t.isEmpty()) return '#';
        char ch = java.text.Normalizer.normalize(t.substring(0, 1), java.text.Normalizer.Form.NFD).charAt(0);
        ch = Character.toUpperCase(ch);
        return ch >= 'A' && ch <= 'Z' ? ch : '#';
    }
    private void sortDrawer(List<CarData.App> source) {
        drawerSource = source;
        List<CarData.App> sorted = new ArrayList<>(source);
        sorted.sort((a, b) -> {
            char la = letterOf(a.label), lb = letterOf(b.label);
            if (la != lb) return la == '#' ? -1 : lb == '#' ? 1 : Character.compare(la, lb);
            return String.CASE_INSENSITIVE_ORDER.compare(a.label, b.label);
        });
        drawerApps = sorted;
        drawerLetters.clear();
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            char letter = letterOf(sorted.get(i).label);
            if (drawerLetters.isEmpty() || drawerLetters.get(drawerLetters.size() - 1) != letter) { drawerLetters.add(letter); starts.add(i); }
        }
        letterStart = new int[starts.size()];
        for (int i = 0; i < letterStart.length; i++) letterStart[i] = starts.get(i);
    }
    // letter slot of an app index
    private int letterIndex(int app) {
        int k = 0;
        for (int i = 0; i < letterStart.length; i++) if (letterStart[i] <= app) k = i;
        return k;
    }
    private void jumpToLetter(int k, boolean bubble) {
        if (letterStart.length == 0) return;
        k = Math.max(0, Math.min(letterStart.length - 1, k));
        drawerFocus = letterStart[k];
        drawerTarget = snapFor(drawerFocus / drawerRows);
        if (bubble) { bubbleLetter = drawerLetters.get(k); bubbleAt = SystemClock.elapsedRealtime(); }
    }
    private boolean stripHit(float x, float y) {
        return y >= stripBox.top - 8 && y <= stripBox.bottom + 8 && x >= stripBox.left - 8 && x <= stripBox.right + 8;
    }
    private void scrubTo(float x) {
        if (stripCell <= 0) return;
        int k = (int) Math.floor((x - stripLeft) / stripCell);
        k = Math.max(0, Math.min(drawerLetters.size() - 1, k));
        if (drawerLetters.isEmpty() || (letterIndex(drawerFocus) == k && SystemClock.elapsedRealtime() - bubbleAt < BUBBLE_MS)) return;
        jumpToLetter(k, true);
    }
    // scroll that puts column i dead centre in the row
    private float snapFor(int column) { return 12 + column * (TILE_W + TILE_GAP) + TILE_W / 2 - drawerRow.width() / 2; }
    private int nearestColumn(float scroll) {
        int column = Math.round((scroll - snapFor(0)) / (TILE_W + TILE_GAP));
        return Math.max(0, Math.min(Math.max(0, drawerColumns - 1), column));
    }
    // past either end it gives a third as much, a stretch rather than a wall
    private float rubber(float scroll) {
        float low = snapFor(0), high = snapFor(Math.max(0, drawerColumns - 1));
        if (scroll < low) return low - (low - scroll) * .35f;
        if (scroll > high) return high + (scroll - high) * .35f;
        return scroll;
    }
    private void dragDrawer(float x) {
        long nowMs = SystemClock.elapsedRealtime();
        float dt = Math.max(.008f, (nowMs - drawerMoveAt) / 1000f), inst = -(x - lastX) / dt;
        drawerFlingV = drawerMoved ? drawerFlingV * .4f + inst * .6f : inst;
        drawerMoved = true; drawerMoveAt = nowMs;
        drawerScroll = rubber(downScroll - (x - downX)); drawerScrollVel = 0;
    }
    private void releaseDrawer(float x) {
        long nowMs = SystemClock.elapsedRealtime();
        float v;
        if (!drawerMoved) {
            // car sent no moves, the whole swipe shows up on lift: speed from distance over time
            drawerScroll = rubber(downScroll - (x - downX));
            v = -(x - downX) / Math.max(.06f, (nowMs - drawerDownAt) / 1000f);
        } else {
            dragDrawer(x);
            v = nowMs - drawerMoveAt > 90 ? 0 : drawerFlingV; // held still before letting go = no throw
        }
        // another flick the same way while it was still coasting: stack it, regripping shouldn't cost speed
        if (v != 0 && Math.signum(v) == Math.signum(drawerCarryV)) v += drawerCarryV * .7f;
        drawerCarryV = 0;
        settleDrawer(Math.max(-DRAWER_MAX_V * 1.6f, Math.min(DRAWER_MAX_V * 1.6f, v)));
    }
    // where friction would stop it, then the magnet picks the column nearest that
    private void settleDrawer(float v) {
        if (Float.isNaN(drawerScroll) || drawerColumns == 0) return;
        int column = nearestColumn(drawerScroll + v / DRAWER_FRICTION);
        drawerFocus = Math.min(drawerApps.size() - 1, column * drawerRows);
        drawerTarget = snapFor(column); drawerScrollVel = v;
    }
    private void turnDrawer(int delta) {
        long nowMs = SystemClock.elapsedRealtime();
        int dir = Integer.signum(delta);
        if (stripFocus) { jumpToLetter(letterIndex(drawerFocus) + delta, true); lastDetentAt = nowMs; return; }
        boolean sameWay = dir == lastDetentDir;
        long gap = nowMs - lastDetentAt;
        fastStreak = sameWay && gap < FAST_DETENT_MS ? fastStreak + 1 : 0;
        if (!sameWay) letterLatch = false; // reversing = homing in, back to app by app
        if (Math.abs(delta) >= 2 || fastStreak >= 2) letterLatch = true;
        else if (gap > LETTER_LATCH_MS) letterLatch = false; // a real pause, not a regrip
        lastDetentAt = nowMs; lastDetentDir = dir;
        if (letterLatch) {
            int k = letterIndex(drawerFocus);
            // going back from mid-letter lands on that letter's first app before the one before it
            if (dir < 0 && letterStart.length > 0 && drawerFocus != letterStart[k]) jumpToLetter(k, true);
            else jumpToLetter(k + dir, true);
        } else stepDrawer(Math.max(-3, Math.min(3, delta)));
    }
    private void stepDrawer(int by) {
        if (drawerApps.isEmpty()) return;
        drawerFocus = Math.max(0, Math.min(drawerApps.size() - 1, drawerFocus + by));
        drawerTarget = snapFor(drawerFocus / drawerRows);
    }
    private void openFromDrawer(CarData.App app) {
        if (CarData.slot(app) == null) { openWindow(app); drawerOpen = false; }
        else { data.open(app); closeSheetsSoon(); }
    }
    public void key(int code) {
        dirty = true;
        // volume keys only bring up the blob, they don't wake the rail
        if (code == 24 || code == 25 || code == 164) { volumeKey(code); return; }
        poke();
        if (focusKey(code)) return;
        if (code == 85 || code == 126 || code == 127) data.mediaAction(0);
        else if (code == 87) data.mediaAction(1);
        else if (code == 88) data.mediaAction(-1);
        else if (code == 4 && windowOpen && window != null && picking == null && !drawerOpen) window.back();
        else if (code == 3 || code == 4) synchronized (this) { picking = null; drawerOpen = false; windowOpen = false; island.dismiss(); }
    }
    // knob, dpad, centre/enter and the car's nav/media/phone buttons. true = used up here
    private synchronized boolean focusKey(int code) {
        boolean overlay = overlayStart >= 0;
        switch (code) {
            case 65538: case 65537: case 65540:
                if (!compact) return true; // wide layout has nothing to jump to
                selectPage(code == 65538 ? 0 : code == 65537 ? 1 : 2); focusZone = 0;
                return true;
            case 23: case 66: press(); return true;
            case 19:
                // up from the carousel moves onto the a-z strip; turning there goes letter by letter
                if (drawerOpen && picking == null && drawerColumns > 0) { stripFocus = true; focusShown = true; return true; }
                rotate(-1); return true;
            case 20:
                if (drawerOpen && picking == null) { stripFocus = false; focusShown = true; return true; }
                rotate(1); return true;
            case 21:
                if (compact && !overlay && focusZone == 1) { focusZone = 0; focusShown = true; } else rotate(-1);
                return true;
            case 22:
                if (compact && !overlay && focusZone == 0) press(); else rotate(1);
                return true;
            case 4:
                if (drawerOpen && picking == null && stripFocus) { stripFocus = false; return true; }
                // back steps out of the page to the rail first, then does what back always did
                if (compact && !overlay && focusShown && focusZone == 1) { focusZone = 0; return true; }
                return false;
            default: return false;
        }
    }
    public synchronized void rotate(int delta) {
        dirty = true; poke();
        if (delta == 0) return;
        boolean wasShown = focusShown, overlay = overlayStart >= 0;
        focusShown = true;
        if (!wasShown) return; // first detent just shows where you are
        if (drawerOpen && picking == null) { turnDrawer(delta); return; }
        if (compact && !overlay && focusZone == 0) { selectPage(Math.max(0, Math.min(2, page + Integer.signum(delta)))); return; }
        if (compact && !overlay && page == 2 && data.showing.size() > 1) { stepCarousel(Integer.signum(delta)); return; }
        List<Hit> list = focusables();
        if (!list.isEmpty()) focusIndex = Math.max(0, Math.min(list.size() - 1, focusIndex + delta));
    }
    private void press() {
        if (!focusShown) { focusShown = true; return; }
        if (drawerOpen && picking == null && stripFocus) { stripFocus = false; return; } // drop back onto that letter's app
        if (drawerOpen && picking == null) {
            if (drawerFocus >= 0 && drawerFocus < drawerApps.size()) {
                wobbling.set(drawerFocusRect); wobbleAt = SystemClock.elapsedRealtime();
                openFromDrawer(drawerApps.get(drawerFocus));
            }
            return;
        }
        List<Hit> list = focusables();
        if (compact && overlayStart < 0 && focusZone == 0) {
            focusZone = 1; focusIndex = 0;
            // land on the page, not the clock's drawer dots
            for (int i = 0; i < list.size(); i++) if (RectF.intersects(list.get(i).rect, pageBox)) { focusIndex = i; break; }
            return;
        }
        if (compact && overlayStart < 0 && page == 2) { if (!data.showing.isEmpty()) island.show(data.showing.get(Math.floorMod(carouselIndex, data.showing.size())), true); return; }
        if (list.isEmpty()) return;
        Hit hit = list.get(Math.max(0, Math.min(list.size() - 1, focusIndex)));
        wobbling.set(hit.visual); wobbleAt = SystemClock.elapsedRealtime();
        hit.action.run();
    }
    // last frame's controls in draw order; an open sheet/window/drawer keeps focus inside it
    private List<Hit> focusables() {
        List<Hit> out = new ArrayList<>();
        for (int i = Math.max(0, overlayStart); i < hits.size(); i++) {
            Hit hit = hits.get(i);
            if (hit.focusable() && (overlayStart >= 0 || hit.rail < 0)) out.add(hit);
        }
        return out;
    }
    private void drawFocus(Canvas c) {
        if (drawerOpen && picking == null) {
            if (stripFocus && !Float.isNaN(pillX)) { r2.set(pillX - stripCell / 2 + 2, stripBox.top, pillX + stripCell / 2 - 2, stripBox.bottom); ring(c, r2); }
            else if (!drawerFocusRect.isEmpty()) ring(c, drawerFocusRect);
            return;
        }
        Hit target = null;
        boolean overlay = overlayStart >= 0;
        if (compact && !overlay && focusZone == 0) { for (Hit hit : hits) if (hit.rail == page) target = hit; }
        else if (compact && !overlay && page == 2) { for (Hit hit : hits) if (hit.visual != null && near(hit.visual, msgBox)) target = hit; }
        else {
            List<Hit> list = focusables();
            if (!list.isEmpty()) target = list.get(Math.max(0, Math.min(list.size() - 1, focusIndex)));
        }
        if (target != null) ring(c, target.visual);
    }
    private void ring(Canvas c, RectF around) {
        r.set(around); r.inset(-3, -3);
        float radius = Math.min(Math.min(r.width(), r.height()) / 2, 24);
        c.drawRoundRect(r, radius, radius, focusGlow);
        c.drawRoundRect(r, radius, radius, focusCore);
    }
    private void selectPage(int p) {
        if (p == page) return;
        pageFrom = page; page = p; pageAt = SystemClock.elapsedRealtime();
        if (focusZone == 1) focusIndex = 0;
    }
    private float pageProgress() { return still ? 1 : Math.min(1, (SystemClock.elapsedRealtime() - pageAt) / (float) PAGE_MS); }
    private boolean pageMoving() { return SystemClock.elapsedRealtime() - pageAt < PAGE_MS + 20; }

    void hit(RectF rect, Runnable action) { pending.add(new Hit(rect, null, action)); }
    // tap target, visual wobbles, null action = just the boing
    void hit(RectF rect, RectF visual, Runnable action) { pending.add(new Hit(rect, visual, action)); }
    private float wobbleTime() { return (SystemClock.elapsedRealtime() - wobbleAt) / 1000f; }
    // scales around box if it's the wobbling one, restoreToCount after
    private int wobble(Canvas c, RectF box) {
        int saved = c.save();
        float t = wobbleTime();
        if (!still && t < WOBBLE_SECONDS && near(box, wobbling)) {
            // big stuff moves less, buttons give a little. a nudge you feel, not a bounce you watch
            float amplitude = Math.max(.012f, Math.min(.04f, 4f / Math.max(box.width(), box.height())));
            float k = 1 - amplitude * (float) (Math.exp(-t * 11) * Math.cos(t * 26));
            c.scale(k, k, box.centerX(), box.centerY());
        }
        return saved;
    }
    private static boolean near(RectF a, RectF b) {
        return Math.abs(a.left - b.left) < .5f && Math.abs(a.top - b.top) < .5f
                && Math.abs(a.right - b.right) < .5f && Math.abs(a.bottom - b.bottom) < .5f;
    }

    // frame
    // ui in the visible rect, car margins black
    public void draw(Canvas canvas, int left, int top, int w, int h, float layoutWidth) {
        draw(canvas, left, top, w, h, layoutWidth, 480, false);
    }
    public void draw(Canvas canvas, int left, int top, int w, int h, float layoutWidth, float layoutHeight, boolean compactLayout) {
        draw(canvas, left, top, w, h, layoutWidth, layoutHeight, compactLayout, 1);
    }
    // supersample = how many canvas pixels per output pixel (Supersampler draws at 2)
    public synchronized void draw(Canvas canvas, int left, int top, int w, int h, float layoutWidth, float layoutHeight,
                                  boolean compactLayout, int supersample) {
        width = layoutWidth; H = layoutHeight; compact = compactLayout; ssaa = Math.max(1, supersample); dirty = false;
        boolean slim = railWantsSlim();
        railSlim = slim;
        stepRail();
        island.redundant = !compact ? null : page == 0 ? CarData.Signal.Kind.NAVIGATION : page == 1 ? CarData.Signal.Kind.MEDIA : null;
        if (closeSheetsAt != 0 && SystemClock.elapsedRealtime() >= closeSheetsAt) { closeSheetsAt = 0; picking = null; drawerOpen = false; }
        float sx = w / layoutWidth, sy = h / H, now = (SystemClock.elapsedRealtime() - start) / 1000f;
        island.still = still();
        island.step(data, now);
        canvas.drawColor(Color.BLACK);
        boolean hardware = canvas.isHardwareAccelerated();
        pending.clear();
        frameCanvasW = canvas.getWidth(); frameCanvasH = canvas.getHeight(); frameLeft = left; frameTop = top; frameSx = sx; frameSy = sy;
        if (!hardware) { eqVisible = false; railEqVisible = false; }
        if (hardware) {
            sigCw = canvas.getWidth(); sigCh = canvas.getHeight(); sigLeft = left; sigTop = top; sigW = w; sigH = h; sigLayout = layoutWidth;
            long signature = signature(sigCw, sigCh, left, top, w, h, layoutWidth);
            if (signature != sceneSignature) {
                sceneSignature = signature; sceneVersion++;
                eqVisible = false; railEqVisible = false; // media() + rail() re-add the eqs while recording
                scene.setPosition(0, 0, canvas.getWidth(), canvas.getHeight());
                Canvas record = scene.beginRecording(canvas.getWidth(), canvas.getHeight());
                try { layoutSpace(record, left, top, w, h, sx, sy); drawScene(record, now); }
                finally { scene.endRecording(); }
                sceneHits.clear(); sceneHits.addAll(pending);
            } else { pending.addAll(sceneHits); }
            canvas.drawRenderNode(scene);
        }
        int saved = canvas.save();
        layoutSpace(canvas, left, top, w, h, sx, sy);
        if (!hardware) drawScene(canvas, now);
        drawLiveMaps(canvas);
        if (eqVisible) equalizer(canvas, eqX, eqY, eqH, eqPlaying, still ? 0 : now, eqColor);
        if (railEqVisible) equalizer(canvas, railEqX, railEqY, 14, true, still ? 0 : now, railEqColor);
        // where this frame's overlay controls start, so the knob stays inside whatever's open
        int overlay = windowOpen ? pending.size() : -1;
        drawWindow(canvas);
        // the ring sits under the island so an incoming card covers it; the drawer and picker sit above the
        // island, so a ring inside them still has to go on top
        boolean ringOnTop = drawerOpen || picking != null;
        if (focusShown && !ringOnTop) drawFocus(canvas);
        drawIsland(canvas, hardware ? scene : null, left, top, sx, sy, now);
        if (drawerOpen) overlay = pending.size();
        drawDrawer(canvas);
        if (picking != null) { overlay = pending.size(); drawPicker(canvas); }
        drawVolume(canvas);
        hits.clear(); hits.addAll(pending);
        if ((overlay >= 0) != (overlayStart >= 0)) {
            if (overlay >= 0) { savedFocus = focusIndex; focusIndex = 0; } else focusIndex = savedFocus;
        }
        overlayStart = overlay;
        if (focusShown && ringOnTop) drawFocus(canvas);
        canvas.restoreToCount(saved);
        animating = !hardware || stillMoving();
    }
    // anything that'll look different next frame without any new input
    private boolean stillMoving() {
        if (island.busy() || closeSheetsAt != 0 || dragging) return true;
        if (liveMaps() || window != null || windowAnim > .002f) return true; // live app frames
        if (!still && ((eqVisible && eqPlaying) || railEqVisible)) return true;
        if (!still && wobbleTime() < WOBBLE_SECONDS + .05f) return true;
        if (carouselMoving() || pageMoving() || railMoving() || (compact && coverMoving())) return true;
        if (volScrub || volume.visible() || volume.animating(SystemClock.elapsedRealtime())
                || SystemClock.elapsedRealtime() - volAt < VOLUME_SHOW_MS + 50) return true;
        float open = drawerOpen ? 1 : 0;
        if (drawerOpen && !Float.isNaN(drawerScroll) && (Math.abs(drawerScrollVel) > .5f || Math.abs(drawerTarget - drawerScroll) > .2f)) return true;
        if (drawerOpen && (Math.abs(pillVel) > .5f || SystemClock.elapsedRealtime() - bubbleAt < BUBBLE_MS + 50)) return true;
        return Math.abs(drawer - open) > .002f || Math.abs(drawerVel) > .01f;
    }
    // what changes the static scene, cheap and no allocs
    private long signature(int canvasW, int canvasH, int left, int top, int w, int h, float layoutWidth) {
        CarData.Media media = data.media; CarData.Navigation nav = data.navigation; CarData.Signal message = data.latestMessage;
        long minute = System.currentTimeMillis() / 60000;
        if (SystemClock.elapsedRealtime() - batteryAt > 30000) {
            batteryAt = SystemClock.elapsedRealtime();
            batteryLevel = context.getSystemService(BatteryManager.class).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        }
        long h1 = 17;
        h1 = h1 * 31 + canvasW; h1 = h1 * 31 + canvasH; h1 = h1 * 31 + left; h1 = h1 * 31 + top;
        h1 = h1 * 31 + w; h1 = h1 * 31 + h; h1 = h1 * 31 + Float.floatToIntBits(layoutWidth);
        h1 = h1 * 31 + minute; h1 = h1 * 31 + batteryLevel;
        h1 = h1 * 31 + System.identityHashCode(media); h1 = h1 * 31 + System.identityHashCode(nav);
        h1 = h1 * 31 + System.identityHashCode(message);
        h1 = h1 * 31 + System.identityHashCode(data.showing); h1 = h1 * 31 + carouselIndex;
        if (carouselMoving()) h1 = h1 * 31 + SystemClock.elapsedRealtime();
        h1 = h1 * 31 + (media != null ? media.positionNow() / 1000 : -1);
        h1 = h1 * 31 + String.valueOf(data.chosen(CarData.MAPS)).hashCode(); h1 = h1 * 31 + String.valueOf(data.chosen(CarData.MEDIA)).hashCode();
        h1 = h1 * 31 + (data.listenerConnected ? 1 : 0) + (backdrop.showingWallpaper() ? 2 : 0) + (liveMaps() ? 4 : 0);
        h1 = h1 * 31 + baker.version(); h1 = h1 * 31 + backdrop.version(); h1 = h1 * 31 + (overWifi ? 1 : 0);
        h1 = h1 * 31 + Float.floatToIntBits(H) + (compact ? 1 : 0); h1 = h1 * 31 + page;
        // flips by itself after the idle timeout, so this is what wakes the encoder for the slim animation
        h1 = h1 * 31 + (railWantsSlim() ? 1 : 0);
        if (railMoving()) h1 = h1 * 31 + SystemClock.elapsedRealtime();
        if (compact && coverMoving()) h1 = h1 * 31 + SystemClock.elapsedRealtime();
        if (pageMoving()) h1 = h1 * 31 + SystemClock.elapsedRealtime();
        if (wobbleTime() < WOBBLE_SECONDS + .05f) h1 = h1 * 31 + SystemClock.elapsedRealtime();
        return h1;
    }
    private void layoutSpace(Canvas canvas, int left, int top, int w, int h, float sx, float sy) {
        canvas.clipRect(left, top, left + w, top + h);
        canvas.translate(left, top); canvas.scale(sx, sy);
    }

    // new surface = old recording is trash
    public synchronized void invalidate() { sceneSignature = Long.MIN_VALUE; dirty = true; }

    // encoder asks before every frame. true = draw; false = it would match the last frame pixel for pixel
    public synchronized boolean needsFrame() {
        if (dirty || animating || data.hasSignal()) return true;
        return signature(sigCw, sigCh, sigLeft, sigTop, sigW, sigH, sigLayout) != sceneSignature;
    }

    // phone ui asks if the real wallpaper is showing
    public boolean wallpaperReadable() { return backdrop.wallpaperReadable(); }

    // static panel glass baked once, no per-frame shader
    private void panel(Canvas c, String key, Silk silk, RectF box, float radius, int tint) {
        if (c.isHardwareAccelerated() && baker.available()) {
            final float layoutWidth = width;
            final float layoutHeight = H;
            // rail resizing = new box every frame. baking each one would queue ~20 full bakes; stretch the last
            // one instead (a few units off for 300ms) and bake once it's settled
            boolean moving = railMoving();
            if (!moving) baker.request(new GlassBaker.Spec(key, frameCanvasW, frameCanvasH, frameLeft, frameTop, frameSx, frameSy,
                    layoutWidth, layoutHeight, box, radius, backdrop.version(), PANEL_GLASS),
                    canvas -> backdrop.draw(canvas, layoutWidth, layoutHeight, 0f));
            Bitmap glassImage = baker.get(key);
            float ss = GlassBaker.supersample(frameSy);
            if (glassImage != null && (moving || Math.abs(glassImage.getWidth() - box.width() * frameSx * ss) <= 1.5f * ss)
                    && (moving || Math.abs(glassImage.getHeight() - box.height() * frameSy * ss) <= 1.5f * ss)) {
                c.drawBitmap(glassImage, null, box, image);
                return;
            }
        }
        // bake not there yet (or no hw canvas), silk over frost
        backdrop.frost(c, box, radius, width, H);
        silk.draw(c, box, radius, tint, ACCENT, true);
    }

    private void drawScene(Canvas c, float now) {
        mapsBox.setEmpty(); msgBox.setEmpty(); // only what this layout/page actually shows takes touches
        mapsShift = 0; mapsAlpha = 1; mapsLeaving = false;
        ambient = 0;
        if (compact) { drawCompact(c, now); return; }
        backdrop.draw(c, width, H, 0f); // animated aurora would redraw every frame, nope
        status(c, 20);
        float x0 = 20, x1 = width - 20, gap = 12, navW = (x1 - x0 - gap) * 1.45f / 2.45f;
        r.set(x0, BAR_TOP, x0 + navW, BAR_BOTTOM); RectF mapsBox = new RectF(r);
        float sideL = x0 + navW + gap, mediaH = (BAR_BOTTOM - BAR_TOP - gap) * 1.35f / 2.35f;
        RectF mediaBox = new RectF(sideL, BAR_TOP, x1, BAR_TOP + mediaH);
        RectF messageBox = new RectF(sideL, BAR_TOP + mediaH + gap, x1, BAR_BOTTOM);
        this.mapsBox.set(mapsBox);
        // live map lives on top of the scene, wobbling the panel under it would just look broken
        if (liveMaps()) maps(c, mapsBox);
        else { int s0 = wobble(c, mapsBox); hit(mapsBox, mapsBox, null); maps(c, mapsBox); c.restoreToCount(s0); }
        int saved;
        saved = wobble(c, mediaBox); hit(mediaBox, mediaBox, null); media(c, mediaBox, now); c.restoreToCount(saved);
        saved = wobble(c, messageBox); messages(c, messageBox); c.restoreToCount(saved);
    }

    private void status(Canvas c, float x0) {
        String clock = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        label(c, clock, x0, 33, 20, semibold, TEXT);
        // 3x3 dots by the clock, same drawer as the swipe
        style(text, semibold, 20);
        float ax = x0 + text.measureText(clock) + 18, ay = 17;
        RectF visual = new RectF(ax - 4, ay - 4, ax + 20, ay + 20);
        int saved = wobble(c, visual);
        paint.setColor(TEXT2);
        for (int i = 0; i < 9; i++) c.drawCircle(ax + 2 + (i % 3) * 6, ay + 2 + (i / 3) * 6, 1.8f, paint);
        c.restoreToCount(saved);
        r.set(ax - 12, ay - 12, ax + 28, ay + 28); hit(r, visual, this::openDrawer);
        String battery = batteryLevel > 0 ? batteryLevel + "%" : "";
        style(text, medium, 15);
        float bw = text.measureText(battery);
        label(c, battery, width - 20 - bw, 31, 15, medium, TEXT2);
        if (!battery.isEmpty()) {
            RectF pill = new RectF(width - 24 - bw, 14, width - 16, 38);
            r.set(Math.min(pill.left, width - 64), 4, width - 4, 46); hit(r, pill, this::batteryTap);
        }
        if (overWifi) icon(c, R.drawable.car_wifi, width - 20 - bw - 26, 16, 18, TEXT2);
    }

    // compact: rail + one page. the island ticks over whatever page you're not on
    private void drawCompact(Canvas c, float now) {
        backdrop.draw(c, width, H, 0f);
        float t = pageProgress();
        // music page with art = the cover is the whole screen. switching pages on the rail crossfades it back
        // to wallpaper + panels in step with the page swing
        float from = pageFrom == 1 ? 1 : 0, to = page == 1 ? 1 : 0, onMusic = t >= 1 ? to : from + (to - from) * smooth(t);
        ambient = onMusic * coverShown();
        if (onMusic > .01f) drawCover(c, onMusic);
        float m = 10, railW = RAIL_SLIM + (RAIL_W - RAIL_SLIM) * railK(), x0 = m + railW + 10;
        railBox.set(m, m, m + railW, H - m);
        rail(c, railBox);
        status(c, x0);
        pageBox.set(x0, 48, width - m, H - m);
        RectF box = new RectF(pageBox);
        if (t >= 1) { if (page == 0) mapsBox.set(box); drawPage(c, page, box, now); return; }
        // full swing: the old page travels all the way out one way while the new one travels all the way in
        // from the other, both fading completely. down the rail = pages move up, back up = pages move down
        float e = t < .5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
        float dir = page > pageFrom ? 1 : -1, travel = box.height() + 16;
        // quick fades so neither page is really visible at the cut lines: the old one is gone by 40% of the
        // swing, the new one only shows from 30% on, when it's mostly past the edge
        float out = 1 - smooth(t / .4f), in = smooth((t - .3f) / .7f);
        float top = box.top - 10, bottom = H;
        int clip = c.save();
        c.clipRect(box.left - 12, top, box.right + 12, bottom); // tucks under the clock row, never over it
        r2.set(box.left - 12, top, box.right + 12, bottom);
        c.saveLayer(r2, null);
        slide(c, pageFrom, box, -dir * travel * e, out, now, true);
        slide(c, page, box, dir * travel * (1 - e), in, now, false);
        // feather the cuts: soft over the gap above and below a resting page, so nothing pops when it lands
        if (featherTop != top || featherBottom != bottom || featherBoxTop != box.top || featherBoxBottom != box.bottom) {
            featherTop = top; featherBottom = bottom; featherBoxTop = box.top; featherBoxBottom = box.bottom;
            float span = bottom - top;
            feather.setShader(new android.graphics.LinearGradient(0, top, 0, bottom,
                    new int[]{0x00000000, 0xFF000000, 0xFF000000, 0x00000000},
                    new float[]{0f, (box.top - top) / span, (box.bottom - top) / span, 1f}, Shader.TileMode.CLAMP));
        }
        c.drawRect(r2, feather);
        c.restoreToCount(clip);
    }
    private static float smooth(float x) { x = Math.max(0, Math.min(1, x)); return x * x * (3 - 2 * x); }
    private void drawPage(Canvas c, int which, RectF box, float now) {
        if (which == 0) {
            if (liveMaps()) maps(c, box);
            else { int s0 = wobble(c, box); hit(box, box, null); maps(c, box); c.restoreToCount(s0); }
        } else if (which == 1) {
            int s1 = wobble(c, box); hit(box, box, null); mediaPage(c, box, now); c.restoreToCount(s1);
        } else {
            int s2 = wobble(c, box); messages(c, box); c.restoreToCount(s2);
        }
    }
    // one page mid-swing; the leaving one keeps no tap targets
    private void slide(Canvas c, int which, RectF box, float dy, float alpha, float now, boolean leaving) {
        if (alpha <= .01f) return;
        int mark = pending.size(), saved = c.save();
        c.translate(0, dy);
        r.set(box); r.inset(-12, -12);
        c.saveLayerAlpha(r, Math.round(255 * alpha));
        if (which == 0) { mapsBox.set(box); mapsShift = dy; mapsAlpha = alpha; mapsLeaving = leaving; }
        drawPage(c, which, box, now);
        c.restoreToCount(saved);
        if (leaving) {
            while (pending.size() > mark) pending.remove(pending.size() - 1);
            if (which == 2) msgBox.setEmpty();
        }
    }
    private void rail(Canvas c, RectF box) {
        float a = ambient;
        if (a < .99f) {
            r.set(box); r.inset(-2, -2);
            int s0 = a > .01f ? c.saveLayerAlpha(r, Math.round(255 * (1 - a))) : c.save();
            panel(c, "rail", railSilk, box, 26, PANEL);
            c.restoreToCount(s0);
        }
        // over the cover it's just a darker wash, the glass would look like a box pasted on top
        if (a > .01f) fill(c, box, 26, Color.argb(Math.round(0x59 * a), 0, 0, 0));
        float inset = 6, gap = 6, itemH = (box.height() - 2 * inset - 2 * gap) / 3;
        for (int i = 0; i < 3; i++) {
            float top = box.top + inset + i * (itemH + gap);
            RectF item = new RectF(box.left + inset, top, box.right - inset, top + itemH);
            int saved = wobble(c, item);
            boolean on = i == page;
            if (on) fill(c, item, 20, mix(ACCENT_CONTAINER, BLEND_ON, a));
            railItem(c, i, item, on);
            c.restoreToCount(saved);
            final int target = i;
            Hit hit = new Hit(item, item, () -> selectPage(target)); hit.rail = i; pending.add(hit);
        }
    }
    // icon that carries live state + a short label: next turn, eq, unread count. slim rail drops the labels
    // and drifts the icon down to the middle
    private void railItem(Canvas c, int i, RectF item, boolean on) {
        float k = Math.max(0, Math.min(1, railK()));
        float cx = item.centerX(), iconY = item.centerY() - 30 + 14 * (1 - k), labelY = item.bottom - 16;
        int tint = on ? mix(ACCENT, TEXT, ambient) : TEXT;
        String label;
        if (i == 0) {
            CarData.Navigation nav = data.navigation;
            if (nav != null && nav.maneuver != null) { r.set(cx - 20, iconY - 2, cx + 20, iconY + 38); bitmapFit(c, nav.maneuver, r, 0); }
            else icon(c, R.drawable.car_map, cx - 16, iconY + 2, 32, tint);
            label = nav != null && nav.detail != null && !nav.detail.isEmpty() ? nav.detail : "Maps";
        } else if (i == 1) {
            CarData.Media m = data.media;
            icon(c, R.drawable.car_music, cx - 16, iconY + 2, 32, tint);
            label = "Music";
            if (m != null && m.playing) {
                label = null;
                railEqX = cx - 13.5f; railEqY = labelY - 14; railEqVisible = true; railEqColor = mix(ACCENT, TEXT, ambient);
                if (!c.isHardwareAccelerated()) { railEqVisible = false; equalizer(c, railEqX, railEqY, 14, true, 0, railEqColor); }
            }
        } else {
            icon(c, R.drawable.car_message, cx - 16, iconY + 2, 32, tint);
            int n = data.showing.size();
            if (n > 0) {
                paint.setColor(ACCENT); c.drawCircle(cx + 16, iconY + 4, 12, paint);
                centered(c, n > 9 ? "9+" : String.valueOf(n), cx + 16, iconY + 9, 12, bold, ON_ACCENT, 24);
            }
            label = "Alerts";
        }
        if (label != null && k > .02f) {
            int color = on ? TEXT : TEXT2;
            centered(c, label, cx, labelY, 15, semibold, (color & 0x00FFFFFF) | (Math.round(Color.alpha(color) * k) << 24), item.width() - 8);
        }
    }
    // music page (rail layout): one layout whatever's playing. title, seek and controls bottom centre over the cover
    // when there is one (drawCover puts it behind the whole screen), over the plain wallpaper when there isn't
    private void mediaPage(Canvas c, RectF box, float now) {
        CarData.App app = data.chosenApp(CarData.MEDIA);
        if (app != null) { mediaCover(c, box, app, now); return; }
        panel(c, "media", mediaSilk, box, 28, PANEL);
        empty(c, box, R.drawable.car_music, "Choose a media app", "Music, podcasts and audiobooks play here.",
                "Select media app", () -> pick(CarData.MEDIA));
    }
    // over the full-screen cover: no panel, no art tile. title, artist, seek and controls stacked bottom centre,
    // translucent white so they sit in the picture rather than on it; app chip tucked top right
    private void mediaCover(Canvas c, RectF box, CarData.App app, float now) {
        CarData.Media m = data.media;
        eqVisible = false; // the rail's music item already has one
        boolean playing = m != null && m.playing;
        float cx = box.centerX(), cw = Math.min(box.width() - 32, 560), cl = cx - cw / 2, cr = cx + cw / 2;
        float cy = box.bottom - 38, seekY = cy - 34 - 24;
        String title = m == null || m.title.isEmpty() ? "Not playing" : m.title;
        String artist = m == null ? "Tap play to start " + app.label : m.artist;
        // a soft shadow under the type so it holds on any cover, the scrim alone isn't enough on white art
        text.setShadowLayer(10, 0, 1, 0x99000000);
        centered(c, title, cx, seekY - 46, 26, bold, TEXT, cw);
        centered(c, artist, cx, seekY - 20, 18, medium, 0xE6FFFFFF, cw);
        long position = m == null ? 0 : m.positionNow(), duration = m == null ? 0 : m.duration;
        // times either side of the bar, one row instead of two
        String now0 = duration > 0 ? time(position) : "", total = duration > 0 ? time(duration) : "";
        style(text, medium, 15);
        float lw = text.measureText(now0), rw = text.measureText(total);
        label(c, now0, cl, seekY + 8, 15, medium, 0xE6FFFFFF);
        label(c, total, cr - rw, seekY + 8, 15, medium, 0xE6FFFFFF);
        text.clearShadowLayer();
        float bl = cl + (duration > 0 ? lw + 14 : 0), br = cr - (duration > 0 ? rw + 14 : 0);
        r.set(bl, seekY, br, seekY + 6); fill(c, r, 3, 0x4DFFFFFF);
        if (duration > 0) {
            float k = Math.min(1f, position / (float) duration);
            r2.set(r.left, r.top, r.left + r.width() * k, r.bottom); fill(c, r2, 3, TEXT);
            paint.setColor(Color.WHITE); c.drawCircle(r2.right, r.centerY(), 7, paint);
        }
        float spread = Math.max(64, Math.min(100, (cw - 56) / 2));
        control(c, cx - spread, cy, 28, R.drawable.car_prev, false, true, () -> data.mediaAction(-1));
        control(c, cx, cy, 34, playing ? R.drawable.car_pause : R.drawable.car_play, true, true, () -> data.mediaAction(0));
        control(c, cx + spread, cy, 28, R.drawable.car_next, false, true, () -> data.mediaAction(1));
        // same chip as the panel, right-aligned at the top of the page
        style(text, semibold, 14);
        float chipW = 6 + 16 + 8 + text.measureText(app.label) + 4 + 14 + 8;
        appChipInline(c, app, box.right - 6 - chipW, box.top + 2, () -> pick(CarData.MEDIA));
    }
    // the cover behind the music page. skips usually send the new title first and the art a beat later, so a
    // missing cover is held for a moment before it counts as gone, and covers crossfade instead of cutting
    private static final long COVER_HOLD_MS = 2000, COVER_FADE_MS = 350;
    private Bitmap coverNow, coverPrev;
    private long coverChangedAt = -100000, coverLostAt = -1;
    private void updateCover() {
        CarData.Media m = data.media;
        boolean app = data.chosenApp(CarData.MEDIA) != null;
        Bitmap want = !app || m == null ? null : m.cover;
        long nowMs = SystemClock.elapsedRealtime();
        if (want == null && coverNow != null) {
            if (coverLostAt < 0) coverLostAt = nowMs;
            if (app && nowMs - coverLostAt < COVER_HOLD_MS) return;
        }
        coverLostAt = -1;
        if (want == coverNow) return;
        // landed mid-fade (an app sending a thumbnail then the full-size art, or skipping fast): swap the incoming
        // layer and let the same fade carry on. restarting it would drop the half-faded old cover in one frame
        if (coverPrev != null && coverNow != null && want != null && coverFade() < 1) { coverNow = want; return; }
        coverPrev = coverNow; coverNow = want; coverChangedAt = nowMs;
    }
    private float coverFade() {
        return still ? 1 : smooth((SystemClock.elapsedRealtime() - coverChangedAt) / (float) COVER_FADE_MS);
    }
    private boolean coverMoving() { return coverLostAt >= 0 || SystemClock.elapsedRealtime() - coverChangedAt < COVER_FADE_MS + 20; }
    // 0 wallpaper, 1 cover, in between while one fades in or out
    private float coverShown() {
        updateCover();
        float f = coverFade(), was = coverPrev != null ? 1 : 0, is = coverNow != null ? 1 : 0;
        return was + (is - was) * f;
    }
    // the cover itself, sharp, cropped to fill, the old one under the new while they swap. only darkened at the
    // top (clock) and bottom (controls)
    private void drawCover(Canvas c, float onMusic) {
        float f = coverFade(), shown = coverShown();
        if (shown <= .01f) return;
        if (coverPrev != null && f < 1) coverBitmap(c, coverPrev, onMusic * (coverNow != null ? 1 : 1 - f));
        if (coverNow != null) coverBitmap(c, coverNow, onMusic * f);
        if (coverTop == null || coverShadeH != H) {
            coverShadeH = H;
            coverTop = new android.graphics.LinearGradient(0, 0, 0, 96, 0x80000000, 0x00000000, Shader.TileMode.CLAMP);
            coverBottom = new android.graphics.LinearGradient(0, H * .4f, 0, H, 0x00000000, 0xD9000000, Shader.TileMode.CLAMP);
        }
        int alpha = Math.round(255 * onMusic * shown);
        paint.setShader(coverTop); paint.setAlpha(alpha); c.drawRect(0, 0, width, 96, paint);
        paint.setShader(coverBottom); paint.setAlpha(alpha); c.drawRect(0, H * .4f, width, H, paint);
        paint.setShader(null); paint.setAlpha(255);
    }
    private void coverBitmap(Canvas c, Bitmap cover, float alpha) {
        if (alpha <= .01f) return;
        float k = Math.max(width / cover.getWidth(), H / cover.getHeight()), w = cover.getWidth() * k, h = cover.getHeight() * k;
        r.set((width - w) / 2, (H - h) / 2, (width + w) / 2, (H + h) / 2);
        coverPaint.setAlpha(Math.round(255 * alpha));
        c.drawBitmap(cover, null, r, coverPaint);
    }
    // a over b by t, per channel
    private static int mix(int a, int b, float t) {
        if (t <= 0) return a;
        if (t >= 1) return b;
        return Color.argb(Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    // maps
    private void maps(Canvas c, RectF box) {
        panel(c, "maps", navSilk, box, 24, PANEL);
        CarData.App app = data.chosenApp(CarData.MAPS);
        if (app != null && liveMaps()) {
            centered(c, "Opening " + app.label + "…", box.centerX(), box.centerY(), 16, semibold, TEXT2, box.width() - 40);
            return;
        }
        if (app == null) {
            empty(c, box, R.drawable.car_map, "Choose a maps app",
                    "Directions from your phone show up here, with turns in the island.", "Select maps app", () -> pick(CarData.MAPS));
            return;
        }
        CarData.Navigation nav = data.navigation;
        if (compact) mapsLarge(c, box, app, nav);
        else if (nav == null) {
            float cy = box.centerY();
            bitmapRound(c, app.icon, box.centerX() - 28, cy - 78, 56, 16);
            centered(c, app.label + " is ready", box.centerX(), cy + 4, 18, semibold, TEXT, box.width() - 40);
            centered(c, "Start a route in " + app.label + " on your phone.", box.centerX(), cy + 30, 14, regular, TEXT2, box.width() - 40);
            centered(c, "Turn-by-turn directions will appear here.", box.centerX(), cy + 50, 14, regular, TEXT2, box.width() - 40);
        } else {
            float tile = 76;
            r.set(box.left + 20, box.top + 20, box.left + 20 + tile, box.top + 20 + tile);
            fill(c, r, 22, ACCENT_CONTAINER);
            if (nav.maneuver != null) bitmapFit(c, nav.maneuver, r, 12);
            else icon(c, R.drawable.car_turn, r.left + 18, r.top + 18, 40, ACCENT);
            float tx = r.right + 16, maxW = box.right - 20 - tx;
            label(c, nav.instruction, tx, box.top + 52, 24, bold, TEXT, maxW);
            label(c, nav.detail, tx, box.top + 80, 15, regular, TEXT2, maxW);
            if (!nav.eta.isEmpty()) label(c, nav.eta, box.left + 20, box.bottom - 24, 18, semibold, TEXT, box.width() - 200);
        }
        appChip(c, app, box.right - 14, box.bottom - 14, () -> pick(CarData.MAPS));
    }
    // rail layout's full maps page: the turn is the whole point, so it gets the size, and wraps rather than cuts off
    private void mapsLarge(Canvas c, RectF box, CarData.App app, CarData.Navigation nav) {
        if (nav == null) {
            float maxW = Math.min(420, box.width() - 48), top = box.centerY() - 86;
            bitmapRound(c, app.icon, box.centerX() - 32, top, 64, 18);
            centered(c, app.label + " is ready", box.centerX(), top + 100, 22, semibold, TEXT, maxW);
            wrapped(c, "Start a route in " + app.label + " on your phone. Turn-by-turn directions will appear here.",
                    box.centerX(), top + 114, 16, regular, TEXT2, maxW, 3);
            return;
        }
        float pad = 22, tile = 96;
        r.set(box.left + pad, box.top + pad, box.left + pad + tile, box.top + pad + tile);
        fill(c, r, 26, ACCENT_CONTAINER);
        if (nav.maneuver != null) bitmapFit(c, nav.maneuver, r, 14);
        else icon(c, R.drawable.car_turn, r.left + 24, r.top + 24, 48, ACCENT);
        float tx = r.right + 20, maxW = box.right - pad - tx;
        float used = wrapped(c, nav.instruction, tx, box.top + pad - 4, 28, bold, TEXT, maxW, 2, false);
        label(c, nav.detail, tx, box.top + pad + Math.max(used, 34) + 22, 18, medium, TEXT2, maxW);
        // eta sits left of the app chip; the chip's ~180 wide at most
        if (!nav.eta.isEmpty()) label(c, nav.eta, box.left + pad, box.bottom - 24, 20, semibold, TEXT, box.width() - pad - 200);
    }

    // media
    private void media(Canvas c, RectF box, float now) {
        panel(c, "media", mediaSilk, box, 28, PANEL);
        CarData.App app = data.chosenApp(CarData.MEDIA);
        if (app == null) {
            empty(c, box, R.drawable.car_music, "Choose a media app", "Music, podcasts and audiobooks play here.",
                    "Select media app", () -> pick(CarData.MEDIA));
            return;
        }
        CarData.Media m = data.media;
        float pad = 16, art = 64;
        r.set(box.left + pad, box.top + pad, box.left + pad + art, box.top + pad + art);
        if (m != null && m.art != null) bitmapRound(c, m.art, r.left, r.top, art, 14);
        else { fill(c, r, 14, PLACEHOLDER); bitmapRound(c, app.icon, r.left + 16, r.top + 16, 32, 9); }
        float tx = r.right + 14, maxW = box.right - pad - tx - 30;
        String title = m == null || m.title.isEmpty() ? "Not playing" : m.title;
        String artist = m == null ? "Tap play to start " + app.label : m.artist;
        label(c, title, tx, box.top + pad + 18, 17, semibold, TEXT, maxW);
        label(c, artist, tx, box.top + pad + 38, 14, regular, TEXT2, maxW);
        appChipInline(c, app, tx, box.top + pad + 46, () -> pick(CarData.MEDIA));
        boolean playing = m != null && m.playing;
        eqX = box.right - pad - 26; eqY = box.top + pad + 8; eqH = 18; eqPlaying = playing; eqVisible = true;
        eqColor = ACCENT;
        if (!c.isHardwareAccelerated()) { eqVisible = false; equalizer(c, eqX, eqY, eqH, playing, still ? 0 : now, eqColor); }

        // seek + times
        float seekY = box.bottom - 102;
        long position = m == null ? 0 : m.positionNow(), duration = m == null ? 0 : m.duration;
        r.set(box.left + pad, seekY, box.right - pad, seekY + 6); fill(c, r, 3, 0x1FFFFFFF);
        if (duration > 0) {
            float k = Math.min(1f, position / (float) duration);
            r2.set(r.left, r.top, r.left + r.width() * k, r.bottom); fill(c, r2, 3, ACCENT);
            paint.setColor(Color.WHITE); c.drawCircle(r2.right, r.centerY(), 7, paint);
            label(c, time(position), box.left + pad, seekY + 24, 14, regular, TEXT2);
            String total = time(duration);
            style(text, regular, 14);
            label(c, total, box.right - pad - text.measureText(total), seekY + 24, 14, regular, TEXT2);
        }
        // controls
        float cy = box.bottom - 44, cx = box.centerX();
        control(c, cx - 70, cy, 24, R.drawable.car_prev, false, () -> data.mediaAction(-1));
        control(c, cx, cy, 28, playing ? R.drawable.car_pause : R.drawable.car_play, true, () -> data.mediaAction(0));
        control(c, cx + 70, cy, 24, R.drawable.car_next, false, () -> data.mediaAction(1));
    }
    private void control(Canvas c, float cx, float cy, float radius, int icon, boolean primary, Runnable action) {
        control(c, cx, cy, radius, icon, primary, false, action);
    }
    // blend = over the music page's cover: translucent white discs that let the cover show through
    private void control(Canvas c, float cx, float cy, float radius, int icon, boolean primary, boolean blend, Runnable action) {
        r.set(cx - radius, cy - radius, cx + radius, cy + radius);
        RectF visual = new RectF(r);
        int saved = wobble(c, visual);
        if (blend) { paint.setColor(primary ? 0x4DFFFFFF : 0x1FFFFFFF); c.drawCircle(cx, cy, radius, paint); }
        else if (primary) { paint.setColor(ACCENT); c.drawCircle(cx, cy, radius, paint); }
        float size = Math.max(26, radius * .85f);
        icon(c, icon, cx - size / 2, cy - size / 2, size, primary && !blend ? ON_ACCENT : TEXT);
        c.restoreToCount(saved);
        r.set(visual); r.inset(-6, -6); hit(r, visual, action);
    }
    private void equalizer(Canvas c, float x, float y, float h, boolean playing, float now, int color) {
        paint.setColor(color);
        for (int i = 0; i < 6; i++) {
            float k = playing ? .3f + .7f * (.5f + .5f * (float) Math.sin(now * OMEGA[i] * .5f + i * 1.7f)) : .25f;
            float bh = h * k;
            r2.set(x + i * 4.5f, y + (h - bh) / 2, x + i * 4.5f + 2.5f, y + (h + bh) / 2);
            c.drawRoundRect(r2, 1.25f, 1.25f, paint);
        }
    }

    // messages: vertical carousel of what's in the shade, swipe up/down or it drifts on its own
    private void messages(Canvas c, RectF box) {
        panel(c, "messages", messageSilk, box, 24, PANEL);
        msgBox.set(box);
        List<CarData.Signal> list = data.showing;
        if (list.isEmpty()) {
            hit(box, box, null);
            if (compact) {
                empty(c, box, R.drawable.car_message, data.listenerConnected ? "No notifications" : "Notifications are off",
                        data.listenerConnected ? "Anything in your phone's shade shows up here." : "Allow notification access on your phone.",
                        null, null);
                return;
            }
            r.set(box.left + 16, box.centerY() - 22, box.left + 60, box.centerY() + 22);
            fill(c, r, 14, PLACEHOLDER);
            icon(c, R.drawable.car_message, r.left + 10, r.top + 10, 24, TEXT2);
            float tx = r.right + 12, maxW = box.right - 16 - tx;
            label(c, data.listenerConnected ? "No notifications" : "Notifications are off", tx, box.centerY() - 3, 15, semibold, TEXT, maxW);
            label(c, data.listenerConnected ? "Anything in your phone's shade shows up here" : "Allow notification access on your phone",
                    tx, box.centerY() + 18, 14, regular, TEXT2, maxW);
            return;
        }
        int n = list.size();
        float pos = n < 2 ? carouselIndex : carouselPos(), h = box.height();
        int saved = c.save();
        c.clipRect(box.left, box.top + 4, box.right, box.bottom - 4);
        for (int k = (int) Math.floor(pos) - 1; k <= (int) Math.floor(pos) + 1; k++) {
            float offset = (k - pos) * h;
            if (Math.abs(offset) >= h) continue;
            CarData.Signal s = list.get(Math.floorMod(k, n));
            float fade = 1 - Math.min(1, Math.abs(offset) / h);
            int layer = fade >= .99f ? c.save() : c.saveLayerAlpha(box, Math.round(255 * fade));
            c.translate(0, offset);
            messageCard(c, box, s, n > 1);
            c.restoreToCount(layer);
        }
        c.restoreToCount(saved);
        if (n > 1) carouselDots(c, box, n, Math.floorMod(Math.round(pos), n));
        CarData.Signal current = list.get(Math.floorMod(carouselIndex, n));
        hit(box, box, () -> island.show(current, true));
    }
    private void messageCard(Canvas c, RectF box, CarData.Signal s, boolean dots) {
        if (compact) { messageCardLarge(c, box, s, dots); return; }
        float pad = 16, size = 44, cy = box.centerY();
        r.set(box.left + pad, cy - size / 2, box.left + pad + size, cy + size / 2);
        if (s.avatar != null) {
            bitmapRound(c, s.avatar, r.left, r.top, size, size / 2);
            // which app it came from, as a badge on the avatar instead of a tiny header line
            if (s.appIcon != null) bitmapRound(c, s.appIcon, r.right - 14, r.bottom - 14, 18, 5);
        } else {
            fill(c, r, 14, PLACEHOLDER);
            if (s.appIcon != null) bitmapRound(c, s.appIcon, r.left + 8, r.top + 8, 28, 8);
            else icon(c, R.drawable.car_message, r.left + 10, r.top + 10, 24, TEXT2);
        }
        float tx = r.right + 14, maxW = box.right - (dots ? 30 : 16) - tx;
        label(c, s.title, tx, cy - 3, 16, semibold, TEXT, maxW);
        label(c, s.text, tx, cy + 18, 14, regular, TEXT2, maxW);
    }
    // whole page to one message: everything centred, big and bold, the block sits in the middle of the panel.
    // avatar on top, app name, then title and body wrapping instead of cutting off
    private void messageCardLarge(Canvas c, RectF box, CarData.Signal s, boolean dots) {
        float pad = 28, inset = dots ? 40 : pad, left = box.left + inset, right = box.right - inset, w = right - left;
        float size = 60, cx = (left + right) / 2;
        // lay it all out first so the whole block can be centred; own paints, a layout keeps the one it was built with
        style(cardTitle, bold, 30); cardTitle.setColor(Color.WHITE);
        style(cardBody, semibold, 22); cardBody.setColor(0xF2FFFFFF);
        float room = box.height() - 2 * pad - size - 14 - 26;
        StaticLayout title = s.title == null || s.title.isEmpty() ? null : centredLayout(s.title, cardTitle, w, 2);
        float titleH = title == null ? 0 : title.getHeight();
        StaticLayout body = null;
        if (s.text != null && !s.text.isEmpty()) {
            int lines = Math.max(1, Math.min(4, (int) ((room - titleH - 10) / (22 * 1.25f))));
            body = centredLayout(s.text, cardBody, w, lines);
        }
        float blockH = size + 14 + 26 + titleH + (body != null ? 10 + body.getHeight() : 0);
        float top = box.top + Math.max(pad, (box.height() - blockH) / 2);
        r.set(cx - size / 2, top, cx + size / 2, top + size);
        if (s.avatar != null) {
            bitmapRound(c, s.avatar, r.left, r.top, size, size / 2);
            if (s.appIcon != null) bitmapRound(c, s.appIcon, r.right - 20, r.bottom - 20, 24, 7);
        } else {
            fill(c, r, 20, PLACEHOLDER);
            if (s.appIcon != null) bitmapRound(c, s.appIcon, r.left + 11, r.top + 11, 38, 11);
            else icon(c, R.drawable.car_message, r.left + 14, r.top + 14, 32, TEXT);
        }
        float y = r.bottom + 14;
        centered(c, s.app, cx, y + 18, 16, bold, ACCENT, w);
        y += 26;
        if (title != null) { int saved = c.save(); c.translate(left, y); title.draw(c); c.restoreToCount(saved); y += titleH + 10; }
        if (body != null) { int saved = c.save(); c.translate(left, y); body.draw(c); c.restoreToCount(saved); }
    }
    private static StaticLayout centredLayout(String s, TextPaint p, float width, int lines) {
        return StaticLayout.Builder.obtain(s, 0, s.length(), p, Math.max(1, Math.round(width)))
                .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 1.08f)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).build();
    }
    // >6 notifs just says 3/9, dots get silly past that
    private void carouselDots(Canvas c, RectF box, int n, int active) {
        float x = box.right - 14;
        if (n > 6) {
            String count = (active + 1) + "/" + n;
            style(text, medium, 14);
            label(c, count, x + 4 - text.measureText(count), box.top + 24, 14, medium, TEXT2);
            return;
        }
        float spacing = 9, top = box.centerY() - (n - 1) * spacing / 2;
        for (int i = 0; i < n; i++) {
            paint.setColor(i == active ? ACCENT : 0x40FFFFFF);
            c.drawCircle(x, top + i * spacing, i == active ? 3 : 2.2f, paint);
        }
    }

    // picker sheet
    private void pick(String slot) { data.refreshAppsSoon(); picking = slot; }
    private void drawPicker(Canvas c) {
        paint.setColor(0x73000000); c.drawRect(0, 0, width, H, paint);
        hit(new RectF(0, 0, width, H), () -> picking = null); // tap outside closes
        List<CarData.App> apps = data.apps(picking);
        int rows = Math.max(1, Math.min(3, (apps.size() + 2) / 3));
        float w = Math.min(560, width - 48), h = 18 + 3 + 12 + 26 + 20 + 14 + rows * 72 + 54;
        RectF sheet = new RectF((width - w) / 2, (H - h) / 2, (width + w) / 2, (H + h) / 2);
        backdrop.frost(c, sheet, 28, width, H); sheetSilk.draw(c, sheet, 28, SHEET, ACCENT, true);
        hit(sheet, () -> { }); // eat taps on the sheet
        r.set(sheet.centerX() - 14, sheet.top + 18, sheet.centerX() + 14, sheet.top + 21); fill(c, r, 1.5f, 0x40FFFFFF);
        boolean maps = CarData.MAPS.equals(picking);
        float x = sheet.left + 20, y = sheet.top + 33;
        label(c, maps ? "Choose your maps app" : "Choose your media app", x, y + 20, 20, semibold, TEXT);
        label(c, maps ? "Its directions appear in this panel and turns in the island."
                : "Now playing and controls appear in this panel.", x, y + 42, 14, regular, TEXT2, w - 40);
        y += 54;
        if (apps.isEmpty()) {
            label(c, maps ? "No navigation apps found on your phone." : "No media apps found on your phone.", x, y + 30, 15, medium, TEXT);
        }
        String current = data.chosen(picking);
        float tileW = (w - 40 - 16) / 3;
        for (int i = 0; i < Math.min(9, apps.size()); i++) {
            CarData.App app = apps.get(i);
            float tx = x + (i % 3) * (tileW + 8), ty = y + (i / 3) * 72;
            r.set(tx, ty, tx + tileW, ty + 64);
            RectF visual = new RectF(r);
            int saved = wobble(c, visual);
            boolean selected = app.pkg.equals(current);
            fill(c, r, 22, selected ? ACCENT_CONTAINER : CHIP);
            if (selected) { paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1); paint.setColor(ACCENT);
                c.drawRoundRect(r, 22, 22, paint); paint.setStyle(Paint.Style.FILL); }
            bitmapRound(c, app.icon, tx + 10, ty + 10, 44, 14);
            label(c, app.label, tx + 64, ty + 28, 15, semibold, TEXT, tileW - 72);
            label(c, app.kind, tx + 64, ty + 48, 14, regular, TEXT2, tileW - 72);
            final String slot = picking, pkg = app.pkg;
            c.restoreToCount(saved);
            hit(visual, visual, () -> { data.choose(slot, pkg); closeSheetsSoon(); });
        }
        style(text, medium, 15);
        float cw = text.measureText("Cancel") + 32;
        r.set(sheet.right - 20 - cw, sheet.bottom - 20 - 34, sheet.right - 20, sheet.bottom - 20);
        RectF cancel = new RectF(r);
        int saved = wobble(c, cancel);
        fill(c, r, 17, CHIP);
        label(c, "Cancel", r.left + 16, r.top + 23, 15, medium, TEXT);
        c.restoreToCount(saved);
        hit(cancel, cancel, this::closeSheetsSoon);
        label(c, "You can change this any time from the panel.", x, sheet.bottom - 32, 14, regular, TEXT2, w - cw - 60);
    }

    // maps panel when a car's connected: car app lib app if it has a car mode, else the phone app on a virtual display
    private void drawLiveMaps(Canvas c) {
        CarData.App app = session ? data.chosenApp(CarData.MAPS) : null;
        String want = mapWant(app);
        if (want == null ? mapSource != null : !want.equals(mapSource)) {
            closeMaps();
            mapSource = want;
            if (want != null && want.endsWith("#car")) { mapHost = new CarAppHost(context, app.carService, null); mapHost.start(); }
            else if (want != null) mapDisplay = new AppDisplay(context, app.pkg);
        }
        if (mapHost != null && mapHost.rejected) {
            // this app's own HostValidator said no — stop here, next frame picks the display fallback or the notif card
            calRejected.add(app.pkg);
            closeMaps();
            return;
        }
        if (app == null || want == null || mapsBox.width() < 1 || mapsAlpha <= .01f) return;
        int moved = c.save(), mark = pending.size();
        if (mapsShift != 0 || mapsAlpha < .99f) {
            c.clipRect(pageBox.left - 12, pageBox.top - 6, pageBox.right + 12, H);
            c.translate(0, mapsShift);
            r.set(mapsBox); r.inset(-4, -4);
            c.saveLayerAlpha(r, Math.round(255 * mapsAlpha));
        }
        drawLiveMapFrame(c, app);
        c.restoreToCount(moved);
        if (mapsLeaving) while (pending.size() > mark) pending.remove(pending.size() - 1);
    }
    private void drawLiveMapFrame(Canvas c, CarData.App app) {
        // the app renders at output pixels, supersampling only smooths our own drawing; no 4x work for the map app
        float px = frameSx / ssaa, py = frameSy / ssaa;
        int w = Math.round(mapsBox.width() * px), h = Math.round(mapsBox.height() * py), dpi = Math.round(160 * px);
        // while the rail slides the app keeps its old size and its frame stretches; one resize once it's settled
        boolean resized = (w != mapW || h != mapH || dpi != mapDpi) && (mapW == 0 || !railMoving());
        if (resized) { mapW = w; mapH = h; mapDpi = dpi; }
        if (mapHost != null) {
            if (resized) mapHost.size(w, h, dpi, new android.graphics.Rect(0, 0, w, h));
            if (carPanel == null) carPanel = new CarAppPanel(context, this);
            hit(mapsBox, this::mapClick); // bare map tap, the panel's own buttons register after and win
            carPanel.draw(c, mapsBox, mapHost, mapHost.frame());
        } else if (mapDisplay != null) {
            if (resized) mapDisplay.size(w, h, dpi);
            liveFrame(c, mapsBox, 24, mapDisplay.frame(), "Opening " + app.label + "…");
        }
    }
    private final android.graphics.Path liveClip = new android.graphics.Path();
    private void liveFrame(Canvas c, RectF box, float radius, Bitmap frame, String waiting) {
        int saved = c.save();
        liveClip.reset(); liveClip.addRoundRect(box, radius, radius, android.graphics.Path.Direction.CW); c.clipPath(liveClip);
        if (frame != null) c.drawBitmap(frame, null, box, image);
        else { paint.setColor(0xFF15171E); c.drawRect(box, paint); centered(c, waiting, box.centerX(), box.centerY(), 15, semibold, TEXT2, box.width() - 40); }
        c.restoreToCount(saved);
    }

    // floating app window, same spring as the drawer so it feels like one ui
    private void drawWindow(Canvas c) {
        long nowMs = SystemClock.elapsedRealtime();
        float dt = Math.min(.1f, (nowMs - windowAt) / 1000f); windowAt = nowMs;
        for (float left = dt; left > 0; left -= .004f) {
            float step = Math.min(.004f, left);
            windowVel += (260 * ((windowOpen ? 1 : 0) - windowAnim) - 32.2f * windowVel) * step; windowAnim += windowVel * step;
            fullVel += (260 * ((windowFull ? 1 : 0) - fullAnim) - 32.2f * fullVel) * step; fullAnim += fullVel * step;
        }
        if (still) { windowAnim = windowOpen ? 1 : 0; fullAnim = windowFull ? 1 : 0; windowVel = fullVel = 0; }
        if (!windowOpen && windowAnim < .002f) {
            windowAnim = 0; windowVel = 0;
            if (window != null) { window.close(); window = null; windowApp = null; }
            windowContent.setEmpty(); windowRect.setEmpty();
            return;
        }
        if (window == null || windowApp == null) return;
        float p = Math.max(0, Math.min(1, windowAnim)), f = Math.max(0, Math.min(1, fullAnim));
        float nw = Math.min(width - 40, width * .7f), nl = (width - nw) / 2;
        windowRect.set(lerp(nl, 6, f), lerp(58, 6, f), lerp(nl + nw, width - 6, f), lerp(H - 14, H - 6, f));
        windowContent.set(windowRect.left + 6, windowRect.top + 40, windowRect.right - 6, windowRect.bottom - 6);
        // size the display off where it's heading, not mid-animation, or it resizes 20 times
        if (windowFull) windowTarget.set(12, 46, width - 12, H - 12); else windowTarget.set(nl + 6, 98, nl + nw - 6, H - 20);
        float px = frameSx / ssaa, py = frameSy / ssaa;
        int w = Math.round(windowTarget.width() * px), h = Math.round(windowTarget.height() * py), dpi = Math.round(160 * px);
        if (windowOpen && (w != winW || h != winH || dpi != winDpi)) { winW = w; winH = h; winDpi = dpi; window.size(w, h, dpi); }

        int saved = p >= .99f ? c.save() : c.saveLayerAlpha(windowRect, Math.round(255 * p));
        float k = .92f + .08f * p;
        c.scale(k, k, windowRect.centerX(), windowRect.centerY());
        paint.setColor(Color.argb(Math.round(70 * p), 0, 0, 0)); r.set(windowRect); r.offset(0, 6); c.drawRoundRect(r, 24, 24, paint);
        backdrop.frost(c, windowRect, 22, width, H); fill(c, windowRect, 22, 0xE6181A21);
        bitmapRound(c, windowApp.icon, windowRect.left + 14, windowRect.top + 9, 22, 6);
        float bx = windowRect.right - 10;
        bx = windowButton(c, bx, 2, () -> windowOpen = false);
        bx = windowButton(c, bx, 1, () -> windowFull = !windowFull);
        bx = windowButton(c, bx, 0, () -> { if (window != null) window.back(); });
        label(c, windowApp.label, windowRect.left + 44, windowRect.top + 26, 15, semibold, TEXT, bx - windowRect.left - 54);
        liveFrame(c, windowContent, 16, window.frame(), "Opening " + windowApp.label + "…");
        c.restoreToCount(saved);
    }
    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    // 0 back 1 full screen toggle 2 close, drawn as lines so no extra drawables
    private float windowButton(Canvas c, float right, int kind, Runnable action) {
        RectF b = new RectF(right - 30, windowRect.top + 5, right, windowRect.top + 35);
        int saved = wobble(c, b);
        fill(c, b, 15, CHIP);
        paint.setColor(TEXT); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2); paint.setStrokeCap(Paint.Cap.ROUND);
        float cx = b.centerX(), cy = b.centerY();
        if (kind == 0) { c.drawLine(cx + 3, cy - 6, cx - 3, cy, paint); c.drawLine(cx - 3, cy, cx + 3, cy + 6, paint); }
        else if (kind == 1) {
            float o = windowFull ? 3 : 6;
            r2.set(cx - o, cy - o, cx + o, cy + o); c.drawRoundRect(r2, 2, 2, paint);
        } else { c.drawLine(cx - 5, cy - 5, cx + 5, cy + 5, paint); c.drawLine(cx + 5, cy - 5, cx - 5, cy + 5, paint); }
        paint.setStyle(Paint.Style.FILL); paint.setStrokeCap(Paint.Cap.BUTT);
        c.restoreToCount(saved);
        if (windowOpen) hit(b, new RectF(b), action);
        return b.left - 8;
    }

    // drawer, slides from the left, two side-scrolling rows
    private void drawDrawer(Canvas c) {
        long nowMs = SystemClock.elapsedRealtime();
        float dt = Math.min(.1f, (nowMs - drawerAt) / 1000f); drawerAt = nowMs;
        float target = drawerOpen ? 1 : 0;
        // follow the finger mid swipe if the car sends moves — never peek for a drag that started on the map panel
        if (!drawerOpen && dragging && !scrolling && picking == null && lastX > downX && !mapsBox.contains(downX, downY))
            target = Math.min(.35f, (lastX - downX) / 400f);
        // critically damped spring, old lerp+cubic did 84% of the slide in one frame and crawled the rest
        for (float left = dt; left > 0; left -= .004f) {
            float step = Math.min(.004f, left);
            drawerVel += (220 * (target - drawer) - 29.7f * drawerVel) * step;
            drawer += drawerVel * step;
        }
        if (still || Math.abs(target - drawer) < .002f && Math.abs(drawerVel) < .01f) { drawer = target; drawerVel = 0; }
        if (drawer <= .001f) { drawerRow.setEmpty(); return; }
        float p = Math.max(0, Math.min(1, drawer));
        paint.setColor(Color.argb(Math.round(0x73 * p), 0, 0, 0)); c.drawRect(0, 0, width, H, paint);
        if (drawerOpen) hit(new RectF(0, 0, width, H), () -> drawerOpen = false); // tap outside closes

        float x0 = 20, w = width - 40, slide = (1 - p) * (w + x0);
        RectF sheet = new RectF(x0 - slide, BAR_TOP, x0 + w - slide, H - 20);
        backdrop.frost(c, sheet, 28, width, H); drawerSilk.draw(c, sheet, 28, SHEET, ACCENT, true);
        if (drawerOpen) hit(sheet, () -> { }); // eat taps on the sheet
        float x = sheet.left + 24, y = sheet.top + 24;
        label(c, "Apps", x, y + 22, 22, semibold, TEXT);

        List<CarData.App> source = data.carApps();
        if (source != drawerSource) sortDrawer(source);
        List<CarData.App> apps = drawerApps;
        int rows = compact ? 1 : 2; // short canvas only fits one row of tiles
        float rowTop = y + 66, rowsH = rows * TILE_H + (rows - 1) * TILE_GAP;
        drawerRow.set(sheet.left + 12, rowTop, sheet.right - 12, Math.min(sheet.bottom - 16, rowTop + rowsH));
        if (apps.isEmpty()) {
            label(c, "Car-ready apps on your phone", x, y + 46, 14, regular, TEXT2, w - 48);
            centered(c, "No car-ready apps found", sheet.centerX(), drawerRow.centerY() - 6, 17, semibold, TEXT, w - 48);
            centered(c, "Install a media or navigation app that supports Android Auto.", sheet.centerX(),
                    drawerRow.centerY() + 20, 14, regular, TEXT2, w - 48);
            drawerColumns = 0; drawerFocusRect.setEmpty(); stripBox.setEmpty();
            return;
        }
        drawerRows = rows; drawerColumns = (apps.size() + rows - 1) / rows;
        drawerFocus = Math.max(0, Math.min(apps.size() - 1, drawerFocus));
        if (Float.isNaN(drawerScroll)) { drawerTarget = drawerScroll = snapFor(drawerFocus / rows); drawerScrollVel = 0; }
        drawerTarget = snapFor(nearestColumn(drawerTarget)); // app list changed size under us
        if (!(dragging && scrolling)) {
            for (float left = dt; left > 0; left -= .004f) {
                float step = Math.min(.004f, left);
                drawerScrollVel += (DRAWER_K * (drawerTarget - drawerScroll) - DRAWER_D * drawerScrollVel) * step;
                drawerScroll += drawerScrollVel * step;
            }
            if (still || (Math.abs(drawerTarget - drawerScroll) < .2f && Math.abs(drawerScrollVel) < 2f)) {
                drawerScroll = drawerTarget; drawerScrollVel = 0;
            }
        }
        drawerFocusRect.setEmpty();
        int saved = c.save();
        // headroom round the row: a tile mid-wobble or with its selected outline grows past its own box
        c.clipRect(drawerRow.left - 8, drawerRow.top - 10, drawerRow.right + 8, drawerRow.bottom + 10);
        String maps = data.chosen(CarData.MAPS), media = data.chosen(CarData.MEDIA);
        for (int i = 0; i < apps.size(); i++) {
            CarData.App app = apps.get(i);
            float tx = drawerRow.left + 12 + (i / rows) * (TILE_W + TILE_GAP) - drawerScroll;
            float ty = drawerRow.top + (i % rows) * (TILE_H + TILE_GAP);
            if (tx + TILE_W < drawerRow.left || tx > drawerRow.right) continue;
            r.set(tx, ty, tx + TILE_W, ty + TILE_H);
            RectF visual = new RectF(r);
            if (i == drawerFocus) drawerFocusRect.set(visual);
            int tileSaved = wobble(c, visual);
            // the middle one is the pick: full size and solid, the rest step back a little with distance
            float off = Math.min(1f, Math.abs(r.centerX() - drawerRow.centerX()) / (TILE_W + TILE_GAP));
            float k = 1 - .08f * off;
            c.scale(k, k, r.centerX(), r.centerY());
            if (off > .01f) { r2.set(r); r2.inset(-6, -6); c.saveLayerAlpha(r2, Math.round(255 * (1 - .35f * off))); }
            boolean selected = app.pkg.equals(CarData.MAPS.equals(CarData.slot(app)) ? maps : media);
            fill(c, r, 24, selected ? ACCENT_CONTAINER : CHIP);
            if (selected) { paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1); paint.setColor(ACCENT);
                c.drawRoundRect(r, 24, 24, paint); paint.setStyle(Paint.Style.FILL); }
            bitmapRound(c, app.icon, r.centerX() - 32, ty + 20, 64, 18);
            centered(c, app.label, r.centerX(), ty + 110, 15, semibold, TEXT, TILE_W - 16);
            centered(c, app.kind, r.centerX(), ty + 131, 14, regular, TEXT2, TILE_W - 16);
            c.restoreToCount(tileSaved);
            if (drawerOpen) {
                RectF tap = new RectF(visual); tap.setIntersect(tap, drawerRow);
                final int index = i, column = i / rows;
                // tap the middle one to open it; tap a side one and it glides into the middle first
                hit(tap, visual, () -> {
                    if (Math.abs(snapFor(column) - drawerScroll) < (TILE_W + TILE_GAP) / 2) openFromDrawer(app);
                    else { drawerFocus = index; drawerTarget = snapFor(column); }
                });
            }
        }
        c.restoreToCount(saved);
        drawStrip(c, x, y + 34, sheet.right - 24 - x, dt);
        drawBubble(c);
        // arrows so you know there's more either side
        if (drawerScroll > snapFor(0) + 1) scrollHint(c, drawerRow.left, true);
        if (drawerScroll < snapFor(drawerColumns - 1) - 1) scrollHint(c, drawerRow.right, false);
    }
    // letters in a centred run under the title; the pill springs to the centred app's letter
    private void drawStrip(Canvas c, float left, float top, float width, float dt) {
        int n = drawerLetters.size();
        if (n == 0) { stripBox.setEmpty(); return; }
        stripCell = Math.min(46, width / n);
        float run = stripCell * n;
        stripLeft = left + (width - run) / 2;
        stripBox.set(stripLeft, top, stripLeft + run, top + 28);
        int live = Math.max(0, Math.min(drawerApps.size() - 1, nearestColumn(drawerScroll) * drawerRows));
        int current = scrubbing || stripFocus ? letterIndex(drawerFocus) : letterIndex(live);
        float target = stripLeft + stripCell * (current + .5f);
        if (Float.isNaN(pillX) || still) { pillX = target; pillVel = 0; }
        for (float rest = dt; rest > 0; rest -= .004f) {
            float step = Math.min(.004f, rest);
            pillVel += (DRAWER_K * (target - pillX) - DRAWER_D * pillVel) * step; pillX += pillVel * step;
        }
        if (Math.abs(target - pillX) < .2f && Math.abs(pillVel) < 2f) { pillX = target; pillVel = 0; }
        r.set(pillX - stripCell / 2 + 2, top, pillX + stripCell / 2 - 2, top + 28);
        fill(c, r, 14, ACCENT_CONTAINER);
        for (int i = 0; i < n; i++) {
            float cx = stripLeft + stripCell * (i + .5f);
            boolean on = i == current;
            centered(c, String.valueOf(drawerLetters.get(i)), cx, top + 20, 16, on ? bold : semibold, on ? ACCENT : TEXT2, stripCell);
        }
    }
    // big letter in a glass-ish capsule over the carousel while you jump letters, fades out after
    private void drawBubble(Canvas c) {
        float age = (SystemClock.elapsedRealtime() - bubbleAt) / (float) BUBBLE_MS;
        if (age >= 1 || drawerColumns == 0) return;
        float alpha = still ? (age < 1 ? 1 : 0) : Math.min(1, (1 - age) / .35f);
        float size = 88, cx = drawerRow.centerX(), cy = drawerRow.centerY();
        r.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);
        int saved = c.saveLayerAlpha(r, Math.round(255 * alpha));
        fill(c, r, 26, 0xE6181A21);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1); paint.setColor(0x33FFFFFF);
        c.drawRoundRect(r, 26, 26, paint); paint.setStyle(Paint.Style.FILL);
        centered(c, String.valueOf(bubbleLetter), cx, cy + 20, 56, bold, Color.WHITE, size);
        c.restoreToCount(saved);
    }
    private void scrollHint(Canvas c, float edge, boolean left) {
        paint.setColor(TEXT2);
        float cy = drawerRow.centerY(), x = left ? edge + 6 : edge - 6, k = left ? 1 : -1;
        paint.setStrokeWidth(3); paint.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(x + 6 * k, cy - 10, x, cy, paint); c.drawLine(x, cy, x + 6 * k, cy + 10, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    // island
    private void drawIsland(Canvas c, RenderNode backdrop, int left, int top, float sx, float sy, float now) {
        RectF box = island.bounds(width);
        if (box.width() < 1) return;
        int accent = island.signal != null && island.signal.color != 0 ? island.signal.color | 0xFF000000 : ACCENT;
        glass.area(left, top, left + width * sx, top + H * sy);
        glass.draw(c, backdrop, sceneVersion, left, top, sx, sy, box, island.radius(), island.sheen(now), island.neon(now), accent);
        float alpha = island.contentAlpha();
        if (alpha > 0.01f && island.signal != null) {
            int saved = alpha >= .99f ? c.save() : c.saveLayerAlpha(box, (int) (alpha * 255));
            c.clipRect(box);
            if (island.expanded) islandCard(c, box, island.signal); else islandCapsule(c, box, island.signal, now);
            c.restoreToCount(saved);
        }
        hit(box, island::tap);
    }
    private void islandCapsule(Canvas c, RectF box, CarData.Signal s, float now) {
        float x = box.left + 8, cy = box.centerY();
        Bitmap icon = s.kind == CarData.Signal.Kind.MEDIA && s.avatar != null ? s.avatar : s.appIcon;
        if (icon != null) bitmapRound(c, icon, x, cy - 12, 24, s.kind == CarData.Signal.Kind.MEDIA ? 7 : 12);
        boolean eq = s.kind == CarData.Signal.Kind.MEDIA;
        float maxW = box.right - 14 - (x + 34) - (eq ? 30 : 0);
        label(c, s.title, x + 34, cy + 6, 18, bold, Color.WHITE, maxW);
        if (eq) {
            paint.setColor(ACCENT);
            float t = still ? 0 : now;
            for (int i = 0; i < 4; i++) {
                float k = .3f + .7f * (.5f + .5f * (float) Math.sin(t * OMEGA[i] * .5f + i * 1.7f)), bh = 16 * k;
                r2.set(box.right - 34 + i * 6, cy - bh / 2, box.right - 34 + i * 6 + 3.5f, cy + bh / 2);
                c.drawRoundRect(r2, 1.75f, 1.75f, paint);
            }
        }
    }
    // like NotificationPresenter.bindExpanded: indicator, header, avatar + title + text
    private void islandCard(Canvas c, RectF box, CarData.Signal s) {
        r.set(box.centerX() - 16, box.top + 10, box.centerX() + 16, box.top + 14); fill(c, r, 2f, 0x40FFFFFF);
        float x = box.left + 20, y = box.top + 24;
        if (s.appIcon != null) bitmapRound(c, s.appIcon, x, y, 22, 6);
        String header = Island.header(s);
        label(c, header, x + 30, y + 17, 17, bold, 0xB3FFFFFF, box.width() - 70);
        float by = y + 36, tx = x;
        if (s.avatar != null) {
            boolean round = s.kind == CarData.Signal.Kind.NOTIFICATION;
            bitmapRound(c, s.avatar, x, by, 56, round ? 28 : 14);
            tx = x + 72;
        }
        float maxW = box.right - 20 - tx;
        label(c, s.title, tx, by + 22, 22, bold, Color.WHITE, maxW);
        // two lines of body, the card's wide enough now that most messages fit whole
        if (s.text == null || s.text.isEmpty() || maxW <= 0) return;
        style(text, regular, 19); text.setColor(0xF2FFFFFF); text.setTextAlign(Paint.Align.LEFT);
        StaticLayout body = StaticLayout.Builder.obtain(s.text, 0, s.text.length(), text, Math.round(maxW))
                .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 1.05f).build();
        int saved = c.save();
        c.translate(tx, by + 32);
        body.draw(c);
        c.restoreToCount(saved);
    }

    // volume
    private void listenVolume(boolean on) {
        if (on == (volumeReceiver != null)) return;
        if (!on) {
            try { context.unregisterReceiver(volumeReceiver); } catch (IllegalArgumentException gone) { }
            volumeReceiver = null;
            return;
        }
        volumeReceiver = new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, android.content.Intent intent) {
                int stream = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1);
                if (stream == android.media.AudioManager.STREAM_MUSIC) volumeChanged();
            }
        };
        android.content.IntentFilter filter = new android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        filter.addAction("android.media.STREAM_MUTE_CHANGED_ACTION");
        context.registerReceiver(volumeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        readVolume();
    }
    private android.media.AudioManager audio() { return context.getSystemService(android.media.AudioManager.class); }
    private void readVolume() {
        android.media.AudioManager am = audio();
        int max = Math.max(1, am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC));
        int min = am.getStreamMinVolume(android.media.AudioManager.STREAM_MUSIC);
        volSteps = Math.max(1, max - min);
        volMuted = am.isStreamMute(android.media.AudioManager.STREAM_MUSIC);
        // finger's the boss mid-drag, late echoes of our own sets would yank the fill around
        if (!volScrub) volLevel = Math.max(0, Math.min(1, (am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) - min) / (float) volSteps));
    }
    private synchronized void volumeChanged() {
        readVolume();
        volAt = SystemClock.elapsedRealtime(); dirty = true;
    }
    // car volume keys (only if it offers them, most cars keep volume to themselves)
    private synchronized void volumeKey(int code) {
        android.media.AudioManager am = audio();
        readVolume();
        // bump past max, the phone's jiggle
        if (code == 24 && volLevel >= 1 && !volMuted) volume.shake(SystemClock.elapsedRealtime());
        int direction = code == 24 ? android.media.AudioManager.ADJUST_RAISE : code == 25 ? android.media.AudioManager.ADJUST_LOWER
                : android.media.AudioManager.ADJUST_TOGGLE_MUTE;
        am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, direction, 0);
        readVolume();
        volAt = SystemClock.elapsedRealtime();
    }
    // up the capsule = louder, snapped to the stream's real steps
    private void volumeFrom(float y) {
        float span = volume.trackBottom - volume.trackTop;
        if (span <= 0) return;
        float f = Math.max(0, Math.min(1, (volume.trackBottom - y) / span));
        int step = Math.round(f * volSteps);
        android.media.AudioManager am = audio();
        int min = am.getStreamMinVolume(android.media.AudioManager.STREAM_MUSIC);
        am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, min + step, 0);
        if (step > 0 && am.isStreamMute(android.media.AudioManager.STREAM_MUSIC))
            am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_UNMUTE, 0);
        volLevel = step / (float) volSteps; volMuted = step == 0;
        volAt = SystemClock.elapsedRealtime();
    }
    private void drawVolume(Canvas c) {
        long nowMs = SystemClock.elapsedRealtime();
        float dt = (nowMs - volFrameAt) / 1000f; volFrameAt = nowMs;
        boolean show = volScrub || nowMs - volAt < VOLUME_SHOW_MS;
        if (show && !volume.showing()) volume.show();
        else if (!show && volume.showing()) volume.dismiss(nowMs);
        volume.level(volLevel, volMuted);
        volume.step(dt, nowMs, still);
        // joined to the very edge of what the driver sees, the path runs on past it like it does past the phone's bezel
        volume.draw(c, width, H / 2, volMuted, volLevel, nowMs, still);
    }

    // shared bits
    private void empty(Canvas c, RectF box, int glyph, String title, String body, String button, Runnable action) {
        boolean small = box.height() < 220;
        // body wraps to two lines on the rail layout's full page, there's room and an ellipsized hint helps no one
        int lines = compact && !small ? 2 : 1;
        float tile = small ? 48 : 56, bodyH = lines == 2 ? 40 : 18, buttonH = button == null ? 0 : 44 + (small ? 14 : 20);
        float block = tile + (small ? 24 : 30) + 22 + bodyH + buttonH;
        float cy = box.centerY() - block / 2 + tile / 2;
        r.set(box.centerX() - tile / 2, cy - tile / 2, box.centerX() + tile / 2, cy + tile / 2);
        fill(c, r, small ? 16 : 18, PLACEHOLDER);
        icon(c, glyph, r.centerX() - 14, r.centerY() - 14, 28, TEXT2);
        float ty = r.bottom + (small ? 24 : 30), maxW = Math.min(420, box.width() - 40);
        centered(c, title, box.centerX(), ty, compact ? 20 : 18, semibold, TEXT, maxW);
        float used = wrapped(c, body, box.centerX(), ty + 8, compact ? 16 : 14, regular, TEXT2, maxW, lines);
        if (button == null) return;
        style(text, medium, 15);
        float bw = text.measureText(button) + 44, by = ty + 8 + Math.max(18, used) + (small ? 14 : 20);
        r.set(box.centerX() - bw / 2, by, box.centerX() + bw / 2, by + 44);
        RectF visual = new RectF(r);
        int saved = wobble(c, visual);
        fill(c, r, 22, ACCENT);
        centered(c, button, box.centerX(), by + 28, 15, medium, ON_ACCENT, bw);
        c.restoreToCount(saved);
        RectF target = new RectF(visual); target.inset(-8, -8); hit(target, visual, action);
    }
    // icon + name + chevron right aligned, opens the picker
    private void appChip(Canvas c, CarData.App app, float right, float bottom, Runnable action) {
        style(text, semibold, 14);
        float w = 6 + 20 + 8 + text.measureText(app.label) + 4 + 14 + 10;
        r.set(right - w, bottom - 32, right, bottom);
        RectF visual = new RectF(r);
        int saved = wobble(c, visual);
        fill(c, r, 16, 0x9E101012);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1); paint.setColor(0x29FFFFFF);
        c.drawRoundRect(r, 16, 16, paint); paint.setStyle(Paint.Style.FILL);
        bitmapRound(c, app.icon, r.left + 6, r.top + 6, 20, 6);
        label(c, app.label, r.left + 34, r.top + 21, 14, semibold, TEXT);
        icon(c, R.drawable.car_chevron, r.right - 24, r.top + 9, 14, TEXT2);
        c.restoreToCount(saved);
        RectF target = new RectF(visual); target.inset(-6, -8); hit(target, visual, action);
    }
    private void appChipInline(Canvas c, CarData.App app, float x, float top, Runnable action) {
        style(text, semibold, 14);
        float w = 6 + 16 + 8 + text.measureText(app.label) + 4 + 14 + 8;
        r.set(x, top, x + w, top + 28);
        RectF visual = new RectF(r);
        int saved = wobble(c, visual);
        fill(c, r, 14, CHIP);
        bitmapRound(c, app.icon, x + 6, top + 6, 16, 5);
        label(c, app.label, x + 30, top + 19, 14, semibold, TEXT2);
        icon(c, R.drawable.car_chevron, r.right - 20, top + 7, 14, TEXT2);
        c.restoreToCount(saved);
        RectF target = new RectF(visual); target.inset(-6, -8); hit(target, visual, action);
    }
    // every measure + draw goes through here so both agree. the rail layout only runs on <=480px panels, where
    // 400 weight strokes go grey and mushy after the encoder: nothing under 14 units, nothing thinner than
    // medium below 18
    void style(TextPaint p, Typeface face, float size) {
        if (compact && size < 18 && face == regular) face = medium;
        p.setTypeface(face); p.setTextSize(compact ? Math.max(14, size) : size);
    }
    // wraps onto up to `lines` lines instead of ellipsizing one; x is the centre if centred, else the left edge.
    // returns the height it took
    private float wrapped(Canvas c, String s, float cx, float top, float size, Typeface face, int color, float maxWidth, int lines) {
        return wrapped(c, s, cx - maxWidth / 2, top, size, face, color, maxWidth, lines, true);
    }
    private float wrapped(Canvas c, String s, float x, float top, float size, Typeface face, int color, float maxWidth,
                          int lines, boolean center) {
        if (s == null || s.isEmpty() || maxWidth <= 0) return 0;
        style(text, face, size); text.setColor(color); text.setTextAlign(Paint.Align.LEFT);
        StaticLayout layout = StaticLayout.Builder.obtain(s, 0, s.length(), text, Math.round(maxWidth))
                .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 1.1f)
                .setAlignment(center ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL).build();
        int saved = c.save();
        c.translate(x, top);
        layout.draw(c);
        c.restoreToCount(saved);
        return layout.getHeight();
    }
    void fill(Canvas c, RectF rect, float radius, int color) { paint.setColor(color); c.drawRoundRect(rect, radius, radius, paint); }
    void label(Canvas c, String s, float x, float baseline, float size, Typeface face, int color) {
        label(c, s, x, baseline, size, face, color, Float.MAX_VALUE);
    }
    void label(Canvas c, String s, float x, float baseline, float size, Typeface face, int color, float maxWidth) {
        if (s == null || s.isEmpty() || maxWidth <= 0) return;
        style(text, face, size); text.setColor(color); text.setTextAlign(Paint.Align.LEFT);
        CharSequence fit = maxWidth == Float.MAX_VALUE ? s : TextUtils.ellipsize(s, text, maxWidth, TextUtils.TruncateAt.END);
        c.drawText(fit, 0, fit.length(), x, baseline, text);
    }
    void centered(Canvas c, String s, float cx, float baseline, float size, Typeface face, int color, float maxWidth) {
        style(text, face, size);
        CharSequence fit = TextUtils.ellipsize(s, text, maxWidth, TextUtils.TruncateAt.END);
        label(c, fit.toString(), cx - text.measureText(fit, 0, fit.length()) / 2, baseline, size, face, color);
    }
    void icon(Canvas c, int res, float x, float y, float size, int color) {
        Drawable d = icons.get(res);
        if (d == null) { d = context.getDrawable(res); if (d == null) return; icons.put(res, d); }
        d.setTint(color); d.setBounds(Math.round(x), Math.round(y), Math.round(x + size), Math.round(y + size)); d.draw(c);
    }
    private void bitmapRound(Canvas c, Bitmap b, float x, float y, float size, float radius) {
        if (b == null) return;
        BitmapShader shader = shaders.get(b);
        if (shader == null) {
            if (shaders.size() > 48) shaders.clear();
            shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP); shaders.put(b, shader);
        }
        float k = Math.max(size / b.getWidth(), size / b.getHeight());
        matrix.setScale(k, k);
        matrix.postTranslate(x + (size - b.getWidth() * k) / 2, y + (size - b.getHeight() * k) / 2);
        shader.setLocalMatrix(matrix);
        image.setShader(shader);
        r2.set(x, y, x + size, y + size);
        c.drawRoundRect(r2, radius, radius, image);
        image.setShader(null);
    }
    void bitmapFit(Canvas c, Bitmap b, RectF box, float inset) {
        r2.set(box); r2.inset(inset, inset);
        float k = Math.min(r2.width() / b.getWidth(), r2.height() / b.getHeight());
        float w = b.getWidth() * k, h = b.getHeight() * k;
        r2.set(box.centerX() - w / 2, box.centerY() - h / 2, box.centerX() + w / 2, box.centerY() + h / 2);
        c.drawBitmap(b, null, r2, image);
    }
    private static String time(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}
