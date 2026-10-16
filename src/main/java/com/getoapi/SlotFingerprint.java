package com.getoapi;

import java.util.function.IntFunction;
import lombok.Value;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

/**
 * The stored record of the finished offer last handled in a GE slot.
 * Stored as "itemId:state:price:totalQuantity:quantitySold:spent".
 */
@Value
public class SlotFingerprint
{
	int itemId;
	String itemName;
	GrandExchangeOfferState state;
	int price;
	int totalQuantity;
	int quantitySold;
	long spent;

	static String of(GrandExchangeOffer offer)
	{
		return offer.getItemId() + ":" + offer.getState() + ":" + offer.getPrice() + ":"
			+ offer.getTotalQuantity() + ":" + offer.getQuantitySold() + ":" + offer.getSpent();
	}

	/**
	 * @return the parsed fingerprint, or null if it isn't in the expected format
	 */
	static SlotFingerprint parse(String stored, IntFunction<String> itemNames)
	{
		String[] parts = stored.split(":");
		if (parts.length != 6)
		{
			return null;
		}

		try
		{
			int itemId = Integer.parseInt(parts[0]);
			return new SlotFingerprint(itemId, itemNames.apply(itemId), GrandExchangeOfferState.valueOf(parts[1]),
				Integer.parseInt(parts[2]), Integer.parseInt(parts[3]), Integer.parseInt(parts[4]),
				Long.parseLong(parts[5]));
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}
}
