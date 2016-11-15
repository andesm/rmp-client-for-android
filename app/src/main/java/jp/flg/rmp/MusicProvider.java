package jp.flg.rmp;

import android.content.Intent;
import android.media.MediaMetadata;
import android.os.Environment;
import android.support.annotation.Nullable;
import android.util.SparseBooleanArray;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import io.realm.Realm;
import io.realm.RealmAsyncTask;
import io.realm.RealmResults;
import okhttp3.JavaNetCookieJar;
import okhttp3.OkHttpClient;
import okhttp3.ResponseBody;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.Retrofit.Builder;
import retrofit2.adapter.rxjava.RxJavaCallAdapterFactory;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.http.Body;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Path;
import rx.Observable;
import rx.Observer;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;

class MusicProvider {
    public static final int SHUFFLE_PLAY_STYLE = 0;
    public static final int SORT_PLAY_STYLE = 1;
    static final String CUSTOM_METADATA_TRACK_SOURCE = "__SOURCE__";
    static final String NEXT_VIEW_STRING = "next_view";
    static final String ALL_VIEW_STRING = "all_view";
    static final String REST_ERROR_VIEW_STRING = "rest_error_view";
    static final String REST_SUCCESS_VIEW_STRING = "rest_success_view";
    static final String ARTIST_VIEW_STRING = "artist_view";
    static final String ALBUM_VIEW_STRING = "album_view";
    static final String TITLE_VIEW_STRING = "title_view";
    static final String RANKING_VIEW_STRING = "ranking_view";
    static final String SCORE_VIEW_STRING = "score_view";
    static final String SKIP_VIEW_STRING = "skip_view";
    static final String COUNT_VIEW_STRING = "count_view";
    static final String REPEAT_VIEW_STRING = "repeat_view";
    private static final String LOG_TAG = LogHelper.makeLogTag(MusicProvider.class);
    private static final String BASE_URL = "http://flg.jp:10080/";
    private final List<RandomMusicPlayerData> rmpDataShuffleList = new ArrayList<>();
    private final List<RandomMusicPlayerData> rmpDataSortList = new ArrayList<>();
    private final CookieManager cookieManager = new CookieManager();
    private final Retrofit retrofit;
    private final Realm realm;
    private final MusicProviderViewCallback callback;
    private int playStyle;
    private String csrfToken;
    @Nullable
    private Iterator<RandomMusicPlayerData> rmpDataIterator;
    @Nullable
    private RandomMusicPlayerData nowMusic;
    private RealmAsyncTask transaction;
    private int restError;
    private int restSuccess;
    private final Observer<List<ResponseBody>> restListCallback = new Observer<List<ResponseBody>>() {
        @Override
        public void onCompleted() {
        }

        @Override
        public void onError(Throwable e) {
            LogHelper.d(LOG_TAG, e);
            restError++;
        }

        @Override
        public void onNext(List<ResponseBody> bodys) {
            restSuccess++;
        }
    };
    private final Observer<ResponseBody> restCallback = new Observer<ResponseBody>() {
        @Override
        public void onCompleted() {
        }

        @Override
        public void onError(Throwable e) {
            LogHelper.d(LOG_TAG, e);
            restError++;
        }

        @Override
        public void onNext(ResponseBody body) {
            restSuccess++;
        }
    };
    private boolean logined;

