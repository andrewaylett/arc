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

import org.checkerframework.checker.initialization.qual.NotOnlyInitialized;
import org.checkerframework.checker.initialization.qual.UnderInitialization;
import org.checkerframework.checker.lock.qual.GuardSatisfied;
import org.checkerframework.checker.lock.qual.Holding;
import org.checkerframework.checker.lock.qual.LockingFree;
import org.checkerframework.checker.lock.qual.MayReleaseLocks;
import org.checkerframework.checker.lock.qual.ReleasesNoLocks;
import org.checkerframework.dataflow.qual.SideEffectFree;

import java.util.HashMap;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.base.Verify.verify;

/**
 * The ElementList class represents a queue used to manage elements in the
 * cache. It supports operations to grow, shrink, and evict elements based on
 * the list's capacity.
 */
public class LRUElementList extends ElementList {

  /** The maximum capacity to set. */
  private final int maxCapacity;

  /**
   * The maximum number of elements the list may hold.
   */
  private final AtomicInteger capacity;

  /** The target list for expired elements. */
  private final @NotOnlyInitialized ExpiredElementList expiryTarget;

  private final Queue<Element<?, ?>> queue;

  /**
   * The current number of elements in the list.
   */
  private final AtomicInteger size = new AtomicInteger(0);

  @LockingFree
  LRUElementList(ListId name, int capacity, @UnderInitialization ExpiredElementList expiryTarget,
      @UnderInitialization InnerArc inner) {
    super(name, inner);
    this.capacity = new AtomicInteger(capacity);
    this.maxCapacity = (capacity * 2) - 1;
    this.expiryTarget = expiryTarget;
    this.queue = new ConcurrentLinkedQueue<>();
  }

  @MayReleaseLocks
  @Override
  void checkSafety() {
    var seen = new HashMap<Element<?, ?>, Integer>();
    var startSize = this.size.get();
    for (var element : queue) {
      var release = element.lock();
      try {
        var owner = element.getOwner();
        if (owner != this) {
          continue;
        }
        seen.compute(element, (k, v) -> v == null ? 1 : v + 1);
        verify(element.containsValue() || !element.containsWeakValue(), "Element in LRU list has only weak value: %s",
            element);
      } finally {
        element.unlock(release);
      }
    }
    verify(seen.size() == startSize, "Size mismatch: found %s items != expected %s", seen.size(), startSize);
    seen.forEach((k, v) -> {
      verify(k.refCount() <= v, "Element %s has ref count of %s > expected %s", k, k.refCount(), v);
    });

    verify(size.get() <= capacity.get(), "Size of %s exceeds capacity of %s: %s", size, capacity, this);
  }

  /**
   * Increases the capacity of the list and adds a new element.
   *
   * @param newElement
   *          the new element to add
   */
  @ReleasesNoLocks
  @Holding("#1.lock")
  void grow(Element<?, ?> newElement) {
    this.capacity.updateAndGet(current -> Math.min(current + 1, this.maxCapacity));
    push(newElement);
  }

  /**
   * Adds a new element to the list. If the list exceeds its capacity, it evicts
   * the least recently used element.
   *
   * @param newElement
   *          the new element to add
   */
  @ReleasesNoLocks
  @Override
  @Holding("#1.lock")
  public void push(Element<?, ?> newElement) {
    var newlyAdded = newElement.addRef(this);
    queue.add(newElement);
    if (newlyAdded) {
      size.incrementAndGet();
    }
  }

  /** Decreases the capacity of the list and evicts elements if necessary. */
  @ReleasesNoLocks
  boolean shrink() {
    int oldCapacity;
    int newCapacity;
    do {
      oldCapacity = this.capacity.getAcquire();
      if (oldCapacity <= 1) {
        return false; // Cannot shrink below 1
      }
      newCapacity = oldCapacity - 1;
    } while (!this.capacity.weakCompareAndSetRelease(oldCapacity, newCapacity));
    return true;
  }

  /** Evicts the least recently used element if the list exceeds its capacity. */
  @MayReleaseLocks
  @Override
  void evict() {
    do {
      var capacity = this.capacity.getAcquire();
      var size = this.size.decrementAndGet();
      if (size < capacity) {
        this.size.incrementAndGet();
        return;
      }
      var victim = queue.remove();
      var release = victim.lock();
      try {
        var expired = victim.removeRef(this);
        if (expired) {
          expiryTarget.push(victim);
        }
        this.size.incrementAndGet();
      } finally {
        victim.unlock(release);
      }
    } while (true);
  }

  @SideEffectFree
  @Override
  public String toString(@GuardSatisfied LRUElementList this) {
    return "ElementList-" + this.name + " (" + this.size + "/" + this.capacity + ")";
  }

  @ReleasesNoLocks
  int getCapacity() {
    return this.capacity.get();
  }

  @Override
  public void noteRemovedElement() {
    this.size.decrementAndGet();
  }

  @ReleasesNoLocks
  @Holding("#1.lock")
  public void processElement(Element<?, ?> e) {
    inner.seenMultiLRU.push(e);
  }
}
