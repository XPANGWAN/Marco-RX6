/**
 * MARCO RX6 scheduled FCM sender.
 * Required Worker secret: FCM_SERVICE_ACCOUNT_JSON
 * Required IAM roles for that service account:
 *   - Firebase Cloud Messaging API Admin
 *   - Cloud Datastore User (roles/datastore.user)
 */
let cachedAccessToken = null;
let cachedAccessTokenExpiry = 0;

function b64url(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}
function encodeJson(value) {
  return b64url(new TextEncoder().encode(JSON.stringify(value)));
}
function pemToBytes(pem) {
  const body = pem.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, "");
  const binary = atob(body);
  return Uint8Array.from(binary, c => c.charCodeAt(0));
}
async function getAccessToken(serviceAccount) {
  if (cachedAccessToken && Date.now() < cachedAccessTokenExpiry - 60000) return cachedAccessToken;
  const now = Math.floor(Date.now() / 1000);
  const header = encodeJson({ alg: "RS256", typ: "JWT" });
  const claim = encodeJson({
    iss: serviceAccount.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging https://www.googleapis.com/auth/datastore",
    aud: serviceAccount.token_uri || "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600
  });
  const unsigned = header + "." + claim;
  const key = await crypto.subtle.importKey(
    "pkcs8", pemToBytes(serviceAccount.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]
  );
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned));
  const assertion = unsigned + "." + b64url(new Uint8Array(signature));
  const response = await fetch(serviceAccount.token_uri || "https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion
    })
  });
  const payload = await response.json();
  if (!response.ok || !payload.access_token) throw new Error("Google OAuth failed: " + JSON.stringify(payload));
  cachedAccessToken = payload.access_token;
  cachedAccessTokenExpiry = Date.now() + (payload.expires_in || 3600) * 1000;
  return cachedAccessToken;
}
async function googleFetch(url, token, options = {}) {
  const response = await fetch(url, {
    ...options,
    headers: { authorization: "Bearer " + token, ...(options.headers || {}) }
  });
  if (!response.ok) {
    const detail = await response.text();
    throw new Error("Google API " + response.status + ": " + detail.slice(0, 700));
  }
  if (response.status === 204) return null;
  return response.json();
}
function stringField(fields, key, fallback = "") {
  return fields?.[key]?.stringValue ?? fallback;
}
function notificationData(document, recipientUid) {
  const f = document.fields || {};
  const data = {
    title: stringField(f, "title", "MARCO"),
    body: stringField(f, "body", "لديك إشعار جديد"),
    type: stringField(f, "type", "general"),
    recipientUid
  };
  for (const key of ["actorUid", "conversationId", "messageId", "gameName", "platform", "coverUrl", "postUrl"]) {
    const value = stringField(f, key);
    if (value) data[key] = value;
  }
  data.notificationId = document.name.split("/").pop();
  return data;
}
async function setPushStatus(documentName, token, status) {
  const url = "https://firestore.googleapis.com/v1/" + documentName +
    "?updateMask.fieldPaths=pushStatus&updateMask.fieldPaths=pushAttemptAt" +
    (status === "sent" || status === "skipped_no_devices" ? "&updateMask.fieldPaths=pushSentAt" : "");
  const fields = {
    pushStatus: { stringValue: status },
    pushAttemptAt: { timestampValue: new Date().toISOString() }
  };
  if (status === "sent") fields.pushSentAt = { timestampValue: new Date().toISOString() };
  await googleFetch(url, token, { method: "PATCH", headers: { "content-type": "application/json" }, body: JSON.stringify({ fields }) });
}
async function removeDevice(documentName, token) {
  const response = await fetch("https://firestore.googleapis.com/v1/" + documentName, {
    method: "DELETE", headers: { authorization: "Bearer " + token }
  });
  if (!response.ok && response.status !== 404) throw new Error("Unable to remove invalid device token: " + response.status);
}
async function sendToDevice(projectId, accessToken, fcmToken, data) {
  const response = await fetch("https://fcm.googleapis.com/v1/projects/" + projectId + "/messages:send", {
    method: "POST",
    headers: { authorization: "Bearer " + accessToken, "content-type": "application/json" },
    body: JSON.stringify({ message: { token: fcmToken, data, android: { priority: "HIGH" } } })
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) {
    const serialized = JSON.stringify(payload);
    if (serialized.includes("UNREGISTERED") || serialized.includes("registration-token-not-registered")) return { invalid: true };
    throw new Error("FCM send failed: " + response.status + " " + serialized.slice(0, 500));
  }
  return { sent: true };
}
async function processNotification(document, projectId, token) {
  const parts = document.name.split("/");
  const usersIndex = parts.indexOf("users");
  if (usersIndex < 0 || !parts[usersIndex + 1] || parts[usersIndex + 2] !== "notifications") return;
  const recipientUid = parts[usersIndex + 1];
  const devicesUrl = "https://firestore.googleapis.com/v1/projects/" + projectId +
    "/databases/(default)/documents/users/" + encodeURIComponent(recipientUid) +
    "/devices?pageSize=100";
  const devicesResult = await googleFetch(devicesUrl, token);
  let devices = devicesResult.documents || [];
  if (stringField(document.fields, "type") === "blog_game") {
    devices = devices.filter(device => device.fields?.blogNotificationsEnabled?.booleanValue === true);
  }
  if (devices.length === 0) {
    await setPushStatus(document.name, token, "skipped_no_devices");
    return;
  }
  await setPushStatus(document.name, token, "processing");
  const data = notificationData(document, recipientUid);
  let delivered = 0;
  for (const device of devices) {
    const fcmToken = stringField(device.fields, "token");
    if (!fcmToken) continue;
    try {
      const result = await sendToDevice(projectId, token, fcmToken, data);
      if (result.invalid) await removeDevice(device.name, token);
      else delivered++;
    } catch (error) {
      console.error("FCM device delivery failed", recipientUid, error.message);
    }
  }
  if (delivered > 0) {
    await setPushStatus(document.name, token, "sent");
  } else {
    // Keep pending so a temporary FCM/API error can be retried by the next cron run.
    await setPushStatus(document.name, token, "pending");
  }
}

const BLOG_FEED_URL = "https://marcorx6app.dpdns.org/api/games?alt=json&max-results=50";
const BLOG_CURSOR_PATH = "notificationControl/blogger";
function firestoreValue(value) {
  if (typeof value === "boolean") return { booleanValue: value };
  return { stringValue: String(value ?? "") };
}
async function readBlogCursor(projectId, token) {
  const url = "https://firestore.googleapis.com/v1/projects/" + projectId +
    "/databases/(default)/documents/" + BLOG_CURSOR_PATH;
  const response = await fetch(url, { headers: { authorization: "Bearer " + token } });
  if (response.status === 404) return null;
  if (!response.ok) throw new Error("Unable to read Blogger cursor: " + response.status);
  const doc = await response.json();
  return doc.fields?.lastPostId?.stringValue || null;
}
async function writeBlogCursor(projectId, token, postId, create = false) {
  const base = "https://firestore.googleapis.com/v1/projects/" + projectId +
    "/databases/(default)/documents/";
  const url = create
    ? base + "notificationControl?documentId=blogger"
    : base + BLOG_CURSOR_PATH + "?updateMask.fieldPaths=lastPostId&updateMask.fieldPaths=updatedAt";
  await googleFetch(url, token, {
    method: create ? "POST" : "PATCH",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ fields: {
      lastPostId: firestoreValue(postId),
      updatedAt: { timestampValue: new Date().toISOString() }
    } })
  });
}
async function listBlogNotificationUsers(projectId, token) {
  const url = "https://firestore.googleapis.com/v1/projects/" + projectId +
    "/databases/(default)/documents:runQuery";
  const query = {
    structuredQuery: {
      from: [{ collectionId: "devices", allDescendants: true }],
      where: { fieldFilter: {
        field: { fieldPath: "blogNotificationsEnabled" },
        op: "EQUAL",
        value: { booleanValue: true }
      } },
      limit: 1000
    }
  };
  const rows = await googleFetch(url, token, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(query)
  });
  const users = new Set();
  for (const row of rows || []) {
    const name = row.document?.name || "";
    const parts = name.split("/");
    const index = parts.indexOf("users");
    if (index >= 0 && parts[index + 1] && parts[index + 2] === "devices") users.add(parts[index + 1]);
  }
  return [...users];
}
async function createBlogNotification(projectId, token, uid, entry) {
  const postId = entry.id?.$t || "";
  const title = (entry.title?.$t || "").trim().slice(0, 120);
  if (!postId || !title) return;
  const links = Array.isArray(entry.link) ? entry.link : [];
  const postUrl = links.find(link => link.rel === "alternate" && String(link.href || "").startsWith("https://"))?.href || "";
  const categories = Array.isArray(entry.category) ? entry.category : [];
  const platform = String(categories[0]?.term || "MARCO RX6").slice(0, 30);
  const hashInput = new TextEncoder().encode(uid + "|" + postId);
  const digest = await crypto.subtle.digest("SHA-256", hashInput);
  const documentId = [...new Uint8Array(digest)].map(byte => byte.toString(16).padStart(2, "0")).join("");
  const url = "https://firestore.googleapis.com/v1/projects/" + projectId +
    "/databases/(default)/documents/users/" + encodeURIComponent(uid) +
    "/notifications?documentId=" + documentId;
  const fields = {
    type: firestoreValue("blog_game"),
    actorUid: firestoreValue("marco-cloudflare"),
    gameName: firestoreValue(title),
    platform: firestoreValue(platform),
    postUrl: firestoreValue(postUrl),
    title: firestoreValue("لعبة جديدة في المكتبة"),
    body: firestoreValue(title),
    createdAt: { timestampValue: new Date().toISOString() },
    read: { booleanValue: false },
    pushStatus: firestoreValue("pending")
  };
  const response = await fetch(url, {
    method: "POST",
    headers: { authorization: "Bearer " + token, "content-type": "application/json" },
    body: JSON.stringify({ fields })
  });
  if (response.status === 409) return; // deterministic ID prevents duplicate post alerts
  if (!response.ok) throw new Error("Unable to queue Blogger notification: " + response.status + " " + (await response.text()).slice(0, 400));
}
async function pollBlogger(env, projectId, token) {
  const response = await fetch(env.BLOG_FEED_URL || BLOG_FEED_URL, {
    headers: { accept: "application/json" }
  });
  if (!response.ok) throw new Error("Blogger feed failed: " + response.status);
  const payload = await response.json();
  const entries = payload.feed?.entry || [];
  if (!entries.length) return;
  const newestId = entries[0]?.id?.$t;
  if (!newestId) return;
  const previousId = await readBlogCursor(projectId, token);
  if (previousId === null) {
    await writeBlogCursor(projectId, token, newestId, true);
    console.log("Blogger cursor initialized");
    return;
  }
  if (newestId === previousId) return;
  const newEntries = [];
  for (const entry of entries) {
    const id = entry?.id?.$t;
    if (!id || id === previousId) break;
    newEntries.push(entry);
  }
  const users = await listBlogNotificationUsers(projectId, token);
  for (const entry of newEntries.reverse()) {
    for (const uid of users) await createBlogNotification(projectId, token, uid, entry);
  }
  await writeBlogCursor(projectId, token, newestId);
  console.log("Blogger posts queued:", newEntries.length, "eligible users:", users.length);
}

