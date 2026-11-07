# NEXUS AI v0.2 — Build Instructions (Termux) and Status

> **Honest status.** This code was written in a cloud workspace with **no Android SDK and no access to Google/Maven servers**, so it has **never been compiled or run**. I checked it by hand (XML well-formed, all resource references exist, brackets balance), but a first build can still hit a few compile errors. If that happens, send me the **first** `e: file:///…` line(s) and I will fix them. "Written" below means the code exists, **not** that it has been tested on a phone.

---

## 1. Build in Termux (one command after the ZIP is in Download)

Use the **GitHub/F-Droid** Termux. Your phone already has the SDK from last time, so this should be faster.

```
termux-setup-storage
cd ~
rm -rf nexus-v2
unzip -o ~/storage/downloads/nexus-ai-v2.zip
cd nexus-v2
bash build-termux.sh
```

`build-termux.sh` runs `termux-setup.sh` automatically if the SDK/Java is missing. When it prints **BUILD SUCCESSFUL**, the APK is `NexusAI-v2-debug.apk` in your Download folder.

### IMPORTANT: uninstall the old NEXUS first
You re-installed Termux, so this build is signed with a **new debug key**. Android will refuse to update over the old app ("App not installed"). **Uninstall the old NEXUS AI, then install the new APK.** (Old chat history is lost; nothing else.)

### If the build fails
| Symptom | Fix |
|---|---|
| `Out of memory` / killed | Close all apps; set `org.gradle.jvmargs=-Xmx1024m` in `gradle.properties`; re-run. |
| `JAVA_HOME is not set` | `export JAVA_HOME=$PREFIX/lib/jvm/java-17-openjdk` and re-run. |
| `SDK location not found` | `echo "sdk.dir=$HOME/android-sdk" > local.properties` |
| `Exec format error` / aapt2 | `cat ~/.gradle/gradle.properties` must contain `android.aapt2FromMavenOverride=...aapt2`. Re-run `bash termux-setup.sh`. |
| `Could not resolve…` | Network hiccup; re-run `bash build-termux.sh`. |
| Compile error in a `.kt` file | Send me the first `e: file:///…` line(s). |

**Cloud alternative:** upload the folder to a private GitHub repo → Actions → *Build NEXUS AI APK* → Run workflow → download the `NexusAI-debug-apk` artifact.

---

## 2. What changed in v0.2

### The "model galat hai" error — fixed
Google **retired Gemini 2.5 Flash/Pro** for new keys, and model names change every few months (Gemini 3.x is current). v0.1 had 2.5 names built in, so every request failed with 404.
v0.2 never trusts a fixed name:
- **Settings → Fetch available models** asks Google/xAI which models *your key* can really use and lists them.
- If a saved model ever stops working, NEXUS **fetches the list itself, picks a working one (newest stable Flash for Gemini), saves it and retries** — you get a toast saying which model it chose.
- Leave the model box empty = fully automatic.

### Gemini **and Grok**
Settings → AI Provider: choose Gemini (Google) or Grok (xAI), each with its own encrypted API key. Both stream replies.
*Grok note:* I could not verify the exact current Grok model list from here. "Fetch available models" asks xAI directly (OpenAI-style `/v1/models`); if that listing fails for your key, type a model name from console.x.ai into the model box.

### New design
Animated boot intro (rings, typed title, boot messages — tap to skip), new launcher icon, 5-tab navigation (Voice, Chat, Modes, **Tools**, Settings).

### New features
| Feature | How to use it |
|---|---|
| Call by name | "Rahul ko call karo" → asks Yes/No → calls (or opens dialer if Call permission is not granted). Needs Contacts permission. |
| WhatsApp message | "WhatsApp par Rahul ko bolo main late hoon" → confirm → opens the chat with the text; **if Accessibility is ON it also presses Send**. |
| WhatsApp video call | "Rahul ko WhatsApp video call karo" → confirm → opens chat and presses the video icon (needs Accessibility). |
| SMS | "Rahul ko sms bhejo main aa raha hoon" → confirm → opens SMS app with text (presses Send if Accessibility ON). |
| Read WhatsApp/Telegram/SMS | "WhatsApp message check karo" (needs **Notification access**). Say "summary do" for an AI summary. |
| Reply to a message | "Usko bolo main 10 minute mein aa raha hoon" → confirm → sent through the notification's own reply action. "Reply suggest karo" gives 3 AI options. |
| Reminders | "10 minute baad chai yaad dilao", "kal subah 7 baje gym yaad dilao", "roz raat 10 baje dawai yaad dilao". Survive reboot. List/delete in Tools. |
| Notes | "Note likho kal doodh lena hai", "mere notes padho". |
| Memory | "Yaad rakho ki mujhe chai pasand hai", "kya yaad hai". Passwords/OTPs refused. View/delete in Tools; switch off in Settings. |
| Screen reading | "Screen par kya likha hai?" (Accessibility ON) → AI explains the visible text. Password fields are skipped. |
| Screen control | "back jao", "home jao", "recent apps", "notification panel kholo", "scroll down", "screenshot lo", "screen lock karo", "click Settings", "type hello". (Accessibility ON) |
| Website Studio | Tools → Website Studio, or say "ek shopping website banao". Preview, edit by chatting, Save as .html + .zip to Downloads, Share code. |
| Wake word | Settings → "Always listen for Hey Nexus" (experimental). |
| Permission dashboard | Settings → Permissions: every permission with reason, status and Enable button. |
| Yes/No confirmation | Calls, messages, SMS, deletes always ask first — by a popup **and** by voice ("haan"/"nahi"). |

