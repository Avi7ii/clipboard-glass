package dev.thom.clipboardglass;

public final class ScopePolicyTest {
    public static void main(String[] args) {
        for (int supported : new int[]{36, 37}) {
            check(ScopePolicy.allows("com.android.systemui", "com.android.systemui", true, false, supported));
            for (String other : new String[]{"android", "com.openai.chatgpt", "com.chunqiunativecheck",
                    "com.android.systemui:screenshot", "com.android.systemui:service", "", null}) {
                check(!ScopePolicy.allows(other, "com.android.systemui", true, false, supported));
                check(!ScopePolicy.allows("com.android.systemui", other, true, false, supported));
            }
            check(!ScopePolicy.allows("com.android.systemui", "com.android.systemui", false, false, supported));
            check(!ScopePolicy.allows("com.android.systemui", "com.android.systemui", true, true, supported));
        }
        for (int sdk : new int[]{34, 35, 38, 100})
            check(!ScopePolicy.allows("com.android.systemui", "com.android.systemui", true, false, sdk));
        System.out.println("Scope isolation tests passed");
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }
}
