package io.approov.service.android.okhttp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import io.approov.service.android.TestAccess;
import okhttp3.OkHttpClient;

/**
 * Every OkHttpClient that comes out of the layer keeps OkHttp's fast fallback between the addresses of a
 * host: it is on unless the app turned it off on its own builder, and the layer never changes the choice.
 */
public class OkHttpFastFallbackTest {
    @Before
    public void setUp() {
        TestAccess.resetApproovService();
        ApproovOkHttp.resetForTesting();
    }

    @After
    public void tearDown() {
        ApproovOkHttp.resetForTesting();
        TestAccess.resetApproovService();
    }

    @Test
    public void protectedBuilderHasFastFallback() {
        OkHttpClient client = ApproovOkHttp.protect(new OkHttpClient.Builder()).build();

        assertTrue(client.fastFallback());
    }

    @Test
    public void defaultAndNamedClientsHaveFastFallback() {
        assertTrue(ApproovOkHttp.getOkHttpClient().fastFallback());
        assertTrue(ApproovOkHttp.getOkHttpClient("named").fastFallback());
    }

    @Test
    public void clientFromAppBuilderHasFastFallback() {
        ApproovOkHttp.setOkHttpClientBuilder(new OkHttpClient.Builder());
        ApproovOkHttp.setOkHttpClientBuilder("named", new OkHttpClient.Builder());

        assertTrue(ApproovOkHttp.getOkHttpClient().fastFallback());
        assertTrue(ApproovOkHttp.getOkHttpClient("named").fastFallback());
    }

    @Test
    public void wovenClientHasFastFallback() {
        OkHttpClient client = ApproovOkHttpWeaver.build(new OkHttpClient.Builder());

        assertTrue(client.fastFallback());
    }

    @Test
    public void clientBuiltAfterInitializeHasFastFallback() {
        TestAccess.enableProtectionAndNotifyPinsChanged();

        assertTrue(ApproovOkHttp.getOkHttpClient().fastFallback());
        assertTrue(ApproovOkHttp.protect(new OkHttpClient.Builder()).build().fastFallback());
        assertTrue(ApproovOkHttpWeaver.build(new OkHttpClient.Builder()).fastFallback());
    }

    @Test
    public void protectKeepsFastFallbackTurnedOffByTheApp() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder().fastFallback(false);

        assertFalse(ApproovOkHttp.protect(builder).build().fastFallback());
        // protecting the same builder again does not turn it back on
        assertFalse(ApproovOkHttp.protect(builder).build().fastFallback());
    }

    @Test
    public void weaverKeepsFastFallbackTurnedOffByTheApp() {
        OkHttpClient client = ApproovOkHttpWeaver.build(new OkHttpClient.Builder().fastFallback(false));

        assertFalse(client.fastFallback());
    }

    @Test
    public void appBuilderKeepsFastFallbackTurnedOffByTheApp() {
        ApproovOkHttp.setOkHttpClientBuilder(new OkHttpClient.Builder().fastFallback(false));
        ApproovOkHttp.setOkHttpClientBuilder("named", new OkHttpClient.Builder().fastFallback(false));

        assertFalse(ApproovOkHttp.getOkHttpClient().fastFallback());
        assertFalse(ApproovOkHttp.getOkHttpClient("named").fastFallback());
        // a client with another name is not affected by the app's choice for these builders
        assertTrue(ApproovOkHttp.getOkHttpClient("other").fastFallback());
    }

    @Test
    public void clientDerivedFromProtectedClientKeepsTheChoice() {
        OkHttpClient on = ApproovOkHttp.protect(new OkHttpClient.Builder()).build();
        OkHttpClient off = ApproovOkHttp.protect(new OkHttpClient.Builder().fastFallback(false)).build();

        assertTrue(ApproovOkHttpWeaver.build(on.newBuilder()).fastFallback());
        assertFalse(ApproovOkHttpWeaver.build(off.newBuilder()).fastFallback());
    }
}
