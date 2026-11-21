# MARCO RX6 Push Service

A clean Cloudflare Worker for scheduled Blogger discovery and Firebase Cloud Messaging delivery.

## Deployment

- Worker name: `marco-rx6-notifications`
- Build directory: `cloudflare/marco-rx6-notifications`
- Deploy command: `npx wrangler deploy`
- Cron: `*/1 * * * *`
- Required encrypted secret: `FCM_SERVICE_ACCOUNT_JSON`

The JSON secret must belong to the dedicated service account
`marco-rx6-notifications@marco-rx6-v2.iam.gserviceaccount.com`
in project `marco-rx6-v2`. Never commit the JSON or share its private key.

## Behavior

1. Polls the MARCO Blogger proxy feed every minute.
2. On the first successful run, stores the newest post as a baseline and sends no historical alerts.
3. Queues deterministic per-user notifications only for users with a device whose `blogNotificationsEnabled` flag is true.
4. Sends pending Firestore notification documents through FCM HTTP v1.
5. Blog notifications go only to opted-in devices; other app notifications go to all registered devices for that user.
6. Updates delivery state to `sent` or `skipped_no_devices`; temporary failures remain pending for retry.

## Endpoints

- `/` returns `Use the scheduled trigger.`
- `/health` returns a small readiness response. It does not validate Firebase credentials or send a push.

## Required Google Cloud roles

- Firebase Cloud Messaging API Admin
- Cloud Datastore User (`roles/datastore.user`)

Enable Firebase Cloud Messaging API and Cloud Firestore API in project `marco-rx6-v2`.
