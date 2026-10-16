package com.getoapi;

import static org.junit.Assert.assertEquals;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Runs the real client against a local server that only implements some endpoints.
 */
public class ApiClientTest
{
	private HttpServer server;
	private ApiClient client;
	private String base;

	@Before
	public void start() throws Exception
	{
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		// /transactions exists and rejects one item id; /slots doesn't exist (default 404)
		server.createContext("/transactions", exchange ->
		{
			String body = new String(exchange.getRequestBody().readAllBytes());
			int code = body.contains("\"itemId\":999") ? 400 : 201;
			exchange.sendResponseHeaders(code, -1);
			exchange.close();
		});
		server.start();
		base = "http://127.0.0.1:" + server.getAddress().getPort();
		client = new ApiClient(new OkHttpClient(), new Gson());
	}

	@After
	public void stop()
	{
		server.stop(0);
	}

	private ApiResult post(String url, int itemId) throws Exception
	{
		CompletableFuture<ApiResult> result = new CompletableFuture<>();
		client.post(url, new Payload("id", "d", null, "d", 1L, 0, itemId, 1, Payload.BUY, 10L, "BOUGHT"), result::complete);
		return result.get(10, TimeUnit.SECONDS);
	}

	@Test
	public void storedTransactionIsOk() throws Exception
	{
		assertEquals(new ApiResult(ApiResult.Outcome.OK, 201), post(base, 560));
	}

	@Test
	public void rejectedTransaction() throws Exception
	{
		assertEquals(new ApiResult(ApiResult.Outcome.REJECTED, 400), post(base, 999));
	}

	@Test
	public void missingSlotsEndpointIsNotImplemented() throws Exception
	{
		CompletableFuture<ApiResult> result = new CompletableFuture<>();
		client.putSlots(base, new SlotsSnapshot("1", List.of()), result::complete);
		assertEquals(new ApiResult(ApiResult.Outcome.NOT_IMPLEMENTED, 404), result.get(10, TimeUnit.SECONDS));
	}

	@Test
	public void nothingListeningOrBadUrlIsFailed() throws Exception
	{
		server.stop(0);
		assertEquals(ApiResult.noResponse(), post(base, 560));
		assertEquals(ApiResult.noResponse(), post("not a url", 560));
		assertEquals(ApiResult.noResponse(), post(null, 560));
	}
}