    MusicProvider(MusicProviderViewCallback callback, Realm realm) {
        this.callback = callback;
        this.realm = realm;

        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor();
        //loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
        loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.HEADERS);

        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);

        OkHttpClient okHttpclient = new OkHttpClient.Builder()
                .addInterceptor(loggingInterceptor)
                //.addNetworkInterceptor(new StethoInterceptor())
                .cookieJar(new JavaNetCookieJar(cookieManager))
                .build();

        Gson gson = new GsonBuilder()
                .excludeFieldsWithoutExposeAnnotation()
                .setLenient()
                .create();

        retrofit = new Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .addCallAdapterFactory(RxJavaCallAdapterFactory.create())
                .client(okHttpclient)
                .build();

        setRmpDataFromRealm();
        intentRmpView();
        getRmpDataAsync();
    }

    void onStop() {
        if (transaction != null && !transaction.isCancelled()) {
            transaction.cancel();
        }
    }

    void initRmpData(int playStyle) {
        this.playStyle = playStyle;
        setRmpDataFromRealm();
        setNextNowMusic();
        getRmpDataAsync();
    }

    @Nullable
    MediaMetadata getNowMusic() {
        return nowMusic == null ? null : nowMusic.toMediaMetadata();
    }

    void handleCompletion() {
        if (nowMusic != null) {
            realm.beginTransaction();
            nowMusic.handleCompletion();
            realm.commitTransaction();
            putRmpData(nowMusic);
        }
        setNextNowMusic();
    }

    void handleSkipToNext() {
        if (nowMusic != null) {
            realm.beginTransaction();
            nowMusic.handleSkipToNext();
            realm.commitTransaction();
            putRmpData(nowMusic);
        }
        setNextNowMusic();
    }

    void handleSkipToPrevious() {
        if (nowMusic != null) {
            realm.beginTransaction();
            nowMusic.handleSkipToPrevious();
            realm.commitTransaction();
            putRmpData(nowMusic);
        }
    }

    private void setNextNowMusic() {
        if (rmpDataShuffleList.isEmpty()) {
            nowMusic = null;
            return;
        }

        int size = rmpDataShuffleList.size();
        for (int count = 0; count < size; count++) {
            if (!rmpDataIterator.hasNext()) {
                setRmpDataIterator();
            }
            nowMusic = rmpDataIterator.next();
            if (nowMusic == null) {
                return;
            }
            String source = Environment.getExternalStorageDirectory()
                    + "/rmp/"
                    + nowMusic.getFile();
            File file = new File(source);
            realm.beginTransaction();
            boolean isPlay = nowMusic.isPlay();
            realm.commitTransaction();
            if (file.exists() && isPlay) {
                intentRmpView();
                return;
            }
        }
    }

    void intentRmpView() {
        Collections.sort(rmpDataSortList, new RandomMusicPlayerDataComparator());
        int all = 0;
        int next = 0;
        for (RandomMusicPlayerData rmp : rmpDataSortList) {
            all += 1;
            rmp.setRanking(all);
            if (rmp.getNow() == 0) {
                next += 1;
            }
        }
        Intent broadcastIntent = new Intent();
        broadcastIntent.setAction(MainActivity.RMP_ACTIVITY_VIEW);
        broadcastIntent.addCategory(Intent.CATEGORY_DEFAULT);
        broadcastIntent.putExtra(NEXT_VIEW_STRING, String.valueOf(next));
        broadcastIntent.putExtra(ALL_VIEW_STRING, String.valueOf(all));
        broadcastIntent.putExtra(REST_ERROR_VIEW_STRING, String.valueOf(restSuccess));
        broadcastIntent.putExtra(REST_SUCCESS_VIEW_STRING, String.valueOf(restError));
        if (nowMusic != null) {
            nowMusic.setBroadcastIntent(broadcastIntent);
        }
        callback.sendRmpViewBroadcast(broadcastIntent);
    }

    private void getRmpDataAsync() {
        retrofit.create(RandomMusicPlayerRESTfulApi.class).getLogin()
                .flatMap(ResponseBody -> {
                    setCsrfToken();
                    return retrofit.create(RandomMusicPlayerRESTfulApi.class)
                            .postLogin("/apps/rmp/", csrfToken,
                                    "admin", "djangoadmin", "Log in");
                })
                .flatMap(ResponseBody -> {
                    setCsrfToken();
                    return retrofit.create(RandomMusicPlayerRESTfulApi.class).get(csrfToken);
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread()).
                subscribe(new Observer<List<RandomMusicPlayerData>>() {
                    @Override
                    public void onCompleted() {
                    }

                    @Override
                    public void onError(Throwable e) {
                        LogHelper.d(LOG_TAG, "getRmpDataAsync() : ", e);
                        logined = false;
                    }

                    @Override
                    public void onNext(List<RandomMusicPlayerData> rmpDataListResult) {
                        LogHelper.d(LOG_TAG, "Getting from REST completed");
                        logined = true;
                        if (rmpDataListResult.isEmpty()) {
                            LogHelper.d(LOG_TAG, "DB empty! Post RmpData to the Server");
                            RealmResults<RandomMusicPlayerData> rmpDataRealm =
                                    realm.where(RandomMusicPlayerData.class).findAll();
                            postRmpData(rmpDataRealm);
                        } else {
                            updateDatabaseAsync(rmpDataListResult);
                        }

                    }
                });
    }

    private void updateDatabaseAsync(Iterable<RandomMusicPlayerData> rmpDataListResult) {

        transaction = realm.executeTransactionAsync(bgRealm -> {
            LogHelper.d(LOG_TAG, "Storing to Database start");
            SparseBooleanArray id = new SparseBooleanArray();
            for (RandomMusicPlayerData rmpRestData : rmpDataListResult) {
                //LogHelper.d(LOG_TAG, "id : " + rmpRestData.getId());
                id.put(rmpRestData.getId(), true);
                RandomMusicPlayerData rmpRealmData = bgRealm
                        .where(RandomMusicPlayerData.class)
                        .equalTo("id", rmpRestData.getId()).findFirst();
                if (rmpRealmData == null) {
                    RandomMusicPlayerData createdRmpData =
                            bgRealm.copyToRealm(rmpRestData);
                } else if (!rmpRestData.getFile().equals(rmpRealmData.getFile())) {
                    LogHelper.d(LOG_TAG, "!! Rmp Data mismatch : " +
                            rmpRestData.getId() + ", " +
                            rmpRestData.getFile() + " : " +
                            rmpRealmData.getId() + ", " +
                            rmpRealmData.getFile());
                    rmpRealmData.deleteFromRealm();
                    RandomMusicPlayerData createdRmpData =
                            bgRealm.copyToRealm(rmpRestData);
                } else {
                    rmpRealmData.updateRmpData(rmpRestData);
                }
            }
            LogHelper.d(LOG_TAG, "Storing to Database completed");

            RealmResults<RandomMusicPlayerData> rmpDataRealm =
                    bgRealm.where(RandomMusicPlayerData.class).findAll();
            rmpDataRealm.stream()
                    .filter(rmpData -> !id.get(rmpData.getId()))
                    .forEach(rmpData -> {
                        LogHelper.d(LOG_TAG, "!! Deleted Rmp Data : " +
                                rmpData.getId());
                        rmpData.deleteFromRealm();
                    });
        }, () -> {
            setRmpDataFromRealm();
            intentRmpView();
            putRmpData();
            LogHelper.d(LOG_TAG, "Realm Transaction completed");
        }, error -> LogHelper.d(LOG_TAG, "updateDatabaseAsync() : ", error));

    }

    private void postRmpData(List<RandomMusicPlayerData> rmpDataListLocal) {
        Collection<Observable<ResponseBody>> observables =
                rmpDataListLocal.stream()
                        .map(rmpData -> retrofit.create(RandomMusicPlayerRESTfulApi.class)
                                .post(csrfToken, realm.copyFromRealm(rmpData)))
                        .collect(Collectors.toCollection(ArrayList::new));

        Observable.merge(observables)
                .toList()
                .single()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(restListCallback);
    }

    private void putRmpData() {
        if (!logined) {
            getRmpDataAsync();
            restError++;
            return;
        }

        Collection<Observable<ResponseBody>> observables =
                rmpDataShuffleList.stream().filter(RandomMusicPlayerData::isUpdate)
                        .map(rmpData -> retrofit.create(RandomMusicPlayerRESTfulApi.class)
                                .put(csrfToken, rmpData.getStringId(), realm.copyFromRealm(rmpData)))
                        .collect(Collectors.toCollection(ArrayList::new));

        Observable.merge(observables)
                .toList()
                .single()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(restListCallback);
    }

    private void putRmpData(RandomMusicPlayerData rmpData) {
        if (!logined) {
            getRmpDataAsync();
            restError++;
            return;
        }

        retrofit.create(RandomMusicPlayerRESTfulApi.class)
                .put(csrfToken, rmpData.getStringId(), realm.copyFromRealm(rmpData))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(restCallback);
    }

    private void setCsrfToken() {
        cookieManager.getCookieStore().getCookies().stream().
                filter(cookie -> Objects.equals("csrftoken", cookie.getName())).
                forEach(cookie -> {
                    csrfToken = cookie.getValue();
                });
    }

    private void setRmpDataFromRealm() {
        RealmResults<RandomMusicPlayerData> rmpDataRealm =
                realm.where(RandomMusicPlayerData.class).findAll();
        rmpDataShuffleList.clear();
        rmpDataSortList.clear();
        rmpDataShuffleList.addAll(rmpDataRealm);
        rmpDataSortList.addAll(rmpDataRealm);
        setRmpDataIterator();
    }

    private void setRmpDataIterator() {
        if (playStyle == SHUFFLE_PLAY_STYLE) {
            Collections.shuffle(rmpDataShuffleList);
            rmpDataIterator = rmpDataShuffleList.iterator();

        } else if (playStyle == SORT_PLAY_STYLE) {
            Collections.sort(rmpDataSortList, new RandomMusicPlayerDataComparator());
            rmpDataIterator = rmpDataSortList.iterator();

        } else {
            rmpDataIterator = null;
        }
    }

    @FunctionalInterface
    interface MusicProviderViewCallback {
        void sendRmpViewBroadcast(Intent broadcastIntent);
    }

    private interface RandomMusicPlayerRESTfulApi {
        @GET("/apps/rmp/music/")
        Observable<List<RandomMusicPlayerData>> get(@Header("X-CSRFToken") String csrf);

        @PUT("/apps/rmp/music/{id}/")
        Observable<ResponseBody> put(@Header("X-CSRFToken") String csrf,
                                     @Path("id") String id,
                                     @Body RandomMusicPlayerData rmpData);

        @POST("/apps/rmp/music/")
        Observable<ResponseBody> post(@Header("X-CSRFToken") String csrf,
                                      @Body RandomMusicPlayerData rmpData);

        @FormUrlEncoded
        @POST("/apps/rmp/api-auth/login/")
        Observable<ResponseBody> postLogin(@Field("next") String next,
                                           @Field("csrfmiddlewaretoken") String csrf,
                                           @Field("username") String username,
                                           @Field("password") String password,
                                           @Field("submit") String submit);

        @GET("/apps/rmp/api-auth/login/")
        Observable<ResponseBody> getLogin();
    }

    private static class RandomMusicPlayerDataComparator
            implements Comparator<RandomMusicPlayerData> {

        @Override
        public int compare(RandomMusicPlayerData a, RandomMusicPlayerData b) {
            int no1 = a.getScore();
            int no2 = b.getScore();

            if (no1 < no2) {
                return 1;
            } else if (no1 == no2) {
                return 0;
            } else {
                return -1;
            }
        }
    }
}
