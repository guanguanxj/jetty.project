//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.core;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.core.internal.Parser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * Test behavior of Parser when encountering good / valid close status codes (per RFC6455)
 */
public class ParserGoodCloseStatusCodesTest
{
    public static Stream<Arguments> data()
    {
        return Stream.of(
            Arguments.of(1000, "Autobahn Server Testcase 7.7.1"),
            Arguments.of(1001, "Autobahn Server Testcase 7.7.2"),
            Arguments.of(1002, "Autobahn Server Testcase 7.7.3"),
            Arguments.of(1003, "Autobahn Server Testcase 7.7.4"),
            Arguments.of(1007, "Autobahn Server Testcase 7.7.5"),
            Arguments.of(1008, "Autobahn Server Testcase 7.7.6"),
            Arguments.of(1009, "Autobahn Server Testcase 7.7.7"),
            Arguments.of(1010, "Autobahn Server Testcase 7.7.8"),
            Arguments.of(1011, "Autobahn Server Testcase 7.7.9"),
            // These must be allowed, and cannot result in a ProtocolException
            Arguments.of(1012, "IANA Assigned"), // Now IANA Assigned
            Arguments.of(1013, "IANA Assigned"), // Now IANA Assigned
            Arguments.of(1014, "IANA Assigned"), // Now IANA Assigned
            Arguments.of(3000, "Autobahn Server Testcase 7.7.10"),
            Arguments.of(3099, "Autobahn Server Testcase 7.7.11"),
            Arguments.of(4000, "Autobahn Server Testcase 7.7.12"),
            Arguments.of(4099, "Autobahn Server Testcase 7.7.13")
        );
    }

    private ByteBufferPool bufferPool = new MappedByteBufferPool();

    @ParameterizedTest(name = "closeCode={0} {1}")
    @MethodSource("data")
    public void testGoodCloseCode(int closeCode, String description) throws InterruptedException
    {
        ParserCapture capture = new ParserCapture(new Parser(bufferPool));

        ByteBuffer raw = BufferUtil.allocate(256);
        BufferUtil.clearToFill(raw);

        // add close frame
        RawFrameBuilder.putOpFin(raw, OpCode.CLOSE, true);
        RawFrameBuilder.putLength(raw, 2, false); // len of closeCode
        raw.putChar((char)closeCode); // 2 bytes for closeCode

        // parse buffer
        BufferUtil.flipToFlush(raw, 0);
        capture.parse(raw);
        Frame frame = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        assertThat("Frame opcode", frame.getOpCode(), is(OpCode.CLOSE));
        CloseStatus closeStatus = new CloseStatus(frame.getPayload());
        assertThat("CloseStatus.code", closeStatus.getCode(), is(closeCode));
        assertThat("CloseStatus.reason", closeStatus.getReason(), nullValue());
    }

    @ParameterizedTest(name = "closeCode={0} {1}")
    @MethodSource("data")
    public void testGoodCloseCode_WithReasonPhrase(int closeCode, String description) throws InterruptedException
    {
        ParserCapture capture = new ParserCapture(new Parser(bufferPool));

        ByteBuffer raw = BufferUtil.allocate(256);
        BufferUtil.clearToFill(raw);

        // add close frame
        RawFrameBuilder.putOpFin(raw, OpCode.CLOSE, true);
        RawFrameBuilder.putLength(raw, 2 + 5, false); // len of closeCode + reason phrase
        raw.putChar((char)closeCode); // 2 bytes for closeCode
        raw.put("hello".getBytes(UTF_8));

        // parse buffer
        BufferUtil.flipToFlush(raw, 0);
        capture.parse(raw);
        Frame frame = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        assertThat("Frame opcode", frame.getOpCode(), is(OpCode.CLOSE));
        CloseStatus closeStatus = new CloseStatus(frame.getPayload());
        assertThat("CloseStatus.code", closeStatus.getCode(), is(closeCode));
        assertThat("CloseStatus.reason", closeStatus.getReason(), is("hello"));
    }
}