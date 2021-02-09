/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.libraries.rcs.simpleclient.protocol.cpim;

import com.google.auto.value.AutoValue;
import com.google.common.base.Utf8;
import com.google.common.collect.ImmutableMap;

import java.util.Map;

/**
 * The CPIM implementation as per RFC 3862. This class supports minimal fields that is required to
 * represent a simple message for test purpose.
 */
@AutoValue
public abstract class SimpleCpimMessage {
    private static final String CRLF = "\r\n";
    private static final String COLSP = ": ";

    public static SimpleCpimMessage.Builder newBuilder() {
        return new AutoValue_SimpleCpimMessage.Builder();
    }

    public abstract ImmutableMap<String, String> namespaces();

    public abstract ImmutableMap<String, String> headers();

    public abstract String contentType();

    public abstract String content();

    public String encode() {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : namespaces().entrySet()) {
            builder
                    .append("NS: ")
                    .append(entry.getKey())
                    .append(" <")
                    .append(entry.getValue())
                    .append(">")
                    .append(CRLF);
        }

        for (Map.Entry<String, String> entry : headers().entrySet()) {
            builder.append(entry.getKey()).append(COLSP).append(entry.getValue()).append(CRLF);
        }

        builder.append(CRLF);
        builder.append("Content-Type").append(COLSP).append(contentType());
        builder.append("Content-Length").append(COLSP).append(Utf8.encodedLength(content()));
        builder.append(CRLF);
        builder.append(content());

        return builder.toString();
    }

    @AutoValue.Builder
    public abstract static class Builder {
        public abstract ImmutableMap.Builder<String, String> namespacesBuilder();

        public abstract ImmutableMap.Builder<String, String> headersBuilder();

        public abstract Builder setContentType(String value);

        public abstract Builder setContent(String value);

        public abstract SimpleCpimMessage build();

        public Builder addNamespace(String name, String value) {
            namespacesBuilder().put(name, value);
            return this;
        }

        public Builder addHeader(String name, String value) {
            headersBuilder().put(name, value);
            return this;
        }
    }
}
