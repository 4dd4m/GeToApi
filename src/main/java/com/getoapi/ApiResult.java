package com.getoapi;

import lombok.Value;

/**
 * How the API answered a request, so callers can tell a missing endpoint or a rejected payload
 * apart from the API simply being down.
 */
@Value
public class ApiResult
{
	public enum Outcome
	{
		// 2xx
		OK,
		// 409: the API already has it (e.g. a transaction it stored before)
		DUPLICATE,
		// 404, 405, 501: the API doesn't implement this endpoint
		NOT_IMPLEMENTED,
		// Any other 4xx except auth and rate limits: this payload will never be accepted as it is
		REJECTED,
		// Network error, timeout, 5xx, 401/403, 408, 429, or an invalid API URL: worth trying again later
		FAILED,
	}

	Outcome outcome;
	// HTTP status, or -1 if no response was received
	int code;

	static ApiResult of(int code)
	{
		if (code >= 200 && code < 300)
		{
			return new ApiResult(Outcome.OK, code);
		}
		switch (code)
		{
			case 409:
				return new ApiResult(Outcome.DUPLICATE, code);
			case 404:
			case 405:
			case 501:
				return new ApiResult(Outcome.NOT_IMPLEMENTED, code);
			case 401:
			case 403:
			case 408:
			case 429:
				return new ApiResult(Outcome.FAILED, code);
			default:
				return new ApiResult(code >= 400 && code < 500 ? Outcome.REJECTED : Outcome.FAILED, code);
		}
	}

	static ApiResult noResponse()
	{
		return new ApiResult(Outcome.FAILED, -1);
	}
}
