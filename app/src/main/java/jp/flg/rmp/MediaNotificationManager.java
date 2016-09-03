package jp.flg.rmp;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.RemoteException;
import android.support.annotation.NonNull;

public class MediaNotificationManager extends BroadcastReceiver {
    private static final String TAG = LogHelper.makeLogTag(MediaNotificationManager.class);

    private static final String EXTRA_START_FULLSCREEN = "jp.flg.rmp.EXTRA_START_FULLSCREEN";
    private static final String EXTRA_CURRENT_MEDIA_DESCRIPTION = "jp.flg.rmp.CURRENT_MEDIA_DESCRIPTION";

    public static final String ACTION_PAUSE = "jp.flg.rmp.pause";
    public static final String ACTION_PLAY = "jp.flg.rmp.play";
    public static final String ACTION_PREV = "jp.flg.rmp.prev";
    public static final String ACTION_NEXT = "jp.flg.rmp.next";
    public static final String ACTION_STOP = "jp.flg.rmp.stop";

    private static final int NOTIFICATION_ID = 412;
    private static final int REQUEST_CODE = 100;
    private final MusicService mService;
    private final NotificationManager mNotificationManager;
    private final PendingIntent mPauseIntent;
    private final PendingIntent mPlayIntent;
    private final PendingIntent mPreviousIntent;
    private final PendingIntent mNextIntent;
    private final PendingIntent mStopCastIntent;
    private final int mNotificationColor;
    private MediaSession.Token mSessionToken;
    private MediaController mController;
    private MediaController.TransportControls mTransportControls;
    private PlaybackState mPlaybackState;
    private MediaMetadata mMetadata;
    private boolean mStarted = false;
    private final MediaController.Callback mCb = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(@NonNull PlaybackState state) {
            mPlaybackState = state;
            LogHelper.d(TAG, "Received new playback state", state);
            if (state.getState() == PlaybackState.STATE_STOPPED ||
                    state.getState() == PlaybackState.STATE_NONE) {
                stopNotification();
            } else {
                Notification notification = createNotification();
                if (notification != null) {
                    mNotificationManager.notify(NOTIFICATION_ID, notification);
                }
            }
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            mMetadata = metadata;
            LogHelper.d(TAG, "Received new metadata ", metadata);
            Notification notification = createNotification();
            if (notification != null) {
                mNotificationManager.notify(NOTIFICATION_ID, notification);
            }
        }

