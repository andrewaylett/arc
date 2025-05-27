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
import org.checkerframework.checker.lock.qual.EnsuresLockHeld;
import org.checkerframework.checker.lock.qual.GuardSatisfied;
import org.checkerframework.checker.lock.qual.Holding;
import org.checkerframework.checker.lock.qual.MayReleaseLocks;
import org.checkerframework.checker.lock.qual.ReleasesNoLocks;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.dataflow.qual.Pure;
import org.jspecify.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
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
  private final Lock lock = new ReentrantLock();
  /** The key associated with this element. */
  private final K key;

  /** The function used to load values. */
  private final Function<? super K, V> loader;

  private final Function<Supplier<V>, CompletableFuture<V>> pool;
  private final UnownedElementList unowned;

  /**
   * A weak reference to the value associated with this element, if it's been
   * computed.
   */
  private @Nullable WeakReference<@Nullable V> weakValue;

  /** A CompletableFuture representing the value associated with this element. */
  private @Nullable CompletableFuture<V> value;

  private ElementList owner;
  private @Nullable DelayedElement currentDelayedElement = null;
  private int ownerRefCount = 0;

  @SuppressFBWarnings("EI2")
  public Element(K key, Function<? super K, V> loader, Function<Supplier<V>, CompletableFuture<V>> pool,
      UnownedElementList owner) {
    this.key = key;
    this.loader = loader;
    this.pool = pool;
    this.owner = owner;
    this.unowned = owner;
  }

  @EnsuresLockHeld("this.lock")
  public void lock() {
    lock.lock();
  }

  @MayReleaseLocks
  public void unlock() {
    lock.unlock();
  }

  /**
   * Retrieves the value associated with this element. If the value is not
   * present, it uses the loader function to load the value.
   */
  @Holding("this.lock")
  @ReleasesNoLocks
  public CompletableFuture<V> get() {
    owner.processElement(this);
    var currentOwner = this.owner;
    if (currentOwner == unowned) {
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
      }
      return currentValue.copy();
    }
    var currentWeakValue = this.weakValue;
    if (currentWeakValue != null) {
      var v = currentWeakValue.get();
      if (v != null) {
        return (this.value = CompletableFuture.completedFuture(v));
      } else {
        this.weakValue = null;
      }
    }
    return load().copy();
  }

  @Holding("this.lock")
  @ReleasesNoLocks
  public boolean containsValue() {
    return value != null;
  }

  @Holding("this.lock")
  @ReleasesNoLocks
  boolean containsWeakValue() {
    var weakValue = this.weakValue;
    return weakValue != null && !weakValue.refersTo(null);
  }

  @Pure
  @Holding("this.lock")
  ElementList getOwner() {
    return owner;
  }

  @Holding("this.lock")
  @ReleasesNoLocks
  CompletableFuture<V> load() {
    var value = pool.apply(() -> loader.apply(key));
    value.thenRun(this::lockAndResetDelay);
    this.value = value;
    return value;
  }

  @Holding("this.lock")
  @ReleasesNoLocks
  private void resetDelay() {
    this.currentDelayedElement = owner.delayManage(this);
  }

  @Holding("this.lock")
  @ReleasesNoLocks
  boolean addRef(ElementList fromOwner) {
    var oldOwner = this.owner;
    if (oldOwner != fromOwner) {
      // Let our previous owner know that we're being added to a different list
      oldOwner.noteRemovedElement();
      owner = fromOwner;
      ownerRefCount = 1;
      return true;
    } else {
      ownerRefCount++;
      return false;
    }
  }

  @Holding("this.lock")
  @ReleasesNoLocks
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
  @Holding("this.lock")
  void expire() {
    value = null;
    unowned.push(this);
  }

  /**
   * Removes the weak reference if no strong reference remains here, pretending
   * that the GC has cleared the value.
   * <p>
   * Primarily useful for testing.
   */
  @Holding("this.lock")
  @ReleasesNoLocks
  public void weakExpire() {
    if (this.value == null) {
      // No strong reference, so a GC may clear the value -- and we pretend it has.
      weakValue = null;
    }
  }

  int refCount() {
    return ownerRefCount;
  }

  @Holding("this.lock")
  @ReleasesNoLocks
  public void delayExpired(DelayedElement delayedElement) {
    if (delayedElement == currentDelayedElement) {
      value = null;
      weakValue = null;
      unowned.push(this);
    }
  }

  @Holding("this.lock")
  @ReleasesNoLocks
  public void reload() {
    if (this.owner.name == ListId.SEEN_MULTI_LRU) {
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

  /**
   * To be run in a separate thread to load the value asynchronously.
   */
  @MayReleaseLocks
  private void lockAndResetDelay() {
    this.lock();
    try {
      resetDelay();
    } finally {
      this.unlock();
    }
  }
}
