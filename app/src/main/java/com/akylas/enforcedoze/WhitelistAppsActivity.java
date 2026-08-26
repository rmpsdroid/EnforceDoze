package com.akylas.enforcedoze;

import static com.akylas.enforcedoze.Utils.logToLogcat;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.text.InputType;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
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

import eu.chainfire.libsuperuser.Shell;

public class WhitelistAppsActivity extends AppCompatActivity {
    RecyclerView recyclerView;
    SharedPreferences sharedPreferences;
    AppsAdapter whitelistAppsAdapter;
    ArrayList<String> whitelistedPackages;
    ArrayList<AppsItem> listData;
    public static String TAG = "EnforceDoze";
    boolean showDozeWhitelistWarning = true;
    boolean isSuAvailable = false;
    MaterialDialog progressDialog = null;private static void log(String message) {
        logToLogcat(TAG, message);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_whitelist_apps);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        recyclerView = findViewById(R.id.recycler_view);
        recyclerView.setHasFixedSize(true);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        whitelistedPackages = new ArrayList<>();
        listData = new ArrayList<>();
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        whitelistAppsAdapter = new AppsAdapter(this, listData);
        recyclerView.setAdapter(whitelistAppsAdapter);
        loadPackagesFromWhitelist();
        isSuAvailable = sharedPreferences.getBoolean("isSuAvailable", false);
        showDozeWhitelistWarning = sharedPreferences.getBoolean("showDozeWhitelistWarning", true);

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

