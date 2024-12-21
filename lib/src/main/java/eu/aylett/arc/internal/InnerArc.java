package eu.aylett.arc.internal;

import org.checkerframework.checker.nullness.qual.NonNull;

/// The InnerArc class manages the internal cache mechanism for the Arc class. It
/// maintains multiple lists to track elements based on their usage patterns.
///
/// @param <K>
///            the type of keys maintained by this cache
/// @param <V>
///            the type of mapped values
public class InnerArc<K extends @NonNull Object, V extends @NonNull Object> {

  /// The list of elements seen once, managed as a Least Recently Used (LRU)
  /// cache.
  private final ElementList<K, V> seenOnceLRU;

  /// Elements seen multiple times, managed as a Least Recently Used (LRU) cache.
  private final ElementList<K, V> seenMultiLRU;

  /// Elements seen once, that have expired out of the main cache but may inform
  /// future adaptivity.
  private final ElementList<K, V> seenOnceExpiring;

  /// Elements seen multiple times, that have expired out of the main cache but
  /// may
  /// inform future adaptivity.
  private final ElementList<K, V> seenMultiExpiring;

  /// The target size for the seen-once LRU list.
  private int targetSeenOnceSize;

  /// Constructs a new InnerArc with the specified capacity.
  ///
  /// @param capacity
  /// the maximum number of elements the cache can hold
  public InnerArc(int capacity) {
    seenOnceExpiring = new ElementList<>(capacity, null);
    seenMultiExpiring = new ElementList<>(capacity, null);
    seenOnceLRU = new ElementList<>(capacity, seenOnceExpiring);
    seenMultiLRU = new ElementList<>(capacity, seenMultiExpiring);
    targetSeenOnceSize = capacity;
  }

  /// Processes an element that was found in the cache. Updates the element's
  /// position in the appropriate list based on its usage.
  ///
  /// @param e
  /// the element to process
  public synchronized void processFoundElement(Element<K, V> e) {
    var listLocation = e.listLocation;
    if (listLocation == null) {
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
      enqueueNewElement(e);
    }
  }

  /// Enqueues a new element into the cache. Adjusts the size of the LRU lists to
  /// maintain the target size.
  ///
  /// @param newElement
  /// the new element to enqueue
  public synchronized void enqueueNewElement(Element<K, V> newElement) {
    var lruToShrink = seenOnceLRU.size >= targetSeenOnceSize ? seenOnceLRU : seenMultiLRU;
    lruToShrink.shrink();
    seenOnceLRU.push(newElement);
  }
}