        @Override
        public void onSessionDestroyed() {
            super.onSessionDestroyed();
            LogHelper.d(TAG, "Session was destroyed, resetting to the new session token");
            try {
                updateSessionToken();
            } catch (RemoteException e) {
                LogHelper.e(TAG, e, "could not connect media controller");
            }
        }
    };

    public MediaNotificationManager(MusicService service) throws RemoteException {
        mService = service;
        updateSessionToken();

        mNotificationColor = getThemeColor(mService, R.attr.colorPrimary,
                Color.DKGRAY);

        mNotificationManager = (NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE);

        String pkg = mService.getPackageName();
        mPauseIntent = PendingIntent.getBroadcast(mService, REQUEST_CODE,
                new Intent(ACTION_PAUSE).setPackage(pkg), PendingIntent.FLAG_CANCEL_CURRENT);
        mPlayIntent = PendingIntent.getBroadcast(mService, REQUEST_CODE,
                new Intent(ACTION_PLAY).setPackage(pkg), PendingIntent.FLAG_CANCEL_CURRENT);
        mPreviousIntent = PendingIntent.getBroadcast(mService, REQUEST_CODE,
                new Intent(ACTION_PREV).setPackage(pkg), PendingIntent.FLAG_CANCEL_CURRENT);
        mNextIntent = PendingIntent.getBroadcast(mService, REQUEST_CODE,
                new Intent(ACTION_NEXT).setPackage(pkg), PendingIntent.FLAG_CANCEL_CURRENT);
        mStopCastIntent = PendingIntent.getBroadcast(mService, REQUEST_CODE,
                new Intent(ACTION_STOP).setPackage(pkg),
                PendingIntent.FLAG_CANCEL_CURRENT);

        // Cancel all notifications to handle the case where the Service was killed and
        // restarted by the system.
        mNotificationManager.cancelAll();
    }

    public void startNotification() {
        if (!mStarted) {
            mMetadata = mController.getMetadata();
            mPlaybackState = mController.getPlaybackState();

            // The notification must be updated after setting started to true
            Notification notification = createNotification();
            if (notification != null) {
                mController.registerCallback(mCb);
                IntentFilter filter = new IntentFilter();
                filter.addAction(ACTION_NEXT);
                filter.addAction(ACTION_PAUSE);
                filter.addAction(ACTION_PLAY);
                filter.addAction(ACTION_PREV);
                filter.addAction(ACTION_STOP);
                mService.registerReceiver(this, filter);

                mService.startForeground(NOTIFICATION_ID, notification);
                mStarted = true;
            }
        }
    }

    public void stopNotification() {
        if (mStarted) {
            mStarted = false;
            mController.unregisterCallback(mCb);
            try {
                mNotificationManager.cancel(NOTIFICATION_ID);
                mService.unregisterReceiver(this);
            } catch (IllegalArgumentException ex) {
                // ignore if the receiver is not registered.
            }
            mService.stopForeground(true);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        LogHelper.d(TAG, "Received intent with action " + action);
        switch (action) {
            case ACTION_PAUSE:
                mTransportControls.pause();
                break;
            case ACTION_PLAY:
                mTransportControls.play();
                break;
            case ACTION_NEXT:
                mTransportControls.skipToNext();
                break;
            case ACTION_PREV:
                mTransportControls.skipToPrevious();
                break;
            case ACTION_STOP:
                Intent i = new Intent(context, MusicService.class);
                i.setAction(MusicService.ACTION_CMD);
                i.putExtra(MusicService.CMD_NAME, MusicService.CMD_STOP);
                mService.startService(i);
                break;
            default:
                LogHelper.w(TAG, "Unknown intent ignored. Action=", action);
        }
    }

    private void updateSessionToken() throws RemoteException {
        MediaSession.Token freshToken = mService.getSessionToken();
        if (mSessionToken == null && freshToken != null ||
                mSessionToken != null && !mSessionToken.equals(freshToken)) {
            if (mController != null) {
                mController.unregisterCallback(mCb);
            }
            mSessionToken = freshToken;
            if (mSessionToken != null) {
                mController = new MediaController(mService, mSessionToken);
                mTransportControls = mController.getTransportControls();
                if (mStarted) {
                    mController.registerCallback(mCb);
                }
            }
        }
    }

    private PendingIntent createContentIntent(MediaDescription description) {
        Intent openUI = new Intent(mService, MainActivity.class);
        openUI.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        openUI.putExtra(EXTRA_START_FULLSCREEN, true);
        if (description != null) {
            openUI.putExtra(EXTRA_CURRENT_MEDIA_DESCRIPTION, description);
        }
        return PendingIntent.getActivity(mService, REQUEST_CODE, openUI,
                PendingIntent.FLAG_CANCEL_CURRENT);
    }

    private Notification createNotification() {
        LogHelper.d(TAG, "updateNotificationMetadata. mMetadata=" + mMetadata);
        if (mMetadata == null || mPlaybackState == null) {
            return null;
        }

        Notification.Builder notificationBuilder = new Notification.Builder(mService);

        // If skip to previous action is enabled
        if ((mPlaybackState.getActions() & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0) {
            Notification.Action.Builder action = new Notification.Action.Builder(
                    Icon.createWithResource(
                            mService.getApplicationContext(),
                            R.drawable.ic_skip_previous_white_24dp),
                    mService.getString(R.string.label_previous),
                    mPreviousIntent);
            notificationBuilder.addAction(action.build());
        }

        addPlayPauseAction(notificationBuilder);

        // If skip to next action is enabled
        if ((mPlaybackState.getActions() & PlaybackState.ACTION_SKIP_TO_NEXT) != 0) {
            Notification.Action.Builder action = new Notification.Action.Builder(
                    Icon.createWithResource(
                            mService.getApplicationContext(),
                            R.drawable.ic_skip_next_white_24dp),
                    mService.getString(R.string.label_next),
                    mNextIntent);
            notificationBuilder.addAction(action.build());
        }

        MediaDescription description = mMetadata.getDescription();

        notificationBuilder
                .setStyle(new Notification.MediaStyle()
                        .setShowActionsInCompactView(
                                new int[]{0, 1, 2})  // show only play/pause in compact view
                        .setMediaSession(mSessionToken))
                //
                .setColor(mNotificationColor)
                .setSmallIcon(R.drawable.ic_notification)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setUsesChronometer(true)
                .setContentIntent(createContentIntent(description))
                .setContentTitle(description.getTitle())
                .setContentText(description.getSubtitle());

        if (mController != null && mController.getExtras() != null) {
            String castName = mController.getExtras().getString(MusicService.EXTRA_CONNECTED_CAST);
            if (castName != null) {
                String castInfo = mService.getResources()
                        .getString(R.string.casting_to_device, castName);
                notificationBuilder.setSubText(castInfo);
                Notification.Action.Builder builder = new Notification.Action.Builder(
                        Icon.createWithResource(
                                mService.getApplicationContext(),
                                R.drawable.ic_close_black_24dp),
                        mService.getString(R.string.label_stop),
                        mStopCastIntent);
                notificationBuilder.addAction(builder.build());
            }
        }

        return notificationBuilder.build();
    }

    private void addPlayPauseAction(Notification.Builder builder) {
        LogHelper.d(TAG, "updatePlayPauseAction");
        String label;
        int icon;
        PendingIntent intent;
        if (mPlaybackState.getState() == PlaybackState.STATE_PLAYING) {
            label = mService.getString(R.string.label_pause);
            icon = R.drawable.ic_pause_white_24dp;
            intent = mPauseIntent;
        } else {
            label = mService.getString(R.string.label_play);
            icon = R.drawable.ic_play_arrow_white_24dp;
            intent = mPlayIntent;
        }
        Notification.Action.Builder action = new Notification.Action.Builder(
                Icon.createWithResource(
                        mService.getApplicationContext(),
                        icon),
                label,
                intent);
        builder.addAction(action.build());

    }

    private int getThemeColor(Context context, int attribute, int defaultColor) {
        int themeColor = 0;
        String packageName = context.getPackageName();
        try {
            Context packageContext = context.createPackageContext(packageName, 0);
            ApplicationInfo applicationInfo =
                    context.getPackageManager().getApplicationInfo(packageName, 0);
            packageContext.setTheme(applicationInfo.theme);
            Resources.Theme theme = packageContext.getTheme();
            TypedArray ta = theme.obtainStyledAttributes(new int[]{attribute});
            themeColor = ta.getColor(0, defaultColor);
            ta.recycle();
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return themeColor;
    }
}
