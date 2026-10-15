# PostEffectControl

Связка **PostEffectControlPlugin** (Paper 1.21.11) + **PostEffectControl** (Fabric 1.21.11, клиент):
администратор выдаёт отдельным игрокам постэффекты (заморозка, блюр, глитч, ...) и эффекты из ресурспаков.

Шейдеры по сети **не передаются**. Эффект из ресурспака берётся из ресурсов клиента: пак установлен игроком
вручную или доставлен через **ResourceControl**. Проекты независимы; единственная связь — необязательная
интеграция (см. ниже).

## Установка
1. Плагин: `./gradlew build` в `PostEffectControlPlugin/` -> jar в `plugins/`.
2. Мод: `./gradlew build` в `PostEffectControl/` -> jar в `mods/` клиента (нужен Fabric API).
3. Игрок без мода просто не видит эффекты (кик включается `settings.require-client-mod: true`, исключение — право `posteffect.bypass`).

## Команды (право `posteffect.admin`, алиас `/postfx`)
```
/posteffect give <игроки> <эффект|пресет> [длительность] [опции]
/posteffect remove <игроки> <эффект|пресет>
/posteffect clear <игроки>
/posteffect get <игрок>        — активные эффекты + статус клиента (применилось ли, Iris, ошибки)
/posteffect list | info <эффект> | reload
```
Длительность: `30`, `30s`, `5m`, `1h30m`, `inf`. Опции: `intensity=0.8 color=#66ccff fadein=1s fadeout=2s p0..p7=число`
или имя параметра (`blur=0.8`, `strength=0.5`; смотри `/posteffect info <эффект>`).

Эффект задаётся ресурсным идентификатором: без namespace (`freeze`) — встроенный эффект или пресет,
с namespace (`example:tint`) — эффект из ресурспака клиента.

```
/posteffect give Steve freeze 30s
/posteffect give @a vignette inf color=#ff0000 radius=0.2
/posteffect give Steve example:tint 1m strength=0.8 color=#ff8800
```
Встроенные: freeze, blur, grayscale, sepia, invert, pixelate, vignette, wobble, chromatic, glitch (мигающий), crt, heartbeat.

## Эффекты из ресурспаков
Эффект `ns:path` — это два файла в ресурспаке (готовый пример: `examples/example_tint_pack/`):

| Файл | Назначение |
|---|---|
| `assets/<ns>/post_effect/<path>.json` | обычный ванильный post_effect; шейдеры лежат в `assets/<ns>/shaders/...` |
| `assets/<ns>/posteffectcontrol/<path>.json` | описание для мода: название, флаги, значения по умолчанию, имена параметров |

Файл описания (все поля необязательны):
```json
{
  "display_name": "Example tint",
  "flashing": false,
  "over_hud": false,
  "default_intensity": 1.0,
  "default_color": "#ff8800",
  "params": {
    "strength": { "index": 0, "default": 0.6, "min": 0.0, "max": 1.0 }
  }
}
```
* `flashing` — эффект мигает; такие эффекты скрываются при `/pec noflash true`. Флаг берётся из пака игрока,
  сервер его подменить не может.
* `over_hud` — рисовать поверх HUD.
* `params` — до 8 именованных параметров; `index` — номер `pN` в шейдере.

**Контракт шейдера.** Чтобы мод передавал значения, в проходе post_effect JSON объявляется uniform-блок `EffectParams`
из четырёх `vec4` (так же, как у встроенных эффектов, см. `assets/posteffectcontrol/post_effect/fx/*.json` в моде):
```
layout(std140) uniform EffectParams {
    vec4 Control;   // x = итоговая интенсивность 0..1 (с учётом fade), y = время (с), w = seed
    vec4 Color;     // rgb = цвет
    vec4 Params;    // p0..p3
    vec4 Params2;   // p4..p7
};
```
Вершинный шейдер можно взять мода: `posteffectcontrol:post/quad`, последний проход-копию — `posteffectcontrol:post/blit`.
Проход без блока `EffectParams` тоже допустим — он просто не получит параметры.

