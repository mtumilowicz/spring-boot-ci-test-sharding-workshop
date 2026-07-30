package com.example.sharding;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@Tag("payment")
@SpringBootTest
@AutoConfigureMockMvc
class PaymentGreetingTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void failsDeterministically() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/greetings/Payment")).andReturn();

        TimeUnit.SECONDS.sleep(60);

        fail("Intentional payment shard failure after greeting: "
                + result.getResponse().getContentAsString());
    }
}
