# Virtual Power Meter 2.1: локальный разбор BLE

Источник: присланный XAPK, пакет `com.appsprinter.virtualpowermeter`,
versionName 2.1 / code 70. Оба вложения XAPK идентичны, SHA-256:
`964f3b53d1168b2233d2f90a4f017369688287732c41aa4bdf674a7a00d0cad4`.

Метод: распаковка ZIP и дизассемблирование `classes.dex` локальным Android SDK
36 `dexdump -d`. APK не запускался и никуда не загружался. Промежуточные файлы:
`.tools/virtual-power/` (не включены в Git). Сторонний отчёт
`1-VPM_BLE_power_broadcast_spec.md` из вложений использован как указатель;
ниже отдельно перечислена самостоятельно проверенная часть.

## Подтверждено байткодом

* `BleAdvertisementService.a()` создаёт PRIMARY CPS 1818: Measurement 2A63
  NOTIFY (0x10), permission READ (1), CCCD 2902 READ|WRITE (0x11), начальное
  значение CCCD 0000; Feature 2A65 READ = `04 00 00 00`; Location 2A5D READ
  = `0D`. Control Point в создаваемом CPS отсутствует.
* `BleAdvertisementService.c()` вызывает старый трёхаргументный
  `startAdvertising(settings, data, callback)`, без scan response.
  Настройки: mode=2 LOW_LATENCY, connectable=true, timeout=0.
  Данные: includeDeviceName=true, includeTxPowerLevel=true, UUID только 1818.
  `setTxPowerLevel` в этом построителе не вызывается: AD-поле TX power и
  настройка мощности передатчика — разные вещи.
* `BleAdvertisementService.b()` создаёт Device Information с Manufacturer
  `EpicRide`, Model `56504`, Firmware — строкой версии. В onCreate также
  создаётся Battery Service. Это не идентификаторы, которые следует
  копировать в GlucoPower.
* Callback `v0/i` содержит последовательное добавление сервисов через очередь
  и `onServiceAdded`, затем запуск рекламы. CCCD 0100/0000 управляет набором
  подписчиков, ответ отправляется через `sendResponse`.
* `v0/J0` формирует ровно 10 байт: flags `10 00`, power LE16, cumulative
  wheel revolutions LE32, last wheel event time LE16. Таким образом,
  Feature=4 согласован с наличием колёсных данных; это не каденс шатуна.
* Время в пакете рассчитывается как
  `(System.currentTimeMillis() * 1024 / 1000) & 65535`.
* Передача использует `characteristic.setValue(bytes)` и старую перегрузку
  `notifyCharacteristicChanged(device, characteristic, false)`.

Ориентиры в DEX для повторной проверки: создание CPS `0x15be08`, Feature
`0x15be6a–0x15be84`, Location `0x15bea6`; реклама `0x15c380–0x15c3e6`;
время `0x271e3c–0x271e62`, размер пакета `0x271eda`, notify `0x271f5c`.

## Что полезно для будущей реализации

1. Отдельный экспериментальный вариант рекламы VPM: короткое имя, TX power
   AD-поле и CPS UUID в одном пакете, без scan response. При стандартном
   бюджете flags+UUID+TX+name остаётся **19 байт UTF-8 имени**, не 19 любых
   символов. Не менять системное имя телефона автоматически.
2. Сравнить этот вариант с минимальной рекламой GlucoPower 1.13 на одном
   устройстве, меняя только рекламный пакет. Ни данный APK, ни сторонний
   отчёт не доказывают его совместимость с CC600 или vivo.
3. Последовательная регистрация GATT и учёт подписчиков уже есть в GlucoPower.
   Наличие READ permission у NOTIFY-характеристики не добавляет свойство READ
   и само по себе не объясняет отсутствие входящего соединения.
4. Не переносить старую notify API, фиксированную модель/производителя,
   колёсные поля без источника скорости или чужой шаблон целиком.
   GlucoPower должен сохранить передачу мощности без выдуманной скорости.

## Ограничения стороннего отчёта

Его раздел 7 — вновь написанный пример, **не декомпилированный код APK**.
В нём остаётся `TICKS_PER_SEC = 1024`, хотя сам отчёт указывает для CPS wheel
time 2048; также время обновляется без подтверждения оборота. Перед переносом
колёсных данных нужна отдельная проверка по спецификации CPS. Battery NOTIFY
без CCCD и упрощённые обработчики записей из примера не следует копировать.
Точный тайминг SensorService, все ветви обработки ошибок и поведение Android
на устройстве в этой краткой независимой проверке не исследовались.

Изменений приложения по результатам этого разбора не внесено.
