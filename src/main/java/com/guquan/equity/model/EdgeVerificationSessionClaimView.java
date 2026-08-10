package com.guquan.equity.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class EdgeVerificationSessionClaimView {
    private String sessionId;
    private String jobId;
    private String accessToken;
    private String officialUrl;
}
