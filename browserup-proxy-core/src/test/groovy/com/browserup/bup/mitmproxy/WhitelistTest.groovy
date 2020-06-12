/*
 * Modifications Copyright (c) 2019 BrowserUp, Inc.
 */

package com.browserup.bup.mitmproxy

import com.browserup.bup.MitmProxyServer
import com.browserup.bup.filters.WhitelistFilter
import com.browserup.bup.proxy.test.util.MockServerTest
import com.browserup.bup.proxy.test.util.NewProxyServerTestUtil
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpVersion
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.client.methods.HttpGet
import org.junit.After
import org.junit.Test

import static com.github.tomakehurst.wiremock.client.WireMock.*
import static org.hamcrest.Matchers.isEmptyOrNullString
import static org.junit.Assert.*
import static org.mockito.Mockito.mock
import static org.mockito.Mockito.when

class WhitelistTest extends MockServerTest {
    MitmProxyServer proxy

    @After
    void tearDown() {
        if (proxy?.started) {
            proxy.abort()
        }
    }

    @Test
    void testWhitelistCannotShortCircuitCONNECT() {
        HttpRequest request = mock(HttpRequest.class)
        when(request.method()).thenReturn(HttpMethod.CONNECT)
        when(request.uri()).thenReturn('somedomain.com:443')
        when(request.protocolVersion()).thenReturn(HttpVersion.HTTP_1_1)
        ChannelHandlerContext mockCtx = mock(ChannelHandlerContext)

        // create a whitelist filter that whitelists no requests (i.e., all requests should return the specified HTTP 500 status code)
        WhitelistFilter filter = new WhitelistFilter(request, mockCtx, true, 500, [])
        HttpResponse response = filter.clientToProxyRequest(request)

        assertNull("Whitelist short-circuited HTTP CONNECT. Expected all HTTP CONNECTs to be whitelisted.", response)
    }

    @Test
    void testNonWhitelistedHttpRequestReturnsWhitelistStatusCode() {
        proxy = new MitmProxyServer()
        proxy.start()
        int proxyPort = proxy.getPort()

        proxy.whitelistRequests(["http://localhost/.*"], 500)

        NewProxyServerTestUtil.getNewHttpClient(proxyPort).withCloseable {
            CloseableHttpResponse response = it.execute(new HttpGet("http://www.someother.domain/someresource"))
            assertEquals("Did not receive whitelist status code in response", 500, response.getStatusLine().getStatusCode())

            String responseBody = NewProxyServerTestUtil.toStringAndClose(response.getEntity().getContent())
            assertThat("Expected whitelist response to contain 0-length body", responseBody, isEmptyOrNullString())
        }
    }

    @Test
    void testNonWhitelistedHttpsRequestReturnsWhitelistStatusCode() {
        String url = "/nonwhitelistedresource"

        stubFor(get(urlEqualTo(url)).willReturn(ok().withBody("should never be returned")))

        proxy = new MitmProxyServer()
        proxy.setTrustAllServers(true)
        proxy.start()
        int proxyPort = proxy.getPort()

        proxy.whitelistRequests(["https://some-other-domain/.*"], 500)

        NewProxyServerTestUtil.getNewHttpClient(proxyPort).withCloseable {
            CloseableHttpResponse response = it.execute(new HttpGet("https://localhost:${mockServerHttpsPort}/nonwhitelistedresource"))
            assertEquals("Did not receive whitelist status code in response", 500, response.getStatusLine().getStatusCode())

            String responseBody = NewProxyServerTestUtil.toStringAndClose(response.getEntity().getContent())
            assertThat("Expected whitelist response to contain 0-length body", responseBody, isEmptyOrNullString())
        }
    }

    @Test
    void testWhitelistedHttpRequestNotShortCircuited() {
        String url = "/whitelistedresource"

        stubFor(get(urlEqualTo(url)).willReturn(ok().withBody("whitelisted")))

        proxy = new MitmProxyServer()
        proxy.start()
        int proxyPort = proxy.getPort()

        proxy.whitelistRequests(["http://localhost:${mockServerPort}/.*".toString()], 500)

        NewProxyServerTestUtil.getNewHttpClient(proxyPort).withCloseable {
            CloseableHttpResponse response = it.execute(new HttpGet("http://localhost:${mockServerPort}/whitelistedresource"))
            assertEquals("Did not receive expected response from mock server for whitelisted url", 200, response.getStatusLine().getStatusCode())

            String responseBody = NewProxyServerTestUtil.toStringAndClose(response.getEntity().getContent())
            assertEquals("Did not receive expected response body from mock server for whitelisted url", "whitelisted", responseBody)
        }
    }

