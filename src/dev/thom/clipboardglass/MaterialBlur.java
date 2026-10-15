package dev.thom.clipboardglass;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.TextView;
import android.widget.ImageView;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.function.Consumer;

/** Uses ColorOS's own material blur engine, never a screenshot or RenderEffect imitation. */
final class MaterialBlur {
    private static final int SURFACE_TAG = 0x7e0c0101;
    final Method setClip;
    final Method setActionChip;
    private final Field overlayView;
    private final Field preview;
    private final Field border;
    private final Field actions;
    private final Field actionOwner, actionKind;
    private final Constructor<?> builderConstructor;
    private final Field useBlur;
    private final Field darkMode;
    private final Field systemBlurState;
    private final Field windowBlurState;
    private final Method enableBlur, blurEnabled;
    private final Object animationLevel;
    private final Method setTarget;
    private final Method setRadius;
    private final Method setCorner;
    private final Method setSmooth;
    private final Method apply;
    private final Method release;
    private final Method colorToFloats;
    private final Method[] setMaterialColors = new Method[4];
    private static final String[] MATERIAL_COLORS = {"coui_dialog_list_mix_blur_light",
            "coui_dialog_list_mix_blur_dark", "coui_dialog_list_blend_blur_light",
            "coui_dialog_list_blend_blur_dark"};
    private final Consumer<String> reporter;
    private final HashSet<String> reported = new HashSet<>();
    private final ModuleSettings settings;
    private final BackgroundContrast contrast;

    MaterialBlur(ClassLoader loader, ModuleSettings settings, Consumer<String> reporter) throws ReflectiveOperationException {
        this.reporter = reporter;
        this.settings = settings;
        BackgroundContrast optionalContrast;
        try { optionalContrast = new BackgroundContrast(loader); }
        catch (ReflectiveOperationException | RuntimeException ignored) { optionalContrast = null; }
        contrast = optionalContrast;
        Class<?> controller = loader.loadClass("com.android.systemui.clipboardoverlay.ClipboardOverlayController");
        Class<?> view = loader.loadClass("com.android.systemui.clipboardoverlay.ClipboardOverlayView");
        Class<?> builder = loader.loadClass("com.coui.appcompat.uiutil.COUIBackgroundBlurBuilder");
        colorToFloats = Reflect.method(loader.loadClass("com.coui.appcompat.uiutil.UIUtil"),
                "colorToFloats", int.class);
        String[] setters = {"setMixColorLight", "setMixColorDark", "setBlendColorLight", "setBlendColorDark"};
        for (int i = 0; i < setters.length; i++) {
            setMaterialColors[i] = Reflect.method(builder, setters[i], float[].class);
        }
        loader.loadClass("com.oplus.graphics.OplusBlurParam");
        setClip = Reflect.method(controller, "setClipData", android.content.ClipData.class, String.class);
        if (Build.VERSION.SDK_INT == 37) {
            Class<?> actionTask = loader.loadClass(controller.getName() + "$$ExternalSyntheticLambda9");
            setActionChip = Reflect.method(actionTask, "run");
            actionOwner = Reflect.field(actionTask, "f$0");
            actionKind = Reflect.field(actionTask, "$r8$classId");
        } else {
            setActionChip = Reflect.method(view, "setActionChip", android.app.RemoteAction.class,
                    loader.loadClass(controller.getName() + "$$ExternalSyntheticLambda0"));
            actionOwner = actionKind = null;
        }
        overlayView = Reflect.field(controller, "mView");
        preview = Reflect.field(view, "mClipboardPreview");
        border = Reflect.field(view, "mPreviewBorder");
        actions = Reflect.field(view, "mActionContainer");
        builderConstructor = builder.getConstructor(Context.class);
        useBlur = Reflect.field(builder, "mUseBackgroundBlur");
        darkMode = Reflect.field(builder, "mIsDarkMode");
        if (Build.VERSION.SDK_INT == 37) {
            Class<?> level = loader.loadClass("com.coui.appcompat.uiutil.AnimLevel");
            animationLevel = Reflect.field(level, "MID_END").get(null);
            enableBlur = Reflect.method(builder, "setUseBackgroundBlur", boolean.class, level);
            blurEnabled = Reflect.method(builder, "shouldEnableBlur");
            systemBlurState = windowBlurState = null;
        } else {
            systemBlurState = Reflect.field(builder, "mSettingBlurState");
            windowBlurState = Reflect.field(builder, "mWindowBlurEnable");
            enableBlur = blurEnabled = null;
            animationLevel = null;
        }
        setTarget = Reflect.method(builder, "setTargetView", View.class);
        setRadius = Reflect.method(builder, "setBlurRadius", int.class);
        setCorner = Reflect.method(builder, "setCornerRadius", float.class);
        setSmooth = Reflect.method(builder, "setSmoothWeight", float.class);
        apply = Reflect.method(builder, "applyBlurBackground");
        release = Reflect.method(builder, "release");
    }

