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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.checkerframework.checker.lock.qual.GuardSatisfied;
import org.checkerframework.checker.lock.qual.LockingFree;
import org.checkerframework.checker.lock.qual.ReleasesNoLocks;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.dataflow.qual.SideEffectFree;
import org.jspecify.annotations.Nullable;

/**
 * The ElementList class represents a doubly linked list used to manage elements
 * in the cache. It supports operations to grow, shrink, and evict elements
 * based on the list's capacity.
 *
 * @param <K>
 *          the type of keys maintained by this cache
 * @param <V>
 *          the type of mapped values
 */
class ElementList<K extends @NonNull Object, V extends @NonNull Object> {

  /** The maximum capacity to set. */
  private final int maxCapacity;

  private final String name;
  private final boolean safetyChecks;
  /** The maximum number of elements the list may hold. */
  private int capacity;

  /** The target list for expired elements. */
  private final @Nullable ElementList<K, V> expiryTarget;

  private final HeadElement<K, V> head;

  private final TailElement<K, V> tail;

  /** The current number of elements in the list. */
  private int size;

  @LockingFree
  @SuppressFBWarnings("EI_EXPOSE_REP")
  ElementList(String name, int capacity, @Nullable ElementList<K, V> expiryTarget, boolean safetyChecks) {
    this.name = name;
    this.capacity = capacity;
    this.maxCapacity = (capacity * 2) - 1;
    this.expiryTarget = expiryTarget;
    var head = new HeadElement<K, V>();
    var tail = new TailElement<K, V>();
    head.setNext(tail);
    tail.setPrev(head);
    this.head = head;
    this.tail = tail;
    this.safetyChecks = safetyChecks;
  }

  @ReleasesNoLocks
  void add(Element<K, V> kvElement) {
    if (expiryTarget == null && kvElement.containsValue()) {
      throw new IllegalStateException("Attempted to add an element with a value to an expired list: " + kvElement);
    }
    kvElement.setListLocation(new Element.ListLocation<>(this, head.next, this.head));
    head.next.setPrev(kvElement);
    head.setNext(kvElement);
    size += 1;
    evict();
  }

  @SideEffectFree
  void checkSafety(boolean sizeCheck) {
    if (!this.safetyChecks) {
      return;
    }

    var seen = 0;
    var e = this.head.next;
    while (e instanceof Element<K, V> element) {
      seen++;
      var listLocation = element.getListLocation();
      if (listLocation == null) {
        throw new IllegalStateException("Element not owned: " + element);
      } else if (listLocation.owner != this) {
        throw new IllegalStateException("Element owned by wrong list: " + element);
      }
      if (expiryTarget != null && !element.containsValue() && element.containsWeakValue()) {
        throw new IllegalStateException("Element in LRU list has only weak value: " + element);
      }
      if (expiryTarget == null && element.containsValue()) {
        throw new IllegalStateException("Element in expired list has a value: " + element);
      }
      e = listLocation.next;
    }
    if (e != this.tail) {
      throw new IllegalStateException("Did not reach our list tail, but: " + e);
    }
    if (seen != this.size) {
      throw new IllegalStateException("Size mismatch: found " + seen + " items != expected " + this.size);
    }

    if (sizeCheck && size > capacity) {
      throw new IllegalStateException("Size of " + size + " exceeds capacity of " + capacity + ": " + this);
    }
  }

  @SideEffectFree
  boolean containsValues() {
    return expiryTarget != null;
  }

  /**
   * Increases the capacity of the list and adds a new element.
   *
   * @param newElement
   *          the new element to add
   */
  @ReleasesNoLocks
  void grow(Element<K, V> newElement) {
    this.capacity = Math.min(this.capacity + 1, this.maxCapacity);
    push(newElement);
  }

  /**
   * Adds a new element to the list. If the list exceeds its capacity, it evicts
   * the least recently used element.
   *
   * @param newElement
   *          the new element to add
   */
  @ReleasesNoLocks
  void push(Element<K, V> newElement) {
    newElement.resplice(this);
    evict();
  }

  @LockingFree
  void remove(Element.ListLocation<K, V> oldLocation) {
    if (oldLocation.owner != this) {
      throw new IllegalStateException("Element owned by wrong list: " + oldLocation);
    }
    oldLocation.prev.setNext(oldLocation.next);
    oldLocation.next.setPrev(oldLocation.prev);
    this.size -= 1;
  }

  @LockingFree
  void bringToHead(Element<K, V> element) {
    var oldLocation = element.getListLocation();
    if (oldLocation == null || oldLocation.owner != this) {
      throw new IllegalStateException("Element not owned by this list: " + element);
    }
    oldLocation.prev.setNext(oldLocation.next);
    oldLocation.next.setPrev(oldLocation.prev);
    oldLocation.next = this.head.next;
    oldLocation.prev = this.head;
    this.head.next.setPrev(element);
    this.head.setNext(element);
  }

  /** Decreases the capacity of the list and evicts elements if necessary. */
  @ReleasesNoLocks
  boolean shrink() {
    var shrunk = this.capacity > 1;
    this.capacity = Math.max(this.capacity - 1, 1);
    evict();
    return shrunk;
  }

  /** Evicts the least recently used element if the list exceeds its capacity. */
  @ReleasesNoLocks
  void evict() {
    while (this.size > this.capacity) {
      checkSafety(false);
      var victim = this.tail.prev;
      if (victim instanceof Element<K, V> element) {
        element.expire(this.expiryTarget);
      } else {
        throw new IllegalStateException("Attempted to expire a head or tail: " + victim);
      }
    }
  }

  @SideEffectFree
  @Override
  public String toString(@GuardSatisfied ElementList<K, V> this) {
    return "ElementList-" + this.name + " (" + this.size + "/" + this.capacity + ")";
  }

  @SideEffectFree
  int getCapacity() {
    return this.capacity;
  }
}
