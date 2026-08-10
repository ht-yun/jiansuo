package com.guquan.equity.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class EdgeCaptchaStatusView {
    private boolean enabled;
    private boolean configured;
    private String codeType;
}
