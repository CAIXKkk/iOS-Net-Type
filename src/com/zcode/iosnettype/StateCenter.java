package com.zcode.iosnettype;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Holds the latest network type text (null = hidden) and pushes it to all views. */
public final class StateCenter {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<IosNetTypeView> VIEWS = new ArrayList<>();
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
        // copy to avoid CME if a view registers during iteration
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
        VIEWS.add(v);
        MAIN.post(v::updateFromState);
    }

    public static void removeView(IosNetTypeView v) {
        VIEWS.remove(v);
    }
}
