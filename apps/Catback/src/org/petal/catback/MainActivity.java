package org.petal.catback;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.companion.CompanionDeviceManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.petal.catback.audio.AudioBridge;
import org.petal.catback.protocol.WifiBootstrap;
import org.petal.catback.ui.CarUi;

// home screen, nerd stuff hides behind long-pressing the title
public final class MainActivity extends Activity {
    // same roles as the car screen so the phone and the dash read as one thing. ember is the glow in the icon's
    // pipe tips and only ever means connected
    private static final int BACKGROUND = 0xFF0D0E12, SURFACE = 0xFF191B23, SURFACE_HIGH = 0xFF24252D,
            ACCENT = CarUi.ACCENT, ACCENT_CONTAINER = CarUi.ACCENT_CONTAINER, ON_ACCENT = CarUi.ON_ACCENT,
            TEXT = CarUi.TEXT, TEXT2 = CarUi.TEXT2, EMBER = 0xFFF59A52, RIPPLE = 0x29FFFFFF;
    // 1.2 steps, commissioner like the car
    private static final int LABEL = 14, BODY = 16, TITLE = 19, HEAD = 28, DISPLAY = 34;
    private Typeface regular, medium, semibold;
    private static final int SETUP_PERMISSIONS = 2, ASSOCIATE = 3, CAPTURE = 4, AUDIO_PERMISSION = 5, PICK_APK = 6;
    private static final String WALLPAPER_PERMISSION = "android.permission.READ_WALLPAPER_INTERNAL";
    private enum Screen { HOME, SETTINGS, DEVELOPER, PREVIEW }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Screen screen = Screen.HOME;
    private TextView headline, detail, devStatus, logs;
    private Button primary;
    private TextView changeLink, forgetLink;
    private Button installedLink;
    private volatile boolean importing;
    private View access, wallpaper;
    private BluetoothDevice pending;
    private final Runnable update = new Runnable() {
        @Override public void run() {
            if (screen == Screen.HOME && headline != null) refreshHome();
            else if (screen == Screen.SETTINGS) refreshAudioNote();
            else if (screen == Screen.DEVELOPER && devStatus != null) {
                devStatus.setText(ProjectionService.state);
                logs.setText(ProjectionService.logText());
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        regular = font(400); medium = font(500); semibold = font(600);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(true);
        getWindow().setStatusBarColor(BACKGROUND); getWindow().setNavigationBarColor(BACKGROUND);
        showHome();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        processIntent(getIntent());
        handler.post(update);
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); processIntent(intent); }
    private void processIntent(Intent intent) {
        if ("preview".equals(intent.getStringExtra("screen"))) { showPreview(); return; }
        if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(intent.getAction())) start(ProjectionService.USB);
        else if ("tcp".equals(intent.getStringExtra("transport"))) start(ProjectionService.TCP);
        else if ("wireless".equals(intent.getStringExtra("transport"))) {
            // adb bench: --es transport wireless --es host <ip> [--ei port 5288] or --es device <bt mac>
            Intent wireless = new Intent(this, ProjectionService.class).setAction(ProjectionService.WIRELESS);
            if (intent.hasExtra("host")) wireless.putExtra(ProjectionService.EXTRA_HOST, intent.getStringExtra("host"))
                    .putExtra(ProjectionService.EXTRA_PORT, intent.getIntExtra("port", 5288));
            if (intent.hasExtra("device")) wireless.putExtra(ProjectionService.EXTRA_DEVICE, intent.getStringExtra("device"));
            startForegroundService(wireless);
        }
    }
    private void start(String action) { startForegroundService(new Intent(this, ProjectionService.class).setAction(action)); }

