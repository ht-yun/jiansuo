package com.guquan.equity.model;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class EdgeVerificationSessionView {
    private String sessionId;
    private String jobId;
    private String status;
    private String message;
    private String currentCompanyName;
    private List<EdgeVerificationCandidateView> candidates;
    private String officialUrl;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
}
