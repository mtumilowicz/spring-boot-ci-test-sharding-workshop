package com.example.sharding;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/greetings")
class GreetingController {

    @GetMapping("/{name}")
    GreetingResponse greet(@PathVariable String name) {
        return new GreetingResponse("Hello, " + name + "!");
    }
}
