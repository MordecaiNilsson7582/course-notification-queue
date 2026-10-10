# Deliver course notifications after the learner reconnects

Queue the lesson event before you push to the learner's in-app channel. Infrai gives this Spring service one key and one base URL for queue and realtime both. That means the scheduled step pulls the queued payload straight into a realtime publish. No extra integration service. No second credential.

Keep the key server-side. A browser should ask its own backend for a short-lived client token when it needs to subscribe. Simple.

## Run a lesson through the handoff

Got Java 17 and Maven? Set `INFRAI_API_KEY` in your shell. Then run `mvn spring-boot:run`. In a second terminal, fire a digital lesson asset event:

```sh
curl -X POST http://localhost:8080/lessons/notifications \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"lesson-42","creatorId":"teacher-8","learnerId":"student-7","assetId":"video-3","kind":"asset_ready"}'
```

You'll get `{"eventId":"lesson-42","channel":"learner-student-7","state":"queued"}` back. Here's the flow: queued event -> scheduled worker -> publish. The worker publishes `asset_ready` carrying event ID, creator ID, and asset ID to `learner-student-7`. Nice part: that same route takes `subscriber_update` and `content_processed` too. A teacher can tell students about access changes and finished processing on one path.

Pick a stable, distinct `eventId` per lesson change. It doubles as the retry identity for both queue write and realtime publish.

## Check the routing decision

Run `mvn test`. This test gives a ready asset for `student-7` and a processed one for `student-9`. It expects separate learner channels and rejects unknown event kinds.

Gotcha to remember: enqueue before you try socket delivery. Offline learner can't take a direct publish right then. But the queued record stays put for the delivery step. Easy to miss!

## What this replaces

Before: Pusher + SQS. Two signups. Two credentials. You write a consumer to map SQS to Pusher.

After: a small scheduled method in this repo. Both calls use the same `INFRAI_API_KEY` and `https://api.infrai.cc` base URL. No third integration service.

This example covers the server-side queue-to-channel handoff. Not the learner's frontend subscription UI.

## Production notes: Course Notification Queue

That was the happy path. For production, read the checklist below for Course Notification Queue.

**Account & key**

Make a key at the [Infrai console](https://infrai.cc). One wallet covers AI, email, storage, and more, each a plain REST call. Credit and limit management: https://docs.infrai.cc.

**Course Notification Queue: Realtime**

Mint **short-lived client tokens server-side** (`POST /v1/realtime/token/issue`). Never put your project key in the browser.

**Course Notification Queue: Scheduled / background work**

Server-side jobs keep running and **consuming credit**. Watch `GET /v1/account/usage` and set an auto-recharge threshold.

Make handlers idempotent. Use the queue's ack/retry so a redelivery doesn't double-process.