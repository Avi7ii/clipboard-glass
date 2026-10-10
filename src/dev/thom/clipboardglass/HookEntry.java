package dev.thom.clipboardglass;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import java.lang.ref.WeakReference;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;

public final class HookEntry extends XposedModule {
    private String process;
    private boolean systemServer;
    private boolean installed;
    private final AtomicBoolean reportedFailure = new AtomicBoolean();
    private WeakReference<Object> activeListener = new WeakReference<>(null);
    private final HashSet<String> stages = new HashSet<>();

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        process = param.getProcessName();
        systemServer = param.isSystemServer();
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        if (installed || !ScopePolicy.allows(process, param.getPackageName(),
                param.isFirstPackage(), systemServer, Build.VERSION.SDK_INT)) return;
        ArrayList<XposedInterface.HookHandle> handles = new ArrayList<>();
        try {
            NativeClipboard clipboard = new NativeClipboard(param.getClassLoader());
            ModuleSettings settings = new ModuleSettings(getRemotePreferences(ModuleSettings.GROUP));
            Consumer<String> stage = message -> {
                if (stages.add(message)) log(4, "ClipboardGlass", message);
            };
            KeyboardPreview keyboard = new KeyboardPreview(param.getClassLoader(), stage);
            PreviewLifetime lifetime = new PreviewLifetime(param.getClassLoader(), settings, stage);
            NativeEditor editor = new NativeEditor(param.getClassLoader(), lifetime, stage);
            SwipePolicy swipe = new SwipePolicy(param.getClassLoader());
            PreviewAppearance appearance = new PreviewAppearance(param.getClassLoader(), settings);
            MaterialBlur blur = new MaterialBlur(param.getClassLoader(), settings,
                    message -> log(4, "ClipboardGlass", message));
            Handler main = new Handler(Looper.getMainLooper());
            settings.onChanged(() -> main.post(() -> {
                Object listener = activeListener.get();
                if (!settings.enabled()) {
                    try { lifetime.stop(clipboard.current(listener)); } catch (ReflectiveOperationException ignored) {}
                    clipboard.dismiss(listener);
                }
                else {
                    try {
                        Object controller = clipboard.current(listener);
                        keyboard.update(controller);
                        appearance.style(controller);
                        lifetime.hideRemote(controller);
                        blur.styleController(controller);
                    } catch (ReflectiveOperationException | RuntimeException ignored) {}
                }
            }));
            // Keep native callback bodies interpreted so their narrow method hooks cannot be
            // bypassed by an old inlined availability check or animateOut call.
            if (!deoptimize(clipboard.callback) || !deoptimize(editor.run)
                    || !deoptimize(blur.setClip)) throw new IllegalStateException();
            handles.add(hook(keyboard.windowConstructor).intercept(chain -> {
                Object result = chain.proceed();
                if (settings.enabled()) keyboard.pinWindow(chain.getThisObject());
                return result;
            }));
            handles.add(hook(keyboard.windowInsets).intercept(chain -> {
                android.view.WindowInsets result = (android.view.WindowInsets) chain.proceed();
                return settings.enabled() ? KeyboardPreview.screenInsets(result) : result;
            }));
            handles.add(hook(clipboard.availability).intercept(chain -> {
                if (settings.enabled() && clipboard.scope.consumeAvailabilityCheck()) return true;
                return chain.proceed();
            }));
            handles.add(hook(blur.setClip).intercept(chain -> {
                Object result = chain.proceed();
                CopyCallbackScope.Frame copy = clipboard.scope.current();
                if (settings.enabled() && copy != null) {
                    stage.accept("Native copy callback preserved");
                    swipe.resetController(chain.getThisObject());
                    lifetime.start(chain.getThisObject(), copy.copiedAt);
                    keyboard.begin(chain.getThisObject());
                }
                else if (settings.enabled()) keyboard.update(chain.getThisObject());
                appearance.style(chain.getThisObject());
                lifetime.hideRemote(chain.getThisObject());
                blur.styleController(chain.getThisObject());
                return result;
            }));
            handles.add(hook(lifetime.remoteVisibility).intercept(chain -> {
                Object result = chain.proceed();
                if (settings.enabled()) lifetime.hideRemoteView(chain.getThisObject());
                return result;
            }));
            handles.add(hook(lifetime.finish).intercept(chain -> {
                if (lifetime.ignoreFinish(chain.getThisObject(), chain.getArg(0), chain.getArg(1))) return null;
                return chain.proceed();
            }));
            handles.add(hook(editor.run).intercept(chain -> {
                Object owner = settings.enabled() ? editor.textEditOwner(chain.getThisObject()) : null;
                if (owner == null) return chain.proceed();
                Object previous = editor.enter(owner);
                try { return chain.proceed(); }
                finally { editor.leave(previous); }
            }));
            handles.add(hook(editor.animateOut).intercept(chain -> {
                if (settings.enabled() && editor.launching(chain.getThisObject())) return null;
                return chain.proceed();
            }));
            handles.add(hook(lifetime.resetTimeout).intercept(chain -> {
                if (settings.enabled() && lifetime.manages(chain.getThisObject())) return null;
                return chain.proceed();
            }));
            handles.add(hook(lifetime.hideImmediate).intercept(chain -> {
                lifetime.stop(chain.getThisObject());
                return chain.proceed();
            }));
            handles.add(hook(swipe.touch).intercept(chain -> {
                if (settings.enabled() && swipe.handle(chain.getThisObject(),
                        (MotionEvent) chain.getArg(1))) return true;
                return chain.proceed();
            }));
            handles.add(hook(appearance.updateTextSize).intercept(chain -> {
                if (appearance.customizeText((android.widget.TextView) chain.getArg(0))) return null;
                return chain.proceed();
            }));
            handles.add(hook(blur.setActionChip).intercept(chain -> {
                Object result = chain.proceed();
                blur.styleView(chain.getThisObject());
                return result;
            }));
            handles.add(hook(keyboard.viewInsets).intercept(chain -> {
                if (!settings.enabled()) return chain.proceed();
                android.view.View root = (android.view.View) chain.getThisObject();
                android.view.WindowInsets insets = (android.view.WindowInsets) chain.getArg(0);
                if (keyboard.position.apply(root, insets, (Integer) chain.getArg(1))) return null;
                return chain.proceed(new Object[]{KeyboardPreview.screenInsets(insets), chain.getArg(1)});
            }));
            handles.add(hook(keyboard.insetsChanged).intercept(chain -> {
                if (!settings.enabled()) return chain.proceed();
                // Avoid the native IME callback repeatedly minimizing and animating the card.
                // Both the window policy and the view margins use a screen anchor.
                keyboard.update(chain.getThisObject());
                appearance.style(chain.getThisObject());
                return null;
            }));
            handles.add(hook(clipboard.callback).intercept(chain -> {
                activeListener = new WeakReference<>(chain.getThisObject());
                if (!settings.enabled()) return chain.proceed();
                CopyCallbackScope.Frame copy = clipboard.scope.enter(SystemClock.uptimeMillis());
                try { return chain.proceed(); }
                finally { clipboard.scope.leave(copy); }
            }));
            installed = true;
            log(4, "ClipboardGlass", "Native clipboard flow and material hooks ready");
        } catch (ReflectiveOperationException | RuntimeException error) {
            for (int i = handles.size() - 1; i >= 0; i--) {
                try { handles.get(i).unhook(); } catch (RuntimeException ignored) {}
            }
            reportFailure();
        }
    }

    private void reportFailure() {
        if (reportedFailure.compareAndSet(false, true)) {
            // No clipboard content, package list, exception message or stack trace is logged.
            log(5, "ClipboardGlass", "Unsupported clipboard implementation; keeping OEM behavior");
        }
    }
}
