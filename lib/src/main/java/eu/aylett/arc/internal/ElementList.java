package eu.aylett.arc.internal;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.jspecify.annotations.Nullable;

/// The ElementList class represents a doubly linked list used to manage elements
/// in the cache. It supports operations to grow, shrink, and evict elements
/// based on the list's capacity.
///
/// @param <K>
///            the type of keys maintained by this cache
/// @param <V>
///            the type of mapped values
public class ElementList<K extends @NonNull Object, V extends @NonNull Object> {

  /// The maximum capacity to set.
  private final int maxCapacity;

  /// The maximum number of elements the list can hold.
  private int capacity;

  /// The target list for expired elements.
  private final @Nullable ElementList<K, V> expiryTarget;

  /// The head element of the list.
  final HeadElement<K, V> head;

  /// The tail element of the list.
  private final TailElement<K, V> tail;

  /// The current number of elements in the list.
  public int size;

  /// Constructs a new ElementList with the specified capacity and expiry target.
  ///
  /// @param capacity
  /// the maximum number of elements the list can hold
  /// @param expiryTarget
  /// the target list for expired elements
  public ElementList(int capacity, @Nullable ElementList<K, V> expiryTarget) {
    this.capacity = capacity;
    this.maxCapacity = (capacity * 2) - 1;
    this.expiryTarget = expiryTarget;
    var head = new HeadElement<K, V>();
    var tail = new TailElement<K, V>();
    head.setNext(tail);
    tail.setPrev(head);
    this.head = head;
    this.tail = tail;
    size = 0;
  }

  /// Increases the capacity of the list and adds a new element.
  ///
  /// @param newElement
  /// the new element to add
  public void grow(Element<K, V> newElement) {
    this.capacity = Math.min(this.capacity + 1, this.maxCapacity);
    push(newElement);
  }

  /// Adds a new element to the list. If the list exceeds its capacity, it evicts
  /// the least recently used element.
  ///
  /// @param newElement
  /// the new element to add
  public void push(Element<K, V> newElement) {
    newElement.resplice(this);
    evict();
  }

  /// Decreases the capacity of the list and evicts elements if necessary.
  public void shrink() {
    this.capacity = Math.max(this.capacity - 1, 1);
    evict();
  }

  /// Evicts the least recently used element if the list exceeds its capacity.
  @SuppressWarnings("method.guarantee.violated")
  private void evict() {
    if (this.size > this.capacity) {
      var victim = this.tail.prev;
      if (victim instanceof Element<K, V> element) {
        element.expire();
        element.resplice(this.expiryTarget);
      }
    }
  }
}
