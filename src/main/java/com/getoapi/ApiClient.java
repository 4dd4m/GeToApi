package com.getoapi;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
@Singleton
public class ApiClient
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final String TRANSACTIONS_PATH = "transactions";
	private static final String SLOTS_PATH = "slots";

	private final OkHttpClient httpClient;
	private final Gson gson;

	@Inject
	public ApiClient(OkHttpClient httpClient, Gson gson)
	{
		this.httpClient = httpClient;
		// Send unknown values (e.g. startTime) as explicit nulls rather than leaving the field out
		this.gson = gson.newBuilder().serializeNulls().create();
	}

	/**
	 * Posts payload to {@code <baseUrl>/transactions} asynchronously; onDone receives true if the
	 * API answered with a 2xx status.
	 */
	public void post(String baseUrl, Payload payload, Consumer<Boolean> onDone)
	{
		send(baseUrl, TRANSACTIONS_PATH, "POST", payload, "transaction", onDone);
	}

	/**
	 * Replaces the account's live GE slots via {@code PUT <baseUrl>/slots}; onDone receives true
	 * if the API answered with a 2xx status.
	 */
	public void putSlots(String baseUrl, SlotsSnapshot snapshot, Consumer<Boolean> onDone)
	{
		send(baseUrl, SLOTS_PATH, "PUT", snapshot, "slots", onDone);
	}

	private void send(String baseUrl, String path, String method, Object body, String what, Consumer<Boolean> onDone)
	{
		HttpUrl base = HttpUrl.parse(baseUrl == null ? "" : baseUrl.trim());
		if (base == null)
		{
			log.warn("Invalid API URL '{}', {} not sent", baseUrl, what);
			onDone.accept(false);
			return;
		}
		HttpUrl url = base.newBuilder().addPathSegment(path).build();

		String json = gson.toJson(body);
		Request request = new Request.Builder()
			.url(url)
			.method(method, RequestBody.create(JSON, json))
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Failed to send {} {} to {}: {}", what, json, url, e.getMessage());
				onDone.accept(false);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (response)
				{
					if (response.isSuccessful())
					{
						log.debug("Sent {} {} to {} ({})", what, json, url, response.code());
					}
					else
					{
						log.warn("API rejected {} {}: HTTP {}", what, json, response.code());
					}
					onDone.accept(response.isSuccessful());
				}
			}
		});
	}
}
