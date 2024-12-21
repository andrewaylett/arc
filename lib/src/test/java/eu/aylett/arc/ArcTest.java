package eu.aylett.arc;

import org.checkerframework.checker.initialization.qual.Initialized;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.PolyNull;
import org.hamcrest.Matcher;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;

import static org.hamcrest.Matchers.containsInAnyOrder;

@SuppressWarnings({"type.arguments.not.inferred", "argument"})
class ArcTest {
  public static <T> org.hamcrest.Matcher<@PolyNull @Initialized T> equalTo(@PolyNull T operand) {
    return org.hamcrest.core.IsEqual.equalTo(operand);
  }

  public static <T> void assertThat(@PolyNull T actual, Matcher<? super @PolyNull T> matcher) {
    MatcherAssert.assertThat("", actual, matcher);
  }

  @Test
  void test() {
    var arc = new Arc<Integer, String>(1, i -> i.toString(), ForkJoinPool.commonPool());
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
    assertThat(recordedValues, equalTo(List.of(1, 2, 1)));
  }

  @Test
  void testMultipleElements() {
    var recordedValues = new ArrayList<@NonNull Integer>();
    var arc = new Arc<Integer, String>(2, i -> {
      recordedValues.add(i);
      return i.toString();
    }, ForkJoinPool.commonPool());

    assertThat(arc.get(1), equalTo("1"));
    assertThat(arc.get(2), equalTo("2"));
    assertThat(arc.get(1), equalTo("1"));
    assertThat(arc.get(2), equalTo("2"));

    // Check that the loader function was called with the expected values
    assertThat(recordedValues, equalTo(List.of(1, 2)));
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
}
