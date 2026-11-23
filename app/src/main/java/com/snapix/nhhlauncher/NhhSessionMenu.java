package com.snapix.nhhlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

public final class NhhSessionMenu {
    private static final int TAG_KEY = android.R.id.custom;
    private NhhSessionMenu() {}

    public static View attach(
            Activity activity,
            FrameLayout parent,
            Runnable editControls,
            Runnable cast,
            Runnable saveAndExit
    ) {
        final TextView badge = new TextView(activity);
        badge.setText("MENU");
        badge.setTextColor(0xFFFFFFFF);
        badge.setTextSize(11f);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(activity, 15), 0, dp(activity, 15), 0);
        badge.setMinHeight(dp(activity, 40));
        badge.setElevation(dp(activity, 12));
        badge.setContentDescription("Open NHH game menu");
        badge.setFocusable(true);
        badge.setClickable(true);
        badge.setTag(TAG_KEY, Boolean.TRUE);

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFFE94B43);
        background.setCornerRadius(dp(activity, 22));
        background.setStroke(dp(activity, 1), 0x66FFFFFF);
        badge.setBackground(background);

        badge.setOnClickListener(anchor -> showMenu(activity, editControls, cast, saveAndExit));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                dp(activity, 40),
                Gravity.TOP | Gravity.END
        );
        params.topMargin = dp(activity, 18);
        params.rightMargin = dp(activity, 18);
        parent.addView(badge, params);
        parent.bringChildToFront(badge);
        parent.post(() -> {
            badge.bringToFront();
            badge.setVisibility(View.VISIBLE);
            badge.requestLayout();
        });
        return badge;
    }

    public static void open(FrameLayout parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (Boolean.TRUE.equals(child.getTag(TAG_KEY))) {
                child.performClick();
                return;
            }
        }
    }

    private static void showMenu(
            Activity activity,
            Runnable editControls,
            Runnable cast,
            Runnable saveAndExit
    ) {
        final String[] items = {"Resume", "Edit Controls", "Cast", "Save and exit"};
        new AlertDialog.Builder(activity)
                .setTitle("NO HEROES HERE")
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0: dialog.dismiss(); break;
                        case 1: dialog.dismiss(); editControls.run(); break;
                        case 2: dialog.dismiss(); cast.run(); break;
                        case 3: dialog.dismiss(); saveAndExit.run(); break;
                    }
                })
                .setOnCancelListener(dialog -> {})
                .show();
    }

    private static int dp(Activity activity, float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}