package com.guquan.equity.provider;

import com.guquan.equity.model.EdgeCaptchaPoint;
import com.guquan.equity.model.EdgeCaptchaSolveResult;
import com.guquan.equity.model.EdgeCaptchaStatusView;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Client for the Super Eagle (超级鹰) captcha-solving API. Credentials are
 * read from configuration only and are never logged or returned to clients.
 */
@Service
public class ChaojiyingCaptchaClient {

    private static final Set<String> COORDINATE_CODE_TYPES = Set.of("2004", "2005", "2006", "2010");
    private static final Set<String> SLIDER_CODE_TYPES = Set.of("4005");

    private final GsxtCaptchaProperties properties;
    private final RestTemplate restTemplate;

    public ChaojiyingCaptchaClient(GsxtCaptchaProperties properties, RestTemplate restTemplate) {
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    public EdgeCaptchaStatusView status() {
        return EdgeCaptchaStatusView.builder()
                .enabled(properties.isEnabled())
                .configured(isConfigured())
                .codeType(properties.getCodeType())
                .build();
    }

    public EdgeCaptchaSolveResult solve(String imageBase64, String previousPicId) {
        if (!properties.isEnabled() || !isConfigured()) {
            return EdgeCaptchaSolveResult.unavailable();
        }
        if (StringUtils.hasText(previousPicId)) {
            reportError(previousPicId);
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("user", properties.getUsername());
        form.add("pass", properties.getPassword());
        form.add("softid", properties.getSoftId());
        form.add("codetype", properties.getCodeType());
        form.add("file_base64", imageBase64);
        try {
            ChaojiyingResponse response = restTemplate.postForObject(
                    properties.getApiUrl(), form, ChaojiyingResponse.class);
            if (response == null || response.getErrNo() != 0) {
                return EdgeCaptchaSolveResult.failure(
                        response == null ? "超级鹰未返回结果" : response.getErrStr());
            }
            return toResult(response);
        } catch (RestClientException exception) {
            return EdgeCaptchaSolveResult.failure("超级鹰接口调用失败：" + safeMessage(exception));
        }
    }

    private boolean isConfigured() {
        return StringUtils.hasText(properties.getUsername())
                && StringUtils.hasText(properties.getPassword())
                && StringUtils.hasText(properties.getSoftId());
    }

    private EdgeCaptchaSolveResult toResult(ChaojiyingResponse response) {
        String picStr = response.getPicStr() == null ? "" : response.getPicStr().trim();
        String codeType = properties.getCodeType();
        if (COORDINATE_CODE_TYPES.contains(codeType)) {
            return EdgeCaptchaSolveResult.success(response.getPicId(), null, parsePoints(picStr), null);
        }
        if (SLIDER_CODE_TYPES.contains(codeType)) {
            return EdgeCaptchaSolveResult.success(response.getPicId(), null, List.of(), parseInteger(picStr));
        }
        return EdgeCaptchaSolveResult.success(response.getPicId(), picStr, List.of(), null);
    }

    private List<EdgeCaptchaPoint> parsePoints(String picStr) {
        List<EdgeCaptchaPoint> points = new ArrayList<>();
        for (String pair : picStr.split("[|;\\s]+")) {
            String[] parts = pair.split(",");
            if (parts.length != 2) continue;
            try {
                points.add(new EdgeCaptchaPoint(
                        Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())));
            } catch (NumberFormatException ignored) {
                // Ignore malformed coordinate pairs from the provider.
            }
        }
        return points;
    }

    private Integer parseInteger(String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private void reportError(String picId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("user", properties.getUsername());
        form.add("pass", properties.getPassword());
        form.add("softid", properties.getSoftId());
        form.add("pic_id", picId);
        try {
            restTemplate.postForObject(properties.getReportErrorUrl(), form, ChaojiyingResponse.class);
        } catch (RestClientException ignored) {
            // Reporting is best-effort; a failed report must not block the next solve.
        }
    }

    private String safeMessage(RestClientException exception) {
        String message = exception.getMessage();
        if (!StringUtils.hasText(message)) return "网络请求失败";
        return message.length() <= 300 ? message : message.substring(0, 300);
    }
}
