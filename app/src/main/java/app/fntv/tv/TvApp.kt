package app.fntv.tv

import android.app.Application
import android.util.Log
import app.fntv.tv.data.FnApi
import app.fntv.tv.data.Prefs
import app.fntv.tv.data.Repo
import coil.EventListener
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ErrorResult
import coil.request.ImageRequest
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 应用入口：初始化配置、会话与全局图片加载器。
 *
 * 图片（海报/剧照）和 API 一样需要鉴权，这里给 Coil 的 OkHttpClient 挂一个拦截器，
 * 每个请求发出时再读 [Repo.token]（登录/退出后立即生效，无需重建 ImageLoader）。
 */
class TvApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()

        // 配置与登录态必须在任何屏幕组合之前就绪
        Prefs.init(this)
        Repo.initFromPrefs()
    }

    /**
     * Coil 会在真正需要加载图片时才调用这里（不在主线程 onCreate 里建磁盘缓存）。
     * 失败时退回「只有鉴权 OkHttp、没有磁盘缓存」的 loader —— 关键是别把鉴权拦掉，
     * 否则海报接口会 401，整屏空白。
     */
    override fun newImageLoader(): ImageLoader = runCatching { createImageLoader() }
        .getOrElse {
            Log.w(TAG, "图片缓存初始化失败，退回无磁盘缓存的加载器", it)
            ImageLoader.Builder(this)
                .okHttpClient { createHttpClient() }
                .crossfade(true)
                .eventListener(IMAGE_ERROR_LOGGER)
                .build()
        }

    /** 构建全局 ImageLoader：带鉴权头的 OkHttp + 磁盘/内存缓存 + 淡入。 */
    private fun createImageLoader(): ImageLoader = ImageLoader.Builder(this)
        // 懒创建：真正发请求时才构造 OkHttpClient
        .okHttpClient { createHttpClient() }
        .diskCache {
            DiskCache.Builder()
                .directory(applicationContext.cacheDir.resolve("fntv_image_cache"))
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        .memoryCache {
            MemoryCache.Builder(applicationContext)
                .maxSizePercent(0.2)
                .build()
        }
        .crossfade(true)
        .eventListener(IMAGE_ERROR_LOGGER)
        .build()

    private fun createHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val token = Repo.token
            val builder = chain.request().newBuilder()
                .header("User-Agent", FnApi.UA)
            // 未登录（token 为空）时不加鉴权头，避免把空值带给服务端
            if (token.isNotEmpty()) {
                builder.header("Authorization", token)
                builder.header("Cookie", "mode=relay; Trim-MC-token=$token")
            }
            chain.proceed(builder.build())
        }
        .build()

    private companion object {
        const val TAG = "TvApp"

        /**
         * 图片加载失败只记日志：Coil 会把失败封装成 ErrorResult，AsyncImage 显示空白，
         * 不会把异常抛到 UI 线程，应用不会崩。
         */
        val IMAGE_ERROR_LOGGER = object : EventListener {
            override fun onError(request: ImageRequest, result: ErrorResult) {
                Log.w(TAG, "图片加载失败：${request.data}", result.throwable)
            }
        }
    }
}