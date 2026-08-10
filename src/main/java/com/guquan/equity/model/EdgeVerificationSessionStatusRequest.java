package com.guquan.equity.model;

import java.util.List;
import lombok.Data;

@Data
public class EdgeVerificationSessionStatusRequest {
    private String status;
    private String message;
    private String currentCompanyName;
    private List<EdgeVerificationCandidateView> candidates;
}
