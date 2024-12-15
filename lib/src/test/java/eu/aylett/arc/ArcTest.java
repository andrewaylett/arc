package eu.aylett.arc;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ForkJoinPool;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

class ArcTest {
  @Test
  void test() {
    var arc = new Arc<Integer, String>(1, Object::toString, ForkJoinPool.commonPool());
    assertThat(arc.get(1), equalTo("1"));
    assertThat(arc.get(1), equalTo("1"));
  }
}
