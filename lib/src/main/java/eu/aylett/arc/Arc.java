/*
 * Copyright 2024-2025 Andrew Aylett
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.aylett.arc;

import eu.aylett.arc.internal.DelayManager;
import eu.aylett.arc.internal.Element;
import eu.aylett.arc.internal.InnerArc;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.ref.SoftReference;
import java.time.Clock;
import java.time.Duration;
import java.time.InstantSource;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The Arc class provides a cache mechanism with a specified capacity. It uses a
 * loader function to load values and a ForkJoinPool for parallel processing.
 *
 * @param <K>
 *          the type of keys maintained by this cache
 * @param <V>
 *          the type of mapped values
 */
public final class Arc<K extends @NonNull Object, V extends @NonNull Object> {

  /**
   * The function used to load values.
   */
  private final Function<? super K, V> loader;

  /**
   * The ForkJoinPool used for parallel processing.
   */
  private final ForkJoinPool pool;

  /**
   * Constructs a new Arc with the specified capacity, loader function, and the
   * common ForkJoinPool.
   *
   * @param capacity
   *          the maximum number of elements the cache can hold
   * @param loader
   *          the function to load values
   * @param expiry
   *          how long after finishing loading a value it should be discarded
   * @param refresh
   *          if a value is used more than once in the refresh interval after
   *          loading, we will refresh it
   */
  public Arc(int capacity, Function<K, V> loader, Duration expiry, Duration refresh) {
    this(capacity, loader, ForkJoinPool.commonPool(), false, expiry, refresh, Clock.systemUTC());
  }

  /**
   * Constructs a new Arc with the specified capacity, loader function, and
   * ForkJoinPool.
   *
   * @param capacity
   *          the maximum number of elements the cache can hold
   * @param loader
   *          the function to load values
   * @param pool
   *          the ForkJoinPool that the loader will be submitted to
   */
  public Arc(int capacity, Function<? super K, V> loader, ForkJoinPool pool) {
    this(capacity, loader, Duration.ofSeconds(60), Duration.ofSeconds(30), pool);
  }

  /**
   * Constructs a new Arc with the specified capacity, loader function, and
   * ForkJoinPool.
   *
   * @param capacity
   *          the maximum number of elements the cache can hold
   * @param loader
   *          the function to load values
   * @param pool
   *          the ForkJoinPool that the loader will be submitted to
   */
  public Arc(int capacity, Function<? super K, V> loader, Duration expiry, Duration refresh, ForkJoinPool pool) {
    this(capacity, loader, pool, false, expiry, refresh, Clock.systemUTC());
  }

  Arc(int capacity, Function<? super K, V> loader, ForkJoinPool pool, boolean safetyChecks, Duration expiry,
      Duration refresh, InstantSource clock) {
    if (capacity < 1) {
      throw new IllegalArgumentException("Capacity must be at least 1");
    }
    if (expiry.compareTo(refresh) < 0) {
      throw new IllegalArgumentException("Expiry must be greater than refresh");
    }
    if (!expiry.isPositive()) {
      throw new IllegalArgumentException("Expiry must be positive");
    }
    if (!refresh.isPositive()) {
      throw new IllegalArgumentException("Refresh must be positive");
    }
    this.loader = loader;
    this.pool = pool;
    elements = new ConcurrentHashMap<>();
    inner = new InnerArc(Math.max(capacity / 2, 1), safetyChecks, new DelayManager(expiry, refresh, clock));
  }

  /**
   * The map of elements stored in the cache.
   */
  private final ConcurrentHashMap<K, SoftReference<@Nullable Element<K, V>>> elements;

  /**
   * The inner cache mechanism.
   */
  private final InnerArc<K, V> inner;

  /**
   * Removes all the weak references, so objects that have expired out of the
   * strong cache will be regenerated.
   * <p>
   * Without this, we may retain references to expired objects that have yet to be
   * GC'd. Primarily useful for testing.
   */
  public void weakExpire() {
    elements.values().forEach(elementSoftReference -> {
      var element = elementSoftReference.get();
      if (element != null) {
        element.weakExpire();
      }
    });
  }

  /**
   * Retrieves the value associated with the specified key. If the key is not
   * present, it uses the loader function to load the value.
   *
   * @param key
   *          the key whose associated value is to be returned
   * @return the value associated with the specified key
   */
  public V get(K key) {
    while (true) {
      var ref = elements.computeIfAbsent(key, k -> inner.createElement(k, loader, pool));
      var e = ref.get();
      if (e == null) {
        // Remove if expired and not already removed/replaced
        elements.computeIfPresent(key,
            new BiFunction<K, SoftReference<@Nullable Element<K, V>>, @Nullable SoftReference<@Nullable Element<K, V>>>() {
              @Override
              public @Nullable SoftReference<@Nullable Element<K, V>> apply(K k,
                  SoftReference<@Nullable Element<K, V>> v) {
                if (v.refersTo(null)) {
                  return null;
                }
                return v;
              }
            });
        continue;
      }
      CompletableFuture<V> completableFuture;
      synchronized (inner) {
        completableFuture = inner.processElement(e);
      }
      return completableFuture.join();
    }
  }
}
