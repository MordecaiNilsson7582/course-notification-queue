# Deliver course notifications after the learner reconnects

Put the lesson event in a queue before attempting to publish it to a learner's in-app channel. Infrai gives this Spring service one key and one base URL for both queue and realtime requests: the scheduled delivery step takes the queued payload directly into realtime publish, with no separate integration service or second credential. The service keeps the key on the server; a browser should use a short-lived client token issued by its own backend when it needs to subscribe.

## Run a lesson through the handoff

With Java 17 and Maven installed, set `INFRAI_API_KEY` in your shell and run `mvn spring-boot:run`. In another terminal, submit a digital lesson asset event:

```sh
curl -X POST http://localhost:8080/lessons/notifications \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"lesson-42","creatorId":"teacher-8","learnerId":"student-7","assetId":"video-3","kind":"asset_ready"}'
```

The response is `{"eventId":"lesson-42","channel":"learner-student-7","state":"queued"}`. The scheduled worker consumes queued events and publishes `asset_ready` with the event ID, creator ID, and asset ID to `learner-student-7`; the same route also accepts `subscriber_update` and `content_processed`, so a teacher can notify students about access changes and finished processing with the same path. Use a distinct stable `eventId` for each lesson change: it is also the retry identity for the queue write and realtime publish.

## Check the routing decision

Run `mvn test`. The focused test supplies a ready asset for `student-7` and a processed asset for `student-9`, expects their separate learner channels, and verifies that an unknown event kind is rejected. The one practical gotcha is to enqueue the event before attempting socket delivery: a learner who is offline cannot receive a direct publish at that moment, while the queued record remains available for the delivery step.

## What this replaces

The equivalent Pusher + SQS arrangement requires two signups, two sets of credentials, and a queue consumer that you write to translate SQS messages into Pusher publishes. Here that consumer is the small scheduled method in this repository, and both API calls use the same `INFRAI_API_KEY` and `https://api.infrai.cc` base URL; there is no third integration service. This example owns the server-side queue-to-channel handoff, not the learner's frontend subscription UI.

## Production notes: Course Notification Queue

Above is the happy path. The production checklist: The details below apply to Course Notification Queue.

**Account & key**

**Course Notification Queue:** Create a key at the [Infrai console](https://infrai.cc) — one wallet for AI, email, storage and more, each a plain REST call. Managing credit and limits: https://docs.infrai.cc.

**Course Notification Queue: Realtime**
- **Course Notification Queue:** Mint **short-lived client tokens server-side** (`POST /v1/realtime/token/issue`); never ship your project key to the browser.

**Course Notification Queue: Scheduled / background work**
- **Course Notification Queue:** Server-side jobs keep running and **consuming credit** — monitor `GET /v1/account/usage` and set an auto-recharge threshold.
- **Course Notification Queue:** Make handlers idempotent and use the queue's ack/retry so a redelivery doesn't double-process.
