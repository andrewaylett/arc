package eu.aylett.arc.internal;

import org.checkerframework.checker.nullness.qual.NonNull;

public class InnerArc<K extends @NonNull Object, V extends @NonNull Object> {
  private final ElementList<K, V> seenOnceLRU;
  private final ElementList<K, V> seenMultiLRU;
  private final ElementList<K, V> seenOnceExpiring;
  private final ElementList<K, V> seenMultiExpiring;
  private int targetSeenOnceSize;

  public InnerArc(int capacity) {
    seenOnceExpiring = new ElementList<>(capacity, null);
    seenMultiExpiring = new ElementList<>(capacity, null);
    seenOnceLRU = new ElementList<>(capacity, seenOnceExpiring);
    seenMultiLRU = new ElementList<>(capacity, seenMultiExpiring);
    targetSeenOnceSize = capacity;
  }

  public synchronized void processFoundElement(Element<K, V> e) {
    var listLocation = e.listLocation;
    // Seen before
    if (listLocation == null) {
      // Expired out of cache
      enqueueNewElement(e);
    } else if (listLocation.owner == seenOnceLRU) {
      seenMultiLRU.push(e);
    } else if (listLocation.owner == seenMultiLRU) {
      seenMultiLRU.push(e);
    } else if (listLocation.owner == seenOnceExpiring) {
      targetSeenOnceSize += 1;
      seenMultiLRU.push(e);
    } else if (listLocation.owner == seenMultiExpiring) {
      seenMultiLRU.grow(e);
      targetSeenOnceSize -= 1;
    } else {
      // Expired out of cache
      enqueueNewElement(e);
    }
  }

  public synchronized void enqueueNewElement(Element<K, V> newElement) {
    var lruToShrink = seenOnceLRU.size >= targetSeenOnceSize ? seenOnceLRU : seenMultiLRU;
    lruToShrink.shrink();
    seenOnceLRU.push(newElement);
  }
}
