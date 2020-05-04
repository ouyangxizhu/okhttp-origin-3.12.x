/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package okhttp3.internal.cache;

import java.io.IOException;

import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.Internal;
import okhttp3.internal.Util;
import okhttp3.internal.http.HttpCodec;
import okhttp3.internal.http.HttpHeaders;
import okhttp3.internal.http.HttpMethod;
import okhttp3.internal.http.RealResponseBody;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;
import okio.Sink;
import okio.Source;
import okio.Timeout;

import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static okhttp3.internal.Util.closeQuietly;
import static okhttp3.internal.Util.discard;

/**
 * Serves requests from the cache and writes responses to the cache.
 * 这个拦截器主要工作是做做缓存处理，如果有有缓存并且缓存可用，那就使用缓存，
 * 否则进行调用下一个拦截器 ConnectionInterceptor 进行网络请求，并将响应内容缓存。
 */
public final class CacheInterceptor implements Interceptor {
    //cache是InternalCache类型，只是一个接口
    final InternalCache cache;

    //构造器注入
    public CacheInterceptor(InternalCache cache) {
        this.cache = cache;
    }

    /**
     * 1.通过Request尝试到Cache中拿缓存（里面非常多流程），当然前提是OkHttpClient中配置了缓存，默认是不支持的。
     * 2.根据response,time,request创建一个缓存策略，用于判断怎样使用缓存。
     * 3.如果缓存策略中设置禁止使用网络，并且缓存又为空，则构建一个Resposne直接返回，注意返回码=504
     * 4.缓存策略中设置不使用网络，但是又缓存，直接返回缓存
     * 5.接着走后续过滤器的流程，chain.proceed(networkRequest)
     * 6.当缓存存在的时候，如果网络返回的Resposne为304，则使用缓存的Resposne。
     * 7.构建网络请求的Resposne
     * 8.当在OKHttpClient中配置了缓存，则将这个Resposne缓存起来。
     * 9.缓存起来的步骤也是先缓存header，再缓存body。
     * 10.返回Resposne。
     *
     *
     * @param chain
     * @return
     * @throws IOException
     */
    @Override
    public Response intercept(Chain chain) throws IOException {
        //1.尝试通过这个Request拿缓存
        //默认cache为null,可以配置cache,不为空尝试获取缓存中的response
        Response cacheCandidate = cache != null
                ? cache.get(chain.request())
                : null;

        long now = System.currentTimeMillis();

        //根据response,time,request创建一个缓存策略，用于判断怎样使用缓存
        //对应的是CacheStrategy这个类，里面主要涉及Http协议中缓存的相关设置
        CacheStrategy strategy = new CacheStrategy.Factory(now, chain.request(), cacheCandidate).get();
        Request networkRequest = strategy.networkRequest;
        Response cacheResponse = strategy.cacheResponse;

        if (cache != null) {
            cache.trackResponse(strategy);
        }

        if (cacheCandidate != null && cacheResponse == null) {
            closeQuietly(cacheCandidate.body()); // The cache candidate wasn't applicable. Close it.
        }

        // If we're forbidden from using the network and the cache is insufficient, fail.
        //2.如果不允许使用网络并且缓存为空，新建一个504的Resposne返回。
        //如果缓存策略中禁止使用网络，并且缓存又为空，则构建一个Resposne直接返回，注意返回码=504
        if (networkRequest == null && cacheResponse == null) {
            return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(504)
                    .message("Unsatisfiable Request (only-if-cached)")
                    .body(Util.EMPTY_RESPONSE)
                    .sentRequestAtMillis(-1L)
                    .receivedResponseAtMillis(System.currentTimeMillis())
                    .build();
        }

        // If we don't need the network, we're done.
        //不使用网络，但是又缓存，直接返回缓存
        if (networkRequest == null) {
            return cacheResponse.newBuilder()
                    .cacheResponse(stripBody(cacheResponse))
                    .build();
        }

        Response networkResponse = null;
        try {
            //调用下一个拦截器进行网络请求
            //3.如果不允许使用网络，但是有缓存，返回缓存。
            networkResponse = chain.proceed(networkRequest);
        } finally {
            // If we're crashing on I/O or otherwise, don't leak the cache body.
            if (networkResponse == null && cacheCandidate != null) {
                closeQuietly(cacheCandidate.body());
            }
        }

        // If we have a cache response too, then we're doing a conditional get.
        //4.链式调用下一个过滤器。
        networkResponse = chain.proceed(networkRequest);

        //当缓存响应和网络响应同时存在的时候，选择用哪个
        if (cacheResponse != null) {
            if (networkResponse.code() == HTTP_NOT_MODIFIED) {
                //如果返回码是304，客户端有缓冲的文档并发出了一个条件性的请求（一般是提供If-Modified-Since头表示客户
                // 只想比指定日期更新的文档）。服务器告诉客户，原来缓冲的文档还可以继续使用。
                //则使用缓存的响应
                Response response = cacheResponse.newBuilder()
                        .headers(combine(cacheResponse.headers(), networkResponse.headers()))
                        .sentRequestAtMillis(networkResponse.sentRequestAtMillis())
                        .receivedResponseAtMillis(networkResponse.receivedResponseAtMillis())
                        .cacheResponse(stripBody(cacheResponse))
                        .networkResponse(stripBody(networkResponse))
                        .build();
                networkResponse.body().close();

                // Update the cache after combining headers but before stripping the
                // Content-Encoding header (as performed by initContentStream()).
                cache.trackConditionalCacheHit();
                cache.update(cacheResponse, response);
                return response;
            } else {
                closeQuietly(cacheResponse.body());
            }
        }

        //使用网络响应
        //6、7.使用网络请求得到的Resposne，并且将这个Resposne缓存起来（前提当然是能缓存）。
        // 接下来就是脑袋都大的细节了，我也不敢说分析的十分详细，只能就我的理解总体分析，学习。
        Response response = networkResponse.newBuilder()
                .cacheResponse(stripBody(cacheResponse))
                .networkResponse(stripBody(networkResponse))
                .build();

        //所以默认创建的OkHttpClient是没有缓存的
        //存缓存这个步骤的分析了，其实前面的取缓存的分析结束后，
        // 这里对存缓存不难猜测其实是想对应的，也就比较好理解了，对应的大体应该是header存入clean[0],body存入clean[1]。
        if (cache != null) {
            //9。 将响应缓存
            if (HttpHeaders.hasBody(response) && CacheStrategy.isCacheable(response, networkRequest)) {
                // Offer this request to the cache.
                //缓存Resposne的Header信息
                CacheRequest cacheRequest = cache.put(response);
                //缓存body
                return cacheWritingResponse(cacheRequest, response);
            }

            //只能缓存GET....不然移除request
            if (HttpMethod.invalidatesCache(networkRequest.method())) {
                try {
                    cache.remove(networkRequest);
                } catch (IOException ignored) {
                    // The cache cannot be written.
                }
            }
        }

        return response;
    }

