# Announcements Notification Channel & Firebase Cloud Messaging Integration

Add a dedicated high-importance `announcements` notification channel ("Updates & Announcements") for news, releases, and community updates, set it as the default Firebase Cloud Messaging (FCM) channel in the manifest, integrate the Firebase Messaging SDK, and prompt for notification permissions on Android 13+.

## User Review & Critical Decisions

> [!IMPORTANT]
> The decisions below reflect your confirmed choices from our clarification step.

- **Confirmed Decision 1 — Full FCM SDK & Runtime Permission Integration**: In addition to registering the `"announcements"` `NotificationChannel` at startup and declaring the manifest `<meta-data>` default channel, we will add the `firebase-messaging` dependency (governed by the existing Firebase BoM), implement a `FirebaseMessagingService` to handle foreground/background announcements, and request `POST_NOTIFICATIONS` permission on Android 13+ (`TIRAMISU`).
- **Confirmed Decision 2 — Localized Channel Strings**: The channel title (`"Updates & Announcements"`) and description (`"News, releases, and community updates"`) will be stored in `strings.xml` alongside existing channel definitions (`playback_channel_name`, `download_channel_name`, `upload_channel_name`) and referenced via `getString(...)`.

## 1. Overview & Core Concept

- **What It Does**: Creates a clean, isolated notification category in Android system settings specifically for app updates, new releases, and community announcements, while wiring Firebase Cloud Messaging to route push notifications into this channel by default.
- **Target Audience / Persona**: Vibe Music listeners who want granular control over notification categories in Android settings (separating silent media playback/download progress from high-priority release & community announcements).
- **Key Value**: Prevents Firebase Cloud Messaging notifications from falling back to an uncategorized "Miscellaneous" channel and ensures foreground/background announcements appear reliably with high priority.

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **First Launch (Android 13+)**: On app startup, if `POST_NOTIFICATIONS` is not yet granted, the system runtime permission dialog prompts the user to allow notifications.
  2. **System Notification Settings**: Opening Android App Info → Notifications displays neatly organized categories:
     - *Updates & Announcements* (`announcements`, High Importance — "News, releases, and community updates")
     - *Now playing* (`vibe_music_playback`)
     - *Downloads* (`downloads`)
     - *Uploads* (`webdav_uploads`)
  3. **Receiving Announcements**: Background and foreground FCM push messages automatically target the `announcements` channel and open `MainActivity` when tapped.
- **Visual Identity & Theme**:
  - Uses Vibe Music's app launcher/notification iconography and Material 3 notification styling (`NotificationCompat.Builder` with `PRIORITY_HIGH` and auto-cancel).

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Eager Channel Creation in `VibeMusicApplication.onCreate()`**
  - *Chosen Approach*: Register the `announcements` `NotificationChannel` during `Application.onCreate()` before any Activity or FCM service runs.
  - *Why*: If a background FCM push arrives before the user ever opens `MainActivity` after an app update, Android already has the `"announcements"` channel registered with its localized name, description, and `IMPORTANCE_HIGH` level.
  - *Alternatives Considered*: Creating the channel lazily inside `MainActivity`—rejected because background FCM pushes arriving prior to Activity launch would miss the pre-configured channel description and importance.
- **Decision 2: Firebase BoM Version Alignment**
  - *Chosen Approach*: Declare `firebase-messaging` in the version catalog without a hardcoded version so the existing `firebase-bom` manages version alignment automatically.
  - *Why*: Ensures strict compatibility with the already-configured `firebase-auth` and `firebase-analytics` libraries.

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

- **Architecture & Component Diagram**:
```
┌──────────────────────────────────────────────────────────────────────┐
│                       Android System & FCM                           │
│   Push Payload ──► com.google.firebase.messaging.default_...         │
│                    android:value="announcements"                     │
└───────────────────┬──────────────────────────────────┬───────────────┘
                    │ (Background Notification)        │ (Foreground / Data)
                    ▼                                  ▼
┌─────────────────────────────────────┐  ┌─────────────────────────────┐
│     VibeMusicApplication            │  │ VibeFirebaseMessagingService│
│  ┌───────────────────────────────┐  │  │  • onNewToken()             │
│  │ createAnnouncementsChannel()  │  │  │  • onMessageReceived()      │
│  │ id = "announcements"          │◄─┼──┤  • Posts NotificationCompat │
│  │ IMPORTANCE_HIGH               │  │  │    to "announcements"       │
│  └───────────────────────────────┘  │  └─────────────────────────────┘
└─────────────────────────────────────┘
                    ▲
                    │ Checks & Requests POST_NOTIFICATIONS (API 33+)
┌───────────────────┴─────────────────┐
│            MainActivity             │
│  • rememberLauncherForActivityResult│
│  • Startup permission check         │
└─────────────────────────────────────┘
```

- **Data Model & State**:
  - **Channel ID**: `"announcements"`
  - **String Resources**:
    - `R.string.announcements_channel_name`: `"Updates & Announcements"`
    - `R.string.announcements_channel_description`: `"News, releases, and community updates"`
  - **Manifest Meta-Data**:
    - `com.google.firebase.messaging.default_notification_channel_id` → `"announcements"`

- **Interactive Component & State Mapping**:
  - **Application Startup (`VibeMusicApplication`)**: Invokes `createAnnouncementsNotificationChannel()` on API 26+ (`Build.VERSION_CODES.O`), registering the channel via `getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)`.
  - **Runtime Permission Flow (`MainActivity`)**: Checks `ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)` on API 33+ (`Build.VERSION_CODES.TIRAMISU`) at startup and launches the existing `notifyPermission` `ActivityResultLauncher` if not yet granted.
  - **FCM Foreground Handler (`VibeFirebaseMessagingService`)**: Extends `FirebaseMessagingService`, overrides `onMessageReceived(remoteMessage)` to build and display a `NotificationCompat` notification on the `"announcements"` channel when a message arrives while the app is in the foreground (guarding on `POST_NOTIFICATIONS` permission).
