package com.akylas.enforcedoze;

import static com.akylas.enforcedoze.Utils.logToLogcat;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.preference.PreferenceManager;

import androidx.appcompat.widget.Toolbar;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.text.InputType;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ListView;
import android.widget.Toast;

import com.afollestad.materialdialogs.MaterialDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.nanotasks.BackgroundWork;
import com.nanotasks.Completion;
import com.nanotasks.Tasks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

public class BlockAppsActivity extends AppCompatActivity {
    RecyclerView recyclerView;
    SharedPreferences sharedPreferences;
    AppsAdapter blockDozeApps;
    ArrayList<String> blockedPackages;
    ArrayList<AppsItem> listData;
    public static String TAG = "BlockAppsActivity";
    boolean isSuAvailable = false;
    MaterialDialog progressDialog = null;

    private static void log(String message) {
        logToLogcat(TAG, message);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_doze_blocklist_apps);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        recyclerView =  findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setHasFixedSize(true);
        ItemTouchHelper.SimpleCallback simpleItemTouchCallback = new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            public boolean onMove(RecyclerView recyclerView,
                                  RecyclerView.ViewHolder viewHolder, RecyclerView.ViewHolder target) {
                return false;// true if moved, false otherwise
            }

            @Override
            public void onSwiped(RecyclerView.ViewHolder viewHolder, int swipeDir) {
                verifyAndRemovePackage(listData.get(viewHolder.getLayoutPosition()).getAppPackageName());
            }
        };
        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(simpleItemTouchCallback);
        itemTouchHelper.attachToRecyclerView(recyclerView);
        blockedPackages = new ArrayList<>();
        listData = new ArrayList<>();
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        blockDozeApps = new AppsAdapter(this, listData);
        recyclerView.setAdapter(blockDozeApps);
        loadPackagesFromBlockList();
        isSuAvailable = sharedPreferences.getBoolean("isSuAvailable", false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Utils.notifyServiceAppBlocklistChanged(getApplicationContext());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.block_app_doze_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_add_app_doze_blocklist) {
            Intent chooser = new Intent(BlockAppsActivity.this, PackageChooserActivity.class);
            chooser.putExtra(PackageChooserActivity.EXTRA_MULTI_SELECT, true);
            // Apps already blocked come back ticked and locked, so they cannot be added twice.
            chooser.putExtra(PackageChooserActivity.EXTRA_PRESELECTED_PACKAGES,
                    blockedPackages.toArray(new String[0]));
            chooser.putExtra(PackageChooserActivity.EXTRA_TITLE, getString(R.string.app_blocklist_setting_title));
            startActivityForResult(chooser, 505);
        } else if (id == R.id.action_add_app_doze_blocklist_package) {
            showManuallyAddPackageDialog();
        } else if (id == R.id.action_remove_app_doze_blocklist_package) {
            showManuallyRemovePackageDialog();
        } else if (id == R.id.action_app_doze_blacklist_more_info) {
            displayDialog(getString(R.string.app_blocklist_dialog_title), getString(R.string.app_blocklist_dialog_text));
        } else if (id == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (data != null) {
            if (requestCode == 505) {
                String[] packages = data.getStringArrayExtra(PackageChooserActivity.RESULT_PACKAGE_NAMES);
                if (packages != null) {
                    addPackages(Arrays.asList(packages));
                } else {
                    // Single-select fallback, still used by the manual-entry paths.
                    verifyAndAddPackage(data.getStringExtra(PackageChooserActivity.RESULT_PACKAGE_NAME));
                }
            } else if (requestCode == 506) {
                String pkg = data.getStringExtra("package_name");
                verifyAndRemovePackage(pkg);
            }
        }
    }

    public void loadPackagesFromBlockList() {
        log("Loading blocked packages...");
        progressDialog = new MaterialDialog.Builder(this)
                .title(getString(R.string.please_wait_text))
                .autoDismiss(false)
                .cancelable(false)
                .content(getString(R.string.loading_blocked_packages))
                .progress(true, 0)
                .show();

        Tasks.executeInBackground(BlockAppsActivity.this, new BackgroundWork<List<String>>() {
            @Override
            public List<String> doInBackground() throws Exception {
                List<String> packages = new ArrayList<>(sharedPreferences.getStringSet("dozeAppBlockList", new LinkedHashSet<String>()));
                return new ArrayList<>(new LinkedHashSet<>(packages));
            }
        }, new Completion<List<String>>() {
            @Override
            public void onSuccess(Context context, List<String> result) {
                if (progressDialog != null) {
                    progressDialog.dismiss();
                }

                if (!result.isEmpty()) {
                    if (!listData.isEmpty() || !blockedPackages.isEmpty()) {
                        listData.clear();
                        blockedPackages.clear();
                    }
                    for (String r : result) {
                        AppsItem appItem = new AppsItem();
                        appItem.setAppPackageName(r);
                        blockedPackages.add(r);
                        try {
                            appItem.setAppName(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(r, PackageManager.GET_META_DATA)).toString());
                        } catch (PackageManager.NameNotFoundException e) {
                            appItem.setAppName("System package");
                        }
                        listData.add(appItem);
                    }
                    blockDozeApps.notifyDataSetChanged();
                }

                log("Blocked packages: " + listData.size() + " packages in total");
            }

            @Override
            public void onError(Context context, Exception e) {
                Log.e(TAG, "Error loading blocklist packages: " + e.getMessage());

            }
        });
    }

    public void showManuallyAddPackageDialog() {
        new MaterialDialog.Builder(this)
                .title(getString(R.string.block_app_dialog_title))
                .content(R.string.name_of_package_doze_block)
                .inputType(InputType.TYPE_CLASS_TEXT)
                .input("com.spotify.music", "", false, new MaterialDialog.InputCallback() {
                    @Override
                    public void onInput(MaterialDialog dialog, CharSequence input) {
                        verifyAndAddPackage(input.toString());
                    }
                }).show();
    }

    public void showManuallyRemovePackageDialog() {
        new MaterialDialog.Builder(this)
                .title(getString(R.string.block_app_dialog_title))
                .content(R.string.name_of_package_doze_block_remove)
                .inputType(InputType.TYPE_CLASS_TEXT)
                .input("com.spotify.music", "", false, new MaterialDialog.InputCallback() {
                    @Override
                    public void onInput(MaterialDialog dialog, CharSequence input) {
                        verifyAndRemovePackage(input.toString());
                    }
                }).show();
    }


    public void verifyAndAddPackage(String packageName) {
        if (blockedPackages.contains(packageName)) {
            displayDialog(getString(R.string.info_text), getString(R.string.app_already_in_blocklist));
        } else {
            modifyBlockList(packageName, false);
            loadPackagesFromBlockList();
        }
    }

    public void verifyAndRemovePackage(String packageName) {
        if (!blockedPackages.contains(packageName)) {
            displayDialog(getString(R.string.info_text), getString(R.string.app_doesnt_exist_in_blocklist));
        } else {
            modifyBlockList(packageName, true);
            loadPackagesFromBlockList();
        }
    }

    /**
     * Adds a whole selection in one write instead of one preference commit (and one full list
     * reload) per app, which is what the old single-pick flow did.
     */
    public void addPackages(List<String> packageNames) {
        List<String> added = new ArrayList<>();
        for (String packageName : packageNames) {
            if (packageName != null && !packageName.trim().isEmpty() && !blockedPackages.contains(packageName)) {
                blockedPackages.add(packageName);
                added.add(packageName);
            }
        }

        if (added.isEmpty()) {
            displayDialog(getString(R.string.info_text), getString(R.string.app_already_in_blocklist));
            return;
        }

        log("Adding " + added.size() + " app(s) to the Doze app blocklist: " + added);
        sharedPreferences.edit().putStringSet("dozeAppBlockList", new LinkedHashSet<>(blockedPackages)).apply();
        Utils.notifyServiceAppBlocklistChanged(getApplicationContext());
        loadPackagesFromBlockList();
        Toast.makeText(this,
                getResources().getQuantityString(R.plurals.apps_added_to_blocklist, added.size(), added.size()),
                Toast.LENGTH_SHORT).show();
    }

    public void modifyBlockList(String packageName, boolean remove) {
        if (remove) {
            log("Removing app " + packageName + " to Doze app blocklist");
            blockedPackages.remove(packageName);
            sharedPreferences.edit().putStringSet("dozeAppBlockList", new LinkedHashSet<>(blockedPackages)).apply();
        } else {
            blockedPackages.add(packageName);
            log("Adding app " + packageName + " to Doze app blocklist");
            sharedPreferences.edit().putStringSet("dozeAppBlockList", new LinkedHashSet<>(blockedPackages)).apply();
        }
    }

    public void displayDialog(String title, String message) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(title);
        builder.setMessage(message);
        builder.setPositiveButton(getString(R.string.close_button_text), null);
        builder.show();
    }
}
