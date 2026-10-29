# Privacy Policy for 字在 Zizai

**Effective date:** [TO FILL: date of the first Google Play release]
**Last updated:** 28 October 2026

> **Draft.** Items marked [TO FILL] must be completed before publishing. This draft is adapted from the
> privacy policy of Dictate Keyboard, on which Zizai is based; it is not legal advice.

This Privacy Policy explains how **字在 Zizai** (the "App", application ID `io.zizai.keyboard`) handles your
information. The App is developed by **[TO FILL: your legal name]** (the "Developer", "we", "us").

Questions about this policy: **[TO FILL: contact email, e.g. feedback@zizai.io]**.

---

## 1. Summary

- **We do not receive any of your data.** We run no server in the App's path, keep no database about you,
  and include no analytics.
- Zizai is a voice-to-text keyboard. To turn your speech into text it sends the audio you record to an
  **AI provider that you choose and configure** (for example OpenAI, Google Gemini or SiliconFlow), directly
  from your device. It never passes through us.
- **On-device recognition is available**: it needs no network connection and no provider at all.
- Your API keys, prompts and settings are stored **only on your device**, plus Android's own end-to-end
  encrypted backup if you use it (section 2.6).
- The App contains **no advertising, no tracking, no telemetry and no crash-reporting SDKs**.
- As a keyboard, the App **does not log your keystrokes or collect what you type**. It only processes audio
  you explicitly record, and text you explicitly ask it to reword.
- **The source code is public**, so all of this can be checked (section 8).

---

## 2. What information the App processes

### 2.1 Voice recordings
When you start a dictation, the App records audio with your device's microphone. The recording is written to
a temporary, app-private file on your device and **deleted automatically** once the transcription completes.
To produce a transcript, the audio is sent to the transcription provider you selected, or processed entirely
on your device if you use on-device recognition.

If you use the "resend / retry" feature, the most recent recording may be kept so it can be sent again. It is
deleted when you start a new dictation, clear it, or the App removes it.

### 2.2 Text you transcribe or reword
The transcribed text, and any text you ask the App to reword or format with AI, is sent to the provider you
configured so the result can be returned. The result is inserted into the field you are typing in.

### 2.3 Dictation history
The App keeps a history of your dictations **on your device**, so you can re-insert, review or re-transcribe
them. It is on by default and holds the most recent entries (50 unless you change it). You can search, pin and
delete entries, or clear the history, in the App's settings.

- Transcripts are stored in an app-private database on your device.
- The source audio is **not** kept unless you switch that on; it is off by default. Failed transcriptions are
  the exception: their audio is kept so the attempt can be repeated, and is removed with the entry.
- **Nothing is recorded in password fields or in incognito mode.** One case can't be told apart: when another
  app asks Android's speech recognition for text, it doesn't say what the text is for, so such a request is
  kept like any other dictation.
- None of this is uploaded. It exists only on your device and is deleted when you clear the history or
  uninstall the App.

### 2.4 API keys and provider configuration
To use a provider, you enter your own API key (or, for a server of your own, its address). These credentials
are stored **locally on your device** in the App's private storage and sent **only** to the matching provider
to authenticate your requests. They are **never** sent to us. The one other place they can be is Android's
own backup (section 2.6).

### 2.5 Prompts, settings and dictionary
Rewording prompts, style settings, language preferences, your personal dictionary and other configuration
are stored **locally on your device**. They are not uploaded, except as part of a request to your chosen
provider when relevant (for example, a prompt you apply to text).

### 2.6 Android's own backup
If Android's built-in backup is on for your phone (backup to your Google account, or a transfer to a new
phone), it includes the App's settings, so a new phone starts where the old one left off. That covers your
API keys, the proxy password, your prompts and settings, your personal dictionary and the keyboard's files.

- On Android 12 and later, the App takes part in the Google-account backup **only when that backup is
  end-to-end encrypted** with your screen lock, which means Google can't read it.
- Your **dictation history, its audio and the clipboard are never part of it.**
- This backup is Android's, not ours, and we never receive it. You can turn it off in Android's settings. The
  App's own "Back up & Restore" lets you leave credentials out of a backup file you make yourself.

### 2.7 Scanning text
**Scan text** reads printed text from a photo you take or pick. It runs entirely on your phone, and the photo
is never uploaded. It uses Google's ML Kit library, which on its own would report usage statistics about the
feature to Google; the App switches that reporting off.

---

## 3. Third-party services you choose

### 3.1 AI providers
For transcription and rewording, the App acts as a client to a provider **that you select and authenticate
with your own account**. When you use such a feature, your audio or text is processed by that provider under
**its** privacy policy and terms. Providers act as independent controllers, not on our behalf; we have no
access to, and no control over, what you send them.

Built-in providers include:

| Provider | Privacy policy |
| --- | --- |
| OpenAI | https://openai.com/policies/privacy-policy |
| Google Gemini | https://policies.google.com/privacy and https://ai.google.dev/gemini-api/terms |
| Groq | https://groq.com/privacy-policy/ |
| OpenRouter | https://openrouter.ai/privacy |
| Together AI | https://www.together.ai/privacy |
| DeepInfra | https://deepinfra.com/privacy |
| Mistral AI | https://mistral.ai/terms/#privacy-policy |
| Scaleway | https://www.scaleway.com/en/privacy-policy/ |
| OVHcloud | https://www.ovhcloud.com/en-ie/personal-data-protection/ |
| xAI (Grok) | https://x.ai/legal/privacy-policy |
| DeepSeek | https://cdn.deepseek.com/policies/en-US/deepseek-privacy-policy.html |
| SiliconFlow | https://docs.siliconflow.cn/cn/legals/privacy-policy (in Chinese) |
| Anthropic | https://www.anthropic.com/legal/privacy |
| Deepgram | https://deepgram.com/privacy |
| AssemblyAI | https://www.assemblyai.com/legal/privacy-policy |
| ElevenLabs | https://elevenlabs.io/privacy |
| Soniox | https://soniox.com/privacy |
| Microsoft Azure Speech | https://privacy.microsoft.com/privacystatement |
| Ollama (your own server) | Runs on your own device or server; no third party involved |
| On-device recognition | Runs entirely on your phone; no third party involved |

