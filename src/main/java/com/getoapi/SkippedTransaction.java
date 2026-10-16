package com.getoapi;

import lombok.Value;

/**
 * A finished transaction that was not posted because its slot was unticked or sending was off.
 */
@Value
public class SkippedTransaction
{
	// 1-based, as shown in the config
	int slot;
	String itemName;
	Payload payload;
}
