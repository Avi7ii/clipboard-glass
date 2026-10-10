package dev.thom.clipboardglass;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/** Wraps the OEM text-edit callback; the OEM still creates and launches its own intent. */
final class NativeEditor {
    final Method run, animateOut;
    private final Field kind, owner, context, creator, root, card;
    private final Method textIntent, taskService, resumeSwitches;
    private final ThreadLocal<Object> editing = new ThreadLocal<>();
    private final PreviewLifetime lifetime;
    private final Consumer<String> report;

    NativeEditor(ClassLoader loader, PreviewLifetime lifetime, Consumer<String> report) throws ReflectiveOperationException {
        this.lifetime = lifetime;
        this.report = report;
        Class<?> controller = loader.loadClass("com.android.systemui.clipboardoverlay.ClipboardOverlayController");
        Class<?> callback = loader.loadClass(controller.getName() + "$$ExternalSyntheticLambda0");
        run = Reflect.method(callback, "run");
        kind = Reflect.field(callback, "$r8$classId");
        owner = Reflect.field(callback, "f$0");
        context = Reflect.field(controller, "mContext");
        creator = Reflect.field(controller, "mIntentCreator");
        root = Reflect.field(controller, "mView");
        card = Reflect.field(loader.loadClass("com.android.systemui.clipboardoverlay.ClipboardOverlayView"), "mClipboardPreview");
        textIntent = Reflect.method(loader.loadClass("com.android.systemui.clipboardoverlay.IntentCreator"),
                "getTextEditorIntent", Context.class);
        animateOut = Reflect.method(controller, "animateOut");
        taskService = Reflect.method(loader.loadClass("android.app.ActivityTaskManager"), "getService");
        resumeSwitches = Reflect.method(loader.loadClass("android.app.IActivityTaskManager"), "resumeAppSwitches");
    }

    Object textEditOwner(Object callback) {
        try { return kind.getInt(callback) == 7 ? owner.get(callback) : null; }
        catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
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
