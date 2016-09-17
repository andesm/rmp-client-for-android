package jp.flg.rmp;

import android.app.Activity;
import android.content.ComponentName;
import android.media.browse.MediaBrowser;
import android.media.browse.MediaBrowser.ConnectionCallback;
import android.media.session.MediaController;
import android.media.session.MediaSession.Token;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;

import jp.flg.rmp.R.id;
import jp.flg.rmp.R.layout;

public class MainActivity extends Activity {
    private static final String TAG = LogHelper.makeLogTag(MainActivity.class);

    private MediaBrowser mMediaBrowser;
    private final ConnectionCallback mConnectionCallback =
            new ConnectionCallback() {
                @Override
                public void onConnected() {
                    LogHelper.d(TAG, "onConnected");
                    connectToSession(mMediaBrowser.getSessionToken());
                }
            };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LogHelper.d(TAG, "Activity onCreate");

        mMediaBrowser = new MediaBrowser(this,
                new ComponentName(this, MusicService.class),
                mConnectionCallback, null);

        setContentView(layout.activity_player);

        Button randomPlayButton = (Button) findViewById(id.random_play);
        randomPlayButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                LogHelper.d(TAG, "Random play");
                getMediaController().getTransportControls().playFromSearch("", null);
            }
        });

        Button stopButton = (Button) findViewById(id.stop);
        stopButton.setOnClickListener(new OnClickListener() {
            @Override
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

    private void connectToSession(Token token) {
        MediaController mediaController = new MediaController(this, token);
        setMediaController(mediaController);
    }
}
