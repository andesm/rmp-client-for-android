package jp.flg.rmp;

import android.content.Context;
import android.media.MediaMetadata;
import android.os.Environment;
import android.support.annotation.Nullable;
import android.util.SparseBooleanArray;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import io.realm.Realm;
import io.realm.RealmResults;
import io.realm.exceptions.RealmException;
import okhttp3.JavaNetCookieJar;
import okhttp3.OkHttpClient;
import okhttp3.ResponseBody;
import okhttp3.logging.HttpLoggingInterceptor;
import okhttp3.logging.HttpLoggingInterceptor.Level;
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
import rx.functions.Func1;
import rx.schedulers.Schedulers;

class MusicProvider {
    static final String CUSTOM_METADATA_TRACK_SOURCE = "__SOURCE__";
    private static final String LOG_TAG = LogHelper.makeLogTag(MusicProvider.class);
    private static final String BASE_URL = "http://flg.jp:10080/";
    private final List<RandomMusicPlayerData> rmpDataList = new ArrayList<>();
    private final CookieManager cookieManager = new CookieManager();
    private Retrofit retrofit;
    private Realm realm;
    private String csrfToken;
    private Iterator<RandomMusicPlayerData> rmpDataIterator;
    @Nullable
    private RandomMusicPlayerData nowMusic;
    private boolean retrieving;

    MusicProvider(Context context) {
        Realm.init(context);
        try {
            realm = Realm.getDefaultInstance();
        } catch (RealmException ignored) {
            return;
        }

        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor();
        //loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
        loggingInterceptor.setLevel(Level.HEADERS);

        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);

        OkHttpClient okHttpclient = new OkHttpClient.Builder()
                .addInterceptor(loggingInterceptor)
                //.addNetworkInterceptor(new StethoInterceptor())
                .cookieJar(new JavaNetCookieJar(cookieManager))
                .build();

        Gson gson = new GsonBuilder()
                .setLenient()
                .create();

