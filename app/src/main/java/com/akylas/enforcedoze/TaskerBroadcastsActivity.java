package com.akylas.enforcedoze;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

public class TaskerBroadcastsActivity extends AppCompatActivity {

    ArrayList<TaskerBroadcastsItem> items;
    ListView listView;
    TaskerBroadcastsAdapter taskerBroadcastsAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tasker_broadcasts);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        listView = (ListView) findViewById(R.id.listViewBroadcasts);
        items = new ArrayList<>();

        final String authToken = AutomationSecurity.getAutomationAuthToken(this);

        items.add(new TaskerBroadcastsItem(
                AutomationSecurity.TARGET_PACKAGE_ITEM,
                getPackageName()
                        + "\n\nTap this item to copy the required target package. "
                        + "Set Tasker's Package field to this value for every EnforceDoze "
                        + "automation broadcast before adding authToken."
        ));

        items.add(new TaskerBroadcastsItem(
                AutomationSecurity.EXTRA_AUTH_TOKEN,
                authToken != null
                        ? "Tap this item to copy the per-install authentication token. "
                                + "Keep this token private. Set Tasker's Package field first, "
                                + "then add this value as the authToken extra."
                        : "Authentication token unavailable. EnforceDoze automation fails closed until "
                                + "the token can be stored."
        ));

        items.add(new TaskerBroadcastsItem(
                Utils.ACTION_ENABLE_FORCEDOZE,
                "Broadcast values required:\nauthToken"
        ));

        items.add(new TaskerBroadcastsItem(
                Utils.ACTION_DISABLE_FORCEDOZE,
                "Broadcast values required:\nauthToken"
        ));

        items.add(new TaskerBroadcastsItem(
                Utils.ACTION_ADD_WHITELIST,
                "Broadcast values required:\nauthToken\npackageName\n\n"
                        + "packageName has to be the full package name of the installed app "
                        + "you want to add to the whitelist"
        ));

        items.add(new TaskerBroadcastsItem(
                Utils.ACTION_REMOVE_WHITELIST,
                "Broadcast values required:\nauthToken\npackageName\n\n"
                        + "packageName has to be the full package name of the installed app "
                        + "you want to remove from the whitelist"
        ));

        items.add(new TaskerBroadcastsItem(
                Utils.ACTION_CHANGE_SETTING,
                "Broadcast values required:\nauthToken\nsettingName\nsettingValue\n\n"
                        + "settingName can be one of the following:"
                        + "\n1) turnOffDataInDoze"
                        + "\n2) turnOffWiFiInDoze"
                        + "\n3) ignoreLockscreenTimeout"
                        + "\n4) dozeEnterDelay"
                        + "\n5) useAutoRotateAndBrightnessFix"
                        + "\n6) enableSensors"
                        + "\n7) disableWhenCharging"
                        + "\n8) showPersistentNotif"
                        + "\n\nsettingValue can be one of the following:"
                        + "\n1) true"
                        + "\n2) false"
                        + "\n3) an integer from 0 to "
                        + AutomationSecurity.MAX_DOZE_ENTER_DELAY_SECONDS
                        + " seconds (ONLY in case of dozeEnterDelay)"
        ));

        taskerBroadcastsAdapter = new TaskerBroadcastsAdapter(this, items);
        listView.setAdapter(taskerBroadcastsAdapter);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                String itemName = ((TextView) view.findViewById(R.id.broadcastName))
                        .getText()
                        .toString();

                String clipboardValue = itemName;
                String clipboardLabel = "fd_broadcast";
                String toastText = "Copied broadcast!";

                if (AutomationSecurity.TARGET_PACKAGE_ITEM.equals(itemName)) {
                    clipboardValue = getPackageName();
                    clipboardLabel = "fd_target_package";
                    toastText = "Copied target package!";
                } else if (AutomationSecurity.EXTRA_AUTH_TOKEN.equals(itemName)) {
                    if (authToken == null) {
                        Toast.makeText(
                                getApplicationContext(),
                                "Authentication token unavailable",
                                Toast.LENGTH_SHORT
                        ).show();
                        return;
                    }

                    clipboardValue = authToken;
                    clipboardLabel = "fd_auth_token";
                    toastText = "Copied auth token!";
                }

                ClipboardManager clipboard =
                        (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);

                ClipData data = ClipData.newPlainText(
                        clipboardLabel,
                        clipboardValue
                );

                clipboard.setPrimaryClip(data);

                Toast.makeText(
                        getApplicationContext(),
                        toastText,
                        Toast.LENGTH_SHORT
                ).show();
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        switch (id) {
            case android.R.id.home:
                onBackPressed();
                return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
