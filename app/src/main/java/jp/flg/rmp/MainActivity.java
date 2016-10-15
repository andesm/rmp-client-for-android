package jp.flg.rmp;

import android.app.Activity;
import android.content.ComponentName;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession.Token;
import android.os.Bundle;

import butterknife.ButterKnife;
import butterknife.OnClick;
import io.realm.Realm;
import jp.flg.rmp.R.id;

public class MainActivity extends Activity {
    private static final String TAG = LogHelper.makeLogTag(MainActivity.class);

    private MediaBrowser mMediaBrowser;
    private final MediaBrowser.ConnectionCallback mConnectionCallback =
            new MediaBrowser.ConnectionCallback() {
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

        Realm.init(this);

        mMediaBrowser = new MediaBrowser(this,
                new ComponentName(this, MusicService.class),
                mConnectionCallback, null);

        setContentView(R.layout.activity_player);

        ButterKnife.bind(this);
    }

    @OnClick(id.random_play)
    public void clickRandomPlay() {
        LogHelper.d(TAG, "Random play");
        getMediaController().getTransportControls().playFromSearch("", null);
    }

    @OnClick(id.stop)
    public void clickStop() {
        LogHelper.d(TAG, "stop");
        getMediaController().getTransportControls().stop();
    }

    @Override
    protected void onStart() {
        LogHelper.d(TAG, "Activity onStart");
        super.onStart();
        mMediaBrowser.connect();
    }

    @Override
    protected void onStop() {
        LogHelper.d(TAG, "Activity onStop");
        super.onStop();
        mMediaBrowser.disconnect();
    }

    private void connectToSession(Token token) {
        MediaController mediaController = new MediaController(this, token);
        setMediaController(mediaController);
    }

}
