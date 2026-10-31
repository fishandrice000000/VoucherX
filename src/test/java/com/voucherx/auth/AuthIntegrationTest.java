package com.voucherx.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voucherx.AbstractIntegrationTest;
import com.voucherx.common.redis.RedisKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void phoneCodeLoginAndTokenLifecycle() throws Exception {
        String phone = "13800000001";
        String code = sendCode(phone);

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","code":"%s"}
                                """.formatted(phone, code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.userId").isNumber())
                .andReturn();

        JsonNode loginJson = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String token = loginJson.path("data").path("token").asText();
        long userId = loginJson.path("data").path("userId").asLong();

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(userId))
                .andExpect(jsonPath("$.data.phone").value(phone));

        String tokenKey = RedisKeys.token(token);
        redisTemplate.expire(tokenKey, 5, TimeUnit.SECONDS);
        mockMvc.perform(get("/api/auth/me").header("Authorization", token))
                .andExpect(status().isOk());
        assertThat(redisTemplate.getExpire(tokenKey, TimeUnit.SECONDS)).isGreaterThan(60);

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void wrongAndExpiredCodeAreRejected() throws Exception {
        String phone = "13800000002";
        String code = sendCode(phone);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","code":"000000"}
                                """.formatted(phone)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40010));

        // 正确验证码仍可继续使用，说明错误尝试没有消耗验证码。
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","code":"%s"}
                                """.formatted(phone, code)))
                .andExpect(status().isOk());

        redisTemplate.delete(RedisKeys.authCode(phone));
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","code":"%s"}
                                """.formatted(phone, code)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40010));
    }

    @Test
    void unauthorizedRequestAndCodeAbuseAreBlocked() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());

        String phone = "13800000003";
        sendCode(phone);
        mockMvc.perform(post("/api/auth/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s"}
                                """.formatted(phone)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(42910));
    }

    private String sendCode(String phone) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s"}
                                """.formatted(phone)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").isNotEmpty())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("code").asText();
    }
}
