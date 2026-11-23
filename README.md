# ChatAI

**Multi-Model AI Chat Android Application** — built with Java, XML, Material Design 3, Room, and Theme Studio.

A modular Android chat app that connects to multiple AI providers (Google Gemini, OpenRouter, Pollinations, Kilo, and local models), with built-in Agent mode, tool execution, wireless ADB debugging, Shizuku/Termux integration, floating chat bubble, and Theme Studio.

---

## 📱 Screenshots

<table>
  <tr>
    <td><b>Main Chat</b></td>
    <td><b>Theme Studio</b></td>
  </tr>
  <tr>
    <td><img src="Screenshot/chat_main.png" width="300"/></td>
    <td><img src="Screenshot/theme_studio.png" width="300"/></td>
  </tr>
  <tr>
    <td><b>Wireless ADB</b></td>
    <td><b>Settings</b></td>
  </tr>
  <tr>
    <td><img src="Screenshot/wireless_adb.png" width="300"/></td>
    <td><img src="Screenshot/settings.png" width="300"/></td>
  </tr>
</table>

> 📂 Drop your screenshot PNG files into the `Screenshot/` folder and name them as above.

---

## ✨ Features

- **Multi-provider AI chat**: Google Gemini, OpenRouter (free), Pollinations (keyless), Kilo, and local models
- **Agent mode**: Multi-step planning with tool execution and user confirmation
- **Tool platform**: Filesystem, terminal, internet download, ADB/Shizuku integration
- **Wireless ADB pairing**: Built-in ChatAI Server for remote debugging
- **Shizuku & Termux**: Privileged shell access and background command execution
- **Floating chat bubble**: Messenger-style overlay for quick chat
- **Theme Studio**: Live color/gradient customization with presets
- **AI Memory**: Long-term memory, project-scoped memory, merge & restore
- **Multi-language**: English, Vietnamese, French, Japanese, Korean, Chinese
- **Theme presets**: Sunset Orange, Ocean Blue, Midnight Purple, Emerald, Rose Pink, Minimal Gray

---

## 🛠 Tech Stack

| Layer | Technology |
|-------|------------|
| Language | Java 11 |
| UI | XML + Material Design 3 + ViewBinding |
| Database | Room (SQLite) |
| Networking | OkHttp + Gson |
| Dependency injection | Manual DI |
| Architecture | MVVM + Functional, one-way data flow |
| Background | AppExecutors + coroutines-compatible patterns |
| Accessibility | Shizuku API, Termux RUN_COMMAND |

---

## 🚀 Getting Started

### Prerequisites

- Android Studio (latest stable)
- Android SDK API 36 (compileSdk), minSdk 26
- Java 11+

### Installation

1. Clone the repository:

```bash
git clone https://github.com/nguyenthanhan12062001/chatbot.git
cd chatbot
```

2. Open the project in Android Studio.
3. Create a `.env` file in the project root with your API keys:

```ini
# GOOGLE_GEMINI_API_KEY=your_gemini_key
# OPENROUTER_API_KEY=your_openrouter_key
```

4. Sync Gradle and run on an emulator or physical device.

### Release Build

The release APK is signed with a keystore kept locally in the project (`release-key.jks`).  
Configure your own signing in `app/build.gradle.kts` before building release:

```kotlin
signingConfigs {
    create("release") {
        storeFile = file("${rootDir}/release-key.jks")
        storePassword = System.getenv("STORE_PASSWORD") ?: "your_store_password"
        keyAlias = "release"
        keyPassword = System.getenv("KEY_PASSWORD") ?: "your_key_password"
    }
}
```

```bash
./gradlew assembleRelease
```

---

## 📂 Project Structure

```
chatbot/
├── app/
│   ├── src/main/java/com/chatai/an/
│   │   ├── ai/               # AI core, providers, tools, agent, memory
│   │   ├── core/             # App container, executors, logger
│   │   ├── integration/      # ADB, Shizuku, Termux, Wireless ADB
│   │   ├── presentation/     # Activities, fragments, adapters, ViewModels
│   │   └── theme/            # Theme Studio, palettes, presets
│   └── res/                  # Layouts, drawables, values (6 languages)
├── gradle/
├── Screenshot/               # App screenshots
├── release-key.jks           # Release signing keystore
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

---

## 🤝 Contributing

Pull requests are welcome. For major changes, please open an issue first to discuss what you would like to change.

---

## 📄 License

This project is licensed under the MIT License.

---

**Maintained by Nguyen Thanh An**
