package com.dsh.kidstv

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 全局共用的 HTTP 客户端。
 *
 * 之前 DavClient / Thumbs / Frames / BookCache 各建一个，
 * 每个都带自己的连接池和 dispatcher，不但浪费内存，连接还不能复用。
 */
object Net {

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            // 中继偶尔会抽风断一条空闲连接，直接重试比报错给用户好
            .retryOnConnectionFailure(true)
            .build()
    }
}
