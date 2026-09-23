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

package org.petalos.hub;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

/** hosts the hub. only settings can launch it */
public class HubActivity extends CollapsingToolbarBaseActivity implements
        PreferenceFragmentCompat.OnPreferenceStartFragmentCallback,
        PreferenceFragmentCompat.OnPreferenceStartScreenCallback {

    // extras copied from aosp settings, don't rename
    public static final String EXTRA_SHOW_FRAGMENT = ":settings:show_fragment";
    public static final String EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args";
    public static final String EXTRA_SHOW_FRAGMENT_TITLE = ":settings:show_fragment_title";
    public static final String EXTRA_SHOW_FRAGMENT_TITLE_RESID =
            ":settings:show_fragment_title_resid";

    private CharSequence mInitialTitle;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.hub_activity);

        String fragmentClass = getIntent().getStringExtra(EXTRA_SHOW_FRAGMENT);
        Bundle initialArgs = getIntent().getBundleExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS);
        if (initialArgs == null) {
            initialArgs = new Bundle();
        }

        // search results smuggle the fragment class in the action
        final String action = getIntent().getAction();
        if (fragmentClass == null && action != null
                && action.startsWith(PetalHubSearchIndexablesProvider.SEARCH_ACTION_PREFIX)) {
            fragmentClass = action.substring(
                    PetalHubSearchIndexablesProvider.SEARCH_ACTION_PREFIX.length());
        }

        if (fragmentClass == null) {
            fragmentClass = HubDashboardFragment.class.getName();
        }

        setTitleFromIntent(getIntent());

        switchToFragment(fragmentClass, initialArgs);

        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    public boolean onPreferenceStartFragment(PreferenceFragmentCompat caller, Preference pref) {
        startFragment(pref.getFragment(), pref.getExtras(), pref.getTitle());
        return true;
    }

    @Override
    public boolean onPreferenceStartScreen(PreferenceFragmentCompat caller, PreferenceScreen pref) {
        startFragment(pref.getFragment(), pref.getExtras(), pref.getTitle());
        return true;
    }

    @Override
    public void onBackPressed() {
        setTitle(mInitialTitle);
        if (!getSupportFragmentManager().popBackStackImmediate()) {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void startFragment(String fragmentClass, Bundle args, CharSequence title) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setClass(this, HubActivity.class);
        intent.putExtra(EXTRA_SHOW_FRAGMENT, fragmentClass);
        intent.putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, args);
        if (title != null) {
            intent.putExtra(EXTRA_SHOW_FRAGMENT_TITLE, title);
        }
        startActivity(intent);
    }

    private boolean switchToFragment(String fragmentClass, Bundle args) {
        Fragment fragment = Fragment.instantiate(this, fragmentClass, args);
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.main_content, fragment);
        transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);
        transaction.commit();
        return true;
    }

    private void setTitleFromIntent(Intent intent) {
        final int initialTitleResId = intent.getIntExtra(EXTRA_SHOW_FRAGMENT_TITLE_RESID, -1);
        if (initialTitleResId > 0) {
            mInitialTitle = getResources().getString(initialTitleResId);
        } else {
            final CharSequence initialTitle = intent.getCharSequenceExtra(EXTRA_SHOW_FRAGMENT_TITLE);
            mInitialTitle = (initialTitle != null) ? initialTitle : getTitle();
        }
        setTitle(mInitialTitle);
    }
}
