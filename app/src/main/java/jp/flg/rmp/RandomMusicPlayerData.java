package jp.flg.rmp;


import android.content.Intent;
import android.media.MediaMetadata;
import android.media.MediaMetadata.Builder;

import com.google.gson.annotations.Expose;

import io.realm.RealmObject;
import io.realm.annotations.PrimaryKey;

import static jp.flg.rmp.MusicProvider.ALBUM_VIEW_STRING;
import static jp.flg.rmp.MusicProvider.ARTIST_VIEW_STRING;
import static jp.flg.rmp.MusicProvider.COUNT_VIEW_STRING;
import static jp.flg.rmp.MusicProvider.REPEAT_VIEW_STRING;
import static jp.flg.rmp.MusicProvider.SCORE_VIEW_STRING;
import static jp.flg.rmp.MusicProvider.SKIP_VIEW_STRING;
import static jp.flg.rmp.MusicProvider.TITLE_VIEW_STRING;

public class RandomMusicPlayerData extends RealmObject {

    @Expose
    @PrimaryKey
    private int id;
    @Expose
    private String owner;
    @Expose
    private String site;
    @Expose
    private String file;
    @Expose
    private String source;
    @Expose
    private String image;

    @Expose
    private String genre;
    @Expose
    private String album;
    @Expose
    private String artist;
    @Expose
    private String title;

    @Expose
    private long duration;
    @Expose
    private long trackNumber;
    @Expose
    private long totalTrackCount;

    @Expose
    private int now;
    @Expose
    private int count;
    @Expose
    private int skip;
    @Expose
    private int repeat;
    @Expose
    private int score;

    boolean isPut(RandomMusicPlayerData rmpData) {
        return rmpData.count < count || rmpData.skip < skip;
    }

    int getId() {
        return id;
    }

    int getNow() {
        return now;
    }

    int getScore() {
        return score;
    }

    String getFile() {
        return file;
    }

    String getStringId() {
        return String.valueOf(id);
    }

    void setBroadcastIntent(Intent broadcastIntent) {
        broadcastIntent.putExtra(ARTIST_VIEW_STRING, artist);
        broadcastIntent.putExtra(ALBUM_VIEW_STRING, album);
        broadcastIntent.putExtra(TITLE_VIEW_STRING, title);
        broadcastIntent.putExtra(SCORE_VIEW_STRING, String.valueOf(score));
        broadcastIntent.putExtra(SKIP_VIEW_STRING, String.valueOf(skip));
        broadcastIntent.putExtra(COUNT_VIEW_STRING, String.valueOf(count));
        broadcastIntent.putExtra(REPEAT_VIEW_STRING, String.valueOf(repeat));
    }

    boolean isPlay() {
        if (now == 0) {
            return true;
        } else {
            now--;
            return false;
        }
    }

    void handleSkipToNext() {
        now += skip + 1;
        double d = (1.0 + Math.sqrt(1.0 + 8.0 * (double) skip)) / 2.0 + 1.0;
        skip = (int) ((d - 1.0) * d / 2.0);
        if (0 < repeat) {
            repeat--;
        }
        score = count + repeat - (now + skip);
    }

    void handleSkipToPrevious() {
        repeat++;
        score = count + repeat - (now + skip);
    }

    void handleCompletion() {
        now++;
        count++;
        skip /= 2;
        score = count + repeat - (now + skip);
    }

    MediaMetadata toMediaMetadata() {
        return new Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, getStringId())
                .putString(MusicProvider.CUSTOM_METADATA_TRACK_SOURCE, file)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
                .putString(MediaMetadata.METADATA_KEY_GENRE, genre)
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, trackNumber)
                .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, totalTrackCount)
                .build();
    }


}