    private static Response stripBody(Response response) {
        return response != null && response.body() != null
                ? response.newBuilder().body(null).build()
                : response;
    }

    /**
     * Returns a new source that writes bytes to {@code cacheRequest} as they are read by the source
     * consumer. This is careful to discard bytes left over when the stream is closed; otherwise we
     * may never exhaust the source stream and therefore not complete the cached response.
     */
    private Response cacheWritingResponse(final CacheRequest cacheRequest, Response response)
            throws IOException {
        // Some apps return a null body; for compatibility we treat that like a null cache request.
        if (cacheRequest == null) return response;
        Sink cacheBodyUnbuffered = cacheRequest.body();
        if (cacheBodyUnbuffered == null) return response;
        //1.获得Resposne中的body
        final BufferedSource source = response.body().source();
        final BufferedSink cacheBody = Okio.buffer(cacheBodyUnbuffered);

        Source cacheWritingSource = new Source() {
            boolean cacheRequestClosed;

            @Override public long read(Buffer sink, long byteCount) throws IOException {
                long bytesRead;
                try {
                    bytesRead = source.read(sink, byteCount);
                } catch (IOException e) {
                    if (!cacheRequestClosed) {
                        cacheRequestClosed = true;
                        cacheRequest.abort(); // Failed to write a complete cache response.
                    }
                    throw e;
                }

                if (bytesRead == -1) {
                    if (!cacheRequestClosed) {
                        cacheRequestClosed = true;
                        cacheBody.close(); // The cache response is complete!
                    }
                    return -1;
                }
                //读的时候会将body写入
                //2.将Resposne中获得的body写入缓存中，也就是刚在拿到的dirtyfile[1]
                //bytesRead就是读的Body，最后利用sink.copyTo，写入cacheBody.buffer()中，也就是刚在拿到的dirtyfile[1]。
                sink.copyTo(cacheBody.buffer(), sink.size() - bytesRead, bytesRead);
                cacheBody.emitCompleteSegments();
                return bytesRead;
            }

            @Override public Timeout timeout() {
                return source.timeout();
            }

            @Override public void close() throws IOException {
                if (!cacheRequestClosed
                        && !discard(this, HttpCodec.DISCARD_STREAM_TIMEOUT_MILLIS, MILLISECONDS)) {
                    cacheRequestClosed = true;
                    //关闭的时候会执行commit操作，最终合并header和body，完成缓存
                    //3.在close中会执行abort操作，对应的里面会执行commit的操作，会将dirtyfile写入cleanfile中，完成持久化保存。
                    cacheRequest.abort();
                }
                source.close();
            }
        };

        String contentType = response.header("Content-Type");
        long contentLength = response.body().contentLength();
        return response.newBuilder()
                .body(new RealResponseBody(contentType, contentLength, Okio.buffer(cacheWritingSource)))
                .build();
    }

