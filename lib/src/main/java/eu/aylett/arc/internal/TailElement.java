package eu.aylett.arc.internal;

class TailElement<K, V> implements ElementBase<K, V> {
  public ElementBase<K, V> prev;

  @Override
  public void setPrev(ElementBase<K, V> prev) {
    this.prev = prev;
  }

  @Override
  public void setNext(ElementBase<K, V> next) {
    throw new UnsupportedOperationException();
  }
}
