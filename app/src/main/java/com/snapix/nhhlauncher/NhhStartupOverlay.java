package com.snapix.nhhlauncher;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

public final class NhhStartupOverlay {
    private static final String LOADING_TAG = "nhh-startup-loading";
    private static final String ERROR_TAG = "nhh-startup-error";

    private NhhStartupOverlay() {}

    public static void show(Activity activity, FrameLayout root, String message) {
        removeExisting(root);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(activity, 24), dp(activity, 24), dp(activity, 24), dp(activity, 24));
        panel.setBackgroundColor(Color.argb(238, 20, 11, 11));
        panel.setClickable(true);
        panel.setFocusable(true);
        panel.setTag(LOADING_TAG);

        ProgressBar spinner = new ProgressBar(activity);
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(dp(activity, 46), dp(activity, 46));
        spinnerParams.bottomMargin = dp(activity, 22);
        panel.addView(spinner, spinnerParams);

        TextView title = new TextView(activity);
        title.setText(message);
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        panel.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView subtitle = new TextView(activity);
        subtitle.setText("Preparing your game session");
        subtitle.setTextColor(0xFFCFBDB9);
        subtitle.setTextSize(13);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        subtitleParams.topMargin = dp(activity, 8);
        panel.addView(subtitle, subtitleParams);

        root.addView(panel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        panel.bringToFront();
    }

    public static void showError(Activity activity, FrameLayout root, String message, Runnable onReturn) {
        removeExisting(root);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(activity, 28), dp(activity, 24), dp(activity, 28), dp(activity, 24));
        panel.setBackgroundColor(Color.argb(245, 20, 11, 11));
        panel.setClickable(true);
        panel.setFocusable(true);
        panel.setTag(ERROR_TAG);

        TextView title = new TextView(activity);
        title.setText("Couldn't start the game");
        title.setTextColor(Color.WHITE);
        title.setTextSize(21);
        title.setGravity(Gravity.CENTER);
        panel.addView(title);

        TextView detail = new TextView(activity);
        detail.setText(message);
        detail.setTextColor(0xFFE4CFCA);
        detail.setTextSize(14);
        detail.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        detailParams.topMargin = dp(activity, 14);
        panel.addView(detail, detailParams);

        Button close = new Button(activity);
        close.setText("RETURN TO NHH");
        close.setOnClickListener(view -> onReturn.run());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(activity, 48)
        );
        buttonParams.topMargin = dp(activity, 22);
        panel.addView(close, buttonParams);

        root.addView(panel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        panel.bringToFront();
    }

    public static boolean isLoading(FrameLayout root) {
        return root.findViewWithTag(LOADING_TAG) != null;
    }

    public static void dismiss(FrameLayout root) {
        removeExisting(root);
    }

    private static void removeExisting(FrameLayout root) {
        View loading = root.findViewWithTag(LOADING_TAG);
        View error = root.findViewWithTag(ERROR_TAG);
        if (loading != null && loading.getParent() instanceof ViewGroup) {
            ((ViewGroup) loading.getParent()).removeView(loading);
        }
        if (error != null && error.getParent() instanceof ViewGroup) {
            ((ViewGroup) error.getParent()).removeView(error);
        }
    }

    private static int dp(Activity activity, float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
