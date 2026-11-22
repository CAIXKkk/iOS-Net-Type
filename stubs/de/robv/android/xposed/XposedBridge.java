package de.robv.android.xposed;

import java.lang.reflect.Member;

/** Compile-time stub of the real Xposed API (provided by LSPosed at runtime). */
public final class XposedBridge {
    private XposedBridge() {}

    public static void log(String text) {}
    public static void log(Throwable t) {}

    public static java.util.Set<XC_MethodHook.Unhook> hookAllMethods(
            Class<?> hookClass, String methodName, XC_MethodHook callback) { return null; }

    public static java.util.Set<XC_MethodHook.Unhook> hookAllConstructors(
            Class<?> hookClass, XC_MethodHook callback) { return null; }

    public static XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook callback) { return null; }

    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args) { return null; }
}
