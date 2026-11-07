package io.github.rukazax.routeveil;

import android.app.Application;

/** Persisted UI flags are not evidence that a VPN survived process death. */
public final class RouteveilApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Settings.initDefaults(this);
        boolean ru=getResources().getConfiguration().getLocales().get(0).getLanguage().equals("ru");
        Settings.prefs(this).edit().putBoolean("connected",false)
            .putString("status",ru?"Отключено":"Disconnected")
            .putString("detail",ru?"Подключение в этом процессе ещё не подтверждено.":"Connection has not been confirmed in this process.")
            .remove("challenge_url").apply();
    }
}