    // home
    private void showHome() {
        screen = Screen.HOME;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout layout = column();
        // top bar: wordmark left (long press = dev), settings right
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("catback", TITLE, TEXT, semibold);
        title.setOnLongClickListener(v -> { showDeveloper(); return true; });
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(link("Settings", this::showSettings));
        LinearLayout.LayoutParams barParams = gap(0); barParams.setMarginEnd(dp(-12)); layout.addView(bar, barParams);

        // the car is the screen: its name big, what it's doing under it, the one action
        headline = text("", DISPLAY, TEXT, semibold); headline.setLineSpacing(0, 1.05f);
        layout.addView(headline, gap(56));
        detail = text("", BODY, TEXT2); detail.setLineSpacing(0, 1.25f);
        layout.addView(detail, gap(12));
        LinearLayout carActions = new LinearLayout(this);
        changeLink = link("Change car", this::chooseCar); forgetLink = link("Forget car", this::confirmForget);
        carActions.addView(changeLink); carActions.addView(forgetLink);
        layout.addView(carActions, hang(4));

        primary = new Button(this); filled(primary);
        layout.addView(primary, gap(28));
        installedLink = new Button(this); tonal(installedLink); installedLink.setText("Use installed Android Auto");
        installedLink.setOnClickListener(v -> importInstalled());
        layout.addView(installedLink, gap(12));

        // island, now playing and turn-by-turn all ride on notif access
        LinearLayout setup = new LinearLayout(this); setup.setOrientation(LinearLayout.VERTICAL);
        access = row("Allow notifications on the car display", null, false, v -> openNotificationAccess());
        setup.addView(access, gap(8));
        // 14+ wants all-files for the wallpaper unless we're the rom build
        wallpaper = row("Show my wallpaper in the car", null, false, v -> openWallpaperAccess());
        setup.addView(wallpaper, gap(8));
        layout.addView(setup, gap(24));

        layout.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));
        layout.addView(link("Developer options", this::showDeveloper), hang(24));
        setContentView(scroll(layout));
        refreshHome();
    }
    private void refreshHome() {
        String car = CarLink.address(this);
        boolean linked = car != null;
        String status = ProjectionService.headline;
        if (!Identity.carReady(this)) { refreshIdentitySetup(); return; }
        installedLink.setVisibility(View.GONE);
        detail.setTextColor(linked && status.startsWith("Connected") ? EMBER : TEXT2);
        if (!linked) {
            headline.setText("Set up your car");
            detail.setText("Pair your car with this phone in Bluetooth settings first, then link it here. "
                    + "After that CATBACK starts by itself whenever you get in.");
            primary.setText("Link a car"); primary.setOnClickListener(v -> chooseCar());
        } else {
            headline.setText(CarLink.name(this));
            detail.setText(status.isEmpty() ? "Ready. CATBACK will start when your car connects." : status);
            boolean active = status.startsWith("Connected") || status.startsWith("Connecting") || status.startsWith("Retrying");
            primary.setText(active ? "Disconnect" : "Connect now");
            primary.setOnClickListener(v -> {
                if (active) stopService(new Intent(this, ProjectionService.class));
                else if (bluetoothReady()) CarLink.connect(this);
            });
        }
        access.setVisibility(notificationAccess() ? View.GONE : View.VISIBLE);
        wallpaper.setVisibility(Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()
                && checkSelfPermission(WALLPAPER_PERMISSION) != PackageManager.PERMISSION_GRANTED ? View.VISIBLE : View.GONE);
        changeLink.setVisibility(linked ? View.VISIBLE : View.GONE);
        forgetLink.setVisibility(linked ? View.VISIBLE : View.GONE);
    }
    // no android auto identity = cars hang up on us, so this blocks everything else
    private void refreshIdentitySetup() {
        Date expired = Identity.carExpiry(this);
        headline.setText(expired != null ? "Car identity expired" : "One more step");
        detail.setTextColor(TEXT2);
        detail.setText(expired != null
                ? "The Android Auto identity CATBACK uses ran out on " + DateFormat.getDateInstance().format(expired)
                    + ". Pick a newer Android Auto APK to keep connecting."
                : "Cars only talk to phones carrying Android Auto's identity. Pick an Android Auto APK "
                    + "(or an .apks / .xapk bundle) and CATBACK copies it out. Android Auto never gets installed or run.");
        primary.setText(importing ? "Reading Android Auto…" : "Choose Android Auto APK");
        primary.setEnabled(!importing);
        primary.setOnClickListener(v -> pickApk());
        installedLink.setVisibility(!importing && gearheadInstalled() ? View.VISIBLE : View.GONE);
        access.setVisibility(View.GONE); wallpaper.setVisibility(View.GONE);
        changeLink.setVisibility(View.GONE); forgetLink.setVisibility(View.GONE);
    }
    private boolean gearheadInstalled() {
        try { getPackageManager().getApplicationInfo(Identity.GEARHEAD, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }
    private void pickApk() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try { startActivityForResult(pick, PICK_APK); }
        catch (android.content.ActivityNotFoundException e) { toast("No file picker on this phone??"); }
    }
    private void importInstalled() {
        String apk;
        try { apk = getPackageManager().getApplicationInfo(Identity.GEARHEAD, 0).sourceDir; }
        catch (PackageManager.NameNotFoundException e) { toast("Android Auto isn't installed"); return; }
        importIdentity(() -> new FileInputStream(apk));
    }
    private interface Opener { InputStream open() throws Exception; }
    private void importIdentity(Opener opener) {
        if (importing) return;
        importing = true;
        if (screen == Screen.HOME) refreshHome(); else if (screen == Screen.SETTINGS) showSettings();
        new Thread(() -> {
            String message;
            try (InputStream in = new BufferedInputStream(opener.open(), 1 << 16)) {
                Date until = Identity.importFrom(getApplicationContext(), in);
                message = "Car identity imported, good until " + DateFormat.getDateInstance().format(until)
                        + ". You can uninstall Android Auto now.";
            } catch (Exception e) {
                message = "Couldn't use that: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
            String done = message;
            runOnUiThread(() -> {
                importing = false; toast(done);
                if (screen == Screen.HOME) refreshHome(); else if (screen == Screen.SETTINGS) showSettings();
            });
        }, "Catback-IdentityImport").start();
    }
    private boolean notificationAccess() {
        return getSystemService(android.app.NotificationManager.class).isNotificationListenerAccessGranted(
                new android.content.ComponentName(this, org.petal.catback.car.PetalNotificationListener.class));
    }
    private void openNotificationAccess() {
        Intent intent = Build.VERSION.SDK_INT >= 30
                ? new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            new android.content.ComponentName(this, org.petal.catback.car.PetalNotificationListener.class).flattenToString())
                : new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        try { startActivity(intent); }
        catch (android.content.ActivityNotFoundException e) { startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
        toast("If the switch is greyed out: App info → ⋮ → Allow restricted settings, then try again.");
    }
    private void openWallpaperAccess() {
        if (Build.VERSION.SDK_INT < 30) return;
        toast("Android only shares the wallpaper with apps that have all-files access. CATBACK only reads the wallpaper.");
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName())));
        } catch (android.content.ActivityNotFoundException e) {
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }
    private void confirmForget() {
        new AlertDialog.Builder(this).setMessage("Forget " + CarLink.name(this) + "? CATBACK will stop starting automatically.")
                .setPositiveButton("Forget", (d, w) -> { CarLink.forget(this); refreshHome(); })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    // linking
    private boolean bluetoothReady() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= 33) missing.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        else { missing.add(Manifest.permission.ACCESS_FINE_LOCATION); missing.add(Manifest.permission.ACCESS_COARSE_LOCATION); }
        missing.removeIf(permission -> checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED);
        if (!missing.isEmpty()) { requestPermissions(missing.toArray(new String[0]), SETUP_PERMISSIONS); return false; }
        return true;
    }
    @SuppressLint("MissingPermission") // bluetoothReady checked it
    private void chooseCar() {
        if (!bluetoothReady()) return;
        BluetoothAdapter adapter = getSystemService(BluetoothManager.class).getAdapter();
        if (adapter == null || !adapter.isEnabled()) { toast("Turn on Bluetooth, then pair your car"); return; }
        // wireless-capable cars float to the top
        List<BluetoothDevice> devices = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (BluetoothDevice device : adapter.getBondedDevices()) {
            boolean projection = advertisesWireless(device);
            String label = (device.getName() != null ? device.getName() : device.getAddress())
                    + (projection ? "  • wireless projection" : "");
            if (projection) { devices.add(0, device); labels.add(0, label); }
            else { devices.add(device); labels.add(label); }
        }
        if (devices.isEmpty()) { toast("Pair your car in Bluetooth settings first"); return; }
        new AlertDialog.Builder(this).setTitle("Which is your car?")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> link(devices.get(which)))
                .setNegativeButton(android.R.string.cancel, null).show();
    }
    @SuppressLint("MissingPermission")
    private static boolean advertisesWireless(BluetoothDevice device) {
        ParcelUuid[] uuids = device.getUuids();
        if (uuids != null) for (ParcelUuid uuid : uuids)
            if (WifiBootstrap.SERVICE.equals(uuid.getUuid()) || WifiBootstrap.REVERSED_SERVICE.equals(uuid.getUuid())) return true;
        return false;
    }
    @SuppressWarnings("deprecation")
    private void link(BluetoothDevice device) {
        CompanionDeviceManager cdm = CarLink.manager(this);
        if (cdm == null) { linked(device, false); return; }
        if (CarLink.associated(this, device.getAddress())) { linked(device, true); return; }
        pending = device;
        // cdm prompt, this grant is what lets us start in the background later
        cdm.associate(CarLink.request(device.getAddress()), new CompanionDeviceManager.Callback() {
            @Override public void onDeviceFound(IntentSender chooser) {
                try { startIntentSenderForResult(chooser, ASSOCIATE, null, 0, 0, 0); }
                catch (IntentSender.SendIntentException e) { toast("Couldn't open the car link prompt"); }
            }
            @Override public void onFailure(CharSequence error) {
                toast("Couldn't link the car" + (error != null ? ": " + error : ""));
            }
        }, handler);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_APK) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                android.net.Uri uri = data.getData();
                importIdentity(() -> getContentResolver().openInputStream(uri));
            }
            return;
        }
        if (request == CAPTURE) {
            if (result == RESULT_OK && data != null) startForegroundService(new Intent(this, ProjectionService.class)
                    .setAction(ProjectionService.AUDIO_PROJECTION)
                    .putExtra(ProjectionService.EXTRA_RESULT_CODE, result).putExtra(ProjectionService.EXTRA_RESULT_DATA, data));
            else toast("Capture not allowed; audio stays on the phone");
            return;
        }
        if (request != ASSOCIATE) return;
        BluetoothDevice device = pending; pending = null;
        if (result == RESULT_OK && device != null) linked(device, true);
        else toast("Car not linked");
    }
    @SuppressLint("MissingPermission")
    private void linked(BluetoothDevice device, boolean companion) {
        String name = device.getName() != null ? device.getName() : device.getAddress();
        CarLink.save(this, device.getAddress(), name);
        boolean automatic = companion && CarLink.observe(this, device.getAddress());
        if (!companion) toast("Linked. This phone can't start CATBACK in the background; open the app to connect.");
        else if (!automatic && Build.VERSION.SDK_INT >= 33) toast("Linked, but automatic start couldn't be enabled");
        refreshHome();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        if (request == AUDIO_PERMISSION) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) requestCapture();
            else toast("Audio capture needs the microphone permission slot; audio stays on the phone");
            return;
        }
        if (request != SETUP_PERMISSIONS) return;
        for (int result : results) if (result != PackageManager.PERMISSION_GRANTED) {
            toast("CATBACK needs Bluetooth and Wi-Fi access to reach your car"); return;
        }
        if (screen == Screen.HOME && CarLink.address(this) == null) chooseCar();
    }

    // settings
    private TextView audioNote;
    private void showSettings() {
        screen = Screen.SETTINGS;
        LinearLayout layout = column();
        layout.addView(text("Settings", HEAD, TEXT, semibold), gap(8));
        layout.addView(section("Audio output"), gap(36));
        String current = Settings.audioOutput(this);
        if (AudioBridge.systemAvailable(this)) {
            layout.addView(audioRow("Car speakers", "All phone media plays on the car's audio system.", !Settings.PHONE.equals(current), Settings.CAR), gap(0));
        } else {
            layout.addView(audioRow("Car speakers (through CATBACK)", "Phone media plays on the car's audio system.", Settings.CAR.equals(current), Settings.CAR), gap(0));
            layout.addView(audioRow("Car speakers: all phone audio (root)", "Every app's sound, no per-app limits. Needs Magisk or KernelSU.", Settings.CAR_ROOT.equals(current), Settings.CAR_ROOT), gap(8));
        }
        layout.addView(audioRow("Phone", "Sound stays on the phone or its own Bluetooth connection.", Settings.PHONE.equals(current), Settings.PHONE), gap(8));
        audioNote = text("", LABEL, TEXT2); audioNote.setLineSpacing(0, 1.25f); audioNote.setPadding(dp(4), 0, dp(4), 0);
        layout.addView(audioNote, gap(12));

        layout.addView(section("Car identity"), gap(36));
        Date expiry = Identity.carExpiry(this);
        boolean ready = expiry != null && Identity.carReady(this);
        TextView identity = text(expiry == null ? "Not imported. Stock cars will refuse to connect."
                : (ready ? "From Android Auto, good until " : "Expired on ")
                    + DateFormat.getDateInstance().format(expiry), BODY, TEXT);
        identity.setLineSpacing(0, 1.25f);
        layout.addView(identity, gap(0));
        button(layout, importing ? "Reading Android Auto…" : "Import from an Android Auto APK", this::pickApk).setEnabled(!importing);
        if (gearheadInstalled()) button(layout, "Import from installed Android Auto", this::importInstalled).setEnabled(!importing);
        setContentView(scroll(layout));
        refreshAudioNote();
    }
    private View audioRow(String title, String detail, boolean selected, String value) {
        return row(title, detail, selected, v -> chooseAudio(value));
    }
    private void chooseAudio(String value) {
        if (Settings.CAR_ROOT.equals(value)) {
            // su pops the magisk/ksu prompt, keep it off the ui thread
            toast("Approve root access for CATBACK if prompted");
            new Thread(() -> {
                boolean ok = org.petal.catback.audio.AudioBridge.rootAvailable();
                runOnUiThread(() -> {
                    if (ok) { Settings.setAudioOutput(this, value); showSettings(); }
                    else toast("Root access wasn't granted, so audio output is unchanged");
                });
            }, "Catback-RootCheck").start();
            return;
        }
        Settings.setAudioOutput(this, value);
        if (Settings.CAR.equals(value) && !AudioBridge.systemAvailable(this)) requestCapture();
        else stopService(new Intent(this, ProjectionService.class).setAction(ProjectionService.STOP));
        showSettings();
    }
    private void requestCapture() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION); return;
        }
        startActivityForResult(getSystemService(android.media.projection.MediaProjectionManager.class).createScreenCaptureIntent(), CAPTURE);
    }
    private void refreshAudioNote() {
        if (audioNote == null) return;
        String output = Settings.audioOutput(this);
        audioNote.setText(Settings.CAR_ROOT.equals(output) || (AudioBridge.systemAvailable(this) && Settings.CAR.equals(output))
                ? ProjectionService.audioStatus
                : Settings.CAR.equals(output)
                ? ProjectionService.audioStatus + "\nAndroid shows a screen-capture prompt for this; CATBACK records audio only, never the screen."
                : "Phone audio stays on the phone.");
    }

    // dev stuff
    private void showDeveloper() {
        screen = Screen.DEVELOPER;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout layout = column();
        layout.addView(text("Developer options", HEAD, TEXT, semibold), gap(8));
        layout.setBackgroundColor(BACKGROUND);
        devStatus = text(ProjectionService.state, BODY, TEXT); layout.addView(devStatus, gap(16));
        button(layout, "Start desktop connection", () -> start(ProjectionService.TCP));
        button(layout, "Connect USB head unit", () -> start(ProjectionService.USB));
        button(layout, "Car display preview", this::showPreview);
        button(layout, "Stop projection", () -> stopService(new Intent(this, ProjectionService.class)));
        button(layout, "Copy diagnostics", () -> getSystemService(ClipboardManager.class)
                .setPrimaryClip(ClipData.newPlainText("CATBACK diagnostics", ProjectionService.logText())));
        TextView help = text("Desktop: adb forward tcp:5277 tcp:5277, then start the Desktop Head Unit. "
                + "USB: select CATBACK for the accessory. Wireless bench: tools/wireless_bench_hu.py.", LABEL, TEXT2);
        help.setLineSpacing(0, 1.25f); layout.addView(help, gap(16));
        ScrollView scroll = new ScrollView(this); scroll.setBackground(rounded(SURFACE, 20));
        scroll.setPadding(dp(16), dp(12), dp(16), dp(12)); scroll.setClipToPadding(false);
        logs = text(ProjectionService.logText(), 12, TEXT2);
        logs.setTypeface(Typeface.MONOSPACE); logs.setTextIsSelectable(true); scroll.addView(logs);
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(-1, 0, 1); logParams.topMargin = dp(16);
        layout.addView(scroll, logParams);
        setContentView(layout);
    }
    private void showPreview() {
        screen = Screen.PREVIEW;
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        setContentView(new Preview());
        if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null)
            getWindow().getInsetsController().hide(android.view.WindowInsets.Type.systemBars());
    }
    @Override public void onBackPressed() {
        if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null)
            getWindow().getInsetsController().show(android.view.WindowInsets.Type.systemBars());
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        if (screen == Screen.SETTINGS) showHome();
        else if (screen == Screen.PREVIEW) showDeveloper();
        else if (screen == Screen.DEVELOPER) showHome();
        else super.onBackPressed();
    }
    @Override public void onDestroy() { handler.removeCallbacks(update); super.onDestroy(); }

    // view junk
    // 24dp gutters, capped at 560dp so a tablet or a foldable doesn't stretch the lines out
    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        int side = Math.max(dp(24), (getResources().getDisplayMetrics().widthPixels - dp(560)) / 2);
        layout.setPadding(side, dp(12), side, dp(24));
        return layout;
    }
    private ScrollView scroll(View content) {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BACKGROUND);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -1));
        return scroll;
    }
    private LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(top); return params;
    }
    // text buttons carry 12dp of padding, hang it into the gutter so the words line up with everything else
    private LinearLayout.LayoutParams hang(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.topMargin = dp(top); params.setMarginStart(dp(-12)); return params;
    }
    private TextView text(String value, int size, int color) { return text(value, size, color, regular); }
    private TextView text(String value, int size, int color, Typeface face) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setTypeface(face); return view;
    }
    private TextView section(String label) {
        TextView view = text(label, LABEL, TEXT2, medium); view.setPadding(dp(4), 0, 0, dp(8)); return view;
    }
    private TextView link(String label, Runnable action) {
        TextView view = text(label, LABEL, TEXT2, medium); view.setPadding(dp(12), 0, dp(12), 0);
        view.setGravity(Gravity.CENTER_VERTICAL); view.setMinHeight(dp(48));
        view.setBackground(control(0, 12, TEXT2)); view.setFocusable(true);
        view.setOnClickListener(v -> action.run()); return view;
    }
    private Button button(LinearLayout layout, String label, Runnable action) {
        Button button = new Button(this); tonal(button); button.setText(label);
        button.setOnClickListener(v -> action.run()); layout.addView(button, gap(8));
        return button;
    }
    // the one primary per screen
    private void filled(Button button) {
        pill(button, TITLE - 2, 56);
        button.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}}, new int[]{TEXT2, ON_ACCENT}));
        button.setBackground(control(ACCENT, 28, ON_ACCENT));
    }
    private void tonal(Button button) {
        pill(button, BODY, 52);
        button.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}}, new int[]{TEXT2, TEXT}));
        button.setBackground(control(SURFACE_HIGH, 26, ACCENT));
    }
    private void pill(Button button, int size, int height) {
        button.setAllCaps(false); button.setTextSize(size); button.setTypeface(semibold);
        button.setMinHeight(dp(height)); button.setMinimumHeight(dp(height)); button.setPadding(dp(20), 0, dp(20), 0);
        button.setStateListAnimator(null);
    }
    // tappable row: title, optional detail, chevron unless it's a choice. selected = accent container, same as the car's chips
    private View row(String title, String detail, boolean selected, View.OnClickListener action) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(14), dp(14), dp(14)); row.setMinimumHeight(dp(56));
        row.setBackground(control(selected ? ACCENT_CONTAINER : SURFACE, 20, ACCENT));
        LinearLayout words = new LinearLayout(this); words.setOrientation(LinearLayout.VERTICAL);
        words.addView(text(title, BODY, TEXT, medium));
        if (detail != null) {
            TextView line = text(detail, LABEL, TEXT2); line.setPadding(0, dp(2), 0, 0); line.setLineSpacing(0, 1.2f);
            words.addView(line);
        }
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        if (detail == null) {
            ImageView chevron = new ImageView(this); chevron.setImageResource(R.drawable.car_chevron);
            chevron.setRotation(-90); chevron.setImageTintList(ColorStateList.valueOf(TEXT2));
            row.addView(chevron, new LinearLayout.LayoutParams(dp(24), dp(24)));
        }
        row.setFocusable(true); row.setClickable(true); row.setSelected(selected);
        if (detail != null && Build.VERSION.SDK_INT >= 30) row.setStateDescription(selected ? "Selected" : "Not selected");
        row.setOnClickListener(action);
        return row;
    }
    // ripple for press + hover, a ring for keyboard/dpad focus that shows up at once
    private Drawable control(int fill, int radius, int ring) {
        GradientDrawable outline = rounded(0, radius - 3); outline.setStroke(dp(2), ring);
        LayerDrawable focused = new LayerDrawable(new Drawable[]{rounded(fill, radius), outline});
        focused.setLayerInset(1, dp(3), dp(3), dp(3), dp(3));
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, rounded(fill == ACCENT ? ACCENT_CONTAINER : fill, radius));
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[0], rounded(fill, radius));
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), states, rounded(Color.WHITE, radius));
    }
    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape;
    }
    private Typeface font(int weight) {
        try {
            return new Typeface.Builder(getAssets(), "fonts/Commissioner-VF.ttf")
                    .setFontVariationSettings("'wght' " + weight).setWeight(weight).build();
        } catch (RuntimeException e) { return Typeface.create("sans-serif", weight >= 600 ? Typeface.BOLD : Typeface.NORMAL); }
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }

    // actual car ui, same draw path as the encoder
    private final class Preview extends View {
        private float layout = 800;
        private final CarUi ui = CarUi.preview(MainActivity.this);
        Preview() { super(MainActivity.this); setContentDescription("Car display preview"); }
        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            // letterbox the car frame
            int frameH = Math.min(h, Math.round(w * 9f / 16f)), top = (h - frameH) / 2;
            layout = Math.max(560, Math.min(1600, 480f * w / frameH));
            ui.draw(canvas, 0, top, w, frameH, layout);
            postInvalidateOnAnimation();
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            int h = getHeight(), frameH = Math.min(h, Math.round(getWidth() * 9f / 16f)), top = (h - frameH) / 2;
            float x = event.getX() / getWidth() * layout, y = (event.getY() - top) / frameH * 480f;
            int masked = event.getActionMasked();
            int action = masked == MotionEvent.ACTION_UP ? 1 : masked == MotionEvent.ACTION_MOVE ? 2 : masked == MotionEvent.ACTION_DOWN ? 0 : -1;
            if (action >= 0 && (masked != MotionEvent.ACTION_DOWN || (y >= 0 && y < 480)))
                ui.touch(x, Math.max(0, Math.min(479, y)), action);
            if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }
}
