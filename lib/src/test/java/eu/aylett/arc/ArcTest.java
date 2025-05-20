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

import org.checkerframework.checker.initialization.qual.Initialized;
import org.checkerframework.checker.lock.qual.GuardedBy;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.PolyNull;
import org.hamcrest.Matcher;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItems;

import static java.util.concurrent.TimeUnit.SECONDS;

class ArcTest {
  public static <T> org.hamcrest.Matcher<@GuardedBy @PolyNull @Initialized T> equalTo(@GuardedBy @PolyNull T operand) {
    return org.hamcrest.core.IsEqual.equalTo(operand);
  }

  public static <T> void assertThat(@GuardedBy @PolyNull T actual, Matcher<? super @GuardedBy @PolyNull T> matcher) {
    MatcherAssert.assertThat("", actual, matcher);
  }

  @Test
  void test() {
    var arc = new Arc<>(1, i -> "" + i, ForkJoinPool.commonPool());
    assertThat(arc.get(1), equalTo("1"));
    assertThat(arc.get(1), equalTo("1"));
  }

  @Test
  void testEviction() {
    var recordedValues = new ArrayList<Integer>();
    var arc = new Arc<Integer, String>(1, i -> {
      recordedValues.add(i);
      return i.toString();
    }, ForkJoinPool.commonPool());

    assertThat(arc.get(1), equalTo("1"));
    assertThat(arc.get(2), equalTo("2"));
    arc.weakExpire();
    assertThat(arc.get(2), equalTo("2"));
    assertThat(arc.get(1), equalTo("1")); // This should reload "1" as it was evicted

    // Check that the loader function was called with the expected values
    assertThat(recordedValues, equalTo(List.<@GuardedBy Integer>of(1, 2, 1)));
  }

  @Test
  void testMultipleElements() {
    var recordedValues = new ArrayList<@NonNull Integer>();
    var arc = new Arc<Integer, String>(4, i -> {
      recordedValues.add(i);
      return i.toString();
    }, ForkJoinPool.commonPool());

    assertThat(arc.get(1), equalTo("1"));
    assertThat(arc.get(2), equalTo("2"));
    assertThat(arc.get(1), equalTo("1"));
    assertThat(arc.get(2), equalTo("2"));

    // Check that the loader function was called with the expected values
    assertThat(recordedValues, equalTo(List.<@GuardedBy Integer>of(1, 2)));
  }

  @Test
  void testLFUElements() {
    var recordedValues = new ArrayList<@NonNull Integer>();
    var arc = new Arc<Integer, String>(5, i -> {
      recordedValues.add(i);
      return i.toString();
    }, ForkJoinPool.commonPool());

    var expectedValues = new ArrayList<@NonNull Integer>();
    arc.get(1);

    for (var i = 1; i <= 50; i++) {
      arc.get(i);
      expectedValues.add(i);
    }

    arc.get(1);

    // Check that the loader function was called with the expected values
    assertThat(recordedValues, equalTo(expectedValues));
  }

  @Test
  void testParallelLoading() {
    var recordedValues = new ConcurrentLinkedDeque<Integer>();
    var pool = ForkJoinPool.commonPool();
    var arc = new Arc<Integer, String>(200, i -> {
      recordedValues.add(i);
      return i.toString();
    }, ForkJoinPool.commonPool());

    var toJoin = new ArrayList<ForkJoinTask<String>>();
    for (var i = 1; i <= 5000; i++) {
      var ii = i;
      toJoin.add(pool.submit(() -> arc.get(ii % 5)));
    }
    toJoin.forEach(ForkJoinTask::join);
    assertThat(recordedValues, containsInAnyOrder(1, 2, 3, 4, 0));
  }

  @Test
  void testParallelLoadingWithExpiry() {
    var recordedValues = new ConcurrentLinkedDeque<Integer>();
    var pool = ForkJoinPool.commonPool();
    var arc = new Arc<Integer, String>(50, i -> {
      recordedValues.add(i);
      return i.toString();
    }, pool, true);
    var random = new Random(0);
    var seen = new ArrayList<Integer>();

    var toJoin = new ArrayList<ForkJoinTask<String>>();
    for (var i = 1; i <= 50000; i++) {
      var ii = random.nextInt(512);
      if (!seen.contains(ii)) {
        seen.add(ii);
      }
      toJoin.add(pool.submit(() -> arc.get(ii)));
      while (toJoin.size() > random.nextInt(512)) {
        toJoin.remove(random.nextInt(toJoin.size())).join();
      }
      arc.weakExpire();
    }
    toJoin.forEach(ForkJoinTask::join);
    assertThat(recordedValues, hasItems(seen.toArray(Integer[]::new)));
  }

  @Test
  void concurrentAccessDoesNotWaitForLoaderToFinish()
      throws ExecutionException, InterruptedException, TimeoutException {
    var inOneSem = new Semaphore(0);
    var releaseOneSem = new Semaphore(0);
    var pool = ForkJoinPool.commonPool();
    var arc = new Arc<Integer, String>(10, s -> {
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
    }, pool);

    var t1 = pool.submit(() -> arc.get(1));
    inOneSem.acquire();
    var t2 = pool.submit(() -> arc.get(2));
    assertThat(t1.get(1, SECONDS), equalTo("1"));
    assertThat(t2.get(1, SECONDS), equalTo("2"));
  }
}