    @Test
    void testWhitelistedHttpsRequestNotShortCircuited() {
        String url = "/whitelistedresource"

        stubFor(get(urlEqualTo(url)).willReturn(ok().withBody("whitelisted")))

        proxy = new MitmProxyServer()
        proxy.setTrustAllServers(true)
        proxy.start()
        int proxyPort = proxy.getPort()

        proxy.whitelistRequests(["https://localhost:${mockServerHttpsPort}/.*".toString()], 500)

        NewProxyServerTestUtil.getNewHttpClient(proxyPort).withCloseable {
            CloseableHttpResponse response = it.execute(new HttpGet("https://localhost:${mockServerHttpsPort}/whitelistedresource"))
            assertEquals("Did not receive expected response from mock server for whitelisted url", 200, response.getStatusLine().getStatusCode())

            String responseBody = NewProxyServerTestUtil.toStringAndClose(response.getEntity().getContent())
            assertEquals("Did not receive expected response body from mock server for whitelisted url", "whitelisted", responseBody)
        }
    }

    @Test
    void testCanWhitelistSpecificHttpResource() {
        String url = "/whitelistedresource"

        stubFor(get(urlEqualTo(url)).willReturn(ok().withBody("whitelisted")))

        String url2 = "/nonwhitelistedresource"

        stubFor(get(urlEqualTo(url2)).willReturn(ok().withBody("should never be returned")))

        proxy = new MitmProxyServer()
        proxy.start()
        int proxyPort = proxy.getPort()

        proxy.whitelistRequests(["http://localhost:${mockServerPort}/whitelistedresource".toString()], 500)

        NewProxyServerTestUtil.getNewHttpClient(proxyPort).withCloseable {
            CloseableHttpResponse nonWhitelistedResponse = it.execute(new HttpGet("http://localhost:${mockServerPort}/nonwhitelistedresource"))
            assertEquals("Did not receive whitelist status code in response", 500, nonWhitelistedResponse.getStatusLine().getStatusCode())

            String nonWhitelistedResponseBody = NewProxyServerTestUtil.toStringAndClose(nonWhitelistedResponse.getEntity().getContent())
            assertThat("Expected whitelist response to contain 0-length body", nonWhitelistedResponseBody, isEmptyOrNullString())

            CloseableHttpResponse whitelistedResponse = it.execute(new HttpGet("http://localhost:${mockServerPort}/whitelistedresource"))
            assertEquals("Did not receive expected response from mock server for whitelisted url", 200, whitelistedResponse.getStatusLine().getStatusCode())

            String whitelistedResponseBody = NewProxyServerTestUtil.toStringAndClose(whitelistedResponse.getEntity().getContent())
            assertEquals("Did not receive expected response body from mock server for whitelisted url", "whitelisted", whitelistedResponseBody)
        }
    }

    @Test
    void testCanWhitelistSpecificHttpsResource() {
        String url = "/whitelistedresource"

        stubFor(get(urlEqualTo(url)).willReturn(ok().withBody("whitelisted")))

        String url2 = "/nonwhitelistedresource"

        stubFor(get(urlEqualTo(url2)).willReturn(ok().withBody("should never be returned")))

        proxy = new MitmProxyServer()
        proxy.setTrustAllServers(true)
        proxy.start()
        int proxyPort = proxy.getPort()

        proxy.whitelistRequests(["https://localhost:${mockServerHttpsPort}/whitelistedresource".toString()], 500)

        NewProxyServerTestUtil.getNewHttpClient(proxyPort).withCloseable {
            CloseableHttpResponse nonWhitelistedResponse = it.execute(new HttpGet("https://localhost:${mockServerHttpsPort}/nonwhitelistedresource"))
            assertEquals("Did not receive whitelist status code in response", 500, nonWhitelistedResponse.getStatusLine().getStatusCode())

            String nonWhitelistedResponseBody = NewProxyServerTestUtil.toStringAndClose(nonWhitelistedResponse.getEntity().getContent())
            assertThat("Expected whitelist response to contain 0-length body", nonWhitelistedResponseBody, isEmptyOrNullString())

            CloseableHttpResponse whitelistedResponse = it.execute(new HttpGet("https://localhost:${mockServerHttpsPort}/whitelistedresource"))
            assertEquals("Did not receive expected response from mock server for whitelisted url", 200, whitelistedResponse.getStatusLine().getStatusCode())

            String whitelistedResponseBody = NewProxyServerTestUtil.toStringAndClose(whitelistedResponse.getEntity().getContent())
            assertEquals("Did not receive expected response body from mock server for whitelisted url", "whitelisted", whitelistedResponseBody)
        }
    }
}