        retrofit = new Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .addCallAdapterFactory(RxJavaCallAdapterFactory.create())
                .client(okHttpclient)
                .build();
    }

    void getRmpData() {
        if (!retrieving) {
            retrieving = true;
            retrofit.create(RandomMusicPlayerRESTfulApi.class).getLogin()
                    .flatMap(new Func1<ResponseBody, Observable<ResponseBody>>() {
                        @Override
                        public Observable<ResponseBody> call(ResponseBody body) {
                            setCsrfToken();
                            return retrofit.create(RandomMusicPlayerRESTfulApi.class)
                                    .postLogin("/app/rmp/", csrfToken,
                                            "andesm", "AkdiJ352o", "Log in");
                        }
                    })
                    .flatMap(new Func1<ResponseBody, Observable<List<RandomMusicPlayerData>>>() {
                        @Override
                        public Observable<List<RandomMusicPlayerData>> call(ResponseBody body) {
                            setCsrfToken();
                            return retrofit.create(RandomMusicPlayerRESTfulApi.class).get(csrfToken);
                        }
                    })
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread()).
                    subscribe(new Observer<List<RandomMusicPlayerData>>() {
                        @Override
                        public void onCompleted() {
                        }

                        @Override
                        public void onError(Throwable e) {
                            LogHelper.d(LOG_TAG, e);
                        }

                        @Override
                        public void onNext(final List<RandomMusicPlayerData> rmpDataListResult) {
                            //realm.deleteAll();
                            LogHelper.d(LOG_TAG, "Getting from REST completed");

                            realm.executeTransactionAsync(new Realm.Transaction() {
                                @Override
                                public void execute(Realm bgRealm) {
                                    LogHelper.d(LOG_TAG, "Storing to Database start");
                                    SparseBooleanArray id = new SparseBooleanArray();
                                    for (RandomMusicPlayerData rmpData : rmpDataListResult) {
                                        RandomMusicPlayerData rmpRealmData = bgRealm
                                                .where(RandomMusicPlayerData.class)
                                                .equalTo("id", rmpData.getId()).findFirst();
                                        if (rmpRealmData == null) {
                                            RandomMusicPlayerData createdRmpData =
                                                    bgRealm.copyToRealm(rmpData);
                                        } else {
                                            rmpRealmData.updateRmpData(rmpData);
                                        }
                                        id.put(rmpData.getId(), true);
                                    }
                                    LogHelper.d(LOG_TAG, "Storing to Database completed");

                                    RealmResults<RandomMusicPlayerData> rmpDataRealm =
                                            bgRealm.where(RandomMusicPlayerData.class).findAll();
                                    for (RandomMusicPlayerData rmpData : rmpDataRealm) {
                                        if (!id.get(rmpData.getId())) {
                                            LogHelper.d(LOG_TAG, "Deleted Rmp Data: " +
                                                    rmpData.getId());
                                            rmpData.deleteFromRealm();
                                        }
                                    }
                                }
                            }, new Realm.Transaction.OnSuccess() {
                                @Override
                                public void onSuccess() {
                                    LogHelper.d(LOG_TAG, "Deleting to Database completed");
                                    setRmpDataFromRealm();
                                    setNextNowMusic();
                                    retrieving = false;
                                }
                            }, new Realm.Transaction.OnError() {
                                @Override
                                public void onError(Throwable error) {
                                    LogHelper.d(LOG_TAG, "executeTransactionAsync error");
                                    retrieving = false;
                                }
                            });

                        }
                    });
        }
        setRmpDataFromRealm();
        setNextNowMusic();
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
            putRmpData();
        }
        setNextNowMusic();
    }

    void handleSkipToNext() {
        if (nowMusic != null) {
            realm.beginTransaction();
            nowMusic.handleSkipToNext();
            realm.commitTransaction();
            putRmpData();
        }
        setNextNowMusic();
    }

    void handleSkipToPrevious() {
        if (nowMusic != null) {
            realm.beginTransaction();
            nowMusic.handleSkipToPrevious();
            realm.commitTransaction();
            putRmpData();
        }
    }

    private void setNextNowMusic() {
        if (rmpDataList.isEmpty()) {
            nowMusic = null;
            return;
        }

        int size = rmpDataList.size();
        for (int count = 0; count < size; count++) {
            if (!rmpDataIterator.hasNext()) {
                rmpDataIterator = rmpDataList.iterator();
            }
            nowMusic = rmpDataIterator.next();
            if (nowMusic == null) {
                return;
            }
            String source = Environment.getExternalStorageDirectory()
                    + "/rmp/"
                    + nowMusic.getFile();
            File file = new File(source);
            if (file.exists()) {
                realm.beginTransaction();
                nowMusic.isPlay();
                realm.commitTransaction();
                return;
            }
        }
    }

    private void putRmpData() {
        if (nowMusic == null) {
            return;
        }
        retrofit.create(RandomMusicPlayerRESTfulApi.class)
                .put(csrfToken, nowMusic.getStringId(), realm.copyFromRealm(nowMusic))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread()).
                subscribe(new Observer<ResponseBody>() {
                    @Override
                    public void onCompleted() {
                    }

                    @Override
                    public void onError(Throwable e) {
                        LogHelper.d(LOG_TAG, e);
                    }

                    @Override
                    public void onNext(ResponseBody body) {

                    }
                });
    }

    private void setCsrfToken() {
        List<HttpCookie> cookies = cookieManager.getCookieStore().getCookies();
        for (HttpCookie cookie : cookies) {
            if (Objects.equals("csrftoken", cookie.getName())) {
                csrfToken = cookie.getValue();
            }
        }
    }

    private void setRmpDataFromRealm() {
        RealmResults<RandomMusicPlayerData> rmpDataRealm =
                realm.where(RandomMusicPlayerData.class).findAll();
        rmpDataList.clear();
        for (RandomMusicPlayerData rmpData : rmpDataRealm) {
            rmpDataList.add(rmpData);
        }
        Collections.shuffle(rmpDataList);
        rmpDataIterator = rmpDataList.iterator();
    }

    private interface RandomMusicPlayerRESTfulApi {
        @GET("/app/rmp/music/")
        Observable<List<RandomMusicPlayerData>> get(@Header("X-CSRFToken") String csrf);

        @PUT("/app/rmp/music/{id}/")
        Observable<ResponseBody> put(@Header("X-CSRFToken") String csrf,
                                     @Path("id") String id,
                                     @Body RandomMusicPlayerData rmpData);

        @FormUrlEncoded
        @POST("/app/rmp/api-auth/login/")
        Observable<ResponseBody> postLogin(@Field("next") String next,
                                           @Field("csrfmiddlewaretoken") String csrf,
                                           @Field("username") String username,
                                           @Field("password") String password,
                                           @Field("submit") String submit);

        @GET("/app/rmp/api-auth/login/")
        Observable<ResponseBody> getLogin();
    }
}