async function runScheduled(env) {
  if (!env.FCM_SERVICE_ACCOUNT_JSON) throw new Error("Missing FCM_SERVICE_ACCOUNT_JSON secret");
  const serviceAccount = JSON.parse(env.FCM_SERVICE_ACCOUNT_JSON);
  const projectId = serviceAccount.project_id;
  if (!projectId || !serviceAccount.client_email || !serviceAccount.private_key) {
    throw new Error("Service account JSON is missing required fields");
  }
  const token = await getAccessToken(serviceAccount);
  try { await pollBlogger(env, projectId, token); }
  catch (error) { console.error("Blogger polling failed", error.message); }
  const queryUrl = "https://firestore.googleapis.com/v1/projects/" + projectId +
    "/databases/(default)/documents:runQuery";
  const query = {
    structuredQuery: {
      from: [{ collectionId: "notifications", allDescendants: true }],
      where: {
        fieldFilter: {
          field: { fieldPath: "pushStatus" },
          op: "EQUAL",
          value: { stringValue: "pending" }
        }
      },
      limit: 100
    }
  };
  const rows = await googleFetch(queryUrl, token, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(query)
  });
  const documents = (rows || []).map(row => row.document).filter(Boolean);
  for (const document of documents) {
    try {
      await processNotification(document, projectId, token);
    } catch (error) {
      console.error("Notification processing failed", document.name, error.message);
    }
  }
  console.log("MARCO push sweep complete; queued:", documents.length);
}
export default {
  async scheduled(_event, env, ctx) {
    ctx.waitUntil(runScheduled(env));
  },
  async fetch(request) {
    if (new URL(request.url).pathname === "/health") {
      return Response.json({ service: "marco-rx6-notifications", status: "ready" });
    }
    return new Response("Use the scheduled trigger.", { status: 404 });
  }
};
