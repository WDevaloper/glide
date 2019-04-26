package com.bumptech.glide.load.data;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;

/**
 * Lazily retrieves data that can be used to load a resource.
 *
 * <p> A new instance is
 * created per resource load by {@link com.bumptech.glide.load.model.ModelLoader}. {@link
 * #loadData(com.bumptech.glide.Priority, com.bumptech.glide.load.data.DataFetcher.DataCallback)}
 * may or may not be called for any given load depending on whether or not the corresponding
 * resource is cached. Cancel also may or may not be called. If
 * {@link #loadData(com.bumptech.glide.Priority,
 * com.bumptech.glide.load.data.DataFetcher.DataCallback)}} is called, then so {@link #cleanup()}
 * will be called. </p>
 *
 * @param <T> The type of data to be loaded (InputStream, byte[], File etc).
 */
public interface DataFetcher<T> {

    /**
     * Callback that must be called when data has been loaded and is available, or when the load
     * fails.
     * 在你获取的数据是否是可用的你必须调用这个告诉Glide是成功还是失败
     * <p>
     * 可以看一下子类如何实现
     *
     * @param <T> The type of data that will be loaded.
     */
    interface DataCallback<T> {

        /**
         * Called with the loaded data if the load succeeded, or with {@code null} if the load failed.
         * <p>
         * 如果加载成功，则使用加载的数据调用;如果加载失败，则使用{@code null}调用。
         */
        void onDataReady(@Nullable T data);

        /**
         * Called when the load fails.
         * <p>
         * 加载失败时调用。
         *
         * @param e a non-null {@link Exception} indicating why the load failed.
         */
        void onLoadFailed(@NonNull Exception e);
    }

    /**
     * 如果缓存中找不到资源，则会调用此方法取拉取数据
     *
     * Fetch data from which a resource can be decoded.
     * 获取可以解码资源的数据
     *
     * <p> This will always be called on background thread so it is safe to perform long running tasks
     * here. Any third party libraries called must be thread safe (or move the work to another thread)
     * since this method will be called from a thread in a
     * {@link java.util.concurrent.ExecutorService}
     * that may have more than one background thread. </p>
     * 这将始终在后台线程上调用，因此在此处执行长时间运行的任务是安全的。
     * 调用的任何第三方库必须是线程安全的（或将工作移动到另一个线程），因为此方法将从
     * {@link java.util.concurrent.ExecutorService}中可能具有多个后台线程的线程调用。
     * <p>
     * You <b>MUST</b> use the {@link DataCallback} once the request is complete.
     * 一旦请求完成，您<b>必须</ b>使用{@link DataCallback}。
     * <p>
     * You are free to move the fetch work to another thread and call the callback from there.
     * 您可以自由地将获取工作移动到另一个线程并从那里调用回调。
     *
     * <p> This method will only be called when the corresponding resource is not in the cache. </p>
     * 仅当相应资源不在缓存中时才会调用此方法。
     *
     * <p> Note - this method will be run on a background thread so blocking I/O is safe. </p>
     * 注意 - 此方法将在后台线程上运行，因此阻塞I/O是安全的。
     *
     * @param priority 请求的优先级
     * @param callback 请求完成时使用的回调告知GLide进行下一步的操作
     * @see #cleanup() where the data retuned will be cleaned up
     */
    void loadData(@NonNull Priority priority, @NonNull DataCallback<? super T> callback);

    /**
     * Cleanup or recycle any resources used by this data fetcher. This method will be called in a
     * finally block after the data provided by {@link #loadData(com.bumptech.glide.Priority,
     * com.bumptech.glide.load.data.DataFetcher.DataCallback)} has been decoded by the
     * {@link com.bumptech.glide.load.ResourceDecoder}.
     * <p>
     * 清理或回收此数据获取器使用的任何资源。 在loadData方法提供的数据已被解码后回调此方法。
     *
     * <p> Note - this method will be run on a background thread so blocking I/O is safe. </p>
     * 注意 - 此方法将在后台线程上运行，因此阻塞I/O是安全的。
     */
    void cleanup();

    /**
     * A method that will be called when a load is no longer relevant and has been cancelled. This
     * method does not need to guarantee that any in process loads do not finish. It also may be
     * called before a load starts or after it finishes.
     *
     * <p> The best way to use this method is to cancel any loads that have not yet started, but allow
     * those that are in process to finish since its we typically will want to display the same
     * resource in a different view in the near future. </p>
     *
     * <p> Note - this method will be run on the main thread so it should not perform blocking
     * operations and should finish quickly. </p>
     * <p>
     * 当加载不再相关且已被取消时将调用的方法。 这个方法不需要保证任何进程中的加载都没有完成。 它也可能是在加载开始之前或完成之后调用。
     *    
     * 使用此方法的最佳方法是取消尚未启动的任何负载，但允许那些正在完成的过程，因为我们通常会想要显示相同的内容资源在不久的将来以不同的视角。
     * 注意 - 此方法将在主线程上运行，因此不应执行阻塞操作并应尽快完成。
     */
    void cancel();

    /**
     * Returns the class of the data this fetcher will attempt to obtain.
     * <p>
     * 返回此fetcher将尝试获取的数据的类。
     */
    @NonNull
    Class<T> getDataClass();

    /**
     * Returns the {@link com.bumptech.glide.load.DataSource} this fetcher will return data from.
     * <p>
     * 返回{@link com.bumptech.glide.load.DataSource}此fetcher将从中返回数据。
     */
    @NonNull
    DataSource getDataSource();
}
