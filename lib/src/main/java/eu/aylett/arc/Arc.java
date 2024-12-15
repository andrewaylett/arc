package eu.aylett.arc;

import eu.aylett.arc.internal.Element;
import eu.aylett.arc.internal.ElementList;

import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;

public class Arc<K, V> {
  private final Function<K, V> loader;
  private final ForkJoinPool pool;

  public Arc(int capacity, Function<K, V> loader, ForkJoinPool pool) {
    this.loader = loader;
    this.pool = pool;
    elements = new ConcurrentHashMap<>();
    t1 = new ElementList<>(capacity);
    t2 = new ElementList<>(capacity);
    b1 = new ElementList<>(capacity);
    b2 = new ElementList<>(capacity);
    targetT1 = capacity;
  }

  private final ConcurrentHashMap<K, WeakReference<Element<K, V>>> elements;
  private final ElementList<K, V> t1;
  private final ElementList<K, V> t2;
  private final ElementList<K, V> b1;
  private final ElementList<K, V> b2;
  private int targetT1;

  public V get(K key) {

    while (true) {
      try {
        var newElement = new Element<>(key, loader, pool);
        var ref = elements.computeIfAbsent(key, k -> new WeakReference<>(newElement));
        if (ref.refersTo(newElement)) {
          // Not seen before
          if (t1.size >= targetT1) {
            var expired = t1.shrink();
            if (expired != null) {
              expired.value = null;
              var dead = b1.push(expired);
              if (dead != null) {
                dead.owner = null;
              }
            }
          } else {
            t1.push(newElement);
            var expired = t2.shrink();
            if (expired != null) {
              expired.value = null;
              var dead = b2.push(expired);
              if (dead != null) {
                dead.owner = null;
              }
            }
          }
          return newElement.get();
        }
        var e = ref.get();
        if (e == null) {
          // Remove if expired and not already removed/replaced
          elements.computeIfPresent(key, (k, v) -> {
            if (v.refersTo(null)) {
              return null;
            }
            return v;
          });
          continue;
        }
        // Seen before
        if (e.owner == null) {
          // Expired out of cache
          t1.push(e);
        } else if (e.owner.owner == t1) {
          var expire = t2.push(e);
          if (expire != null) {
            expire.value = null;
            var dead = b2.push(expire);
            if (dead != null) {
              dead.owner = null;
            }
          }
        } else if (e.owner.owner == t2) {
          t2.push(e);
        } else if (e.owner.owner == b1) {
          targetT1 += 1;
          var expire = t2.push(e);
          if (expire != null) {
            expire.value = null;
            var dead = b2.push(expire);
            if (dead != null) {
              dead.owner = null;
            }
          }
        } else if (e.owner.owner == b2) {
          t2.grow(e);
          targetT1 -= 1;
        } else {
          // Expired out of cache
          t1.push(e);
        }
        return e.get();
      } catch (InterruptedException e) {
        // retry
      } catch (ExecutionException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
