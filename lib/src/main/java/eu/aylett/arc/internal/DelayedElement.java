/*
 * Copyright 2025 Andrew Aylett
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
import org.checkerframework.checker.lock.qual.MayReleaseLocks;
import org.checkerframework.dataflow.qual.SideEffectFree;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

public final class DelayedElement implements Delayed {
  public final Element<?, ?> element;

  private final long expiryTime;
  private final DelayManager.GetDelay manager;

  public DelayedElement(Element<?, ?> element, DelayManager.GetDelay manager, long expiryTime) {
    this.element = element;
    this.expiryTime = expiryTime;
    this.manager = manager;
  }

  @Override
  @SideEffectFree
  public long getDelay(@GuardedBy DelayedElement this, TimeUnit unit) {
    return manager.getDelay(expiryTime, unit);
  }

  @Override
  @SuppressWarnings("override.receiver")
  public int compareTo(@GuardedBy DelayedElement this, Delayed o) {
    if (o instanceof DelayedElement other) {
      return Long.compare(expiryTime, other.expiryTime);
    }
    @SuppressWarnings("method.guarantee.violated")
    var result = Long.compare(getDelay(TimeUnit.MILLISECONDS), o.getDelay(TimeUnit.MILLISECONDS));
    return result;
  }

  @MayReleaseLocks
  public void expireFromDelay() {
    element.lock();
    try {
      element.delayExpired(this);
    } finally {
      element.unlock();
    }
  }

  @MayReleaseLocks
  public void refresh() {
    element.lock();
    try {
      element.reload();
    } finally {
      element.unlock();
    }
  }

  @Override
  @SuppressWarnings("instanceof.pattern.unsafe")
  public boolean equals(@GuardSatisfied DelayedElement this, @GuardSatisfied @Nullable Object o) {
    if (o instanceof DelayedElement that) {
      return expiryTime == that.expiryTime && Objects.equals(element, that.element);
    }
    return false;
  }

  @Override
  public int hashCode(@GuardSatisfied DelayedElement this) {
    return Objects.hash(element, expiryTime);
  }

  @Override
  @SideEffectFree
  public String toString(@GuardSatisfied DelayedElement this) {
    return "DelayedElement{" + "element=" + element + ", expiryTime=" + expiryTime + '}';
  }
}
