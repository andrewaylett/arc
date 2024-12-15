package eu.aylett.arc.internal;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.jspecify.annotations.Nullable;

public class ElementList<K extends @NonNull Object, V extends @NonNull Object> {
  private int capacity;
  private final @Nullable ElementList<K, V> expiryTarget;
  final HeadElement<K, V> head;
  private final TailElement<K, V> tail;
  public int size;

  public ElementList(int capacity, @Nullable ElementList<K, V> expiryTarget) {
    this.capacity = capacity;
    this.expiryTarget = expiryTarget;
    var head = new HeadElement<K, V>();
    var tail = new TailElement<K, V>();
    head.setNext(tail);
    tail.setPrev(head);
    this.head = head;
    this.tail = tail;
    size = 0;
  }

  public void grow(Element<K, V> newElement) {
    this.capacity += 1;
    push(newElement);
  }

  public void push(Element<K, V> newElement) {
    newElement.resplice(this);
    evict();
  }

  public void shrink() {
    this.capacity -= 1;
    evict();
  }

  private void evict() {
    if (this.size > this.capacity) {
      var victim = this.tail.prev;
      if (victim instanceof Element<K, V> element) {
        element.value = null;
        element.resplice(this.expiryTarget);
      }
    }
  }
}
