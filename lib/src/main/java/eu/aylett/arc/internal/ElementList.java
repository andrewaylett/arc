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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.checkerframework.checker.initialization.qual.NotOnlyInitialized;
import org.checkerframework.checker.initialization.qual.UnderInitialization;
import org.checkerframework.checker.lock.qual.Holding;
import org.checkerframework.checker.lock.qual.LockingFree;
import org.checkerframework.checker.lock.qual.MayReleaseLocks;
import org.checkerframework.checker.lock.qual.ReleasesNoLocks;

public abstract class ElementList {
  public final ListId name;
  protected @NotOnlyInitialized InnerArc inner;

  @LockingFree
  @SuppressFBWarnings("EI2")
  public ElementList(ListId name, @UnderInitialization InnerArc inner) {
    this.name = name;
    this.inner = inner;
  }

  @MayReleaseLocks
  abstract void checkSafety();

  @ReleasesNoLocks
  @Holding("#1.lock")
  abstract void push(Element<?, ?> newElement);

  @MayReleaseLocks
  abstract void evict();

  public abstract void noteRemovedElement();

  @ReleasesNoLocks
  @Holding("#1.lock")
  public DelayedElement delayManage(Element<?, ?> element) {
    return inner.delayManage(element);
  }

  @ReleasesNoLocks
  @Holding("#1.lock")
  public abstract void processElement(Element<?, ?> kvElement);
}
