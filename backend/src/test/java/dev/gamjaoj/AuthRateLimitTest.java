package dev.gamjaoj;
import dev.gamjaoj.config.AuthRateLimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.assertThat;

class AuthRateLimitTest {
    @Test
    void limitsAuthRequestsPerAddressWithoutTrustingForwardedHeaders() throws Exception {
        var filter = new AuthRateLimit();
        for (int i = 0; i < 31; i++) {
            var request = new MockHttpServletRequest("POST", "/api/auth/login");
            request.setServletPath("/api/auth/login");
            request.setRemoteAddr("192.0.2.1");
            request.addHeader("X-Forwarded-For", "192.0.2." + i);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> {});
            assertThat(response.getStatus()).isEqualTo(i == 30 ? 429 : 200);
        }
        var other = new MockHttpServletRequest("POST", "/api/auth/signup");
        other.setServletPath("/api/auth/signup"); other.setRemoteAddr("192.0.2.100");
        var response = new MockHttpServletResponse();
        filter.doFilter(other, response, (req, res) -> {});
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
