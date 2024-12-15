package eu.aylett.arc.internal;

import org.jspecify.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;

public class Element<K, V> implements ElementBase<K, V> {
  private final K key;
  private final Function<K, V> loader;
  private final ForkJoinPool pool;
  private @Nullable WeakReference<V> weakValue;
  public @Nullable CompletableFuture<V> value;
  public @Nullable Owner<K, V> owner;

  public Element(K key, Function<K, V> loader, ForkJoinPool pool) {
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
    if (this.owner != null) {
      this.owner.prev = prev;
    } else {
      throw new IllegalStateException("Not owned");
    }
  }

  @Override
  public void setNext(ElementBase<K, V> next) {
    if (this.owner != null) {
      this.owner.next = next;
    } else {
      throw new IllegalStateException("Not owned");
    }
  }

  public void resplice(ElementList<K, V> newOwner) {
    if (owner != null) {
      owner.prev.setNext(owner.next);
      owner.next.setPrev(owner.prev);
      owner.owner.size -= 1;
    }
    owner = new Owner<>(newOwner, newOwner.head.next, newOwner.head);
    newOwner.head.next.setPrev(this);
    newOwner.head.setNext(this);
    newOwner.size += 1;
  }

  public static final class Owner<K, V> {
    public ElementList<K, V> owner;
    public ElementBase<K, V> next;
    public ElementBase<K, V> prev;

    public Owner(
            ElementList<K, V> owner,
            ElementBase<K, V> next,
            ElementBase<K, V> prev) {
      this.owner = owner;
      this.next = next;
      this.prev = prev;
    }
    }
}
