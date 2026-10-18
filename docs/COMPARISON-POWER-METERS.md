# Почему CC600 не показывает vivo как мощемер: сравнение трёх реализаций

Проверка: 19 октября 2026 года. Сравнивается GlucoPower 1.7 в ветке
`fix/glucopower-ble-background`. Реального обмена CC600 → vivo в предоставленных
вложениях нет: TXT — экспорт сканирования Redmi, фотографии — GATT-клиент Redmi.

## Результат

В этих репозиториях не найдена ошибка обязательного Cycling Power Service,
которая объясняла бы отсутствие GlucoPower в поиске CC600. Это не доказывает
совместимость CC600 с Android: репозитории работают на других BLE-стеках и не
содержат код фильтрации устройств прошивки CC600.

Предыдущие изменения очереди notifications и удержания GATT действуют после
установления связи. Если CC600 не доходит до соединения и чтения сервиса, они
не могут устранить причину отбрасывания устройства при поиске.

## Состав GATT

| Параметр | stevejarvis/powermeter | kochcodes/ESP32 | paixaop/zwack | GlucoPower 1.7 |
| --- | --- | --- | --- | --- |
| Cycling Power Service | `1818` | `1818` | `1818` | `1818` |
| Measurement | `2A63`, Notify | `2A63`, Notify | `2A63`, Notify | `2A63`, Notify |
| Feature | `2A65`, 4 байта, 0 | `2A65`, Read; запись некорректной длины | `2A65`, 4 байта, `08 00 00 00` | `2A65`, 4 байта, 0 |
| Sensor Location | `2A5D`, 5 | `2A5D`, Read; значение не установлено | `2A5D`, 13 | `2A5D`, 5 |
| Control Point `2A66` | Нет | Нет | Нет | Нет |
| Appearance Cycling Power явно задаётся приложением | Нет | Нет | Нет | Нет |

