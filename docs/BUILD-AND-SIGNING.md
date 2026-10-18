# Сборка 1.11 и постоянная подпись

В проекте согласованы Android compileSdk 36, Build Tools 36.0.0, AGP 8.10.1,
Gradle 8.14.3, Kotlin 2.2.21 и JDK 17. `minSdk` остаётся 26, `targetSdk` — 34.
Повышение compileSdk не переключает приложение на новые target-поведения
Android 16 и само по себе не исправляет обнаружение BLE. SDK 34 также позволяет
создать приложение, работающее на Android 16.

Совместимость инструментов: [AGP 8.10](https://developer.android.com/build/releases/agp-8-10-0-release-notes),
[Kotlin Gradle plugin](https://kotlinlang.org/docs/gradle-configure-project.html),
[назначение targetSdk](https://developer.android.com/guide/topics/manifest/uses-sdk-element).

## Локальная сборка

Установить JDK 17 и Android SDK с пакетами `platforms;android-36` и
`build-tools;36.0.0`, указать SDK через `ANDROID_HOME` или `local.properties`.
Из корня проекта:

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Ошибки тестов и lint являются
ошибками сборки; их не следует скрывать через `continue-on-error`.
Тесты Robolectric работают с API 34 и не моделируют радиоканал vivo/CC600.

## GitHub Actions

Workflow собирает PR и push в `main`/`fix/**`, сохраняет отчёты даже при неудаче.
APK выгружается и публикуется как prerelease только после успешных проверок;
для PR публикации нет. Доступ на запись к репозиторию получает только job
публикации. Для воспроизводимой версии Gradle используется wrapper.

Чтобы CI-сборки устанавливались поверх друг друга, им нужен один и тот же ключ.
В Settings → Secrets and variables → Actions создать секрет
**DEBUG_KEYSTORE_BASE64** из существующего debug-keystore с alias
`androiddebugkey` и паролем `android`. Для местной установки, используемой
в этой рабочей папке, путь — `.tools/android-user/debug.keystore`.
Скопировать его Base64 в буфер обмена можно без вывода ключа в терминал:

```powershell
$signingKeyPath = (Resolve-Path '.tools/android-user/debug.keystore').Path
[Convert]::ToBase64String([IO.File]::ReadAllBytes($signingKeyPath)) | Set-Clipboard
```

Значение вставить непосредственно в GitHub Secret. Сам keystore, его Base64
и пароли не добавлять в исходники или артефакты. Workflow восстанавливает ключ
только вне PR. Если секрета нет, CI предупреждает и использует временный ключ:
стабильность подписи в этом случае не обеспечивается.

Добавление секрета не восстанавливает частный ключ старого APK. Для обновления
без удаления подпись должна совпадать с уже установленным приложением.

## Проверка предложений по BLE

Минимальный профиль содержит Measurement + CCCD, Feature и Sensor Location.
При Feature = 0 и отсутствии процедур управления Control Point исключается
разделом 3.4 [Cycling Power Service](https://www.bluetooth.org/DocMan/handlers/DownloadDoc.ashx?doc_id=585857).
Пустая характеристика 2A66 не обеспечивает корректную поддержку управления.
Appearance 0x0484 рекомендован профилем, но публичный
[AdvertiseData.Builder](https://developer.android.com/reference/android/bluetooth/le/AdvertiseData.Builder)
не позволяет задать это поле. Подмена его manufacturer data создаёт другой тип
рекламных данных.

Число сопряжений не равно заполнению resolving list: Android отбирает устройства
с IRK и учитывает ёмкость контроллера. Проверять нужно конкретную ошибку
заполнения, а не удалять все сопряжения. См. [AOSP: отбор IRK](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/android16-release/system/stack/btm/btm_ble_privacy.cc#546)
и [проверка ёмкости](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/android16-release/system/main/shim/acl.cc#1090).
