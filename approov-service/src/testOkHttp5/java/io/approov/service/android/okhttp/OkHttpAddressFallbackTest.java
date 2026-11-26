package io.approov.service.android.okhttp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.SocketFactory;

import io.approov.service.android.TestAccess;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * A host that resolves to an address that never answers and to one that does is reached through the second
 * address long before the connect timeout, on a client built by the layer. The address that never answers
 * is modelled in the socket factory, so that the test does not depend on the routing of the machine: a
 * connect to it blocks until the connect timeout or until OkHttp gives the attempt up.
 */
public class OkHttpAddressFallbackTest {
    // the host name that the test DNS resolves to the two addresses
    private static final String HOST = "two-addresses.test";

    // the connect timeout of the client, which the call must not come close to
    private static final long CONNECT_TIMEOUT_SECONDS = 20;

    // the longest the call may take, well below the connect timeout
    private static final long FALLBACK_LIMIT_MILLIS = 5000;

    private MockWebServer server;
    private InetAddress loopback;
    private BlackHoleSocketFactory socketFactory;

    @Before
    public void setUp() throws Exception {
        TestAccess.resetApproovService();
        ApproovOkHttp.resetForTesting();
        loopback = InetAddress.getByAddress(HOST, new byte[] { 127, 0, 0, 1 });
        server = new MockWebServer();
        server.start(loopback, 0);
    }

    @After
    public void tearDown() throws Exception {
        if (socketFactory != null)
            socketFactory.release();
        server.shutdown();
        ApproovOkHttp.resetForTesting();
        TestAccess.resetApproovService();
    }

    @Test
    public void unansweredIPv6AddressFallsBackToIPv4() throws Exception {
        // 2001:db8::/32 is reserved for documentation, so it is never a real destination
        InetAddress unanswered = InetAddress.getByAddress(HOST,
                new byte[] { 0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1 });

        assertReachedThroughSecondAddress(unanswered);
    }

    @Test
    public void unansweredFirstAddressOfTheSameFamilyFallsBack() throws Exception {
        // 192.0.2.0/24 is reserved for documentation, so it is never a real destination
        InetAddress unanswered = InetAddress.getByAddress(HOST, new byte[] { (byte) 192, 0, 2, 1 });

        assertReachedThroughSecondAddress(unanswered);
    }

    /**
     * Makes a call with a layer built client to a host that resolves to the unanswered address first and
     * to the test server second, and checks that it completes through the test server.
     *
     * @param unanswered is the address that never completes a connect
     * @throws Exception if there was a problem
     */
    private void assertReachedThroughSecondAddress(InetAddress unanswered) throws Exception {
        socketFactory = new BlackHoleSocketFactory(unanswered);
        server.enqueue(new MockResponse().setBody("reached"));
        Dns dns = hostname -> {
            if (!HOST.equals(hostname))
                throw new java.net.UnknownHostException(hostname);
            return Arrays.asList(unanswered, loopback);
        };
        OkHttpClient client = ApproovOkHttp.protect(new OkHttpClient.Builder()
                .dns(dns)
                .socketFactory(socketFactory)
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .build();
        HttpUrl url = new HttpUrl.Builder().scheme("http").host(HOST).port(server.getPort())
                .addPathSegment("fallback").build();

        long start = System.nanoTime();
        String body;
        try (Response response = client.newCall(new Request.Builder().url(url).build()).execute()) {
            assertEquals(200, response.code());
            body = response.body().string();
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // the response came from the test server, which only the second address reaches
        assertEquals("reached", body);
        RecordedRequest recorded = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(recorded);
        assertEquals("/fallback", recorded.getPath());

        // the unanswered address was tried first, so the call did depend on falling back
        List<InetAddress> attempts = socketFactory.attempts();
        assertEquals(Arrays.asList(unanswered, loopback), attempts);
        assertTrue("call took " + elapsedMillis + " ms with a connect timeout of "
                + CONNECT_TIMEOUT_SECONDS + " s", elapsedMillis < FALLBACK_LIMIT_MILLIS);

        // the attempt that lost is given up rather than left to run to the connect timeout
        assertTrue("the unanswered connect was not given up",
                socketFactory.awaitUnansweredGivenUp(FALLBACK_LIMIT_MILLIS));
    }

    // BlackHoleSocketFactory creates sockets whose connect to one address never completes.
    private static final class BlackHoleSocketFactory extends SocketFactory {
        // the address that never completes a connect
        private final InetAddress unanswered;

        // the addresses that a connect was started to, in order
        private final List<InetAddress> attempts = Collections.synchronizedList(new ArrayList<>());

        // counted down when a blocked connect ends because its socket was closed
        private final CountDownLatch givenUp = new CountDownLatch(1);

        // counted down to end every blocked connect when the test is over
        private final CountDownLatch released = new CountDownLatch(1);

        BlackHoleSocketFactory(InetAddress unanswered) {
            this.unanswered = unanswered;
        }

        List<InetAddress> attempts() {
            synchronized (attempts) {
                return new ArrayList<>(attempts);
            }
        }

        boolean awaitUnansweredGivenUp(long timeoutMillis) throws InterruptedException {
            return givenUp.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        void release() {
            released.countDown();
        }

        @Override
        public Socket createSocket() {
            return new Socket() {
                // counted down when this socket is closed
                private final CountDownLatch closed = new CountDownLatch(1);

                @Override
                public void connect(SocketAddress endpoint, int timeout) throws IOException {
                    InetAddress address = ((InetSocketAddress) endpoint).getAddress();
                    attempts.add(address);
                    if (!unanswered.equals(address)) {
                        super.connect(endpoint, timeout);
                        return;
                    }

                    // block as a connect to an address that drops every packet does, until the timeout
                    // passes or the socket is closed
                    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
                    try {
                        while ((timeout == 0) || (System.nanoTime() < deadline)) {
                            if (closed.await(10, TimeUnit.MILLISECONDS)) {
                                givenUp.countDown();
                                throw new SocketException("Socket closed");
                            }
                            if (released.getCount() == 0)
                                throw new SocketException("Socket closed");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException("connect interrupted");
                    }
                    throw new SocketTimeoutException("connect timed out");
                }

                @Override
                public synchronized void close() throws IOException {
                    closed.countDown();
                    super.close();
                }
            };
        }

        @Override
        public Socket createSocket(String host, int port) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Socket createSocket(InetAddress host, int port) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) {
            throw new UnsupportedOperationException();
        }
    }
}
