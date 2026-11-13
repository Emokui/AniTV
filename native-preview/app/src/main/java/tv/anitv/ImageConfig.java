package tv.anitv;

import android.content.Context;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.module.AppGlideModule;
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory;
import com.bumptech.glide.load.engine.cache.LruResourceCache;
import com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool;

@GlideModule
public final class ImageConfig extends AppGlideModule {
    @Override public boolean isManifestParsingEnabled() { return false; }
    @Override public void applyOptions(Context context, GlideBuilder builder) {
        builder.setMemoryCache(new LruResourceCache(16L * 1024 * 1024))
                .setBitmapPool(new LruBitmapPool(8L * 1024 * 1024))
                .setDiskCache(new InternalCacheDiskCacheFactory(context, 64L * 1024 * 1024));
    }
}
