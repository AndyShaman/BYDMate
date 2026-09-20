# Reference Alice Worker / VPS

Это референсная single-car реализация bridge для интеграции Яндекс Алисы с BYDMate. Она предназначена для тестирования и как пример протокола, а не как готовый multi-user backend.

## Что находится в папке

- `worker.mjs` - логика исходного Cloudflare Worker: Yandex Smart Home + BYDMate API.
- `server.mjs` - тонкий Node.js адаптер для запуска той же логики на обычном VPS.
- `d1-sqlite.mjs` - минимальный D1-compatible слой поверх SQLite.
- `.env.example` - обязательные переменные.
- `bydmate-alice.service.example` - пример systemd unit.
- `nginx.example.conf` - пример HTTPS reverse proxy.

Расширенный список semantic actions в Worker сохранён намеренно как reference catalog. Android BYDMate безопасно отклоняет команды, которых нет в его публичном контракте.

## Быстрый запуск на VPS

Требуется Node.js 20+ и публичный HTTPS-домен.

```bash
sudo mkdir -p /opt/bydmate-alice
sudo chown "$USER":"$USER" /opt/bydmate-alice
cd /opt/bydmate-alice
# скопировать содержимое этой папки
npm install
cp .env.example .env
nano .env
npm start
```

Проверка:

```bash
curl https://alice.example.com/health
```

В BYDMate указать:

- Endpoint URL: `https://alice.example.com`
- API Key: значение `BYDMATE_API_KEY` из `.env`

Для полного голосового теста создать приватную интеграцию Smart Home в Yandex Dialogs и указать этот HTTPS endpoint. `YANDEX_CLIENT_ID` должен соответствовать OAuth client ID этой интеграции.

## Ручной тест без Алисы

Сначала можно проверить Android bridge вообще без Yandex:

```bash
curl -X POST https://alice.example.com/api/enqueue \
  -H 'X-Api-Key: YOUR_KEY' \
  -H 'Content-Type: application/json' \
  -d '{"action":"climate.on"}'
```

Автомобиль должен получить команду через `/api/poll`, выполнить её и отправить результат в `/api/ack`.

Навигация использует единую команду `app.navigation.open`; конкретное приложение выбирается в настройках BYDMate.

## Production

Для публичного сервиса текущую single-car схему нужно расширить: user/car pairing, отдельные очереди и state на автомобиль, отзываемые car credentials, rate limiting и изоляция пользователей.
