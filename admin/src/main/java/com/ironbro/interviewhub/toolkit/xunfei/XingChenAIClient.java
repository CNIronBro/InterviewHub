package com.ironbro.interviewhub.toolkit.xunfei;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
@Component
public class XingChenAIClient {

    public String uploadFile(MultipartFile file, String apiKey, String apiSecret) throws Exception {
        URL url = new URL("https://xingchen-api.xf-yun.com/workflow/v1/upload_file");
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setDoInput(true);
        conn.setRequestProperty("Authorization", "Bearer " + apiKey + ":" + apiSecret);

        String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (OutputStream outputStream = conn.getOutputStream();
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8), true)) {

            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                    .append(file.getOriginalFilename()).append("\"\r\n");
            writer.append("Content-Type: application/octet-stream\r\n");
            writer.append("\r\n");
            writer.flush();

            try (InputStream inputStream = file.getInputStream()) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
            }
            outputStream.flush();
            writer.append("\r\n");
            writer.append("--").append(boundary).append("--").append("\r\n");
            writer.flush();
        }

        int responseCode = conn.getResponseCode();
        if (responseCode == HttpsURLConnection.HTTP_OK) {
            String responseBody = readResponseBody(conn);
            JSONObject jsonResponse = JSON.parseObject(responseBody);
            JSONObject data = jsonResponse.getJSONObject("data");
            if (data != null) {
                String fileUrl = data.getString("url");
                log.info("XingChen upload success, url={}", fileUrl);
                return fileUrl;
            }
        }
        throw new RuntimeException("File upload failed, status=" + responseCode);
    }

    public void chat(String input, String chatId, String history, boolean stream,
                     OutputStream outputStream, Consumer<String> callback,
                     String customApiKey, String customApiSecret, String customFlowId,
                     String fileUrl, Map<String, Object> extraParameters) throws Exception {
        URL url = new URL("https://xingchen-api.xf-yun.com/workflow/v1/chat/completions");
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setRequestProperty("Authorization", "Bearer " + customApiKey + ":" + customApiSecret);
        conn.setDoOutput(true);

        JSONObject requestBody = new JSONObject();
        requestBody.put("flow_id", customFlowId);
        requestBody.put("uid", "123");
        requestBody.put("stream", stream);
        requestBody.put("chat_id", chatId);
        requestBody.put("history", new ArrayList<>());
        JSONObject parameters = new JSONObject();
        if (fileUrl != null && !fileUrl.trim().isEmpty())
            parameters.put("USER_FILE", fileUrl);
        if (extraParameters != null) {
            for (Map.Entry<String, Object> entry : extraParameters.entrySet())
                if (entry.getValue() != null) parameters.put(entry.getKey(), entry.getValue());
        }
        parameters.put("AGENT_USER_INPUT", input);
        requestBody.put("parameters", parameters);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(requestBody.toJSONString().getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != HttpsURLConnection.HTTP_OK)
            throw new IOException("Chat failed, status=" + responseCode);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String payload = line.startsWith("data: ") ? line.substring(6).trim() : line;
                if (payload.startsWith("{") || "[DONE]".equals(payload)) {
                    callback.accept(payload);
                    outputStream.write(payload.getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                }
            }
        }
        conn.disconnect();
    }

    private String readResponseBody(HttpsURLConnection conn) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) response.append(line);
            return response.toString();
        }
    }
}