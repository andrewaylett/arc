package eu.aylett.arc.internal;

public interface ElementBase<K, V> {
  void setPrev(ElementBase<K, V> prev);

  void setNext(ElementBase<K, V> next);
}
