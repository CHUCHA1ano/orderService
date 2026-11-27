package com.innowise.orderservice.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;
import com.innowise.orderservice.model.entity.Item;
import com.innowise.orderservice.repository.ItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Singleton-container pattern: the Postgres container and the in-process WireMock
 * server are started once per JVM and shared by all integration test classes.
 * <p>
 * WireMock replaces the real userService: the client under test performs actual
 * HTTP calls against it (timeouts, API key header, circuit breaker included).
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractOrderIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:15-alpine"))
            .withDatabaseName("orderdb")
            .withUsername("postgres")
            .withPassword("postgres");

    static final WireMockServer WIREMOCK = new WireMockServer(options().dynamicPort());

    static {
        POSTGRES.start();
        WIREMOCK.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.integration.user-service.url", WIREMOCK::baseUrl);
        registry.add("app.integration.user-service.api-key", () -> "test-internal-key");
        registry.add("spring.jpa.show-sql", () -> "false");
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ItemRepository itemRepository;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetWireMock() {
        WIREMOCK.resetAll();
    }

    /**
     * Every test starts from an empty schema. order_items goes first because the
     * foreign keys to orders and items are RESTRICT.
     */
    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("delete from order_items");
        jdbcTemplate.execute("delete from orders");
        jdbcTemplate.execute("delete from items");
    }

    protected static String userJson(UUID id, String email) {
        return userJson(id, email, true);
    }

    protected static String userJson(UUID id, String email, boolean active) {
        return """
                {"id":"%s","name":"John","surname":"Doe","email":"%s","active":%s}
                """.formatted(id, email, active);
    }

    protected void stubUserByEmail(String email, UUID userId) {
        stubUserByEmail(email, userId, true);
    }

    protected void stubUserByEmail(String email, UUID userId, boolean active) {
        WIREMOCK.stubFor(get(urlPathEqualTo("/api/internal/users/by-email"))
                .withQueryParam("email", equalTo(email))
                .willReturn(okJson(userJson(userId, email, active))));
    }

    protected void stubUserById(UUID userId, String email) {
        stubUserById(userId, email, true);
    }

    protected void stubUserById(UUID userId, String email, boolean active) {
        WIREMOCK.stubFor(get(urlPathEqualTo("/api/internal/users/" + userId))
                .willReturn(okJson(userJson(userId, email, active))));
    }

    protected void stubUserLookupError(int status) {
        WIREMOCK.stubFor(get(urlPathEqualTo("/api/internal/users/by-email"))
                .willReturn(aResponse().withStatus(status)));
    }

    protected void stubUserByIdError(int status) {
        WIREMOCK.stubFor(get(urlMatching("/api/internal/users/[0-9a-fA-F-]{36}"))
                .willReturn(aResponse().withStatus(status)));
    }

    protected Item createItem(String name, String price) {
        return itemRepository.save(Item.builder().name(name).price(new BigDecimal(price)).build());
    }

    protected RequestPostProcessor userJwt(UUID userId, String email) {
        return jwt().jwt(j -> j.claim("user_id", userId.toString()).claim("email", email))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    protected RequestPostProcessor adminJwt() {
        return jwt().jwt(j -> j.claim("user_id", UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    protected String createOrder(RequestPostProcessor auth, UUID itemId, int quantity) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/orders")
                        .with(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"itemId":"%s","quantity":%d}]}
                                """.formatted(itemId, quantity)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }
}
