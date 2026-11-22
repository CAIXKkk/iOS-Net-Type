package com.zcode.iosnettype;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import dalvik.system.InMemoryDexClassLoader;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.zip.ZipFile;

public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "iOSNetType";
    private static final String MODULE_PKG = "com.zcode.iosnettype";
    private static boolean sControllerCaptured = false;
    private static boolean sFirstCallLogged = false;
    private static volatile String sRawNetText = null;
    private static volatile boolean sWifiVisible = false;
    private static volatile String sEffectiveText = null;
    private static volatile Class<?> sViewCls = null;
    private static volatile Object sController = null;
    private static volatile Object sMobileHolder = null;

    // pure-java reflection helpers (avoid the obfuscated XposedHelpers entirely)
    private static Class<?> findCls(String name, ClassLoader cl) throws Exception {
        return Class.forName(name, false, cl);
    }

    private static Object call(Object obj, String name, Class<?>[] sig, Object... args) throws Exception {
        Method m = obj.getClass().getMethod(name, sig);
        return m.invoke(obj, args);
    }

    private static Object callStatic(Class<?> cls, String name, Class<?>[] sig, Object... args) throws Exception {
        Method m = cls.getMethod(name, sig);
        return m.invoke(null, args);
    }

    private static Object getField(Object obj, String name) throws Exception {
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
        try {
            XposedBridge.log(TAG + ": " + msg);
        } catch (Throwable ignored) {}
    }

    private static void logErr(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        try {
            XposedBridge.log(TAG + ": " + msg + " -> " + t);
            XposedBridge.log(t);
        } catch (Throwable ignored) {}
    }

    private static String extractTextFromModel(Object model) {
        if (model == null) return null;
        try {
            Object nt = call(model, "getNetworkTypeText", new Class<?>[0]);
            if (nt != null) {
                Object prefix = null;
                try {
                    prefix = call(nt, "getPrefix", new Class<?>[0]);
                } catch (Throwable ignored) {}
                if (!(prefix instanceof String) || ((String) prefix).isEmpty()) {
                    try {
                        prefix = getField(nt, "prefix");
                    } catch (Throwable ignored) {}
                }
                if (prefix instanceof String && !((String) prefix).isEmpty()
                        && !"Unknown".equals(prefix)) {
                    return (String) prefix;
                }
                Object name = getField(nt, "name");
                if (name instanceof String && !((String) name).isEmpty()
                        && !"Unknown".equals(name)) {
                    return (String) name;
                }
            }
        } catch (Throwable t) {
            logErr("extractTextFromModel failed", t);
        }
        return null;
    }

    private static void updateEffectiveState(String src) {
        final String effective = sWifiVisible ? null : sRawNetText;
        if (!sFirstCallLogged || !java.util.Objects.equals(sEffectiveText, effective)) {
            sFirstCallLogged = true;
            sEffectiveText = effective;
            log("state -> effective=" + effective + " (raw=" + sRawNetText + ", wifi=" + sWifiVisible + ", src=" + src + ")");
        }
        Class<?> vc = sViewCls;
        if (vc != null) {
            try {
                Method m = vc.getMethod("onNetTypeText", String.class);
                m.invoke(null, effective);
            } catch (Throwable t) {
                logErr("onNetTypeText invoke failed", t);
            }
        }
        final Object ctrl = sController;
        if (ctrl != null && sMobileHolder != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    Method setVis = ctrl.getClass().getMethod("setIconVisibility", String.class, boolean.class);
                    setVis.invoke(ctrl, "mobile", effective != null && !effective.isEmpty());
                } catch (Throwable ignored) {}
            });
        }
    }

    private static void publishRawText(String text, String rawSource) {
        sRawNetText = text;
        updateEffectiveState(rawSource);
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpp) {
        if (!"com.android.systemui".equals(lpp.packageName)) return;
        log("loading into systemui");
        final ClassLoader cl = lpp.classLoader;

        // Hook 1A: Read-only observer on upstream flow combiner in OplusStackedMobileIconViewModelImpl.
        // Do NOT mutate arr[i] here so Compose SnapshotMutableState transitions normally.
        try {
            Class<?> combineCls = findCls(
                    "com.oplus.systemui.statusbar.pipeline.ui.viewmodel.OplusStackedMobileIconViewModelImpl$networkTypeTextInfo_delegate$lambda$9$$inlined$combine$1$3", cl);
            XposedBridge.hookAllMethods(combineCls, "invokeSuspend", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object arrObj = getField(param.thisObject, "L$1");
                        if (arrObj instanceof Object[]) {
                            Object[] arr = (Object[]) arrObj;
                            // ArraysKt.firstOrNull(T[]) strictly returns arr.length == 0 ? null : arr[0]
                            Object primaryModel = arr.length > 0 ? arr[0] : null;
                            String text = extractTextFromModel(primaryModel);
                            publishRawText(text, "flow");
                        }
                    } catch (Throwable t) {
                        logErr("combine hook failed", t);
                    }
                }
            });
            log("hooked networkTypeTextInfo flow combiner");
        } catch (Throwable t) {
            logErr("hook flow combiner failed", t);
        }

        // Hook 1B: Getter hook on getNetworkTypeTextInfo() — captures both non-null and null,
        // and returns null so the welded 5G/4G badge on the signal icon is never drawn by Compose.
        XC_MethodHook netTextHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object model = param.getResult();
                    String text = extractTextFromModel(model);
                    publishRawText(text, "getter");
                    if (model != null) {
                        param.setResult(null);
                    }
                } catch (Throwable t) {
                    logErr("capture failed", t);
                }
            }
        };
        String[] vmImpls = {
                "com.oplus.systemui.statusbar.pipeline.ui.viewmodel.OplusStackedMobileIconViewModelImpl",
        };
        for (String name : vmImpls) {
            try {
                XposedBridge.hookAllMethods(findCls(name, cl),
                        "getNetworkTypeTextInfo", netTextHook);
                log("hooked " + name);
            } catch (Throwable t) {
                logErr("hook " + name + " failed", t);
            }
        }

        // Hook 2: Observe ModernStatusBarView.isIconVisible() & setVisibleState() on the base class
        // for slot="wifi" so our standalone 5G view is strictly mutually exclusive with the Wi-Fi icon.
        try {
            Class<?> modernViewCls = findCls(
                    "com.android.systemui.statusbar.pipeline.shared.ui.view.ModernStatusBarView", cl);
            XC_MethodHook wifiVisHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object view = param.thisObject;
                        if (view == null) return;
                        Object slot = call(view, "getSlot", new Class<?>[0]);
                        if ("wifi".equals(slot)) {
                            boolean vis = (Boolean) call(view, "isIconVisible", new Class<?>[0]);
                            if (sWifiVisible != vis) {
                                sWifiVisible = vis;
                                updateEffectiveState("ModernStatusBarView(wifi).vis=" + vis);
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            };
            XposedBridge.hookAllMethods(modernViewCls, "isIconVisible", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object view = param.thisObject;
                        if (view == null) return;
                        Object slot = call(view, "getSlot", new Class<?>[0]);
                        if ("wifi".equals(slot) && param.getResult() instanceof Boolean) {
                            boolean vis = (Boolean) param.getResult();
                            if (sWifiVisible != vis) {
                                sWifiVisible = vis;
                                updateEffectiveState("ModernStatusBarView(wifi).isIconVisible=" + vis);
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.hookAllMethods(modernViewCls, "setVisibleState", wifiVisHook);
            log("hooked ModernStatusBarView for wifi visibility");
        } catch (Throwable t) {
            logErr("hook ModernStatusBarView failed", t);
        }

        // Hook 4: kill the mobile data-activity arrows (small in/out triangles on the stacked
        // dual-SIM bars). MobileIconSets.getStackedActivityIndicatorIcon(activityIn, activityOut,
        // isSoftSim) is the single resId selector; its consumer (OplusStackedMobileIconLayoutStrategy)
        // draws the indicator only when resId != 0, and 0 is the designed "no indicator" value
        // (StateFlow initial). Force 0 on the base class plus every carrier icon-set variant.
        String[] iconSets = {
                "com.oplus.systemui.statusbar.policy.MobileIconSets",
                "com.oplus.systemui.statusbar.phone.signal.network.BigTypeMobileIconSets",
                "com.oplus.systemui.statusbar.phone.signal.network.MobileIconSetsSignalFive",
                "com.oplus.systemui.statusbar.phone.signal.network.TMOMobileIconSets",
                "com.oplus.systemui.statusbar.phone.signal.network.OplusCustMobileTypeSets",
        };
        for (final String setName : iconSets) {
            try {
                java.util.Set<?> unhooks = XposedBridge.hookAllMethods(findCls(setName, cl),
                        "getStackedActivityIndicatorIcon", new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                param.setResult(0);
                            }
                        });
                int count = unhooks == null ? 0 : unhooks.size();
                if (count > 0) {
                    log("hooked " + setName + ".getStackedActivityIndicatorIcon (methods=" + count + ")");
                } else {
                    log("skip " + setName + ".getStackedActivityIndicatorIcon (not declared)");
                }
            } catch (Throwable t) {
                logErr("hook " + setName + " failed", t);
            }
        }
        // Belt: ViewModel getter — Compose checks != 0 before painting the arrow Image.
        try {
            XposedBridge.hookAllMethods(findCls(
                    "com.oplus.systemui.statusbar.pipeline.ui.viewmodel.OplusStackedMobileIconViewModelImpl", cl),
                    "getActivityIndicatorIconResId", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(0);
                        }
                    });
            log("hooked OplusStackedMobileIconViewModelImpl.getActivityIndicatorIconResId");
        } catch (Throwable t) {
            logErr("hook getActivityIndicatorIconResId failed", t);
        }

        // Hook 5: kill the wifi data-activity arrows (wifi_inout overlay + legacy wifi_in/out).
        // 5a) OplusWifiViewModel.wifiActivityResId is a StateFlow<Integer> hydrated from this
        // combiner lambda; 0 is its designed initial value ("no icon"), so every emission -> 0
        // makes the binder never attach an arrow drawable.
        try {
            XposedBridge.hookAllMethods(findCls(
                    "com.oplus.systemui.statusbar.pipeline.wifi.ui.viewmodel.OplusWifiViewModel$wifiActivityResId$1", cl),
                    "invokeSuspend", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(Integer.valueOf(0));
                        }
                    });
            log("hooked OplusWifiViewModel wifiActivityResId combiner");
        } catch (Throwable t) {
            logErr("hook wifi activity combiner failed", t);
        }
        // 5b) Collapse the arrow child views on every wifi icon instance (statusbar/shade/keyguard)
        // so arrows can never be drawn even if the flow hook misses. Same technique as
        // 0x0 layout params survive later setVisibility(VISIBLE).
        try {
            XposedBridge.hookAllConstructors(findCls(
                    "com.oplus.systemui.statusbar.phone.signal.widget.OplusModernStatusBarWifiView", cl),
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            final android.view.View view = (android.view.View) param.thisObject;
                            new Handler(Looper.getMainLooper()).post(new Runnable() {
                                @Override
                                public void run() {
                                    collapseWifiArrowViews(view);
                                }
                            });
                        }
                    });
            log("hooked OplusModernStatusBarWifiView ctor for arrow collapse");
        } catch (Throwable t) {
            logErr("hook OplusModernStatusBarWifiView ctor failed", t);
        }


        // Hook 3: capture the icon controller, then register our standalone view.
        Class<?> implCls;
        try {
            implCls = findCls(
                    "com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl", cl);
        } catch (Throwable t) {
            logErr("controller class not found", t);
            return;
        }
        // Prevent setNewMobileIconSubIds from adding legacy unstacked MOBILE_NEW sub-slots (tag=3,4)
        // into the "mobile" slot or wiping our standalone 5G BindableIconHolder.
        XposedBridge.hookAllMethods(implCls, "setNewMobileIconSubIds", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(null);
            }
        });
        XposedBridge.hookAllMethods(implCls, "removeAllIconsForSlot", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (sMobileHolder != null && param.args.length > 0 && "mobile".equals(param.args[0])) {
                    param.setResult(null);
                }
            }
        });
        XposedBridge.hookAllMethods(implCls, "setIconVisibility", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.args.length >= 2 && "wifi".equals(param.args[0]) && param.args[1] instanceof Boolean) {
                    boolean vis = (Boolean) param.args[1];
                    if (sWifiVisible != vis) {
                        sWifiVisible = vis;
                        updateEffectiveState("setIconVisibility(wifi)");
                    }
                }
            }
        });
        XC_MethodHook captureControllerHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (sControllerCaptured) return;
                final Object controller = param.thisObject;
                if (controller == null) return;
                sControllerCaptured = true;
                sController = controller;
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        Context sysuiCtx = (Context) getField(controller, "mContext");
                        Class<?> vc = loadViewDex(sysuiCtx, cl);
                        sViewCls = vc;
                        updateEffectiveState("init");
                        register(controller, cl, vc);
                        updateEffectiveState("post-register");
                    } catch (Throwable t) {
                        logErr("init failed", t);
                    }
                });
            }
        };
        XposedBridge.hookAllConstructors(implCls, captureControllerHook);
        XposedBridge.hookAllMethods(implCls, "addIconGroup", captureControllerHook);
        XposedBridge.hookAllMethods(implCls, "setIcon", captureControllerHook);
        XposedBridge.hookAllMethods(implCls, "setIconVisibility", captureControllerHook);
    }

    private static Class<?> loadViewDex(Context sysuiCtx, ClassLoader hostCl) throws Exception {
        String apkPath = sysuiCtx.getPackageManager()
                .getApplicationInfo(MODULE_PKG, 0).sourceDir;
        byte[] dexBytes;
        try (ZipFile zf = new ZipFile(apkPath)) {
            InputStream in = zf.getInputStream(zf.getEntry("assets/view.dex"));
            dexBytes = readAll(in);
        }
        ClassLoader viewCl = new InMemoryDexClassLoader(ByteBuffer.wrap(dexBytes), hostCl);
        return viewCl.loadClass("com.zcode.iosnettype.IosNetTypeView");
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    /** Collapses the wifi data-activity arrow child views (wifi_inout/wifi_in/wifi_out) to 0x0. */
    private static void collapseWifiArrowViews(android.view.View root) {
        if (root == null) return;
        try {
            android.content.res.Resources res = root.getResources();
            int[] ids = new int[]{
                    res.getIdentifier("wifi_inout", "id", "com.android.systemui"),
                    res.getIdentifier("wifi_in", "id", "com.android.systemui"),
                    res.getIdentifier("wifi_out", "id", "com.android.systemui"),
                    res.getIdentifier("inout_container", "id", "com.android.systemui"),
            };
            collapseViewById(root, ids);
        } catch (Throwable ignored) {}
    }

    private static void collapseViewById(android.view.View v, int[] ids) {
        try {
            for (int id : ids) {
                if (id != 0 && v.getId() == id) {
                    android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
                    if (lp != null) {
                        lp.width = 0;
                        lp.height = 0;
                        v.setLayoutParams(lp);
                    }
                    v.setVisibility(android.view.View.GONE);
                }
            }
        } catch (Throwable ignored) {}
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                collapseViewById(vg.getChildAt(i), ids);
            }
        }
    }


    private static void register(Object controller, ClassLoader cl, Class<?> viewCls) throws Exception {
        final Class<?> creatorIface = findCls(
                "com.android.systemui.statusbar.pipeline.icons.shared.model.ModernStatusBarViewCreator", cl);
        Object creator = Proxy.newProxyInstance(cl, new Class<?>[]{creatorIface},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if ("createAndBind".equals(method.getName())) {
                            Context ctx = (Context) args[0];
                            log("createAndBind called for mobile slot");
                            return viewCls.getConstructor(Context.class).newInstance(ctx);
                        }
                        return null;
                    }
                });
        Class<?> baseHolderCls = findCls(
                "com.android.systemui.statusbar.phone.StatusBarIconHolder", cl);
        Class<?> holderCls = findCls(
                "com.android.systemui.statusbar.phone.StatusBarIconHolder$BindableIconHolder", cl);
        Object holder = holderCls.getDeclaredConstructor(creatorIface, String.class)
                .newInstance(creator, "mobile");
        sMobileHolder = holder;
        Method setIcon = controller.getClass().getMethod("setIcon", String.class, baseHolderCls);
        setIcon.invoke(controller, "mobile", holder);
        log("slot mobile registered");
    }
}
