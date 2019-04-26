package com.bumptech.glide.load.engine;

import android.support.annotation.NonNull;
import android.util.Log;

import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Encoder;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.data.HttpUrlFetcher;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoader.LoadData;
import com.bumptech.glide.util.LogTime;

import java.util.Collections;

/**
 * Generates {@link com.bumptech.glide.load.data.DataFetcher DataFetchers} from original source data
 * using registered {@link com.bumptech.glide.load.model.ModelLoader ModelLoaders} and the model
 * provided for the load.
 *
 * <p> Depending on the disk cache strategy, source data may first be written to disk and then
 * loaded from the cache file rather than returned directly. </p>
 */
class SourceGenerator implements DataFetcherGenerator, DataFetcher.DataCallback<Object>, DataFetcherGenerator.FetcherReadyCallback {
    private static final String TAG = "SourceGenerator";

    //解码帮助类
    private final DecodeHelper<?> helper;
    // 加载资源回调，一般
    private final FetcherReadyCallback cb;

    private volatile ModelLoader.LoadData<?> loadData;


    private int loadDataListIndex;
    private DataCacheGenerator sourceCacheGenerator;
    private Object dataToCache;

    private DataCacheKey originalKey;

    SourceGenerator(DecodeHelper<?> helper, FetcherReadyCallback cb) {
        this.helper = helper;
        this.cb = cb;
    }

    /**
     * 准备开始拉去数据
     *
     * @return
     */
    @Override
    public boolean startNext() {
        if (dataToCache != null) {
            Object data = dataToCache;
            dataToCache = null;
            cacheData(data);
        }

        if (sourceCacheGenerator != null && sourceCacheGenerator.startNext()) {
            return true;
        }
        sourceCacheGenerator = null;

        loadData = null;
        boolean started = false;
        while (!started && hasNextModelLoader()) {
            loadData = helper.getLoadData().get(loadDataListIndex++);
            if (loadData != null
                    && (helper.getDiskCacheStrategy().isDataCacheable(loadData.fetcher.getDataSource())
                    || helper.hasLoadPath(loadData.fetcher.getDataClass()))) {
                started = true;

                /**
                 *  DataFetcher的loadData()方法的回调时机,请看实现类{@link HttpUrlFetcher}
                 */
                loadData.fetcher.loadData(helper.getPriority(), this);
            }
        }
        return started;
    }

    private boolean hasNextModelLoader() {
        return loadDataListIndex < helper.getLoadData().size();
    }


    /**
     * @param dataToCache
     */
    private void cacheData(Object dataToCache) {
        long startTime = LogTime.getLogTime();
        try {
            Encoder<Object> encoder = helper.getSourceEncoder(dataToCache);
            DataCacheWriter<Object> writer = new DataCacheWriter<>(encoder, dataToCache, helper.getOptions());
            originalKey = new DataCacheKey(loadData.sourceKey, helper.getSignature());
            helper.getDiskCache().put(originalKey, writer);
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "Finished encoding source to cache"
                        + ", key: " + originalKey
                        + ", data: " + dataToCache
                        + ", encoder: " + encoder
                        + ", duration: " + LogTime.getElapsedMillis(startTime));
            }
        } finally {
            /**
             * 就是在做完缓存之后回调，请看实现类{@link HttpUrlFetcher}做资源清理工作
             *
             */
            loadData.fetcher.cleanup();
        }

        sourceCacheGenerator =
                new DataCacheGenerator(Collections.singletonList(loadData.sourceKey), helper, this);
    }

    @Override
    public void cancel() {
        LoadData<?> local = loadData;
        if (local != null) {
            local.fetcher.cancel();
        }
    }


    /**
     * 这就是为什么我们在loadData加载网络成功或者失败之后需要调用{@link DataFetcher.DataCallback}的方法回调通知Glide
     *
     * @param data
     */
    @Override
    public void onDataReady(Object data) {
        /**
         * 获取磁盘缓存策略
         */
        DiskCacheStrategy diskCacheStrategy = helper.getDiskCacheStrategy();
        if (data != null && diskCacheStrategy.isDataCacheable(loadData.fetcher.getDataSource())) {
            dataToCache = data;
            // We might be being called back on someone else's thread. Before doing anything, we should
            // reschedule to get back onto Glide's thread.
            /**
             * 重新调度startNext()
             */
            cb.reschedule();
        } else {
            /**
             * 网络数据加载成功之后如果不需要缓存，直接回调回去展示
             */
            cb.onDataFetcherReady(loadData.sourceKey, data, loadData.fetcher, loadData.fetcher.getDataSource(), originalKey);
        }
    }


    /**
     * 这就是为什么我们在loadData成功或者失败之后需要调用{@link DataFetcher.DataCallback}的方法回调通知Glide
     *
     * @param e a non-null {@link Exception} indicating why the load failed.
     */
    @Override
    public void onLoadFailed(@NonNull Exception e) {
        cb.onDataFetcherFailed(originalKey, e, loadData.fetcher, loadData.fetcher.getDataSource());
    }

    @Override
    public void reschedule() {
        // We don't expect this to happen, although if we ever need it to we can delegate to our
        // callback.
        throw new UnsupportedOperationException();
    }

    // Called from source cache generator.
    @Override
    public void onDataFetcherReady(Key sourceKey, Object data, DataFetcher<?> fetcher,
                                   DataSource dataSource, Key attemptedKey) {
        // This data fetcher will be loading from a File and provide the wrong data source, so override
        // with the data source of the original fetcher
        cb.onDataFetcherReady(sourceKey, data, fetcher, loadData.fetcher.getDataSource(), sourceKey);
    }

    @Override
    public void onDataFetcherFailed(Key sourceKey, Exception e, DataFetcher<?> fetcher,
                                    DataSource dataSource) {
        cb.onDataFetcherFailed(sourceKey, e, fetcher, loadData.fetcher.getDataSource());
    }
}
