package com.android.systemui.statusbar.pipeline.shared.ui.binder;

/** Compile-time stub of the real SystemUI interface (resolved from SystemUI at runtime). */
public interface ModernStatusBarViewBinding {
    boolean getShouldIconBeVisible();

    boolean isCollecting();

    void onDecorTintChanged(int decorTint);

    void onIconTintChanged(int tint, int contrastTint);

    void onVisibilityStateChanged(int state);
}
