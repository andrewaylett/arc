/*
 * Copyright 2024-2025 Andrew Aylett
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.aylett.arc;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

import static java.time.Duration.ofSeconds;
import static java.util.Collections.synchronizedList;
import static java.util.concurrent.TimeUnit.SECONDS;

class ArcTest {
  @Test
  void test() {
    var arc = Arc.build(i -> "" + i, 1);
    assertThat(arc.get(1), equalTo("1"));
    assertThat(arc.get(1), equalTo("1"));

    arc.checkSafety();
  }

  @Test
  void testEviction() {
    var recordedValues = new ArrayList<Integer>();
    var arc = Arc.<Integer, String>build(i -> {
      recordedValues.add(i);
      return i.toString();
    }, 1);

    assertThat(arc.get(1), equalTo("1"));
    assertThat(arc.get(2), equalTo("2"));
    arc.checkSafety();
    arc.weakExpire();
    assertThat(arc.get(2), equalTo("2"));
    assertThat(arc.get(1), equalTo("1")); // This should reload "1" as it was evicted

    // Check that the loader function was called with the expected values
    assertThat(recordedValues, equalTo(List.of(1, 2, 1)));

    arc.checkSafety();
  }

  @Test
  void testMultipleElements() {
    var recordedValues = new ArrayList<Integer>();
    var arc = Arc.build((Integer i) -> {
      recordedValues.add(i);
      return i.toString();
    }, 4);

    assertThat(arc.get(1), equalTo("1"));
    assertThat(arc.get(2), equalTo("2"));
    assertThat(arc.get(1), equalTo("1"));
    assertThat(arc.get(2), equalTo("2"));

    // Check that the loader function was called with the expected values
    assertThat(recordedValues, equalTo(List.of(1, 2)));
  }

  @Test
  void testLFUElements() {
    var recordedValues = synchronizedList(new ArrayList<Integer>());
    var arc = Arc.build((Integer i) -> {
      recordedValues.add(i);
      return i.toString();
    }, 5);

    var expectedValues = new ArrayList<Integer>();
    arc.get(1);

    for (var i = 1; i <= 50; i++) {
      arc.get(i);
      expectedValues.add(i);
    }

    // Should not have been evicted, so we won't record it again.
    arc.get(1);

    synchronized (recordedValues) {
      // Check that the loader function was called with the expected values
      assertThat(recordedValues, equalTo(expectedValues));
    }

    arc.checkSafety();
  }

  @Test
  void testParallelLoading() {
    var recordedValues = new ConcurrentLinkedDeque<Integer>();
    var pool = ForkJoinPool.commonPool();
    var arc = Arc.build((Integer i) -> {
      recordedValues.add(i);
      return i.toString();
    }, 200);

    var toJoin = new ArrayList<ForkJoinTask<String>>();
    for (var i = 1; i <= 5000; i++) {
      var ii = i;
      toJoin.add(pool.submit(() -> arc.get(ii % 5)));
    }
    toJoin.forEach(ForkJoinTask::join);
    assertThat(recordedValues, containsInAnyOrder(1, 2, 3, 4, 0));

    arc.checkSafety();
  }

  @Test
  void testParallelLoadingWithExpiry() {
    var recordedValues = ConcurrentHashMap.<Integer>newKeySet();
    var pool = ForkJoinPool.commonPool();
    var clock = new MockInstantSource();
    var arc = Arc.builder().withExpiry(Duration.ofMinutes(1)).withRefresh(ofSeconds(30)).withClock(clock)
        .build((Integer i) -> {
          recordedValues.add(i);
          return i.toString();
        }, 50);
    var random = new Random(0);
    var seen = new HashSet<Integer>();

    var toJoin = new ArrayList<ForkJoinTask<String>>();
    for (var i = 1; i <= 500000; i++) {
      var ii = random.nextInt(512);
      seen.add(ii);
      toJoin.add(pool.submit(() -> arc.get(ii)));
      while (toJoin.size() > random.nextInt(512)) {
        toJoin.remove(random.nextInt(toJoin.size())).join();
      }
      if (i % 100 == 0) {
        // Every 100 iterations, expire the weakly cached elements
        arc.weakExpire();
      }
      clock.value.incrementAndGet();
    }
    toJoin.forEach(ForkJoinTask::join);
    assertThat(recordedValues, hasItems(seen.toArray(Integer[]::new)));
    arc.checkSafety();
  }

  @Test
  void concurrentAccessDoesNotWaitForLoaderToFinish()
      throws ExecutionException, InterruptedException, TimeoutException {
    var inOneSem = new Semaphore(0);
    var releaseOneSem = new Semaphore(0);
    var pool = ForkJoinPool.commonPool();
    var arc = Arc.build((Integer s) -> {
      try {
        return switch (s) {
          case 1 -> {
            inOneSem.release();
            releaseOneSem.acquire();
            yield "1";
          }
          case 2 -> {
            releaseOneSem.release();
            yield "2";
          }
          default -> s.toString();
        };
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }, 10);

    var t1 = pool.submit(() -> arc.get(1));
    inOneSem.acquire();
    var t2 = pool.submit(() -> arc.get(2));
    assertThat(t1.get(1, SECONDS), equalTo("1"));
    assertThat(t2.get(1, SECONDS), equalTo("2"));

    arc.checkSafety();
  }

  @Test
  void repeatedElementsAreRefreshed() {
    final var testCount = 100070;
    var clock = new MockInstantSource();
    var recordedValues = synchronizedList(new ArrayList<Integer>());
    var zeroTimestamps = synchronizedList(new ArrayList<Instant>());
    var minusOneTimestamps = synchronizedList(new ArrayList<Instant>());

    var arc = Arc.builder().withExpiry(ofSeconds(60)).withRefresh(ofSeconds(30)).withClock(clock).build((Integer i) -> {
      recordedValues.add(i);
      if (i == 0) {
        zeroTimestamps.add(clock.instant());
      }
      if (i == -1) {
        minusOneTimestamps.add(clock.instant());
      }
      return "" + i;
    }, 1000);

    for (var i = 0; i < testCount; i++) {
      arc.get(i);
      if (i % 100 == 0) {
        arc.get(0);
      }
      if (i % 900 == 0) {
        arc.get(-1);
      }
      clock.value.addAndGet(1);
      if (i % 100 == 0) {
        arc.checkSafety();
      }
    }

    Map<Integer, Long> record;
    synchronized (recordedValues) {
      record = recordedValues.stream().collect(Collectors.groupingBy((v) -> v, Collectors.counting()));
    }
    // Would be testCount/100 without any caching or refreshing
    assertThat(record.get(0), lessThan(testCount / 200L));
    // Would be testCount/600 with no early refreshes
    assertThat(record.get(0), greaterThan(testCount / 500L));
    for (var i = 1; i < testCount; i++) {
      assertThat(record.get(i), equalTo(1L));
    }

    synchronized (zeroTimestamps) {
      IntStream.range(0, zeroTimestamps.size() - 1).mapToObj(start -> zeroTimestamps.subList(start, start + 2))
          .forEach(adjacentPair -> {
            var start = adjacentPair.getFirst();
            var end = adjacentPair.getLast();
            assertThat(end.toEpochMilli(), lessThan(start.plusSeconds(55).toEpochMilli()));
            assertThat(end.toEpochMilli(), greaterThan(start.plusSeconds(29).toEpochMilli()));
          });
    }

    synchronized (minusOneTimestamps) {
      IntStream.range(0, minusOneTimestamps.size() - 1).mapToObj(start -> minusOneTimestamps.subList(start, start + 2))
          .forEach(adjacentPair -> {
            // Fetched every 90s, so will expire between fetches and not be refreshed.
            var start = adjacentPair.getFirst();
            var end = adjacentPair.getLast();
            assertThat(end.toEpochMilli(), lessThan(start.plusSeconds(95).toEpochMilli()));
            assertThat(end.toEpochMilli(), greaterThan(start.plusSeconds(85).toEpochMilli()));
          });
    }

    arc.checkSafety();
  }

  static class MockInstantSource implements InstantSource {
    public final AtomicLong value = new AtomicLong();

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(value.get() * 100);
    }
  }

  @Test
  void testNullKeyHandling() {
    var arc = Arc.build(Object::toString, 10);
    @SuppressWarnings("DataFlowIssue")
    var e = assertThrowsExactly(NullPointerException.class, () -> arc.get(null));
    assertThat(e.getMessage(), equalTo("key cannot be null"));
  }

  @Test
  void testLargeCapacity() {
    var arc = Arc.build(Object::toString, 100_000);
    for (var i = 0; i < 100_000; i++) {
      assertThat(arc.get(i), equalTo(String.valueOf(i)));
    }
  }

  @Test
  void testWeakExpireWithNoGC() {
    var arc = Arc.build(Object::toString, 10);
    arc.get(1);
    arc.get(2);
    arc.weakExpire();
    assertThat(arc.get(1), equalTo("1"));
    assertThat(arc.get(2), equalTo("2"));
  }

  @Test
  void testWeakExpireWithGC() {
    var arc = Arc.build(Object::toString, 10);
    arc.get(1);
    arc.get(2);
    System.gc(); // Suggest garbage collection
    arc.weakExpire();
    assertThat(arc.get(1), equalTo("1")); // Should reload
    assertThat(arc.get(2), equalTo("2")); // Should reload
  }

  @Test
  void testLoaderExceptionHandling() {
    var loaderFailed = new UnsupportedOperationException("Loader failed");

    var arc = Arc.build((Integer i) -> {
      if (i == 1) {
        throw loaderFailed;
      }
      return i.toString();
    }, 10);

    var ex = assertThrowsExactly(CompletionException.class, () -> arc.get(1));
    assertThat(ex.getCause(), is(loaderFailed));

    assertThat(arc.get(2), equalTo("2"));
  }

  @Test
  void testLoaderRetriesAfterException() {
    var shouldThrow = new AtomicBoolean(true);

    var arc = Arc.build((Integer i) -> {
      if (i == 1 && shouldThrow.getAndSet(false)) {
        throw new UnsupportedOperationException("Loader failed");
      }
      return i.toString();
    }, 10);

    var ex = assertThrowsExactly(CompletionException.class, () -> arc.get(1));
    var cause = ex.getCause();
    assertThat(cause.getMessage(), equalTo("Loader failed"));

    assertThat(arc.get(2), equalTo("2"));
    assertThat(arc.get(1), equalTo("1"));
  }

  @Test
  void testConcurrentAccessWithSameKey() {
    var pool = ForkJoinPool.commonPool();
    var arc = Arc.build((Integer i) -> {
      try {
        Thread.sleep(100); // Simulate delay in loading
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
      return i.toString();
    }, 10);

    var tasks = new ArrayList<ForkJoinTask<String>>();
    for (var i = 0; i < 10; i++) {
      tasks.add(pool.submit(() -> arc.get(1)));
    }

    tasks.forEach(ForkJoinTask::join);
    assertThat(arc.get(1), equalTo("1"));
  }

  @Test
  void testEvictionWithHighTurnover() {
    var arc = Arc.build(Object::toString, 5);
    for (var i = 0; i < 20; i++) {
      arc.get(i);
    }
    arc.weakExpire();
    assertThat(arc.get(19), equalTo("19"));
    assertThat(arc.get(0), equalTo("0")); // Should reload
  }

  @Test
  void testCustomForkJoinPool() {
    var customPool = new ForkJoinPool(2);
    var arc = Arc.builder().withPool(customPool).build(Object::toString, 10);

    var tasks = new ArrayList<ForkJoinTask<String>>();
    for (var i = 0; i < 10; i++) {
      var finalI = i;
      tasks.add(customPool.submit(() -> arc.get(finalI)));
    }

    tasks.forEach(ForkJoinTask::join);
    for (var i = 0; i < 10; i++) {
      assertThat(arc.get(i), equalTo(String.valueOf(i)));
    }
  }

  @Test
  void testLoaderWithComplexValues() {
    var arc = Arc.<Integer, List<Integer>>build(i -> List.of(i, i * 2, i * 3), 10);
    assertThat(arc.get(2), equalTo(List.of(2, 4, 6)));
    assertThat(arc.get(3), equalTo(List.of(3, 6, 9)));
  }
}
