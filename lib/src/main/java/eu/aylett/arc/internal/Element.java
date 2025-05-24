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
import org.checkerframework.checker.lock.qual.GuardSatisfied;
import org.checkerframework.checker.lock.qual.GuardedBy;
import org.checkerframework.checker.lock.qual.LockingFree;
import org.checkerframework.checker.lock.qual.ReleasesNoLocks;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.dataflow.qual.SideEffectFree;
import org.jspecify.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

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
  private final Function<? super K, V> loader;

  private final Function<Supplier<V>, CompletableFuture<V>> pool;

  private final DelayManager delayManager;

  /**
   * A weak reference to the value associated with this element, if it's been
   * computed.
   */
  private @Nullable WeakReference<@Nullable V> weakValue;

  /** A CompletableFuture representing the value associated with this element. */
  private @Nullable CompletableFuture<V> value;

  private @Nullable @GuardedBy ElementList owner;
  private @Nullable DelayedElement currentDelayedElement = null;
  private int ownerRefCount = 0;

  @LockingFree
  @SuppressFBWarnings("EI2")
  public Element(K key, Function<? super K, V> loader, Function<Supplier<V>, CompletableFuture<V>> pool,
      DelayManager delayManager) {
    this.key = key;
    this.loader = loader;
    this.pool = pool;
    this.delayManager = delayManager;
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
    if (currentOwner.isForExpiredElements()) {
      throw new IllegalStateException("Called get on an object in an expired list");
    }

    var currentValue = this.value;
    if (currentValue != null) {
      if (currentValue.isDone()) {
        var v = currentValue.join();
        var currentWeakValue = this.weakValue;
        if (currentWeakValue == null || !currentWeakValue.refersTo(v)) {
          this.weakValue = new WeakReference<>(v);
        }
        return currentValue;
      }
    }
    var currentWeakValue = this.weakValue;
    if (currentWeakValue != null) {
      var v = currentWeakValue.get();
      if (v != null) {
        if (currentValue == null) {
          this.value = currentValue = CompletableFuture.completedFuture(v);
          return currentValue;
        }
      } else {
        this.weakValue = null;
      }
    }
    if (currentValue == null) {
      currentValue = load();
    }
    return currentValue;
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
  @GuardedBy @Nullable ElementList getOwner() {
    return owner;
  }

  CompletableFuture<V> load() {
    var value = pool.apply(() -> {
      var val = loader.apply(key);
      resetDelay();
      return val;
    });
    this.value = value;
    return value;
  }

  private void resetDelay() {
    this.currentDelayedElement = delayManager.add(this);
  }

  boolean addRef(@GuardSatisfied Element<K, V> this, @GuardedBy ElementList fromOwner) {
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

  boolean removeRef(ElementList fromOwner) {
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
   * Removes the weak reference if no strong reference remains here, pretending
   * that the GC has cleared the value.
   * <p>
   * Primarily useful for testing.
   */
  @LockingFree
  public void weakExpire() {
    if (this.value == null) {
      // No strong reference, so a GC may clear the value -- and we pretend it has.
      weakValue = null;
    }
  }

  int refCount() {
    return ownerRefCount;
  }

  public void delayExpired(DelayedElement delayedElement) {
    if (delayedElement == currentDelayedElement) {
      value = null;
      weakValue = null;
      var currentOwner = this.owner;
      if (currentOwner != null) {
        currentOwner.noteRemovedElement();
        this.owner = null;
      }
    }
  }

  public void reload() {
    var currentOwner = this.owner;
    if (currentOwner != null && currentOwner.name == ElementList.Name.SEEN_MULTI_LRU) {
      load();
    }
  }

  @Override
  public String toString(@GuardSatisfied Element<K, V> this) {
    var currentWeakValue = weakValue;
    var weakValueString = currentWeakValue == null ? "null" : currentWeakValue.get();
    return "Element{" + "key=" + key + ", value=" + value + ", weakValue=" + weakValueString + ", owner=" + owner
        + ", ownerRefCount=" + ownerRefCount + '}';
  }
}
