package dev.thom.clipboardglass;

import android.animation.Animator;
import android.graphics.Insets;
import android.view.View;
import android.view.WindowInsets;
import android.view.Window;
import android.view.WindowManager;
import android.content.Context;
import android.graphics.Rect;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/** Keeps the native expanded preview at the same screen anchor, independent of the IME. */
final class KeyboardPreview {
    final Method insetsChanged, viewInsets, windowInsets;
    final Constructor<?> windowConstructor;
    final PreviewPosition position = new PreviewPosition();
    private final Field view, window, minimized, orientation, enterAnimator, windowParams, windowManager, card;
    private final Method getInsets, setInsets, expanded, animateIn;
    private final Consumer<String> report;
    private final boolean[] reported = new boolean[2];

    KeyboardPreview(ClassLoader loader, Consumer<String> report) throws ReflectiveOperationException {
        this.report = report;
        Class<?> controller = loader.loadClass("com.android.systemui.clipboardoverlay.ClipboardOverlayController");
        Class<?> nativeView = loader.loadClass("com.android.systemui.clipboardoverlay.ClipboardOverlayView");
        Class<?> nativeWindow = loader.loadClass("com.android.systemui.clipboardoverlay.ClipboardOverlayWindow");
        insetsChanged = Reflect.method(controller, "onInsetsChanged", WindowInsets.class, int.class);
        view = Reflect.field(controller, "mView");
        window = Reflect.field(controller, "mWindow");
        minimized = Reflect.field(controller, "mIsMinimized");
        enterAnimator = Reflect.field(controller, "mEnterAnimator");
        orientation = Reflect.field(nativeWindow, "mOrientation");
        windowParams = Reflect.field(nativeWindow, "mWindowLayoutParams");
        windowManager = Reflect.field(nativeWindow, "mWindowManager");
        windowConstructor = nativeWindow.getDeclaredConstructor(Context.class, WindowManager.class);
        card = Reflect.field(nativeView, "mClipboardPreview");
        getInsets = Reflect.method(nativeWindow, "getWindowInsets");
        windowInsets = getInsets;
        setInsets = Reflect.method(nativeView, "setInsets", WindowInsets.class, int.class);
        viewInsets = setInsets;
        expanded = Reflect.method(controller, "setExpandedView", Runnable.class);
        animateIn = Reflect.method(controller, "animateIn");
    }

    void begin(Object controller) {
        if (controller == null) return;
        update(controller);
    }

    void pinWindow(Object nativeWindow) {
        try {
            Window phoneWindow = (Window) nativeWindow;
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) windowParams.get(nativeWindow);
            int oldFlags = params.flags, oldMode = params.softInputMode, oldFit = params.getFitInsetsTypes();
            PreviewPosition.pinWindow(params);
            phoneWindow.setSoftInputMode(params.softInputMode);
            View decor = phoneWindow.peekDecorView();
            if (decor != null && decor.isAttachedToWindow()
                    && (oldFlags != params.flags || oldMode != params.softInputMode || oldFit != params.getFitInsetsTypes()))
                ((WindowManager) windowManager.get(nativeWindow)).updateViewLayout(decor, params);
        } catch (ReflectiveOperationException | RuntimeException ignored) {}
    }

    void reportPosition(Object controller) {
        try {
            View root = (View) view.get(controller);
            root.postDelayed(() -> {
                try {
                    if (!root.isAttachedToWindow()) return;
                    Object nativeWindow = window.get(controller);
                    WindowInsets raw = ((WindowManager) windowManager.get(nativeWindow)).getCurrentWindowMetrics().getWindowInsets();
                    int index = raw.isVisible(WindowInsets.Type.ime()) ? 1 : 0;
                    if (reported[index]) return;
                    Rect bounds = new Rect();
                    if (!((View) card.get(root)).getGlobalVisibleRect(bounds)) return;
                    int[] origin = new int[2]; root.getLocationOnScreen(origin);
                    reported[index] = true;
                    report.accept("Fixed preview coordinates: ime=" + (index == 1 ? "shown" : "hidden")
                            + " card=" + bounds.toShortString() + " root=" + origin[0] + "," + origin[1]
                            + " size=" + root.getWidth() + "x" + root.getHeight());
                } catch (ReflectiveOperationException | RuntimeException ignored) {}
            }, 400);
        } catch (ReflectiveOperationException | RuntimeException ignored) {}
    }

    static WindowInsets screenInsets(WindowInsets current) {
        return new WindowInsets.Builder(current)
                .setInsets(WindowInsets.Type.ime(), Insets.NONE)
                .setVisible(WindowInsets.Type.ime(), false)
                .setInsets(WindowInsets.Type.navigationBars(),
                        current.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()))
                .build();
    }

    void update(Object controller) {
        if (controller == null) return;
        try {
            Object nativeWindow = window.get(controller);
            pinWindow(nativeWindow);
            WindowInsets current = (WindowInsets) getInsets.invoke(nativeWindow);
            setInsets.invoke(view.get(controller), screenInsets(current), orientation.getInt(nativeWindow));
            if (minimized.getBoolean(controller)) {
                Animator animation = (Animator) enterAnimator.get(controller);
                if (animation != null && animation.isRunning()) animation.cancel();
                minimized.setBoolean(controller, false);
                expanded.invoke(controller, (Runnable) () -> {
                    try { animateIn.invoke(controller); } catch (ReflectiveOperationException | RuntimeException ignored) {}
                });
            }
            reportPosition(controller);
        } catch (ReflectiveOperationException | RuntimeException ignored) {}
    }
}
