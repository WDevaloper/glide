package com.bumptech.glide.load.engine;

import android.support.annotation.NonNull;

import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Encoder;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.data.HttpUrlFetcher;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoader.LoadData;

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
    //主要和缓存相关
    private volatile ModelLoader.LoadData<?> loadData;

    private int loadDataListIndex;
    //数据缓存代
    private DataCacheGenerator sourceCacheGenerator;
    private Object dataToCache;

    private DataCacheKey originalKey;

    SourceGenerator(DecodeHelper<?> helper, FetcherReadyCallback cb) {
        this.helper = helper;
        this.cb = cb;
    }

    /**
     * 准备开始拉取网络数据
     *
     * @return
     */
    @Override
    public boolean startNext() {

        // 判断是否有数据需要取缓存
        if (dataToCache != null) {
            Object data = dataToCache;
            dataToCache = null;
            cacheData(data);
        }

        // 如果上一步创建了资源缓存代，就开始资源缓存代
        if (sourceCacheGenerator != null && sourceCacheGenerator.startNext()) {
            return true;
        }
        sourceCacheGenerator = null;

        loadData = null;
        boolean started = false;
        while (!started && hasNextModelLoader()) {
            // 获取当前的数据请求器
            loadData = helper.getLoadData().get(loadDataListIndex++);

            if (loadData != null &&
                    (helper.getDiskCacheStrategy().isDataCacheable(loadData.fetcher.getDataSource())
                            || helper.hasLoadPath(loadData.fetcher.getDataClass()))) {
                started = true;

                /**
                 * 从Glide注册的register中获取请求model 的加载器
                 *
                 *  DataFetcher的loadData()方法的回调时机,请看实现类{@link HttpUrlFetcher}
                 *
                 *  还把回调传进去
                 *
                 *  异步操作
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
     * 缓存操作
     *
     * @param dataToCache
     */
    private void cacheData(Object dataToCache) {
        try {
            /**
             * 其实这里主要构造DecodeHelper
             */
            //编码
            Encoder<Object> encoder = helper.getSourceEncoder(dataToCache);
            // 写入缓存
            DataCacheWriter<Object> writer = new DataCacheWriter<>(encoder, dataToCache, helper.getOptions());
            // 缓存Key
            originalKey = new DataCacheKey(loadData.sourceKey, helper.getSignature());
            helper.getDiskCache().put(originalKey, writer);
        } finally {
            /**
             * 就是在做完缓存之后回调，请看实现类{@link HttpUrlFetcher}做资源清理工作
             *
             * DecodeJob也有调用
             *
             */
            loadData.fetcher.cleanup();
        }

        // 创建资源缓存代
        sourceCacheGenerator = new DataCacheGenerator(Collections.singletonList(loadData.sourceKey), helper, this);
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

        //如果不缓存
        if (data != null && diskCacheStrategy.isDataCacheable(loadData.fetcher.getDataSource())) {
            dataToCache = data;
            // We might be being called back on someone else's thread. Before doing anything, we should
            // reschedule to get back onto Glide's thread.
            /**
             * 重新调度startNext()，然后调用cacheData(Object dataToCache)取缓存
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
        throw new UnsupportedOperationException();
    }

    // Called from source cache generator.
    @Override
    public void onDataFetcherReady(Key sourceKey, Object data, DataFetcher<?> fetcher,
                                   DataSource dataSource, Key attemptedKey) {
        cb.onDataFetcherReady(sourceKey, data, fetcher, loadData.fetcher.getDataSource(), sourceKey);
    }

    @Override
    public void onDataFetcherFailed(Key sourceKey, Exception e, DataFetcher<?> fetcher,
                                    DataSource dataSource) {
        cb.onDataFetcherFailed(sourceKey, e, fetcher, loadData.fetcher.getDataSource());
    }
}
