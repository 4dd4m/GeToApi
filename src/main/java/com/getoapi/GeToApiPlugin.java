package com.getoapi;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

@Slf4j
@PluginDescriptor(
	name = "GE To API",
	description = "Posts finished Grand Exchange transactions to a custom API endpoint",
	tags = {"ge", "grand exchange", "api", "export"}
)
public class GeToApiPlugin extends Plugin
{
	// While logging in the client reports every slot as EMPTY before the real offers arrive,
	// so EMPTY is only trusted as "offer collected" once this many ticks have passed.
	private static final int LOGIN_GRACE_TICKS = 2;
	private static final int RETRY_SECONDS = 30;
	static final int SLOT_COUNT = 8;

	@Inject
	private Client client;

	@Inject
	private ApiConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private ApiClient apiClient;

	@Inject
	private TransactionQueue queue;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ClientThread clientThread;

	@Inject
	private SkippedTransactions skipped;

	@Inject
	private ItemManager itemManager;

	@Inject
	private Gson gson;

	// Slots that changed before the account was known; handled once it is
	private final Set<Integer> pendingSlots = new HashSet<>();
	private int ticksSinceLogin;

	// True while a POST is in flight; the queue is sent one item at a time, in order
	private final AtomicBoolean sending = new AtomicBoolean();
	// The live slots changed and should be sent to the API (at most once per game tick)
	private boolean slotsDirty;
	// The last slots update failed; the retry task marks the slots dirty again
	private final AtomicBoolean slotsFailed = new AtomicBoolean();
	private ScheduledFuture<?> retryTask;
	private NavigationButton navButton;
	private GeToApiPanel panel;

	@Override
	protected void startUp()
	{
		panel = new GeToApiPanel(
			queue::clear,
			onDone -> clientThread.invoke(() ->
			{
				int marked = markCurrentSlotsHandled();
				SwingUtilities.invokeLater(() -> onDone.accept(marked));
			}),
			this::sendSkipped,
			skipped::remove,
			this::setSlotEnabled,
			slot -> clientThread.invoke(() -> clearSlotFingerprint(slot)));
		for (int slot = 0; slot < SLOT_COUNT; slot++)
		{
			panel.setSlotEnabled(slot, slotEnabled(slot));
		}
		navButton = NavigationButton.builder()
			.tooltip("GE To API")
			.icon(createIcon())
			.priority(10)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		queue.setListener(() ->
		{
			int size = queue.size();
			SwingUtilities.invokeLater(() -> panel.setQueueSize(size));
		});
		queue.load();

		skipped.setListener(() ->
		{
			List<SkippedTransaction> entries = skipped.getAll();
			SwingUtilities.invokeLater(() -> panel.setSkipped(entries));
		});
		skipped.load();

		clientThread.invoke(this::refreshSlotsView);

		retryTask = executor.scheduleWithFixedDelay(this::sendQueue, RETRY_SECONDS, RETRY_SECONDS, TimeUnit.SECONDS);
		sendQueue();
	}

