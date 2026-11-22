package com.snapix.nhhlauncher;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
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
        badge.setText("NHH");
        badge.setTextColor(0xFFFFFFFF);
        badge.setTextSize(12f);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(activity, 15), 0, dp(activity, 15), 0);
        badge.setMinHeight(dp(activity, 34));
        badge.setElevation(dp(activity, 8));
        badge.setContentDescription("Open NHH game menu");
        badge.setTag(TAG_KEY, Boolean.TRUE);

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFFE94B43);
        background.setCornerRadius(dp(activity, 22));
        background.setStroke(dp(activity, 1), 0x55FFFFFF);
        badge.setBackground(background);

        badge.setOnClickListener(anchor -> showMenu(activity, badge, editControls, cast, saveAndExit));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                dp(activity, 36),
                Gravity.TOP | Gravity.END
        );
        params.topMargin = dp(activity, 12);
        params.rightMargin = dp(activity, 12);
        parent.addView(badge, params);
        badge.bringToFront();
        parent.post(() -> {
            badge.bringToFront();
            badge.setVisibility(View.VISIBLE);
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
            View anchor,
            Runnable editControls,
            Runnable cast,
            Runnable saveAndExit
    ) {
        PopupMenu popup = new PopupMenu(activity, anchor);
        popup.getMenu().add("Resume").setOnMenuItemClickListener(item -> true);
        popup.getMenu().add("Edit Controls").setOnMenuItemClickListener(item -> {
            editControls.run();
            return true;
        });
        popup.getMenu().add("Cast").setOnMenuItemClickListener(item -> {
            cast.run();
            return true;
        });
        popup.getMenu().add("Save and exit").setOnMenuItemClickListener(item -> {
            saveAndExit.run();
            return true;
        });
        popup.show();
    }

    private static int dp(Activity activity, float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
