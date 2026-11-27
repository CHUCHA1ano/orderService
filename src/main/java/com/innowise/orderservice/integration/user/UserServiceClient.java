package com.innowise.orderservice.integration.user;

import com.innowise.orderservice.exception.UserNotFoundException;
import com.innowise.orderservice.exception.UserServiceIntegrationException;
import com.innowise.orderservice.integration.user.dto.UserDto;
import com.innowise.orderservice.util.PiiMasker;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;

@Service
public class UserServiceClient {

    private static final Logger log = LoggerFactory.getLogger(UserServiceClient.class);

    private static final String API_KEY_HEADER = "X-Internal-Api-Key";

    private final RestClient restClient;

    public UserServiceClient(UserServiceProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder()
                .baseUrl(properties.url())
                .requestFactory(requestFactory)
                .defaultHeader(API_KEY_HEADER, properties.apiKey())
                .build();
    }

    @CircuitBreaker(name = "userService", fallbackMethod = "unavailableByEmail")
    public UserDto getUserByEmail(String email) {
        return call(() -> restClient.get()
                .uri("/api/internal/users/by-email?email={email}", email)
                .retrieve()
                .body(UserDto.class), "by-email", email);
    }

    @CircuitBreaker(name = "userService", fallbackMethod = "unavailableById")
    public UserDto getUserById(UUID id) {
        return call(() -> restClient.get()
                .uri("/api/internal/users/{id}", id)
                .retrieve()
                .body(UserDto.class), "by-id", id);
    }

    /**
     * Resilience4j invokes the fallback when the call is rejected because the
     * circuit is open (CallNotPermittedException). Business outcomes that already
     * carry a meaning (404) or were wrapped by {@link #call} are rethrown as is;
     * everything else becomes the same temporary-unavailability error, so an
     * open circuit is a 502, never a 500.
     */
    private UserDto unavailableByEmail(String email, Throwable ex) {
        log.warn("User service call rejected (lookup=by-email, key={}): {}",
                PiiMasker.maskEmails(email), ex.toString());
        return rethrowOrUnavailable(ex);
    }

    private UserDto unavailableById(UUID id, Throwable ex) {
        log.warn("User service call rejected (lookup=by-id, key={}): {}", id, ex.toString());
        return rethrowOrUnavailable(ex);
    }

    private static UserDto rethrowOrUnavailable(Throwable ex) {
        if (ex instanceof UserNotFoundException notFound) {
            throw notFound;
        }
        if (ex instanceof UserServiceIntegrationException integration) {
            throw integration;
        }
        throw new UserServiceIntegrationException("User service is temporarily unavailable", ex);
    }

    private UserDto call(Caller supplier, String lookup, Object key) {
        try {
            return supplier.get();
        } catch (RestClientResponseException ex) {
            log.warn("User service responded with error (lookup={}, key={}): status={}, body={}",
                    lookup, PiiMasker.maskEmails(String.valueOf(key)), ex.getStatusCode(),
                    PiiMasker.sanitizeBody(ex.getResponseBodyAsString()));
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new UserNotFoundException("User not found: " + key);
            }
            throw new UserServiceIntegrationException("User service is temporarily unavailable", ex);
        } catch (RestClientException ex) {
            log.warn("User service call failed (lookup={}, key={}): {}",
                    lookup, PiiMasker.maskEmails(String.valueOf(key)),
                    PiiMasker.sanitizeBody(ex.getMessage()));
            throw new UserServiceIntegrationException("User service is temporarily unavailable", ex);
        }
    }

    @FunctionalInterface
    private interface Caller {
        UserDto get();
    }
}
