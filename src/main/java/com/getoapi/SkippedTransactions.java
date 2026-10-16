package com.getoapi;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Transactions that were fingerprinted but not posted, kept so they can still be sent by hand.
 * Persisted in the RuneLite config; only the most recent {@link #MAX_ENTRIES} are kept.
 */
@Slf4j
@Singleton
public class SkippedTransactions
{
	static final String KEY = "skipped";
	private static final int MAX_ENTRIES = 50;
	private static final Type LIST_TYPE = new TypeToken<List<SkippedTransaction>>(){}.getType();

	private final ConfigManager configManager;
	private final ScheduledExecutorService executor;
	private final Gson gson;
	private final List<SkippedTransaction> items = new ArrayList<>();
	private Runnable listener = () -> {};

	@Inject
	public SkippedTransactions(ConfigManager configManager, ScheduledExecutorService executor, Gson gson)
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
				List<SkippedTransaction> saved = gson.fromJson(json, LIST_TYPE);
				if (saved != null)
				{
					items.addAll(saved);
				}
			}
			catch (JsonSyntaxException e)
			{
				log.warn("Discarding unreadable skipped transactions", e);
			}
		}
		listener.run();
	}

	/**
	 * Called whenever the list changes, from whichever thread changed it.
	 */
	public synchronized void setListener(Runnable listener)
	{
		this.listener = listener;
	}

	public synchronized void add(SkippedTransaction entry)
	{
		items.add(entry);
		while (items.size() > MAX_ENTRIES)
		{
			items.remove(0);
		}
		save();
	}

	/**
	 * @return true if the entry was still in the list
	 */
	public synchronized boolean remove(SkippedTransaction entry)
	{
		// Identity, not equals: two identical trades are still separate entries
		boolean removed = items.removeIf(e -> e == entry);
		if (removed)
		{
			save();
		}
		return removed;
	}

	/**
	 * @return a snapshot, newest first
	 */
	public synchronized List<SkippedTransaction> getAll()
	{
		List<SkippedTransaction> snapshot = new ArrayList<>(items);
		Collections.reverse(snapshot);
		return snapshot;
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