Источники: [Arduino ble.ino, закреплённый коммит](https://github.com/stevejarvis/powermeter/blob/ffcaa34c78c26ef7de67002da41961e8300f65d1/ble.ino),
[ESP32 main.cpp, закреплённый коммит](https://github.com/kochcodes/ESP32_BLE_CyclingPowerMeter/blob/4099da5c3953098ea6708a7b4bf2a100a1f4302e/src/main.cpp),
[Zwack CPS](https://github.com/paixaop/zwack/blob/master/lib/cps/cycling-power-service.js).

Arduino использует те же Feature = 0 и Location = 5. Это особенно важно:
отсутствие cadence, calibration Control Point или другой локации нельзя объявить
найденной причиной отказа CC600. Добавление новых характеристик без наблюдения
за запросами CC600 было бы экспериментом, а не исправлением установленной ошибки.

Минимальное измерение мощности — два байта Flags = 0 и два байта signed watts
little endian. GlucoPower передаёт `00 00 5D 00` для 93 Вт; такой пакет уже получен
Redmi на присланном фото. Этот факт подтверждает существование работающего
канала vivo → Redmi, но не обработку рекламы или GATT прошивкой CC600.

## Реклама и различия BLE-стеков

| Параметр | Arduino / Bluefruit | ESP32 Arduino 2.0.5 | Zwack / bleno Linux | vivo, предоставленный журнал |
| --- | --- | --- | --- | --- |
| Имя | `JrvsPwr` | `Zwifterino` | `Zwack` по умолчанию | `vivo X300 Ultra` |
| Размещение имени | Основной ADV | Scan response | Scan response | Экспорт может объединять ADV и scan response |
| UUID | Complete 16-bit list, `1818` | `1818` | В том числе `1818` | Complete 16-bit list, `1818` |
| Flags | Discoverable / LE-only | `06` | `06` | `02` |
| Appearance датчика в ADV | Явно не добавлена | По умолчанию 0 | Не добавлена | В показанной записи отсутствует |

Проверка библиотек важнее поверхностного чтения примера: ESP32 вызывает
`setScanResponse(true)` через значение по умолчанию, после чего библиотека
переносит имя из ADV в scan response и копирует туда UUID. GlucoPower 1.7 уже
использует такую схему. Поэтому требование «обязательно перенести имя в основной
пакет» из этих трёх проектов не следует.

Первичные источники:

* [Реклама Arduino](https://github.com/stevejarvis/powermeter/blob/ffcaa34c78c26ef7de67002da41961e8300f65d1/ble.ino#L68).
* [ESP32 BLEAdvertising.cpp 2.0.5: значения по умолчанию и формирование ADV/scan response](https://github.com/espressif/arduino-esp32/blob/2.0.5/libraries/BLE/src/BLEAdvertising.cpp).
* [ESP32 BLEAdvertising.h: scan response включён по умолчанию](https://github.com/espressif/arduino-esp32/blob/2.0.5/libraries/BLE/src/BLEAdvertising.h#L78).
* [Zwack: вызов startAdvertising](https://github.com/paixaop/zwack/blob/master/lib/zwack-ble-sensor.js).
* [Bleno: байты Flags, UUID и имени](https://github.com/abandonware/bleno/blob/master/lib/hci-socket/gap.js).

`0x02` — LE General Discoverable. В `0x06` дополнительно установлен бит `0x04`
BR/EDR Not Supported. Отсутствие этого бита у Android-телефона с поддержкой
Classic Bluetooth само по себе не является ошибкой BLE. Если конкретная прошивка
CC600 требует именно `0x06` для мощности, телефон может отбрасываться ещё до
GATT. Это конкретная гипотеза для проверки, а не установленное поведение CC600.
То, что ЧСС обнаруживается, не позволяет автоматически приписать той же
прошивке идентичные фильтры для обоих типов датчиков.

Flags формирует стек Android. Публичный
[AdvertiseData.Builder](https://developer.android.com/reference/android/bluetooth/le/AdvertiseData.Builder)
не даёт произвольно установить Flags или добавить AD Appearance. В AOSP это
видно в [LE advertising manager](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/system/gd/hci/le_advertising_manager.cc).
Копирование `02 01 06` внутрь manufacturer/service data создаст другое AD-поле,
а не заменит системный Flags. Такую подмену в приложение добавлять нельзя:
она не воспроизведёт рекламу ESP32.

В журнале также есть проприетарная реклама vivo без `1818` под тем же адресом.
Кроме того, клиент Redmi видит системные сервисы Android в общей GATT-базе.
Кэширование типа устройства по адресу, фильтр телефона или ограниченный обход
базы CC600 — другие проверяемые гипотезы. Приложение не управляет чужими
системными сервисами и не может удалить их из общего списка.

## Что нельзя переносить из примеров

* Arduino объявляет фиксированную длину Measurement 4, но формирует 8 байт и
  использует `0x2000` вместо бита Crank Revolution Data `0x0020`. Библиотека
  ограничивает уведомление длиной характеристики. Это не образец правильной
  передачи каденса: [BLECharacteristic.cpp](https://github.com/adafruit/Adafruit_nRF52_Arduino/blob/bd0747473242d5d7c58ebc67ab0aa5098db56547/libraries/Bluefruit52Lib/src/BLECharacteristic.cpp#L527).
* ESP32-пример записывает один 8-байтный буфер одновременно в Measurement и
  Feature; Feature должен иметь длину 4. Копирование этого кода ухудшит
  соответствие профилю.
* Zwack выделяет 14 байт независимо от наличия необязательных полей и вручную
  добавляет CCCD поверх автоматически создаваемого bleno. Проблема двойного CCCD
  зафиксирована в [issue #25](https://github.com/paixaop/zwack/issues/25).

## Проверка, которая разделит причины

1. На том же CC600 проверить **мощность с Redmi как передатчика**, установив ту
   же сборку GlucoPower 1.7 и включив тест 93 Вт. Работа Redmi как клиента nRF
   или передатчика ЧСС — другие проверки и этого результата не заменяют.
2. На CC600 при обеих проверках удалить только старую запись соответствующего
   телефона как датчика. Не менять настоящие ЧСС и каденс. На телефонах оставить
   выбранным только режим мощности и одинаковые настройки приложения.
3. Сохранить диагностику самого GlucoPower после 30–60 секунд поиска CC600:
   `ACL`, `LINK`, `GATT ACCESS`, `READ`, `WRITE CCCD`.
4. Записать Bluetooth HCI snoop vivo с одной такой попыткой и сохранить
   системный bug report по [инструкции AOSP](https://source.android.com/docs/core/connect/bluetooth/verifying_debugging).
   Проверить создание LE-соединения, ATT discovery и причины разрыва. Полная
   трасса может содержать сведения о других устройствах, поэтому для передачи
   нужен только относящийся к проверке фрагмент.

Если Redmi находится как мощемер, проблема сужается до конкретного BLE-стека,
рекламы, системной базы или идентичности vivo. Если оба не находятся, остаётся
общая несовместимость способа эмуляции Android с поиском мощности CC600.
Ни один результат сам по себе ещё не доказывает фильтрацию по биту Flags.

Если нет обращения к GATT приложения, изменение Watts, Feature, cadence и
очереди notifications не проверяет причину отказа поиска. Если обращения есть,
по их порядку уже можно найти конкретную характеристику или ATT-ответ,
после которого CC600 отказывается от датчика. Для такого вывода в текущих
вложениях данных недостаточно.

## Дополнительная проверка Android-клиента и Nordic

### DragosStahie/heart_rate_monitor

Это приёмник, а не эмулятор датчика:
[BLEScanner.kt](https://github.com/DragosStahie/heart_rate_monitor/blob/main/app/src/main/java/com/dragosstahie/heartratemonitor/ble/BLEScanner.kt)
фильтрует сканирование по `180D`, а
[BLEDeviceConnection.kt](https://github.com/DragosStahie/heart_rate_monitor/blob/main/app/src/main/java/com/dragosstahie/heartratemonitor/ble/BLEDeviceConnection.kt)
вызывает `connectGatt()`, discovery и запись CCCD чужого пульсометра.
Кода рекламы или `BluetoothGattServer` в нём нет. Эти операции выполняются
на стороне, аналогичной велокомпьютеру, а не GlucoPower.

Его парсер не стоит переносить: 16-битное значение ЧСС там собирается как
big endian, тогда как стандарт требует little endian. Callback API 33 также
читает `characteristic.value`, хотя уже получает отдельный массив `value`.
GlucoPower использует little endian, а на API 33+ передаёт массив данных
непосредственно в `notifyCharacteristicChanged()`; существующие тесты покрывают
8- и 16-битный формат ЧСС. Наличие такого примера само по себе не подтверждает
исправность любого Android-приёмника или передатчика на Android 16.

### Nordic Android BLE Library

Указанный [BLE-COMMON.md](https://github.com/nordicsemi/Android-BLE-Library/blob/main/BLE-COMMON.md)
описывает дополнительные парсеры входящих профилей, в том числе HR, CSC и glucose.
Реализации рекламы CPS в этом документе нет. Поэтому отдельно проверены серверные
классы Nordic на ревизии `da698f297c75784ca3932bc19777cde93ec7c975`:

* [BleServerManager.close()](https://github.com/nordicsemi/Android-BLE-Library/blob/da698f297c75784ca3932bc19777cde93ec7c975/ble/src/main/java/no/nordicsemi/android/ble/BleServerManager.java#L116)
  и [отмена управляемого соединения](https://github.com/nordicsemi/Android-BLE-Library/blob/da698f297c75784ca3932bc19777cde93ec7c975/ble/src/main/java/no/nordicsemi/android/ble/BleManagerHandler.java#L824)
  разделяют владение соединением и наблюдение callback. По этому образцу в
  GlucoPower 1.8 отменяются только `retainedClients`, затем закрывается GATT-сервер.
* [useConnection()](https://github.com/nordicsemi/Android-BLE-Library/blob/da698f297c75784ca3932bc19777cde93ec7c975/ble/src/main/java/no/nordicsemi/android/ble/BleServerManager.java#L167)
  подтверждает необходимость `connect(device, false)` для удержания используемого
  серверного соединения. Такой вызов уже добавлен в GlucoPower 1.7.
* [Обработчик Execute Write](https://github.com/nordicsemi/Android-BLE-Library/blob/da698f297c75784ca3932bc19777cde93ec7c975/ble/src/main/java/no/nordicsemi/android/ble/BleManagerHandler.java#L3308)
  всегда завершает ATT-запрос ответом. В GlucoPower 1.8 добавлены ответы на Execute
  Write и Cancel; применение prepared writes по-прежнему не поддерживается.
  Для пустой очереди оба ответа успешны, как требует
  [Bluetooth Core ATT §3.4.6.3](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-54/out/en/host/attribute-protocol--att-.html).

Полная замена транспорта на Nordic не требуется для этих исправлений. Приёмы
последовательной регистрации сервисов, очереди уведомлений и раздельного состояния
подписок уже используются. Изменения 1.8 относятся к управлению соединением после
подключения и ответам ATT; они не устанавливают причину отбрасывания vivo в поиске
датчиков мощности CC600.
