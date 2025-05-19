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

package eu.aylett.arc.internal;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.checkerframework.checker.lock.qual.LockingFree;
import org.checkerframework.checker.lock.qual.ReleasesNoLocks;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.dataflow.qual.SideEffectFree;
import org.jspecify.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;

/**
 * The Element class represents an element in the cache. It manages the value
 * associated with a key and its position in the cache's linked lists.
 *
 * @param <K>
 *          the type of keys maintained by this cache
 * @param <V>
 *          the type of mapped values
 */
public final class Element<K extends @NonNull Object, V extends @NonNull Object> {
  /** The key associated with this element. */
  private final K key;

  /** The function used to load values. */
  private final Function<K, V> loader;

  private final ForkJoinPool pool;

  /**
   * A weak reference to the value associated with this element, if it's been
   * computed.
   */
  private @Nullable WeakReference<@Nullable V> weakValue;

  /** A CompletableFuture representing the value associated with this element. */
  private @Nullable CompletableFuture<V> value;

  private @Nullable ElementList<K, V> owner;
  private int ownerRefCount = 0;

  @LockingFree
  @SuppressFBWarnings("EI_EXPOSE_REP")
  public Element(K key, Function<K, V> loader, ForkJoinPool pool) {
    this.key = key;
    this.loader = loader;
    this.pool = pool;
  }

  /**
   * Retrieves the value associated with this element. If the value is not
   * present, it uses the loader function to load the value.
   */
  @ReleasesNoLocks
  CompletableFuture<V> get() {
    var currentOwner = this.owner;
    if (currentOwner == null) {
      throw new IllegalStateException("Called get on an element with no owner");
    }
    if (!currentOwner.containsValues()) {
      throw new IllegalStateException("Called get on an object in an expired list");
    }
    return setup();
  }

  @SideEffectFree
  public boolean containsValue() {
    return value != null;
  }

  @SideEffectFree
  boolean containsWeakValue() {
    var weakValue = this.weakValue;
    return weakValue != null && !weakValue.refersTo(null);
  }

  @SideEffectFree
  @Nullable ElementList<K, V> getOwner() {
    return owner;
  }

  @ReleasesNoLocks
  @SuppressFBWarnings("EI_EXPOSE_REP")
  CompletableFuture<V> setup() {
    var currentOwner = this.owner;
    if (currentOwner != null && !currentOwner.containsValues()) {
      throw new IllegalStateException("Called setup on an expired element");
    }
    var value = this.value;
    if (value != null) {
      if (value.isDone()) {
        var v = value.join();
        var weakValue = this.weakValue;
        if (weakValue == null || !weakValue.refersTo(v)) {
          this.weakValue = new WeakReference<>(v);
        }
      }
      return value;
    }
    var weakValue = this.weakValue;
    if (weakValue != null) {
      var v = weakValue.get();
      if (v != null) {
        this.value = value = CompletableFuture.completedFuture(v);
      } else {
        this.weakValue = null;
      }
    }
    if (value == null) {
      this.value = value = CompletableFuture.supplyAsync(() -> loader.apply(key), pool);
    }
    return value;
  }

  boolean addRef(ElementList<K, V> fromOwner) {
    var oldOwner = this.owner;
    if (oldOwner != fromOwner) {
      if (oldOwner != null) {
        // Let our previous owner know that we're being added to a different list
        oldOwner.noteRemovedElement();
      }
      owner = fromOwner;
      ownerRefCount = 1;
      return true;
    } else {
      ownerRefCount++;
      return false;
    }
  }

  boolean removeRef(ElementList<K, V> fromOwner) {
    if (owner != fromOwner) {
      // We've been added to a different list already
      return false;
    }
    ownerRefCount--;
    if (ownerRefCount == 0) {
      expire();
      return true;
    }
    return false;
  }

  /**
   * A "normal" expiry, leaving the weak reference but allowing the GC to collect
   * the object if necessary.
   */
  @ReleasesNoLocks
  void expire() {
    value = null;
    owner = null;
  }

  /**
   * Removes the weak reference, so if the entry has expired already then we'll
   * regenerate the value.
   * <p>
   * Primarily useful for testing.
   */
  @LockingFree
  public void weakExpire() {
    weakValue = null;
  }

  int refCount() {
    return ownerRefCount;
  }
}
