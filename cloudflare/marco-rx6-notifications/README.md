# MARCO RX6 Cloudflare push sender

This Worker runs every minute. It polls the Blogger JSON feed, queues each newly published game for accounts with at least one device opted into Blogger alerts, and sends all queued app notifications (messages, follows, and game alerts) through Firebase Cloud Messaging (FCM HTTP v1).

## Required configuration

1. Create/keep the Worker named `marco-rx6-notifications`.
2. Set the encrypted Worker secret `FCM_SERVICE_ACCOUNT_JSON` to the service-account JSON already created for Firebase project `marco-rx6-v2`. Never commit this JSON or paste it into chat.
3. In Google Cloud IAM, grant that service account:
   - Firebase Cloud Messaging API Admin
   - Cloud Datastore User (`roles/datastore.user`) so it can query notifications, read device tokens, and update delivery status.
4. Add the Cron Trigger `*/1 * * * *` (once per minute).
5. Deploy the contents of `worker.js`. The `/health` route only confirms that the Worker is running; it does not send a test push.
6. Keep the GitHub Actions FCM workflow manual-only; its scheduled sender is disabled to prevent duplicate pushes. Cloudflare is the sole scheduled FCM sender.

## Firestore queue contract

The app writes social notifications with `pushStatus: "pending"`. The Worker queries the `notifications` collection group, reads `users/{uid}/devices`, sends a data-only FCM message, then changes `pushStatus` to `sent`. Blogger posts are detected server-side from the feed URL used by the app and queued once per user/post using a deterministic Firestore document ID. The first run creates `notificationControl/blogger.lastPostId` as a baseline and does not send old posts. Blogger alerts are sent only to devices whose `blogNotificationsEnabled` field is `true`; message and follow alerts continue to use all registered devices. If there are no eligible devices it records `skipped_no_devices`. Temporary delivery failures are returned to `pending` for retry. Invalid FCM device tokens are removed.

## Important

- Deploy the updated `firestore.rules` from the repository so each signed-in or guest device can store its `blogNotificationsEnabled` opt-in flag.
- Install the matching app build and enable Blogger notifications once on each device. The app cancels its old on-device WorkManager Blogger poller and syncs the preference with its FCM device record.
- The Blogger poll runs on the one-minute Cron Trigger, so delivery is normally within about one minute of the next scheduled poll, plus FCM/network latency; it is not instantaneous.
- Push delivery is at-least-once: if a Worker stops after FCM accepts a message but before Firestore records `sent`, a duplicate may be delivered on a later run.
- Android 13+ users must grant notification permission. The app already checks that permission before displaying notifications.
- Cloudflare Cron Triggers and Worker usage are subject to the current Free plan limits.
