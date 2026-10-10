package dev.thom.clipboardglass;

import android.content.SharedPreferences;

/** Preferences stay in the framework's private store, never a public file or broadcast. */
final class ModuleSettings {
    static final String GROUP = "options";
    final SharedPreferences preferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener;
    private volatile Runnable onChanged;

    ModuleSettings(SharedPreferences preferences) {
        this.preferences = preferences;
        listener = (prefs, key) -> {
            Runnable callback = onChanged;
            if (callback != null) callback.run();
        };
        preferences.registerOnSharedPreferenceChangeListener(listener);
    }

    void onChanged(Runnable callback) { onChanged = callback; }
    boolean enabled() { return preferences.getBoolean("enabled", true); }
    boolean blur() { return preferences.getBoolean("blur", true); }
    int corner() { return Math.max(4, Math.min(20, preferences.getInt("corner", 8))); }
    int opacity() { return Math.max(0, Math.min(40, preferences.getInt("opacity", 20))); }
    int size() { return Math.max(60, Math.min(160, preferences.getInt("size", 100))); }
    int fontSize() { return Math.max(10, Math.min(24, preferences.getInt("font_size", 14))); }
    int fontWeight() { return Math.max(100, Math.min(900, preferences.getInt("font_weight", 500))); }
    static int durationMillis(SharedPreferences prefs) {
        int legacy = prefs.getInt("duration", 3);
        int fallback = legacy >= 1 && legacy <= 5 ? legacy * 1000 : 3000;
        int millis = prefs.getInt("duration_ms", fallback);
        return Math.max(1000, Math.min(5000, Math.round(millis / 100f) * 100));
    }
    int durationMillis() { return durationMillis(preferences); }
    int materialKey() { return (blur() ? 1 : 0) + corner() * 4 + opacity() * 128; }
}
