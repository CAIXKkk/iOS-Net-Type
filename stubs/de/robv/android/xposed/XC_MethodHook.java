package de.robv.android.xposed;

import java.lang.reflect.Member;

/** Compile-time stub of the real Xposed API (provided by LSPosed at runtime). */
public abstract class XC_MethodHook extends XCallback {
    public XC_MethodHook() {}
    public XC_MethodHook(int priority) {}

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static class Unhook {
        public void unhook() {}
    }

    public static class MethodHookParam extends XCallback.Param {
        public Member method;
        public Object getResult() { return null; }
        public void setResult(Object result) {}
        public Throwable throwable;
    }
}
