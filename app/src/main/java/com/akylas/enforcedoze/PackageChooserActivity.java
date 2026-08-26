package com.akylas.enforcedoze;

import static com.akylas.enforcedoze.Utils.logToLogcat;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.afollestad.materialdialogs.MaterialDialog;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.nanotasks.BackgroundWork;
import com.nanotasks.Completion;
import com.nanotasks.Tasks;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Picks installed apps. Runs in one of two modes:
 * <ul>
 *     <li>single (default, unchanged behaviour): tapping a row returns it as {@code package_name}.</li>
 *     <li>multi ({@link #EXTRA_MULTI_SELECT}): checkboxes plus a confirm button, returning every
 *     newly picked app as {@code package_names}.</li>
 * </ul>
 * Both modes get the search field and, in multi mode, a select-all action.
 */
public class PackageChooserActivity extends AppCompatActivity {

    /** Opt in to checkboxes and batch confirmation. */
    public static final String EXTRA_MULTI_SELECT = "multi_select";
    /** Packages already in the caller's list: shown ticked, locked, and never returned. */
    public static final String EXTRA_PRESELECTED_PACKAGES = "preselected_packages";
    /** Toolbar title override. */
    public static final String EXTRA_TITLE = "title";

    /** Single-select result: one package name. */
    public static final String RESULT_PACKAGE_NAME = "package_name";
    /** Multi-select result: every newly picked package name. */
    public static final String RESULT_PACKAGE_NAMES = "package_names";

    public static String TAG = "EnforceDoze";

    /** Remembered per-activity so the choice survives reopening the picker. */
    private static final String PREF_SHOW_SYSTEM_APPS = "showSystemApps";
    /** Locale-aware so app names sort the way the user expects. */
    private static final Collator LABEL_COLLATOR = Collator.getInstance();

    private PackageChooserAdapter adapter;
    private MaterialDialog progressDialog = null;
    private RecyclerView recyclerView;
    private TextView emptyView;
    private ExtendedFloatingActionButton confirmButton;
    private PackageManager pm;
    private boolean multiSelect;
    private Set<String> preselected = new HashSet<>();

    private static void log(String message) {
        logToLogcat(TAG, message);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_package_chooser);

        pm = getPackageManager();
        multiSelect = getIntent().getBooleanExtra(EXTRA_MULTI_SELECT, false);
        String[] preselectedExtra = getIntent().getStringArrayExtra(EXTRA_PRESELECTED_PACKAGES);
        if (preselectedExtra != null) {
            Collections.addAll(preselected, preselectedExtra);
        }

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            String title = getIntent().getStringExtra(EXTRA_TITLE);
            getSupportActionBar().setTitle(title != null ? title : getString(R.string.package_chooser_title));
        }

        recyclerView = findViewById(R.id.recycler_view);
        emptyView = findViewById(R.id.emptyView);
        confirmButton = findViewById(R.id.confirmButton);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));

        adapter = new PackageChooserAdapter(multiSelect, getString(R.string.package_chooser_already_added), pm);
        adapter.setShowSystemApps(getPreferences(MODE_PRIVATE).getBoolean(PREF_SHOW_SYSTEM_APPS, false));
        adapter.setOnItemClicked(item -> {
            Intent intentMessage = new Intent();
            intentMessage.putExtra(RESULT_PACKAGE_NAME, item.getPackageName());
            setResult(RESULT_OK, intentMessage);
            finish();
        });
        adapter.setOnSelectionChanged(this::updateConfirmButton);
        recyclerView.setAdapter(adapter);

        if (multiSelect) {
            confirmButton.setVisibility(View.VISIBLE);
            confirmButton.setOnClickListener(v -> confirmSelection());
        }
        updateConfirmButton(0);

        TextInputEditText searchInput = findViewById(R.id.searchInput);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                adapter.filter(s.toString());
                updateEmptyView();
                invalidateOptionsMenu();
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // Nothing picked is a cancel, so the caller does not add anything.
                setResult(RESULT_CANCELED);
                finish();
            }
        });

        loadInstalledApps();
    }

    private void loadInstalledApps() {
        progressDialog = new MaterialDialog.Builder(this)
                .title(getString(R.string.please_wait_text))
                .autoDismiss(false)
                .cancelable(false)
                .content(R.string.loading_installed_apps_text)
                .progress(true, 0)
                .show();

        Tasks.executeInBackground(PackageChooserActivity.this, new BackgroundWork<List<PackageChooserAdapter.Item>>() {
            @Override
            public List<PackageChooserAdapter.Item> doInBackground() {
                // Every installed app, not just the ones with a launcher icon: plenty of
                // background-heavy apps have no launcher activity, and those are exactly the ones
                // worth blocking during Doze. The system/user split is what keeps the list usable.
                List<ApplicationInfo> installed = pm.getInstalledApplications(PackageManager.GET_META_DATA);

                // Labels are resolved here; icons are left to the adapter, which loads only the
                // rows actually shown - several hundred drawables at once is far too much.
                List<PackageChooserAdapter.Item> items = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (ApplicationInfo info : installed) {
                    if (!seen.add(info.packageName)) {
                        continue;
                    }
                    if (info.packageName.equals(getPackageName())) {
                        // Never offer EnforceDoze itself; blocking or suspending it would stop the
                        // very service that is meant to undo the change.
                        continue;
                    }
                    items.add(new PackageChooserAdapter.Item(
                            info.packageName,
                            String.valueOf(pm.getApplicationLabel(info)),
                            info,
                            isSystemApp(info),
                            preselected.contains(info.packageName)));
                }
                Collections.sort(items, (a, b) -> LABEL_COLLATOR.compare(a.label, b.label));
                return items;
            }
        }, new Completion<List<PackageChooserAdapter.Item>>() {
            @Override
            public void onSuccess(Context context, List<PackageChooserAdapter.Item> result) {
                if (progressDialog != null) {
                    progressDialog.dismiss();
                }
                adapter.submit(result);
                updateEmptyView();
                invalidateOptionsMenu();
                log("Package chooser loaded " + result.size() + " apps, " + preselected.size() + " already in the list");
            }

            @Override
            public void onError(Context context, Exception e) {
                if (progressDialog != null) {
                    progressDialog.dismiss();
                }
                Log.e(TAG, "Error loading packages: " + e.getMessage());
            }
        });
    }

    /**
     * An app that shipped with the ROM. FLAG_UPDATED_SYSTEM_APP is excluded on purpose: once the
     * user has installed their own update over a preloaded app (a browser, a launcher) it behaves
     * like any other user app and belongs in the default list.
     */
    private static boolean isSystemApp(ApplicationInfo info) {
        boolean system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        boolean updated = (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        return system && !updated;
    }

    private void updateEmptyView() {
        boolean empty = adapter.getItemCount() == 0;
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void updateConfirmButton(int selectedCount) {
        if (!multiSelect) {
            return;
        }
        confirmButton.setText(selectedCount == 0
                ? getString(R.string.package_chooser_add_none)
                : getString(R.string.package_chooser_add_count, selectedCount));
        confirmButton.setEnabled(selectedCount > 0);
    }

    private void confirmSelection() {
        ArrayList<String> selected = adapter.getNewlySelectedPackages();
        if (selected.isEmpty()) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        Intent intentMessage = new Intent();
        intentMessage.putExtra(RESULT_PACKAGE_NAMES, selected.toArray(new String[0]));
        setResult(RESULT_OK, intentMessage);
        finish();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // The menu is always inflated: select-all is multi-select only, but the system-apps filter
        // is just as useful when picking a single app.
        getMenuInflater().inflate(R.menu.package_chooser_menu, menu);
        MenuItem selectAll = menu.findItem(R.id.action_toggle_select_all);
        if (selectAll != null) {
            selectAll.setVisible(multiSelect);
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem toggle = menu.findItem(R.id.action_toggle_select_all);
        if (toggle != null) {
            boolean allSelected = adapter.areAllVisibleSelected();
            toggle.setTitle(allSelected
                    ? getString(R.string.package_chooser_deselect_all)
                    : getString(R.string.package_chooser_select_all));
        }
        MenuItem systemApps = menu.findItem(R.id.action_show_system_apps);
        if (systemApps != null) {
            systemApps.setChecked(adapter.isShowingSystemApps());
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_toggle_select_all) {
            adapter.setAllVisibleSelected(!adapter.areAllVisibleSelected());
            invalidateOptionsMenu();
            return true;
        } else if (id == R.id.action_show_system_apps) {
            boolean show = !adapter.isShowingSystemApps();
            adapter.setShowSystemApps(show);
            getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_SHOW_SYSTEM_APPS, show).apply();
            updateEmptyView();
            invalidateOptionsMenu();
            return true;
        } else if (id == android.R.id.home) {
            setResult(RESULT_CANCELED);
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
