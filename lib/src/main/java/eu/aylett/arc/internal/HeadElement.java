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

import org.checkerframework.checker.initialization.qual.NotOnlyInitialized;
import org.checkerframework.checker.lock.qual.LockingFree;

/**
 * The HeadElement class represents the head of a doubly linked list used in the
 * cache. It implements the ElementBase interface and provides methods to manage
 * the next element in the list.
 *
 * @param <K>
 *          the type of keys maintained by this cache
 * @param <V>
 *          the type of mapped values
 */
final class HeadElement<K, V> implements ElementBase<K, V> {

  /** The next element in the linked list. */
  @NotOnlyInitialized
  ElementBase<K, V> next;

  /** Constructs a new HeadElement. Initializes the next element to itself. */
  HeadElement() {
    next = this;
  }

  @LockingFree
  @Override
  public void setPrev(ElementBase<K, V> prev) {
    throw new UnsupportedOperationException();
  }

  @LockingFree
  @Override
  public void setNext(ElementBase<K, V> next) {
    this.next = next;
  }
}
