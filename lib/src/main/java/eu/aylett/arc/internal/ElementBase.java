package eu.aylett.arc.internal;

/// The ElementBase interface defines the basic operations for elements in the
/// cache. It provides methods to set the previous and next elements in a linked
/// list.
///
/// @param <K>
///            the type of keys maintained by this cache
/// @param <V>
///            the type of mapped values
public interface ElementBase<K, V> {

  /// Sets the previous element in the linked list.
  ///
  /// @param prev
  /// the previous element
  void setPrev(ElementBase<K, V> prev);

  /// Sets the next element in the linked list.
  ///
  /// @param next
  /// the next element
  void setNext(ElementBase<K, V> next);
}
