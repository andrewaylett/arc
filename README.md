# ARC Library

ARC is an [Adaptive Replacement Cache](https://en.wikipedia.org/wiki/Adaptive_replacement_cache).

Originally intended for page table caches, this library makes the algorithm available for general use.

Values are always loaded in a separate thread, and (like Guava's cache) we will only load a value once.
The common pool is used if no fork-join pool is provided.

While the cache only holds strong references to a limited number of values (up to the given capacity),
it continues to hold weak references to all values it's loaded.  If the garbage collector hasn't reclaimed the space,
the cache will still return the value.

## Expiry and Refresh

The cache can be configured to expire values, and also to refresh if a value has been re-used.

Both expiry time and refresh time are relative to the last time we completed loading the value successfully.


## Usage

Include the library in your dependencies:

<!-- [[[cog
result = sp.run(
    ["./gradlew", "-q", "printCurrentVersion"],
    capture_output=True,
    text=True,
    check=True
)
version = result.stdout.strip()
cog.outl(f"""```groovy
dependencies {{
    implementation 'eu.aylett.arc:arc:{version}'
}}
```""")
]]] -->
```groovy
dependencies {
    implementation 'eu.aylett.arc:arc:0.1.0'
}
```
<!-- [[[end]]] (checksum: d5ea57cfcca437efbd1f279011922168) -->

Build yourself a suitable cache:

<!-- [[[cog
result = sp.run(
    ["./gradlew", "-q", "printReadmeDemo"],
    capture_output=True,
    text=True,
    check=True
)
cog.outl("```java")
cog.outl(result.stdout.strip())
cog.outl("```")
]]] -->
```java
import eu.aylett.arc.Arc;

public class ReadmeTest {
  public static void main(String[] args) {
    var arc = Arc.<Integer, String>build(i -> ("" + i).repeat(i), 1);
    assert arc.get(1).equals("1");
    assert arc.get(7).equals("7777777");
  }
}
```
<!-- [[[end]]] (checksum: caf7f544dc976983ac7639203bf9694b) -->
