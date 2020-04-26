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
package com.datastax.oss.dsbulk.codecs.text.string.dse;

import com.datastax.dse.driver.api.core.data.geometry.LineString;
import com.datastax.dse.driver.api.core.type.codec.DseTypeCodecs;
import com.datastax.oss.dsbulk.codecs.text.string.StringConvertingCodec;
import com.datastax.oss.dsbulk.codecs.util.CodecUtils;
import java.util.List;

public class StringToLineStringCodec extends StringConvertingCodec<LineString> {

  public StringToLineStringCodec(List<String> nullStrings) {
    super(DseTypeCodecs.LINE_STRING, nullStrings);
  }

  @Override
  public LineString externalToInternal(String s) {
    if (isNullOrEmpty(s)) {
      return null;
    }
    return CodecUtils.parseLineString(s);
  }

  @Override
  public String internalToExternal(LineString value) {
    if (value == null) {
      return nullString();
    }
    return value.asWellKnownText();
  }
}