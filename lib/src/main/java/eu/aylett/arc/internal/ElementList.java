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

import org.checkerframework.checker.lock.qual.LockingFree;
import org.checkerframework.checker.lock.qual.ReleasesNoLocks;
import org.checkerframework.dataflow.qual.Pure;

public abstract class ElementList {
  public final Name name;

  @LockingFree
  public ElementList(Name name) {
    this.name = name;
  }

  @ReleasesNoLocks
  abstract void checkSafety(boolean sizeCheck);

  @Pure
  abstract boolean isForExpiredElements();

  @ReleasesNoLocks
  abstract void push(Element<?, ?> newElement);

  @ReleasesNoLocks
  abstract void evict();

  public abstract void noteRemovedElement();

  public enum Name {
    SEEN_ONCE_LRU, SEEN_MULTI_LRU, SEEN_ONCE_EXPIRING, SEEN_MULTI_EXPIRING
  }
}
