/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal;

import java.io.IOException;
import java.net.Socket;
import javax.annotation.Nullable;
import javax.net.ssl.SSLSocket;
import okhttp3.Address;
import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.ConnectionSpec;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;
import okhttp3.internal.cache.InternalCache;
import okhttp3.internal.connection.RealConnection;
import okhttp3.internal.connection.RouteDatabase;
import okhttp3.internal.connection.StreamAllocation;

/**
 * Escalate internal APIs in {@code okhttp3} so they can be used from OkHttp's implementation
 * packages. The only implementation of this interface is in {@link OkHttpClient}.
 * 通过查看源码可以发现是一个抽象类，并且实际上调用的是instance对象，
 * 看到这个名词其实第一个反应就是单例模式，源码上看也没错,这里确实是单例模式中的类似饿汉模式。
 * 但是不同的是这里的初始化并没有在这里写，其实也难怪，这个类是抽象类，是不能初始化的，
 * 所以这里我们就需要找到这个抽象类的实现类。通过寻找可以发现这个类的实现类的初始化是在OkHttpClient中
 *
 *
 */
public abstract class Internal {

  public static void initializeInstanceForTests() {
    // Needed in tests to ensure that the instance is actually pointing to something.
    new OkHttpClient();
  }

  public static Internal instance;

  public abstract void addLenient(Headers.Builder builder, String line);

  public abstract void addLenient(Headers.Builder builder, String name, String value);

  public abstract void setCache(OkHttpClient.Builder builder, InternalCache internalCache);

  public abstract RealConnection get(ConnectionPool pool, Address address,
      StreamAllocation streamAllocation, Route route);

  public abstract boolean equalsNonHost(Address a, Address b);

  public abstract Socket deduplicate(
      ConnectionPool pool, Address address, StreamAllocation streamAllocation);

  public abstract void put(ConnectionPool pool, RealConnection connection);

  public abstract boolean connectionBecameIdle(ConnectionPool pool, RealConnection connection);

  public abstract RouteDatabase routeDatabase(ConnectionPool connectionPool);

  public abstract int code(Response.Builder responseBuilder);

  public abstract void apply(ConnectionSpec tlsConfiguration, SSLSocket sslSocket,
      boolean isFallback);

  public abstract boolean isInvalidHttpUrlHost(IllegalArgumentException e);

  public abstract StreamAllocation streamAllocation(Call call);

  public abstract @Nullable IOException timeoutExit(Call call, @Nullable IOException e);

  public abstract Call newWebSocketCall(OkHttpClient client, Request request);
}
