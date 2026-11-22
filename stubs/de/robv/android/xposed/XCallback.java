package de.robv.android.xposed;

/** Compile-time stub of the real Xposed API (provided by LSPosed at runtime). */
public abstract class XCallback {
    public XCallback() {}
    public XCallback(int priority) {}

    public static class Param {
        public Object thisObject;
        public Object[] args;
        public Param() {}
        public Param(Object thisObject, Object[] args) {
            this.thisObject = thisObject;
            this.args = args;
        }
    }
}
