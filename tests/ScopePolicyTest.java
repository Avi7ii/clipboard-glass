package dev.thom.clipboardglass;

public final class ScopePolicyTest {
    public static void main(String[] args) {
        check(ScopePolicy.allows("com.android.systemui", "com.android.systemui", true, false, 36));
        for (String other : new String[]{"android", "com.openai.chatgpt", "com.chunqiunativecheck",
                "com.android.systemui:screenshot", "com.android.systemui:service", "", null}) {
            check(!ScopePolicy.allows(other, "com.android.systemui", true, false, 36));
            check(!ScopePolicy.allows("com.android.systemui", other, true, false, 36));
        }
        check(!ScopePolicy.allows("com.android.systemui", "com.android.systemui", false, false, 36));
        check(!ScopePolicy.allows("com.android.systemui", "com.android.systemui", true, true, 36));
        for (int sdk : new int[]{34, 35, 37})
            check(!ScopePolicy.allows("com.android.systemui", "com.android.systemui", true, false, sdk));
        System.out.println("Scope isolation tests passed");
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }
}
