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

package eu.aylett.arc.internal;

import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.Nullable;

public final class Invariants {
  @Contract(value = "null -> fail; !null -> param1", pure = true)
  public static <T> T checkNotNull(@Nullable T reference) {
    return checkNotNull(reference, "Invariant failed: value is null");
  }

  @Contract(value = "null, _ -> fail; !null, _ -> param1", pure = true)
  public static <T> T checkNotNull(@Nullable T reference, String message) {
    if (reference == null) {
      throw new NullPointerException(message);
    }
    return reference;
  }
}
