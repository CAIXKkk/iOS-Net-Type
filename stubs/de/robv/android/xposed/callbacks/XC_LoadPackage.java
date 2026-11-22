package de.robv.android.xposed.callbacks;

import de.robv.android.xposed.XCallback;

/** Compile-time stub of the real Xposed API (provided by LSPosed at runtime). */
public class XC_LoadPackage extends XCallback {
    public XC_LoadPackage() {}

    public static class LoadPackageParam extends XCallback.Param {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public boolean isFirstApplication;
    }
}
