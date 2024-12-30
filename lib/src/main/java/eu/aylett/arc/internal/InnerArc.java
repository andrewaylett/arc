/*
 * Copyright 2024 Andrew Aylett
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
import org.checkerframework.checker.lock.qual.Holding;
import org.checkerframework.checker.lock.qual.ReleasesNoLocks;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.SideEffectFree;
import org.checkerframework.framework.qual.CFComment;

import java.util.concurrent.CompletableFuture;

/**
 * The InnerArc class manages the internal cache mechanism for the Arc class. It
 * maintains multiple lists to track elements based on their usage patterns.
 *
 * @param <K>
 *          the type of keys maintained by this cache
 * @param <V>
 *          the type of mapped values
 */
public class InnerArc<K extends @NonNull Object, V extends @NonNull Object> {

  /**
   * The list of elements seen once, managed as a Least Recently Used (LRU) cache.
   */
  private final @GuardedBy("this") ElementList<K, V> seenOnceLRU;

  /**
   * Elements seen multiple times, managed as a Least Recently Used (LRU) cache.
   */
  private final @GuardedBy("this") ElementList<K, V> seenMultiLRU;

  /**
   * Elements seen once, that have expired out of the main cache but may inform
   * future adaptivity.
   */
  private final @GuardedBy("this") ElementList<K, V> seenOnceExpiring;

  /**
   * Elements seen multiple times, that have expired out of the main cache but may
   * inform future adaptivity.
   */
  private final @GuardedBy("this") ElementList<K, V> seenMultiExpiring;
  private final int initialCapacity;

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
  public InnerArc(int capacity, boolean safetyChecks) {
    initialCapacity = capacity;
    seenOnceExpiring = new ElementList<>("seenOnceExpiring", capacity, null, safetyChecks);
    seenMultiExpiring = new ElementList<>("seenMultiExpiring", capacity, null, safetyChecks);
    seenOnceLRU = new ElementList<>("seenOnceLRU", capacity, seenOnceExpiring, safetyChecks);
    seenMultiLRU = new ElementList<>("seenMultiLRU", capacity, seenMultiExpiring, safetyChecks);
    targetSeenOnceCapacity = capacity;
    this.safetyChecks = safetyChecks;
  }

  @SideEffectFree
  @Holding("this")
  private @Nullable ListId ownerFor(@Nullable Element<K, V> e) {
    if (e == null) {
      return null;
    }
    var listLocation = e.getListLocation();
    if (listLocation == null) {
      // New (or at least expired out of the cache) after all
      return null;
    } else if (listLocation.owner == seenOnceLRU) {
      return ListId.SEEN_ONCE_LRU;
    } else if (listLocation.owner == seenMultiLRU) {
      return ListId.SEEN_MULTI_LRU;
    } else if (listLocation.owner == seenOnceExpiring) {
      return ListId.SEEN_ONCE_EXPIRING;
    } else if (listLocation.owner == seenMultiExpiring) {
      return ListId.SEEN_MULTI_EXPIRING;
    } else {
      throw new IllegalStateException("Element " + e + " found in an unknown list " + listLocation.owner);
    }
  }

  /**
   * Processes an element that was found in the cache. Updates the element's
   * position in the appropriate list based on its usage.
   */
  @ReleasesNoLocks
  @Holding("this")
  @SuppressWarnings("method.invocation")
  @CFComment("CF wants us to propagate guard satisfied, but all accesses are mediated by this entrypoint")
  public CompletableFuture<V> processElement(@GuardSatisfied InnerArc<K, V> this, Element<K, V> e) {
    try {
      switch (ownerFor(e)) {
        case null:
          // New or expired out of the cache
          enqueueNewElement(e);
          break;
        case SEEN_ONCE_LRU, SEEN_MULTI_LRU:
          if (!e.containsValue()) {
            throw new IllegalStateException("Element in LRU list has no value: " + e);
          }
          seenMultiLRU.push(e);
          break;
        case SEEN_ONCE_EXPIRING:
          targetSeenOnceCapacity = Math.min(initialCapacity * 2 - 1, targetSeenOnceCapacity + 1);
          seenMultiLRU.push(e);
          break;
        case SEEN_MULTI_EXPIRING:
          var shrunk = seenOnceLRU.shrink();
          if (shrunk) {
            seenMultiLRU.grow(e);
          } else {
            seenMultiLRU.push(e);
          }
          targetSeenOnceCapacity = Math.max(1, targetSeenOnceCapacity - 1);
          break;
      }
      var completableFuture = e.get();
      checkSafety();
      return completableFuture;
    } catch (InterruptedException ex) {
      throw new IllegalStateException(ex);
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
  @SuppressWarnings("method.invocation")
  private void enqueueNewElement(Element<K, V> newElement) throws InterruptedException {
    newElement.setup();
    if (seenOnceLRU.getCapacity() >= targetSeenOnceCapacity) {
      seenOnceLRU.push(newElement);
    } else {
      seenMultiLRU.shrink();
      seenOnceLRU.grow(newElement);
    }
  }

  @SideEffectFree
  @Holding("this")
  @SuppressWarnings("method.invocation")
  private void checkSafety(@GuardSatisfied InnerArc<K, V> this) {
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
}
