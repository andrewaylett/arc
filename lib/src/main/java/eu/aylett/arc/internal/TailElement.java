package eu.aylett.arc.internal;

import org.checkerframework.checker.initialization.qual.NotOnlyInitialized;
import org.checkerframework.checker.lock.qual.LockingFree;

/// The TailElement class represents the tail of a doubly linked list used in the
/// cache. It implements the ElementBase interface and provides methods to manage
/// the previous element in the list.
///
/// @param <K>
///            the type of keys maintained by this cache
/// @param <V>
///            the type of mapped values
final class TailElement<K, V> implements ElementBase<K, V> {

  /// The previous element in the linked list.
  @NotOnlyInitialized
  ElementBase<K, V> prev;

  /// Constructs a new TailElement. Initializes the previous element to itself.
  TailElement() {
    prev = this;
  }

  @LockingFree
  @Override
  public void setPrev(ElementBase<K, V> prev) {
    this.prev = prev;
  }

  @LockingFree
  @Override
  public void setNext(ElementBase<K, V> next) {
    throw new UnsupportedOperationException();
  }
}