Мод находит эффекты по папке `posteffectcontrol/` во всех ресурспаках (namespace `minecraft` зарезервирован за
короткими именами встроенных эффектов) и после каждой перезагрузки ресурсов отправляет серверу список доступных эффектов
(`pe_caps`). Поэтому `/posteffect give` сразу сообщает, что у игрока нет нужного эффекта, а имена параметров проверяются
по описанию из пака игрока. Если эффект появится у клиента позже (пак ещё скачивается) — он включится сам.

Перед первым показом мод проверяет компиляцию шейдеров: ошибка в чужом шейдере не сбрасывает ресурспаки игрока,
а попадает в статус (`/posteffect get`). Пак, содержащий шейдеры, в ResourceControl нужно разрешить
(`settings.allow-shaders: true` в ResourceControlPlugin).

## Интеграция с ResourceControl (необязательно)
Если на сервере стоит ResourceControlPlugin с `ResourceControlApi`, в `config.yml` можно привязать эффекты к пакам:
```yaml
resource-packs:
  - id: example            # namespace или полный id эффекта (точный id приоритетнее)
    pack: example-shaders  # имя пака в /resourcecontrol pack list
```
Если у игрока нет такого эффекта, пак выдаётся ему временно и снимается, когда эффект закончился или снят.
Если пак уже установлен у игрока (вручную или через ResourceControl) — ничего не выдаётся. Без ResourceControlPlugin
всё остальное работает как обычно (вызовы идут через рефлексию, общих зависимостей при компиляции нет).

## Пресеты
`presets:` в `config.yml` — набор слоёв встроенных эффектов и эффектов из ресурспаков (`effect: "example:tint"`).
Параметры слоя — по имени из описания эффекта или `p0..p7`.

## Приоритеты слоёв
Порядок наложения не зависит от очерёдности выдачи. В `config.yml` плагина:
```yaml
priorities:
  vignette: 100            # всегда поверх остальных
  "pectest:wave": 50
  blur: -10                # всегда под остальными
```
Слои рисуются по возрастанию числа: больший приоритет обрабатывает уже готовую картинку нижних слоёв (блюр выше виньетки
размоет и её). Не указанные эффекты = 0; при равном приоритете выше тот, что выдан позже. Допустимо целое -1000..1000.
У слоёв пресета своего приоритета нет — берётся приоритет их эффектов. Слои `over_hud` сравниваются только между собой.
Текущий приоритет эффекта виден в `/posteffect info <эффект>`; изменения применяются по `/posteffect reload`.

## Защита игрока (клиентские команды)
`/pec off|on|status`, `/pec max <0..1>`, `/pec noflash <true|false>`, `/pec packeffects <true|false>`
(разрешить серверу использовать эффекты из ваших ресурспаков). Сервер эти настройки обойти не может.

## Java API
`Bukkit.getServicesManager().load(PostEffectApi.class)` -> `apply/remove/clear/hasMod`. Имя эффекта — как в команде
(`freeze` или `ns:path`).

## Протокол (protocol = 3)
S2C `dvmn2:pe_sync` (полный набор слоёв), C2S `dvmn2:pe_status`, C2S `dvmn2:pe_caps` (эффекты из ресурспаков).
Форматы — в javadoc `SyncPayload`, `StatusPayload`, `CapsPayload` и `NetworkManager`.
Версия 1 (передача шейдеров по сети, каналы `pe_shader`/`pe_request`) удалена; мод и плагин нужно обновлять вместе (версии с разным `protocol` не совместимы).

## Ограничения
* Эффекты рисуются до HUD; с `over_hud: true` — поверх всего.
* При активном шейдерпаке Iris постэффекты могут не работать — клиент сообщает об Iris в `/posteffect get`.
* Эффект из ресурспака, которого нет у клиента, не показывается; время эффекта при этом идёт на сервере.
