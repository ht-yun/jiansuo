package com.guquan.equity.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.guquan.equity.model.EdgeCaptchaSolveResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class ChaojiyingCaptchaClientTest {

    private final GsxtCaptchaProperties properties = new GsxtCaptchaProperties();
    private final RestTemplate restTemplate = new RestTemplate();
    private final ChaojiyingCaptchaClient client = new ChaojiyingCaptchaClient(properties, restTemplate);
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        server = MockRestServiceServer.bindTo(restTemplate).build();
        properties.setEnabled(true);
        properties.setUsername("user");
        properties.setPassword("pass");
        properties.setSoftId("soft");
    }

    @Test
    void returnsUnavailableWhenNotConfigured() {
        properties.setEnabled(false);

        EdgeCaptchaSolveResult result = client.solve("aGVsbG8=", null);

        assertThat(result.isSolved()).isFalse();
        assertThat(result.getMessage()).contains("未配置");
    }

    @Test
    void parsesTextSolution() {
        properties.setCodeType("1005");
        server.expect(requestTo("http://upload.chaojiying.net/Upload/Processing.php"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"err_no\":0,\"err_str\":\"OK\",\"pic_id\":\"pic-1\",\"pic_str\":\"ABCD\"}",
                        MediaType.APPLICATION_JSON));

        EdgeCaptchaSolveResult result = client.solve("aGVsbG8=", null);

        assertThat(result.isSolved()).isTrue();
        assertThat(result.getText()).isEqualTo("ABCD");
        assertThat(result.getPicId()).isEqualTo("pic-1");
        server.verify();
    }

    @Test
    void parsesCoordinateSolution() {
        properties.setCodeType("2005");
        server.expect(requestTo("http://upload.chaojiying.net/Upload/Processing.php"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"err_no\":0,\"err_str\":\"OK\",\"pic_id\":\"pic-2\",\"pic_str\":\"120,45|260,88\"}",
                        MediaType.APPLICATION_JSON));

        EdgeCaptchaSolveResult result = client.solve("aGVsbG8=", null);

        assertThat(result.isSolved()).isTrue();
        assertThat(result.getPoints()).hasSize(2);
        assertThat(result.getPoints().get(0).getX()).isEqualTo(120);
        assertThat(result.getPoints().get(1).getY()).isEqualTo(88);
    }

    @Test
    void reportsPreviousPictureBeforeRetrying() {
        properties.setCodeType("1005");
        server.expect(requestTo("http://upload.chaojiying.net/Upload/ReportError.php"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"err_no\":0,\"err_str\":\"OK\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://upload.chaojiying.net/Upload/Processing.php"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"err_no\":0,\"err_str\":\"OK\",\"pic_id\":\"pic-3\",\"pic_str\":\"1234\"}",
                        MediaType.APPLICATION_JSON));

        EdgeCaptchaSolveResult result = client.solve("aGVsbG8=", "pic-2");

        assertThat(result.isSolved()).isTrue();
        assertThat(result.getText()).isEqualTo("1234");
        server.verify();
    }

    @Test
    void surfacesProviderError() {
        server.expect(requestTo("http://upload.chaojiying.net/Upload/Processing.php"))
                .andRespond(withSuccess(
                        "{\"err_no\":1002,\"err_str\":\"余额不足\"}", MediaType.APPLICATION_JSON));

        EdgeCaptchaSolveResult result = client.solve("aGVsbG8=", null);

        assertThat(result.isSolved()).isFalse();
        assertThat(result.getMessage()).contains("余额不足");
    }
}
