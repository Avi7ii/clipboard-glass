package dev.thom.clipboardglass;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.IBinder;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Samples only a tiny backdrop region in RAM, excluding the clipboard window itself. */
final class BackgroundContrast {
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "PreviewContrast");
        thread.setDaemon(true);
        return thread;
    });
    private final Object manager;
    private final Constructor<?> optionsConstructor;
    private final Field crop, scale, full, excluded, display, buffer, secure, space;
    private final Method screenshot;

    BackgroundContrast(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> managerType = loader.loadClass("android.view.OplusWindowManager");
        Class<?> optionsType = loader.loadClass("com.oplus.app.OplusScreenShotOptions");
        Class<?> resultType = loader.loadClass("com.oplus.app.OplusScreenShotResult");
        manager = managerType.getConstructor().newInstance();
        optionsConstructor = optionsType.getConstructor();
        crop = Reflect.field(optionsType, "mSourceCrop");
        scale = Reflect.field(optionsType, "mScaleIndex");
        full = Reflect.field(optionsType, "mFullDisplay");
        excluded = Reflect.field(optionsType, "mExcludeWindows");
        display = Reflect.field(optionsType, "mDisplayId");
        buffer = Reflect.field(resultType, "mHardwareBuffer");
        secure = Reflect.field(resultType, "mContainsSecureLayers");
        space = Reflect.field(resultType, "mColorSpaceNamed");
        screenshot = Reflect.method(managerType, "getScreenshot", optionsType);
    }

    void sample(Rect area, IBinder token, int displayId, Consumer<Double> callback) {
        worker.execute(() -> {
            Double luminance = null;
            HardwareBuffer pixels = null;
            Bitmap hardware = null, software = null;
            try {
                Object options = optionsConstructor.newInstance();
                crop.set(options, area);
                scale.setFloat(options, 16f / Math.max(area.width(), area.height()));
                full.setBoolean(options, true);
                excluded.set(options, new IBinder[]{token});
                display.setInt(options, displayId);
                Object result = screenshot.invoke(manager, options);
                if (result != null) {
                    pixels = (HardwareBuffer) buffer.get(result);
                    if (pixels != null && !secure.getBoolean(result)
                            && pixels.getWidth() <= 64 && pixels.getHeight() <= 64) {
                        int colorSpace = space.getInt(result);
                        ColorSpace.Named[] names = ColorSpace.Named.values();
                        ColorSpace color = colorSpace >= 0 && colorSpace < names.length
                                ? ColorSpace.get(names[colorSpace]) : ColorSpace.get(ColorSpace.Named.SRGB);
                        hardware = Bitmap.wrapHardwareBuffer(pixels, color);
                        if (hardware != null) software = hardware.copy(Bitmap.Config.ARGB_8888, false);
                        if (software != null) {
                            double sum = 0;
                            int count = software.getWidth() * software.getHeight();
                            for (int y = 0; y < software.getHeight(); y++) {
                                for (int x = 0; x < software.getWidth(); x++) {
                                    int rgb = software.getPixel(x, y);
                                    sum += .2126 * linear(Color.red(rgb)) + .7152 * linear(Color.green(rgb))
                                            + .0722 * linear(Color.blue(rgb));
                                }
                            }
                            if (count > 0) luminance = sum / count;
                        }
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Secure or unsupported backdrops retain the theme's readable foreground.
            } finally {
                if (software != null) software.recycle();
                if (hardware != null) hardware.recycle();
                if (pixels != null) pixels.close();
            }
            callback.accept(luminance);
        });
    }

    private static double linear(int channel) {
        double value = channel / 255d;
        return value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4);
    }
}