    /**
     * Combines cached headers with a network headers as defined by RFC 7234, 4.3.4.
     */
    private static Headers combine(Headers cachedHeaders, Headers networkHeaders) {
        Headers.Builder result = new Headers.Builder();

        for (int i = 0, size = cachedHeaders.size(); i < size; i++) {
            String fieldName = cachedHeaders.name(i);
            String value = cachedHeaders.value(i);
            if ("Warning".equalsIgnoreCase(fieldName) && value.startsWith("1")) {
                continue; // Drop 100-level freshness warnings.
            }
            if (isContentSpecificHeader(fieldName) || !isEndToEnd(fieldName)
                    || networkHeaders.get(fieldName) == null) {
                Internal.instance.addLenient(result, fieldName, value);
            }
        }

        for (int i = 0, size = networkHeaders.size(); i < size; i++) {
            String fieldName = networkHeaders.name(i);
            if (!isContentSpecificHeader(fieldName) && isEndToEnd(fieldName)) {
                Internal.instance.addLenient(result, fieldName, networkHeaders.value(i));
            }
        }

        return result.build();
    }

    /**
     * Returns true if {@code fieldName} is an end-to-end HTTP header, as defined by RFC 2616,
     * 13.5.1.
     */
    static boolean isEndToEnd(String fieldName) {
        return !"Connection".equalsIgnoreCase(fieldName)
                && !"Keep-Alive".equalsIgnoreCase(fieldName)
                && !"Proxy-Authenticate".equalsIgnoreCase(fieldName)
                && !"Proxy-Authorization".equalsIgnoreCase(fieldName)
                && !"TE".equalsIgnoreCase(fieldName)
                && !"Trailers".equalsIgnoreCase(fieldName)
                && !"Transfer-Encoding".equalsIgnoreCase(fieldName)
                && !"Upgrade".equalsIgnoreCase(fieldName);
    }

    /**
     * Returns true if {@code fieldName} is content specific and therefore should always be used
     * from cached headers.
     */
    static boolean isContentSpecificHeader(String fieldName) {
        return "Content-Length".equalsIgnoreCase(fieldName)
                || "Content-Encoding".equalsIgnoreCase(fieldName)
                || "Content-Type".equalsIgnoreCase(fieldName);
    }
}
