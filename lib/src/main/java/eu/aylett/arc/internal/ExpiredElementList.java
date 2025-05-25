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
class ExpiredElementList extends ElementList {

  /** The maximum number of elements the list may hold. */
  private final int capacity;

  private final Queue<Element<?, ?>> queue;

  /** The current number of elements in the list. */
  private int size;

  @LockingFree
  ExpiredElementList(Name name, int capacity) {
    super(name);
    this.capacity = capacity;
    this.queue = new ConcurrentLinkedQueue<>();
  }

  @ReleasesNoLocks
  @Override
  void checkSafety(@GuardedBy ExpiredElementList this) {
    var seen = new HashMap<Element<?, ?>, Integer>();
    for (var element : queue) {
      var owner = element.getOwner();
      if (owner != this) {
        continue;
      }
      seen.compute(element, (k, v) -> v == null ? 1 : v + 1);
      if (element.containsValue()) {
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

    if (size > capacity) {
      throw new IllegalStateException("Size of " + size + " exceeds capacity of " + capacity + ": " + this);
    }
  }

  @Pure
  @Override
  boolean isForExpiredElements() {
    return true;
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
  void push(@GuardedBy ExpiredElementList this, Element<?, ?> newElement) {
    if (newElement.containsValue()) {
      throw new IllegalStateException("Attempted to add an element with a value to an expired list: " + newElement);
    }
    var newlyAdded = newElement.addRef(this);
    queue.add(newElement);
    if (newlyAdded) {
      size += 1;
    }
    evict();
  }

  /** Evicts the least recently used element if the list exceeds its capacity. */
  @ReleasesNoLocks
  @Override
  void evict(@GuardedBy ExpiredElementList this) {
    while (this.size > this.capacity) {
      var victim = queue.remove();
      var expired = victim.removeRef(this);
      if (expired) {
        this.size -= 1;
      }
    }
  }

  @SideEffectFree
  @Override
  public String toString(@GuardSatisfied ExpiredElementList this) {
    return "ElementList-" + this.name + " (" + this.size + "/" + this.capacity + ")";
  }

  @Override
  public void noteRemovedElement() {
    this.size -= 1;
  }

}
