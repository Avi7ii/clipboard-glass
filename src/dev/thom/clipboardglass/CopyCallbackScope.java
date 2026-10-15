package dev.thom.clipboardglass;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** The OEM availability exception exists only during one native copy callback. */
final class CopyCallbackScope {
    private final ThreadLocal<Frame> active = new ThreadLocal<>();
    private final Map<Object, Long> tasks = Collections.synchronizedMap(new WeakHashMap<>());

    Frame enter(long copiedAt) {
        return enter(copiedAt, true);
    }

    private Frame enter(long copiedAt, boolean availabilityPending) {
        Frame frame = new Frame(active.get(), copiedAt, availabilityPending);
        active.set(frame);
        return frame;
    }

    // Android 17 posts background and main-thread work. Carry only the original
    // timestamp on the OEM task object, without retaining clipboard data or granting
    // the region exception outside the synchronous availability check.
    void capture(Object task) {
        Frame frame = active.get();
        if (frame != null) tasks.put(task, frame.copiedAt);
    }

    Frame resume(Object task) {
        Long copiedAt = tasks.remove(task);
        return copiedAt == null ? null : enter(copiedAt, false);
    }

    void leave(Frame frame) {
        if (frame.parent == null) active.remove();
        else active.set(frame.parent);
    }

    boolean consumeAvailabilityCheck() {
        Frame frame = active.get();
        if (frame == null || !frame.availabilityPending) return false;
        frame.availabilityPending = false;
        return true;
    }

    Frame current() { return active.get(); }

    static final class Frame {
        private final Frame parent;
        final long copiedAt;
        private boolean availabilityPending;
        Frame(Frame parent, long copiedAt, boolean availabilityPending) {
            this.parent = parent;
            this.copiedAt = copiedAt;
            this.availabilityPending = availabilityPending;
        }
    }
}