        if (showDozeWhitelistWarning) {
            displayDialog(getString(R.string.whitelisting_text), getString(R.string.whitelisted_apps_restrictions_text));
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putBoolean("showDozeWhitelistWarning", false);
            editor.apply();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.whitelist_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_add_whitelist) {
            Intent chooser = new Intent(WhitelistAppsActivity.this, PackageChooserActivity.class);
            chooser.putExtra(PackageChooserActivity.EXTRA_MULTI_SELECT, true);
            chooser.putExtra(PackageChooserActivity.EXTRA_PRESELECTED_PACKAGES,
                    whitelistedPackages.toArray(new String[0]));
            chooser.putExtra(PackageChooserActivity.EXTRA_TITLE, getString(R.string.whitelist_apps_setting_text));
            startActivityForResult(chooser, 999);
        } else if (id == R.id.action_add_whitelist_package) {
            showManuallyAddPackageDialog();
        } else if (id == R.id.action_remove_whitelist_package) {
            showManuallyRemovePackageDialog();
        } else if (id == R.id.action_whitelist_more_info) {
            displayDialog(getString(R.string.whitelisting_text), getString(R.string.whitelisted_apps_restrictions_text));
        } else if (id == R.id.action_launch_system_whitelist) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));

            onBackPressed();
            return true;
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
            if (requestCode == 999) {
                String[] packages = data.getStringArrayExtra(PackageChooserActivity.RESULT_PACKAGE_NAMES);
                if (packages != null) {
                    addPackages(Arrays.asList(packages));
                } else {
                    verifyAndAddPackage(data.getStringExtra(PackageChooserActivity.RESULT_PACKAGE_NAME));
                }
            } else if (requestCode == 998) {
                String pkg = data.getStringExtra("package_name");
                verifyAndRemovePackage(pkg);
            }
        }
    }

    public void loadPackagesFromWhitelist() {
        log("Loading whitelisted packages...");
        progressDialog = new MaterialDialog.Builder(this)
                .title(getString(R.string.please_wait_text))
                .autoDismiss(false)
                .cancelable(false)
                .content(R.string.loading_whitelisted_packages)
                .progress(true, 0)
                .show();

        Tasks.executeInBackground(WhitelistAppsActivity.this, new BackgroundWork<List<String>>() {
            @Override
            public List<String> doInBackground() throws Exception {
                List<String> output;
                List<String> packages = new ArrayList<>();
                output = Shell.SH.run("dumpsys deviceidle whitelist");
                for (String s : output) {
                    packages.add(s.split(",")[1]);
                }

                return new ArrayList<>(new LinkedHashSet<>(packages));
            }
        }, new Completion<List<String>>() {
            @Override
            public void onSuccess(Context context, List<String> result) {
                if (progressDialog != null) {
                    progressDialog.dismiss();
                }

                if (!result.isEmpty()) {
                    if (!listData.isEmpty() || !whitelistedPackages.isEmpty()) {
                        listData.clear();
                        whitelistedPackages.clear();
                    }
                    for (String r : result) {
                        AppsItem appItem = new AppsItem();
                        appItem.setAppPackageName(r);
                        whitelistedPackages.add(r);
                        try {
                            appItem.setAppName(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(r, PackageManager.GET_META_DATA)).toString());
                        } catch (PackageManager.NameNotFoundException e) {
                            appItem.setAppName("System package");
                        }
                        listData.add(appItem);
                    }
                }
                whitelistAppsAdapter.notifyDataSetChanged();

                log("Whitelisted packages: " + listData.size() + " packages in total");
            }

            @Override
            public void onError(Context context, Exception e) {
                Log.e(TAG, "Error loading packages: " + e.getMessage());

            }
        });
    }

    public void showManuallyAddPackageDialog() {
        new MaterialDialog.Builder(this)
                .title(getString(R.string.whitelist_apps_setting_text))
                .content(R.string.manually_add_package_dialog_text)
                .inputType(InputType.TYPE_CLASS_TEXT)
                .cancelable(true)
                .input("com.spotify.music", "", false, new MaterialDialog.InputCallback() {
                    @Override
                    public void onInput(MaterialDialog dialog, CharSequence input) {
                        verifyAndAddPackage(input.toString());
                    }
                }).show();
    }

    public void showManuallyRemovePackageDialog() {
        new MaterialDialog.Builder(this)
                .title(getString(R.string.whitelist_apps_setting_text))
                .content(R.string.manually_remove_package_dialog_text)
                .inputType(InputType.TYPE_CLASS_TEXT)
                .cancelable(true)
                .input("com.spotify.music", "", false, new MaterialDialog.InputCallback() {
                    @Override
                    public void onInput(MaterialDialog dialog, CharSequence input) {
                        verifyAndRemovePackage(input.toString());
                    }
                }).show();
    }


    public void verifyAndAddPackage(String packageName) {
        if (whitelistedPackages.contains(packageName)) {
            displayDialog(getString(R.string.info_text), getString(R.string.app_already_whitelisted_text));
        } else {
            modifyWhitelist(packageName, false);
            loadPackagesFromWhitelist();
        }
    }

    public void verifyAndRemovePackage(String packageName) {
        if (!whitelistedPackages.contains(packageName)) {
            displayDialog(getString(R.string.info_text), getString(R.string.app_not_whitelisted_text));
        } else {
            modifyWhitelist(packageName, true);
            loadPackagesFromWhitelist();
        }
    }

    /**
     * Whitelisting is a shell command per package rather than a preference write, so these are
     * issued as a batch and the list is reloaded once at the end instead of after every app.
     */
    public void addPackages(List<String> packageNames) {
        List<String> added = new ArrayList<>();
        for (String packageName : packageNames) {
            if (packageName != null && !packageName.trim().isEmpty() && !whitelistedPackages.contains(packageName)) {
                modifyWhitelist(packageName, false);
                added.add(packageName);
            }
        }

        if (added.isEmpty()) {
            displayDialog(getString(R.string.info_text), getString(R.string.app_already_whitelisted_text));
            return;
        }

        log("Adding " + added.size() + " app(s) to the Doze whitelist: " + added);
        Toast.makeText(this,
                getResources().getQuantityString(R.plurals.apps_added_to_whitelist, added.size(), added.size()),
                Toast.LENGTH_SHORT).show();
        // The dumpsys calls are asynchronous; give them a moment before re-reading the list back.
        recyclerView.postDelayed(this::loadPackagesFromWhitelist, 750);
    }

    public void modifyWhitelist(String packageName, boolean remove) {
        if (remove) {
            log("Removing app " + packageName + " from Doze whitelist");
            executeCommand("dumpsys deviceidle whitelist -" + packageName);
        } else {
            log("Adding app " + packageName + " to Doze whitelist");
            executeCommand("dumpsys deviceidle whitelist +" + packageName);
        }
    }

    public void displayDialog(String title, String message) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(title);
        builder.setMessage(message);
        builder.setPositiveButton(getString(R.string.close_button_text), null);
        builder.show();
    }

    public void executeCommand(final String command) {
        if (Utils.isDeviceRunningOnN() && isSuAvailable) {
            Shell.SU.run(command);
        } else {
            Shell.SH.run(command);
        }
    }
}
