package jp.flg.rmp;

import android.content.Intent;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.RemoteException;
import android.service.media.MediaBrowserService;
import android.support.annotation.NonNull;

import java.util.List;

public class MusicService extends MediaBrowserService implements
        Playback.PlaybackServiceCallback {
    private static final String TAG = LogHelper.makeLogTag(MusicService.class);

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

    private MusicProvider mMusicProvider;
    private MediaSession mSession;
    private Playback mPlayback;
    private MediaNotificationManager mMediaNotificationManager;

    @Override
    public void onCreate() {
        super.onCreate();
        LogHelper.d(TAG, "onCreate");

        mMusicProvider = new MusicProvider(this);
        mPlayback = new Playback(this, mMusicProvider, this);

        // Start a new MediaSession
        mSession = new MediaSession(this, "MusicService");
        setSessionToken(mSession.getSessionToken());
        mSession.setCallback(mPlayback.getMediaSessionCallback());
        mSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);

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

    @Override
    public void onLoadChildren(@NonNull String parentMediaId, @NonNull Result <List<MediaBrowser.MediaItem>> result) {
        LogHelper.d(TAG, "OnLoadChildren", parentMediaId);
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
    public void onPlaybackStateUpdated(PlaybackState newState) {
        mSession.setPlaybackState(newState);
    }

    @Override
    public void onMetadataChanged(MediaMetadata metadata) {
        mSession.setMetadata(metadata);
    }
}
