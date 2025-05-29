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
import eu.aylett.arc.internal.UnownedElementList;
import org.checkerframework.checker.lock.qual.MayReleaseLocks;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.ref.SoftReference;
import java.time.Clock;
import java.time.Duration;
import java.time.InstantSource;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;

import static eu.aylett.arc.internal.Invariants.checkNotNull;

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
   * The map of elements stored in the cache.
   */
  private final ConcurrentHashMap<K, SoftReference<@Nullable Element<K, V>>> elements;
  private final UnownedElementList unowned;
  private final InnerArc inner;
  private final AtomicBoolean needsEviction = new AtomicBoolean(false);

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
    this(capacity, loader, ForkJoinPool.commonPool(), expiry, refresh, Clock.systemUTC());
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
    this(capacity, loader, pool, expiry, refresh, Clock.systemUTC());
  }

  Arc(int capacity, Function<? super K, V> loader, ForkJoinPool pool, Duration expiry, Duration refresh,
      InstantSource clock) {
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
    this.loader = checkNotNull(loader);
    this.pool = checkNotNull(pool);
    elements = new ConcurrentHashMap<>();
    inner = new InnerArc(Math.max(capacity / 2, 1), new DelayManager(expiry, refresh, clock));
    unowned = inner.unowned;
  }

  /**
   * Removes all the weak references, so objects that have expired out of the
   * strong cache will be regenerated.
   * <p>
   * Without this, we may retain references to expired objects that have yet to be
   * GC'd. Primarily useful for testing.
   */
  @MayReleaseLocks
  public void weakExpire() {
    elements.values().forEach(elementSoftReference -> {
      var element = elementSoftReference.get();
      if (element != null) {
        element.lock();
        try {
          element.weakExpire();
          if (element.getOwner() instanceof UnownedElementList) {
            elements.computeIfPresent(element.key, (k, v) -> {
              v.clear();
              return v; // Keep the entry
            });
          }
        } finally {
          element.unlock();
        }
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
  @MayReleaseLocks
  public V get(K key) {
    checkNotNull(key, "key cannot be null");
    while (true) {
      var ref = elements.computeIfAbsent(key, k -> {
        var element = new Element<>(k, loader, (l) -> CompletableFuture.supplyAsync(l, pool), unowned);
        return new SoftReference<>(element);
      });
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
      e.lock();
      CompletableFuture<V> completableFuture;
      try {
        completableFuture = e.get();
        // We set this before spawning the eviction task,
        // so if there's no running eviction the value must be true when the new task
        // starts.
        needsEviction.setRelease(true);
      } finally {
        e.unlock();
      }
      pool.submit(this::runEviction);
      return completableFuture.join();
    }
  }

  @MayReleaseLocks
  public void checkSafety() {
    inner.takeEvictionLock();
    try {
      inner.evict();
      weakExpire();
      inner.checkSafety();
    } finally {
      inner.releaseEvictionLock();
    }
  }

  @MayReleaseLocks
  private void runEviction() {
    // If needsEviction is set above,
    // we will either continue to loop or we know that a new task will be started
    // and will run the eviction.
    while (needsEviction.getAcquire() && inner.tryEvictionLock()) {
      // It does not matter if this task is the one started by the most recent get()
      // call,
      // if we get the lock the other task will exit.
      try {
        // We loop while holding the lock, but there's a chance that we don't even need
        // to run eviction once.
        // If our thread paused between the get and the try, allowing a running eviction
        // to finish.
        while (needsEviction.getAcquire()) {
          // Mark false before we start, so if anything changes while we're evicting then
          // we'll run again.
          needsEviction.setRelease(false);
          // Retake the lock to satisfy the lock checker
          inner.takeEvictionLock();
          try {
            inner.evict();
          } finally {
            inner.releaseEvictionLock();
          }
        }
      } finally {
        inner.releaseEvictionLock();
        // We will loop again, re-taking the lock, if the flag is set while releasing
        // the lock, but no-one else has taken it.
      }
    }
  }
}
