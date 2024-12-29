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
