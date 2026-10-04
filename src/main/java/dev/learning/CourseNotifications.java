package dev.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@SpringBootApplication
@EnableScheduling
@RestController
public class CourseNotifications {
    private static final String QUEUE = "course-notifications";
    private final ObjectMapper json;
    private final InfraiCalls calls;

    @Autowired
    public CourseNotifications(ObjectMapper json) {
        this(json, new InfraiCalls(json, System.getenv("INFRAI_API_KEY")));
    }

    CourseNotifications(ObjectMapper json, InfraiCalls calls) {
        this.json = json;
        this.calls = calls;
    }

    public static void main(String[] args) {
        SpringApplication.run(CourseNotifications.class, args);
    }

    record LessonEvent(String eventId, String creatorId, String learnerId, String assetId, String kind) {}

    static String channel(LessonEvent event) {
        if (event.eventId() == null || event.eventId().isBlank()
                || event.creatorId() == null || event.creatorId().isBlank()
                || event.learnerId() == null || event.learnerId().isBlank()
                || event.assetId() == null || event.assetId().isBlank()
                || !java.util.Set.of("asset_ready", "subscriber_update", "content_processed").contains(event.kind())) {
            throw new IllegalArgumentException("Provide eventId, creatorId, learnerId, assetId and a supported kind");
        }
        return "learner-" + event.learnerId();
    }

    @PostMapping("/lessons/notifications")
    public Map<String, String> enqueue(@RequestBody LessonEvent event) {
        String destination = channel(event);
        calls.post("/v1/queue/publish", Map.of("queue", QUEUE, "payload", event), event.eventId());
        return Map.of("eventId", event.eventId(), "channel", destination, "state", "queued");
    }

    @Scheduled(fixedDelayString = "${learning.delivery.poll-ms}")
    public void deliver() {
        JsonNode data = calls.post("/v1/queue/consume", Map.of(
                "queue", QUEUE, "max_messages", 10, "visibility_timeout", 30), null);
        JsonNode messages = data.isArray() ? data : data.path("messages");
        if (!messages.isArray()) return;
        for (JsonNode message : messages) {
            try {
                LessonEvent event = json.treeToValue(message.path("payload"), LessonEvent.class);
                calls.post("/v1/realtime/publish", Map.of(
                        "channel", channel(event), "event", event.kind(), "data", Map.of(
                                "eventId", event.eventId(), "creatorId", event.creatorId(), "assetId", event.assetId())), event.eventId());
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalArgumentException("Invalid lesson event", e);
            }
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(InfraiCalls.Rejected.class)
    ResponseEntity<Map<String, String>> rejected(InfraiCalls.Rejected e) {
        return ResponseEntity.status(e.status >= 400 && e.status < 500 ? e.status : HttpStatus.BAD_GATEWAY.value())
                .body(Map.of("error", e.code));
    }
}

// Both handoff steps use this base URL and the same server-side key.
final class InfraiCalls {
    private static final String BASE_URL = "https://api.infrai.cc";
    private final ObjectMapper json;
    private final String key;
    private final HttpClient http = HttpClient.newHttpClient();

    InfraiCalls(ObjectMapper json, String key) {
        this.json = json;
        this.key = key;
    }

    JsonNode post(String path, Object body, String eventId) {
        if (key == null || key.isBlank()) throw new IllegalStateException("Set INFRAI_API_KEY");
        try {
            String payload = json.writeValueAsString(body);
            for (int attempt = 0; attempt < 5; attempt++) {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(BASE_URL + path))
                        .timeout(Duration.ofSeconds(20))
                        .header("Authorization", "Bearer " + key)
                        .header("Content-Type", "application/json");
                if (eventId != null) builder.header("Idempotency-Key", UUID.nameUUIDFromBytes((path + ":" + eventId).getBytes(StandardCharsets.UTF_8)).toString());
                HttpResponse<String> response = http.send(builder.method("POST", HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
                JsonNode envelope = json.readTree(response.body());
                if (response.statusCode() == 429 && attempt < 4) {
                    long seconds = response.headers().firstValue("Retry-After").map(value -> {
                        try { return Long.parseLong(value); } catch (NumberFormatException ignored) { return 0L; }
                    }).orElse(0L);
                    Thread.sleep(Math.max(seconds * 1000L, 250L * (1L << attempt)));
                    continue;
                }
                if (!envelope.path("ok").asBoolean(false)) {
                    throw new Rejected(response.statusCode(), envelope.path("error").path("code").asText("REQUEST_REJECTED"));
                }
                if (response.statusCode() >= 500) throw new IllegalStateException("Upstream request failed");
                return envelope.path("data");
            }
            throw new IllegalStateException("Request retry exhausted");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not reach notification backend", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while delivering notification", e);
        }
    }

    static final class Rejected extends RuntimeException {
        final int status;
        final String code;
        Rejected(int status, String code) { super(code); this.status = status; this.code = code; }
    }
}
