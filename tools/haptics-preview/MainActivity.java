package org.petalos.haptics.preview;

import android.app.Activity;
import android.os.Bundle;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.android.server.vibrator.PetalRichTapPattern;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity {
    private Vibrator mVibrator;
    private JSONObject mProfiles;
    private float mStrength = 1f;
    private final String[] mNames = {"Click", "Thud", "Spin", "Quick rise", "Slow rise",
            "Quick fall", "Tick", "Fine tick", "Default tap — stronger"};

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        mVibrator = getSystemService(VibratorManager.class).getDefaultVibrator();
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding * 2, padding, padding);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
        text(content, "Petal Haptics Preview", 26);
        text(content, "Preview the shared petalOS curves. This app does not change system haptics.", 16);
        try (InputStream input = getAssets().open("petal_richtap_patterns.json")) {
            mProfiles = new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            String checks = checkProfiles();
            text(content, checks, 14);
            Log.i("PetalHapticsPreview", checks);
        } catch (Exception e) {
            text(content, "Profile check failed: " + e.getMessage(), 16);
            Log.e("PetalHapticsPreview", "Profile checks failed", e);
            return;
        }
        text(content, "Strength", 18);
        SeekBar strength = new SeekBar(this);
        strength.setMax(100);
        strength.setProgress(100);
        strength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean user) {
                mStrength = value / 100f;
            }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        content.addView(strength);
        for (int id = 1; id <= mNames.length; id++) {
            final int selected = id;
            button(content, mNames[id - 1], () -> play(selected));
        }
        button(content, "Native Android click", () -> mVibrator.vibrate(
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK),
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)));
        button(content, "Stop", () -> mVibrator.cancel());
    }

    private void play(int id) {
        try {
            List<PetalRichTapPattern.Sample> samples =
                    PetalRichTapPattern.parse(mProfiles.getJSONObject(Integer.toString(id)));
            long[] times = new long[samples.size()];
            int[] amplitudes = new int[samples.size()];
            for (int i = 0; i < samples.size(); i++) {
                times[i] = samples.get(i).durationMs;
                amplitudes[i] = Math.round(255 * mStrength * samples.get(i).amplitude);
                if (!mVibrator.hasAmplitudeControl() && amplitudes[i] > 0) {
                    amplitudes[i] = 255;
                }
            }
            mVibrator.vibrate(VibrationEffect.createWaveform(times, amplitudes, -1),
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private String checkProfiles() throws JSONException {
        int[] durations = {15, 45, 80, 30, 110, 30, 15, 15, 22};
        for (int id = 1; id <= durations.length; id++) {
            int duration = 0;
            for (PetalRichTapPattern.Sample sample :
                    PetalRichTapPattern.parse(mProfiles.getJSONObject(Integer.toString(id)))) {
                if (sample.durationMs < 1 || sample.durationMs > 5
                        || !Float.isFinite(sample.amplitude)
                        || sample.amplitude < 0 || sample.amplitude > 1) {
                    throw new IllegalStateException("Invalid sample " + id);
                }
                duration += sample.durationMs;
            }
            if (duration != durations[id - 1]) {
                throw new IllegalStateException("Incorrect duration " + id);
            }
        }
        List<JSONObject> invalid = new ArrayList<>();
        JSONObject wrongVersion = copyProfile();
        wrongVersion.getJSONObject("Metadata").put("Version", 2);
        invalid.add(wrongVersion);
        for (String field : new String[] {"Duration", "RelativeTime"}) {
            JSONObject bad = copyProfile();
            event(bad).put(field, -1);
            invalid.add(bad);
        }
        JSONObject badType = copyProfile();
        event(badType).put("Type", "unknown");
        invalid.add(badType);
        JSONObject badIntensity = copyProfile();
        event(badIntensity).getJSONObject("Parameters").put("Intensity", 101);
        invalid.add(badIntensity);
        JSONObject badCurve = copyProfile();
        event(badCurve).getJSONObject("Parameters").getJSONArray("Curve")
                .getJSONObject(1).put("Time", 0);
        invalid.add(badCurve);
        JSONObject badEnd = copyProfile();
        event(badEnd).getJSONObject("Parameters").getJSONArray("Curve")
                .getJSONObject(3).put("Intensity", 0.5);
        invalid.add(badEnd);
        JSONObject overlap = copyProfile();
        overlap.getJSONArray("Pattern").put(overlap.getJSONArray("Pattern").get(0));
        invalid.add(overlap);
        for (JSONObject bad : invalid) {
            try {
                PetalRichTapPattern.parse(bad);
            } catch (JSONException expected) {
                continue;
            }
            throw new IllegalStateException("Invalid profile accepted");
        }
        return "Passed: 9 profiles and 8 invalid-pattern checks.\nAmplitude control: "
                + mVibrator.hasAmplitudeControl();
    }

    private JSONObject copyProfile() throws JSONException {
        return new JSONObject(mProfiles.getJSONObject("1").toString());
    }

    private JSONObject event(JSONObject profile) throws JSONException {
        return profile.getJSONArray("Pattern").getJSONObject(0).getJSONObject("Event");
    }

    private void text(LinearLayout layout, String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setPadding(0, 12, 0, 12);
        layout.addView(view);
    }

    private void button(LinearLayout layout, String name, Runnable action) {
        Button button = new Button(this);
        button.setText(name);
        button.setOnClickListener(view -> action.run());
        layout.addView(button);
    }

    @Override
    protected void onStop() {
        mVibrator.cancel();
        super.onStop();
    }
}
