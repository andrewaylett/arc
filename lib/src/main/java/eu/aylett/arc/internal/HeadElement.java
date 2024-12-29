package eu.aylett.arc.internal;

import org.checkerframework.checker.initialization.qual.NotOnlyInitialized;
import org.checkerframework.checker.lock.qual.LockingFree;

/// The HeadElement class represents the head of a doubly linked list used in the
/// cache. It implements the ElementBase interface and provides methods to manage
/// the next element in the list.
///
/// @param <K>
///            the type of keys maintained by this cache
/// @param <V>
///            the type of mapped values
final class HeadElement<K, V> implements ElementBase<K, V> {

  /// The next element in the linked list.
  @NotOnlyInitialized
  ElementBase<K, V> next;

  /// Constructs a new HeadElement. Initializes the next element to itself.
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
