package dev.thom.clipboardglass;

import android.view.View;
import android.view.ViewGroup;
import android.view.DisplayCutout;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.graphics.Insets;

/** Computes a screen anchor directly; never accepts a previously IME-shifted margin. */
final class PreviewPosition {
    static void pinWindow(WindowManager.LayoutParams params) {
        params.softInputMode = (params.softInputMode & ~WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        // NOT_FOCUSABLE + ALT_FOCUSABLE_IM puts this window below the keyboard.
        // Clear only ALT_FOCUSABLE_IM so the fixed card stays visible without taking focus.
        params.flags = (params.flags | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                & ~WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        params.setFitInsetsTypes(0);
    }

    boolean apply(View view, WindowInsets insets, int orientation) {
        if (!(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams margins)) return false;
        Insets bars = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars());
        DisplayCutout cutout = insets.getDisplayCutout();
        Insets waterfall = cutout == null ? Insets.NONE : cutout.getWaterfallInsets();
        int left = waterfall.left, right = waterfall.right;
        int top = orientation == 1 && cutout != null
                ? Math.max(cutout.getSafeInsetTop(), waterfall.top) : waterfall.top;
        int bottom = Math.max(bars.bottom, waterfall.bottom);
        if (orientation == 1 && cutout != null) bottom = Math.max(bottom, cutout.getSafeInsetBottom());
        if (margins.leftMargin != left || margins.topMargin != top
                || margins.rightMargin != right || margins.bottomMargin != bottom) {
            margins.setMargins(left, top, right, bottom);
            view.setLayoutParams(margins);
        }
        return true;
    }
}
