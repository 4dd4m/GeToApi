# GE To API

A RuneLite plugin that sends your Grand Exchange activity to an HTTP API you run yourself:

- **Finished transactions**: every completed or cancelled offer that traded something.
- **Live slots** (optional): what is in each of your 8 GE slots, as it changes.

Nothing is sent anywhere until you turn it on, and then only to the API URL you configure.

## Setup

In the plugin's settings:

| Setting | Default | |
|---|---|---|
| Send transactions | off | Master switch. While off, nothing is sent: finished transactions go to the *Skipped* list instead, and slots aren't sent. |
| API URL | `http://127.0.0.1:5000` | Base URL. The plugin appends `/transactions` and `/slots`. |

To see what the plugin sends without writing an API first, run `python tools/test_server.py`. It listens on
the default URL and logs every request; stop anything else on port 5000 first. Add `--no-slots` to see how the
plugin behaves when an API doesn't implement `/slots`.

## Your API

The plugin calls up to two endpoints. **Both are optional.** Implement only the ones you need: the plugin
notices a missing endpoint and stops calling it, without errors (see [Responses](#responses)).

| Method | Path | When | Body |
|---|---|---|---|
| `POST` | `<API URL>/transactions` | An offer finishes | [Transaction](#post-transactions) |
| `PUT` | `<API URL>/slots` | Your GE slots change | [Slots snapshot](#put-slots) |

All timestamps are ISO-8601 in UTC, e.g. `2026-10-16T12:34:56.789Z`. Unknown values are sent as `null`, not
left out.

### `POST /transactions`

**When:** an offer becomes `BOUGHT`, `SOLD`, `CANCELLED_BUY` or `CANCELLED_SELL` and at least one item was traded.
Offers cancelled before anything traded are not sent. Finished offers that are still in a slot at login (e.g. they
finished while you were logged out) are sent once, then remembered, so collecting or re-logging doesn't post them
again.

```json
{
  "id": "3f0c7c1e-8f3e-4c8e-9a54-0c1f6c7b2a11",
  "date": "2026-10-16T12:34:56.789Z",
  "startTime": "2026-10-16T12:02:10.120Z",
  "endTime": "2026-10-16T12:34:56.789Z",
  "playerId": 1234567890123456789,
  "slot": 3,
  "itemId": 560,
  "quantity": 1000,
  "type": 0,
  "total": 131000,
  "state": "BOUGHT"
}
```

| Field | Type | |
|---|---|---|
| `id` | string (UUID) | Random per transaction. If a response gets lost, the plugin sends the same `id` again, so store it uniquely and treat a repeat as already stored. `null` only for transactions queued by very old versions. |
| `date` | timestamp | Same as `endTime`; kept for older APIs. |
| `startTime` | timestamp or `null` | When the offer was placed. `null` if it was already in the slot when the plugin started watching (placed on another device, or before login), as the game doesn't say. |
| `endTime` | timestamp | When the plugin saw the offer finish. For offers that finished while you were logged out, this is your next login. |
| `playerId` | integer (64-bit) | RuneLite account hash: unique per account and survives name changes. It exceeds JavaScript's safe integer range, so parse it as a 64-bit integer or string. |
| `slot` | integer `0`-`7` | GE slot, zero-based. |
| `itemId` | integer | OSRS item id. |
| `quantity` | integer | Items actually traded. For a cancelled offer, only the part that traded. |
| `type` | `0` or `1` | `0` = buy, `1` = sell. |
| `total` | integer | gp actually spent (buy) or received (sell) for `quantity`. Price each = `total / quantity`. |
| `state` | string | `BOUGHT`, `SOLD`, `CANCELLED_BUY` or `CANCELLED_SELL`. |

### `PUT /slots`

**When:** sent at most once per game tick when a slot changes: an offer is placed, fills partly or fully, is
cancelled or collected. Also sent shortly after login, to pick up anything that changed while you were away, such
as offers collected on mobile. **It's only sent when the slots differ from the last snapshot sent.**
That snapshot is remembered per account, across restarts, and forgotten when sending fails so the retry goes out.

The body is the full state of every **occupied** slot of the account. **A slot that isn't listed is empty**, so
replace the account's slots with what you receive rather than merging. `slots` is `[]` when all 8 are empty.

```json
{
  "accountHash": "1234567890123456789",
  "slots": [
    {
      "slot": 0,
      "itemId": 560,
      "state": "BUYING",
      "price": 131,
      "totalQuantity": 25000,
      "quantitySold": 9800,
      "spent": 1283800,
      "startTime": "2026-10-16T11:22:03.481Z"
    }
  ]
}
```

| Field | Type | |
|---|---|---|
| `accountHash` | string | Same account hash as `playerId`, as a string. |
| `slot` | integer `0`-`7` | GE slot, zero-based. |
| `itemId` | integer | OSRS item id. |
| `state` | string | `BUYING`, `SELLING`, `BOUGHT`, `SOLD`, `CANCELLED_BUY` or `CANCELLED_SELL`. |
| `price` | integer | Offer price per item. |
| `totalQuantity` | integer | Offer size. |
| `quantitySold` | integer | Traded so far, `0`-`totalQuantity`. |
| `spent` | integer | gp traded so far. |
| `startTime` | timestamp or `null` | When the offer was placed; `null` if unknown (see `POST /transactions`). |

The per-slot checkboxes don't affect this endpoint; all 8 slots are always included.

### Responses

Return any `2xx` on success; the response body is ignored. Other answers:

| Response | `/transactions` | `/slots` |
|---|---|---|
| `2xx` | Stored. Sends the next queued transaction. | Done. |
| `409` | Treated as "already stored": removed from the queue. | Treated as success. |
| `404`, `405`, `501` (endpoint not implemented) | Stops sending. Transactions stay queued, nothing is lost. | Stops sending slots. |
| Any other `4xx`, e.g. `400`, `422` | That transaction is moved to *Skipped* with the HTTP status as the reason, so it can't block the rest of the queue. You can send it again by hand. | Not retried; sent again once the slots change. |
| `5xx`, `401`, `403`, `408`, `429`, timeout, no connection, invalid URL | Kept at the front of the queue and retried every 30 seconds. | Retried every 30 seconds with the current slots. |

After a "not implemented" answer, the side panel says which endpoint is missing. The plugin tries the endpoint
again when you change the API URL, toggle *Send transactions* or log out and back in. An unreachable API is
logged once per outage, not on every retry, and nothing is shown as an error.

## The side panel

Open it from the upload-arrow icon in the RuneLite sidebar.

### Queue

Finished transactions are queued before they are sent. The queue:

- is sent **oldest first, one at a time**, and stops at the first failure until the next retry (every 30 s),
- is saved in your RuneLite settings, so nothing is lost if the API is down or RuneLite closes,
- waits while *Send transactions* is off.

**Clear queue** discards everything queued, after a confirmation. Discarded transactions are not sent.

### Slots

One card per GE slot, showing the finished offer the plugin last handled there (its "fingerprint"). Fingerprints
are kept per account and are how the plugin avoids posting the same transaction twice.

- **Checkbox (on by default):** whether finished transactions from this slot are posted. When unticked, the slot
  is still tracked, but its transactions go to *Skipped* instead of the queue.
- **Clear:** forgets this slot's fingerprint. If a finished offer is still in the slot, it's treated as new and
  posted again the next time the game reports it, at the latest on your next login.
- **Refresh:** re-reads all 8 slots and marks every finished one as already sent, **without** posting anything.
  Use it if you don't want offers that are sitting in your slots right now to be sent.

### Skipped

Finished transactions that weren't sent, with the reason: *Send transactions* was off, the slot was unticked, or
the API rejected it. The newest 50 are kept, saved like the queue.

- **Send:** moves it to the queue with its original times. It ignores the slot checkbox but still waits for
  *Send transactions*.
- **Dismiss:** deletes it without sending.

## What leaves your computer

Only the fields documented above, and only to the API URL you set. That covers your account hash, item ids,
quantities, prices and times. No account name, password or session data is sent.

## License

BSD 2-Clause; see [LICENSE](LICENSE).
