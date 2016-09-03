package jp.flg.rmp;

import android.app.Activity;
import android.content.ComponentName;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.os.RemoteException;
import android.view.View;
import android.widget.Button;

public class MainActivity extends Activity {
    private static final String TAG = LogHelper.makeLogTag(MainActivity.class);

    private MediaBrowser mMediaBrowser;
    private final MediaBrowser.ConnectionCallback mConnectionCallback =
            new MediaBrowser.ConnectionCallback() {
                @Override
                public void onConnected() {
                    LogHelper.d(TAG, "onConnected");
                    try {
                        connectToSession(mMediaBrowser.getSessionToken());
                    } catch (RemoteException e) {
                        LogHelper.e(TAG, e, "could not connect media controller");
                    }
                }
            };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LogHelper.d(TAG, "Activity onCreate");

        mMediaBrowser = new MediaBrowser(this, new ComponentName(this, MusicService.class), mConnectionCallback, null);

        setContentView(R.layout.activity_player);

        final Button random_play_button = (Button) findViewById(R.id.random_play);
        random_play_button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                LogHelper.d(TAG, "Random play");
                getMediaController().getTransportControls().playFromSearch("", null);
            }
        });

        final Button stop_button = (Button) findViewById(R.id.stop);
        stop_button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                LogHelper.d(TAG, "stop");
                getMediaController().getTransportControls().stop();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        LogHelper.d(TAG, "Activity onStart");
        mMediaBrowser.connect();
    }

    @Override
    protected void onStop() {
        super.onStop();
        LogHelper.d(TAG, "Activity onStop");
        mMediaBrowser.disconnect();
    }

    private void connectToSession(MediaSession.Token token) throws RemoteException {
        MediaController mediaController = new MediaController(this, token);
        setMediaController(mediaController);
    }
}