### Turn on the powerful features (one time)
1. **Settings → Permissions**: Enable *Microphone*, *Contacts*, *Phone calls*, *Notifications*.
2. **Notification access** → find NEXUS AI → ON (for WhatsApp reading/replying).
3. **Accessibility** → NEXUS AI → ON (for screen reading, tapping Send, Back/Home…). Android shows a warning; that is normal for any app with this power. NEXUS only acts when you ask.
4. **Battery: no restrictions** (helps reminders and wake word on Infinix).

---

## 3. Status list

### Written in v0.2 (needs your testing)
Everything in the table above, plus all v0.1 features: avatar with moods, streaming chat, STT/TTS with voice picker, 9 modes + custom mode, torch/battery/storage/volume/timer/alarm/open-app/settings screens.

### Not built yet
- Image/file/screenshot **picture** understanding (screen reading works on *text* only), camera/photo questions
- Calendar integration, weather, news, daily briefing, unit conversion screens (the AI can still answer such questions)
- Birthday reminders from contacts (use "roz/har saal" reminders instead)
- 100+ language selector (the AI replies in many languages; voice/recognition list is Hindi + 3 English variants)
- Backup/restore, avatar/theme customisation, encrypted chat history
- Web search / live data
- Auto-answering or ending phone calls

### Limits set by Android (not bugs)
- **Wi-Fi/Bluetooth/airplane mode** cannot be toggled silently by an app on Android 10+. NEXUS opens the settings panel; with Accessibility ON you can say "click Wi-Fi".
- **Ending calls** and reading private WhatsApp databases are not possible for normal apps; NEXUS only sees message *notifications*, and only after you enable Notification access.
- **WhatsApp auto-Send** works by pressing the on-screen Send button via Accessibility; it depends on WhatsApp's screen layout/language and can fail after WhatsApp updates (NEXUS then tells you to press Send).
- **Contacts saved in Hindi script** must be spoken in Hindi (Latin "Rahul" will not match "राहुल").
- **Wake word** depends on Google's speech recogniser, may beep, drains battery, and Infinix may kill it. Interrupting speech works by tapping the mic.
- **Reminders** are exact only if "Exact alarms" is allowed; otherwise they can be a few minutes late.
- **"Do everything":** NEXUS will not secretly send messages, record, or bypass Android security; anything it cannot do it says plainly instead of pretending.

---

## 4. Project layout
```
app/src/main/java/com/nexus/ai/
  core/     Assistant (brain), SmartCommands, PhoneCommands, Reminders, Inbox, Modes, Prefs, ItemStore…
  ai/       GeminiClient, GrokClient, ChatProvider + ModelPicker
  voice/    VoiceManager (TTS + STT)
  service/  NexusNotificationListener, NexusAccessibilityService, WakeService, ReminderReceiver/BootReceiver
  ui/       MainActivity, StudioActivity, avatar/ (AvatarView, WaveView, BootView), screens/ (Home, Chat, Modes, Tools, Settings)
```
Permissions in the manifest: INTERNET, RECORD_AUDIO, READ_CONTACTS, CALL_PHONE, POST_NOTIFICATIONS, SCHEDULE_EXACT_ALARM, RECEIVE_BOOT_COMPLETED, SET_ALARM, FOREGROUND_SERVICE(+_MICROPHONE). Each is requested only when its feature is first used (with an explanation first).

## 5. Release APK
`./gradlew assembleRelease` signs with the debug key so it installs for personal use. For sharing or Play Store, create your own keystore and replace the signing config.
