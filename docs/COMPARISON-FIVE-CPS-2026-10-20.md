# Пять реализаций CPS: сравнение с CC600, 2026-10-20

Исходники скачаны для чтения в .tools/references; сторонний код не исполнялся.

| Проект / просмотренный commit | Обнаружение | Сервис мощности |
|---|---|---|
| qubeck/OpenBikePowerMeter f7c09486097da06ac083f7e52fda82dc1714d491 | src/ble_svcs.cpp: имя, TX, 1818, явные Flags LE_ONLY_GENERAL_DISC_MODE; интервалы 1000 мс fast; явного Appearance нет | Notify, Feature с crank/offset compensation, Location, Control Point для калибровки |
| stevejarvis/powermeter ffcaa34c78c26ef7de67002da41961e8300f65d1 | ble.ino: имя, TX, CPS, Flags LE_ONLY_GENERAL_DISC_MODE; 20/152.5 мс; явного Appearance нет | Measurement 4 байта, Feature 00000000, Location 05; практически наш минимальный CPS |
| jdt23/powermeter 4f4064af0b4c080c40158b669a7fbcef0c0aeb82 | powermeterSensor/powermeterSensor.ino: ArduinoBLE.setAdvertisedService(1818), local name | 2A63 Read/Notify 4 байта, но нет обязательных 2A65/2A5D; нестандартно размещены resistance/cadence. Не эталон полной CPS-совместимости |
| kswiorek/ble-powermeter adff051d6202a4876b135ad20919fa3ae37709cf | powerMeter/powerMeter.ino: ESP32 addServiceUUID(1818), имя FT7; параметры остального пакета делегированы библиотеке, явного Appearance нет | 2A63 Notify+BLE2902, 2A65, 2A5D=05; 8 байт измерения, flags0020, crank counters; Feature 0x00100008 |
| nebucaz/kracerble a3daaa51b668154ece177ec2780bb29577946d14 | Sources/kracerble/GATT_Characteristics/BLEPeripheral.swift: имя и полный список 16-bit UUID в scan response; iBeacon/flags в комментарии не выполняются | Services/CyclingPowerService.swift: CPS, нулевые Feature/Measurement flags, Location rearWheel; Control Point не добавлен. README ориентирован на Zwift |

Ни README, ни просмотренный BLE-код этих проектов не дают подтверждения работы с Geoid CC600 V1.408. Отсутствие явного вызова Appearance в приложении не заменяет захват итогового пакета библиотеки.

## Наиболее существенное найденное отличие до подключения

У qubeck и stevejarvis макрос LE_ONLY_GENERAL_DISC_MODE = 0x02 | 0x04 = 0x06. У vivo в реальной HCI-трассе Flags=02. Разница — BR/EDR Not Supported, а не discoverable/connectable или UUID мощности. Это осмысленная гипотеза фильтра CC600 для CPS, но НЕ установленная причина. ЧСС/каденс на этом же телефоне работают после удаления пары, поэтому утверждать глобальный запрет всех dual-mode телефонов нельзя. Подтверждение потребует A/B с одинаковым CPS на программируемом передатчике (02 против 06, отдельно Appearance off/0484). Произвольные Flags и Appearance публичный Android AdvertiseData.Builder не задаёт; setDiscoverable меняет discoverability, а не отсутствие BR/EDR. Подмена этих полей manufacturer/service data некорректна.

Другие гипотезы: сохранённый тип датчика/сопряжение на CC600; ограничения BLE CPS его прошивки; обработка RPA. Источник адресов и библиотечные default-флаги всех микроконтроллеров по одному приложению не установлены. Не считать public/random-static обязательным признаком CPS.

## Что уже исключает старая трасса

В 21:13 телефон рекламировал 1818 legacy/connectable/1M и не получил входящего LE-соединения. Поэтому Feature, Location, каденс внутри CPS, Control Point и формат notify не могли быть прочитаны в этой попытке. Отдельный nRF-клиент ранее получил 74 пакета 00005D00. Изменение характеристик не объясняет текущий отказ на этапе поиска. Нулевой Feature не означает отсутствие поддержки мощности: базовая instantaneous power передаётся без дополнительных capability-битов; stevejarvis использует именно этот вариант.

Проверка после удаления пары важнее очередной модификации CPS: пользователь подтвердил восстановление ЧСС/каденса, но не сообщил результат мощности 1.17 после удаления пары. Нужен этот результат; при отказе — сравнительный scan реально обнаруживаемого CC600 BLE-мощемера (не ANT+) и vivo. Фиксировать AD bytes, Flags, Appearance, UUID и тип адреса. Одинаковый адрес для разных профилей делает сохранённый тип датчика гипотезой, не доказательством.

Код приложения не изменён: подтверждённой ошибки, которую исправляет перенос из этих репозиториев, не найдено. Новый APK ради недоказанной причины не собран.

## Первичные источники

- https://github.com/qubeck/OpenBikePowerMeter/blob/f7c09486097da06ac083f7e52fda82dc1714d491/src/ble_svcs.cpp
- https://github.com/stevejarvis/powermeter/blob/ffcaa34c78c26ef7de67002da41961e8300f65d1/ble.ino
- https://github.com/jdt23/powermeter/blob/4f4064af0b4c080c40158b669a7fbcef0c0aeb82/powermeterSensor/powermeterSensor.ino
- https://github.com/kswiorek/ble-powermeter/blob/adff051d6202a4876b135ad20919fa3ae37709cf/powerMeter/powerMeter.ino
- https://github.com/nebucaz/kracerble/blob/a3daaa51b668154ece177ec2780bb29577946d14/Sources/kracerble/GATT_Characteristics/BLEPeripheral.swift
- https://github.com/nebucaz/kracerble/blob/a3daaa51b668154ece177ec2780bb29577946d14/Sources/kracerble/Services/CyclingPowerService.swift
- https://docs.nordicsemi.com/r/bundle/s112_v6.1.0_api/page/group_b_l_e_g_a_p_a_d_v_f_l_a_g_s.html
- https://developer.android.com/reference/android/bluetooth/le/AdvertiseData.Builder
- Локально: REPORT-VIVO-2026-10-19-2113.md, REPORT-NRF-2026-10-19.md, VALIDATION-1.17.md.
