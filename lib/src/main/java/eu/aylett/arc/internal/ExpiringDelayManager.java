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

import org.checkerframework.checker.lock.qual.MayReleaseLocks;

import java.time.Duration;
import java.time.InstantSource;
import java.util.concurrent.DelayQueue;

import static com.google.common.base.Preconditions.checkArgument;

public final class ExpiringDelayManager extends DelayManager {
  private final DelayQueue<TimeDelayedElement> queue;
  private final Duration expiry;

  public ExpiringDelayManager(Duration expiry, InstantSource timeSource) {
    super(timeSource);
    checkArgument(expiry.isPositive(), "Expiry must be positive");
    this.queue = new DelayQueue<>();
    this.expiry = expiry;
  }

  @Override
  public DelayedElement add(Element<?, ?> element) {
    var epochMilli = timeSource.instant().toEpochMilli();
    var delayedElement = new TimeDelayedElement(element, this::getDelay, epochMilli + expiry.toMillis());
    queue.add(delayedElement);
    return delayedElement;
  }

  @MayReleaseLocks
  @Override
  public void poll() {
    TimeDelayedElement element;
    while ((element = queue.poll()) != null) {
      element.expireFromDelay();
    }
  }
}
