package eu.aylett.arc.internal;

import org.checkerframework.checker.lock.qual.LockingFree;

public interface ElementBase<K, V> {
  @LockingFree
  void setPrev(ElementBase<K, V> prev);

  @LockingFree
  void setNext(ElementBase<K, V> next);
}
