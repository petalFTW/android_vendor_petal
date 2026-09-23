/*
 * Copyright (C) 2026 petalOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.petalos.advanced;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

/** single activity, swaps fragments based on what the Hub asked for */
public class AdvancedActivity extends AppCompatActivity {

    public static final String EXTRA_SCREEN = ":petal:screen";

    private static final String SEARCH_PREFIX = "org.petalos.hub.SEARCH.";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_advanced);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        final Fragment fragment;
        final String screen = resolveScreen();
        switch (screen) {
            case "sources":
                fragment = new FilterListsFragment();
                setTitle(R.string.screen_sources);
                break;
            case "sites":
                fragment = new BlockedSitesFragment();
                setTitle(R.string.screen_sites);
                break;
            default:
                fragment = new AdvancedHomeFragment();
                setTitle(R.string.screen_home);
                break;
        }

        getSupportFragmentManager().beginTransaction()
                .replace(R.id.advanced_content, fragment)
                .commit();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private String resolveScreen() {
        String screen = getIntent().getStringExtra(EXTRA_SCREEN);
        if (screen == null) {
            final String action = getIntent().getAction();
            if (action != null && action.startsWith(SEARCH_PREFIX)) {
                screen = action.substring(SEARCH_PREFIX.length());
            }
        }
        return screen == null ? "home" : screen;
    }
}
