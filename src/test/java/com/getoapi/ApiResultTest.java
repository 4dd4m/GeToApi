package com.getoapi;

import static org.junit.Assert.assertEquals;

import com.getoapi.ApiResult.Outcome;
import org.junit.Test;

public class ApiResultTest
{
	@Test
	public void classifiesStatusCodes()
	{
		assertEquals(Outcome.OK, ApiResult.of(200).getOutcome());
		assertEquals(Outcome.OK, ApiResult.of(201).getOutcome());
		assertEquals(Outcome.OK, ApiResult.of(204).getOutcome());
		assertEquals(Outcome.DUPLICATE, ApiResult.of(409).getOutcome());
		assertEquals(Outcome.NOT_IMPLEMENTED, ApiResult.of(404).getOutcome());
		assertEquals(Outcome.NOT_IMPLEMENTED, ApiResult.of(405).getOutcome());
		assertEquals(Outcome.NOT_IMPLEMENTED, ApiResult.of(501).getOutcome());
		assertEquals(Outcome.REJECTED, ApiResult.of(400).getOutcome());
		assertEquals(Outcome.REJECTED, ApiResult.of(422).getOutcome());
		assertEquals(Outcome.FAILED, ApiResult.of(401).getOutcome());
		assertEquals(Outcome.FAILED, ApiResult.of(403).getOutcome());
		assertEquals(Outcome.FAILED, ApiResult.of(429).getOutcome());
		assertEquals(Outcome.FAILED, ApiResult.of(500).getOutcome());
		assertEquals(Outcome.FAILED, ApiResult.of(503).getOutcome());
		assertEquals(Outcome.FAILED, ApiResult.of(302).getOutcome());
		assertEquals(Outcome.FAILED, ApiResult.noResponse().getOutcome());
	}
}
