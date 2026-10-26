# MyPassword Modern

Standalone Java Android project that carries the legacy MyPassword workflow into a current Gradle/Android Gradle Plugin project.

## Open in Android Studio

Open this directory as a project and let Android Studio sync Gradle. Use Android Studio's bundled Gradle JDK (JDK 17 or newer); no separate system JDK installation is needed if the bundled runtime is available. Install Android SDK Platform 36 when prompted.

## Compatibility notes

- The application id is `com.example.coffeecat.mypassword.next`, so it can be installed beside the old application.
- `LegacyPasswordAlgorithm` keeps the original SHA-256, timestamp string format, fingerprint slicing, and output-character selection behavior.
- The first-step check preserves the original behavior: it formats `yyyy-MM-dd HH:mm:ss` with the device defaults, appends that timestamp to both digest fragments, hashes both strings with SHA-256, then compares the results.
- The second-step legacy implementation assigned a timestamp but did not use it when creating the output; this project preserves that behavior.
- Compatibility is intended for the same input, timestamp, locale, and time zone. Keep the old app available as a reference when validating known examples.
- The selected password length defaults to 8. Lengths 9–12 preserve the original 8-character result as a prefix and append deterministic characters.
- The length and note are saved per master-password fingerprint and exact second input. The note is encrypted with an AES-GCM key held by Android Keystore; the lookup identifier is a digest rather than the raw second input.
- After unlocking, use **按备注检索** to search saved notes by a keyword. Selecting a match loads its input, length, and note into the generator.
- Use **设备迁移 → 导出备份** on the old phone, transfer the `.mpbackup` file, then choose **恢复备份** on the new phone. The backup is encrypted with the current master password and can only be restored after unlocking with that same password. Restoring merges entries into the new phone's local store.

## 说明&使用方法
这是个单机密码软件，原版是手搓的，这次用codex进行了升级，可以实现一站一密，通过保护密码验证后，所有密码使用主密码+站点名称进行分散得出，长度可选，支持本地搜索索引，本地不会保存生成的密码。支持配置文件导出导入。
使用方法也很简单：只需替换LegacyPasswordAlgorithm.java中LEGACY_CHECK_FRAGMENT的值为保护密码的sha256第19个hex字符至60个hex字符，然后用android studio编译一下即可。
