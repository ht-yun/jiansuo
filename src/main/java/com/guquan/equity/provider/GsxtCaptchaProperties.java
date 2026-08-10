package com.guquan.equity.provider;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Captcha handling configuration. Test mode is only for automated tests;
 * production defaults to the manual verification flow unless Super Eagle
 * solving is explicitly enabled with credentials.
 */
@Data
@ConfigurationProperties(prefix = "gsxt-captcha")
public class GsxtCaptchaProperties {

    private boolean testMode;
    private boolean enabled;
    private String username;
    private String password;
    private String softId;
    private String codeType = "2005";
    private int maxAttempts = 3;
    private String apiUrl = "http://upload.chaojiying.net/Upload/Processing.php";
    private String reportErrorUrl = "http://upload.chaojiying.net/Upload/ReportError.php";

}
