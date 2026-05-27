package com.insightspark.controller;

import com.insightspark.common.ApiResponse;
import com.insightspark.service.PythonAiService;
import com.insightspark.service.VoicePreferenceService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/voice")
@CrossOrigin
public class VoiceController {

    @Autowired
    private PythonAiService pythonAiService;

    @Autowired
    private VoicePreferenceService voicePreferenceService;

    @PostMapping("/tts")
    public ApiResponse<Map<String, Object>> textToSpeech(@RequestBody Map<String, Object> request) {
        String text = String.valueOf(request.getOrDefault("text", "")).trim();
        if (text.isBlank()) {
            return ApiResponse.badRequest("播报文本不能为空");
        }

        String voiceGender = String.valueOf(request.getOrDefault("voiceGender", "female")).trim();
        String locale = String.valueOf(request.getOrDefault("locale", "zh-CN")).trim();
        String voiceLocale = String.valueOf(request.getOrDefault("voiceLocale", locale)).trim();
        Double rate = parseDouble(request.get("rate")).orElse(1.0D);

        return pythonAiService.textToSpeech(text, voiceGender, locale, voiceLocale, rate)
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.badRequest("云端 TTS 服务不可用或未返回音频"));
    }

    @PostMapping("/tts-url")
    public ApiResponse<Map<String, Object>> textToSpeechUrl(@RequestBody Map<String, Object> request) {
        String text = String.valueOf(request.getOrDefault("text", "")).trim();
        if (text.isBlank()) {
            return ApiResponse.badRequest("播报文本不能为空");
        }

        String voiceGender = String.valueOf(request.getOrDefault("voiceGender", "female")).trim();
        String locale = String.valueOf(request.getOrDefault("locale", "zh-CN")).trim();
        String voiceLocale = String.valueOf(request.getOrDefault("voiceLocale", locale)).trim();
        Double rate = parseDouble(request.get("rate")).orElse(1.0D);

        return pythonAiService.textToSpeechUrl(text, voiceGender, locale, voiceLocale, rate)
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.badRequest("云端 TTS 服务不可用或未返回音频地址"));
    }

    @PostMapping("/asr")
    public ApiResponse<Map<String, Object>> recognizeSpeech(@RequestBody Map<String, Object> request) {
        String audioBase64 = String.valueOf(request.getOrDefault("audioBase64", "")).trim();
        if (audioBase64.isBlank()) {
            return ApiResponse.badRequest("音频不能为空");
        }

        String locale = String.valueOf(request.getOrDefault("locale", "zh-CN")).trim();
        return pythonAiService.recognizeSpeech(audioBase64, locale)
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.badRequest("云端语音识别服务不可用或未返回文本"));
    }

    @PostMapping("/tts-stream")
    public void textToSpeechStream(@RequestBody Map<String, Object> request, HttpServletResponse response) throws IOException {
        String text = String.valueOf(request.getOrDefault("text", "")).trim();
        if (text.isBlank()) {
            throw new IllegalArgumentException("播报文本不能为空");
        }

        String voiceGender = String.valueOf(request.getOrDefault("voiceGender", "female")).trim();
        String locale = String.valueOf(request.getOrDefault("locale", "zh-CN")).trim();
        String voiceLocale = String.valueOf(request.getOrDefault("voiceLocale", locale)).trim();
        Double rate = parseDouble(request.get("rate")).orElse(1.0D);
        Double volume = parseDouble(request.get("volume")).orElse(0.85D);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", text);
        payload.put("voiceGender", voiceGender);
        payload.put("locale", locale);
        payload.put("voiceLocale", voiceLocale);
        payload.put("rate", rate);
        payload.put("volume", volume);

        pythonAiService.streamTextToSpeech(payload, response);
    }

    @GetMapping("/preferences")
    public ApiResponse<Map<String, Object>> getPreferences() {
        return ApiResponse.success(voicePreferenceService.getCurrentUserPreference());
    }

    @PostMapping("/preferences")
    public ApiResponse<Map<String, Object>> savePreferences(@RequestBody Map<String, Object> request) {
        return ApiResponse.success(voicePreferenceService.saveCurrentUserPreference(request));
    }

    private Optional<Double> parseDouble(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof Number number) {
            return Optional.of(number.doubleValue());
        }
        try {
            return Optional.of(Double.parseDouble(String.valueOf(value).trim()));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }
}
