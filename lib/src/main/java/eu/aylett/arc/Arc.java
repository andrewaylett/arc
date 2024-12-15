package eu.aylett.arc;

import eu.aylett.arc.internal.Element;
import eu.aylett.arc.internal.ElementList;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.ref.SoftReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.function.BiFunction;
import java.util.function.Function;

public class Arc<K extends @NonNull Object, V extends @NonNull Object> {
  private final Function<K, V> loader;
  private final ForkJoinPool pool;

  public Arc(int capacity, Function<K, V> loader, ForkJoinPool pool) {
    this.loader = loader;
    this.pool = pool;
    elements = new ConcurrentHashMap<>();
    seenOnceExpiring = new ElementList<>(capacity, null);
    seenMultiExpiring = new ElementList<>(capacity, null);
    seenOnceLRU = new ElementList<>(capacity, seenOnceExpiring);
    seenMultiLRU = new ElementList<>(capacity, seenMultiExpiring);
    targetSeenOnceSize = capacity;
  }

  private final ConcurrentHashMap<K, SoftReference<@Nullable Element<K, V>>> elements;
  private final ElementList<K, V> seenOnceLRU;
  private final ElementList<K, V> seenMultiLRU;
  private final ElementList<K, V> seenOnceExpiring;
  private final ElementList<K, V> seenMultiExpiring;
  private int targetSeenOnceSize;

  public V get(K key) {

    while (true) {
        var newElement = new Element<>(key, loader, pool);
        var ref = elements.computeIfAbsent(key, k -> new SoftReference<>(newElement));
        if (ref.refersTo(newElement)) {
          // Not seen before
          var lruToShrink = seenOnceLRU.size >= targetSeenOnceSize ? seenOnceLRU : seenMultiLRU;
          lruToShrink.shrink();

          return newElement.get();
        }
        var e = ref.get();
        if (e == null) {
          // Remove if expired and not already removed/replaced
          elements.computeIfPresent(key, new BiFunction<K, SoftReference<@Nullable Element<K, V>>, @Nullable SoftReference<@Nullable Element<K, V>>>() {
            @Override
            public @Nullable SoftReference<@Nullable Element<K, V>> apply(K k, SoftReference<@Nullable Element<K, V>> v) {
              if (v.refersTo(null)) {
                return null;
              }
              return v;
            }
          });
          continue;
        }
        // Seen before
        if (e.listLocation == null) {
          // Expired out of cache
          seenOnceLRU.push(e);
        } else if (e.listLocation.owner == seenOnceLRU) {
          seenMultiLRU.push(e);
        } else if (e.listLocation.owner == seenMultiLRU) {
          seenMultiLRU.push(e);
        } else if (e.listLocation.owner == seenOnceExpiring) {
          targetSeenOnceSize += 1;
          seenMultiLRU.push(e);
        } else if (e.listLocation.owner == seenMultiExpiring) {
          seenMultiLRU.grow(e);
          targetSeenOnceSize -= 1;
        } else {
          // Expired out of cache
          seenOnceLRU.push(e);
        }
        return e.get();
    }
  }
}
