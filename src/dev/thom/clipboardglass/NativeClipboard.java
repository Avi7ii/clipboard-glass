package dev.thom.clipboardglass;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Keeps the OEM listener, user handling, suppression rules and controller ownership intact. */
final class NativeClipboard {
    final Method callback, availability;
    final CopyCallbackScope scope = new CopyCallbackScope();
    private final Field overlay;
    private final Method hide;

    NativeClipboard(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> listener = loader.loadClass("com.android.systemui.clipboardoverlay.ClipboardListener");
        callback = Reflect.method(listener, "onPrimaryClipChanged");
        availability = Reflect.method(loader.loadClass("com.oplusos.systemui.common.feature.FeatureOption"), "isExpRegion");
        overlay = Reflect.field(listener, "mClipboardOverlay");
        hide = Reflect.method(loader.loadClass("com.android.systemui.clipboardoverlay.ClipboardOverlayController"), "hideImmediate");
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
