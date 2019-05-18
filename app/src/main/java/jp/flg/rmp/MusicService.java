package jp.flg.rmp;

import android.app.Service;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser.MediaItem;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.service.media.MediaBrowserService;
import android.support.annotation.NonNull;

import java.util.List;
import java.util.Objects;

import io.realm.Realm;
import io.realm.exceptions.RealmException;
import jp.flg.rmp.MusicProvider.MusicProviderViewCallback;
import jp.flg.rmp.Playback.PlaybackServiceCallback;

public class MusicService extends MediaBrowserService implements
        PlaybackServiceCallback, MusicProviderViewCallback {

    public static final String RMP_SERVICE_VIEW = "jp.flg.rmp.intent.action.RMP_SERVICE_VIEW";

    // The action of the incoming Intent indicating that it contains a command
    // to be executed (see {@link #onStartCommand})
    public static final String ACTION_CMD = "jp.flg.rmp.ACTION_CMD";
    // The key in the extras of the incoming Intent indicating the command that
    // should be executed (see {@link #onStartCommand})
    public static final String CMD_NAME = "CMD_NAME";
    // A value of a CMD_NAME key that indicates that the music playback should switch
    // to local playback from cast playback.
    public static final String CMD_STOP = "CMD_STOP";
    private static final String MEDIA_ID_ROOT = "__ROOT__";
    private static final String TAG = LogHelper.makeLogTag(MusicService.class);
    private MediaSession mSession;
    private Playback mPlayback;
    private MusicProvider musicProvider;
    private MediaNotificationManager mMediaNotificationManager;

    @Override
    public void onCreate() {
        super.onCreate();
        LogHelper.d(TAG, "onCreate");

        Realm.init(this);
        Realm realm;
        try {
            realm = Realm.getDefaultInstance();
        } catch (RealmException ignored) {
            return;
        }

        musicProvider = new MusicProvider(this, realm);
        mPlayback = new Playback(this, musicProvider, this);

        // Start a new MediaSession
        mSession = new MediaSession(this, "MusicService");
        setSessionToken(mSession.getSessionToken());
        mSession.setCallback(mPlayback.getMediaSessionCallback());
        mSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);

        mPlayback.updatePlaybackState(null);

        mMediaNotificationManager = new MediaNotificationManager(this);
    }


    @Override
    public int onStartCommand(Intent startIntent, int flags, int startId) {
        LogHelper.d(TAG, "onStartCommand :", startIntent);

        if (startIntent != null) {
            String action = startIntent.getAction();
            String command = startIntent.getStringExtra(CMD_NAME);
            LogHelper.d(TAG, "onStartCommand : ", action, command);
            if (Objects.equals(ACTION_CMD, action)) {
                if (Objects.equals(CMD_STOP, command)) {
                    mPlayback.handleStopRequest(null);
                }
            }
        }

        return Service.START_STICKY;
    }

    @Override
    public void onDestroy() {
        LogHelper.d(TAG, "onDestroy");
        mPlayback.handleStopRequest(null);
        mMediaNotificationManager.stopNotification();
        mSession.release();
        musicProvider.onStop();
    }

    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid,
                                 Bundle rootHints) {
        LogHelper.d(TAG, "OnGetRoot: clientPackageName=" + clientPackageName,
                "; clientUid=" + clientUid + " ; rootHints=", rootHints);
        musicProvider.intentRmpView();
        return new BrowserRoot(MEDIA_ID_ROOT, null);
    }

    @Override
    public void onLoadChildren(@NonNull String parentMediaId,
                               @NonNull Result<List<MediaItem>> result) {
        LogHelper.d(TAG, "OnLoadChildren", parentMediaId);
    }

    @Override
    public void onPlaybackStart() {
        if (!mSession.isActive()) {
            mSession.setActive(true);
        }
        startForegroundService(new Intent(getApplicationContext(), MusicService.class));
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

    @Override
    public void sendRmpViewBroadcast(Intent broadcastIntent) {
        sendBroadcast(broadcastIntent);
    }
}
