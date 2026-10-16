package com.getoapi;

import lombok.Value;

@Value
public class Payload
{
	public static final int BUY = 0;
	public static final int SELL = 1;

	// Random UUID; lets the API ignore a retry of a transaction it already stored.
	// Null for transactions queued before this field existed.
	String id;
	// All timestamps are ISO-8601 UTC, e.g. 2026-10-16T12:34:56.789Z
	// When the transaction finished; same as endTime
	String date;
	// When the offer was placed; null if it was already in the slot at login
	String startTime;
	// When the offer finished (or was cancelled); for offers that finished while logged out,
	// this is when the plugin first saw them, i.e. the next login
	String endTime;
	// RuneLite account hash; unique per account and survives name changes
	long playerId;
	// Zero-based GE slot; null for transactions queued before this field existed
	Integer slot;
	int itemId;
	int quantity;
	// 0 = buy, 1 = sell
	int type;
	// total gp spent (buy) or received (sell) for the quantity actually traded
	long total;
	// GrandExchangeOfferState name, e.g. BOUGHT or CANCELLED_SELL; null for older transactions
	String state;
}
