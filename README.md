# ARC Library

ARC is an [Adaptive Replacement Cache](https://en.wikipedia.org/wiki/Adaptive_replacement_cache).

Originally intended for page table caches, this library makes the algorithm available for general use.

Values are always loaded in a separate thread, and (like Guava's cache) we will only load a value once.
The common pool is used if no fork-join pool is provided.

While the cache only holds strong references to a limited number of values (up to the given capacity),
it continues to hold weak references to all values it's loaded.  If the garbage collector hasn't reclaimed the space,
the cache will still return the value.
