package dev.thom.clipboardglass;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Two-dimensional gestures scoped to the clipboard view; screenshot gestures stay native. */
final class SwipePolicy {
    private static final int TAG = 0x7e0c0102;
    final Method touch;
    private final Field view, preview, callbacks, controllerView;
    private final Method initiated, completed;

    SwipePolicy(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> root = loader.loadClass("com.android.systemui.screenshot.DraggableConstraintLayout");
        Class<?> handler = loader.loadClass(root.getName() + "$SwipeDismissHandler");
        Class<?> nativeView = loader.loadClass("com.android.systemui.clipboardoverlay.ClipboardOverlayView");
        Class<?> controller = loader.loadClass("com.android.systemui.clipboardoverlay.ClipboardOverlayController");
        Class<?> callback = loader.loadClass(root.getName() + "$SwipeDismissCallbacks");
        touch = Reflect.method(handler, "onTouch", View.class, MotionEvent.class);
        view = Reflect.field(handler, "mView");
        preview = Reflect.field(nativeView, "mClipboardPreview");
        callbacks = Reflect.field(root, "mCallbacks");
        controllerView = Reflect.field(controller, "mView");
        initiated = Reflect.method(callback, "onSwipeDismissInitiated", Animator.class);
        completed = Reflect.method(callback, "onDismissComplete");
    }

    boolean handle(Object handler, MotionEvent event) {
        try {
            View target = (View) view.get(handler);
            if (!target.getClass().getName().equals("com.android.systemui.clipboardoverlay.ClipboardOverlayView")) return false;
            Drag drag = (Drag) target.getTag(TAG);
            if (drag == null) {
                drag = new Drag(target, (View) preview.get(target));
                target.setTag(TAG, drag);
                target.addOnAttachStateChangeListener(drag);
            }
            drag.touch(event);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) { return false; }
    }

    void resetController(Object controller) {
        if (controller == null) return;
        try {
            View target = (View) controllerView.get(controller);
            Drag drag = (Drag) target.getTag(TAG);
            if (drag != null) drag.reset();
        } catch (ReflectiveOperationException | RuntimeException ignored) {}
    }

    private final class Drag implements View.OnAttachStateChangeListener {
        private final View target, card;
        private final float density, slop;
        private float startX, startY;
        private boolean pressed, moved;
        private VelocityTracker velocity;
        private ValueAnimator dismiss;

        Drag(View target, View card) {
            this.target = target;
            this.card = card;
            density = target.getResources().getDisplayMetrics().density;
            slop = ViewConfiguration.get(target.getContext()).getScaledTouchSlop();
        }

        void touch(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                Rect bounds = new Rect();
                card.getGlobalVisibleRect(bounds);
                bounds.inset(-Math.round(12 * density), -Math.round(12 * density));
                if (!bounds.contains((int) event.getRawX(), (int) event.getRawY())) return;
                reset();
                pressed = true;
                startX = event.getRawX();
                startY = event.getRawY();
                velocity = VelocityTracker.obtain();
                velocity.addMovement(event);
                return;
            }
            if (!pressed) return;
            if (velocity != null) velocity.addMovement(event);
            float dx = event.getRawX() - startX;
            float dy = event.getRawY() - startY;
            float distance = (float) Math.hypot(dx, dy);
            if (action == MotionEvent.ACTION_MOVE) {
                moved |= distance >= slop;
                if (moved) {
                    target.setTranslationX(dx);
                    target.setTranslationY(dy);
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                float speed = 0;
                if (velocity != null) {
                    velocity.computeCurrentVelocity(1000);
                    speed = (float) Math.hypot(velocity.getXVelocity(), velocity.getYVelocity());
                }
                releaseVelocity();
                pressed = false;
                if (!moved || action == MotionEvent.ACTION_CANCEL || distance < 32 * density) {
                    target.animate().translationX(0).translationY(0).alpha(1)
                            .setDuration(220).setInterpolator(new OvershootInterpolator(0.45f)).start();
                } else {
                    dismiss(dx, dy, distance, speed);
                }
            }
        }

        private void dismiss(float dx, float dy, float distance, float speed) {
            float travel = Math.max(100 * density, Math.min(220 * density, speed * 0.12f));
            float endX = dx + dx / distance * travel;
            float endY = dy + dy / distance * travel;
            ValueAnimator animation = ValueAnimator.ofFloat(0, 1);
            dismiss = animation;
            animation.setDuration(Math.max(120, Math.min(220, Math.round(travel / Math.max(speed, 800 * density) * 1000))));
            animation.setInterpolator(new PathInterpolator(0.2f, 0, 0, 1));
            animation.addUpdateListener(value -> {
                float progress = (float) value.getAnimatedValue();
                target.setTranslationX(dx + (endX - dx) * progress);
                target.setTranslationY(dy + (endY - dy) * progress);
                target.setAlpha(1 - progress);
            });
            animation.addListener(new AnimatorListenerAdapter() {
                private boolean cancelled;
                @Override public void onAnimationCancel(Animator value) { cancelled = true; }
                @Override public void onAnimationEnd(Animator value) {
                    if (!cancelled) {
                        try { completed.invoke(callbacks.get(target)); }
                        catch (ReflectiveOperationException | RuntimeException ignored) { reset(); }
                    }
                }
            });
            try {
                initiated.invoke(callbacks.get(target), animation);
                animation.start();
            } catch (ReflectiveOperationException | RuntimeException ignored) { reset(); }
        }

        private void releaseVelocity() {
            if (velocity != null) { velocity.recycle(); velocity = null; }
        }

        void reset() {
            pressed = moved = false;
            releaseVelocity();
            if (dismiss != null) { dismiss.cancel(); dismiss = null; }
            target.animate().setListener(null).cancel();
            target.setTranslationX(0);
            target.setTranslationY(0);
            target.setAlpha(1);
        }

        @Override public void onViewAttachedToWindow(View view) {}
        @Override public void onViewDetachedFromWindow(View view) {
            reset();
            target.setTag(TAG, null);
            target.removeOnAttachStateChangeListener(this);
        }
    }
}
