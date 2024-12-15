package eu.aylett.arc.internal;

class HeadElement<K, V> implements ElementBase<K, V> {
  protected ElementBase<K, V> next;

  @Override
  public void setPrev(ElementBase<K, V> prev) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setNext(ElementBase<K, V> next) {
    this.next = next;
  }
}
