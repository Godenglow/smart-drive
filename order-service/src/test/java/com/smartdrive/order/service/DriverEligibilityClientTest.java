package com.smartdrive.order.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.smartdrive.common.api.ApiException;
import com.smartdrive.common.api.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DriverEligibilityClientTest {
    @Test void checksNoticeAndOnlineWithInternalAuthentication() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NearbyDriverClient client = new NearbyDriverClient(builder, "internal-test-secret");
        expect(server, "{\"code\":0,\"data\":{\"online\":true,\"notified\":true}}");
        client.requireEligible(42, 8);
        server.verify();
        server.reset();
        expect(server, "{\"code\":0,\"data\":{\"online\":true,\"notified\":false}}");
        rejects(client, ErrorCode.FORBIDDEN);
        server.verify();
        server.reset();
        expect(server, "{\"code\":0,\"data\":{\"online\":false,\"notified\":true}}");
        rejects(client, ErrorCode.DRIVER_OFFLINE);
        server.verify();
        server.reset();
        expect(server, "{\"code\":0,\"data\":{\"online\":false,\"notified\":true}}");
        assertTrue(client.wasNotified(42, 8)); // Viewing a received notice does not require staying online.
        server.verify();
    }

    @Test void malformedUnavailableOrUnconfiguredDependencyFailsClosed() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NearbyDriverClient client = new NearbyDriverClient(builder, "internal-test-secret");
        for (String body : new String[]{"{}", "{\"code\":0,\"data\":{\"online\":true}}", "not json"}) {
            server.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            rejects(client, ErrorCode.SERVICE_UNAVAILABLE);
            server.verify();
            server.reset();
        }
        server.expect(anything()).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        rejects(client, ErrorCode.SERVICE_UNAVAILABLE);
        server.verify();
        rejects(new NearbyDriverClient(RestClient.builder(), ""), ErrorCode.SERVICE_UNAVAILABLE);
    }

    private void expect(MockRestServiceServer server, String body) {
        server.expect(requestTo("http://driver-service/internal/drivers/42/eligibility?orderId=8"))
                .andExpect(header("X-Internal-Token", "internal-test-secret"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void rejects(NearbyDriverClient client, ErrorCode code) {
        assertEquals(code, assertThrows(ApiException.class, () -> client.requireEligible(42, 8)).errorCode());
    }
}
