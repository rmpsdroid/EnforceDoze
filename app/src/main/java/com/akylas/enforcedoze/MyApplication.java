package com.akylas.enforcedoze;

import android.content.Context;

public class MyApplication extends android.app.Application {
    private static Context context;

    @Override
    public void onCreate() {
        super.onCreate();
        MyApplication.context = getApplicationContext();
        // Every process entry point goes through Application.onCreate, so initialising here means
        // a service recreated at 03:00 starts logging without anything else having to remember to.
        DiagnosticLogger.init(MyApplication.context);
    }

    public static Context getAppContext() {
        return MyApplication.context;
    }
}
