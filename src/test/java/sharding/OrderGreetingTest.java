package sharding;

import java.util.concurrent.TimeUnit;

import sharding.shards.OrderShard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@OrderShard
@SpringBootTest
@AutoConfigureMockMvc
class OrderGreetingTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void greetsOrder() throws Exception {
        var result = mockMvc.perform(get("/api/greetings/Order"));

        TimeUnit.SECONDS.sleep(60);

        result.andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.message").value("Hello, Order!"));
    }
}
