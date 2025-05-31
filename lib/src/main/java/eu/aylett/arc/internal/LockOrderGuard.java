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

import org.jspecify.annotations.Nullable;

import static com.google.common.base.Verify.verify;

public class LockOrderGuard {
  private final ThreadLocal<@Nullable Element<?, ?>> elementLockHeld = ThreadLocal.withInitial(() -> null);

  public Release markThreadHoldingLock(Element<?, ?> element) {
    var oldElement = elementLockHeld.get();
    verify(oldElement == null, "Thread already holding a lock: %s", oldElement);

    elementLockHeld.set(element);

    return () -> {
      var heldElement = elementLockHeld.get();
      verify(heldElement == element, "Thread holding a different lock.  Expected %s, found %s", element, heldElement);
      elementLockHeld.remove();
    };
  }

  public void assertNoElementLockHeld() {
    var heldElement = elementLockHeld.get();
    verify(heldElement == null, "Thread already holding a lock on: %s", heldElement);
  }

  public interface Release {
    void release();
  }
}
