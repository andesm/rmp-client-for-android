package jp.flg.rmp;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession.Token;
import android.os.Bundle;
import android.widget.TextView;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import io.realm.Realm;
import jp.flg.rmp.R.id;

public class MainActivity extends Activity {
    public static final String RMP_ACTIVITY_VIEW = "jp.flg.rmp.intent.action.RMP_ACTIVITY_VIEW";
    private static final String TAG = LogHelper.makeLogTag(MainActivity.class);
    @BindView(R.id.next)
    TextView nextView;
    @BindView(R.id.all)
    TextView allView;
    @BindView(R.id.rest_error)
    TextView restSucessView;
    @BindView(R.id.rest_sucess)
    TextView restErrorView;
    @BindView(R.id.artist)
    TextView artistView;
    @BindView(R.id.album)
    TextView alubmView;
    @BindView(R.id.title)
    TextView titleView;
    @BindView(R.id.ranking)
    TextView rankingView;
    @BindView(R.id.score)
    TextView scoreView;
    @BindView(R.id.skip)
    TextView skipView;
    @BindView(R.id.count)
    TextView countView;
    @BindView(R.id.repeat)
    TextView repeatView;

    private IntentFilter filter;
    private RmpViewReceiver receiver;

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

        Intent broadcastIntent = new Intent();
        broadcastIntent.setAction(MusicService.RMP_SERVICE_VIEW);
        broadcastIntent.addCategory(Intent.CATEGORY_DEFAULT);
        sendBroadcast(broadcastIntent);
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
        filter = new IntentFilter(RMP_ACTIVITY_VIEW);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        receiver = new RmpViewReceiver();
        registerReceiver(receiver, filter);

    }

    @Override
    protected void onStop() {
        LogHelper.d(TAG, "Activity onStop");
        super.onStop();
        mMediaBrowser.disconnect();
        unregisterReceiver(receiver);
    }

    private void connectToSession(Token token) {
        MediaController mediaController = new MediaController(this, token);
        setMediaController(mediaController);
    }

    private void setRmpView(Intent intent) {
        nextView.setText(intent.getStringExtra(MusicProvider.NEXT_VIEW_STRING));
        allView.setText(intent.getStringExtra(MusicProvider.ALL_VIEW_STRING));
        restSucessView.setText(intent.getStringExtra(MusicProvider.REST_SUCCESS_VIEW_STRING));
        restErrorView.setText(intent.getStringExtra(MusicProvider.REST_ERROR_VIEW_STRING));
        artistView.setText(intent.getStringExtra(MusicProvider.ARTIST_VIEW_STRING));
        alubmView.setText(intent.getStringExtra(MusicProvider.ALBUM_VIEW_STRING));
        titleView.setText(intent.getStringExtra(MusicProvider.TITLE_VIEW_STRING));
        rankingView.setText(intent.getStringExtra(MusicProvider.RANKING_VIEW_STRING));
        scoreView.setText(intent.getStringExtra(MusicProvider.SCORE_VIEW_STRING));
        skipView.setText(intent.getStringExtra(MusicProvider.SKIP_VIEW_STRING));
        countView.setText(intent.getStringExtra(MusicProvider.COUNT_VIEW_STRING));
        repeatView.setText(intent.getStringExtra(MusicProvider.REPEAT_VIEW_STRING));
    }

    public class RmpViewReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            setRmpView(intent);
        }
    }
}
