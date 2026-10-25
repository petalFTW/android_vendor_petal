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
import android.view.MenuItem;

import androidx.fragment.app.Fragment;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

/** same collapsing toolbar shell the rest of the hub uses */
public class AdvancedActivity extends CollapsingToolbarBaseActivity {

    public static final String EXTRA_SCREEN = ":petal:screen";

    private static final String SEARCH_PREFIX = "org.petalos.hub.SEARCH.";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_advanced);

        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
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
                .replace(R.id.main_content, fragment)
                .commit();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
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
