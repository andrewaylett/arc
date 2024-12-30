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

import org.checkerframework.checker.lock.qual.LockingFree;

/// The ElementBase interface defines the basic operations for elements in the
/// cache. It provides methods to set the previous and next elements in a linked
/// list.
///
/// @param <K>
///            the type of keys maintained by this cache
/// @param <V>
///            the type of mapped values
public interface ElementBase<K, V> {

  @LockingFree
  void setPrev(ElementBase<K, V> prev);

  @LockingFree
  void setNext(ElementBase<K, V> next);
}
