/*
 * Copyright DataStax, Inc.
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
package com.datastax.oss.dsbulk.codecs.text.string;

import static com.datastax.oss.dsbulk.tests.assertions.TestAssertions.assertThat;

import com.datastax.oss.driver.shaded.guava.common.collect.Lists;
import com.datastax.oss.protocol.internal.util.Bytes;
import java.nio.ByteBuffer;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class StringToBlobCodecTest {

  private final byte[] data = {1, 2, 3, 4, 5, 6};
  private final byte[] empty = {};

  private final ByteBuffer dataBb = ByteBuffer.wrap(data);
  private final ByteBuffer emptyBb = ByteBuffer.wrap(empty);

  private final String data64 = Base64.getEncoder().encodeToString(data);
  private final String dataHex = Bytes.toHexString(data);

  private final StringToBlobCodec codec = new StringToBlobCodec(Lists.newArrayList("NULL"));

  @Test
  void should_convert_from_valid_external() {
    assertThat(codec)
        .convertsFromExternal(data64)
        .toInternal(dataBb)
        .convertsFromExternal(dataHex)
        .toInternal(dataBb)
        .convertsFromExternal("0x")
        .toInternal(emptyBb)
        .convertsFromExternal("")
        .toInternal(null)
        .convertsFromExternal(null)
        .toInternal(null)
        .convertsFromExternal("NULL")
        .toInternal(null);
  }

  @Test
  void should_convert_from_valid_internal() {
    assertThat(codec)
        .convertsFromInternal(dataBb)
        .toExternal(data64)
        .convertsFromInternal(emptyBb)
        .toExternal("")
        .convertsFromInternal(null)
        .toExternal("NULL");
  }

  @Test
  void should_not_convert_from_invalid_external() {
    assertThat(codec).cannotConvertFromExternal("not a valid binary");
  }
}