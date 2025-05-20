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
import org.checkerframework.dataflow.qual.SideEffectFree;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;

/**
 * The ElementList class represents a doubly linked list used to manage elements
 * in the cache. It supports operations to grow, shrink, and evict elements
 * based on the list's capacity.
 */
class ElementList {

  /** The maximum capacity to set. */
  private final int maxCapacity;

  private final String name;
  private final boolean safetyChecks;
  /** The maximum number of elements the list may hold. */
  private int capacity;

  /** The target list for expired elements. */
  private final @Nullable ElementList expiryTarget;

  private final Deque<Element<?, ?>> queue;

  /** The current number of elements in the list. */
  private int size;

  @LockingFree
  ElementList(String name, int capacity, @Nullable ElementList expiryTarget, boolean safetyChecks) {
    this.name = name;
    this.capacity = capacity;
    this.maxCapacity = (capacity * 2) - 1;
    this.expiryTarget = expiryTarget;
    this.queue = new ArrayDeque<>(maxCapacity);
    this.safetyChecks = safetyChecks;
  }

  @ReleasesNoLocks
  void checkSafety(@GuardedBy ElementList this, boolean sizeCheck) {
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
      if (expiryTarget != null && !element.containsValue() && element.containsWeakValue()) {
        throw new IllegalStateException("Element in LRU list has only weak value: " + element);
      }
      if (expiryTarget == null && element.containsValue()) {
        throw new IllegalStateException("Element in expired list has a value: " + element);
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

  @SideEffectFree
  boolean isForExpiredElements(@GuardSatisfied ElementList this) {
    return expiryTarget == null;
  }

  /**
   * Increases the capacity of the list and adds a new element.
   *
   * @param newElement
   *          the new element to add
   */
  @ReleasesNoLocks
  void grow(@GuardedBy ElementList this, Element<?, ?> newElement) {
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
  void push(@GuardedBy ElementList this, Element<?, ?> newElement) {
    if (expiryTarget == null && newElement.containsValue()) {
      throw new IllegalStateException("Attempted to add an element with a value to an expired list: " + newElement);
    }
    var newlyAdded = newElement.addRef(this);
    queue.addFirst(newElement);
    if (newlyAdded) {
      size += 1;
    }
    evict();
  }

  /** Decreases the capacity of the list and evicts elements if necessary. */
  @ReleasesNoLocks
  boolean shrink(@GuardedBy ElementList this) {
    var shrunk = this.capacity > 1;
    this.capacity = Math.max(this.capacity - 1, 1);
    evict();
    return shrunk;
  }

  /** Evicts the least recently used element if the list exceeds its capacity. */
  @ReleasesNoLocks
  void evict(@GuardedBy ElementList this) {
    while (this.size > this.capacity) {
      checkSafety(false);
      var victim = queue.removeLast();
      var expired = victim.removeRef(this);
      if (expired) {
        this.size -= 1;
        if (expiryTarget != null) {
          expiryTarget.push(victim);
        }
      }
    }
  }

  @SideEffectFree
  @Override
  public String toString(@GuardSatisfied ElementList this) {
    return "ElementList-" + this.name + " (" + this.size + "/" + this.capacity + ")";
  }

  @SideEffectFree
  int getCapacity(@GuardSatisfied ElementList this) {
    return this.capacity;
  }

  public void noteRemovedElement() {
    this.size -= 1;
  }
}
