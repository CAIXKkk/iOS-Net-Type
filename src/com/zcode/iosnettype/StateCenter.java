package com.zcode.iosnettype;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/** Holds the latest network type text (null = hidden) and pushes it to attached views. */
public final class StateCenter {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<IosNetTypeView> VIEWS = Collections.newSetFromMap(new WeakHashMap<>());
    private static String sText;

    private StateCenter() {}

    public static void onNetTypeText(String text) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply(text);
        } else {
            MAIN.post(() -> apply(text));
        }
    }

    private static void apply(String text) {
        if (Objects.equals(text, sText)) return;
        sText = text;
        for (IosNetTypeView v : new ArrayList<>(VIEWS)) {
            try {
                v.updateFromState();
            } catch (Throwable ignored) {}
        }
    }

    public static String getNetText() {
        return sText;
    }

    public static void addView(IosNetTypeView v) {
        if (v == null) return;
        Runnable task = () -> {
            VIEWS.add(v);
            try {
                v.updateFromState();
            } catch (Throwable ignored) {}
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            MAIN.post(task);
        }
    }

    public static void removeView(IosNetTypeView v) {
        if (v == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            VIEWS.remove(v);
        } else {
            MAIN.post(() -> VIEWS.remove(v));
        }
    }
}
