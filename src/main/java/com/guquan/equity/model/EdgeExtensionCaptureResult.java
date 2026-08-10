package com.guquan.equity.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class EdgeExtensionCaptureResult {
    private boolean completed;
    private String message;
    private CompanyAllSectionsView parsed;
    private EdgeExtensionTargetView nextTarget;
    private long cooldownMillis;
}
