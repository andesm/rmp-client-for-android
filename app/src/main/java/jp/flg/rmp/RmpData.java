package jp.flg.rmp;


import android.media.MediaMetadata;

import io.realm.RealmObject;
import io.realm.annotations.PrimaryKey;

public class RmpData extends RealmObject {
    private static final String TAG = LogHelper.makeLogTag(RmpData.class);

    @PrimaryKey
    private int id;
    private String owner;
    private String site;
    private String file;
    private String source;
    private String image;

    private String genre;
    private String album;
    private String artist;
    private String title;

    private int duration;
    private int trackNumber;
    private int totalTrackCount;

    private int now;
    private int count;
    private int skip;
    private int repeat;
    private int score;

     public void updateRmpData(RmpData rmpData) {
        owner = rmpData.owner;
        site = rmpData.site;
        file = rmpData.file;
        source = rmpData.source;
        image = rmpData.image;

        genre = rmpData.genre;
        album = rmpData.album;
        artist = rmpData.artist;
        title = rmpData.title;

        duration = rmpData.duration;
        trackNumber = rmpData.trackNumber;
        totalTrackCount = rmpData.totalTrackCount;

        now = rmpData.now;
        count = rmpData.count;
        skip = rmpData.skip;
        repeat = rmpData.repeat;
        score = rmpData.score;
    }

    int getId() {
        return id;
    }

    public String getStringId() {
        return String.valueOf(id);
    }

    public boolean isPlay() {
        if (now == 0) {
            return true;
        } else {
            now--;
            return false;
        }
    }

    public void handleSkipToNext() {
        now += skip + 1;
        double n = (1 + Math.sqrt(1 + 8 * skip)) / 2 + 1;
        skip = (int) (((n - 1) * n) / 2);
        if (0 < repeat)
            repeat--;
        score = count + repeat - (now + skip);
    }

    public void handleSkipToPrevious() {
        repeat++;
        score = count + repeat - (now + skip);
    }

    public void handleCompletion() {
        now++;
        count++;
        skip = skip / 2;
        score = count + repeat - (now + skip);
    }

    public MediaMetadata toMediaMetadata() {
        return new MediaMetadata.Builder()
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