    void styleController(Object controller) {
        if (controller == null) return;
        try { styleView(overlayView.get(controller)); } catch (ReflectiveOperationException ignored) {}
    }

    void styleAction(Object receiver) {
        if (actionOwner == null) styleView(receiver);
        else {
            try { if (actionKind.getInt(receiver) == 1) styleController(actionOwner.get(receiver)); }
            catch (ReflectiveOperationException | RuntimeException ignored) {}
        }
    }

    void styleView(Object view) {
        try {
            View card = (View) preview.get(view);
            View frame = (View) border.get(view);
            // Remove the solid outer preview border only after a material surface exists.
            surface(card, frame, false);
            ViewGroup container = (ViewGroup) actions.get(view);
            for (int i = 0; i < container.getChildCount(); i++) {
                View chip = container.getChildAt(i);
                if (chip.getVisibility() == View.GONE) continue;
                // ColorOS places the opaque chip background on the inner LinearLayout.
                View target = chip instanceof ViewGroup && ((ViewGroup) chip).getChildCount() > 0
                        ? ((ViewGroup) chip).getChildAt(0) : chip;
                surface(target, null, true);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Appearance must never prevent a usable native clipboard preview.
        }
    }

    private void surface(View target, View frame, boolean tintIcons) {
        if (target == null) return;
        Object existing = target.getTag(SURFACE_TAG);
        if (existing instanceof Surface) {
            Surface surface = (Surface) existing;
            if (target.isAttachedToWindow()) surface.schedule();
            return;
        }
        if (existing != null) return;
        Surface surface = new Surface(target, frame, tintIcons);
        target.setTag(SURFACE_TAG, surface);
        target.addOnAttachStateChangeListener(surface);
        if (target.isAttachedToWindow()) surface.schedule();
    }

    private final class Surface implements View.OnAttachStateChangeListener {
        private final View target;
        private final View frame;
        private final Drawable originalFrame;
        private final ArrayList<TextView> texts = new ArrayList<>();
        private final ArrayList<ColorStateList> textColors = new ArrayList<>();
        private final ArrayList<ImageView> icons = new ArrayList<>();
        private final ArrayList<ColorStateList> iconColors = new ArrayList<>();
        private final Drawable original;
        private final Drawable originalForeground;
        private final float originalElevation;
        private final ViewOutlineProvider originalOutline;
        private final boolean originalClip;
        private Object builder;
        private int appliedNight = -1;
        private int appliedSettings = -1;
        private int foreground;
        private boolean sampling;
        private int generation;
        private GradientDrawable edge;
        private final Runnable contrastPulse = this::sampleContrast;

        Surface(View target, View frame, boolean tintIcons) {
            this.target = target;
            this.frame = frame;
            originalFrame = frame == null ? null : frame.getBackground();
            original = target.getBackground();
            originalForeground = target.getForeground();
            originalElevation = target.getElevation();
            originalOutline = target.getOutlineProvider();
            originalClip = target.getClipToOutline();
            collectContent(target, tintIcons);
        }

        private void collectContent(View view, boolean tintIcons) {
            if (view instanceof TextView) {
                TextView text = (TextView) view;
                texts.add(text);
                textColors.add(text.getTextColors());
            } else if (tintIcons && view instanceof ImageView) {
                ImageView icon = (ImageView) view;
                icons.add(icon);
                iconColors.add(icon.getImageTintList());
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) collectContent(group.getChildAt(i), tintIcons);
            }
        }

        void attach() {
            if (!target.isAttachedToWindow()) return;
            int night = target.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            int preferenceKey = settings.materialKey();
            if (builder != null && appliedNight == night && appliedSettings == preferenceKey) return;
            if (builder != null) cleanup();
            try {
                // Respect the existing advanced blur setting; never write it.
                if (!settings.blur() || !settings.enabled() || Settings.System.getInt(target.getContext().getContentResolver(),
                        "system_material_blur_enable", 0) != 1) return;
                float density = target.getResources().getDisplayMetrics().density;
                float cornerDp = settings.corner();
                builder = builderConstructor.newInstance(target.getContext());
                // Same material colors and conversion used by ColorOS's COUI dialogs.
                for (int i = 0; i < setMaterialColors.length; i++) {
                    int resource = target.getResources().getIdentifier(MATERIAL_COLORS[i],
                            "color", "com.android.systemui");
                    if (resource == 0) throw new IllegalStateException();
                    float[] rgba = (float[]) colorToFloats.invoke(null, target.getContext().getColor(resource));
                    if (i < 2) {
                        rgba[0] = rgba[1] = rgba[2] = i == 0 ? 1f : .85f;
                        rgba[3] = settings.opacity() / 100f;
                    }
                    setMaterialColors[i].invoke(builder, rgba);
                }
                setTarget.invoke(builder, target);
                setRadius.invoke(builder, Math.round(32f * density));
                setCorner.invoke(builder, cornerDp * density);
                setSmooth.invoke(builder, 2f);
                if (enableBlur != null) enableBlur.invoke(builder, true, animationLevel);
                else useBlur.setBoolean(builder, true);
                apply.invoke(builder);
                Drawable material = target.getBackground();
                if (material == null || material == original) throw new IllegalStateException();
                // Native enter animations change View alpha. Do not freeze drawable alpha at 0.
                material.setAlpha(255);
                target.setOutlineProvider(new ViewOutlineProvider() {
                    @Override public void getOutline(View view, Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerDp * density);
                    }
                });
                target.setClipToOutline(true);
                appliedNight = night;
                appliedSettings = preferenceKey;
                if (frame != null) frame.setBackground(null);
                if (frame != null) {
                    edge = new GradientDrawable();
                    edge.setColor(Color.TRANSPARENT);
                    edge.setCornerRadius(cornerDp * density);
                    target.setForeground(edge);
                    target.setElevation(6f * density);
                }
                setForeground(darkMode.getBoolean(builder) ? Color.WHITE : Color.rgb(24, 24, 24));
                target.postDelayed(contrastPulse, 160);
                report("ColorOS material surface attached");
            } catch (ReflectiveOperationException | RuntimeException error) {
                Throwable cause = error instanceof InvocationTargetException
                        ? ((InvocationTargetException) error).getTargetException() : error;
                StackTraceElement[] trace = cause.getStackTrace();
                report("Material surface fallback: " + cause.getClass().getSimpleName()
                        + (trace.length == 0 ? "" : " at " + trace[0].getClassName()
                        + "." + trace[0].getMethodName()));
                cleanup();
            }
        }

