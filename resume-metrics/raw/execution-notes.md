# Execution notes

## 2026-08-22 11:43 run

Harness failed before login because the first script version treated Mailpit `To` as a scalar. No business benchmark requests ran; the script was fixed.

## 2026-08-22 11:44 run

Login, course creation, upload, material parse and embedding completed. Knowledge-version confirmation returned HTTP 500 (requestId `ee9b2911-6020-4935-9aef-be7f3b08fc1b`) for `finalweek-metrics-20260822-114437@example.com`, while the associated outline task was created and later reached `SUCCEEDED`. The run stopped and produced no replay rows.

## 2026-08-22 11:47 run

Full acceptance run completed. Cold samples and 300 formal replay samples were saved under `async-acceptance-20260822-114700.*`.

## 2026-08-22 11:50 run

RabbitMQ idempotency run completed. The earlier one-message format preflight is separate from the formal 180-message count.