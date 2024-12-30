package eu.aylett.arc.internal;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.checkerframework.checker.lock.qual.LockingFree;
import org.checkerframework.checker.lock.qual.ReleasesNoLocks;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.dataflow.qual.SideEffectFree;
import org.jspecify.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;

/// The Element class represents an element in the cache. It manages the value
/// associated with a key and its position in the cache's linked lists.
///
/// @param <K>
///            the type of keys maintained by this cache
/// @param <V>
///            the type of mapped values
public class Element<K extends @NonNull Object, V extends @NonNull Object> implements ElementBase<K, V> {
  /// The key associated with this element.
  private final K key;

  /// The function used to load values.
  private final Function<K, V> loader;

  private final ForkJoinPool pool;

  /// A weak reference to the value associated with this element, if it's been
  /// computed.
  private @Nullable WeakReference<@Nullable V> weakValue;

  /// A CompletableFuture representing the value associated with this element.
  private @Nullable CompletableFuture<V> value;

  private @Nullable ListLocation<K, V> listLocation;

  @LockingFree
  @SuppressFBWarnings("EI_EXPOSE_REP")
  public Element(K key, Function<K, V> loader, ForkJoinPool pool) {
    this.key = key;
    this.loader = loader;
    this.pool = pool;
  }

  /// Retrieves the value associated with this element. If the value is not
  /// present, it uses the loader function to load the value.
  @ReleasesNoLocks
  CompletableFuture<V> get() {
    var listLocation = this.listLocation;
    if (listLocation == null || !listLocation.owner.containsValues()) {
      throw new IllegalStateException("Called get on an object in an expired list");
    }
    return setup();
  }

  @SideEffectFree
  public boolean containsValue() {
    return value != null;
  }

  @SideEffectFree
  boolean containsWeakValue() {
    var weakValue = this.weakValue;
    return weakValue != null && !weakValue.refersTo(null);
  }

  @SideEffectFree
  @Nullable ListLocation<K, V> getListLocation() {
    return listLocation;
  }

  @LockingFree
  void setListLocation(ListLocation<K, V> listLocation) {
    this.listLocation = listLocation;
  }

  @Override
  @LockingFree
  public void setPrev(ElementBase<K, V> prev) {
    var listLocation = this.listLocation;
    if (listLocation != null) {
      listLocation.prev = prev;
    } else {
      throw new IllegalStateException("Not owned");
    }
  }

  @Override
  @LockingFree
  public void setNext(ElementBase<K, V> next) {
    var listLocation = this.listLocation;
    if (listLocation != null) {
      listLocation.next = next;
    } else {
      throw new IllegalStateException("Not owned");
    }
  }

  /// Repositions this element in a linked list.
  @ReleasesNoLocks
  void resplice(@Nullable ElementList<K, V> newOwner) {
    var oldLocation = this.listLocation;
    if (oldLocation != null && oldLocation.owner == newOwner) {
      newOwner.bringToHead(this);
      return;
    }
    if (oldLocation != null) {
      oldLocation.owner.remove(oldLocation);
      this.listLocation = null;
    }
    if (newOwner != null) {
      newOwner.add(this);
    }
  }

  @ReleasesNoLocks
  @SuppressFBWarnings("EI_EXPOSE_REP")
  CompletableFuture<V> setup() {
    var value = this.value;
    if (value != null) {
      if (value.isDone()) {
        var v = value.join();
        var weakValue = this.weakValue;
        if (weakValue == null || !weakValue.refersTo(v)) {
          this.weakValue = new WeakReference<>(v);
        }
      }
      return value;
    }
    var weakValue = this.weakValue;
    if (weakValue != null) {
      var v = weakValue.get();
      if (v != null) {
        this.value = value = CompletableFuture.completedFuture(v);
      } else {
        this.weakValue = null;
      }
    }
    if (value == null) {
      this.value = value = CompletableFuture.supplyAsync(() -> loader.apply(key), pool);
    }
    var listLocation = this.listLocation;
    if (listLocation != null && !listLocation.owner.containsValues()) {
      throw new IllegalStateException("Called setup on an expired element");
    }
    return value;
  }

  static final class ListLocation<K extends @NonNull Object, V extends @NonNull Object> {
    final ElementList<K, V> owner;
    ElementBase<K, V> next;
    ElementBase<K, V> prev;

    @LockingFree
    ListLocation(ElementList<K, V> owner, ElementBase<K, V> next, ElementBase<K, V> prev) {
      this.owner = owner;
      this.next = next;
      this.prev = prev;
    }
  }

  /// A "normal" expiry, leaving the weak reference but allowing the GC to collect
  /// the object if necessary.
  @ReleasesNoLocks
  void expire(@Nullable ElementList<K, V> newOwner) {
    value = null;
    resplice(newOwner);
  }

  /// Removes the weak reference, so if the entry has expired already then we'll
  /// regenerate
  /// the value.
  ///
  /// Primarily useful for testing.
  @LockingFree
  public void weakExpire() {
    weakValue = null;
  }
}
