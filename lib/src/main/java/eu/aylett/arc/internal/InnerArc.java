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
import org.checkerframework.checker.lock.qual.Holding;
import org.checkerframework.checker.lock.qual.ReleasesNoLocks;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.SideEffectFree;
import org.jspecify.annotations.NonNull;

import java.lang.ref.SoftReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;

/**
 * The InnerArc class manages the internal cache mechanism for the Arc class. It
 * maintains multiple lists to track elements based on their usage patterns.
 */
public class InnerArc {

  /**
   * The list of elements seen once, managed as a Least Recently Used (LRU) cache.
   */
  private final @GuardedBy LRUElementList seenOnceLRU;

  /**
   * Elements seen multiple times, managed as a Least Recently Used (LRU) cache.
   */
  private final @GuardedBy LRUElementList seenMultiLRU;

  /**
   * Elements seen once, that have expired out of the main cache but may inform
   * future adaptivity.
   */
  private final @GuardedBy ExpiredElementList seenOnceExpiring;

  /**
   * Elements seen multiple times, that have expired out of the main cache but may
   * inform future adaptivity.
   */
  private final @GuardedBy ExpiredElementList seenMultiExpiring;
  private final int initialCapacity;
  private final DelayManager delayManager;

  /**
   * The target size for the seen-once LRU list.
   */
  private int targetSeenOnceCapacity;
  private final boolean safetyChecks;

  /**
   * Constructs a new InnerArc with the specified capacity.
   *
   * @param capacity
   *          the maximum number of elements the cache can hold
   * @param safetyChecks
   *          whether to check the cache's internal consistency after every
   *          operation
   */
  @SuppressFBWarnings("EI2")
  public InnerArc(int capacity, boolean safetyChecks, DelayManager delayManager) {
    initialCapacity = capacity;
    this.delayManager = delayManager;
    seenOnceExpiring = new ExpiredElementList(LRUElementList.Name.SEEN_ONCE_EXPIRING, capacity, safetyChecks);
    seenMultiExpiring = new ExpiredElementList(LRUElementList.Name.SEEN_MULTI_EXPIRING, capacity, safetyChecks);
    seenOnceLRU = new LRUElementList(LRUElementList.Name.SEEN_ONCE_LRU, capacity, seenOnceExpiring, safetyChecks);
    seenMultiLRU = new LRUElementList(LRUElementList.Name.SEEN_MULTI_LRU, capacity, seenMultiExpiring, safetyChecks);
    targetSeenOnceCapacity = capacity;
    this.safetyChecks = safetyChecks;
  }

  @SideEffectFree
  @Holding("this")
  private @Nullable ListId ownerFor(@GuardSatisfied InnerArc this, @Nullable Element<?, ?> e) {
    if (e == null) {
      return null;
    }
    var owner = e.getOwner();
    return switch (owner) {
      case null -> null;
      case LRUElementList l when l == seenOnceLRU -> ListId.SEEN_ONCE_LRU;
      case LRUElementList l when l == seenMultiLRU -> ListId.SEEN_MULTI_LRU;
      case ExpiredElementList l when l == seenOnceExpiring -> ListId.SEEN_ONCE_EXPIRING;
      case ExpiredElementList l when l == seenMultiExpiring -> ListId.SEEN_MULTI_EXPIRING;
      default -> throw new IllegalStateException("Element " + e + " found in an unknown list " + owner);
    };
  }

  /**
   * Processes an element that was found in the cache. Updates the element's
   * position in the appropriate list based on its usage.
   */
  @ReleasesNoLocks
  @Holding("this")
  public <V extends @NonNull Object> CompletableFuture<V> processElement(@GuardedBy InnerArc this, Element<?, V> e) {
    try {
      var oldOwner = ownerFor(e);
      switch (oldOwner) {
        case null ->
          // New or expired out of the cache
          enqueueNewElement(e);
        case SEEN_ONCE_LRU, SEEN_MULTI_LRU -> {
          if (!e.containsValue()) {
            throw new IllegalStateException("Element in LRU list has no value: " + e);
          }
          seenMultiLRU.push(e);
        }
        case SEEN_ONCE_EXPIRING -> {
          targetSeenOnceCapacity = Math.min(initialCapacity * 2 - 1, targetSeenOnceCapacity + 1);
          seenMultiLRU.push(e);
        }
        case SEEN_MULTI_EXPIRING -> {
          var shrunk = seenOnceLRU.shrink();
          if (shrunk) {
            seenMultiLRU.grow(e);
          } else {
            seenMultiLRU.push(e);
          }
          targetSeenOnceCapacity = Math.max(1, targetSeenOnceCapacity - 1);
        }
      }
      return e.get();
    } finally {
      checkSafety();
      delayManager.poll();
    }
  }

  /**
   * Enqueues a new element into the cache. Adjusts the size of the LRU lists to
   * maintain the target size.
   *
   * @param newElement
   *          the new element to enqueue
   */
  @ReleasesNoLocks
  @Holding("this")
  private void enqueueNewElement(@GuardSatisfied InnerArc this, Element<?, ?> newElement) {
    if (seenOnceLRU.getCapacity() >= targetSeenOnceCapacity) {
      seenOnceLRU.push(newElement);
    } else {
      seenMultiLRU.shrink();
      seenOnceLRU.grow(newElement);
    }
  }

  @ReleasesNoLocks
  @Holding("this")
  private void checkSafety(@GuardSatisfied InnerArc this) {
    if (!safetyChecks) {
      return;
    }

    if (seenMultiLRU.getCapacity() + seenOnceLRU.getCapacity() != initialCapacity * 2) {
      throw new IllegalStateException("Size mismatch: " + seenMultiLRU.getCapacity() + " + " + seenOnceLRU.getCapacity()
          + " != " + initialCapacity * 2);
    }

    seenOnceLRU.checkSafety(true);
    seenMultiLRU.checkSafety(true);
    seenOnceExpiring.checkSafety(true);
    seenMultiExpiring.checkSafety(true);
  }

  public <K extends @NonNull Object, V extends @NonNull Object> SoftReference<@Nullable Element<K, V>> createElement(
      @GuardedBy InnerArc this, K key, Function<? super K, V> loader, ForkJoinPool pool) {
    return new SoftReference<>(new Element<>(key, loader, (l) -> CompletableFuture.supplyAsync(l, pool), delayManager));
  }
}
