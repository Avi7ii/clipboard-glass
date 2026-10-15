package dev.thom.clipboardglass;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.os.Build;
import android.animation.Animator;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/** Wraps the OEM text-edit callback; the OEM still creates and launches its own intent. */
final class NativeEditor {
    final Method run, animateOut, exitAnimation, finishAnimationEnd;
    private final Field kind, owner, context, creator, root, card;
    private final Field completionOwner, completionEvent, completionIntent, model;
    private final Method modelType;
    private final int previewKind;
    private final Method textIntent, taskService, resumeSwitches;
    private final ThreadLocal<Object> editing = new ThreadLocal<>();
    private final PreviewLifetime lifetime;
    private final Consumer<String> report;

    NativeEditor(ClassLoader loader, PreviewLifetime lifetime, Consumer<String> report) throws ReflectiveOperationException {
        this.lifetime = lifetime;
        this.report = report;
        Class<?> controller = loader.loadClass("com.android.systemui.clipboardoverlay.ClipboardOverlayController");
        boolean android17 = Build.VERSION.SDK_INT == 37;
        Class<?> callback = loader.loadClass(android17
                ? "com.android.systemui.clipboardoverlay.ClipboardOverlayView$$ExternalSyntheticLambda4"
                : controller.getName() + "$$ExternalSyntheticLambda0");
        run = android17 ? Reflect.method(callback, "onClick", View.class) : Reflect.method(callback, "run");
        kind = Reflect.field(callback, "$r8$classId");
        owner = Reflect.field(callback, android17 ? "f$1" : "f$0");
        previewKind = android17 ? 1 : 7;
        context = Reflect.field(controller, "mContext");
        creator = Reflect.field(controller, "mIntentCreator");
        root = Reflect.field(controller, "mView");
        card = Reflect.field(loader.loadClass("com.android.systemui.clipboardoverlay.ClipboardOverlayView"), "mClipboardPreview");
        textIntent = Reflect.method(loader.loadClass("com.android.systemui.clipboardoverlay.IntentCreator"),
                "getTextEditorIntent", Context.class);
        if (android17) {
            animateOut = null;
            exitAnimation = Reflect.method(loader.loadClass("com.android.systemui.clipboardoverlay.ClipboardOverlayView"),
                    "getExitAnimation");
            Class<?> completion = loader.loadClass(controller.getName() + "$7");
            finishAnimationEnd = Reflect.method(completion, "onAnimationEnd", Animator.class);
            completionOwner = Reflect.field(completion, "this$0");
            completionEvent = Reflect.field(completion, "val$event");
            completionIntent = Reflect.field(completion, "val$intent");
            model = Reflect.field(controller, "mClipboardModel");
            modelType = Reflect.method(loader.loadClass("com.android.systemui.clipboardoverlay.ClipboardModel"), "getType");
        } else {
            animateOut = Reflect.method(controller, "animateOut");
            exitAnimation = finishAnimationEnd = null;
            completionOwner = completionEvent = completionIntent = model = null;
            modelType = null;
        }
        taskService = Reflect.method(loader.loadClass("android.app.ActivityTaskManager"), "getService");
        resumeSwitches = Reflect.method(loader.loadClass("android.app.IActivityTaskManager"), "resumeAppSwitches");
    }

    Object textEditOwner(Object callback) {
        try {
            if (kind.getInt(callback) != previewKind) return null;
            Object controller = owner.get(callback);
            if (modelType != null) {
                Object type = modelType.invoke(model.get(controller));
                if (!(type instanceof Enum<?>) || !((Enum<?>) type).name().equals("TEXT")) return null;
            }
            return controller;
        }
        catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
    }

    Object pendingCompletion(Object callback) {
        try {
            Object controller = completionOwner.get(callback);
            Object event = completionEvent.get(callback);
            return lifetime.awaitingEditor(controller) && event instanceof Enum<?>
                    && ((Enum<?>) event).name().equals("CLIPBOARD_OVERLAY_EDIT_TAPPED")
                    && completionIntent.get(callback) != null ? controller : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
    }

    Object enterCompletion(Object controller) {
        Object previous = editing.get();
        editing.set(controller);
        return previous;
    }

    boolean editingView(Object view) {
        Object controller = editing.get();
        try { return controller != null && root.get(controller) == view && lifetime.awaitingEditor(controller); }
        catch (ReflectiveOperationException | RuntimeException ignored) { return false; }
    }

    Object enter(Object controller) {
        Object previous = editing.get();
        editing.set(controller);
        report.accept("Native edit tap received");
        try {
            Context ctx = (Context) context.get(controller);
            Intent nativeIntent = (Intent) textIntent.invoke(creator.get(controller), ctx);
            lifetime.watchEditor(controller, ctx, nativeIntent.getComponent());
            // Home briefly stops app switches. SystemUI already owns STOP_APP_SWITCHES;
            // this is its normal transient navigation permission, never a persisted setting.
            resumeSwitches.invoke(taskService.invoke(null));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            report.accept("Native edit preparation unavailable");
        }
        return previous;
    }

    void leave(Object previous) {
        Object controller = editing.get();
        if (previous == null) editing.remove();
        else editing.set(previous);
        try {
            View preview = (View) card.get(root.get(controller));
            // The OEM click listener disables the card after this callback returns.
            // Re-enable after that listener unwinds if a failed launch left the card alive.
            preview.post(() -> {
                if (lifetime.active(controller)) preview.setClickable(true);
            });
        } catch (ReflectiveOperationException | RuntimeException ignored) {}
    }

    boolean launching(Object controller) { return editing.get() == controller; }
}
