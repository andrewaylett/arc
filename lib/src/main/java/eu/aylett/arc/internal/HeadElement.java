package eu.aylett.arc.internal;

import org.checkerframework.checker.initialization.qual.NotOnlyInitialized;

final class HeadElement<K, V> implements ElementBase<K, V> {
  @NotOnlyInitialized ElementBase<K, V> next;

  HeadElement() {
    next = this;
  }

  @Override
  public void setPrev(ElementBase<K, V> prev) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setNext(ElementBase<K, V> next) {
    this.next = next;
  }
}
