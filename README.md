# GlucoBLE Power Bridge (для Geoid CC600)

Android-приложение («прослойка»), которое принимает текущий уровень глюкозы из **xDrip+** или **GlucoDataHandler** и транслирует его по Bluetooth Low Energy (BLE) на велокомпьютер **Geoid CC600** под видом **датчика мощности (Cycling Power, 0x1818)**.

Например, сахар **9.3 ммоль/л** отображается на велокомпьютере в поле мощности как **93 Вт (W)**.

## Как собрать APK

### Вариант 1: Через Android Studio на компьютере
1. Распакуйте архив `GlucoBLE_Power_Project.zip`.
2. Откройте папку `GlucoBLE_Power_Project` в **Android Studio**.
3. Дождитесь окончания синхронизации Gradle и выберите в верхнем меню **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
4. Установите готовый файл `app-debug.apk` на телефон.

### Вариант 2: Без установки Android Studio (через GitHub прямо с телефона)
1. Создайте бесплатный приватный репозиторий на GitHub и загрузите туда файлы из архива.
2. Перейдите во вкладку **Actions** → запустите **Build GlucoBLE Power APK**.
3. Через 2 минуты скачайте готовый `app-debug.apk` в разделе **Artifacts**.
