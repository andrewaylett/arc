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

import org.junit.jupiter.api.Test;

/**
 * This test is lightly mangled and injected into README.md by cog.
 */
public class ReadmeTest {
  @Test
  void test() {
    var arc = Arc.<Integer, String>build(i -> ("" + i).repeat(i), 1);
    assert arc.get(1).equals("1");
    assert arc.get(7).equals("7777777");
  }
}
