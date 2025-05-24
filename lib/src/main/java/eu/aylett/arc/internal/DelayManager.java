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

import org.checkerframework.dataflow.qual.SideEffectFree;

import java.time.Duration;
import java.time.InstantSource;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.TimeUnit;

public final class DelayManager {
  private final DelayQueue<DelayedElement> queue;
  private final DelayQueue<DelayedElement> refreshQueue;
  private final Duration expiry;
  private final Duration refresh;
  private final InstantSource timeSource;

  public DelayManager(Duration expiry, Duration refresh, InstantSource timeSource) {
    this.queue = new DelayQueue<>();
    this.refreshQueue = new DelayQueue<>();
    this.expiry = expiry;
    this.refresh = refresh;
    this.timeSource = timeSource;
  }

  public DelayedElement add(Element<?, ?> element) {
    var epochMilli = timeSource.instant().toEpochMilli();
    var delayedElement = new DelayedElement(element, this::getDelay, epochMilli + expiry.toMillis());
    queue.add(delayedElement);
    refreshQueue.add(new DelayedElement(element, this::getDelay, epochMilli + refresh.toMillis()));
    return delayedElement;
  }

  public void poll() {
    DelayedElement element;
    while ((element = queue.poll()) != null) {
      element.expireFromDelay();
    }
    while ((element = refreshQueue.poll()) != null) {
      element.refresh();
    }
  }

  @SideEffectFree
  public long getDelay(long expiryTime, TimeUnit unit) {
    @SuppressWarnings("method.guarantee.violated")
    var result = unit.convert(expiryTime - timeSource.instant().toEpochMilli(), TimeUnit.MILLISECONDS);
    return result;
  }

  public interface GetDelay {
    @SideEffectFree
    long getDelay(long expiryTime, TimeUnit unit);
  }
}
