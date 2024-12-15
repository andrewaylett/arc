package eu.aylett.arc.internal;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.jspecify.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;

public class Element<K extends @NonNull Object, V extends @NonNull Object> implements ElementBase<K, V> {
  private final K key;
  private final Function<K, V> loader;
  private final ForkJoinPool pool;
  private @Nullable WeakReference<V> weakValue;
  public @Nullable CompletableFuture<V> value;
  public @Nullable ListLocation<K, V> listLocation;

  public Element(K key, Function<K, V> loader, ForkJoinPool pool) {
    this.key = key;
    this.loader = loader;
    this.pool = pool;
  }

  public V get() {
    if (value != null) {
      var v = value.join();
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
    if (this.listLocation != null) {
      this.listLocation.prev = prev;
    } else {
      throw new IllegalStateException("Not owned");
    }
  }

  @Override
  public void setNext(ElementBase<K, V> next) {
    if (this.listLocation != null) {
      this.listLocation.next = next;
    } else {
      throw new IllegalStateException("Not owned");
    }
  }

  public void resplice(@Nullable ElementList<K, V> newOwner) {
    if (listLocation != null) {
      var oldLocation = listLocation;
      oldLocation.prev.setNext(oldLocation.next);
      oldLocation.next.setPrev(oldLocation.prev);
      oldLocation.owner.size -= 1;
      listLocation = null;
    }
    if (newOwner != null) {
      listLocation = new ListLocation<>(newOwner, newOwner.head.next, newOwner.head);
      newOwner.head.next.setPrev(this);
      newOwner.head.setNext(this);
      newOwner.size += 1;
    }
  }

  public static final class ListLocation<K extends @NonNull Object, V extends @NonNull Object> {
    public ElementList<K, V> owner;
    public ElementBase<K, V> next;
    public ElementBase<K, V> prev;

    public ListLocation(
            ElementList<K, V> owner,
            ElementBase<K, V> next,
            ElementBase<K, V> prev) {
      this.owner = owner;
      this.next = next;
      this.prev = prev;
    }
    }
}