	@Override
	protected void shutDown()
	{
		retryTask.cancel(false);
		queue.setListener(() -> {});
		skipped.setListener(() -> {});
		clientToolbar.removeNavigation(navButton);
		pendingSlots.clear();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (ApiConfig.GROUP.equals(event.getGroup())
			&& ("enabled".equals(event.getKey()) || "address".equals(event.getKey())))
		{
			sendQueue();
			clientThread.invoke(() -> slotsDirty = true);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			ticksSinceLogin = 0;
			refreshSlotsView();
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			pendingSlots.clear();
			refreshSlotsView();
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		ticksSinceLogin++;
		if (ticksSinceLogin == LOGIN_GRACE_TICKS + 1)
		{
			// The login replay is over: sync all slots, which also clears offers collected elsewhere
			slotsDirty = true;
		}
		if (slotsDirty && isLive() && client.getAccountHash() != -1)
		{
			slotsDirty = false;
			sendSlots();
		}

		if (pendingSlots.isEmpty() || client.getAccountHash() == -1)
		{
			return;
		}

		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		for (int slot : pendingSlots)
		{
			handleOffer(slot, offers[slot]);
		}
		pendingSlots.clear();
		// The account just became known, so its fingerprints can be shown
		refreshSlotsView();
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		if (client.getAccountHash() == -1)
		{
			// Fingerprints are stored per account, so wait until we know which one this is
			pendingSlots.add(event.getSlot());
			return;
		}

		handleOffer(event.getSlot(), event.getOffer());
	}

	private void handleOffer(int slot, GrandExchangeOffer offer)
	{
		GrandExchangeOfferState state = offer.getState();
		slotsDirty = true;

		if (state == GrandExchangeOfferState.EMPTY)
		{
			if (isLive())
			{
				configManager.unsetConfiguration(ApiConfig.GROUP, slotKey(slot));
				configManager.unsetConfiguration(ApiConfig.GROUP, startKey(slot));
				refreshSlotsView();
			}
			return;
		}

		String startTime = trackStartTime(slot, offer);

		int type;
		switch (state)
		{
			case BOUGHT:
			case CANCELLED_BUY:
				type = Payload.BUY;
				break;
			case SOLD:
			case CANCELLED_SELL:
				type = Payload.SELL;
				break;
			default:
				// BUYING / SELLING: still in progress
				return;
		}

		int quantity = offer.getQuantitySold();
		if (quantity == 0)
		{
			// Cancelled before anything was traded
			return;
		}

		// Finished offers are re-sent on every login until collected, so remember what was
		// already handled per account and slot to avoid posting the same transaction twice.
		String fingerprint = SlotFingerprint.of(offer);
		String key = slotKey(slot);
		if (fingerprint.equals(configManager.getConfiguration(ApiConfig.GROUP, key)))
		{
			return;
		}
		configManager.setConfiguration(ApiConfig.GROUP, key, fingerprint);
		// Flush to disk now rather than on RuneLite's save timer, so a crash can't cause a repost
		executor.execute(configManager::sendConfig);
		refreshSlotsView();

		String now = Instant.now().toString();
		Payload payload = new Payload(UUID.randomUUID().toString(), now, startTime, now, client.getAccountHash(),
			slot, offer.getItemId(), quantity, type, offer.getSpent(),
			state.name());

		if (!config.enabled() || !slotEnabled(slot))
		{
			// Keep it so it can still be sent by hand from the panel
			String itemName = itemManager.getItemComposition(offer.getItemId()).getName();
			skipped.add(new SkippedTransaction(slot + 1, itemName, payload));
			log.debug("GE transaction in slot {} skipped: {}", slot + 1, payload);
			return;
		}

		log.debug("GE transaction finished in slot {}: {}", slot + 1, payload);
		queue.add(payload);
		sendQueue();
	}

	/**
	 * The game doesn't expose when an offer was placed, so remember when this offer first
	 * appeared in the slot. Only an offer that appears while we're watching gets a time; one
	 * that was already there at login (placed elsewhere or before the client started) is
	 * recorded as unknown, as its real start can't be known.
	 *
	 * @return ISO-8601 start time, or null if unknown
	 */
	private String trackStartTime(int slot, GrandExchangeOffer offer)
	{
		String identity = offerIdentity(offer);
		String key = startKey(slot);

		// Stored as "<identity>|<start time or empty>"
		String stored = configManager.getConfiguration(ApiConfig.GROUP, key);
		if (stored != null && stored.startsWith(identity + "|"))
		{
			String start = stored.substring(identity.length() + 1);
			return start.isEmpty() ? null : start;
		}

		// A different offer than the one recorded (or none), so this one is new
		String start = isLive() ? Instant.now().toString() : "";
		configManager.setConfiguration(ApiConfig.GROUP, key, identity + "|" + start);
		executor.execute(configManager::sendConfig);
		return start.isEmpty() ? null : start;
	}

	/**
	 * The start time recorded by {@link #trackStartTime} for the offer now in the slot, without
	 * recording anything.
	 *
	 * @return ISO-8601 start time, or null if unknown
	 */
	private String storedStartTime(int slot, GrandExchangeOffer offer)
	{
		String identity = offerIdentity(offer);
		String stored = configManager.getConfiguration(ApiConfig.GROUP, startKey(slot));
		if (stored == null || !stored.startsWith(identity + "|"))
		{
			return null;
		}
		String start = stored.substring(identity.length() + 1);
		return start.isEmpty() ? null : start;
	}

	private static String offerIdentity(GrandExchangeOffer offer)
	{
		return offer.getItemId() + ":" + (isBuy(offer.getState()) ? "buy" : "sell") + ":"
			+ offer.getPrice() + ":" + offer.getTotalQuantity();
	}

	/**
	 * Sends every occupied slot to the API so the UI can show them live. Must run on the
	 * client thread. Ignores the per-slot checkboxes, which only decide which transactions are sent.
	 */
	private void sendSlots()
	{
		if (!config.enabled())
		{
			return;
		}

		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		List<SlotsSnapshot.Slot> slots = new ArrayList<>();
		for (int slot = 0; slot < offers.length; slot++)
		{
			GrandExchangeOffer offer = offers[slot];
			if (offer == null || offer.getState() == GrandExchangeOfferState.EMPTY)
			{
				continue;
			}
			slots.add(new SlotsSnapshot.Slot(slot, offer.getItemId(), offer.getState().name(), offer.getPrice(),
				offer.getTotalQuantity(), offer.getQuantitySold(), offer.getSpent(), storedStartTime(slot, offer)));
		}

		SlotsSnapshot snapshot = new SlotsSnapshot(Long.toString(client.getAccountHash()), slots);

		// Only post when the slots differ from what the API last got for this account. The last
		// snapshot is kept in the config so a restart doesn't resend unchanged slots either.
		String key = slotsKey();
		String json = gson.toJson(snapshot);
		if (json.equals(configManager.getConfiguration(ApiConfig.GROUP, key)))
		{
			return;
		}
		// Recorded before sending so changes made while this is in flight compare against it
		configManager.setConfiguration(ApiConfig.GROUP, key, json);

		apiClient.putSlots(config.address(), snapshot, success ->
		{
			if (!success)
			{
				// Forget it (unless something newer was recorded since), so the retry sends it again
				clientThread.invoke(() ->
				{
					if (json.equals(configManager.getConfiguration(ApiConfig.GROUP, key)))
					{
						configManager.unsetConfiguration(ApiConfig.GROUP, key);
					}
				});
				slotsFailed.set(true);
			}
		});
	}

	private static boolean isBuy(GrandExchangeOfferState state)
	{
		return state == GrandExchangeOfferState.BUYING
			|| state == GrandExchangeOfferState.BOUGHT
			|| state == GrandExchangeOfferState.CANCELLED_BUY;
	}

	/**
	 * True once logged in long enough that slot updates are real changes, not the login replay.
	 */
	private boolean isLive()
	{
		return client.getGameState() == GameState.LOGGED_IN && ticksSinceLogin > LOGIN_GRACE_TICKS;
	}

	/**
	 * Moves a skipped transaction into the queue; it keeps its original date and ignores the
	 * slot checkboxes, but still waits for "Send transactions" to be on.
	 */
	private void sendSkipped(SkippedTransaction entry)
	{
		if (skipped.remove(entry))
		{
			queue.add(entry.getPayload());
			sendQueue();
		}
	}

	/**
	 * Rebuilds the stored fingerprints from the slots as they are right now, without sending
	 * anything: finished slots are marked as already sent, all other slots are cleared.
	 * Must run on the client thread.
	 *
	 * @return the number of finished slots marked, or -1 if the GE slots aren't loaded yet
	 */
	private int markCurrentSlotsHandled()
	{
		if (!isLive() || client.getAccountHash() == -1)
		{
			return -1;
		}

		// Anything still waiting from login would otherwise be posted after the slots were marked
		pendingSlots.clear();

		int marked = 0;
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		for (int slot = 0; slot < offers.length; slot++)
		{
			GrandExchangeOffer offer = offers[slot];
			if (isFinished(offer.getState()) && offer.getQuantitySold() > 0)
			{
				configManager.setConfiguration(ApiConfig.GROUP, slotKey(slot), SlotFingerprint.of(offer));
				marked++;
			}
			else
			{
				configManager.unsetConfiguration(ApiConfig.GROUP, slotKey(slot));
			}
		}
		executor.execute(configManager::sendConfig);
		refreshSlotsView();
		log.debug("Marked {} finished GE slots as sent", marked);
		return marked;
	}

	/**
	 * Forgets the fingerprint of one slot. If a finished offer is still in that slot, it is
	 * treated as new the next time the game reports it (at the latest, the next login).
	 * Must run on the client thread.
	 */
	private void clearSlotFingerprint(int slot)
	{
		if (client.getAccountHash() == -1)
		{
			return;
		}

		configManager.unsetConfiguration(ApiConfig.GROUP, slotKey(slot));
		executor.execute(configManager::sendConfig);
		log.debug("Cleared fingerprint of slot {}", slot + 1);
		refreshSlotsView();
	}

	/**
	 * Pushes the current account's stored fingerprints to the panel. Must run on the client thread.
	 */
	private void refreshSlotsView()
	{
		if (panel == null)
		{
			return;
		}

		if (client.getAccountHash() == -1)
		{
			SwingUtilities.invokeLater(() -> panel.setSlotFingerprints(null));
			return;
		}

		SlotFingerprint[] fingerprints = new SlotFingerprint[SLOT_COUNT];
		for (int slot = 0; slot < SLOT_COUNT; slot++)
		{
			String stored = configManager.getConfiguration(ApiConfig.GROUP, slotKey(slot));
			if (stored != null)
			{
				fingerprints[slot] = SlotFingerprint.parse(stored,
					itemId -> itemManager.getItemComposition(itemId).getName());
			}
		}
		SwingUtilities.invokeLater(() -> panel.setSlotFingerprints(fingerprints));
	}

	/**
	 * @param slot zero-based GE slot index, as reported by the client
	 */
	private boolean slotEnabled(int slot)
	{
		// Toggled from the sidebar panel; slots are on unless explicitly turned off
		Boolean enabled = configManager.getConfiguration(ApiConfig.GROUP, slotEnabledKey(slot), Boolean.class);
		return enabled == null || enabled;
	}

	private void setSlotEnabled(int slot, boolean enabled)
	{
		configManager.setConfiguration(ApiConfig.GROUP, slotEnabledKey(slot), enabled);
		executor.execute(configManager::sendConfig);
	}

	private static String slotEnabledKey(int slot)
	{
		// 1-based to match the slot numbers shown to the user
		return "slot" + (slot + 1);
	}

	private static boolean isFinished(GrandExchangeOfferState state)
	{
		switch (state)
		{
			case BOUGHT:
			case SOLD:
			case CANCELLED_BUY:
			case CANCELLED_SELL:
				return true;
			default:
				return false;
		}
	}

	/**
	 * Sends queued transactions oldest first, stopping at the first failure; the retry task
	 * picks it up again later. Safe to call from any thread.
	 */
	private void sendQueue()
	{
		if (slotsFailed.compareAndSet(true, false))
		{
			// Runs on the retry task too, so a failed slots update is tried again with fresh data
			clientThread.invoke(() -> slotsDirty = true);
		}

		if (!config.enabled() || !sending.compareAndSet(false, true))
		{
			return;
		}

		Payload next = queue.peek();
		if (next == null)
		{
			sending.set(false);
			return;
		}

		apiClient.post(config.address(), next, success ->
		{
			sending.set(false);
			if (success)
			{
				queue.remove(next);
				sendQueue();
			}
		});
	}

	private static BufferedImage createIcon()
	{
		// Upload arrow in RuneLite orange
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0xDC8A00));
		g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.drawLine(8, 11, 8, 2);
		g.drawLine(4, 6, 8, 2);
		g.drawLine(12, 6, 8, 2);
		g.drawLine(2, 14, 14, 14);
		g.dispose();
		return image;
	}

	private String slotKey(int slot)
	{
		return "handled." + client.getAccountHash() + "." + slot;
	}

	private String slotsKey()
	{
		return "slots." + client.getAccountHash();
	}

	private String startKey(int slot)
	{
		return "start." + client.getAccountHash() + "." + slot;
	}

	@Provides
	ApiConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ApiConfig.class);
	}
}
