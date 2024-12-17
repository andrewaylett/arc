package eu.aylett.arc.internal;

import org.checkerframework.checker.lock.qual.LockingFree;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.concurrent.CompletableFuture;
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
    var value = this.value;
    if (value != null) {
      var v = value.join();
      this.weakValue = new WeakReference<>(v);
      return v;
    }
    var weakValue = this.weakValue;
    if (weakValue != null) {
      var v = weakValue.get();
      if (v != null) {
        this.value = CompletableFuture.completedFuture(v);
        return v;
      }
    }
    this.weakValue = null;
    this.value = CompletableFuture.supplyAsync(() -> loader.apply(key), pool);
    return get();
  }

  @Override
  public void setPrev(ElementBase<K, V> prev) {
    var listLocation = this.listLocation;
    if (listLocation != null) {
      listLocation.prev = prev;
    } else {
      throw new IllegalStateException("Not owned");
    }
  }

  @Override
  public void setNext(ElementBase<K, V> next) {
    var listLocation = this.listLocation;
    if (listLocation != null) {
      listLocation.next = next;
    } else {
      throw new IllegalStateException("Not owned");
    }
  }

  @LockingFree
  public void resplice(@Nullable ElementList<K, V> newOwner) {
    var oldLocation = this.listLocation;
    if (oldLocation != null) {
      oldLocation.prev.setNext(oldLocation.next);
      oldLocation.next.setPrev(oldLocation.prev);
      oldLocation.owner.size -= 1;
      this.listLocation = null;
    }
    if (newOwner != null) {
      this.listLocation = new ListLocation<>(newOwner, newOwner.head.next, newOwner.head);
      newOwner.head.next.setPrev(this);
      newOwner.head.setNext(this);
      newOwner.size += 1;
    }
  }

  public static final class ListLocation<K extends @NonNull Object, V extends @NonNull Object> {
    public ElementList<K, V> owner;
    public ElementBase<K, V> next;
    public ElementBase<K, V> prev;

    public ListLocation(ElementList<K, V> owner, ElementBase<K, V> next, ElementBase<K, V> prev) {
      this.owner = owner;
      this.next = next;
      this.prev = prev;
    }
  }
}
