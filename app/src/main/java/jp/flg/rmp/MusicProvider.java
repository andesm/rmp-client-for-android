package jp.flg.rmp;


import android.content.Context;
import android.media.MediaMetadata;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import io.realm.Realm;
import io.realm.RealmConfiguration;
import io.realm.RealmResults;
import okhttp3.JavaNetCookieJar;
import okhttp3.OkHttpClient;
import okhttp3.ResponseBody;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
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

public class MusicProvider {
    private static final String TAG = LogHelper.makeLogTag(MusicProvider.class);

    public static final String CUSTOM_METADATA_TRACK_SOURCE = "__SOURCE__";
    private static final String BASE_URL = "http://flg.jp:10080/";

    private interface RmpRESTfulApi {
        @GET("/app/rmp/music/")
        Observable<List<RmpData>> get(@Header("X-CSRFToken") String csrfToken);

        @PUT("/app/rmp/music/{id}/")
        Observable<ResponseBody> put(@Header("X-CSRFToken") String csrfToken, @Path("id") String id, @Body RmpData rmpData);

        @FormUrlEncoded
        @POST("/app/rmp/api-auth/login/")
        Observable<ResponseBody> postLogin(@Field("next") String next, @Field("csrfmiddlewaretoken") String csrfToken, @Field("username") String username, @Field("password") String password, @Field("submit") String submit);

        @GET("/app/rmp/api-auth/login/")
        Observable<ResponseBody> getLogin();
    }

    private Retrofit retrofit;
    private String csrfToken;

    private Realm realm;
    private List<RmpData> rmpDataList = new ArrayList<>();
    private Iterator<RmpData> rmpDataIterator;
    private RmpData nowMusic;

    private CookieManager cookieManager = new CookieManager();

    public MusicProvider(Context context) {
        RealmConfiguration realmConfig = new RealmConfiguration.Builder(context).build();
        Realm.setDefaultConfiguration(realmConfig);
        realm = Realm.getDefaultInstance();
        //realm.beginTransaction();
        //realm.deleteAll();
        //realm.commitTransaction();

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
                .setLenient()
                .create();

        retrofit = new Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .addCallAdapterFactory(RxJavaCallAdapterFactory.create())
                .client(okHttpclient)
                .build();
    }

    public void getRmpData() {
        retrofit.create(RmpRESTfulApi.class).getLogin()
                .flatMap(new Func1<ResponseBody, Observable<ResponseBody>>() {
                    @Override
                    public Observable<ResponseBody> call(ResponseBody body) {
                        setCsrfToken();
                        return retrofit.create(RmpRESTfulApi.class).postLogin("/app/rmp/", csrfToken, "andesm", "AkdiJ352o", "Log in");
                    }
                })
                .flatMap(new Func1<ResponseBody, Observable<List<RmpData>>>() {
                    @Override
                    public Observable<List<RmpData>> call(ResponseBody body) {
                        setCsrfToken();
                        return retrofit.create(RmpRESTfulApi.class).get(csrfToken);
                    }
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread()).
                subscribe(new Observer<List<RmpData>> () {
                    @Override
                    public void onCompleted() {
                    }

                    @Override
                    public void onError(Throwable e) {
                        LogHelper.d(TAG, e);
                    }

                    @Override
                    public void onNext(List<RmpData> rmpDataList)  {
                        realm.beginTransaction();
                        for (RmpData rmpData : rmpDataList) {
                            RmpData rmpRealmData = realm.where(RmpData.class).equalTo("id", rmpData.getId()).findFirst();
                            if (rmpRealmData == null) {
                                RmpData createdRmpData = realm.copyToRealm(rmpData);
                            } else {
                                rmpRealmData.updateRmpData(rmpData);
                            }
                        }
                        realm.commitTransaction();
                        LogHelper.d(TAG, "Getting from REST completed");
                        setRmpDataRealm();
                    }
                });
        setRmpDataRealm();
    }


    public MediaMetadata getNowMusic() {
        if (rmpDataList.isEmpty()) {
            return null;
        }
        realm.beginTransaction();
        do {
            if(!rmpDataIterator.hasNext()) {
                rmpDataIterator = rmpDataList.iterator();
            }
            nowMusic = rmpDataIterator.next();
        } while(!nowMusic.isPlay());
        realm.commitTransaction();

        return nowMusic.toMediaMetadata();
    }


    public void handleCompletion() {
        realm.beginTransaction();
        nowMusic.handleCompletion();
        realm.commitTransaction();
        putRmpData();
    }

    public void handleSkipToNext() {
        realm.beginTransaction();
        nowMusic.handleSkipToNext();
        realm.commitTransaction();
        putRmpData();
    }

    public void handleSkipToPrevious() {
        realm.beginTransaction();
        nowMusic.handleSkipToPrevious();
        realm.commitTransaction();
    }


    private void putRmpData() {
        retrofit.create(RmpRESTfulApi.class).put(csrfToken, nowMusic.getStringId(), realm.copyFromRealm(nowMusic))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread()).
                subscribe(new Observer<ResponseBody> () {
                    @Override
                    public void onCompleted() {
                    }

                    @Override
                    public void onError(Throwable e) {
                        LogHelper.d(TAG, e);
                    }

                    @Override
                    public void onNext(ResponseBody body) {

                    }
                });
    }

    private void setCsrfToken() {
        List<HttpCookie> cookies = cookieManager.getCookieStore().getCookies();
        for (HttpCookie cookie : cookies) {
            if (cookie.getName().equals("csrftoken")) {
                csrfToken = cookie.getValue();
            }
        }
    }

    private void setRmpDataRealm() {
        RealmResults<RmpData> rmpDataRealm = realm.where(RmpData.class).findAll();
        rmpDataList.clear();
        for (RmpData rmpData : rmpDataRealm) {
            rmpDataList.add(rmpData);
        }
        Collections.shuffle(rmpDataList);
        rmpDataIterator = rmpDataList.iterator();
    }
}
