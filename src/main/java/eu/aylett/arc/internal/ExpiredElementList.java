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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Verify.verify;

/**
 * The ElementList class represents a queue used to manage elements in the
 * cache. It supports operations to grow, shrink, and evict elements based on
 * the list's capacity.
 */
abstract class ExpiredElementList extends ElementList {

  /** The maximum number of elements the list may hold. */
  private final int capacity;

  private final Queue<Element<?, ?>> queue;

  /** The current number of elements in the list. */
  private final AtomicInteger size = new AtomicInteger(0);

  @LockingFree
  ExpiredElementList(ListId name, int capacity, @UnderInitialization InnerArc inner) {
    super(name, inner);
    this.capacity = capacity;
    this.queue = new ConcurrentLinkedQueue<>();
  }

  @MayReleaseLocks
  @Override
  void checkSafety() {
    var seen = new HashMap<Element<?, ?>, Integer>();
    var initialSize = this.size.get();
    for (var element : queue) {
      var release = element.lock();
      try {
        var owner = element.getOwner();
        if (owner != this) {
          continue;
        }
        seen.compute(element, (k, v) -> v == null ? 1 : v + 1);
        verify(!element.containsValue(), "Element in expired list has a value: %s", element);
      } finally {
        element.unlock(release);
      }
    }
    verify(seen.size() == initialSize, "Size mismatch: found %s items != expected %s", seen.size(), this.size);
    seen.forEach((k, v) -> {
      verify(k.refCount() <= v, "Element %s has ref count of %s > expected %s", k, k.refCount(), v);
    });

    verify(initialSize <= capacity, "Size of %s exceeds capacity of %s: %s", size, capacity, this);
  }

  /**
   * Adds a new element to the list. If the list exceeds its capacity, it evicts
   * the least recently used element.
   *
   * @param newElement
   *          the new element to add
   */
  @ReleasesNoLocks
  @Holding("#1.lock")
  @Override
  void push(Element<?, ?> newElement) {
    checkArgument(!newElement.containsValue(), "Attempted to add an element with a value to an expired list: %s",
        newElement);
    var newlyAdded = newElement.addRef(this);
    queue.add(newElement);
    if (newlyAdded) {
      size.incrementAndGet();
    }
  }

  /** Evicts the least recently used element if the list exceeds its capacity. */
  @MayReleaseLocks
  @Override
  void evict() {
    while (true) {
      var decrementedSize = size.decrementAndGet();
      if (decrementedSize < capacity) {
        size.incrementAndGet();
        return;
      }

      var victim = queue.remove();
      var release = victim.lock();
      try {
        victim.removeRef(this);
        size.incrementAndGet();
      } finally {
        victim.unlock(release);
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
    this.size.decrementAndGet();
  }

  @ReleasesNoLocks
  @Holding("#1.lock")
  public abstract void processElement(Element<?, ?> e);

  public static class SeenOnce extends ExpiredElementList {
    public SeenOnce(int capacity, @UnderInitialization InnerArc inner) {
      super(ListId.SEEN_ONCE_EXPIRING, capacity, inner);
    }

    @ReleasesNoLocks
    @Holding("#1.lock")
    @Override
    public void processElement(Element<?, ?> e) {
      inner.enqueueSeenOnceElement(e);
    }
  }

  public static class SeenMulti extends ExpiredElementList {
    public SeenMulti(int capacity, @UnderInitialization InnerArc inner) {
      super(ListId.SEEN_MULTI_EXPIRING, capacity, inner);
    }

    @ReleasesNoLocks
    @Holding("#1.lock")
    @Override
    public void processElement(Element<?, ?> e) {
      inner.enqueueSeenMultiElement(e);
    }
  }
}
