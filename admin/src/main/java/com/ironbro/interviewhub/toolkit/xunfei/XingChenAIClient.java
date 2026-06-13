package com.ironbro.interviewhub.toolkit.xunfei;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.net.ssl.HttpsURLConnection;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;

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