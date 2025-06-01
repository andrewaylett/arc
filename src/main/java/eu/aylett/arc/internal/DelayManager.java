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
import org.checkerframework.dataflow.qual.SideEffectFree;

import java.time.InstantSource;
import java.util.concurrent.TimeUnit;

public abstract class DelayManager {
  protected final InstantSource timeSource;

  public DelayManager(InstantSource timeSource) {
    this.timeSource = timeSource;
  }

  public abstract DelayedElement add(Element<?, ?> element);

  @MayReleaseLocks
  public abstract void poll();

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
