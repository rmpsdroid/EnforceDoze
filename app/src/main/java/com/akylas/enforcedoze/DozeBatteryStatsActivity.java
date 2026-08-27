package com.akylas.enforcedoze;

import static com.akylas.enforcedoze.Utils.logToLogcat;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.preference.PreferenceManager;

import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;

import com.afollestad.materialdialogs.MaterialDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.nanotasks.BackgroundWork;
import com.nanotasks.Completion;
import com.nanotasks.Tasks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public class DozeBatteryStatsActivity extends AppCompatActivity {

    Set<String> dozeUsageStats;
    ArrayList<String> sortedDozeUsageStats;
    MaterialDialog progressDialog = null;
    RecyclerView mListView;
    DozeStatsAdapter adapter;
    SharedPreferences sharedPreferences;
    SharedPreferences.Editor editor;
    public static String TAG = "EnforceDoze";

    private static void log(String message) {
        logToLogcat(TAG, message);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_doze_battery_stats);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        editor = sharedPreferences.edit();
        dozeUsageStats = PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getStringSet("dozeUsageDataAdvanced", new LinkedHashSet<String>());
        mListView = findViewById(R.id.material_listview);
        adapter = new DozeStatsAdapter();
        mListView.setAdapter(adapter);
        mListView.setLayoutManager(new LinearLayoutManager(this));

        ViewCompat.setOnApplyWindowInsetsListener(mListView, (v, insets) -> {
            if (mListView != null) {
                int bottomInset = insets
                        .getInsets(WindowInsetsCompat.Type.systemBars())
                        .bottom;

                mListView.setPadding(
                        mListView.getPaddingLeft(),
                        mListView.getPaddingTop(),
                        mListView.getPaddingRight(),
                        bottomInset
                );
                mListView.setClipToPadding(false);
            }
            return insets;
        });
        if (!dozeUsageStats.isEmpty()) {
            sortedDozeUsageStats = new ArrayList<>(dozeUsageStats);
            Collections.sort(sortedDozeUsageStats);
            Collections.reverse(sortedDozeUsageStats);
            log("Size: " + sortedDozeUsageStats.size());

            if (sharedPreferences.contains("dozeUsageData")) {
                log("Found old stats data, deleting..");
                editor.remove("dozeUsageData").commit();
            } else if (sharedPreferences.contains("dozeUsageDataNew")) {
                log("Found old stats data, deleting..");
                editor.remove("dozeUsageDataNew").commit();
            }

            if (sortedDozeUsageStats.size() > 100) {
                log("Trimming stats data to most recent 100 entries...");
                int newSize = sortedDozeUsageStats.size() % 2 == 0 ? sortedDozeUsageStats.size() / 2 : (sortedDozeUsageStats.size() / 2) + 1;
                ArrayList<String> tempArrayList1 = new ArrayList<>(sortedDozeUsageStats.subList(0, newSize));
                ArrayList<String> tempArrayList2 = new ArrayList<>(sortedDozeUsageStats);
                tempArrayList2.removeAll(tempArrayList1);
                sortedDozeUsageStats.removeAll(tempArrayList2);
                tempArrayList1.clear();
                tempArrayList2.clear();
                editor.putStringSet("dozeUsageDataAdvanced", new LinkedHashSet<String>(sortedDozeUsageStats));
                editor.apply();
            }

//            if ((sortedDozeUsageStats.size() & 1) == 0) {
                int count = sortedDozeUsageStats.size();
                for (int i = 0; i < count - 1; ) {
                    StatsEntry exitEntry = parseStatsEntry(sortedDozeUsageStats.get(i));
                    StatsEntry enterEntry = parseStatsEntry(sortedDozeUsageStats.get(i + 1));

                    // A single unreadable record must not take the whole screen down with it. The
                    // stored set is left exactly as it is; only the rendering skips past it.
                    if (exitEntry == null || enterEntry == null) {
                        log("Skipping malformed statistics record at index "
                                + (exitEntry == null ? i : i + 1));
                        i = i + 1;
                        continue;
                    }

                    log("Exit data : [" + exitEntry + "]");
                    log("Enter data: [" + enterEntry + "]");

                    boolean isSession = enterEntry.event.equals("ENTER") && exitEntry.event.equals("EXIT");
                    boolean isMaintenance = enterEntry.event.equals("ENTER_MAINTENANCE")
                            && exitEntry.event.equals("EXIT_MAINTENANCE");

                    if (isSession || isMaintenance) {
                        // The list is sorted newest first, so the exit of a pair must not predate
                        // its enter. timeSpentString() throws on a negative duration.
                        if (exitEntry.timestamp < enterEntry.timestamp) {
                            log("Skipping statistics pair with a negative duration at index " + i);
                            i = i + 1;
                            continue;
                        }

                        int batteryUsed = enterEntry.batteryLevel - exitEntry.batteryLevel;
                        DozeStatsCard card = new DozeStatsCard(
                                isMaintenance ? "Doze Session (Maintenance)" : "Doze Session",
                                "Start Time: " + Utils.getDateCurrentTimeZone(enterEntry.timestamp) +
                                        "\nEnd Time: " + Utils.getDateCurrentTimeZone(exitEntry.timestamp) +
                                        "\nTime spent: " + Utils.timeSpentString(enterEntry.timestamp, exitEntry.timestamp) +
                                        "\nBattery used: " + batteryUsed + "%",
                                returnDrawableBattery(batteryUsed)
                        );
                        adapter.addCard(card);
                        i = i + 2;
                    } else {
                        i = i + 1;
                    }
                }
//            } else {
//                log("Missing log entries, redirecting users to old stats activity");
//                startActivity(new Intent(this, DozeStatsActivity.class));
//                finish();
//            }
            mListView.scrollToPosition(0);
        }
    }

    /** One parsed "timestamp,battery,event" record. */
    private static final class StatsEntry {
        final long timestamp;
        final int batteryLevel;
        final String event;

        StatsEntry(long timestamp, int batteryLevel, String event) {
            this.timestamp = timestamp;
            this.batteryLevel = batteryLevel;
            this.event = event;
        }

        @Override
        public String toString() {
            return timestamp + "," + batteryLevel + "," + event;
        }
    }

    /**
     * Validates a stored record before indexing or parsing any of it.
     * <p>
     * Battery levels are written with Float.toString(), so every record holds a decimal such as
     * "25.0". The maintenance branch here used to read them with Integer.valueOf(), which threw
     * NumberFormatException and killed this activity as soon as any maintenance session existed;
     * the ordinary-session branch beside it already parsed them as floats. Parsing is now shared,
     * so the two cannot drift apart again.
     *
     * @return the parsed record, or null when it is malformed or from an older layout
     */
    private StatsEntry parseStatsEntry(String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.split(",");
        if (parts.length < 3) {
            return null;
        }
        try {
            long timestamp = Long.parseLong(parts[0].trim());
            int batteryLevel = (int) Float.parseFloat(parts[1].trim());
            String event = parts[2].trim();
            if (event.isEmpty()) {
                return null;
            }
            return new StatsEntry(timestamp, batteryLevel, event);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.doze_stats_menu_new, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_clear_stats) {
            clearStats();
        } else if (id == R.id.action_switch_stats_ui) {
            startActivity(new Intent(this, DozeStatsActivity.class));
        } else if (id == R.id.action_stats_more_info) {
            showMoreInfoDialog();
        } else if (id == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public void showMoreInfoDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(getString(R.string.doze_stats_battery_icon_meaning_dialog_title));
        builder.setMessage(getString(R.string.doze_stats_battery_icon_meaning_dialog_text));
        builder.setPositiveButton(getString(R.string.close_button_text), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                dialogInterface.dismiss();
            }
        });
        builder.show();
    }

    public Drawable returnDrawableBattery(int bUsage) {
        return (bUsage >= 3) ? ContextCompat.getDrawable(getApplicationContext(), R.drawable.ic_battery_alert_black_48dp) : ContextCompat.getDrawable(getApplicationContext(), R.drawable.ic_battery_charging_full_black_48dp);
    }

    public void clearStats() {
        progressDialog = new MaterialDialog.Builder(this)
                .title(getString(R.string.please_wait_text))
                .cancelable(false)
                .autoDismiss(false)
                .content(getString(R.string.clearing_doze_stats_text))
                .progress(true, 0)
                .show();

        Tasks.executeInBackground(DozeBatteryStatsActivity.this, new BackgroundWork<Boolean>() {
            @Override
            public Boolean doInBackground() throws Exception {
                log("Clearing Doze stats");
                editor.remove("dozeUsageDataAdvanced");
                return editor.commit();
            }
        }, new Completion<Boolean>() {
            @Override
            public void onSuccess(Context context, Boolean result) {
                if (progressDialog != null) {
                    progressDialog.dismiss();
                }
                if (result) {
                    log("Doze stats successfully cleared");
                    Utils.notifyServiceSettingsChanged(context);
                    adapter.clearAll();
                    MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
                    builder.setTitle(getString(R.string.cleared_text));
                    builder.setMessage(getString(R.string.doze_battery_stats_clear_msg));
                    builder.setPositiveButton(getString(R.string.close_button_text), new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            dialogInterface.dismiss();
                        }
                    });
                    builder.show();
                }

            }

            @Override
            public void onError(Context context, Exception e) {
                Log.e(TAG, "Error clearing Doze stats: " + e.getMessage());

            }
        });
    }
}
