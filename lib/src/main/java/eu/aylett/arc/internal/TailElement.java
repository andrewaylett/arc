package eu.aylett.arc.internal;

import org.checkerframework.checker.initialization.qual.NotOnlyInitialized;

final class TailElement<K, V> implements ElementBase<K, V> {
  @NotOnlyInitialized ElementBase<K, V> prev;

  TailElement() {
    prev = this;
  }

  @Override
  public void setPrev(ElementBase<K, V> prev) {
    this.prev = prev;
  }

  @Override
  public void setNext(ElementBase<K, V> next) {
    throw new UnsupportedOperationException();
  }
}
