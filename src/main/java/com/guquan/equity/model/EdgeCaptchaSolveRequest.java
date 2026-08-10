package com.guquan.equity.model;

import lombok.Data;

@Data
public class EdgeCaptchaSolveRequest {
    private String jobId;
    private String imageBase64;
    private String previousPicId;
}
