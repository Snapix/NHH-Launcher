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
        badge.setContentDescription("NHH session menu");

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFFE94B43);
        background.setCornerRadius(dp(activity, 22));
        background.setStroke(dp(activity, 1), 0x55FFFFFF);
        badge.setBackground(background);

        badge.setOnClickListener(anchor -> {
            PopupMenu popup = new PopupMenu(activity, badge);
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
        });

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                dp(activity, 36),
                Gravity.TOP | Gravity.END
        );
        params.topMargin = dp(activity, 12);
        params.rightMargin = dp(activity, 12);
        parent.addView(badge, params);
        badge.bringToFront();
        return badge;
    }

    private static int dp(Activity activity, float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
