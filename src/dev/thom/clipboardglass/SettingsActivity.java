package dev.thom.clipboardglass;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Insets;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.function.Consumer;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class SettingsActivity extends Activity {
    private static volatile SharedPreferences remote;
    private static boolean registered;
    private static java.lang.ref.WeakReference<SettingsActivity> current = new java.lang.ref.WeakReference<>(null);
    private final ArrayList<View> controls = new ArrayList<>();
    private LinearLayout content;
    private TextView status;
    private boolean binding;

    @Override public void onCreate(Bundle saved) {
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        setTheme(night ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(saved);
        current = new java.lang.ref.WeakReference<>(this);
        render();
        if (!registered) {
            registered = true;
            XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                @Override public void onServiceBind(XposedService service) {
                    try { remote = service.getRemotePreferences(ModuleSettings.GROUP); }
                    catch (RuntimeException ignored) { remote = null; }
                    SettingsActivity activity = current.get();
                    if (activity != null) activity.runOnUiThread(activity::render);
                }
                @Override public void onServiceDied(XposedService service) {
                    remote = null;
                    SettingsActivity activity = current.get();
                    if (activity != null) activity.runOnUiThread(activity::render);
                }
            });
        }
    }

    @Override protected void onResume() {
        super.onResume();
        current = new java.lang.ref.WeakReference<>(this);
        render();
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void render() {
        binding = true;
        controls.clear();
        ScrollView scroll = new ScrollView(this);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            scroll.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setFocusableInTouchMode(true);
        content.setPadding(dp(24), dp(28), dp(24), dp(36));
        scroll.addView(content);
        setContentView(scroll);
        label("复制预览", 30, true);
        status = label(remote == null ? "等待模块连接，请在 LSPosed 中启用复制预览。"
                : "设置立即生效，复制内容不会保存。", 14, false);
        status.setPadding(0, dp(12), 0, dp(20));
        toggle("启用复制预览", "enabled", true);
        toggle("ColorOS 高级模糊", "blur", true);
        slider("圆角", "corner", 4, 20, 8, " dp");
        slider("材质遮罩", "opacity", 0, 40, 20, "%");
        durationControl();
        slider("浮窗大小", "size", 60, 160, 100, "%");
        slider("预览字号", "font_size", 10, 24, 14, " sp");
        slider("字体粗细", "font_weight", 100, 900, 500, "");
        label("圆角越小越方正；遮罩越低，背景模糊越明显。自动反色根据卡片后方的背景选择黑色或白色。", 14, false)
                .setPadding(0, dp(16), 0, 0);
        label("复制后固定计时，回桌面和点背景不会关闭。卡片可向任意方向拖动，超过阈值后松手划走，短拖动会回弹。键盘弹出时预览会自动上移。", 14, false)
                .setPadding(0, dp(12), 0, 0);
        for (View control : controls) control.setEnabled(remote != null);
        content.requestFocus();
        binding = false;
    }

    private void durationControl() {
        int millis = remote == null ? 3000 : ModuleSettings.durationMillis(remote);
        TextView title = label(durationText(millis), 17, false);
        title.setPadding(0, dp(20), 0, dp(8));
        SeekBar bar = new SeekBar(this);
        bar.setMax(40);
        bar.setProgress((millis - 1000) / 100);
        content.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        controls.add(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar view, int progress, boolean fromUser) {
                title.setText(durationText(1000 + progress * 100));
            }
            @Override public void onStartTrackingTouch(SeekBar view) {}
            @Override public void onStopTrackingTouch(SeekBar view) {
                write(p -> p.edit().putInt("duration_ms", 1000 + view.getProgress() * 100).apply());
            }
        });
        label("1.0–5.0 秒 · 每格 0.1 秒", 14, false);
    }

    private String durationText(int millis) {
        return "显示时长  " + String.format(java.util.Locale.ROOT, "%.1f", millis / 1000f) + " 秒";
    }


    private TextView label(String text, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    private void toggle(String label, String key, boolean defaultValue) {
        Switch control = new Switch(this);
        control.setText(label);
        control.setTextSize(17);
        control.setPadding(0, dp(18), 0, dp(18));
        control.setChecked(remote == null ? defaultValue : remote.getBoolean(key, defaultValue));
        content.addView(control, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        controls.add(control);
        control.setOnCheckedChangeListener((button, value) -> write(p -> p.edit().putBoolean(key, value).apply()));
    }

    private void slider(String title, String key, int min, int max, int defaultValue, String unit) {
        int value = Math.max(min, Math.min(max, remote == null ? defaultValue : remote.getInt(key, defaultValue)));
        TextView label = label(title + "  " + value + unit, 17, false);
        label.setPadding(0, dp(20), 0, dp(8));
        SeekBar slider = new SeekBar(this);
        slider.setMax(max - min);
        slider.setProgress(value - min);
        content.addView(slider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        controls.add(slider);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                label.setText(title + "  " + (progress + min) + unit);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {
                write(p -> p.edit().putInt(key, bar.getProgress() + min).apply());
            }
        });
    }

    private void write(Consumer<SharedPreferences> operation) {
        if (binding || remote == null) return;
        try { operation.accept(remote); }
        catch (RuntimeException ignored) { status.setText("连接已断开，请重新打开设置。"); }
    }
}
