package jp.flg.rmp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.AudioManager.OnAudioFocusChangeListener;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.media.session.MediaSession.Callback;
import android.media.session.PlaybackState;
import android.media.session.PlaybackState.Builder;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Environment;
import android.os.PowerManager;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.view.KeyEvent;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

class Playback implements OnAudioFocusChangeListener,
        OnCompletionListener, OnPreparedListener, OnErrorListener {
    private static final String TAG = LogHelper.makeLogTag(Playback.class);

    // The volume we set the media player to when we lose audio focus, but are
    // allowed to reduce the volume instead of stopping playback.
    private static final float VOLUME_DUCK = 0.2f;
    // The volume we set the media player when we have audio focus.
    private static final float VOLUME_NORMAL = 1.0f;

    // we don't have audio focus, and can't duck (play at a low volume)
    private static final int AUDIO_NO_FOCUS_NO_DUCK = 0;
    // we don't have focus, but can duck (play at a low volume)
    private static final int AUDIO_NO_FOCUS_CAN_DUCK = 1;
    // we have full audio focus
    private static final int AUDIO_FOCUSED = 2;

    private static final long DOUBLE_CLICK = 400L;

    private final Context mContext;
    private final MusicProvider mMusicProvider;
    private final PlaybackServiceCallback mServiceCallback;
    private final MediaSessionCallback mMediaSessionCallback;
    private final AudioManager mAudioManager;
    private final IntentFilter mAudioNoisyIntentFilter =
            new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
    @Nullable
    private MediaPlayer mMediaPlayer;
    private int mState;
    private volatile int mCurrentPosition;
    // Type of audio focus we have:
    private int mAudioFocus = AUDIO_NO_FOCUS_NO_DUCK;
    private boolean mPlayOnFocusGain;
    private final BroadcastReceiver mAudioNoisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Objects.equals(AudioManager.ACTION_AUDIO_BECOMING_NOISY, intent.getAction())) {
                LogHelper.d(TAG, "Headphones disconnected.");
                if (isPlaying()) {
                    Intent newIntent = new Intent(context, MusicService.class);
                    newIntent.setAction(MusicService.ACTION_CMD);
                    newIntent.putExtra(MusicService.CMD_NAME, MusicService.CMD_STOP);
                    mContext.startService(newIntent);
                }
            }
        }
    };
    private volatile boolean mAudioNoisyReceiverRegistered;

    Playback(Context context, MusicProvider musicProvider,
             PlaybackServiceCallback serviceCallback) {
        mContext = context;
        mMusicProvider = musicProvider;
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mState = PlaybackState.STATE_NONE;
        mServiceCallback = serviceCallback;
        mMediaSessionCallback = new MediaSessionCallback();
    }

    private void registerAudioNoisyReceiver() {
        if (!mAudioNoisyReceiverRegistered) {
            mContext.registerReceiver(mAudioNoisyReceiver, mAudioNoisyIntentFilter);
            mAudioNoisyReceiverRegistered = true;
        }
    }

    private void unregisterAudioNoisyReceiver() {
        if (mAudioNoisyReceiverRegistered) {
            mContext.unregisterReceiver(mAudioNoisyReceiver);
            mAudioNoisyReceiverRegistered = false;
        }
    }

    private void tryToGetAudioFocus() {
        LogHelper.d(TAG, "tryToGetAudioFocus");
        if (mAudioFocus != AUDIO_FOCUSED) {
            int result = mAudioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                mAudioFocus = AUDIO_FOCUSED;
            }
        }
    }

    private void giveUpAudioFocus() {
        LogHelper.d(TAG, "giveUpAudioFocus");
        if (mAudioFocus == AUDIO_FOCUSED) {
            if (mAudioManager.abandonAudioFocus(this) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                mAudioFocus = AUDIO_NO_FOCUS_NO_DUCK;
            }
        }
    }

    Callback getMediaSessionCallback() {
        return mMediaSessionCallback;
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        LogHelper.d(TAG, "onAudioFocusChange. focusChange=", focusChange);
        if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
            // We have gained focus:
            mAudioFocus = AUDIO_FOCUSED;

        } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            // We have lost focus. If we can duck (low playback volume), we can keep playing.
            // Otherwise, we need to pause the playback.
            boolean canDuck = focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK;
            mAudioFocus = canDuck ? AUDIO_NO_FOCUS_CAN_DUCK : AUDIO_NO_FOCUS_NO_DUCK;

            // If we are playing, we need to reset media player by calling configMediaPlayerState
            // with mAudioFocus properly set.
            if (mState == PlaybackState.STATE_PLAYING && !canDuck) {
                // If we don't have audio focus and can't duck, we save the information that
                // we were playing, so that we can resume playback once we get the focus back.
                mPlayOnFocusGain = true;
            }
        } else {
            LogHelper.e(TAG, "onAudioFocusChange: Ignoring unsupported focusChange: ", focusChange);
        }
        configMediaPlayerState();
    }

    @Override
    public void onPrepared(MediaPlayer player) {
        LogHelper.d(TAG, "onPrepared from MediaPlayer");
        configMediaPlayerState();
    }

    @Override
    public void onCompletion(MediaPlayer player) {
        LogHelper.d(TAG, "onCompletion from MediaPlayer");
        mMusicProvider.handleCompletion();
        handlePlayRequest();
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        LogHelper.e(TAG, "Media player error: what=" + what + ", extra=" + extra);
        updatePlaybackState("MediaPlayer error " + what + " (" + extra + ")");
        return true; // true indicates we handled the error
    }

    private void handlePauseRequest() {
        LogHelper.d(TAG, "handlePauseRequest: mState=" + mState);
        if (isPlaying()) {
            if (mState == PlaybackState.STATE_PLAYING) {
                // Pause media player and cancel the 'foreground service' state.
                if (mMediaPlayer != null && mMediaPlayer.isPlaying()) {
                    mMediaPlayer.pause();
                    mCurrentPosition = mMediaPlayer.getCurrentPosition();
                }
                giveUpAudioFocus();
            }
            mState = PlaybackState.STATE_PAUSED;
            updatePlaybackState(null);
            unregisterAudioNoisyReceiver();
            mServiceCallback.onPlaybackStop();
        }
    }

    void handleStopRequest(String withError) {
        LogHelper.d(TAG, "handleStopRequest: mState=" + mState + " error=", withError);

        mState = PlaybackState.STATE_STOPPED;
        updatePlaybackState(null);
        // Give up Audio focus
        giveUpAudioFocus();
        unregisterAudioNoisyReceiver();
        // Relax all resources
        // stop and release the Media Player, if it's available
        if (mMediaPlayer != null) {
            mMediaPlayer.reset();
            mMediaPlayer.release();
            mMediaPlayer = null;
        }

        mServiceCallback.onPlaybackStop();
        updatePlaybackState(withError);
    }

    void updatePlaybackState(CharSequence error) {
        LogHelper.d(TAG, "updatePlaybackState, playback state=" + mState);
        int position = mMediaPlayer != null ? mMediaPlayer.getCurrentPosition() : mCurrentPosition;
        long actions =
                PlaybackState.ACTION_PLAY |
                        PlaybackState.ACTION_PLAY_FROM_SEARCH |
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS |
                        PlaybackState.ACTION_SKIP_TO_NEXT;
        if (isPlaying()) {
            actions |= PlaybackState.ACTION_PAUSE;
        }

        Builder stateBuilder = new Builder().setActions(actions);

        if (error != null) {
            stateBuilder.setErrorMessage(error);
            mState = PlaybackState.STATE_ERROR;
        }
        stateBuilder.setState(mState, (long) position, 1.0f, SystemClock.elapsedRealtime());

        mServiceCallback.onPlaybackStateUpdated(stateBuilder.build());

        if (mState == PlaybackState.STATE_PLAYING ||
                mState == PlaybackState.STATE_PAUSED) {
            mServiceCallback.onNotificationRequired();
        }
    }

    private boolean isPlaying() {
        return mPlayOnFocusGain || mMediaPlayer != null && mMediaPlayer.isPlaying();
    }

    private void handlePlayRequest() {
        LogHelper.d(TAG, "handlePlayRequest: mState=" + mState);
        mServiceCallback.onPlaybackStart();
        mPlayOnFocusGain = true;
        tryToGetAudioFocus();
        registerAudioNoisyReceiver();

        if (mState == PlaybackState.STATE_PAUSED && mMediaPlayer != null) {
            configMediaPlayerState();
        } else {
            MediaMetadata track = mMusicProvider.getNowMusic();
            if (track == null) {
                handleStopRequest("No nowMusic");
                return;
            }

            String source = Environment.getExternalStorageDirectory()
                    + "/rmp/"
                    + track.getString(MusicProvider.CUSTOM_METADATA_TRACK_SOURCE);
            LogHelper.d(TAG, "Music source: " + source);

            Uri sourceUri = Uri.fromFile(new File(source));

            mState = PlaybackState.STATE_STOPPED;
            mServiceCallback.onMetadataChanged(track);
            mCurrentPosition = 0;

            try {
                if (mMediaPlayer == null) {
                    mMediaPlayer = new MediaPlayer();

                    // Make sure the media player will acquire a wake-lock while
                    // playing. If we don't do that, the CPU might go to sleep while the
                    // song is playing, causing playback to stop.
                    mMediaPlayer.setWakeMode(mContext.getApplicationContext(),
                            PowerManager.PARTIAL_WAKE_LOCK);

                    // we want the media player to notify us when it's ready preparing,
                    // and when it's done playing:
                    mMediaPlayer.setOnPreparedListener(this);
                    mMediaPlayer.setOnCompletionListener(this);
                    mMediaPlayer.setOnErrorListener(this);
                } else {
                    mMediaPlayer.reset();
                }

                mState = PlaybackState.STATE_BUFFERING;
                mMediaPlayer.setDataSource(mContext.getApplicationContext(), sourceUri);
                mMediaPlayer.prepareAsync();
                updatePlaybackState(null);

            } catch (IOException ex) {
                LogHelper.e(TAG, ex, "Exception playing song");
                updatePlaybackState(ex.getMessage());
            }
        }
    }

    private void configMediaPlayerState() {
        LogHelper.d(TAG, "configMediaPlayerState. mAudioFocus=", mAudioFocus);
        if (mAudioFocus == AUDIO_NO_FOCUS_NO_DUCK) {
            // If we don't have audio focus and can't duck, we have to pause,
            if (mState == PlaybackState.STATE_PLAYING) {
                handlePauseRequest();
            }
        } else {  // we have audio focus:
            if (mMediaPlayer != null) {
                if (mAudioFocus == AUDIO_NO_FOCUS_CAN_DUCK) {
                    mMediaPlayer.setVolume(VOLUME_DUCK, VOLUME_DUCK); // we'll be relatively quiet
                } else {
                    mMediaPlayer.setVolume(VOLUME_NORMAL, VOLUME_NORMAL); // we can be loud again
                } // else do something for remote client.
            }
            // If we were playing when we lost focus, we need to resume playing.
            if (mPlayOnFocusGain) {
                if (mMediaPlayer != null && !mMediaPlayer.isPlaying()) {
                    LogHelper.d(TAG, "configMediaPlayerState startMediaPlayer. seeking to ",
                            mCurrentPosition);
                    mMediaPlayer.start();
                    mState = PlaybackState.STATE_PLAYING;
                }
                mPlayOnFocusGain = false;
            }
        }
        updatePlaybackState(null);
    }

    interface PlaybackServiceCallback {
        void onPlaybackStart();

        void onNotificationRequired();

        void onPlaybackStop();

        void onPlaybackStateUpdated(PlaybackState newState);

        void onMetadataChanged(MediaMetadata metadata);
    }

    private class MediaSessionCallback extends Callback {
        private long lastClickTime;
        private int numClicks;

        @Override
        public boolean onMediaButtonEvent(@NonNull Intent mediaButtonIntent) {
            KeyEvent event = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            LogHelper.d(TAG, "onMediaButtonEvent called: " + event);
            if (event.getKeyCode() == KeyEvent.KEYCODE_HEADSETHOOK &&
                    event.getAction() == KeyEvent.ACTION_DOWN) {
                long eventTime = event.getEventTime();
                LogHelper.d(TAG, "eventTime - mLastClickTime = "
                        + (eventTime - lastClickTime));
                if (eventTime - lastClickTime < DOUBLE_CLICK) {
                    numClicks++;
                }
                lastClickTime = eventTime;
            }

            final int oldNumClicks = numClicks;
            CountDownTimer checkIfDone = new CountDownTimer(DOUBLE_CLICK, 10L) {
                @Override
                public void onTick(long millisUntilFinished) {
                    if (oldNumClicks != numClicks) {
                        cancel();
                    }
                }

                @Override
                public void onFinish() {
                    if (oldNumClicks == numClicks) {
                        LogHelper.d(TAG, "numClicks = " + numClicks);
                        if (numClicks == 1) {
                            LogHelper.d(TAG, "skip");
                            mState = PlaybackState.STATE_STOPPED;
                            onSkipToNext();

                        } else if (numClicks >= 2) {
                            LogHelper.d(TAG, "previous");
                            mState = PlaybackState.STATE_STOPPED;
                            onSkipToPrevious();

                        }
                        numClicks = 0;
                        lastClickTime = 0L;
                    }
                }
            }.start();

            return numClicks != 0 || super.onMediaButtonEvent(mediaButtonIntent);
        }


        @Override
        public void onPlayFromSearch(String query, Bundle extras) {
            LogHelper.d(TAG, "onPlayFromUri");
            handleStopRequest(null);
            mMusicProvider.getRmpData();
            handlePlayRequest();
        }

        @Override
        public void onPlay() {
            LogHelper.d(TAG, "onPlay");
            handlePlayRequest();
        }

        @Override
        public void onPause() {
            LogHelper.d(TAG, "onPause. current state=" + mState);
            handlePauseRequest();
        }

        @Override
        public void onStop() {
            LogHelper.d(TAG, "onStop. current state=" + mState);
            handleStopRequest(null);
        }

        @Override
        public void onSkipToNext() {
            LogHelper.d(TAG, "onSkipToNext");
            mMusicProvider.handleSkipToNext();
            handlePlayRequest();
        }

        @Override
        public void onSkipToPrevious() {
            LogHelper.d(TAG, "onSkipToPrevious");
            mMusicProvider.handleSkipToPrevious();
            handlePlayRequest();
        }

    }
}
