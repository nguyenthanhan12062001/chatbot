package com.chatai.an.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.chatai.an.core.AppLogger;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * Tracks ChatAI's foreground/task Activities so the Messenger-style Chat Heads overlay
 * can move the app task to background via {@link Activity#moveTaskToBack(boolean)} when
 * Expanded Chat opens, and restore the exact task/screen without finish() or session loss.
 */
public final class AppTaskTracker implements Application.ActivityLifecycleCallbacks {

    private static final String TAG = "AppTaskTracker";

    private WeakReference<Activity> resumedActivityRef = new WeakReference<>(null);
    private WeakReference<Activity> topAliveActivityRef = new WeakReference<>(null);
    private int mainTaskId = -1;
    private boolean taskMovedToBackByOverlay = false;

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        updateTopActivity(activity);
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        updateTopActivity(activity);
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        resumedActivityRef = new WeakReference<>(activity);
        updateTopActivity(activity);
        taskMovedToBackByOverlay = false;
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        Activity current = resumedActivityRef.get();
        if (current == activity) {
            resumedActivityRef = new WeakReference<>(null);
        }
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        Activity resumed = resumedActivityRef.get();
        if (resumed == activity) {
            resumedActivityRef = new WeakReference<>(null);
        }
        Activity top = topAliveActivityRef.get();
        if (top == activity) {
            topAliveActivityRef = new WeakReference<>(null);
        }
    }

    private void updateTopActivity(@NonNull Activity activity) {
        if (!activity.isFinishing() && !activity.isDestroyed()) {
            topAliveActivityRef = new WeakReference<>(activity);
            int taskId = activity.getTaskId();
            if (taskId > 0) {
                mainTaskId = taskId;
            }
        }
    }

    public boolean hasForegroundActivity() {
        Activity resumed = resumedActivityRef.get();
        return resumed != null && !resumed.isFinishing() && !resumed.isDestroyed();
    }

    @Nullable
    public Activity getResumedActivity() {
        Activity resumed = resumedActivityRef.get();
        return (resumed != null && !resumed.isFinishing() && !resumed.isDestroyed()) ? resumed : null;
    }

    @Nullable
    public Activity getTopAliveActivity() {
        Activity top = topAliveActivityRef.get();
        return (top != null && !top.isFinishing() && !top.isDestroyed()) ? top : null;
    }

    public int getMainTaskId() {
        return mainTaskId;
    }

    public boolean isTaskMovedToBackByOverlay() {
        return taskMovedToBackByOverlay;
    }

    /**
     * Moves ChatAI's foreground Activity task to the background without calling finish()
     * so Expanded Chat becomes the sole foreground surface while preserving session & streaming.
     */
    public boolean moveAppTaskToBackground() {
        Activity foreground = getResumedActivity();
        if (foreground == null) {
            return false;
        }
        try {
            boolean moved = foreground.moveTaskToBack(true);
            if (moved) {
                taskMovedToBackByOverlay = true;
            }
            return moved;
        } catch (Throwable t) {
            AppLogger.w(TAG, "moveTaskToBack failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Restores the existing ChatAI task and its previous top Activity to foreground.
     * Must be called while a visible TYPE_APPLICATION_OVERLAY window is still attached
     * to satisfy Android 14/15 Background Activity Launch (BAL) rules.
     */
    public void restoreAppTaskToForeground(@NonNull Context context, @Nullable String activeConversationId) {
        taskMovedToBackByOverlay = false;

        boolean movedExistingTask = false;
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                List<ActivityManager.AppTask> appTasks = am.getAppTasks();
                if (appTasks != null) {
                    for (ActivityManager.AppTask appTask : appTasks) {
                        ActivityManager.RecentTaskInfo info = appTask.getTaskInfo();
                        if (info == null) continue;
                        int infoTaskId = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? info.taskId : info.id;
                        if (mainTaskId <= 0 || infoTaskId == mainTaskId) {
                            appTask.moveToFront();
                            movedExistingTask = true;
                            break;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            AppLogger.w(TAG, "AppTask.moveToFront unavailable or skipped: " + t.getMessage());
        }

        Activity topAlive = getTopAliveActivity();
        Class<?> targetActivityClass = topAlive != null ? topAlive.getClass() : MainActivity.class;
        Intent intent = new Intent(context, targetActivityClass);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        if (targetActivityClass == MainActivity.class
                && topAlive == null
                && activeConversationId != null
                && !activeConversationId.isEmpty()) {
            intent.putExtra(MainActivity.EXTRA_OPEN_CONVERSATION_ID, activeConversationId);
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ActivityOptions options = ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(
                                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                        );
                int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
                PendingIntent pi = PendingIntent.getActivity(context, 7409, intent, piFlags, options.toBundle());
                pi.send(context, 0, null, null, null, null, options.toBundle());
            } else {
                context.startActivity(intent);
            }
        } catch (Throwable primaryErr) {
            AppLogger.w(TAG, "Primary task restore fallback to startActivity (movedTask="
                    + movedExistingTask + "): " + primaryErr.getMessage());
            try {
                context.startActivity(intent);
            } catch (Throwable fallbackErr) {
                AppLogger.e(TAG, "Unable to restore ChatAI task to foreground", fallbackErr);
            }
        }
    }

    public void onTaskRemoved() {
        resumedActivityRef = new WeakReference<>(null);
        topAliveActivityRef = new WeakReference<>(null);
        mainTaskId = -1;
        taskMovedToBackByOverlay = false;
    }
}
