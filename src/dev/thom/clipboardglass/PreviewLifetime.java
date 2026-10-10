package dev.thom.clipboardglass;

import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;
import java.util.Map;
import java.util.Collections;
import java.util.function.Consumer;

/** A single monotonic deadline for each copy; unrelated touches cannot extend or dismiss it. */
final class PreviewLifetime {
    final Method finish, resetTimeout, hideImmediate, remoteVisibility;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WeakHashMap<Object, Deadline> deadlines = new WeakHashMap<>();
    private final Map<Object, Boolean> managedTimeouts = Collections.synchronizedMap(new WeakHashMap<>());
    private final Field timeout, view, context, remote;
    private final ModuleSettings settings;
    private final EditorVisibility editor;
    private boolean watchingScreen;

    PreviewLifetime(ClassLoader loader, ModuleSettings settings, Consumer<String> report) throws ReflectiveOperationException {
        this.settings = settings;
        editor = new EditorVisibility(this::close, report);
        Class<?> controller = loader.loadClass("com.android.systemui.clipboardoverlay.ClipboardOverlayController");
        Class<?> nativeView = loader.loadClass("com.android.systemui.clipboardoverlay.ClipboardOverlayView");
        finish = Reflect.method(controller, "finish",
                loader.loadClass("com.android.systemui.clipboardoverlay.ClipboardOverlayEvent"), Intent.class);
        hideImmediate = Reflect.method(controller, "hideImmediate");
        resetTimeout = Reflect.method(loader.loadClass("com.android.systemui.screenshot.TimeoutHandler"), "resetTimeout");
        remoteVisibility = Reflect.method(nativeView, "setRemoteCopyVisibility", boolean.class);
        timeout = Reflect.field(controller, "mTimeoutHandler");
        view = Reflect.field(controller, "mView");
        context = Reflect.field(controller, "mContext");
        remote = Reflect.field(nativeView, "mRemoteCopyChip");
    }

    void start(Object controller, long copiedAt) {
        if (controller == null) return;
        try {
            stop(controller);
            Handler nativeTimeout = (Handler) timeout.get(controller);
            nativeTimeout.removeMessages(2);
            managedTimeouts.put(nativeTimeout, true);
            Deadline deadline = new Deadline(controller, nativeTimeout);
            deadlines.put(controller, deadline);
            main.postAtTime(deadline, copiedAt + settings.durationMillis());
            if (!watchingScreen) {
                Context ctx = (Context) context.get(controller);
                ctx.registerReceiver(new BroadcastReceiver() {
                    @Override public void onReceive(Context context, Intent intent) {
                        for (Object open : deadlines.keySet().toArray()) {
                            stop(open);
                            try { hideImmediate.invoke(open); }
                            catch (ReflectiveOperationException | RuntimeException ignored) { stop(open); }
                        }
                    }
                }, new IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED);
                watchingScreen = true;
            }
            hideRemote(controller);
        } catch (ReflectiveOperationException | RuntimeException ignored) {}
    }

    void stop(Object controller) {
        Deadline previous = deadlines.remove(controller);
        if (previous != null) {
            main.removeCallbacks(previous);
            managedTimeouts.remove(previous.nativeTimeout);
        }
        editor.cancel(controller);
    }

    boolean manages(Object nativeTimeout) { return managedTimeouts.containsKey(nativeTimeout); }
    boolean active(Object controller) { return deadlines.containsKey(controller); }

    void watchEditor(Object controller, Context context, ComponentName expected) {
        if (deadlines.containsKey(controller)) editor.prepare(controller, context, expected);
    }

    void close(Object controller) {
        stop(controller);
        try { hideImmediate.invoke(controller); }
        catch (ReflectiveOperationException | RuntimeException ignored) {}
    }

    boolean ignoreFinish(Object controller, Object event, Object intent) {
        if (!settings.enabled() || !(event instanceof Enum<?>)) return false;
        String name = ((Enum<?>) event).name();
        if (name.equals("CLIPBOARD_OVERLAY_TAP_OUTSIDE")) return true;
        if (!name.equals("CLIPBOARD_OVERLAY_DISMISSED_OTHER") || intent != null) return false;
        try {
            Context ctx = (Context) context.get(controller);
            KeyguardManager lock = ctx.getSystemService(KeyguardManager.class);
            return lock != null && !lock.isDeviceLocked();
        } catch (ReflectiveOperationException | RuntimeException ignored) { return false; }
    }

    void hideRemote(Object controller) {
        try { hideRemoteView(view.get(controller)); } catch (ReflectiveOperationException ignored) {}
    }

    void hideRemoteView(Object nativeView) {
        try { ((View) remote.get(nativeView)).setVisibility(View.GONE); }
        catch (ReflectiveOperationException | RuntimeException ignored) {}
    }

    private final class Deadline implements Runnable {
        private final WeakReference<Object> controller;
        private final Handler nativeTimeout;
        Deadline(Object controller, Handler nativeTimeout) {
            this.controller = new WeakReference<>(controller);
            this.nativeTimeout = nativeTimeout;
        }
        @Override public void run() {
            Object current = controller.get();
            if (current == null || deadlines.get(current) != this) return;
            close(current);
        }
    }
}
