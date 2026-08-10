package com.guquan.equity.model;

import lombok.Builder;
import lombok.Data;

/**
 * A safe, display-only enterprise candidate returned by the extension.
 * It deliberately contains no account, cookie, or internal GSXT URL data.
 */
@Data
@Builder
public class EdgeVerificationCandidateView {
    private String candidateId;
    private String companyName;
    private String creditCode;
    private String legalPerson;
    private String registrationStatus;
    private String registeredAddress;
    private String summary;
}
