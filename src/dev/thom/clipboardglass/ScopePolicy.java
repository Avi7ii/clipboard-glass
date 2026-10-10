package dev.thom.clipboardglass;

final class ScopePolicy {
    private ScopePolicy() {}

    static boolean allows(String process, String packageName, boolean firstPackage,
            boolean systemServer, int sdk) {
        return sdk == 36 && !systemServer && firstPackage
                && "com.android.systemui".equals(process)
                && "com.android.systemui".equals(packageName);
    }
}
