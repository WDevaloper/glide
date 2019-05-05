package com.bumptech.glide.load.engine.cache;

import android.annotation.SuppressLint;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.util.LruCache;

/**
 * An LRU in memory cache for {@link com.bumptech.glide.load.engine.Resource}s.
 */
public class LruResourceCache extends LruCache<Key, Resource<?>> implements MemoryCache {
    private ResourceRemovedListener listener;

    /**
     * Constructor for LruResourceCache.
     *
     * @param size The maximum size in bytes the in memory cache can use.
     */
    public LruResourceCache(long size) {
        super(size);
    }

    @Override
    public void setResourceRemovedListener(@NonNull ResourceRemovedListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onItemEvicted(@NonNull Key key, @Nullable Resource<?> item) {
        if (listener != null && item != null) {
            listener.onResourceRemoved(item);
        }
    }

    @Override
    protected int getSize(@Nullable Resource<?> item) {
        if (item == null) {
            return super.getSize(null);
        } else {
            return item.getSize();
        }
    }

    @SuppressLint("InlinedApi")
    @Override
    public void trimMemory(int level) {
        //系统内存非常低了，该应用程序处在LRU缓存列表的最近位置，但不会被清理掉。
        //此时应该释放一些较为容易恢复的资源，让手机内存变得充足，从而让我们的应用程序更长时间的待在缓存你中
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            // Entering list of cached background apps
            // Evict our entire bitmap cache
            clearMemory();


            //系统内存非常低了，并将应用从前台切换到后台，即回收UI资源
            //应用程序正常运行，但大部分后台程序已经被杀死，请务必释放自身的不需要的内存资源，否则你也可能会被杀死。
        } else if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                || level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            // The app's UI is no longer visible, or app is in the foreground but system is running
            // critically low on memory
            // Evict oldest half of our bitmap cache
            trimToSize(getMaxSize() / 2);
        }
    }
}
