package eu.aylett.arc.internal;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/// The Element class represents an element in the cache. It manages the value
/// associated with a key and its position in the cache's linked lists.
///
/// @param <K>
///            the type of keys maintained by this cache
/// @param <V>
///            the type of mapped values
public class Element<K extends @NonNull Object, V extends @NonNull Object> implements ElementBase<K, V> {
  private final Lock lock = new ReentrantLock();

  /// The key associated with this element.
  private final K key;

  /// The function used to load values.
  private final Function<K, V> loader;

  /// The ForkJoinPool used for parallel processing.
  private final ForkJoinPool pool;

  /// A weak reference to the value associated with this element.
  private @Nullable WeakReference<V> weakValue;

  /// A CompletableFuture representing the value associated with this element.
  private @Nullable CompletableFuture<V> value;

  /// The location of this element in the linked list.
  public @Nullable ListLocation<K, V> listLocation;

  /// Constructs a new Element with the specified key, loader function, and
  /// ForkJoinPool.
  ///
  /// @param key
  /// the key associated with this element
  /// @param loader
  /// the function to load values
  /// @param pool
  /// the ForkJoinPool for parallel processing
  public Element(K key, Function<K, V> loader, ForkJoinPool pool) {
    this.key = key;
    this.loader = loader;
    this.pool = pool;
  }

  /// Retrieves the value associated with this element. If the value is not
  /// present, it uses the loader function to load the value.
  ///
  /// @return the value associated with this element
  @SuppressWarnings("method.guarantee.violated")
  public V get() {
    lock.lock();
    var locked = true;
    var value = this.value;
    try {
      if (value != null) {
        if (value.isDone()) {
          var v = value.join();
          this.weakValue = new WeakReference<>(v);
          return v;
        }
        lock.unlock();
        locked = false;
        return value.join();
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
      this.value = value = CompletableFuture.supplyAsync(() -> loader.apply(key), pool);
    } finally {
      if (locked) {
        lock.unlock();
      }
    }
    return value.join();
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

  /// Repositions this element in the linked list.
  ///
  /// @param newOwner
  /// the new owner list for this element
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

  /// The ListLocation class represents the position of an element in a linked
  /// list.
  ///
  /// @param <K>
  /// the type of keys maintained by this cache
  /// @param <V>
  /// the type of mapped values
  public static final class ListLocation<K extends @NonNull Object, V extends @NonNull Object> {
    public ElementList<K, V> owner;
    public ElementBase<K, V> next;
    public ElementBase<K, V> prev;

    /// Constructs a new ListLocation with the specified owner, next element, and
    /// previous element.
    ///
    /// @param owner
    /// the owner list of this location
    /// @param next
    /// the next element in the list
    /// @param prev
    /// the previous element in the list
    public ListLocation(ElementList<K, V> owner, ElementBase<K, V> next, ElementBase<K, V> prev) {
      this.owner = owner;
      this.next = next;
      this.prev = prev;
    }
  }

  /// A "normal" expiry, leaving the weak reference but allowing the GC to collect
  /// the object if necessary.
  @SuppressWarnings("method.guarantee.violated")
  public void expire() {
    lock.lock();
    value = null;
    lock.unlock();
  }

  /// Removes the weak reference, so if it's expired already then we'll regenerate
  /// the value.
  @SuppressWarnings("method.guarantee.violated")
  public void weakExpire() {
    lock.lock();
    weakValue = null;
    lock.unlock();
  }
}
