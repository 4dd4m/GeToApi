package com.getoapi;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

class GeToApiPanel extends PluginPanel
{
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
		.withZone(ZoneId.systemDefault());

	private final JLabel queueLabel = new JLabel();
	private final JLabel statusLabel = new JLabel();
	private final JLabel skippedHeader = new JLabel();
	private final JPanel skippedList = new JPanel();
	private final JCheckBox[] slotBoxes = new JCheckBox[GeToApiPlugin.SLOT_COUNT];
	private final JLabel[] slotLabels = new JLabel[GeToApiPlugin.SLOT_COUNT];
	private final JButton[] slotClearButtons = new JButton[GeToApiPlugin.SLOT_COUNT];
	private final Consumer<SkippedTransaction> onSendSkipped;
	private final Consumer<SkippedTransaction> onDismissSkipped;

	/**
	 * @param onMarkSlots runs the slot snapshot and calls back on the EDT with the number of
	 *                    slots marked, or -1 if the GE slots aren't available
	 */
	GeToApiPanel(Runnable onClearQueue, Consumer<Consumer<Integer>> onMarkSlots,
		Consumer<SkippedTransaction> onSendSkipped, Consumer<SkippedTransaction> onDismissSkipped,
		BiConsumer<Integer, Boolean> onSlotToggled, IntConsumer onClearSlot)
	{
		this.onSendSkipped = onSendSkipped;
		this.onDismissSkipped = onDismissSkipped;

		setLayout(new BorderLayout());
		setBorder(new EmptyBorder(10, 10, 10, 10));

		JButton clearButton = new JButton("Clear queue");
		clearButton.addActionListener(e ->
		{
			int answer = JOptionPane.showConfirmDialog(this,
				"Discard all queued transactions? They will not be sent.",
				"Clear queue", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (answer == JOptionPane.YES_OPTION)
			{
				onClearQueue.run();
			}
		});

		JPanel controls = new JPanel(new GridLayout(0, 1, 0, 8));
		controls.add(queueLabel);
		controls.add(clearButton);

		JButton refreshButton = new JButton("Refresh");
		refreshButton.setToolTipText("Re-read the GE slots and save them as fingerprints (marks finished slots as sent)");
		refreshButton.addActionListener(e ->
		{
			int answer = JOptionPane.showConfirmDialog(this,
				"Re-read all GE slots and save them as fingerprints?\n"
					+ "Every finished slot is marked as already sent; any that hasn't been posted yet will never be sent.",
				"Refresh slots", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (answer != JOptionPane.YES_OPTION)
			{
				return;
			}

			refreshButton.setEnabled(false);
			onMarkSlots.accept(marked ->
			{
				refreshButton.setEnabled(true);
				statusLabel.setText(marked < 0
					? "Log in and wait a moment first"
					: "Saved " + marked + " finished slot(s)");
			});
		});

		JLabel slotsTitle = new JLabel("Slots");
		slotsTitle.setFont(FontManager.getRunescapeBoldFont());
		slotsTitle.setToolTipText("Ticked slots are posted. Unticked slots are still tracked, but go to Skipped instead.");

		JPanel slotsHeader = new JPanel(new BorderLayout());
		slotsHeader.setBorder(new EmptyBorder(16, 0, 6, 0));
		slotsHeader.add(slotsTitle, BorderLayout.WEST);
		slotsHeader.add(refreshButton, BorderLayout.EAST);
		slotsHeader.add(statusLabel, BorderLayout.SOUTH);

		JPanel slotsList = new JPanel();
		slotsList.setLayout(new BoxLayout(slotsList, BoxLayout.Y_AXIS));
		for (int slot = 0; slot < slotBoxes.length; slot++)
		{
			slotsList.add(createSlotCard(slot, onSlotToggled, onClearSlot));
			slotsList.add(Box.createVerticalStrut(4));
		}

		JPanel slotsSection = new JPanel(new BorderLayout());
		slotsSection.add(slotsHeader, BorderLayout.NORTH);
		slotsSection.add(slotsList, BorderLayout.CENTER);

		skippedHeader.setFont(FontManager.getRunescapeBoldFont());
		skippedHeader.setBorder(new EmptyBorder(16, 0, 6, 0));
		skippedList.setLayout(new BoxLayout(skippedList, BoxLayout.Y_AXIS));

		JPanel skippedSection = new JPanel(new BorderLayout());
		skippedSection.add(skippedHeader, BorderLayout.NORTH);
		skippedSection.add(skippedList, BorderLayout.CENTER);

		JPanel content = new JPanel(new BorderLayout());
		content.add(controls, BorderLayout.NORTH);
		content.add(slotsSection, BorderLayout.CENTER);
		content.add(skippedSection, BorderLayout.SOUTH);
		add(content, BorderLayout.NORTH);

		setQueueSize(0);
		setSkipped(List.of());
		setSlotFingerprints(null);
	}

	private JPanel createSlotCard(int slot, BiConsumer<Integer, Boolean> onSlotToggled, IntConsumer onClearSlot)
	{
		JCheckBox box = new JCheckBox("Slot " + (slot + 1), true);
		box.setOpaque(false);
		box.setToolTipText("Post transactions from this slot");
		box.addActionListener(e -> onSlotToggled.accept(slot, box.isSelected()));
		slotBoxes[slot] = box;

		JButton clear = new JButton("Clear");
		clear.setToolTipText("Forget this slot's fingerprint");
		clear.addActionListener(e ->
		{
			int answer = JOptionPane.showConfirmDialog(this,
				"Forget the fingerprint of slot " + (slot + 1) + "?\n"
					+ "If a finished offer is still in this slot, it will be posted again\n"
					+ "the next time the game reports it (at the latest, your next login).",
				"Clear slot " + (slot + 1), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (answer == JOptionPane.YES_OPTION)
			{
				onClearSlot.accept(slot);
			}
		});
		slotClearButtons[slot] = clear;

		JLabel text = new JLabel();
		slotLabels[slot] = text;

		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		top.add(box, BorderLayout.WEST);
		top.add(clear, BorderLayout.EAST);

		JPanel card = new JPanel(new BorderLayout(0, 4));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(new EmptyBorder(6, 8, 8, 8));
		card.add(top, BorderLayout.NORTH);
		card.add(text, BorderLayout.CENTER);
		return card;
	}

	/**
	 * @param fingerprints one per slot, null where nothing is stored; or null altogether when
	 *                     no account is logged in
	 */
	void setSlotFingerprints(SlotFingerprint[] fingerprints)
	{
		NumberFormat n = NumberFormat.getIntegerInstance();
		for (int slot = 0; slot < slotLabels.length; slot++)
		{
			SlotFingerprint fp = fingerprints == null ? null : fingerprints[slot];
			slotClearButtons[slot].setEnabled(fp != null);
			if (fp == null)
			{
				slotLabels[slot].setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				slotLabels[slot].setText(fingerprints == null ? "Log in to see fingerprints" : "No fingerprint");
				continue;
			}

			slotLabels[slot].setForeground(null);
			slotLabels[slot].setText("<html><b>" + escape(fp.getItemName()) + "</b><br>"
				+ stateName(fp) + " " + n.format(fp.getQuantitySold()) + "/" + n.format(fp.getTotalQuantity())
				+ " @ " + n.format(fp.getPrice()) + "<br>"
				+ n.format(fp.getSpent()) + " gp</html>");
		}
	}

	private static String stateName(SlotFingerprint fp)
	{
		switch (fp.getState())
		{
			case BOUGHT: return "Bought";
			case SOLD: return "Sold";
			case CANCELLED_BUY: return "Cancelled buy";
			case CANCELLED_SELL: return "Cancelled sell";
			default: return fp.getState().toString();
		}
	}

	/**
	 * @param slot zero-based GE slot index
	 */
	void setSlotEnabled(int slot, boolean enabled)
	{
		slotBoxes[slot].setSelected(enabled);
	}

	void setQueueSize(int size)
	{
		queueLabel.setText("Items in queue: " + size);
	}

	/**
	 * @param entries newest first
	 */
	void setSkipped(List<SkippedTransaction> entries)
	{
		skippedHeader.setText("Skipped (" + entries.size() + ")");
		skippedList.removeAll();

		if (entries.isEmpty())
		{
			JLabel empty = new JLabel("Nothing skipped");
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			skippedList.add(empty);
		}
		for (SkippedTransaction entry : entries)
		{
			skippedList.add(createRow(entry));
			skippedList.add(Box.createVerticalStrut(6));
		}

		skippedList.revalidate();
		skippedList.repaint();
	}

	private JPanel createRow(SkippedTransaction entry)
	{
		Payload p = entry.getPayload();
		String side = p.getType() == Payload.BUY ? "Buy" : "Sell";
		JLabel text = new JLabel("<html><b>" + escape(entry.getItemName()) + "</b><br>"
			+ side + " " + NumberFormat.getIntegerInstance().format(p.getQuantity())
			+ " · " + NumberFormat.getIntegerInstance().format(p.getTotal()) + " gp<br>"
			+ "Slot " + entry.getSlot() + " · " + formatDate(p.getDate()) + "</html>");

		JButton send = new JButton("Send");
		send.setToolTipText("Add to the queue and post it with its original date");
		send.addActionListener(e -> onSendSkipped.accept(entry));

		JButton dismiss = new JButton("Dismiss");
		dismiss.setToolTipText("Delete without sending");
		dismiss.addActionListener(e -> onDismissSkipped.accept(entry));

		JPanel buttons = new JPanel(new GridLayout(1, 2, 6, 0));
		buttons.setOpaque(false);
		buttons.add(send);
		buttons.add(dismiss);

		JPanel row = new JPanel(new BorderLayout(0, 6));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(8, 8, 8, 8));
		row.add(text, BorderLayout.CENTER);
		row.add(buttons, BorderLayout.SOUTH);
		return row;
	}

	private static String formatDate(String isoDate)
	{
		try
		{
			return DATE_FORMAT.format(Instant.parse(isoDate));
		}
		catch (DateTimeParseException e)
		{
			return isoDate;
		}
	}

	private static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
