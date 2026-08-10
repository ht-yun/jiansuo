package com.guquan.equity.provider;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "gsxt-access-control")
public class GsxtAccessControlProperties {

    /** Minimum wait after a company is opened or collected. */
    private int minCompanyIntervalSeconds = 45;
    /** Randomized upper bound for the wait between companies. */
    private int maxCompanyIntervalSeconds = 90;
    /** Successful companies allowed in a rolling one-hour window. */
    private int maxCompaniesPerHour = 20;
    /** Forced pause after the official site reports an access block. */
    private int blockedCooldownHours = 12;
}
