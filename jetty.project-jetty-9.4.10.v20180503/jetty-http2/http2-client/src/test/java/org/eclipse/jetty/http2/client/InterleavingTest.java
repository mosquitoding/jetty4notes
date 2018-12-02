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

package org.eclipse.jetty.http2.client;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ISession;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

public class InterleavingTest extends AbstractTest
{
    @Test
    public void testInterleaving() throws Exception
    {
        CountDownLatch serverStreamsLatch = new CountDownLatch(2);
        List<Stream> serverStreams = new ArrayList<>();
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                serverStreams.add(stream);
                serverStreamsLatch.countDown();
                return null;
            }
        });

        int maxFrameSize = Frame.DEFAULT_MAX_LENGTH + 1;
        Session session = newClient(new Session.Listener.Adapter()
        {
            @Override
            public Map<Integer, Integer> onPreface(Session session)
            {
                Map<Integer, Integer> settings = new HashMap<>();
                settings.put(SettingsFrame.MAX_FRAME_SIZE, maxFrameSize);
                return settings;
            }
        });

        BlockingQueue<FrameBytesCallback> dataFrames = new LinkedBlockingDeque<>();
        Stream.Listener streamListener = new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                ByteBuffer data = frame.getData();
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                dataFrames.offer(new FrameBytesCallback(frame, bytes, callback));
            }
        };

        HeadersFrame headersFrame1 = new HeadersFrame(newRequest("GET", new HttpFields()), null, true);
        FuturePromise<Stream> streamPromise1 = new FuturePromise<>();
        session.newStream(headersFrame1, streamPromise1, streamListener);
        streamPromise1.get(5, TimeUnit.SECONDS);

        HeadersFrame headersFrame2 = new HeadersFrame(newRequest("GET", new HttpFields()), null, true);
        FuturePromise<Stream> streamPromise2 = new FuturePromise<>();
        session.newStream(headersFrame2, streamPromise2, streamListener);
        streamPromise2.get(5, TimeUnit.SECONDS);

        Assert.assertTrue(serverStreamsLatch.await(5, TimeUnit.SECONDS));

        Thread.sleep(1000);

        Stream serverStream1 = serverStreams.get(0);
        Stream serverStream2 = serverStreams.get(1);
        MetaData.Response response1 = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, new HttpFields(), 0);
        serverStream1.headers(new HeadersFrame(serverStream1.getId(), response1, null, false), Callback.NOOP);

        Random random = new Random();
        byte[] content1 = new byte[2 * ((ISession)serverStream1.getSession()).updateSendWindow(0)];
        random.nextBytes(content1);
        byte[] content2 = new byte[2 * ((ISession)serverStream2.getSession()).updateSendWindow(0)];
        random.nextBytes(content2);

        MetaData.Response response2 = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, new HttpFields(), 0);
        serverStream2.headers(new HeadersFrame(serverStream2.getId(), response2, null, false), new Callback()
        {
            @Override
            public void succeeded()
            {
                // Write data for both streams from within the callback so that they get queued together.

                ByteBuffer buffer1 = ByteBuffer.wrap(content1);
                serverStream1.data(new DataFrame(serverStream1.getId(), buffer1, true), NOOP);

                ByteBuffer buffer2 = ByteBuffer.wrap(content2);
                serverStream2.data(new DataFrame(serverStream2.getId(), buffer2, true), NOOP);
            }
        });

        // The client reads with a buffer size that is different from the
        // frame size and synthesizes DATA frames, so expect N frames for
        // stream1 up to maxFrameSize of data, then M frames for stream2
        // up to maxFrameSize of data, and so forth, interleaved.

        Map<Integer, ByteArrayOutputStream> contents = new HashMap<>();
        contents.put(serverStream1.getId(), new ByteArrayOutputStream());
        contents.put(serverStream2.getId(), new ByteArrayOutputStream());
        List<StreamLength> streamLengths = new ArrayList<>();
        int finished = 0;
        while (finished < 2)
        {
            FrameBytesCallback frameBytesCallback = dataFrames.poll(5, TimeUnit.SECONDS);
            if (frameBytesCallback == null)
                Assert.fail();

            DataFrame dataFrame = frameBytesCallback.frame;
            int streamId = dataFrame.getStreamId();
            int length = dataFrame.remaining();
            streamLengths.add(new StreamLength(streamId, length));
            if (dataFrame.isEndStream())
                ++finished;

            contents.get(streamId).write(frameBytesCallback.bytes);

            frameBytesCallback.callback.succeeded();
        }

        // Verify that the content has been sent properly.
        Assert.assertArrayEquals(content1, contents.get(serverStream1.getId()).toByteArray());
        Assert.assertArrayEquals(content2, contents.get(serverStream2.getId()).toByteArray());

        // Verify that the interleaving is correct.
        Map<Integer, List<Integer>> groups = new HashMap<>();
        groups.put(serverStream1.getId(), new ArrayList<>());
        groups.put(serverStream2.getId(), new ArrayList<>());
        int currentStream = 0;
        int currentLength = 0;
        for (StreamLength streamLength : streamLengths)
        {
            if (currentStream == 0)
                currentStream = streamLength.stream;
            if (currentStream != streamLength.stream)
            {
                groups.get(currentStream).add(currentLength);
                currentStream = streamLength.stream;
                currentLength = 0;
            }
            currentLength += streamLength.length;
        }
        groups.get(currentStream).add(currentLength);

        Logger logger = Log.getLogger(getClass());
        logger.debug("frame lengths = {}", streamLengths);

        groups.forEach((stream, lengths) ->
        {
            logger.debug("stream {} interleaved lengths = {}", stream, lengths);
            for (Integer length : lengths)
                Assert.assertThat(length, Matchers.lessThanOrEqualTo(maxFrameSize));
        });
    }

    private static class FrameBytesCallback
    {
        private final DataFrame frame;
        private final byte[] bytes;
        private final Callback callback;

        private FrameBytesCallback(DataFrame frame, byte[] bytes, Callback callback)
        {
            this.frame = frame;
            this.bytes = bytes;
            this.callback = callback;
        }
    }

    private static class StreamLength
    {
        private final int stream;
        private final int length;

        private StreamLength(int stream, int length)
        {
            this.stream = stream;
            this.length = length;
        }

        @Override
        public String toString()
        {
            return String.format("(%d,%d)", stream, length);
        }
    }
}
