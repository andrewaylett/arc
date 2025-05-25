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

import org.checkerframework.checker.lock.qual.GuardSatisfied;
import org.checkerframework.checker.lock.qual.GuardedBy;
import org.checkerframework.checker.lock.qual.LockingFree;
import org.checkerframework.checker.lock.qual.ReleasesNoLocks;
import org.checkerframework.dataflow.qual.Pure;
import org.checkerframework.dataflow.qual.SideEffectFree;

import java.util.HashMap;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The ElementList class represents a queue used to manage elements in the
 * cache. It supports operations to grow, shrink, and evict elements based on
 * the list's capacity.
 */
class LRUElementList extends ElementList {

  /** The maximum capacity to set. */
  private final int maxCapacity;

  private final boolean safetyChecks;
  /** The maximum number of elements the list may hold. */
  private int capacity;

  /** The target list for expired elements. */
  private final ExpiredElementList expiryTarget;

  private final Queue<Element<?, ?>> queue;

  /** The current number of elements in the list. */
  private int size;

  @LockingFree
  LRUElementList(Name name, int capacity, ExpiredElementList expiryTarget, boolean safetyChecks) {
    super(name);
    this.capacity = capacity;
    this.maxCapacity = (capacity * 2) - 1;
    this.expiryTarget = expiryTarget;
    this.queue = new ConcurrentLinkedQueue<>();
    this.safetyChecks = safetyChecks;
  }

  @ReleasesNoLocks
  @Override
  void checkSafety(@GuardedBy LRUElementList this, boolean sizeCheck) {
    if (!this.safetyChecks) {
      return;
    }

    var seen = new HashMap<Element<?, ?>, Integer>();
    for (var element : queue) {
      var owner = element.getOwner();
      if (owner != this) {
        continue;
      }
      seen.compute(element, (k, v) -> v == null ? 1 : v + 1);
      if (!element.containsValue() && element.containsWeakValue()) {
        throw new IllegalStateException("Element in LRU list has only weak value: " + element);
      }
    }
    if (seen.size() != this.size) {
      throw new IllegalStateException("Size mismatch: found " + seen.size() + " items != expected " + this.size);
    }
    seen.forEach((k, v) -> {
      if (k.refCount() > v) {
        throw new IllegalStateException("Element " + k + " has ref count of " + k.refCount() + " > expected " + v);
      }
    });

    if (sizeCheck && size > capacity) {
      throw new IllegalStateException("Size of " + size + " exceeds capacity of " + capacity + ": " + this);
    }
  }

  @Pure
  @Override
  boolean isForExpiredElements() {
    return false;
  }

  /**
   * Increases the capacity of the list and adds a new element.
   *
   * @param newElement
   *          the new element to add
   */
  @ReleasesNoLocks
  void grow(@GuardedBy LRUElementList this, Element<?, ?> newElement) {
    this.capacity = Math.min(this.capacity + 1, this.maxCapacity);
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
  void push(@GuardedBy LRUElementList this, Element<?, ?> newElement) {
    var newlyAdded = newElement.addRef(this);
    if (!newElement.containsValue()) {
      newElement.get();
    }
    queue.add(newElement);
    if (newlyAdded) {
      size += 1;
    }
    evict();
  }

  /** Decreases the capacity of the list and evicts elements if necessary. */
  @ReleasesNoLocks
  boolean shrink(@GuardedBy LRUElementList this) {
    var shrunk = this.capacity > 1;
    this.capacity = Math.max(this.capacity - 1, 1);
    evict();
    return shrunk;
  }

  /** Evicts the least recently used element if the list exceeds its capacity. */
  @ReleasesNoLocks
  @Override
  void evict(@GuardedBy LRUElementList this) {
    while (this.size > this.capacity) {
      checkSafety(false);
      var victim = queue.remove();
      var expired = victim.removeRef(this);
      if (expired) {
        this.size -= 1;
        expiryTarget.push(victim);
      }
    }
  }

  @SideEffectFree
  @Override
  public String toString(@GuardSatisfied LRUElementList this) {
    return "ElementList-" + this.name + " (" + this.size + "/" + this.capacity + ")";
  }

  @SideEffectFree
  int getCapacity(@GuardSatisfied LRUElementList this) {
    return this.capacity;
  }

  @Override
  public void noteRemovedElement() {
    this.size -= 1;
  }

}
