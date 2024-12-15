package eu.aylett.arc;

import org.jspecify.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.concurrent.CompletableFuture;
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

  private interface ElementBase<K, V> {
    void setPrev(ElementBase<K, V> prev);
    void setNext(ElementBase<K, V> next);
  }
  private static class Element<K, V> implements ElementBase<K, V> {
    private final K key;
    private final Function<K, V> loader;
    private final ForkJoinPool pool;
    private @Nullable WeakReference<V> weakValue;
    private CompletableFuture<V> value;
    protected @Nullable ElementList<K, V> owner;
    private ElementBase<K, V> next;
    private ElementBase<K, V> prev;

    private Element(K key, Function<K, V> loader, ForkJoinPool pool) {
      this.key = key;
      this.loader = loader;
      this.pool = pool;
    }

    public V get() throws ExecutionException, InterruptedException {
      if (value != null) {
        var v = value.get();
        weakValue = new WeakReference<>(v);
        return v;
      }
      if (weakValue != null) {
        var v = weakValue.get();
        if (v != null) {
          value = CompletableFuture.completedFuture(v);
          return v;
        }
      }
      weakValue = null;
      value = CompletableFuture.supplyAsync(() -> loader.apply(key), pool);
      return get();
    }

    @Override
    public void setPrev(ElementBase<K, V> prev) {
      this.prev = prev;
    }

    @Override
    public void setNext(ElementBase<K, V> next) {
      this.next = next;
    }

    public void resplice(ElementList<K, V> owner) {
      prev.setNext(next);
      next.setPrev(prev);
      next = owner.head.next;
      prev = owner.head;
      owner.head.next.setPrev(this);
      owner.head.setNext(this);
    }
  }

  private static class HeadElement<K, V> implements ElementBase<K, V> {
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

  private static class TailElement<K, V> implements ElementBase<K, V> {
    private ElementBase<K, V> prev;
    @Override
    public void setPrev(ElementBase<K, V> prev) {
      this.prev = prev;
    }

    @Override
    public void setNext(ElementBase<K, V> next) {
      throw new UnsupportedOperationException();
    }
  }

  private static class ElementList<K, V> {
    private int capacity;
    private final HeadElement<K, V> head;
    private final TailElement<K, V> tail;
    private int size;

    private ElementList(int capacity) {
      this.capacity = capacity;
      head = new HeadElement<>();
      tail = new TailElement<>();
      head.next = tail;
      tail.prev = head;
      size = 0;
    }

    public void grow(Element<K, V> newElement) {
      this.capacity += 1;
      push(newElement);
    }

    public @Nullable Element<K, V> push(Element<K, V> newElement) {
      if (newElement.owner == this) {
        newElement.resplice(this);
        return null;
      }
      if (newElement.owner != null) {
        newElement.owner.size -= 1;
        newElement.resplice(this);
      }
      newElement.owner = this;
      newElement.next = head.next;
      newElement.prev = head;
      head.next.setPrev(newElement);
      head.setNext(newElement);
      this.size += 1;
      return evict();
    }

    public @Nullable Element<K, V> shrink() {
      this.capacity -= 1;
      return evict();
    }

    private @Nullable Element<K, V> evict() {
      if (this.size > this.capacity) {
        var victim = this.tail.prev;
        if (victim instanceof Arc.Element<K,V> element) {
          element.owner = null;
          this.tail.prev = element.prev;
          this.size -= 1;
          return element;
        }
      }
      return null;
    }
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
        if (e.owner == t1) {
          var expire = t2.push(e);
          if (expire != null) {
            expire.value = null;
            var dead = b2.push(expire);
            if (dead != null) {
              dead.owner = null;
            }
          }
        } else if (e.owner == t2) {
          t2.push(e);
        } else if (e.owner == b1) {
          targetT1 += 1;
          var expire = t2.push(e);
          if (expire != null) {
            expire.value = null;
            var dead = b2.push(expire);
            if (dead != null) {
              dead.owner = null;
            }
          }
        } else if (e.owner == b2) {
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
