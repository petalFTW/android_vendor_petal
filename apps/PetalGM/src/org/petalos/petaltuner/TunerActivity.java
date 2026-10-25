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

package org.petalos.petaltuner;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

import org.petalos.petaltuner.fragments.DashboardFragment;

public class TunerActivity extends CollapsingToolbarBaseActivity implements
        PreferenceFragmentCompat.OnPreferenceStartFragmentCallback,
        PreferenceFragmentCompat.OnPreferenceStartScreenCallback {

    public static final String EXTRA_SHOW_FRAGMENT = ":settings:show_fragment";
    public static final String EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args";
    public static final String EXTRA_SHOW_FRAGMENT_TITLE = ":settings:show_fragment_title";

    private CharSequence mInitialTitle;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.tuner_activity);

        String fragmentClass = getIntent().getStringExtra(EXTRA_SHOW_FRAGMENT);
        Bundle args = getIntent().getBundleExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS);
        if (args == null) {
            args = new Bundle();
        }
        if (fragmentClass == null) {
            fragmentClass = DashboardFragment.class.getName();
        }
        mInitialTitle = getTitle();
        switchToFragment(fragmentClass, args);

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
        intent.setClass(this, TunerActivity.class);
        intent.putExtra(EXTRA_SHOW_FRAGMENT, fragmentClass);
        intent.putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, args);
        if (title != null) {
            intent.putExtra(EXTRA_SHOW_FRAGMENT_TITLE, title);
        }
        startActivity(intent);
    }

    private void switchToFragment(String fragmentClass, Bundle args) {
        Fragment fragment = Fragment.instantiate(this, fragmentClass, args);
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.main_content, fragment);
        transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);
        transaction.commit();
    }
}
