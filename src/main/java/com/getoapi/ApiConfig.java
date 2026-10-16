package com.getoapi;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(ApiConfig.GROUP)
public interface ApiConfig extends Config
{
	String GROUP = "getoapi";

	@ConfigItem(
		keyName = "enabled",
		name = "Send transactions",
		description = "Post finished Grand Exchange transactions to the API endpoint",
		position = 0
	)
	default boolean enabled()
	{
		return false;
	}

	@ConfigItem(
		keyName = "address",
		name = "API URL",
		description = "Base URL of the API; all database access goes through it",
		position = 1
	)
	default String address()
	{
		return "http://127.0.0.1:5000";
	}
}
