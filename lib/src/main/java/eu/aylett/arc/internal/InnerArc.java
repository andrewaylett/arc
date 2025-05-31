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
import org.checkerframework.checker.initialization.qual.NotOnlyInitialized;
import org.checkerframework.checker.lock.qual.EnsuresLockHeld;
import org.checkerframework.checker.lock.qual.EnsuresLockHeldIf;
import org.checkerframework.checker.lock.qual.Holding;
import org.checkerframework.checker.lock.qual.MayReleaseLocks;
import org.checkerframework.checker.lock.qual.ReleasesNoLocks;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.base.Verify.verify;

/**
 * The InnerArc class manages the internal cache mechanism for the Arc class. It
 * maintains multiple lists to track elements based on their usage patterns.
 */
public class InnerArc {
  // This lock must not be taken while holding a lock on an Element.
  private final Lock evictionLock = new ReentrantLock();
  private final LockOrderGuard lockOrderGuard;

  /**
   * The list of elements seen once, managed as a Least Recently Used (LRU) cache.
   */
  private final @NotOnlyInitialized LRUElementList seenOnceLRU;

  /**
   * Elements seen multiple times, managed as a Least Recently Used (LRU) cache.
   */
  protected final @NotOnlyInitialized LRUElementList seenMultiLRU;

  /**
   * Elements seen once, that have expired out of the main cache but may inform
   * future adaptivity.
   */
  private final @NotOnlyInitialized ExpiredElementList seenOnceExpiring;

  public final @NotOnlyInitialized UnownedElementList unowned;

  /**
   * Elements seen multiple times, that have expired out of the main cache but may
   * inform future adaptivity.
   */
  private final @NotOnlyInitialized ExpiredElementList seenMultiExpiring;
  private final int initialCapacity;
  private final DelayManager delayManager;

  /**
   * The target size for the seen-once LRU list.
   */
  private int targetSeenOnceCapacity;

  /**
   * Constructs a new InnerArc with the specified capacity.
   *
   * @param capacity
   *          the maximum number of elements the cache can hold
   */
  @SuppressFBWarnings("EI2")
  public InnerArc(LockOrderGuard lockOrderGuard, int capacity, DelayManager delayManager) {
    this.lockOrderGuard = lockOrderGuard;
    initialCapacity = capacity;
    this.delayManager = delayManager;
    unowned = new UnownedElementList(this);
    seenOnceExpiring = new ExpiredElementList.SeenOnce(capacity, this);
    seenMultiExpiring = new ExpiredElementList.SeenMulti(capacity, this);
    seenOnceLRU = new LRUElementList(ListId.SEEN_ONCE_LRU, capacity, seenOnceExpiring, this);
    seenMultiLRU = new LRUElementList(ListId.SEEN_MULTI_LRU, capacity, seenMultiExpiring, this);
    targetSeenOnceCapacity = capacity;
  }

  /**
   * Enqueues a new element into the cache. Adjusts the size of the LRU lists to
   * maintain the target size.
   *
   * @param newElement
   *          the new element to enqueue
   */
  @Holding("#1.lock")
  @ReleasesNoLocks
  public void enqueueNewElement(Element<?, ?> newElement) {
    if (seenOnceLRU.getCapacity() >= targetSeenOnceCapacity) {
      seenOnceLRU.push(newElement);
    } else {
      if (seenMultiLRU.shrink()) {
        seenOnceLRU.grow(newElement);
      } else {
        seenOnceLRU.push(newElement);
      }
    }
  }

  @MayReleaseLocks
  public void checkSafety() {
    verify(seenMultiLRU.getCapacity() + seenOnceLRU.getCapacity() == initialCapacity * 2,
        "Size mismatch: %s + %s != %s", seenMultiLRU.getCapacity(), seenOnceLRU.getCapacity(), initialCapacity * 2);

    seenOnceLRU.checkSafety();
    seenMultiLRU.checkSafety();
    seenOnceExpiring.checkSafety();
    seenMultiExpiring.checkSafety();
    unowned.checkSafety();
  }

  @Holding("#1.lock")
  @ReleasesNoLocks
  public DelayedElement delayManage(Element<?, ?> element) {
    return delayManager.add(element);
  }

  @Holding("#1.lock")
  @ReleasesNoLocks
  public void enqueueSeenOnceElement(Element<?, ?> e) {
    targetSeenOnceCapacity = Math.min(initialCapacity * 2 - 1, targetSeenOnceCapacity + 1);
    seenMultiLRU.push(e);
  }

  @ReleasesNoLocks
  @Holding("#1.lock")
  public void enqueueSeenMultiElement(Element<?, ?> e) {
    var shrunk = seenOnceLRU.shrink();
    if (shrunk) {
      seenMultiLRU.grow(e);
    } else {
      seenMultiLRU.push(e);
    }
    targetSeenOnceCapacity = Math.max(1, targetSeenOnceCapacity - 1);
  }

  @MayReleaseLocks
  @Holding("this.evictionLock")
  public void evict() {
    seenMultiLRU.evict();
    seenOnceLRU.evict();
    seenMultiExpiring.evict();
    seenOnceExpiring.evict();
    unowned.evict();
    delayManager.poll();
  }

  @EnsuresLockHeldIf(expression = "this.evictionLock", result = true)
  @ReleasesNoLocks
  public boolean tryEvictionLock() {
    lockOrderGuard.assertNoElementLockHeld();
    return evictionLock.tryLock();
  }

  @EnsuresLockHeld("this.evictionLock")
  @ReleasesNoLocks
  public void takeEvictionLock() {
    lockOrderGuard.assertNoElementLockHeld();
    evictionLock.lock();
  }

  @MayReleaseLocks
  public void releaseEvictionLock() {
    evictionLock.unlock();
    lockOrderGuard.assertNoElementLockHeld();
  }
}