A few differ in ways worth knowing before you choose:

- **Google Gemini:** on Google's free tier, Google may use what you send to improve its products, and human
  reviewers may read it. On its paid tier it doesn't. Which applies depends on your Google account.
- **SiliconFlow** operates in mainland China, and what you send to it is processed there.
- **Scaleway and OVHcloud** process in the EU. **Soniox** and **OpenRouter** offer EU regions, which you can
  choose in the App.
- **Microsoft Azure Speech** uses your own Speech resource, in the Azure region you choose.

You can also configure a **custom OpenAI-compatible server**. Your data then goes to whichever server you
specify, and you are responsible for how that server handles it.

**Please read a provider's privacy policy before using it.** Because requests are made with your own API key,
the provider may keep or process the data under your account terms with it (for example, for abuse
monitoring).

### 3.2 Google Play
Zizai is a paid app. **Google** processes the purchase as the seller and an independent controller for the
payment; we never see your payment details. See https://policies.google.com/privacy.

### 3.3 Downloads from GitHub
Some files are too large to ship inside the App, so it downloads them from GitHub releases (GitHub is a
Microsoft company):

- on-device recognition models, when you tap to download one;
- word lists for swipe typing and suggestions, and the Chinese Pinyin pack, when you add a keyboard language
  or first type in it;
- the prompt library, each time you open it.

These are plain file downloads. Nothing about you, your dictations or your typing is sent with them. As with
any download, GitHub sees your IP address and which file was requested. See
https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement.

---

## 4. Permissions and why they are used

| Permission | Purpose |
| --- | --- |
| `RECORD_AUDIO` | To capture your voice when you start a dictation. |
| `INTERNET` | To send audio and text to the provider you configured, and to download models and word lists. |
| `MODIFY_AUDIO_SETTINGS` / `BLUETOOTH` | To route recording correctly, including through Bluetooth headsets. |
| `VIBRATE` | Optional haptic feedback. |
| `POST_NOTIFICATIONS` | Status notifications, such as model-download progress, on Android 13+. |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | To keep downloading an on-device model after you leave the App. |

The App uses the microphone only while you are actively dictating. **It does not request access to your
contacts.** You can add a contact's name to your personal dictionary by picking that one contact in Android's
own contact picker, or by selecting a contacts file you exported yourself. Only the name is taken, and it is
stored on your device like any other dictionary word.

---

## 5. Data storage and retention

- **On your device:** API keys, settings, prompts, your dictionary and your dictation history stay until you
  delete them in the App or uninstall it. Android's own backup may hold a copy of the settings (section 2.6).
  Temporary recordings are deleted automatically after transcription.
- **With your chosen provider:** retention is governed by that provider's policy, not by us.
- **With us:** nothing.

---

## 6. We do not collect what you type

Although Zizai is a keyboard, it does **not** record, log, store or transmit your general typing. Words you
type may be learned locally to improve suggestions (never in incognito mode or password fields); that data
stays on your device, and you can view and delete it in the App's dictionary settings.

---

## 7. Data security

Data sent to providers travels over encrypted HTTPS connections. The one exception is a plain `http://`
address you enter yourself for a server on your own network; the App warns you that such a connection is
not encrypted. Credentials and settings are kept in the App's private, sandboxed storage, which other apps
can't read. No method of transmission or storage is 100% secure.

---

## 8. You don't have to take our word for it

The App is open source under the Apache License 2.0: **https://github.com/zizaiAndroid/zizai**. You can read
every line this policy describes, including what is sent to a provider and when, and you're free to build the
App yourself. Published source isn't proof of what a given binary contains, but it lets anyone check the
claims made here.

---

## 9. Children's privacy

The App is not directed at children under 13 (or the equivalent minimum age where you live), and we do not
knowingly process children's data. AI providers may set their own age requirements.

---

## 10. International data transfers

Providers you choose may process data in other countries, such as the United States. SiliconFlow processes in
mainland China. Scaleway, OVHcloud, and the EU regions of Soniox and OpenRouter keep processing in the EU.

---

## 11. Your choices and rights

- You decide whether to use an AI provider, and which one, or none (on-device recognition).
- You can change or delete your API keys, history, prompts and settings at any time in the App.
- You can uninstall the App to remove all data stored on your device.
- Depending on where you live (for example under the EU GDPR), you may have rights of access, correction,
  erasure, restriction, portability and objection, and the right to complain to a supervisory authority.
  Because we hold no data about you, there is normally nothing for us to provide or erase.
- For data already processed by a provider, exercise your rights with that provider directly.

---

## 12. Changes to this policy

We may update this policy. Material changes will be reflected in the "Last updated" date and published at
this address.

---

## 13. Contact

**[TO FILL: your legal name]** — [TO FILL: contact email]

Project repository: https://github.com/zizaiAndroid/zizai
