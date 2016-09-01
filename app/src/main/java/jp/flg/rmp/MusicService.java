package jp.flg.rmp;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.support.annotation.NonNull;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaBrowserServiceCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaButtonReceiver;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import java.lang.ref.WeakReference;
import java.util.List;

public class MusicService extends MediaBrowserServiceCompat implements
        Playback.PlaybackServiceCallback {

    public static final String MEDIA_ID_ROOT = "__ROOT__";
    // Extra on MediaSession that contains the Cast device name currently connected to
    public static final String EXTRA_CONNECTED_CAST = "jp.flg.rmp.CAST_NAME";
    // The action of the incoming Intent indicating that it contains a command
    // to be executed (see {@link #onStartCommand})
    public static final String ACTION_CMD = "jp.flg.rmp.ACTION_CMD";
    // The key in the extras of the incoming Intent indicating the command that
    // should be executed (see {@link #onStartCommand})
    public static final String CMD_NAME = "CMD_NAME";
    // A value of a CMD_NAME key in the extras of the incoming Intent that
    // indicates that the music playback should be paused (see {@link #onStartCommand})
    public static final String CMD_PAUSE = "CMD_PAUSE";
    // A value of a CMD_NAME key that indicates that the music playback should switch
    // to local playback from cast playback.
    public static final String CMD_STOP = "CMD_STOP";
    private static final String TAG = LogHelper.makeLogTag(MusicService.class);

    private MusicProvider mMusicProvider;
    private MediaSessionCompat mSession;
    private Playback mPlayback;
    private MediaNotificationManager mMediaNotificationManager;

    @Override
    public void onCreate() {
        super.onCreate();
        LogHelper.d(TAG, "onCreate");

        mMusicProvider = new MusicProvider(this);
        mPlayback = new Playback(this, mMusicProvider, this);

        // Start a new MediaSession
        mSession = new MediaSessionCompat(this, "MusicService");
        setSessionToken(mSession.getSessionToken());
        mSession.setCallback(mPlayback.getMediaSessionCallback());
        mSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

        mPlayback.updatePlaybackState(null);

        try {
            mMediaNotificationManager = new MediaNotificationManager(this);
        } catch (RemoteException e) {
            throw new IllegalStateException("Could not create a MediaNotificationManager", e);
        }
    }

    public int onStartCommand(Intent startIntent, int flags, int startId) {
        LogHelper.d(TAG, "onStartCommand");

        if (startIntent != null) {
            String action = startIntent.getAction();
            String command = startIntent.getStringExtra(CMD_NAME);
            if (ACTION_CMD.equals(action)) {
                if (CMD_PAUSE.equals(command)) {
                    mPlayback.handlePauseRequest();
                }
            } else {
                // Try to handle the intent as a media button event wrapped by MediaButtonReceiver
                MediaButtonReceiver.handleIntent(mSession, startIntent);
            }
        }

        return START_STICKY;
    }

    public void onDestroy() {
        LogHelper.d(TAG, "onDestroy");
        mPlayback.handleStopRequest(null);
        mMediaNotificationManager.stopNotification();
        mSession.release();
    }

    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid,
                                 Bundle rootHints) {
        LogHelper.d(TAG, "OnGetRoot: clientPackageName=" + clientPackageName,
                "; clientUid=" + clientUid + " ; rootHints=", rootHints);
        return new BrowserRoot(MEDIA_ID_ROOT, null);
    }

    public void onLoadChildren(@NonNull final String parentMediaId,
                               @NonNull final Result<List<MediaBrowserCompat.MediaItem>> result) {
        LogHelper.d(TAG, "OnLoadChildren: parentMediaId=", parentMediaId);
    }

    @Override
    public void onPlaybackStart() {
        if (!mSession.isActive()) {
            mSession.setActive(true);
        }

        startService(new Intent(getApplicationContext(), MusicService.class));
    }

    @Override
    public void onNotificationRequired() {
        mMediaNotificationManager.startNotification();
    }

    @Override
    public void onPlaybackStop() {
        stopForeground(true);
    }

    @Override
    public void onPlaybackStateUpdated(PlaybackStateCompat newState) {
        mSession.setPlaybackState(newState);
    }

    @Override
    public void onMetadataChanged(MediaMetadataCompat metadata) {
        mSession.setMetadata(metadata);
    }
}
