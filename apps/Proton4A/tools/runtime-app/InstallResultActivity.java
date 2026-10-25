package org.petalos.proton4a.runtime;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Displays PackageInstaller's confirmation and the final app-drawer result. */
public final class InstallResultActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent result = getIntent();
        int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = result.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmation != null) startActivity(confirmation);
            finish();
            return;
        }
        String packageName = result.getStringExtra("program_name");
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.BLACK);
        TextView message = new TextView(this);
        message.setGravity(Gravity.CENTER);
        message.setTextColor(Color.WHITE);
        message.setTextSize(18);
        message.setText(status == PackageInstaller.STATUS_SUCCESS
                ? "Installed. Your Windows EXE now has its own app-drawer icon."
                : "Installation failed: " + result.getStringExtra(
                        PackageInstaller.EXTRA_STATUS_MESSAGE));
        layout.addView(message);
        if (status == PackageInstaller.STATUS_SUCCESS && packageName != null) {
            Button open = new Button(this);
            open.setText("Open Windows app");
            open.setOnClickListener(view -> {
                Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
                if (launch != null) startActivity(launch);
            });
            layout.addView(open);
        }
        setContentView(layout);
    }
}
