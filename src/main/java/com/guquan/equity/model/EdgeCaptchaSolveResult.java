package com.guquan.equity.model;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class EdgeCaptchaSolveResult {
    private boolean solved;
    private String message;
    private String text;
    private List<EdgeCaptchaPoint> points;
    private Integer dragDistance;
    private String picId;

    public static EdgeCaptchaSolveResult unavailable() {
        return EdgeCaptchaSolveResult.builder()
                .solved(false)
                .message("超级鹰验证码识别未启用或未配置")
                .points(List.of())
                .build();
    }

    public static EdgeCaptchaSolveResult failure(String message) {
        return EdgeCaptchaSolveResult.builder()
                .solved(false)
                .message(message)
                .points(List.of())
                .build();
    }

    public static EdgeCaptchaSolveResult success(String picId, String text,
            List<EdgeCaptchaPoint> points, Integer dragDistance) {
        return EdgeCaptchaSolveResult.builder()
                .solved(true)
                .message("识别成功")
                .text(text)
                .points(points == null ? List.of() : points)
                .dragDistance(dragDistance)
                .picId(picId)
                .build();
    }
}
