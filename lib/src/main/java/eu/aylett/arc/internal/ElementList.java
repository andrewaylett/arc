package eu.aylett.arc.internal;

import org.jspecify.annotations.Nullable;

public class ElementList<K, V> {
  private int capacity;
  final HeadElement<K, V> head;
  private final TailElement<K, V> tail;
  public int size;

  public ElementList(int capacity) {
    this.capacity = capacity;
    head = new HeadElement<>();
    tail = new TailElement<>();
    head.next = tail;
    tail.prev = head;
    size = 0;
  }

  public void grow(Element<K, V> newElement) {
    this.capacity += 1;
    push(newElement);
  }

  public @Nullable Element<K, V> push(Element<K, V> newElement) {
    if (newElement.owner != null) {
      newElement.resplice(this);
      return null;
    }
    newElement.owner = new Element.Owner<>(this, head.next, head);
    head.next.setPrev(newElement);
    head.setNext(newElement);
    this.size += 1;
    return evict();
  }

  public @Nullable Element<K, V> shrink() {
    this.capacity -= 1;
    return evict();
  }

  private @Nullable Element<K, V> evict() {
    if (this.size > this.capacity) {
      var victim = this.tail.prev;
      if (victim instanceof Element<K, V> element) {
        assert element.owner != null;
        this.tail.prev = element.owner.prev;
        element.owner = null;
        this.size -= 1;
        return element;
      }
    }
    return null;
  }
}
