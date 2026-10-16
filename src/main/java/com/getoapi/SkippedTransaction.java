package com.getoapi;

import lombok.Value;

/**
 * A finished transaction that was not posted: its slot was unticked, sending was off, or the
 * API rejected it. It can still be sent by hand from the panel.
 */
@Value
public class SkippedTransaction
{
	// 1-based, as shown in the config; 0 if unknown
	int slot;
	String itemName;
	Payload payload;
	// Why it wasn't sent; null for entries saved before this field existed
	String reason;
}
