package com.getoapi;

import java.util.List;
import lombok.Value;

/**
 * The live state of every occupied GE slot of one account, sent to {@code PUT <api>/slots}.
 * Slots that aren't listed are empty.
 */
@Value
public class SlotsSnapshot
{
	// As a string: account hashes exceed JavaScript's safe integers on the UI side
	String accountHash;
	List<Slot> slots;

	@Value
	public static class Slot
	{
		// Zero-based GE slot
		int slot;
		int itemId;
		// GrandExchangeOfferState name, e.g. BUYING or CANCELLED_SELL
		String state;
		// Offer price per item
		long price;
		int totalQuantity;
		int quantitySold;
		long spent;
		// ISO-8601 UTC when the offer was placed; null if it was already in the slot at login
		String startTime;
	}
}
