package com.bumptech.glide.load.engine;

import android.os.Process;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;

import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.engine.EngineResource.ResourceListener;
import com.bumptech.glide.util.Executors;
import com.bumptech.glide.util.Preconditions;
import com.bumptech.glide.util.Synthetic;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;

final class ActiveResources {
    private final boolean isActiveResourceRetentionAllowed;

    // 监控GC回收资源线程池
    private final Executor monitorClearedResourcesExecutor;


    //使用强引用存储ResourceWeakReference
    @VisibleForTesting
    final Map<Key, ResourceWeakReference> activeEngineResources = new HashMap<>();


    //引用队列与ResourceWeakReference配合监听GC回收
    private final ReferenceQueue<EngineResource<?>> resourceReferenceQueue = new ReferenceQueue<>();

    private ResourceListener listener;

    private volatile boolean isShutdown;
    @Nullable
    private volatile DequeuedResourceCallback cb;

    ActiveResources(boolean isActiveResourceRetentionAllowed) {
        this(
                isActiveResourceRetentionAllowed,
                //启动监控线程
                java.util.concurrent.Executors.newSingleThreadExecutor(
                        new ThreadFactory() {
                            @Override
                            public Thread newThread(@NonNull final Runnable r) {
                                return new Thread(
                                        new Runnable() {
                                            @Override
                                            public void run() {
                                                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                                                r.run();
                                            }
                                        },
                                        "glide-active-resources");
                            }
                        }));
    }

    @VisibleForTesting
    ActiveResources(boolean isActiveResourceRetentionAllowed, Executor monitorClearedResourcesExecutor) {
        this.isActiveResourceRetentionAllowed = isActiveResourceRetentionAllowed;
        this.monitorClearedResourcesExecutor = monitorClearedResourcesExecutor;

        monitorClearedResourcesExecutor.execute(
                new Runnable {
                    @Override
                    public void run() {
                        cleanReferenceQueue();
                    }
                });
    }

    void setListener(ResourceListener listener) {
        synchronized (listener) {
            synchronized (this) {
                this.listener = listener;
            }
        }
    }

    /**
     * 资源加载完成调用此方法写入缓存
     *
     * @param key
     * @param resource
     */
    synchronized void activate(Key key, EngineResource<?> resource) {
        ResourceWeakReference toPut =
                new ResourceWeakReference(
                        key, resource, resourceReferenceQueue, isActiveResourceRetentionAllowed);

        ResourceWeakReference removed = activeEngineResources.put(key, toPut);
        if (removed != null) {
            removed.reset();
        }
    }

    /**
     * 移除指定Key的缓存
     *
     * @param key
     */
    synchronized void deactivate(Key key) {
        ResourceWeakReference removed = activeEngineResources.remove(key);
        if (removed != null) {
            removed.reset();
        }
    }


    /**
     * 通过Key获取缓存
     *
     * @param key
     * @return
     */
    @Nullable
    synchronized EngineResource<?> get(Key key) {
        ResourceWeakReference activeRef = activeEngineResources.get(key);
        if (activeRef == null) {
            return null;
        }

        EngineResource<?> active = activeRef.get();
        if (active == null) {
            cleanupActiveReference(activeRef);
        }
        return active;
    }

    @SuppressWarnings({"WeakerAccess", "SynchronizeOnNonFinalField"})
    @Synthetic
    void cleanupActiveReference(@NonNull ResourceWeakReference ref) {
        // Fixes a deadlock where we normally acquire the Engine lock and then the ActiveResources lock
        // but reverse that order in this one particular test. This is definitely a bit of a hack...
        synchronized (listener) {
            synchronized (this) {
                //将Reference移除HashMap强引用
                activeEngineResources.remove(ref.key);

                if (!ref.isCacheable || ref.resource == null) {
                    return;
                }

                //重新构建新的资源
                EngineResource<?> newResource = new EngineResource<>(ref.resource,
                        /*isMemoryCacheable=*/ true,
                        /*isRecyclable=*/ false,
                        ref.key,
                        listener);

                // 如果资源被回收，有可能会回调Engine资源会被再次加入LruCache内存缓存中
                listener.onResourceReleased(ref.key, newResource);
            }
        }
    }

    @SuppressWarnings("WeakerAccess")
    @Synthetic
    void cleanReferenceQueue() {
        while (!isShutdown) {
            try {
                //remove会阻塞当前线程，知道GC回收，将ResourceWeakReference放入队列
                ResourceWeakReference ref = (ResourceWeakReference) resourceReferenceQueue.remove();
                cleanupActiveReference(ref);

                // 这行代码仅仅是测试用的
                DequeuedResourceCallback current = cb;
                if (current != null) {
                    current.onResourceDequeued();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @VisibleForTesting
    void setDequeuedResourceCallback(DequeuedResourceCallback cb) {
        this.cb = cb;
    }

    @VisibleForTesting
    interface DequeuedResourceCallback {
        void onResourceDequeued();
    }

    @VisibleForTesting
    void shutdown() {
        isShutdown = true;
        if (monitorClearedResourcesExecutor instanceof ExecutorService) {
            ExecutorService service = (ExecutorService) monitorClearedResourcesExecutor;
            Executors.shutdownAndAwaitTermination(service);
        }
    }

    @VisibleForTesting
    static final class ResourceWeakReference extends WeakReference<EngineResource<?>> {
        @SuppressWarnings("WeakerAccess")
        @Synthetic
        final Key key;
        @SuppressWarnings("WeakerAccess")
        @Synthetic
        final boolean isCacheable;

        @Nullable
        @SuppressWarnings("WeakerAccess")
        @Synthetic
        Resource<?> resource;

        @Synthetic
        @SuppressWarnings("WeakerAccess")
        ResourceWeakReference(@NonNull Key key, @NonNull EngineResource<?> referent, @NonNull ReferenceQueue<? super EngineResource<?>> queue, boolean isActiveResourceRetentionAllowed) {
            super(referent, queue);
            this.key = Preconditions.checkNotNull(key);
            this.resource = referent.isMemoryCacheable() && isActiveResourceRetentionAllowed ? Preconditions.checkNotNull(referent.getResource()) : null;
            isCacheable = referent.isMemoryCacheable();
        }

        /**
         * 调用此方法会将references加入与之关联的ReferencesQueue队列，当然这个方法有可能GC也会调用此方法清除references中的对象置为null，
         * <p>
         * 所以这里重写WeakReference，使用成员变量保存资源对象，如果你通过get方法获取对象必定为null
         */
        void reset() {
            resource = null;
            clear();
        }
    }
}
