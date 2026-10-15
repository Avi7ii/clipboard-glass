package dev.thom.clipboardglass;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import android.os.Build;

/** Keeps the OEM listener, user handling, suppression rules and controller ownership intact. */
final class NativeClipboard {
    final Method callback, availability;
    final Constructor<?>[] taskConstructors;
    final Method[] taskRuns;
    final CopyCallbackScope scope = new CopyCallbackScope();
    private final Field overlay;
    private final Method hide;

    NativeClipboard(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> listener = loader.loadClass("com.android.systemui.clipboardoverlay.ClipboardListener");
        callback = Reflect.method(listener, "onPrimaryClipChanged");
        availability = Reflect.method(loader.loadClass("com.oplusos.systemui.common.feature.FeatureOption"), "isExpRegion");
        overlay = Reflect.field(listener, "mClipboardOverlay");
        hide = Reflect.method(loader.loadClass("com.android.systemui.clipboardoverlay.ClipboardOverlayController"), "hideImmediate");
        if (Build.VERSION.SDK_INT == 37) {
            taskConstructors = new Constructor<?>[2];
            taskRuns = new Method[2];
            for (int i = 0; i < 2; i++) {
                Class<?> task = loader.loadClass(listener.getName() + "$$ExternalSyntheticLambda" + i);
                taskConstructors[i] = task.getDeclaredConstructor(int.class);
                taskRuns[i] = Reflect.method(task, "run");
            }
        } else {
            taskConstructors = new Constructor<?>[0];
            taskRuns = new Method[0];
        }
    }

    Object current(Object listener) throws ReflectiveOperationException {
        return listener == null ? null : overlay.get(listener);
    }

    void dismiss(Object listener) {
        try {
            Object controller = current(listener);
            if (controller != null) hide.invoke(controller);
        } catch (ReflectiveOperationException | RuntimeException ignored) {}
    }
}
