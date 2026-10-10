package dev.thom.clipboardglass;

import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Resizes the actual native layout and keeps its text settings through OEM layout passes. */
final class PreviewAppearance {
    private static final int CARD_TAG = 0x7e0c0103;
    private static final int FONT_TAG = 0x7e0c0104;
    final Method updateTextSize;
    private final Field controllerView, preview, text, hidden, maxLines;
    private final ModuleSettings settings;

    PreviewAppearance(ClassLoader loader, ModuleSettings settings) throws ReflectiveOperationException {
        this.settings = settings;
        Class<?> controller = loader.loadClass("com.android.systemui.clipboardoverlay.ClipboardOverlayController");
        Class<?> root = loader.loadClass("com.android.systemui.clipboardoverlay.ClipboardOverlayView");
        controllerView = Reflect.field(controller, "mView");
        preview = Reflect.field(root, "mClipboardPreview");
        text = Reflect.field(root, "mTextPreview");
        hidden = Reflect.field(root, "mHiddenPreview");
        maxLines = Reflect.field(root, "mMaxLines");
        updateTextSize = Reflect.method(root, "updateTextSize", TextView.class, CharSequence.class);
    }

    void style(Object controller) {
        if (controller == null || !settings.enabled()) return;
        try {
            Object root = controllerView.get(controller);
            View card = (View) preview.get(root);
            Card state = (Card) card.getTag(CARD_TAG);
            if (state == null) {
                state = new Card(card, (TextView) text.get(root), (TextView) hidden.get(root));
                card.setTag(CARD_TAG, state);
            }
            boolean changed = state.resize(settings.size());
            changed |= applyFont(state.text);
            changed |= applyFont(state.hidden);
            if (changed) maxLines.setInt(root, 0);
            // Keep the OEM OnClickListener and its editor callback. Only restore clickability
            // when a new copy or layout update reuses a card disabled by the OEM listener.
            card.setEnabled(true);
            card.setClickable(true);
        } catch (ReflectiveOperationException | RuntimeException ignored) {}
    }

    boolean customizeText(TextView target) {
        if (!settings.enabled() || !(target.getTag(FONT_TAG) instanceof Font)) return false;
        applyFont(target);
        return true;
    }

    private boolean applyFont(TextView target) {
        Font state = (Font) target.getTag(FONT_TAG);
        if (state == null) {
            state = new Font(target.getTypeface());
            target.setTag(FONT_TAG, state);
        }
        int size = settings.fontSize(), weight = settings.fontWeight();
        boolean changed = state.size != size || state.weight != weight;
        target.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE);
        target.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        if (changed) {
            target.setTypeface(Typeface.create(state.original, weight, false));
            target.setFontVariationSettings("'wght' " + weight);
            state.size = size;
            state.weight = weight;
        }
        return changed;
    }

    private static final class Font {
        final Typeface original;
        int size = -1, weight = -1;
        Font(Typeface original) { this.original = original; }
    }

    private static final class Card {
        final View card;
        final TextView text, hidden;
        final int width, height, textWidth, textHeight, hiddenWidth, hiddenHeight;
        private int scale = -1;
        Card(View card, TextView text, TextView hidden) {
            this.card = card;
            this.text = text;
            this.hidden = hidden;
            ViewGroup.LayoutParams cp = card.getLayoutParams();
            width = cp.width;
            height = cp.height;
            ViewGroup.LayoutParams tp = text.getLayoutParams(), hp = hidden.getLayoutParams();
            textWidth = tp.width; textHeight = tp.height;
            hiddenWidth = hp.width; hiddenHeight = hp.height;
        }
        boolean resize(int percent) {
            if (scale == percent) return false;
            scale = percent;
            resize(card, width, height, percent);
            resize(text, textWidth, textHeight, percent);
            resize(hidden, hiddenWidth, hiddenHeight, percent);
            return true;
        }
        private static void resize(View target, int width, int height, int percent) {
            ViewGroup.LayoutParams params = target.getLayoutParams();
            params.width = width > 0 ? Math.round(width * percent / 100f) : width;
            params.height = height > 0 ? Math.round(height * percent / 100f) : height;
            target.setLayoutParams(params);
        }
    }
}
