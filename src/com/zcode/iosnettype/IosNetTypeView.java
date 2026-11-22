package com.zcode.iosnettype;

import android.content.Context;
import android.graphics.Typeface;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.android.systemui.statusbar.pipeline.shared.ui.binder.ModernStatusBarViewBinding;
import com.android.systemui.statusbar.pipeline.shared.ui.view.ModernStatusBarView;
import kotlin.jvm.functions.Function0;

/** Standalone network type ("5G"/"4G") indicator living in the "mobile" slot. */
public class IosNetTypeView extends ModernStatusBarView {
    private static final String TAG = "iOSNetType";
    private static final String SLOT = "mobile";

    private TextView mText;
    private final IosBinding mBinding = new IosBinding();

    public static void onNetTypeText(String text) {
        StateCenter.onNetTypeText(text);
    }

    public IosNetTypeView(Context context) {
        super(context, null);
        try {
            initView(SLOT, (Function0<ModernStatusBarViewBinding>) () -> mBinding);
            // Hide and collapse the default status_bar_dot view added by ModernStatusBarView.initView
            // so it never takes 78px width when 5G text is hidden.
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                child.setVisibility(View.GONE);
                child.setLayoutParams(new FrameLayout.LayoutParams(0, 0));
            }
            mText = new TextView(context);
            mText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f);
            mText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            mText.setGravity(Gravity.CENTER);
            mText.setIncludeFontPadding(false);
            mText.setSingleLine(true);
            int padH = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 2.5f, context.getResources().getDisplayMetrics());
            mText.setPadding(padH, 0, padH, 0);
            mText.setVisibility(View.GONE);
            addView(mText, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER));
        } catch (Throwable t) {
            // never let a broken view crash systemui
            Log.e(TAG, "view ctor failed", t);
        }
        StateCenter.addView(this);
        updateFromState();
    }

    private boolean isSiblingWifiVisible() {
        try {
            android.view.ViewParent vp = getParent();
            if (vp instanceof android.view.ViewGroup) {
                android.view.ViewGroup vg = (android.view.ViewGroup) vp;
                for (int i = 0, n = vg.getChildCount(); i < n; i++) {
                    View c = vg.getChildAt(i);
                    if (c != this && c instanceof ModernStatusBarView) {
                        ModernStatusBarView mv = (ModernStatusBarView) c;
                        if ("wifi".equals(mv.getSlot()) && mv.isIconVisible()) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean shouldShowText() {
        String t = StateCenter.getNetText();
        return t != null && !t.isEmpty() && !isSiblingWifiVisible();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (!shouldShowText()) {
            setMeasuredDimension(0, 0);
            return;
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    public void dispatchDraw(android.graphics.Canvas canvas) {
        if (!shouldShowText()) {
            return;
        }
        super.dispatchDraw(canvas);
    }

    void updateFromState() {
        try {
            String t = StateCenter.getNetText();
            boolean vis = shouldShowText();
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child != mText) {
                    child.setVisibility(View.GONE);
                }
            }
            if (mText != null) {
                mText.setText(vis ? t : "");
                mText.setVisibility(vis ? View.VISIBLE : View.GONE);
            }
            setVisibility(vis ? View.VISIBLE : View.GONE);
            requestLayout();
            invalidate();
            if (getParent() != null) {
                getParent().requestLayout();
            }
        } catch (Throwable ignored) {}
    }

    private class IosBinding implements ModernStatusBarViewBinding {
        @Override
        public boolean getShouldIconBeVisible() {
            return shouldShowText();
        }

        @Override
        public boolean isCollecting() {
            return true;
        }

        @Override
        public void onIconTintChanged(int tint, int contrastTint) {
            if (mText != null) mText.setTextColor(tint);
        }

        @Override
        public void onDecorTintChanged(int decorTint) {
        }

        @Override
        public void onVisibilityStateChanged(int state) {
            updateFromState();
        }
    }
}
