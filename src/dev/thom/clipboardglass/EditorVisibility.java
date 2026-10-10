package dev.thom.clipboardglass;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Confirms the actual native editor window, never a task-list position or launch return. */
final class EditorVisibility {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WeakHashMap<Object, Request> pending = new WeakHashMap<>();
    private final Consumer<Object> opened;
    private final Consumer<String> report;
    private boolean registered;

    EditorVisibility(Consumer<Object> opened, Consumer<String> report) {
        this.opened = opened;
        this.report = report;
    }

    boolean prepare(Object controller, Context context, ComponentName expected) {
        if (controller == null || expected == null) return false;
        Context appContext = context.getApplicationContext();
        if (!(appContext instanceof Application)) return false;
        if (!registered) {
            ((Application) appContext).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(Activity activity, Bundle saved) {}
                @Override public void onActivityStarted(Activity activity) {}
                @Override public void onActivityResumed(Activity activity) {
                    for (Request request : pending.values().toArray(new Request[0])) {
                        if (request.expected.equals(activity.getComponentName())) {
                            request.activity = new WeakReference<>(activity);
                            report.accept("Native editor resumed");
                            main.removeCallbacks(request);
                            main.post(request);
                        }
                    }
                }
                @Override public void onActivityPaused(Activity activity) {
                    for (Request request : pending.values().toArray(new Request[0])) {
                        if (request.activity.get() == activity) {
                            main.removeCallbacks(request);
                            request.activity.clear();
                        }
                    }
                }
                @Override public void onActivityStopped(Activity activity) {}
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle saved) {}
                @Override public void onActivityDestroyed(Activity activity) {
                    for (Request request : pending.values().toArray(new Request[0])) {
                        if (request.activity.get() == activity) {
                            main.removeCallbacks(request);
                            request.activity.clear();
                        }
                    }
                }
            });
            registered = true;
        }
        cancel(controller);
        pending.put(controller, new Request(controller, expected));
        return true;
    }

    void cancel(Object controller) {
        Request previous = pending.remove(controller);
        if (previous != null) main.removeCallbacks(previous);
    }

    private final class Request implements Runnable {
        private final WeakReference<Object> controller;
        private final ComponentName expected;
        private WeakReference<Activity> activity = new WeakReference<>(null);

        Request(Object controller, ComponentName expected) {
            this.controller = new WeakReference<>(controller);
            this.expected = expected;
        }

        @Override public void run() {
            Object owner = controller.get();
            if (owner == null || pending.get(owner) != this) return;
            Activity editor = activity.get();
            if (editor == null || editor.isFinishing() || editor.isDestroyed()) return;
            View decor = editor.getWindow().getDecorView();
            if (decor.isAttachedToWindow() && decor.isShown() && decor.hasWindowFocus()) {
                report.accept("Native editor window focused");
                cancel(owner);
                opened.accept(owner);
            } else {
                main.postDelayed(this, 50);
            }
        }
    }
}
