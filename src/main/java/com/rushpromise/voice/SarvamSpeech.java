package com.rushpromise.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Component
class SarvamSpeech {
    static final int MAX_AUDIO_BYTES = 2_000_000;
    private static final URI SARVAM = URI.create("https://api.sarvam.ai");
    private static final Map<String, String> RECORDING_TYPES = Map.of(
            "audio/webm", "webm", "audio/mp4", "mp4", "audio/wav", "wav", "audio/ogg", "ogg");
    private final ObjectMapper json;
    private final String apiKey;
    private final MeterRegistry metrics;
    private final ProviderCircuit sttCircuit = new ProviderCircuit();
    private final ProviderCircuit ttsCircuit = new ProviderCircuit();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    SarvamSpeech(ObjectMapper json, MeterRegistry metrics, @Value("${SARVAM_API_KEY:}") String apiKey) {
        this.json = json;
        this.apiKey = apiKey;
        this.metrics = metrics;
    }

    boolean configured() {
        return !apiKey.isBlank();
    }

    Transcript transcribe(MultipartFile audio) {
        if (audio.isEmpty()) return new Transcript("", "");
        String type = audio.getContentType() == null ? "" : audio.getContentType().split(";", 2)[0]
                .strip().toLowerCase(Locale.ROOT);
        String extension = RECORDING_TYPES.get(type);
        if (extension == null || audio.getSize() > MAX_AUDIO_BYTES) {
            throw new IllegalArgumentException("Unsupported audio or recording larger than 2 MB");
        }
        String boundary = "rush-" + UUID.randomUUID();
        String intro = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n"
                + "saaras:v4\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"mode\"\r\n\r\n"
                + "transcribe\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\";"
                + " filename=\"turn." + extension + "\"\r\nContent-Type: " + type + "\r\n\r\n";
        try {
            JsonNode result = post("/speech-to-text", "multipart/form-data; boundary=" + boundary,
                    HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofString(intro),
                            HttpRequest.BodyPublishers.ofByteArray(audio.getBytes()),
                            HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n")));
            if (!result.path("transcript").isTextual()) throw new SpeechUnavailable();
            return new Transcript(result.path("transcript").asText().strip(), result.path("language_code").asText(""));
        } catch (IOException ex) {
            throw new SpeechUnavailable();
        }
    }

    String synthesize(String text, String language) {
        try {
            String request = json.writeValueAsString(Map.of(
                    "text", text, "target_language_code", language,
                    "speaker", "shubh_enhi_ads", "model", "bulbul:v4-flash",
                    "speech_sample_rate", 8000, "output_audio_codec", "wav"));
            JsonNode result = post("/text-to-speech", "application/json",
                    HttpRequest.BodyPublishers.ofString(request));
            String audio = result.path("audios").path(0).asText("");
            if (audio.isEmpty()) throw new SpeechUnavailable();
            return audio;
        } catch (IOException ex) {
            throw new SpeechUnavailable();
        }
    }

    private JsonNode post(String path, String type, HttpRequest.BodyPublisher body) throws IOException {
        if (!configured()) throw new SpeechUnavailable();
        boolean stt = path.equals("/speech-to-text");
        ProviderCircuit circuit = stt ? sttCircuit : ttsCircuit;
        String stage = stt ? "stt" : "tts";
        Timer.Sample elapsed = Timer.start(metrics);
        String outcome = "error";
        try {
            if (!circuit.allow()) {
                outcome = "circuit_open";
                throw new SpeechUnavailable();
            }
            HttpRequest request = HttpRequest.newBuilder(SARVAM.resolve(path))
                    .header("api-subscription-key", apiKey)
                    .header("Content-Type", type)
                    .timeout(Duration.ofSeconds(7))
                    .POST(body)
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status >= 500 || status == 429) circuit.failed();
            else circuit.healthy();
            if (status != 200) throw new SpeechUnavailable();
            JsonNode result = json.readTree(response.body());
            outcome = "success";
            return result;
        } catch (IOException ex) {
            circuit.failed();
            throw ex;
        } catch (InterruptedException ex) {
            circuit.failed();
            Thread.currentThread().interrupt();
            throw new SpeechUnavailable();
        } finally {
            elapsed.stop(metrics.timer("rush.voice.provider", "stage", stage, "outcome", outcome));
        }
    }

    static final class ProviderCircuit {
        private int failures;
        private Instant nextProbe = Instant.EPOCH;
        private boolean probing;

        synchronized boolean allow() {
            if (failures < 3) return true;
            if (probing || Instant.now().isBefore(nextProbe)) return false;
            probing = true;
            return true;
        }

        synchronized void failed() {
            if (++failures >= 3) nextProbe = Instant.now().plusSeconds(15);
            probing = false;
        }

        synchronized void healthy() {
            failures = 0;
            probing = false;
        }
    }

    record Transcript(String text, String language) {}
    static final class SpeechUnavailable extends RuntimeException {}
}
