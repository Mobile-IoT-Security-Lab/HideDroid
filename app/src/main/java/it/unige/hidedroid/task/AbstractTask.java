package it.unige.hidedroid.task;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

import apktool.module.Logger;
import it.unige.hidedroid.R;
import it.unige.hidedroid.activity.SaveSettingApkActivity;
import it.unige.hidedroid.view.MessageView;
import me.zhanghai.android.materialprogressbar.MaterialProgressBar;

public abstract class AbstractTask implements Logger {
    protected Context ctx;
    protected MessageView message;
    protected AlertDialog dialog;
    protected MaterialProgressBar progressBar;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable onFinishedListener;

    public AbstractTask(Context ctx) {
        this.ctx = ctx;
    }

    /**
     * Invoked after the finish dialog is acknowledged, in addition to (not
     * instead of) the SaveSettingApkActivity-specific refresh below. Lets
     * other callers (e.g. the app list quick actions) refresh their own UI.
     */
    public void setOnFinishedListener(Runnable onFinishedListener) {
        this.onFinishedListener = onFinishedListener;
    }

    /**
     * True once the hosting Activity is gone (destroyed/finishing) - the list's
     * quick actions let the user navigate away while a repack still runs in
     * the background, unlike the modal modification screen this was written for.
     */
    private boolean isContextGone() {
        return ctx instanceof Activity && (((Activity) ctx).isDestroyed() || ((Activity) ctx).isFinishing());
    }

    /** Dismisses the progress dialog if still showing; safe to call from any thread or state. */
    public void dismissDialogSafely() {
        uiHandler.post(() -> {
            if (dialog != null && dialog.isShowing()) {
                try {
                    dialog.dismiss();
                } catch (Exception ignored) {
                    // Window already gone (e.g. Activity destroyed concurrently) - nothing to clean up.
                }
            }
        });
    }

    /**
     * Starts the task on a background thread and posts progress/finish events
     * to the main thread. Replaces the deprecated AsyncTask implementation.
     */
    public void start(AbstractTaskWrapper wrapper) {
        if (isContextGone()) {
            return;
        }
        showDialog();
        executor.execute(() -> {
            boolean success = true;
            for (File file : wrapper.files) {
                if (!process(wrapper.selectedPrivacyLevels, wrapper.selectedPrivacyLevelsLock,
                        file, wrapper.isDebugEnabled)) {
                    success = false;
                }
            }
            final boolean result = success;
            uiHandler.post(() -> onFinished(result));
        });
    }

    private void showDialog() {
        Context ctx = this.ctx;
        message = new MessageView(ctx);

        TextView customTitle = new TextView(ctx);
        customTitle.setText(getTitle());
        customTitle.setTextSize(20);
        customTitle.setTypeface(customTitle.getTypeface(), Typeface.BOLD);
        customTitle.setPadding(20, 20, 10, 0);

        LayoutInflater li = LayoutInflater.from(this.ctx);
        View promptsLoadingButton = li.inflate(R.layout.progress_bar_loading, null);
        dialog = new AlertDialog.Builder(ctx, R.style.CustomAlertDialog).
                setView(promptsLoadingButton).
                setCustomTitle(customTitle).
                setTitle(getTitle()).
                setCancelable(false).
                show();
        this.progressBar = (MaterialProgressBar) promptsLoadingButton.findViewById(R.id.progress_bar_alert_dialog);
        ((FrameLayout) promptsLoadingButton.findViewById(R.id.message_container)).addView(message);
    }

    protected abstract int getTitle();

    protected abstract boolean process(Map<String, AtomicInteger> selectedPrivacyLevels, ReentrantLock selectedPrivacyLevelsLock,
                                       File f, AtomicBoolean isDebugEnabled);

    private void onFinished(boolean result) {
        if (isContextGone()) {
            dismissDialogSafely();
            return;
        }
        CharSequence text = message.getText();
        dialog.dismiss();
        if (shouldShowFinishDialog()) {
            Context ctx = this.ctx;
            MessageView m = new MessageView(ctx);
            m.append(text);

            TextView customTitle = new TextView(ctx);
            customTitle.setText("Task completed");
            customTitle.setTextSize(20);
            customTitle.setTypeface(customTitle.getTypeface(), Typeface.BOLD);
            customTitle.setPadding(20, 20, 10, 0);

            new AlertDialog.Builder(ctx, R.style.CustomAlertDialog).
                    setView(m).
                    setCustomTitle(customTitle).
                    setTitle("Task Completed").
                    setPositiveButton("OK", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            if (ctx instanceof SaveSettingApkActivity) {
                                ((SaveSettingApkActivity) ctx).changeButtonSettings();
                            }
                            if (onFinishedListener != null) {
                                onFinishedListener.run();
                            }
                        }
                    }).
                    show();
        }
    }

    protected boolean shouldShowFinishDialog() {
        return true;
    }

    private void appendMessage(CharSequence s) {
        message.append(s);
    }

    @Override
    public void info(String message) {
        Log.i("APKTOOL", message);
        uiHandler.post(() -> appendMessage(String.format("I:%s\n", message)));
    }

    @Override
    public void warning(String message) {
        Log.w("APKTOOL", message);
        uiHandler.post(() -> appendMessage(String.format("W:%s\n", message)));
    }

    @Override
    public void error(String message) {
        Log.e("APKTOOL", message);
        uiHandler.post(() -> appendMessage(String.format("E:%s\n", message)));
    }

    @Override
    public void log(Level level, String format, Throwable ex) {
        char ch = level.getName().charAt(0);
        String fmt = "%c:%s\n";
        uiHandler.post(() -> {
            appendMessage(String.format(fmt, ch, format));
            log(fmt, ch, ex);
        });
    }

    private void log(String fmt, char ch, Throwable ex) {
        if (ex == null) return;
        appendMessage(String.format(fmt, ch, ex.getMessage()));
        for (StackTraceElement ste : ex.getStackTrace())
            appendMessage(String.format(fmt, ch, ste));
        log(fmt, ch, ex.getCause());
    }
}
