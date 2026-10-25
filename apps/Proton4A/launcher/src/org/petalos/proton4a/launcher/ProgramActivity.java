package org.petalos.proton4a.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Tiny per-program APK: hands its bundled EXE to the Proton4A runtime app. */
public final class ProgramActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setClassName("org.petalos.proton4a",
                "org.petalos.proton4a.runtime.RuntimeActivity");
        intent.putExtra("org.petalos.proton4a.PROGRAM_PACKAGE", getPackageName());
        startActivity(intent);
        finish();
    }
}
