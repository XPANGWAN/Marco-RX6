/**
 * MARCO RX6 Push Service — clean Cloudflare Worker.
 * Cron: every minute. Secret: FCM_SERVICE_ACCOUNT_JSON.
 * This worker polls the public Blogger feed, queues opted-in blog alerts,
 * and delivers pending Firestore notifications through FCM HTTP v1.
 */
const FEED_URL = "https://marcorx6.blogspot.com/feeds/posts/default?alt=json&max-results=50";
const MAX_PENDING_PER_RUN = 25;
let tokenCache = { value: "", expiresAt: 0 };

function base64Url(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}
function encodeJson(value) {
  return base64Url(new TextEncoder().encode(JSON.stringify(value)));
}
function decodePem(pem) {
  const body = pem.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, "");
  const binary = atob(body);
  return Uint8Array.from(binary, char => char.charCodeAt(0));
}
function fields(data) {
  const result = {};
  for (const [key, value] of Object.entries(data)) {
    if (typeof value === "string") result[key] = { stringValue: value };
    else if (typeof value === "boolean") result[key] = { booleanValue: value };
    else if (typeof value === "number") result[key] = { integerValue: String(value) };
  }
  return result;
}
function readString(document, key, fallback = "") {
  return document?.fields?.[key]?.stringValue ?? fallback;
}
async function accessToken(env) {
  if (tokenCache.value && Date.now() < tokenCache.expiresAt - 60000) return tokenCache.value;
  const account = JSON.parse(env.FCM_SERVICE_ACCOUNT_JSON || "");
  if (
    account.project_id !== "marco-rx6-v2" ||
    account.client_email !== "firebase-adminsdk-fbsvc@marco-rx6-v2.iam.gserviceaccount.com" ||
    !account.private_key
  ) {
    throw new Error("FCM_SERVICE_ACCOUNT_JSON must belong to the configured MARCO RX6 Firebase service account");
  }
  const now = Math.floor(Date.now() / 1000);
  const header = encodeJson({ alg: "RS256", typ: "JWT" });
  const claim = encodeJson({
    iss: account.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging https://www.googleapis.com/auth/datastore",
    aud: account.token_uri || "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600
  });
  const unsigned = header + "." + claim;
  const key = await crypto.subtle.importKey(
    "pkcs8", decodePem(account.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]
  );
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned));
  const assertion = unsigned + "." + base64Url(new Uint8Array(signature));
  const response = await fetch(account.token_uri || "https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion
    })
  });
  const body = await response.json();
  if (!response.ok || !body.access_token) throw new Error("Google OAuth failed: " + JSON.stringify(body).slice(0, 500));
  tokenCache = { value: body.access_token, expiresAt: Date.now() + Number(body.expires_in || 3600) * 1000 };
  return tokenCache.value;
}
async function google(env, path, options = {}) {
  const token = await accessToken(env);
  const response = await fetch("https://firestore.googleapis.com/v1/projects/marco-rx6-v2/databases/(default)/documents/" + path, {
    ...options,
    headers: {
      authorization: "Bearer " + token,
      "content-type": "application/json",
      ...(options.headers || {})
    }
  });
  const body = response.status === 204 ? null : await response.json().catch(() => ({}));
  if (!response.ok && response.status !== 404 && response.status !== 409) {
    throw new Error("Firestore " + response.status + ": " + JSON.stringify(body).slice(0, 500));
  }
  return { status: response.status, body };
}
async function queryGroup(env, collectionId, fieldPath, op, value, limit = 100) {
  const token = await accessToken(env);
  const response = await fetch(
    "https://firestore.googleapis.com/v1/projects/marco-rx6-v2/databases/(default)/documents:runQuery",
    {
      method: "POST",
      headers: { authorization: "Bearer " + token, "content-type": "application/json" },
      body: JSON.stringify({ structuredQuery: {
        from: [{ collectionId, allDescendants: true }],
        where: { fieldFilter: { field: { fieldPath }, op, value } },
        limit
      } })
    }
  );
  const body = await response.json();
  if (!response.ok) throw new Error("Firestore query " + response.status + ": " + JSON.stringify(body).slice(0, 500));
  return body.map(item => item.document).filter(Boolean);
}
async function sha256(value) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, "0")).join("");
}
function parsePost(entry) {
  const id = entry?.id?.$t || "";
  const title = entry?.title?.$t || "";
  const links = Array.isArray(entry?.link) ? entry.link : [];
  const link = links.find(item => item.rel === "alternate" && String(item.href || "").startsWith("https://"))?.href || "";
  const thumbnail = entry?.["media$thumbnail"]?.url || "";
  const html = entry?.content?.$t || entry?.summary?.$t || "";
  const embedded = html.match(/<img[^>]+src=["']([^"']+)["']/i)?.[1] || "";
  // Prefer the full-size image embedded in the post; Blogger media$thumbnail is usually only 72px.
  const imageUrl = String(embedded || thumbnail).replace(/&amp;/g, "&").startsWith("https://")
    ? String(embedded || thumbnail).replace(/&amp;/g, "&")
    : "";
  return id && title ? { id, title: title.slice(0, 120), link, imageUrl } : null;
}
async function getBlogCursor(env) {
  const result = await google(env, "notificationControl/blogger");
  return result.body?.fields?.lastPostId?.stringValue || "";
}
async function saveBlogCursor(env, postId) {
  const token = await accessToken(env);
  const response = await fetch(
    "https://firestore.googleapis.com/v1/projects/marco-rx6-v2/databases/(default)/documents/notificationControl/blogger?updateMask.fieldPaths=lastPostId&updateMask.fieldPaths=updatedAt",
    {
      method: "PATCH",
      headers: { authorization: "Bearer " + token, "content-type": "application/json" },
      body: JSON.stringify({ fields: {
        lastPostId: { stringValue: postId },
        updatedAt: { timestampValue: new Date().toISOString() }
      } })
    }
  );
  if (!response.ok) throw new Error("Unable to save Blogger cursor: " + response.status + " " + (await response.text()).slice(0, 300));
}
async function optedInUsers(env) {
  // Read Android device documents first so logs distinguish missing registration
  // from a device that exists but has notifications switched off.
  const devices = await queryGroup(env, "devices", "platform", "EQUAL", { stringValue: "android" }, 500);
  const users = new Set();
  let devicesWithToken = 0;
  let enabledDevices = 0;
  for (const device of devices) {
    const hasToken = Boolean(readString(device, "token"));
    const enabled = device.fields?.blogNotificationsEnabled?.booleanValue === true;
    if (hasToken) devicesWithToken++;
    if (hasToken && enabled) {
      enabledDevices++;
      const match = device.name?.match(/documents\/users\/([^/]+)\/devices\//);
      if (match) users.add(match[1]);
    }
  }
  console.log("MARCO device registration diagnostic", JSON.stringify({
    androidDeviceDocs: devices.length,
    devicesWithToken,
    enabledDevices,
    optedInUsers: users.size
  }));
  return [...users];
}
async function queueBlogPost(env, userId, post) {
  const id = (await sha256(userId + ":" + post.id)).slice(0, 40);
  const token = await accessToken(env);
  const path = "users/" + encodeURIComponent(userId) + "/notifications?documentId=" + id;
  const response = await fetch("https://firestore.googleapis.com/v1/projects/marco-rx6-v2/databases/(default)/documents/" + path, {
    method: "POST",
    headers: { authorization: "Bearer " + token, "content-type": "application/json" },
    body: JSON.stringify({ fields: {
      ...fields({
        type: "blog_game",
        actorUid: "system",
        title: "لعبة جديدة في MARCO RX6",
        body: post.title,
        postUrl: post.link,
        gameName: post.title,
        imageUrl: post.imageUrl || "",
        read: false,
        pushStatus: "pending"
      }),
      createdAt: { timestampValue: new Date().toISOString() }
    } })
  });
  if (!response.ok && response.status !== 409) {
    throw new Error("Unable to queue Blogger post (" + response.status + "): " + (await response.text()).slice(0, 300));
  }
}
async function pollBlogger(env) {
  const response = await fetch(FEED_URL, { headers: { accept: "application/json" } });
  if (!response.ok) throw new Error("Blogger feed returned HTTP " + response.status);
  const feed = await response.json();
  const entries = (feed?.feed?.entry || []).map(parsePost).filter(Boolean);
  if (!entries.length) return { queued: 0, baseline: false };
  const newest = entries[0];
  const previous = await getBlogCursor(env);
  console.log("MARCO Blogger feed diagnostic", JSON.stringify({ entries: entries.length, newestPostId: newest.id, newestHasImage: Boolean(newest.imageUrl), cursorPresent: Boolean(previous) }));
  if (!previous) {
    await saveBlogCursor(env, newest.id);
    return { queued: 0, baseline: true };
  }
  const previousIndex = entries.findIndex(post => post.id === previous);
  if (previousIndex < 0) {
    // The saved cursor fell outside the latest 50 feed entries. Do not treat
    // the whole feed as new: that would resend old games after a feed gap.
    // Move the cursor to the newest visible post and safely resume from here.
    await saveBlogCursor(env, newest.id);
    console.warn("MARCO Blogger cursor gap; skipped old feed window", JSON.stringify({
      visiblePosts: entries.length,
      cursorFound: false
    }));
    return { queued: 0, baseline: false, cursorGap: true, skippedOldWindow: entries.length };
  }
  if (previousIndex === 0) return { queued: 0, baseline: false };
  const fresh = entries.slice(0, previousIndex);
  const users = await optedInUsers(env);
  console.log("MARCO Blogger delivery diagnostic", JSON.stringify({ freshPosts: fresh.length, optedInUsers: users.length, freshPostsWithImages: fresh.filter(post => Boolean(post.imageUrl)).length }));
  // Never advance the cursor while there are no opted-in devices. Retrying
  // on the next cron cycle prevents silently losing a newly published post.
  if (users.length === 0) return { queued: 0, baseline: false, awaitingOptedInUsers: true };
  for (const post of fresh.reverse()) {
    for (const userId of users) await queueBlogPost(env, userId, post);
  }
  await saveBlogCursor(env, newest.id);
  return { queued: fresh.length * users.length, baseline: false, cursorGap: false };
}
async function listDevices(env, userId, blogOnly) {
  const token = await accessToken(env);
  const response = await fetch(
    "https://firestore.googleapis.com/v1/projects/marco-rx6-v2/databases/(default)/documents/users/" + encodeURIComponent(userId) + "/devices",
    { headers: { authorization: "Bearer " + token } }
  );
  if (!response.ok) throw new Error("Unable to read devices for user " + userId + ": " + response.status);
  const body = await response.json();
  return (body.documents || []).filter(doc => {
    const enabled = doc.fields?.blogNotificationsEnabled?.booleanValue === true;
    const tokenValue = doc.fields?.token?.stringValue;
    return tokenValue && (!blogOnly || enabled);
  });
}
async function setPushStatus(env, documentName, status) {
  const name = documentName.replace(/^projects\/marco-rx6-v2\/databases\/\(default\)\/documents\//, "");
  const token = await accessToken(env);
  const response = await fetch(
    "https://firestore.googleapis.com/v1/projects/marco-rx6-v2/databases/(default)/documents/" + name +
    "?updateMask.fieldPaths=pushStatus&updateMask.fieldPaths=pushAttemptAt",
    {
      method: "PATCH",
      headers: { authorization: "Bearer " + token, "content-type": "application/json" },
      body: JSON.stringify({ fields: {
        pushStatus: { stringValue: status },
        pushAttemptAt: { timestampValue: new Date().toISOString() }
      } })
    }
  );
  if (!response.ok) throw new Error("Unable to update notification status: " + response.status);
}
async function sendOne(env, notification) {
  const parts = notification.name.split("/documents/")[1].split("/");
  const userIndex = parts.indexOf("users");
  const userId = userIndex >= 0 ? parts[userIndex + 1] : "";
  if (!userId) throw new Error("Notification path does not contain a user ID");
  const data = {};
  for (const key of ["title", "body", "type", "postUrl", "gameName", "platform", "actorUid", "conversationId", "messageId", "imageUrl"]) {
    const value = readString(notification, key);
    if (value) data[key] = value;
  }
  data.notificationId = parts[parts.length - 1];
  const devices = await listDevices(env, userId, data.type === "blog_game");
  let imageHost = "";
  if (data.imageUrl) {
    try {
      const parsedImageUrl = new URL(data.imageUrl);
      imageHost = parsedImageUrl.protocol === "https:" ? parsedImageUrl.hostname : "non-https";
    } catch {
      imageHost = "invalid-url";
    }
  }
  console.log("MARCO FCM payload diagnostic", JSON.stringify({
    type: data.type || "unknown",
    imagePresent: Boolean(data.imageUrl),
    imageHost,
    imageLength: data.imageUrl ? data.imageUrl.length : 0,
    deviceCount: devices.length
  }));
  if (!devices.length) {
    await setPushStatus(env, notification.name, "skipped_no_devices");
    return "skipped";
  }
  const token = await accessToken(env);
  let delivered = 0;
  for (const device of devices) {
    const fcmToken = readString(device, "token");
    const response = await fetch("https://fcm.googleapis.com/v1/projects/marco-rx6-v2/messages:send", {
      method: "POST",
      headers: { authorization: "Bearer " + token, "content-type": "application/json" },
      body: JSON.stringify({ message: { token: fcmToken, data, android: { priority: "high" } } })
    });
    if (response.ok) {
      delivered++;
    } else {
      const detail = await response.text();
      if (detail.includes("UNREGISTERED") || detail.includes("registration-token-not-registered")) {
        const deviceName = device.name.split("/documents/")[1];
        await google(env, deviceName, { method: "DELETE" });
      } else {
        throw new Error("FCM rejected a message: " + response.status + " " + detail.slice(0, 300));
      }
    }
  }
  await setPushStatus(env, notification.name, delivered ? "sent" : "skipped_no_devices");
  return delivered ? "sent" : "skipped";
}
async function deliverPending(env) {
  const pending = await queryGroup(env, "notifications", "pushStatus", "EQUAL", { stringValue: "pending" }, MAX_PENDING_PER_RUN);
  let sent = 0, skipped = 0;
  for (const notification of pending) {
    try {
      const result = await sendOne(env, notification);
      if (result === "sent") sent++; else skipped++;
    } catch (error) {
      console.error("Notification delivery failed:", error.message);
    }
  }
  return { sent, skipped, checked: pending.length };
}
export default {
  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === "/health") {
      return Response.json({
        service: "marco-rx6-notifications",
        status: "ready",
        build: "notifications-rebuild-2026-11-21"
      });
    }
    return new Response("Use the scheduled trigger.", { status: 200 });
  },
  async scheduled(_event, env, ctx) {
    ctx.waitUntil((async () => {
      try {
        const blogger = await pollBlogger(env);
        const delivery = await deliverPending(env);
        console.log("MARCO push cycle complete", JSON.stringify({ blogger, delivery }));
      } catch (error) {
        console.error("MARCO push cycle failed:", error.message);
      }
    })());
  }
};
