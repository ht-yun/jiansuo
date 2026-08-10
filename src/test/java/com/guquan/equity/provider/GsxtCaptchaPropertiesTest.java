package com.guquan.equity.provider;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GsxtCaptchaPropertiesTest {

    @Test
    void captchaTestModeIsDisabledByDefault() {
        assertThat(new GsxtCaptchaProperties().isTestMode()).isFalse();
    }

    @Test
    void captchaTestModeCanBeEnabledExplicitlyForTests() {
        GsxtCaptchaProperties properties = new GsxtCaptchaProperties();
        properties.setTestMode(true);

        assertThat(properties.isTestMode()).isTrue();
    }

    @Test
    void superEagleSolvingIsDisabledByDefault() {
        GsxtCaptchaProperties properties = new GsxtCaptchaProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getUsername()).isNull();
        assertThat(properties.getPassword()).isNull();
        assertThat(properties.getSoftId()).isNull();
        assertThat(properties.getCodeType()).isEqualTo("2005");
        assertThat(properties.getMaxAttempts()).isEqualTo(3);
    }
}
