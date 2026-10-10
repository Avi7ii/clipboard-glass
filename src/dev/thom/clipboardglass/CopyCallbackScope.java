package dev.thom.clipboardglass;

/** The OEM availability exception exists only during one native copy callback. */
final class CopyCallbackScope {
    private final ThreadLocal<Frame> active = new ThreadLocal<>();

    Frame enter(long copiedAt) {
        Frame frame = new Frame(active.get(), copiedAt);
        active.set(frame);
        return frame;
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
        private boolean availabilityPending = true;
        Frame(Frame parent, long copiedAt) { this.parent = parent; this.copiedAt = copiedAt; }
    }
}
