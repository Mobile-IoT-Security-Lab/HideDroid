package it.unige.hidedroid.task;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

import brut.util.Logger;
import it.unige.hidedroid.R;
import it.unige.hidedroid.activity.SaveSettingApkActivity;
import it.unige.hidedroid.view.MessageView;
import me.zhanghai.android.materialprogressbar.MaterialProgressBar;

public abstract class AbstractTask extends AsyncTask<AbstractTaskWrapper, CharSequence, Boolean> implements Logger {
    protected Context ctx;
    protected MessageView message;
    protected AlertDialog dialog;
    protected MaterialProgressBar progressBar;

    public AbstractTask(Context ctx) {
        this.ctx = ctx;
    }

    @Override
    protected Boolean doInBackground(AbstractTaskWrapper[] w1) {
        boolean success = true;
        for (File file : w1[0].files)
            if (!process(w1[0].selectedPrivacyLevels, w1[0].selectedPrivacyLevelsLock, file, w1[0].isDebugEnabled))
                success = false;
        return success;
    }

    @Override
    protected void onPreExecute() {
        Context ctx = this.ctx;
        message = new MessageView(ctx);

        TextView customTitle = new TextView(ctx);
        customTitle.setText(getTitle());
        customTitle.setTextSize(20);
        customTitle.setTypeface(customTitle.getTypeface(), Typeface.BOLD);
        customTitle.setPadding(20, 20, 10, 0);

        //dialog = new AlertDialog.Builder(ctx, R.style.CustomAlertDialog).
        //        setCustomTitle(customTitle).

        // OLD
        /*
        dialog = new AlertDialog.Builder(ctx, R.style.CustomAlertDialog).
        setView(message).
        setCustomTitle(customTitle).
        setTitle(getTitle()).
        setCancelable(false).
        show();
        */
        LayoutInflater li = LayoutInflater.from(this.ctx);
        View promptsLoadingButton = li.inflate(R.layout.progress_bar_loading, null);
        dialog = new AlertDialog.Builder(ctx, R.style.CustomAlertDialog).
                setView(promptsLoadingButton).
                setCustomTitle(customTitle).
                setTitle(getTitle()).
                setCancelable(false).
                show();
        this.progressBar = (MaterialProgressBar) promptsLoadingButton.findViewById(R.id.progress_bar_alert_dialog);


    }

    protected abstract int getTitle();

    protected abstract boolean process(Map<String, AtomicInteger> selectedPrivacyLevels, ReentrantLock selectedPrivacyLevelsLock,
                                       File f, AtomicBoolean isDebugEnabled);

    @Override
    protected void onPostExecute(Boolean result) {
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
                    //setView(m).
                            setCustomTitle(customTitle).
                    setTitle("Task Completed").
                    setPositiveButton("OK", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            SaveSettingApkActivity activity = (SaveSettingApkActivity) ctx;
                            activity.changeButtonSettings();
                        }
                    }).
                    show();

        }
    }

    protected boolean shouldShowFinishDialog() {
        return true;
    }

    @Override
    protected void onProgressUpdate(CharSequence[] values) {
        for (CharSequence s : values)
            message.append(s);
    }

    @Override
    public void info(String message) {
        Log.i("APKTOOL", message);
        publishProgress(String.format("I:%s\n", message));
    }

    @Override
    public void warning(String message) {
        Log.w("APKTOOL", message);
        publishProgress(String.format("W:%s\n", message));
    }

    @Override
    public void error(String message) {
        Log.e("APKTOOL", message);
        publishProgress(String.format("E:%s\n", message));
    }

    @Override
    public void log(Level level, String format, Throwable ex) {
        char ch = level.getName().charAt(0);
        String fmt = "%c:%s\n";
        publishProgress(String.format(fmt, ch, format));
        log(fmt, ch, ex);
    }

    private void log(String fmt, char ch, Throwable ex) {
        if (ex == null) return;
        publishProgress(String.format(fmt, ch, ex.getMessage()));
        for (StackTraceElement ste : ex.getStackTrace())
            publishProgress(String.format(fmt, ch, ste));
        log(fmt, ch, ex.getCause());
    }
}
