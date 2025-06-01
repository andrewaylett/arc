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

import org.checkerframework.checker.initialization.qual.UnderInitialization;
import org.checkerframework.checker.lock.qual.Holding;
import org.checkerframework.checker.lock.qual.MayReleaseLocks;
import org.checkerframework.checker.lock.qual.ReleasesNoLocks;

public class UnownedElementList extends ElementList {
  public UnownedElementList(@UnderInitialization InnerArc inner) {
    super(ListId.UNOWNED, inner);
  }

  @Override
  @ReleasesNoLocks
  @Holding("#1.lock")
  void push(Element<?, ?> newElement) {
    // We don't remember unowned elements, so we only addRef
    newElement.addRef(this);
  }

  @Override
  void evict() {
    // No eviction needed for unowned elements.
  }

  @Override
  public void noteRemovedElement() {
    // No action needed for unowned elements.
  }

  @Override
  @ReleasesNoLocks
  @Holding("#1.lock")
  public void processElement(Element<?, ?> e) {
    // New or expired out of the cache
    inner.enqueueNewElement(e);
  }

  @Override
  @MayReleaseLocks
  public void checkSafety() {
    // No safety checks needed for unowned elements.
  }
}
