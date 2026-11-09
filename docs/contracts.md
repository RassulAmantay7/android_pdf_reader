# Контракты между модулями (главная сессия)

Ускоренный режим (одобрен пользователем): сразу полная v1, проверка на телефоне в конце.

## Отклонения от docs/plan.md
1. **UI на Android View, без Compose и без androidx** — 0 сторонних зависимостей. Причина: найден android.jar API 35, и весь Kotlin-код (:core и :app) компилируется локально (`scripts/check.sh`). С Compose это невозможно (нет Google Maven). Риск «слепой» сборки в CI сводится к XML-ресурсам и Gradle-конфигу.
2. **Версии — проверенная связка вместо самых новых:** Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21, JDK 17, compileSdk/targetSdk/minSdk 35. Локальный компилятор — тоже Kotlin 2.0.21 (из дистрибутива Gradle).
3. **Пометки: путь «С»** (свой инкрементальный писатель /Ink, /Highlight, /Text в :core) — рекомендация планировщика, работает на любом Android 15.
4. Спайк G1 на телефоне объединён с финальной проверкой.

## Правила для всех агентов
- Без XML-разметки и без `R.*` в Kotlin: интерфейс строится в коде. Ресурсы — только манифест, тема, иконка.
- Только публичный API Android SDK 35 (android.jar — сторонняя копия AOSP; скрытые API могут скомпилироваться локально, но упадут в CI).
- Тексты интерфейса — на русском.
- Перед сдачей: `scripts/check.sh` → `CHECK: OK`.
- Свои файлы — только из своей зоны. Сигнатуры в файлах-контрактах не менять (добавлять можно).

## Система координат
«Координаты страницы» — пункты PDF (1/72″), начало в левом верхнем углу видимой (повёрнутой) страницы, Y вниз — как у android.graphics.pdf. Перевод в пространство PDF (/MediaBox, /CropBox, /Rotate) — только в `PdfAnnotationWriter`.

## Зоны ответственности
| Агент | Файлы |
|---|---|
| core-pdf | `core/src/main/kotlin/.../core/pdf/**`, тесты `core/src/test/.../pdf/**` |
| reader-view | `app/.../reader/PdfDoc.kt`, `PdfReaderView.kt`, новые `app/.../reader/view/**`, `core/.../core/geometry/**` + тесты |
| ui-designer | `app/.../ui/**`, `app/.../data/**`, `MainActivity.kt`, `app/.../settings/**`, `docs/design.md`, `res/**` |
| reader-screen | `app/.../reader/ReaderActivity.kt`, новые `app/.../reader/screen/**` |
| главная сессия | Gradle, манифест, CI, `core/.../annot/**`, docs |
