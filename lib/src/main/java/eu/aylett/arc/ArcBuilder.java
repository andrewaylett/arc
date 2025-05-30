/*
 * Copyright 2025 Andrew Aylett
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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.checkerframework.checker.lock.qual.NewObject;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NonNull;

import java.time.Clock;
import java.time.Duration;
import java.time.InstantSource;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

public class ArcBuilder {

  private Duration expiry = Duration.ofSeconds(60);
  private Duration refresh = Duration.ofSeconds(30);
  private ForkJoinPool pool = ForkJoinPool.commonPool();
  private InstantSource clock = Clock.systemUTC();

  ArcBuilder() {
  }

  public ArcBuilder withExpiry(Duration expiry) {
    this.expiry = checkNotNull(expiry);
    return this;
  }

  public ArcBuilder withRefresh(Duration refresh) {
    this.refresh = checkNotNull(refresh);
    return this;
  }

  @SuppressFBWarnings("EI2")
  @Contract("_ -> this")
  public ArcBuilder withPool(ForkJoinPool pool) {
    this.pool = checkNotNull(pool);
    return this;
  }

  @Contract("_ -> this")
  public ArcBuilder withClock(InstantSource clock) {
    this.clock = checkNotNull(clock);
    return this;
  }

  @Contract(value = "_, _ -> new", pure = true)
  public <K extends @NonNull Object, V extends @NonNull Object> @NewObject Arc<K, V> build(Function<K, V> loader,
      int capacity) {
    checkNotNull(loader, "Loader function must be provided");
    checkArgument(capacity > 0, "Capacity must be greater than zero");
    return new Arc<>(capacity, loader, pool, expiry, refresh, clock);
  }
}
