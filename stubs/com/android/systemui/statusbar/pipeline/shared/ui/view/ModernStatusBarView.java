package com.android.systemui.statusbar.pipeline.shared.ui.view;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import kotlin.jvm.functions.Function0;

/** Compile-time stub of the real SystemUI class (resolved from SystemUI at runtime). */
public class ModernStatusBarView extends FrameLayout {
    public ModernStatusBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void initView(String slot, Function0 bindingFactory) {}

    public String getSlot() { return null; }

    public boolean isIconVisible() { return false; }
}
