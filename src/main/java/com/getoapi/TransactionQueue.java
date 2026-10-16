package com.getoapi;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Transactions waiting to be accepted by the API. Persisted in the RuneLite config so
 * nothing is lost if the API is down or the client is closed.
 */
@Slf4j
@Singleton
public class TransactionQueue
{
	static final String KEY = "queue";
	private static final Type LIST_TYPE = new TypeToken<List<Payload>>(){}.getType();

	private final ConfigManager configManager;
	private final ScheduledExecutorService executor;
	private final Gson gson;
	private final Deque<Payload> items = new ArrayDeque<>();
	private Runnable listener = () -> {};

	@Inject
	public TransactionQueue(ConfigManager configManager, ScheduledExecutorService executor, Gson gson)
	{
		this.configManager = configManager;
		this.executor = executor;
		this.gson = gson;
	}

	public synchronized void load()
	{
		items.clear();
		String json = configManager.getConfiguration(ApiConfig.GROUP, KEY);
		if (json != null)
		{
			try
			{
				List<Payload> saved = gson.fromJson(json, LIST_TYPE);
				if (saved != null)
				{
					items.addAll(saved);
				}
			}
			catch (JsonSyntaxException e)
			{
				log.warn("Discarding unreadable transaction queue", e);
			}
		}
		listener.run();
	}

	/**
	 * Called whenever the queue changes, from whichever thread changed it.
	 */
	public synchronized void setListener(Runnable listener)
	{
		this.listener = listener;
	}

	public synchronized void add(Payload payload)
	{
		items.addLast(payload);
		save();
	}

	public synchronized Payload peek()
	{
		return items.peekFirst();
	}

	/**
	 * Removes payload if it is still at the head; it may be gone if the queue was cleared mid-send.
	 */
	public synchronized void remove(Payload payload)
	{
		if (items.peekFirst() == payload)
		{
			items.removeFirst();
			save();
		}
	}

	public synchronized void clear()
	{
		items.clear();
		save();
	}

	public synchronized int size()
	{
		return items.size();
	}

	private void save()
	{
		if (items.isEmpty())
		{
			configManager.unsetConfiguration(ApiConfig.GROUP, KEY);
		}
		else
		{
			configManager.setConfiguration(ApiConfig.GROUP, KEY, gson.toJson(items));
		}
		executor.execute(configManager::sendConfig);
		listener.run();
	}
}
