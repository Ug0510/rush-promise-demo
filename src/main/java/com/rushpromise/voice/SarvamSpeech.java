package com.rushpromise.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    SarvamSpeech(ObjectMapper json, @Value("${SARVAM_API_KEY:}") String apiKey) {
        this.json = json;
        this.apiKey = apiKey;
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
        HttpRequest request = HttpRequest.newBuilder(SARVAM.resolve(path))
                .header("api-subscription-key", apiKey)
                .header("Content-Type", type)
                .timeout(Duration.ofSeconds(7))
                .POST(body)
                .build();
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) throw new SpeechUnavailable();
            return json.readTree(response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SpeechUnavailable();
        }
    }

    record Transcript(String text, String language) {}
    static final class SpeechUnavailable extends RuntimeException {}
}
