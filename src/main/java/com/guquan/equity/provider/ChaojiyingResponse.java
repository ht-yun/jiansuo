package com.guquan.equity.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChaojiyingResponse {
    @JsonProperty("err_no")
    private int errNo;
    @JsonProperty("err_str")
    private String errStr;
    @JsonProperty("pic_id")
    private String picId;
    @JsonProperty("pic_str")
    private String picStr;
    @JsonProperty("md5")
    private String md5;
}
