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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/** tile dashboard. two per row, yeah it's dumb but it works */
public class HubDashboardFragment extends Fragment {

    /** tile accents cycle through these three */
    private static final int[] ACCENTS = {
            R.color.petal_accent_violet,
            R.color.petal_accent_pink,
            R.color.petal_accent_teal,
    };

    private static final class Entry {
        final int titleRes;
        final int summaryRes;
        final int iconRes;
        final String fragmentClass;
        final String targetPackage;
        final String targetClass;
        final String screen;

        Entry(int titleRes, int summaryRes, int iconRes, String fragmentClass) {
            this(titleRes, summaryRes, iconRes, fragmentClass, null, null, null);
        }

        Entry(int titleRes, int summaryRes, int iconRes, String fragmentClass,
                String targetPackage, String targetClass) {
            this(titleRes, summaryRes, iconRes, fragmentClass, targetPackage, targetClass, null);
        }

        Entry(int titleRes, int summaryRes, int iconRes, String fragmentClass,
                String targetPackage, String targetClass, String screen) {
            this.titleRes = titleRes;
            this.summaryRes = summaryRes;
            this.iconRes = iconRes;
            this.fragmentClass = fragmentClass;
            this.targetPackage = targetPackage;
            this.targetClass = targetClass;
            this.screen = screen;
        }
    }

    private static final Entry[] PERSONALIZATION = {
            new Entry(R.string.category_quick_settings, R.string.quick_settings_summary,
                    R.drawable.ic_hub_qs, QuickSettingsFragment.class.getName()),
            new Entry(R.string.category_launcher, R.string.launcher_summary,
                    R.drawable.ic_hub_launcher, LauncherFragment.class.getName()),
            new Entry(R.string.category_status_bar, R.string.status_bar_summary,
                    R.drawable.ic_hub_statusbar, StatusBarFragment.class.getName()),
            new Entry(R.string.category_dialogs, R.string.dialogs_summary,
                    R.drawable.ic_hub_dialogs, DialogsFragment.class.getName()),
            new Entry(R.string.category_island, R.string.island_summary,
                    R.drawable.ic_hub_island, IslandFragment.class.getName()),
            new Entry(R.string.depth_studio_title, R.string.depth_studio_summary,
                    R.drawable.ic_hub_lockscreen, null,
                    "org.petalos.depth", "org.petalos.depth.DepthStudioActivity"),
    };

    /** the new advanced corner. launches the standalone blocker app. */
    private static final Entry[] ADVANCED = {
            new Entry(R.string.category_blocker, R.string.blocker_summary,
                    R.drawable.ic_hub_advanced, null,
                    "org.petalos.advanced", "org.petalos.advanced.AdvancedActivity",
                    "home"),
            new Entry(R.string.category_filter_lists, R.string.filter_lists_summary,
                    R.drawable.ic_hub_filterlists, null,
                    "org.petalos.advanced", "org.petalos.advanced.AdvancedActivity",
                    "sources"),
            new Entry(R.string.category_blocked_sites, R.string.blocked_sites_summary,
                    R.drawable.ic_hub_blocksites, null,
                    "org.petalos.advanced", "org.petalos.advanced.AdvancedActivity",
                    "sites"),
    };

    private static final Entry[] DEVICE = {
            new Entry(R.string.category_power, R.string.power_summary,
                    R.drawable.ic_hub_power, PowerFragment.class.getName()),
            new Entry(R.string.category_bridge, R.string.bridge_summary,
                    R.drawable.ic_hub_qs, BridgeFragment.class.getName()),
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.hub_dashboard, container, false);

        FrameLayout hero = root.findViewById(R.id.hub_hero_card);
        hero.setOnClickListener(view -> openFragment(LockscreenFragment.class.getName(),
                getString(R.string.category_lockscreen)));

        LinearLayout sections = root.findViewById(R.id.hub_sections);
        buildSection(inflater, sections, R.string.dashboard_section_personalization,
                PERSONALIZATION);
        buildSection(inflater, sections, R.string.dashboard_section_advanced, ADVANCED);
        buildSection(inflater, sections, R.string.dashboard_section_device, DEVICE);
        return root;
    }

    private void buildSection(LayoutInflater inflater, LinearLayout parent, int titleRes,
            Entry[] entries) {
        TextView header = (TextView) inflater.inflate(R.layout.hub_section_header, parent, false);
        header.setText(getString(titleRes));
        parent.addView(header);

        int index = 0;
        while (index < entries.length) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = getResources().getDimensionPixelSize(R.dimen.petal_card_gap);
            parent.addView(row, rowParams);

            addTile(inflater, row, entries[index], index);
            if (index + 1 < entries.length) {
                addTile(inflater, row, entries[index + 1], index + 1);
            } else {
                View spacer = new View(requireContext());
                LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(
                        0, 1, 1f);
                spacerParams.setMarginStart(getResources()
                        .getDimensionPixelSize(R.dimen.petal_card_gap));
                row.addView(spacer, spacerParams);
            }
            index += 2;
        }
    }

    private void addTile(LayoutInflater inflater, LinearLayout row, Entry entry, int position) {
        View tile = inflater.inflate(R.layout.hub_card_tile, row, false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                getResources().getDimensionPixelSize(R.dimen.petal_tile_height), 1f);
        if (position % 2 == 1) {
            params.setMarginStart(getResources().getDimensionPixelSize(R.dimen.petal_card_gap));
        }
        row.addView(tile, params);

        int accent = requireContext().getColor(ACCENTS[position % ACCENTS.length]);

        ImageView icon = tile.findViewById(R.id.hub_tile_icon);
        icon.setImageResource(entry.iconRes);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(accent));

        TextView title = tile.findViewById(R.id.hub_tile_title);
        title.setText(entry.titleRes);
        TextView summary = tile.findViewById(R.id.hub_tile_summary);
        summary.setText(entry.summaryRes);

        tile.setOnClickListener(view -> {
            if (entry.fragmentClass != null) {
                openFragment(entry.fragmentClass, getString(entry.titleRes));
            } else if (entry.targetPackage != null) {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.setClassName(entry.targetPackage, entry.targetClass);
                if (entry.screen != null) {
                    intent.putExtra(":petal:screen", entry.screen);
                }
                startActivity(intent);
            }
        });
    }

    private void openFragment(String fragmentClass, CharSequence title) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setClass(requireContext(), HubActivity.class);
        intent.putExtra(HubActivity.EXTRA_SHOW_FRAGMENT, fragmentClass);
        if (title != null) {
            intent.putExtra(HubActivity.EXTRA_SHOW_FRAGMENT_TITLE, title);
        }
        startActivity(intent);
    }
}
