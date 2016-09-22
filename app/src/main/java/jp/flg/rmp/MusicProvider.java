package jp.flg.rmp;


import android.content.Context;
import android.media.MediaMetadata;
import android.support.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import io.realm.Realm;
import io.realm.RealmConfiguration;
import io.realm.RealmResults;
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
    private final Retrofit m_retrofit;
    private final Realm realm;
    private final List<RandomMusicPlayerData> rmpDataList = new ArrayList<>();
    private final CookieManager cookieManager = new CookieManager();
    private String csrfToken;
    private Iterator<RandomMusicPlayerData> rmpDataIterator;
    private RandomMusicPlayerData nowMusic;

    MusicProvider(Context context) {
        RealmConfiguration.Builder builder = new RealmConfiguration.Builder(context);
        RealmConfiguration realmConfig = builder.build();
        Realm.setDefaultConfiguration(realmConfig);
        realm = Realm.getDefaultInstance();
        //realm.beginTransaction();

        //realm.deleteAll();
        //realm.commitTransaction();

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

        m_retrofit = new Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .addCallAdapterFactory(RxJavaCallAdapterFactory.create())
                .client(okHttpclient)
                .build();
    }

    void getRmpData() {
        m_retrofit.create(RandomMusicPlayerRESTfulApi.class).getLogin()
                .flatMap(new Func1<ResponseBody, Observable<ResponseBody>>() {
                    @Override
                    public Observable<ResponseBody> call(ResponseBody body) {
                        setCsrfToken();
                        return m_retrofit.create(RandomMusicPlayerRESTfulApi.class)
                                .postLogin("/app/rmp/", csrfToken,
                                        "andesm", "AkdiJ352o", "Log in");
                    }
                })
                .flatMap(new Func1<ResponseBody, Observable<List<RandomMusicPlayerData>>>() {
                    @Override
                    public Observable<List<RandomMusicPlayerData>> call(ResponseBody body) {
                        setCsrfToken();
                        return m_retrofit.create(RandomMusicPlayerRESTfulApi.class).get(csrfToken);
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
                    public void onNext(List<RandomMusicPlayerData> rmpDataListResult) {
                        realm.beginTransaction();
                        for (RandomMusicPlayerData rmpData : rmpDataListResult) {
                            RandomMusicPlayerData rmpRealmData = realm
                                    .where(RandomMusicPlayerData.class)
                                    .equalTo("id", rmpData.getId()).findFirst();
                            if (rmpRealmData == null) {
                                RandomMusicPlayerData createdRmpData = realm.copyToRealm(rmpData);
                            } else {
                                rmpRealmData.updateRmpData(rmpData);
                            }
                        }
                        realm.commitTransaction();
                        LogHelper.d(LOG_TAG, "Getting from REST completed");
                        setRmpDataRealm();
                    }
                });
        setRmpDataRealm();
        setNextNowMusic();
    }

    private void setNextNowMusic() {
        realm.beginTransaction();
        do {
            if (!rmpDataIterator.hasNext()) {
                rmpDataIterator = rmpDataList.iterator();
            }
            nowMusic = rmpDataIterator.next();
        } while (!nowMusic.isPlay());
        realm.commitTransaction();
    }

    @Nullable
    MediaMetadata getNowMusic() {
        return nowMusic != null ? nowMusic.toMediaMetadata() : null;
    }

    void handleCompletion() {
        realm.beginTransaction();
        nowMusic.handleCompletion();
        realm.commitTransaction();
        putRmpData();
        setNextNowMusic();
    }

    void handleSkipToNext() {
        realm.beginTransaction();
        nowMusic.handleSkipToNext();
        realm.commitTransaction();
        putRmpData();
        setNextNowMusic();
    }

    void handleSkipToPrevious() {
        realm.beginTransaction();
        nowMusic.handleSkipToPrevious();
        realm.commitTransaction();
        putRmpData();
    }

    private void putRmpData() {
        m_retrofit.create(RandomMusicPlayerRESTfulApi.class)
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

    private void setRmpDataRealm() {
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
