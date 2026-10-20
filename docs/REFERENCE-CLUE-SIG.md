# Adafruit CLUE и Bluetooth SIG Assigned Numbers — 2026-10-20

Материалы: пользовательский PDF «Bluetooth Cycling Speed & Cadence Sensor Display with Clue», 23 страницы, John Park, обновление24.02.2025; официальный репозиторий https://bitbucket.org/bluetooth-SIG/public/src/main/assigned_numbers/ (локально .tools/references/bluetooth-sig-public, commit43473addb5339859b853c9ba6227c426f82deb42). Веб-просмотр Bitbucket не сработал, git-копия прочитана успешно. PDF извлечён целиком, страница11 с nRF Connect просмотрена визуально.

## Что действительно полезно

1. PDF — реализация central/приёмника CSC, не peripheral CPS и не прошивка CC600. На стр16/20 пример выбирает рекламу по наличию CyclingSpeedAndCadenceService в adv.services, индексирует найденные устройства по адресу и только затем подключается. Он не содержит явного фильтра Appearance или имени. Это пример разделения discovery/GATT, но не доказательство алгоритма CC600.
2. Стр3 прямо предупреждает: дисплей показывает сырые накопленные обороты, а не вычисленные RPM. На стр11 у Wahoo показаны счётчики wheel/crank и времена последних событий. Нельзя копировать число93 непосредственно в поле crank revolutions и ожидать93об/мин. Наш CadenceEncoder уже генерирует меняющиеся счётчики и event time; проверен исходный код. По спецификации CSC время в1/1024с; cadence = 60*1024*deltaRevolutions/deltaTicks с учётом переполнения. Поэтому показания могут появляться не с первого уведомления; точная задержка/усреднение CC600 неизвестны.
3. Wahoo в PDF комбинирует скорость+каденс. Его Feature и SC Control Point нельзя слепо переносить в crank-only эмулятор. CSC спецификация требует CP только при поддержке соответствующих процедур; объявленные возможности должны совпадать с реальным payload. В нашем CSC Feature0200 и flags02 согласованы с crank-only.
4. Assigned Numbers — справочник идентификаторов, не матрица совместимости CC600 и не формат пакета каждой характеристики. UUID проверены: HRS180D, CSC1816, CPS1818; CSC Measurement2A5B/Feature2A5C, CPS Measurement2A63/Feature2A65, SensorLocation2A5D, CCCD2902.
5. core/appearance_values.yaml: Cycling category0x012; подкатегории Speed02, Cadence03, Power04, SpeedAndCadence05. Полное значение category<<6|subcategory:0482/0483/0484/0485. Важное различие: это Appearance, а не UUID сервисов и не SensorLocation05 (левый шатун). Корректное значение0484 само по себе не доказывает, что CC600 фильтрует по нему.
6. core/ad_types.yaml: AD02 — неполный список16-bit UUID, AD03 — полный; AD16 — Service Data16-bit UUID; AD19 — Appearance; AD14 — Service Solicitation (запрос сервиса, не объявление предоставляемого). Поэтому E1.18 не заменяет Appearance. Не добавлять solicitation как фиктивный способ объявить мощемер. Android Builder не даёт переключателя complete/incomplete list; реальная реклама vivo уже показывала полный список16-bit UUID1818.
7. Для декодирования сырых пакетов полезные примеры: 03 03 18 18 — полный список с CPS; 03 03 16 18 — CSC; 03 19 84 04 — Appearance Power Sensor. Первый байт — длина последующих type+value, UUID/Appearance little-endian. Flags02 в значении AD01 не то же самое, что ADtype02. Несколько пространств идентификаторов нельзя смешивать.

## Практический вывод

Нового доказанного дефекта GlucoPower или обязательного отсутствующего поля для CC600 материалы не выявили. Не менять сборку1.18 до её сравнительного теста. A/B/C проверяют размещение UUID/имени, D зависит от scan response, E проверяет дополнительный типAD16. Если варианты не помогут, нужен scan действительно обнаруживаемого CC600 BLE-мощемера; только тогда сравнение даст эталон для этого приёмника. CLUE может служить независимым CSC-клиентом, но его успех не подтверждает CPS или фильтры CC600.

Источники: пользовательский PDF, стр3,9–12,16–21; https://www.bluetooth.com/specifications/assigned-numbers/ ; YAML core/appearance_values.yaml, core/ad_types.yaml, uuids/service_uuids.yaml, uuids/characteristic_uuids.yaml; https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/CSCS_v1.0/out/en/index-en.html (3.1/3.4).

Код и APK не изменялись. Аппаратных тестов не выполнялось.