        private void setForeground(int color) {
            foreground = color;
            for (TextView text : texts) text.setTextColor(color);
            for (ImageView icon : icons) icon.setImageTintList(ColorStateList.valueOf(color));
            if (edge != null) edge.setStroke(Math.max(1, Math.round(target.getResources().getDisplayMetrics().density)),
                    color == Color.WHITE ? 0x90ffffff : 0x55000000);
        }

        private void sampleContrast() {
            if (builder == null || !target.isAttachedToWindow() || contrast == null) return;
            try {
                boolean enabled = blurEnabled != null ? (Boolean) blurEnabled.invoke(builder)
                        : systemBlurState.getBoolean(builder) && windowBlurState.getBoolean(builder);
                if (!enabled) {
                    setForeground(darkMode.getBoolean(builder) ? Color.WHITE : Color.rgb(24, 24, 24));
                    target.postDelayed(contrastPulse, 350);
                    return;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) { return; }
            if (!sampling && target.isShown() && target.getWindowToken() != null && target.getDisplay() != null) {
                Rect area = new Rect();
                if (target.getGlobalVisibleRect(area) && !area.isEmpty()) {
                    sampling = true;
                    int requestedGeneration = generation;
                    contrast.sample(area, target.getWindowToken(), target.getDisplay().getDisplayId(), luminance -> {
                        target.post(() -> {
                            sampling = false;
                            if (builder == null || generation != requestedGeneration || luminance == null) return;
                            // Hysteresis avoids flickering on mid-gray backgrounds.
                            if (foreground == Color.WHITE && luminance > .25) setForeground(Color.rgb(24, 24, 24));
                            else if (foreground != Color.WHITE && luminance < .18) setForeground(Color.WHITE);
                            report("Backdrop foreground adaptation active");
                        });
                    });
                }
            }
            target.postDelayed(contrastPulse, 350);
        }

        void schedule() {
            // View attach callbacks can precede the parent assignment to ViewRootImpl.
            target.post(() -> {
                if (target.getTag(SURFACE_TAG) == this) attach();
            });
        }

        void cleanup() {
            generation++;
            target.removeCallbacks(contrastPulse);
            if (builder != null) {
                try { release.invoke(builder); } catch (ReflectiveOperationException | RuntimeException ignored) {}
                builder = null;
            }
            target.setBackground(original);
            target.setForeground(originalForeground);
            target.setElevation(originalElevation);
            edge = null;
            target.setOutlineProvider(originalOutline);
            target.setClipToOutline(originalClip);
            if (frame != null) frame.setBackground(originalFrame);
            for (int i = 0; i < texts.size(); i++) texts.get(i).setTextColor(textColors.get(i));
            for (int i = 0; i < icons.size(); i++) icons.get(i).setImageTintList(iconColors.get(i));
        }

        @Override public void onViewAttachedToWindow(View view) { schedule(); }
        @Override public void onViewDetachedFromWindow(View view) {
            cleanup();
            target.setTag(SURFACE_TAG, null);
            target.removeOnAttachStateChangeListener(this);
        }
    }

    private void report(String message) {
        // Framework log is protected by LSPosed; no text, source package or stack is included.
        if (reported.add(message)) reporter.accept(message);
    }
}
