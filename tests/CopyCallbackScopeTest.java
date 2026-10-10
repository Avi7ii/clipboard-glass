package dev.thom.clipboardglass;

public final class CopyCallbackScopeTest {
    public static void main(String[] args) throws Exception {
        CopyCallbackScope scope = new CopyCallbackScope();
        check(!scope.consumeAvailabilityCheck());
        CopyCallbackScope.Frame outer = scope.enter(1250);
        check(scope.current() == outer && outer.copiedAt == 1250);
        CopyCallbackScope.Frame inner = scope.enter(1300);
        check(scope.consumeAvailabilityCheck() && !scope.consumeAvailabilityCheck());
        scope.leave(inner);
        check(scope.current() == outer && scope.consumeAvailabilityCheck());
        check(!scope.consumeAvailabilityCheck());
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
            check(scope.current() == null && !scope.consumeAvailabilityCheck());
            CopyCallbackScope.Frame own = scope.enter(1400);
            check(scope.consumeAvailabilityCheck());
            scope.leave(own);
            check(scope.current() == null);
            } catch (Throwable error) { failure.set(error); }
        });
        other.start(); other.join();
        check(failure.get() == null);
        check(scope.current() == outer && !scope.consumeAvailabilityCheck());
        scope.leave(outer);
        try {
            CopyCallbackScope.Frame failing = scope.enter(1500);
            try { throw new IllegalStateException(); }
            finally { scope.leave(failing); }
        } catch (IllegalStateException expected) {}
        check(scope.current() == null && !scope.consumeAvailabilityCheck());
        System.out.println("Native callback scope: single check, nesting, threads and exception cleanup passed");
    }
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
}
